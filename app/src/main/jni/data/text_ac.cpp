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
    std::string pattern;
    std::string canonical;
    std::string called;
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
        for (unsigned char ch : patterns_[pattern_id].pattern) {
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
                size_t len = pattern.pattern.size();

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

    size_t char_count = std::min(
        init.char_patterns.size(),
        std::min(init.char_canonicals.size(), init.char_called.size()));
    if (init.char_patterns.size() != init.char_canonicals.size()
        || init.char_patterns.size() != init.char_called.size()) {
        LOGW("[TextAC] character pattern/canonical/called size mismatch pattern=%zu canonical=%zu called=%zu",
            init.char_patterns.size(),
            init.char_canonicals.size(),
            init.char_called.size());
    }

    for (size_t i = 0; i < char_count; ++i) {
        const auto& pattern_text = init.char_patterns[i];
        const auto& canonical = init.char_canonicals[i];
        const auto& called = init.char_called[i];
        if (pattern_text.empty() || canonical.empty()) {continue;}

        MatchPattern pattern;
        pattern.pattern = pattern_text;
        pattern.canonical = canonical;
        pattern.called = called;
        pattern.kind = MatchKind::character;

        ac->AddPattern(std::move(pattern));
    }

    for (const auto& term : init.term_list) {
        if (term.empty()) {continue;}

        MatchPattern pattern;
        pattern.pattern = term;
        pattern.canonical = term;
        pattern.called = "";
        pattern.kind = MatchKind::term;

        ac->AddPattern(std::move(pattern));
    }

    ac->Build();

    {
    std::lock_guard<std::mutex> lock(g_text_ac_mutex);
    g_text_ac = std::move(ac);
    g_text_ac_ready.store(true, std::memory_order_release);
    }

    LOGI("[TextAC] ready char_patterns=%zu term=%zu",
     char_count,
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

        ac_hit.matched_text = pattern.pattern;
        ac_hit.canonical = pattern.canonical;
        ac_hit.called = pattern.called;
        ac_hit.kind = pattern.kind;

        if (pattern.kind == MatchKind::character) {
            bool touches_katakana_boundary = TouchesKatakanaBoundary(text, hit.begin, hit.end);
            ac_hit.source = pattern.pattern == pattern.canonical ? AcHitSource::direct : AcHitSource::alias;
            ac_hit.score = touches_katakana_boundary ? 0.1f : 1.0f;
        } else if (pattern.kind == MatchKind::term) {
            ac_hit.source = AcHitSource::direct;
            ac_hit.score = 1.0f;
        }
        
        out.push_back(std::move(ac_hit));
    }

    return out;
}
