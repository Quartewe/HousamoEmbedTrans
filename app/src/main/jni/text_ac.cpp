#include "housamo.hpp"
#include <array>
#include <queue>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <memory>
#include <atomic>
#include <utility>
#include <algorithm>

namespace {

struct MatchPattern {
    std::string canonical; 
    MatchKind kind;
};

struct MatchHit {
    size_t begin = 0;
    size_t end = 0;
    int pattern_id = -1;
};

struct AcNode {
    std::array<int, 256> next;
    int fail = 0;
    std::vector<int> out;

    AcNode() {
        next.fill(-1);
    }
};

class AhoCorasick {
public:
    void AddPattern(MatchPattern pattern) {
        int pattern_id = static_cast<int>(patterns_.size());
        patterns_.push_back(std::move(pattern));

        int state = 0;
        for (unsigned char ch : patterns_[pattern_id].canonical) {
            int& next_state = nodes_[state].next[ch];
            if (next_state == -1) {
                next_state = static_cast<int>(nodes_.size());
                nodes_.emplace_back();
            }
            state = next_state;
        }

        nodes_[state].out.push_back(pattern_id);
    }

    void Build() {
        std::queue<int> q;

        for (int c = 0; c < 256; ++c) {
            int child = nodes_[0].next[c];

            if (child == -1) {
                nodes_[0].next[c] = 0;
            } else {
                nodes_[child].fail = 0;
                q.push(child);
            }
        }

        while (!q.empty()) {
            int state = q.front();
            q.pop();

            int fail_state = nodes_[state].fail;

            // 继承 fail 状态的输出。
            const auto& fail_out = nodes_[fail_state].out;
            nodes_[state].out.insert(
                nodes_[state].out.end(),
                fail_out.begin(),
                fail_out.end()
            );

            for (int c = 0; c < 256; ++c) {
                int child = nodes_[state].next[c];

                if (child == -1) {
                    nodes_[state].next[c] = nodes_[fail_state].next[c];
                } else {
                    nodes_[child].fail = nodes_[fail_state].next[c];
                    q.push(child);
                }
            }
        }

        built_ = true;
    }

    std::vector<MatchHit> Scan(const std::string& text) const {
        std::vector<MatchHit> hits;
        int state = 0;

        for (size_t i = 0; i < text.size(); ++i) {
            unsigned char ch = static_cast<unsigned char>(text[i]);
            state = nodes_[state].next[ch];

            for (int pattern_id : nodes_[state].out) {
                const auto& pattern = patterns_[pattern_id];
                size_t len = pattern.canonical.size();

                MatchHit hit;
                hit.begin = i + 1 - len;
                hit.end = i + 1;
                hit.pattern_id = pattern_id;

                hits.push_back(hit);
            }
        }

        return hits;
    }

    const MatchPattern& Pattern(int id) const {
        return patterns_[id];
    }

private:
    std::vector<AcNode> nodes_{AcNode{}};
    std::vector<MatchPattern> patterns_;
    bool built_ = false;
};

std::atomic<bool> g_text_ac_ready = false;
std::mutex g_text_ac_mutex;
std::shared_ptr<const AhoCorasick> g_text_ac;

bool DecodeUtf8At(const std::string& text, size_t pos, char32_t* out) {
    if (out == nullptr || pos >= text.size()) {
        return false;
    }

    unsigned char c0 = static_cast<unsigned char>(text[pos]);

    if (c0 < 0x80) {
        *out = c0;
        return true;
    }

    if ((c0 & 0xE0) == 0xC0 && pos + 1 < text.size()) {
        unsigned char c1 = static_cast<unsigned char>(text[pos + 1]);
        if ((c1 & 0xC0) != 0x80) return false;
        *out = ((c0 & 0x1F) << 6) | (c1 & 0x3F);
        return true;
    }

    if ((c0 & 0xF0) == 0xE0 && pos + 2 < text.size()) {
        unsigned char c1 = static_cast<unsigned char>(text[pos + 1]);
        unsigned char c2 = static_cast<unsigned char>(text[pos + 2]);
        if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80) return false;
        *out = ((c0 & 0x0F) << 12)
             | ((c1 & 0x3F) << 6)
             |  (c2 & 0x3F);
        return true;
    }

    if ((c0 & 0xF8) == 0xF0 && pos + 3 < text.size()) {
        unsigned char c1 = static_cast<unsigned char>(text[pos + 1]);
        unsigned char c2 = static_cast<unsigned char>(text[pos + 2]);
        unsigned char c3 = static_cast<unsigned char>(text[pos + 3]);
        if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) return false;
        *out = ((c0 & 0x07) << 18)
             | ((c1 & 0x3F) << 12)
             | ((c2 & 0x3F) << 6)
             |  (c3 & 0x3F);
        return true;
    }

    return false;
}

bool DecodePrevUtf8(const std::string& text, size_t pos, char32_t* out) {
    if (pos == 0 || pos > text.size()) {
        return false;
    }

    size_t start = pos - 1;
    while (start > 0 && (static_cast<unsigned char>(text[start]) & 0xC0) == 0x80) {
        --start;
    }

    return DecodeUtf8At(text, start, out);
}

bool DecodeNextUtf8(const std::string& text, size_t pos, char32_t* out) {
    return DecodeUtf8At(text, pos, out);
}

bool IsKatakanaLike(char32_t cp) {
    if (cp >= 0x30A0 && cp <= 0x30FF) return true;
    if (cp >= 0x31F0 && cp <= 0x31FF) return true;
    if (cp >= 0xFF66 && cp <= 0xFF9D) return true;
    if (cp == 0xFF70) return true;
    return false;
}

bool TouchesKatakanaBoundary(const std::string& text, size_t begin, size_t end) {
    char32_t prev = 0;
    char32_t next = 0;

    if (DecodePrevUtf8(text, begin, &prev) && IsKatakanaLike(prev)) {
        return true;
    }

    if (DecodeNextUtf8(text, end, &next) && IsKatakanaLike(next)) {
        return true;
    }

    return false;
}

} // namespace

void StartAcInit(ACInit ac_init) {
    std::thread([init = std::move(ac_init)]() mutable {
    auto ac = std::make_shared<AhoCorasick>();

    for (const auto& name : init.char_list) {
        if (name.empty()) {continue;}

        MatchPattern pattern;
        pattern.canonical = name;
        pattern.kind = MatchKind::character;

        ac->AddPattern(std::move(pattern));
    }

    for (const auto& term : init.term_list) {
        if (term.empty()) {continue;}

        MatchPattern pattern;
        pattern.canonical = term;
        pattern.kind = MatchKind::term;

        ac->AddPattern(std::move(pattern));
    }

    ac->Build();

    {
    std::lock_guard<std::mutex> lock(g_text_ac_mutex);
    g_text_ac = std::move(ac);
    g_text_ac_ready.store(true, std::memory_order_release);
    }

    LOGI("[TextAC] ready char=%zu term=%zu",
     init.char_list.size(),
     init.term_list.size());
    }).detach();
}

bool IsAcReady() {
    return g_text_ac_ready.load(std::memory_order_acquire);
}

std::vector<AcHit> AcScan(const std::string& text) {
    std::shared_ptr<const AhoCorasick> ac;

    {
        std::lock_guard<std::mutex> lock(g_text_ac_mutex);
        ac = g_text_ac;
    }

    if (!ac) return {};

    auto hits = ac->Scan(text);

    std::vector<AcHit> out;
    out.reserve(hits.size());

    for (const auto& hit : hits) {
        const auto& pattern = ac->Pattern(hit.pattern_id);

        AcHit ac_hit;
        ac_hit.text = pattern.canonical;
        ac_hit.kind = pattern.kind;
        if (pattern.kind == MatchKind::character) {
            ac_hit.score = TouchesKatakanaBoundary(text, hit.begin, hit.end) ? 0.1f : 1.0f;
        } 
        
        out.push_back(std::move(ac_hit));
    }

    return out;
}
