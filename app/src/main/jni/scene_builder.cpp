#include "housamo.hpp"

#include <algorithm>
#include <cmath>
#include <mutex>
#include <utility>
#include <unordered_set>

struct CharacterSignal {
    float speaker_score = 0.0f;
    float show_score = 0.0f;
    float text_score = 0.0f;
};

static bool IsCapturePaused() {
    return stop_reason.load(std::memory_order_acquire) == StopReason::existing_scene;
}

class SceneBuilder {
public:
    void Start() {
        LOGI("[SceneBuilder] workers started");
    }

    void Submit(ScenarioParseResult result) {
        Scene scene;

        const size_t item_count = result.scene_items.size();
        const size_t protect_count = result.protect.size();

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (latest_scene_ &&
                result.scene == latest_scene_->scene &&
                g_runtime_config.target_lang == latest_scene_->target_lang &&
                item_count == latest_scene_->scene_items.size() &&
                protect_count == latest_scene_->protect.size()
            ) {
                LOGW("[SceneBuilder] parse result received but scene unchanged, items=%zu protect=%zu",
                    item_count,
                    protect_count);
                return;
            }
        }

        LOGI("[SceneBuilder] parse result received items=%zu protect=%zu",
             item_count,
             protect_count);

        std::vector<GameTerm> character_terms;

        scene.scene = result.scene;
        scene.target_lang = g_runtime_config.target_lang;
        scene.protect = std::move(result.protect);
        scene.scene_items = std::move(result.scene_items);
        CharacterBuild(
            result.speaker_character,
            result.show_character,
            result.text_character,
            result.aliases,
            item_count,
            scene.character,
            scene.mentioned_characters,
            character_terms
        );
        TermBuild(result.game_terms, character_terms, scene.game_terms);

        auto scene_ptr = std::make_shared<const Scene>(std::move(scene));

        {
            std::lock_guard<std::mutex> lock(mutex_);
            latest_scene_ = scene_ptr;
        }

        SubmitToJsonHandler(scene_ptr);
    }

private:
    template<typename T, typename KeyFn>
    static void PushUnique(std::unordered_set<std::string>& seen, T item, KeyFn key_fn, std::vector<T>& out) {
        const std::string& key = key_fn(item);

        if (key.empty()) return;

        if (seen.insert(key).second) {
            out.push_back(std::move(item));
        }

    }

    template <typename Fn>
    static void ForEachCharacterTermName(const CharacterItem& character, Fn&& fn) {
        for (const std::string& term : character.guild) {
            fn(term);
        }

        for (const std::string& term : character.school) {
            fn(term);
        }

        for (const std::string& term : character.origin_world) {
            fn(term);
        }
    }

    void TermBuild(const std::vector<ItemScore>& item, const std::vector<GameTerm>& character_terms, std::vector<GameTerm>& out) {
        std::unordered_set<std::string> seen_terms;

        for (ItemScore i : item) {
            if (i.score >= g_runtime_config.character_weight.text_mentioned_score) {
                GameTerm term;
                if (!FindGameTerm(i.item, &term)) {
                    LOGW("[SceneBuilder] game term not found: %s", i.item.c_str());
                    term.term = i.item;
                }
                PushUnique(seen_terms, std::move(term), [](const GameTerm& t) {
                    return t.term;
                }, out);
            }
        }

        for (GameTerm term : character_terms) {
            PushUnique(seen_terms, std::move(term), [](const GameTerm& t) {
                return t.term;
            }, out);
        }
    }

    bool AttachMatchedAliases(const std::string& name, CharacterItem& character, const std::unordered_map<std::string, std::unordered_set<std::string>>& alias_map) {
        auto it = alias_map.find(name);

        if (it == alias_map.end() || it->second.empty()) {
            return false;
        }

        character.aliases.reserve(it->second.size());
        for (const std::string& alias : it->second) {
            if (!FindAliasItem(name, alias, &character.aliases)) {
                LOGW("[SceneBuilder] alias not found: %s -> %s", name.c_str(), alias.c_str());
                return false;
            }
        }

        return true;
    }

    void CharacterBuild(
        const std::vector<ItemScore>& speaker_character,
        const std::vector<ItemScore>& show_character,
        const std::vector<ItemScore>& text_character,
        const std::vector<AliasScore>& aliases,
        const size_t& item_count,
        Character& character_out,
        std::vector<MentionedCharacter>& mentioned_out,
        std::vector<GameTerm>& character_terms_out
    ) {
        const CharacterWeightConfig& weight = g_runtime_config.character_weight;

        std::unordered_map<std::string, CharacterSignal> signals;
        std::unordered_set<std::string> seen_terms;

        for (const auto& item : speaker_character) {
            signals[item.item].speaker_score += item.score;
        }

        for (const auto& item : show_character) {
            signals[item.item].show_score += item.score;
        }

        for (const auto& item : text_character) {
            signals[item.item].text_score += item.score;
        }

        std::unordered_map<std::string, std::unordered_set<std::string>> alias_map;

        for (const AliasScore& alias : aliases) {
            if (alias.score >= weight.text_mentioned_score) {
                alias_map[alias.canonical].insert(alias.pattern);
            }
        }

        size_t total_size = signals.size();
        character_out.high_weight.reserve(total_size);
        character_out.low_weight.reserve(total_size);
        mentioned_out.reserve(total_size);
        std::unordered_set<std::string> high_set;

        float n = std::max(1.0f, static_cast<float>(item_count));

        for (const auto& [name, sig] : signals) {
            float raw_score = sig.speaker_score + sig.show_score + sig.text_score;
            float density = raw_score / std::sqrt(n);

            float speaker_turns = sig.speaker_score / 10.0f;
            float show_hits = sig.show_score / 2.0f;
            float text_hits = sig.text_score;
            float relevance =
                5.0f * std::log1p(speaker_turns) +
                2.0f * std::log1p(show_hits) +
                1.0f * std::log1p(text_hits);

            CharacterItem character;
            if (!FindCharacterItem(name, &character)) {
                LOGW("[SceneBuilder] character not found: %s", name.c_str());
                character.name = name;
            }

            AttachMatchedAliases(name, character, alias_map);

            bool strong_signal =
                speaker_turns >= 2.0f ||
                (speaker_turns >= 1.0f && show_hits >= 1.0f);

            bool high =
                strong_signal ||
                relevance >= weight.high_relevance ||
                (relevance >= weight.mid_relevance && density >= weight.density_high);

            if (high) {
                std::vector<std::string> term_list;

            ForEachCharacterTermName(character, [&](const std::string& term_item) {
                GameTerm term;
                if (!FindGameTerm(term_item, &term)) {
                    LOGW("[SceneBuilder] game term not found: %s", term_item.c_str());
                    term.term = term_item;
                }

                PushUnique(seen_terms, std::move(term), [](const GameTerm& t) {
                    return t.term;
                }, character_terms_out);
            });

                character_out.high_weight.push_back(std::move(character));

                high_set.insert(name);
            }
        }

        std::unordered_map<std::string, int> low_term_map;

        for (const auto& [name, sig] : signals) {
            if (high_set.find(name) != high_set.end()) {
                continue;
            }

            CharacterItem character;
            if (!FindCharacterItem(name, &character)) {
                character.name = name;
            }

            AttachMatchedAliases(name, character, alias_map);

            bool low =
                sig.text_score >= weight.text_low_score ||
                (sig.text_score > weight.text_mentioned_score &&
                 RelatedNum(name, high_set) >= weight.related_num);

            if (low) {
                ForEachCharacterTermName(character, [&](const std::string& term_item) {
                    low_term_map[term_item]++;
                });

                character_out.low_weight.push_back(std::move(character));

            } else if (sig.text_score >= weight.text_mentioned_score) {
                MentionedCharacter mentioned;
                mentioned.name = name;
                mentioned.i18n = character.i18n;
                mentioned_out.push_back(std::move(mentioned));
            }
        }

        for (const auto& [term_item, count] : low_term_map) {
            if (count >= weight.low_term_score) {
                GameTerm term;
                if (!FindGameTerm(term_item, &term)) {
                    LOGW("[SceneBuilder] game term not found: %s", term_item.c_str());
                    term.term = term_item;
                }
                PushUnique(seen_terms, std::move(term), [](const GameTerm& t) {
                    return t.term;
                }, character_terms_out);
            }
        }
    }

private:
    std::mutex mutex_;
    std::shared_ptr<const Scene> latest_scene_;
};

static SceneBuilder g_scene_builder;

void SubmitScenarioParseResult(ScenarioParseResult result) {
    g_scene_builder.Submit(std::move(result));
}

void StartSceneBuilder() {
    g_scene_builder.Start();
}
