#pragma once
#include <algorithm>
#include <cstddef>
#include <vector>

namespace het::continuation {
// Each edge is a possible transfer to the next label. -1 leaves the known graph.
class ChoiceJoins {
    struct Label {
        int firstTarget = -1;
        std::size_t targetCount = 0;
        std::vector<int> predecessors;
    };
    std::vector<Label> labels;
public:
    explicit ChoiceJoins(std::size_t size) : labels(size) {}

    void add(int from, int to) {
        auto& label = labels[from];
        if (label.targetCount == 0) label.firstTarget = to;
        ++label.targetCount;
        if (to >= 0) labels[to].predecessors.push_back(from);
    }

    int nearest(const std::vector<int>& starts) const {
        if (starts.empty() || std::find(starts.begin(), starts.end(), -1) != starts.end()) return -1;
        if (starts.size() == 1) return starts.front();
        // A mandatory join occurs on every path, including this witness path.
        // Try its labels in traversal order. Prove each candidate backwards:
        // a predecessor must reach it only when every successor must reach it.
        std::vector<bool> visited(labels.size());
        for (int at = starts.front(); at >= 0 && !visited[at];) {
            visited[at] = true;
            std::vector<std::size_t> reachedEdges(labels.size());
            std::vector<bool> mustReach(labels.size());
            std::vector<int> work{at};
            mustReach[at] = true;
            for (std::size_t i = 0; i < work.size(); ++i) {
                for (int prev : labels[work[i]].predecessors) {
                    if (!mustReach[prev] && ++reachedEdges[prev] == labels[prev].targetCount) {
                        mustReach[prev] = true;
                        work.push_back(prev);
                    }
                }
            }
            if (std::all_of(starts.begin(), starts.end(), [&](int start) { return mustReach[start]; }))
                return at;
            at = labels[at].firstTarget;
        }
        return -1;
    }
};
} // namespace het::continuation
