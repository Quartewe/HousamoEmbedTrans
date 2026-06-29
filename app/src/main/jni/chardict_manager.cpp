#include "housamo.hpp"
#include <mutex>
#include <thread>
#include <memory>
#include <utility>
#include <unordered_map>
#include <rapidjson/document.h>
#include <rapidjson/error/en.h>

namespace {

class CharDictManager {
public:
    bool LoadFromJson(const std::string& json_text) {
        rapidjson::Document doc;

        doc.Parse(json_text.c_str());

        if (doc.HasParseError()) {
            LOGE("[CharacterDict] JSON parse error: %s (offset %zu)",
                 rapidjson::GetParseError_En(doc.GetParseError()),
                 doc.GetErrorOffset());
            return false;
        }

        if (!doc.IsObject()) {
            LOGE("[CharacterDict] root is not an object");
            return false;
        }

        items_.reserve(doc.MemberCount());

        for (auto it = doc.MemberBegin(); it != doc.MemberEnd(); ++it) {
            if (!it->name.IsString() || !it->value.IsObject()) {
            continue;
            }

            std::string name(it->name.GetString(), it->name.GetStringLength());
            const auto& obj = it->value;

            CharacterItem item;
            item.name = name;
            item.i18n.en = JsonString(obj, "en");
            item.i18n.zh_tw = JsonString(obj, "zh-tw");
            item.i18n.zh_cn = JsonString(obj, "zh-cn");
            item.guild = JsonArray(obj, "guild");
            item.school = JsonArray(obj, "school");
            item.origin_world = JsonArray(obj, "origin_world");
            item.speech_style = JsonString(obj, "speech_style");
            item.relationships = ParseRelationships(obj);
            item.info = JsonString(obj, "info");
            item.description = JsonString(obj, "description");

            items_.emplace(std::move(name), std::move(item));
        }

        LOGI("[CharacterDict] loaded %zu items", items_.size());

        return true;
    }

    bool FindItem(const std::string& name, CharacterItem* out) const {
        if (!out || out == nullptr) return false;

        auto it = items_.find(name);
        if (it == items_.end()) return false;

        *out = it->second;
        return true;
    }

private:
    static std::string JsonString(
        const rapidjson::Value& obj,
        const char* key
    ) {
        if (!obj.IsObject() || !obj.HasMember(key)) return "";

        const auto& v = obj[key];
        if (!v.IsString()) return "";

        return std::string(v.GetString(), v.GetStringLength());
    }

    static std::vector<std::string> JsonArray(
        const rapidjson::Value& obj,
        const char* key
    ) {
        std::vector<std::string> out;

        if (!obj.IsObject() || !obj.HasMember(key)) return out;

        const auto& v = obj[key];
        if (!v.IsArray()) return out;

        for (const auto& item : v.GetArray()) {
            if (item.IsString()) {
                out.emplace_back(item.GetString(), item.GetStringLength());
            }
        }

        return out;
    }

    static std::vector<Relationship> ParseRelationships(
        const rapidjson::Value& obj
    ) {
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

private:
    std::unordered_map<std::string, CharacterItem> items_;
};

static std::mutex g_character_dict_mutex;
static std::shared_ptr<const CharDictManager> g_character_dict;

} // namespace


bool FindCharacterItem(const std::string& name, CharacterItem* out) {
    std::shared_ptr<const CharDictManager> dict;

    {
        std::lock_guard<std::mutex> lock(g_character_dict_mutex);
        dict = g_character_dict;
    }

    if (!dict) return false;

    return dict->FindItem(name, out);
}

void StartCharDictManager(std::string json_text) {
    std::thread([json_text = std::move(json_text)]() mutable {
        auto dict = std::make_shared<CharDictManager>();

        if (!dict->LoadFromJson(json_text)) {
            LOGE("[CharacterDict] load failed");
            return;
        }

        {
            std::lock_guard<std::mutex> lock(g_character_dict_mutex);
            g_character_dict = std::move(dict);
        }

        LOGI("[CharacterDict] ready");
    }).detach();
}