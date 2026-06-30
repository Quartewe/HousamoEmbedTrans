#include "housamo.hpp"

#include <memory>
#include <mutex>
#include <thread>
#include <utility>

#include <rapidjson/document.h>
#include <rapidjson/error/en.h>

namespace {

struct CharacterAlias {
    std::string name;
    std::string called;
};

struct CharacterRecord {
    CharacterItem item;
    std::vector<CharacterAlias> aliases;
};

struct GameDataSnapshot {
    std::unordered_map<std::string, CharacterRecord> characters;
    std::unordered_map<std::string, GameTerm> terms;
    std::unordered_map<std::string, std::unordered_set<std::string>> related_index;
};

static std::mutex g_game_data_mutex;
static std::shared_ptr<const GameDataSnapshot> g_game_data;

static std::string JsonString(const rapidjson::Value& obj, const char* key) {
    if (!obj.IsObject() || !obj.HasMember(key)) return {};

    const auto& v = obj[key];
    if (!v.IsString()) return {};

    return std::string(v.GetString(), v.GetStringLength());
}

static std::vector<std::string> JsonStringArray(const rapidjson::Value& obj, const char* key) {
    std::vector<std::string> out;

    if (!obj.IsObject() || !obj.HasMember(key)) return out;

    const auto& v = obj[key];
    if (!v.IsArray()) return out;

    out.reserve(v.Size());
    for (const auto& item : v.GetArray()) {
        if (item.IsString()) {
            out.emplace_back(item.GetString(), item.GetStringLength());
        }
    }

    return out;
}

static std::vector<Relationship> ParseRelationships(const rapidjson::Value& obj) {
    std::vector<Relationship> out;

    if (!obj.IsObject() || !obj.HasMember("relationships")) return out;

    const auto& arr = obj["relationships"];
    if (!arr.IsArray()) return out;

    out.reserve(arr.Size());
    for (const auto& item : arr.GetArray()) {
        if (!item.IsObject()) continue;

        Relationship rel;
        rel.target = JsonString(item, "target");
        rel.type = JsonString(item, "type");

        if (!rel.target.empty() || !rel.type.empty()) {
            out.push_back(std::move(rel));
        }
    }

    return out;
}

static std::vector<CharacterAlias> ParseAliases(const rapidjson::Value& obj) {
    std::vector<CharacterAlias> out;

    if (!obj.IsObject() || !obj.HasMember("alias")) return out;

    const auto& arr = obj["alias"];
    if (!arr.IsArray()) return out;

    out.reserve(arr.Size());
    for (const auto& item : arr.GetArray()) {
        if (!item.IsObject()) continue;

        CharacterAlias alias;
        alias.name = JsonString(item, "name");
        alias.called = JsonString(item, "called");

        if (!alias.name.empty()) {
            out.push_back(std::move(alias));
        }
    }

    return out;
}

static bool ParseDocument(
    const std::string& json_text,
    const char* tag,
    rapidjson::Document* out
) {
    if (out == nullptr) return false;

    out->Parse(json_text.c_str());

    if (out->HasParseError()) {
        LOGE("[%s] JSON parse error: %s (offset %zu)",
             tag,
             rapidjson::GetParseError_En(out->GetParseError()),
             out->GetErrorOffset());
        return false;
    }

    if (!out->IsObject()) {
        LOGE("[%s] root is not an object", tag);
        return false;
    }

    return true;
}

static bool LoadCharacters(const std::string& json_text, GameDataSnapshot* snapshot) {
    if (snapshot == nullptr) return false;

    rapidjson::Document doc;
    if (!ParseDocument(json_text, "JsonManager.CharacterDict", &doc)) {
        return false;
    }

    snapshot->characters.reserve(doc.MemberCount());

    for (auto it = doc.MemberBegin(); it != doc.MemberEnd(); ++it) {
        if (!it->name.IsString() || !it->value.IsObject()) {
            continue;
        }

        std::string name(it->name.GetString(), it->name.GetStringLength());
        if (name.empty()) {
            continue;
        }

        const auto& obj = it->value;

        CharacterRecord record;
        record.item.name = name;
        record.item.i18n.en = JsonString(obj, "en");
        record.item.i18n.zh_tw = JsonString(obj, "zh-tw");
        record.item.i18n.zh_cn = JsonString(obj, "zh-cn");
        record.item.guild = JsonStringArray(obj, "guild");
        record.item.school = JsonStringArray(obj, "school");
        record.item.origin_world = JsonStringArray(obj, "origin_world");
        record.item.speech_style = JsonString(obj, "speech_style");
        record.item.relationships = ParseRelationships(obj);
        record.item.info = JsonString(obj, "info");
        record.item.description = JsonString(obj, "description");
        record.aliases = ParseAliases(obj);

        snapshot->characters.emplace(std::move(name), std::move(record));
    }

    LOGI("[JsonManager] loaded %zu characters", snapshot->characters.size());
    return true;
}

static bool LoadGameTerms(const std::string& json_text, GameDataSnapshot* snapshot) {
    if (snapshot == nullptr) return false;

    rapidjson::Document doc;
    if (!ParseDocument(json_text, "JsonManager.GameTerms", &doc)) {
        return false;
    }

    snapshot->terms.reserve(doc.MemberCount());

    for (auto it = doc.MemberBegin(); it != doc.MemberEnd(); ++it) {
        if (!it->name.IsString() || !it->value.IsObject()) {
            continue;
        }

        std::string key(it->name.GetString(), it->name.GetStringLength());
        if (key.empty()) {
            continue;
        }

        const auto& obj = it->value;

        GameTerm term;
        term.term = key;
        term.i18n.en = JsonString(obj, "en");
        term.i18n.zh_tw = JsonString(obj, "zh-tw");
        term.i18n.zh_cn = JsonString(obj, "zh-cn");
        term.description = JsonString(obj, "description");

        snapshot->terms.emplace(std::move(key), std::move(term));
    }

    LOGI("[JsonManager] loaded %zu game terms", snapshot->terms.size());
    return true;
}

static void BuildRelatedIndex(GameDataSnapshot* snapshot) {
    if (snapshot == nullptr) return;

    snapshot->related_index.clear();

    for (const auto& [name, record] : snapshot->characters) {
        if (name.empty()) {
            continue;
        }

        for (const Relationship& rel : record.item.relationships) {
            if (rel.target.empty() || rel.target == name) {
                continue;
            }

            snapshot->related_index[name].insert(rel.target);
            snapshot->related_index[rel.target].insert(name);
        }
    }
}

static void AddAcPattern(
    ACInit* out,
    const std::string& pattern,
    const std::string& canonical,
    const std::string& called,
    std::unordered_set<std::string>* seen_patterns
) {
    if (out == nullptr || seen_patterns == nullptr || pattern.empty() || canonical.empty()) {
        return;
    }

    if (!seen_patterns->insert(pattern).second) {
        return;
    }

    out->char_patterns.push_back(pattern);
    out->char_canonicals.push_back(canonical);
    out->char_called.push_back(called);
}

static ACInit BuildAcInit(const GameDataSnapshot& snapshot) {
    ACInit out;
    std::unordered_set<std::string> seen_patterns;

    size_t alias_count = 0;
    for (const auto& [name, record] : snapshot.characters) {
        alias_count += record.aliases.size();
    }

    out.char_patterns.reserve(snapshot.characters.size() + alias_count);
    out.char_canonicals.reserve(snapshot.characters.size() + alias_count);
    out.char_called.reserve(snapshot.characters.size() + alias_count);
    out.term_list.reserve(snapshot.terms.size());
    seen_patterns.reserve(snapshot.characters.size() + alias_count);

    for (const auto& [name, record] : snapshot.characters) {
        (void)record;
        AddAcPattern(&out, name, name, "", &seen_patterns);
    }

    for (const auto& [name, record] : snapshot.characters) {
        for (const CharacterAlias& alias : record.aliases) {
            AddAcPattern(&out, alias.name, name, alias.called, &seen_patterns);
        }
    }

    for (const auto& [term, item] : snapshot.terms) {
        (void)item;
        if (!term.empty()) {
            out.term_list.push_back(term);
        }
    }

    return out;
}

static std::shared_ptr<const GameDataSnapshot> GetGameDataSnapshot() {
    std::lock_guard<std::mutex> lock(g_game_data_mutex);
    return g_game_data;
}

} // namespace

bool IsJsonManagerReady() {
    return static_cast<bool>(GetGameDataSnapshot());
}

int RelatedNum(const std::string& name, const std::unordered_set<std::string>& related_characters) {
    auto snapshot = GetGameDataSnapshot();
    if (!snapshot) return 0;

    auto it = snapshot->related_index.find(name);
    if (it == snapshot->related_index.end()) return 0;

    int out = 0;
    const auto& related = it->second;

    for (const auto& related_character : related_characters) {
        if (related.find(related_character) != related.end()) {
            ++out;
        }
    }

    return out;
}

bool FindCharacterItem(const std::string& name, CharacterItem* out) {
    if (out == nullptr) return false;

    auto snapshot = GetGameDataSnapshot();
    if (!snapshot) return false;

    auto it = snapshot->characters.find(name);
    if (it == snapshot->characters.end()) return false;

    *out = it->second.item;
    return true;
}

bool FindGameTerm(const std::string& term, GameTerm* out) {
    if (out == nullptr) return false;

    auto snapshot = GetGameDataSnapshot();
    if (!snapshot) return false;

    auto it = snapshot->terms.find(term);
    if (it == snapshot->terms.end()) return false;

    *out = it->second;
    return true;
}

void StartJsonManager(std::string chardict_json, std::string gameterms_json) {
    std::thread(
        [chardict_json = std::move(chardict_json),
         gameterms_json = std::move(gameterms_json)]() mutable {
            auto snapshot = std::make_shared<GameDataSnapshot>();

            if (!LoadCharacters(chardict_json, snapshot.get())) {
                LOGE("[JsonManager] character load failed");
                return;
            }

            if (!LoadGameTerms(gameterms_json, snapshot.get())) {
                LOGE("[JsonManager] game term load failed");
                return;
            }

            BuildRelatedIndex(snapshot.get());
            ACInit ac_init = BuildAcInit(*snapshot);

            {
                std::lock_guard<std::mutex> lock(g_game_data_mutex);
                g_game_data = snapshot;
            }

            LOGI("[JsonManager] ready: characters=%zu terms=%zu ac_char_patterns=%zu ac_terms=%zu",
                 snapshot->characters.size(),
                 snapshot->terms.size(),
                 ac_init.char_patterns.size(),
                 ac_init.term_list.size());

            StartAcInit(std::move(ac_init));
        })
        .detach();
}
