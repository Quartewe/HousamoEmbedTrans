#include "housamo.hpp"
#include <rapidjson/document.h>
#include <rapidjson/prettywriter.h>
#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <filesystem>
#include <limits>
#include <sys/stat.h>
#include <sys/types.h>

#include <condition_variable>
#include <deque>
#include <thread>

#include "jni_bridge.hpp"

namespace {

struct JsonItem {
    std::string scene_name;
    rapidjson::Document process_doc;
    std::shared_ptr<const Scene> scene;
};

struct ApiItem {
    std::string scene_name;
    std::string api_doc;
    std::vector<OrderKey> seq_to_order; // seq N maps to index N - 1
};

struct ApiBuildContext {
    std::vector<OrderKey> seq_to_order;

    int AddText(const OrderKey& order) {
        seq_to_order.push_back(order);
        return static_cast<int>(seq_to_order.size());
    }
};

struct JsonSceneResItem {
    rapidjson::Value scene_item;
    rapidjson::Value api_item;
};

// Json helper

static rapidjson::Value JsonString(const std::string& str, rapidjson::Document::AllocatorType& alloc) {
    return rapidjson::Value(str.c_str(), static_cast<rapidjson::SizeType>(str.length()), alloc);
}

static rapidjson::Value JsonStringArray(const std::vector<std::string>& arr, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value json_arr(rapidjson::kArrayType);
    for (const auto& str : arr) {
        json_arr.PushBack(JsonString(str, alloc), alloc);
    }
    return json_arr;
}

static rapidjson::Value JsonI18N(const I18N& i18n, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("en", JsonString(i18n.en, alloc), alloc);
    obj.AddMember("zh_tw", JsonString(i18n.zh_tw, alloc), alloc);
    obj.AddMember("zh_cn", JsonString(i18n.zh_cn, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonAliasItem(const AliasItem& alias, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("name", JsonString(alias.name, alloc), alloc);
    obj.AddMember("i18n", JsonI18N(alias.i18n, alloc), alloc);
    obj.AddMember("called", JsonString(alias.called, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonAliasItemArray(const std::vector<AliasItem>& aliases, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value arr(rapidjson::kArrayType);
    for (const auto& alias : aliases) {
        arr.PushBack(JsonAliasItem(alias, alloc), alloc);
    }
    return arr;
}
 
static rapidjson::Value JsonRelationship(const Relationship& rel, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("target", JsonString(rel.target, alloc), alloc);
    obj.AddMember("type", JsonString(rel.type, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonRelationshipArray(const std::vector<Relationship>& rels, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value arr(rapidjson::kArrayType);
    for (const auto& rel : rels) {
        arr.PushBack(JsonRelationship(rel, alloc), alloc);
    }
    return arr;
}

static rapidjson::Value JsonCharacterItem(const CharacterItem& character, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("name", JsonString(character.name, alloc), alloc);
    obj.AddMember("aliases", JsonAliasItemArray(character.aliases, alloc), alloc);
    obj.AddMember("i18n", JsonI18N(character.i18n, alloc), alloc);
    obj.AddMember("school", JsonStringArray(character.school, alloc), alloc);
    obj.AddMember("guild", JsonStringArray(character.guild, alloc), alloc);
    obj.AddMember("origin_world", JsonStringArray(character.origin_world, alloc), alloc);
    obj.AddMember("relationships", JsonRelationshipArray(character.relationships, alloc), alloc);
    obj.AddMember("info", JsonString(character.info, alloc), alloc);
    obj.AddMember("description", JsonString(character.description, alloc), alloc);
    obj.AddMember("speech_style", JsonString(character.speech_style, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonCharacterItemArray(const std::vector<CharacterItem>& characters, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value arr(rapidjson::kArrayType);
    for (const auto& item : characters) {
        arr.PushBack(JsonCharacterItem(item, alloc), alloc);
    }
    return arr;
}

static rapidjson::Value JsonCharacter(const Character& character, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("mc", JsonCharacterItem(character.mc, alloc), alloc);
    obj.AddMember("high_weight", JsonCharacterItemArray(character.high_weight, alloc), alloc);
    obj.AddMember("low_weight", JsonCharacterItemArray(character.low_weight, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonMentionedCharacter(const MentionedCharacter& mentioned, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("name", JsonString(mentioned.name, alloc), alloc);
    obj.AddMember("i18n", JsonI18N(mentioned.i18n, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonMentionedCharacterArray(const std::vector<MentionedCharacter>& mentioneds, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value arr(rapidjson::kArrayType);
    for (const auto& mentioned : mentioneds) {
        arr.PushBack(JsonMentionedCharacter(mentioned, alloc), alloc);
    }
    return arr;
}

static rapidjson::Value JsonGameTerm(const GameTerm& term, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("term", JsonString(term.term, alloc), alloc);
    obj.AddMember("i18n", JsonI18N(term.i18n, alloc), alloc);
    obj.AddMember("description", JsonString(term.description, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonGameTermArray(const std::vector<GameTerm>& terms, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value arr(rapidjson::kArrayType);
    for (const auto& term : terms) {
        arr.PushBack(JsonGameTerm(term, alloc), alloc);
    }
    return arr;
}

static rapidjson::Value JsonProtectedToken(const ProtectedToken& token, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("label", JsonString(token.label, alloc), alloc);
    obj.AddMember("origin", JsonString(token.origin, alloc), alloc);
    return obj;
}

static rapidjson::Value JsonProtectedTokenArray(const std::vector<ProtectedToken>& tokens, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value arr(rapidjson::kArrayType);
    for (const auto& token : tokens) {
        arr.PushBack(JsonProtectedToken(token, alloc), alloc);
    }
    return arr;
}

static rapidjson::Value JsonOrderKey(const OrderKey& order, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);
    obj.AddMember("label_index", order.label_index, alloc);
    obj.AddMember("page_no", order.page_no, alloc);
    obj.AddMember("cmd_index", order.cmd_index, alloc);
    obj.AddMember("sub_index", order.sub_index, alloc);
    return obj;
}

static rapidjson::Value JsonTextItem(const TextItem& text_item, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("type", "text", alloc);
    obj.AddMember("order", JsonOrderKey(text_item.order, alloc), alloc);
    obj.AddMember("speaker", JsonString(text_item.speaker, alloc), alloc);
    obj.AddMember("text", JsonString(text_item.text, alloc), alloc);

    rapidjson::Value translations(rapidjson::kObjectType);
    obj.AddMember("translations", translations, alloc);

    return obj;
}

static rapidjson::Value ApiTextItem(
    const TextItem& text_item,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    rapidjson::Value obj(rapidjson::kObjectType);

    const int seq = context.AddText(text_item.order);

    obj.AddMember("type", "text", alloc);
    obj.AddMember("seq", seq, alloc);
    obj.AddMember("speaker", JsonString(text_item.speaker, alloc), alloc);
    obj.AddMember("text", JsonString(text_item.text, alloc), alloc);

    return obj;

}

static rapidjson::Value JsonSceneItemValue(
    const SceneItem& item,
    rapidjson::Document::AllocatorType& alloc);

static rapidjson::Value ApiSceneItemValue(
    const SceneItem& item,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context);

static rapidjson::Value JsonChoiceBranch(const ChoiceBranch& branch, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("target_label", JsonString(branch.target_label, alloc), alloc);

    rapidjson::Value options(rapidjson::kArrayType);
    for (const TextItem& option : branch.options) {
        options.PushBack(JsonTextItem(option, alloc), alloc);
    }
    obj.AddMember("options", options, alloc);

    rapidjson::Value following_text(rapidjson::kArrayType);
    for (const SceneItem& item : branch.following_text) {
        following_text.PushBack(JsonSceneItemValue(item, alloc), alloc);
    }
    obj.AddMember("following_text", following_text, alloc);

    return obj;
}

static rapidjson::Value JsonChoiceBlock(const ChoiceBlock& choice_block, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("type", "choice", alloc);
    obj.AddMember("order", JsonOrderKey(choice_block.order, alloc), alloc);
    obj.AddMember("merge_label", JsonString(choice_block.merge_label, alloc), alloc);

    rapidjson::Value branches(rapidjson::kArrayType);
    for (const ChoiceBranch& branch : choice_block.branches) {
        branches.PushBack(JsonChoiceBranch(branch, alloc), alloc);
    }
    obj.AddMember("branches", branches, alloc);

    return obj;
}

static rapidjson::Value ApiChoiceBranch(
    const ChoiceBranch& branch,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("target_label", JsonString(branch.target_label, alloc), alloc);

    rapidjson::Value options(rapidjson::kArrayType);
    for (const TextItem& option : branch.options) {
        options.PushBack(ApiTextItem(option, alloc, context), alloc);
    }
    obj.AddMember("options", options, alloc);

    rapidjson::Value following_text(rapidjson::kArrayType);
    for (const SceneItem& item : branch.following_text) {
        following_text.PushBack(ApiSceneItemValue(item, alloc, context), alloc);
    }
    obj.AddMember("following_text", following_text, alloc);

    return obj;
}

static rapidjson::Value ApiChoiceBlock(
    const ChoiceBlock& choice_block,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("type", "choice", alloc);
    obj.AddMember("merge_label", JsonString(choice_block.merge_label, alloc), alloc);

    rapidjson::Value branches(rapidjson::kArrayType);
    for (const ChoiceBranch& branch : choice_block.branches) {
        branches.PushBack(ApiChoiceBranch(branch, alloc, context), alloc);
    }
    obj.AddMember("branches", branches, alloc);

    return obj;
}

static rapidjson::Value JsonIfBlock(const IfBlock& if_block, rapidjson::Document::AllocatorType& alloc) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("type", "if", alloc);
    obj.AddMember("order", JsonOrderKey(if_block.order, alloc), alloc);
    obj.AddMember("condition", JsonString(if_block.condition, alloc), alloc);
    obj.AddMember("target_label", JsonString(if_block.target_label, alloc), alloc);
    obj.AddMember("merge_label", JsonString(if_block.merge_label, alloc), alloc);

    rapidjson::Value following_text(rapidjson::kArrayType);
    for (const SceneItem& item : if_block.following_text) {
        following_text.PushBack(JsonSceneItemValue(item, alloc), alloc);
    }
    obj.AddMember("following_text", following_text, alloc);

    return obj;
}

static rapidjson::Value ApiIfBlock(
    const IfBlock& if_block,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    rapidjson::Value obj(rapidjson::kObjectType);

    obj.AddMember("type", "if", alloc);
    obj.AddMember("condition", JsonString(if_block.condition, alloc), alloc);
    obj.AddMember("target_label", JsonString(if_block.target_label, alloc), alloc);
    obj.AddMember("merge_label", JsonString(if_block.merge_label, alloc), alloc);

    rapidjson::Value following_text(rapidjson::kArrayType);
    for (const SceneItem& item : if_block.following_text) {
        following_text.PushBack(ApiSceneItemValue(item, alloc, context), alloc);
    }
    obj.AddMember("following_text", following_text, alloc);

    return obj;
}

static rapidjson::Value JsonSceneItemVariant(
    const TextItem& item,
    rapidjson::Document::AllocatorType& alloc) {
    return JsonTextItem(item, alloc);
}

static rapidjson::Value JsonSceneItemVariant(
    const ChoiceBlock& block,
    rapidjson::Document::AllocatorType& alloc
) {
    return JsonChoiceBlock(block, alloc);
}

static rapidjson::Value JsonSceneItemVariant(
    const IfBlock& block,
    rapidjson::Document::AllocatorType& alloc
) {
    return JsonIfBlock(block, alloc);
}

static rapidjson::Value ApiSceneItemVariant(
    const TextItem& item,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    return ApiTextItem(item, alloc, context);
}

static rapidjson::Value ApiSceneItemVariant(
    const ChoiceBlock& block,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    return ApiChoiceBlock(block, alloc, context);
}

static rapidjson::Value ApiSceneItemVariant(
    const IfBlock& block,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    return ApiIfBlock(block, alloc, context);
}

static rapidjson::Value JsonSceneItemValue(
    const SceneItem& item,
    rapidjson::Document::AllocatorType& alloc) {
    return std::visit([&](const auto& value) {
        return JsonSceneItemVariant(value, alloc);
    }, item.value);
}

static rapidjson::Value ApiSceneItemValue(
    const SceneItem& item,
    rapidjson::Document::AllocatorType& alloc,
    ApiBuildContext& context) {
    return std::visit([&](const auto& value) {
        return ApiSceneItemVariant(value, alloc, context);
    }, item.value);
}

static JsonSceneResItem JsonSceneItemArray(
    const SceneItem& item,
    rapidjson::Document::AllocatorType& alloc,
    rapidjson::Document::AllocatorType& api_alloc,
    ApiBuildContext& context) {
    JsonSceneResItem out;
    out.scene_item = JsonSceneItemValue(item, alloc);
    out.api_item = ApiSceneItemValue(item, api_alloc, context);
    return out;
}

// main handler

static std::string FormalPath(const std::string& scene_name) {
    const auto& base_dir = g_runtime_config.base_dir;
    if (base_dir.empty() || scene_name.empty()) {
        return "";
    }

    std::string file;
    file.reserve(scene_name.size());

    for (unsigned char ch : scene_name) {
        const bool allowed =
            (ch >= 'a' && ch <= 'z') ||
            (ch >= 'A' && ch <= 'Z') ||
            (ch >= '0' && ch <= '9') ||
            ch == '_' ||
            ch == '-' ||
            ch == '.';

        file.push_back(allowed ? static_cast<char>(ch) : '_');
    }

    return base_dir + "/scenes/" + file;
}

static bool HaveFile(const std::string& name) {
    return std::filesystem::exists(name + ".json");
}

static bool ReadFile(const std::string& scene_name, rapidjson::Document& doc) {
    std::string path = FormalPath(scene_name) + ".json";

    if (!std::filesystem::exists(path)) {
        return false;
    }

    std::ifstream file(path, std::ios::binary);

    if (!file.is_open()) {
        LOGE("[JsonHandler] Failed to open file %s for reading: %s", path.c_str(), std::strerror(errno));
        return false;
    }

    std::string text{
        std::istreambuf_iterator<char>{file},
        std::istreambuf_iterator<char>{}
    };

    doc.Parse(text.c_str(), text.size());

    if (doc.HasParseError() || !doc.IsObject()) {
        LOGE(
            "[JsonHandler] Failed to parse JSON file %s: error=%u offset=%zu",
            path.c_str(),
            static_cast<unsigned>(doc.GetParseError()),
            doc.GetErrorOffset()
        );
        return false;
    }

    return true;
}

static bool ReadIntMember(
    const rapidjson::Value& object,
    const char* name,
    int* out) {
    if (!out || !object.IsObject()) {
        return false;
    }

    const auto member = object.FindMember(name);
    if (member == object.MemberEnd() || !member->value.IsInt()) {
        return false;
    }

    *out = member->value.GetInt();
    return true;
}

static bool ReadOrderKey(const rapidjson::Value& value, OrderKey* out) {
    if (!out || !value.IsObject()) {
        return false;
    }

    OrderKey order;
    if (!ReadIntMember(value, "label_index", &order.label_index)
        || !ReadIntMember(value, "page_no", &order.page_no)
        || !ReadIntMember(value, "cmd_index", &order.cmd_index)
        || !ReadIntMember(value, "sub_index", &order.sub_index)) {
        return false;
    }

    *out = order;
    return true;
}

static bool SameOrderKey(const OrderKey& left, const OrderKey& right) {
    return left.label_index == right.label_index
        && left.page_no == right.page_no
        && left.cmd_index == right.cmd_index
        && left.sub_index == right.sub_index;
}

static bool ReadSeqToOrder(
    const rapidjson::Value& value,
    std::vector<OrderKey>* out) {
    if (!out || !value.IsArray()
        || value.Size() > static_cast<rapidjson::SizeType>(std::numeric_limits<int>::max())) {
        return false;
    }

    out->clear();
    out->reserve(value.Size());

    int expected_seq = 1;
    for (const rapidjson::Value& seq_item : value.GetArray()) {
        if (!seq_item.IsObject()) {
            return false;
        }

        const auto seq_member = seq_item.FindMember("seq");
        const auto order_member = seq_item.FindMember("order");
        if (seq_member == seq_item.MemberEnd()
            || !seq_member->value.IsInt()
            || seq_member->value.GetInt() != expected_seq
            || order_member == seq_item.MemberEnd()) {
            return false;
        }

        OrderKey order;
        if (!ReadOrderKey(order_member->value, &order)) {
            return false;
        }

        out->push_back(order);
        ++expected_seq;
    }

    return true;
}

static bool JsonStringEquals(const rapidjson::Value& value, const char* expected) {
    const size_t expected_size = std::strlen(expected);
    return value.IsString()
        && value.GetStringLength() == expected_size
        && std::memcmp(value.GetString(), expected, expected_size) == 0;
}

static bool HasStringMember(const rapidjson::Value& object, const char* name) {
    if (!object.IsObject()) {
        return false;
    }

    const auto member = object.FindMember(name);
    return member != object.MemberEnd() && member->value.IsString();
}

static bool ConvertExistingSceneItemToApi(
    rapidjson::Value& item,
    const std::vector<OrderKey>& seq_to_order,
    size_t* cursor,
    rapidjson::Document::AllocatorType& alloc);

static bool ConvertExistingSceneItemsToApi(
    rapidjson::Value& items,
    const std::vector<OrderKey>& seq_to_order,
    size_t* cursor,
    rapidjson::Document::AllocatorType& alloc) {
    if (!cursor || !items.IsArray()) {
        return false;
    }

    for (rapidjson::Value& item : items.GetArray()) {
        if (!ConvertExistingSceneItemToApi(item, seq_to_order, cursor, alloc)) {
            return false;
        }
    }

    return true;
}

static bool ConvertExistingTextToApi(
    rapidjson::Value& item,
    const std::vector<OrderKey>& seq_to_order,
    size_t* cursor,
    rapidjson::Document::AllocatorType& alloc) {
    if (!cursor || !item.IsObject()
        || !HasStringMember(item, "speaker")
        || !HasStringMember(item, "text")
        || item.HasMember("seq")) {
        return false;
    }

    const auto order_member = item.FindMember("order");
    const auto translations_member = item.FindMember("translations");
    if (order_member == item.MemberEnd()
        || translations_member == item.MemberEnd()
        || !translations_member->value.IsObject()
        || *cursor >= seq_to_order.size()) {
        return false;
    }

    OrderKey order;
    if (!ReadOrderKey(order_member->value, &order)
        || !SameOrderKey(order, seq_to_order[*cursor])) {
        return false;
    }

    const int seq = static_cast<int>(*cursor + 1);
    item.RemoveMember("order");
    item.RemoveMember("translations");
    item.AddMember("seq", seq, alloc);
    ++(*cursor);
    return true;
}

static bool RemoveStructuralOrder(rapidjson::Value& item) {
    if (!item.IsObject() || item.HasMember("seq")) {
        return false;
    }

    const auto order_member = item.FindMember("order");
    OrderKey order;
    if (order_member == item.MemberEnd()
        || !ReadOrderKey(order_member->value, &order)) {
        return false;
    }

    item.RemoveMember("order");
    return true;
}

static bool ConvertExistingChoiceToApi(
    rapidjson::Value& item,
    const std::vector<OrderKey>& seq_to_order,
    size_t* cursor,
    rapidjson::Document::AllocatorType& alloc) {
    if (!HasStringMember(item, "merge_label") || !RemoveStructuralOrder(item)) {
        return false;
    }

    const auto branches_member = item.FindMember("branches");
    if (branches_member == item.MemberEnd() || !branches_member->value.IsArray()) {
        return false;
    }

    for (rapidjson::Value& branch : branches_member->value.GetArray()) {
        if (!branch.IsObject() || !HasStringMember(branch, "target_label")) {
            return false;
        }

        const auto options_member = branch.FindMember("options");
        const auto following_member = branch.FindMember("following_text");
        if (options_member == branch.MemberEnd()
            || following_member == branch.MemberEnd()
            || !options_member->value.IsArray()
            || options_member->value.Empty()
            || !following_member->value.IsArray()) {
            return false;
        }

        for (rapidjson::Value& option : options_member->value.GetArray()) {
            if (!option.IsObject()) {
                return false;
            }

            const auto option_type = option.FindMember("type");
            if (option_type == option.MemberEnd()
                || !JsonStringEquals(option_type->value, "text")
                || !ConvertExistingTextToApi(option, seq_to_order, cursor, alloc)) {
                return false;
            }
        }

        if (!ConvertExistingSceneItemsToApi(
                following_member->value,
                seq_to_order,
                cursor,
                alloc)) {
            return false;
        }
    }

    return true;
}

static bool ConvertExistingIfToApi(
    rapidjson::Value& item,
    const std::vector<OrderKey>& seq_to_order,
    size_t* cursor,
    rapidjson::Document::AllocatorType& alloc) {
    if (!HasStringMember(item, "condition")
        || !HasStringMember(item, "target_label")
        || !HasStringMember(item, "merge_label")
        || !RemoveStructuralOrder(item)) {
        return false;
    }

    const auto following_member = item.FindMember("following_text");
    return following_member != item.MemberEnd()
        && ConvertExistingSceneItemsToApi(
            following_member->value,
            seq_to_order,
            cursor,
            alloc);
}

static bool ConvertExistingSceneItemToApi(
    rapidjson::Value& item,
    const std::vector<OrderKey>& seq_to_order,
    size_t* cursor,
    rapidjson::Document::AllocatorType& alloc) {
    if (!item.IsObject()) {
        return false;
    }

    const auto type_member = item.FindMember("type");
    if (type_member == item.MemberEnd() || !type_member->value.IsString()) {
        return false;
    }

    if (JsonStringEquals(type_member->value, "text")) {
        return ConvertExistingTextToApi(item, seq_to_order, cursor, alloc);
    }

    if (JsonStringEquals(type_member->value, "choice")) {
        return ConvertExistingChoiceToApi(item, seq_to_order, cursor, alloc);
    }

    if (JsonStringEquals(type_member->value, "if")) {
        return ConvertExistingIfToApi(item, seq_to_order, cursor, alloc);
    }

    return false;
}

static bool JavaStoreScene(const char* bytes, size_t size) {
    if (!bytes
        || size == 0
        || size > static_cast<size_t>(std::numeric_limits<jsize>::max())
        || !g_java_bridge.jvm
        || !g_java_bridge.main_hook_class
        || !g_java_bridge.store_scene_method) {
        return false;
    }

    JNIEnv* env = nullptr;
    bool attached_here = false;
    if (g_java_bridge.jvm->GetEnv(
            reinterpret_cast<void**>(&env),
            JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_bridge.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("[JsonHandler] Failed to attach scene mirror worker to JVM");
            return false;
        }
        attached_here = true;
    }

    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (!array) {
        if (attached_here) {
            g_java_bridge.jvm->DetachCurrentThread();
        }
        return false;
    }

    env->SetByteArrayRegion(
        array,
        0,
        static_cast<jsize>(size),
        reinterpret_cast<const jbyte*>(bytes)
    );
    jboolean stored = env->CallStaticBooleanMethod(
        g_java_bridge.main_hook_class,
        g_java_bridge.store_scene_method,
        array
    );
    env->DeleteLocalRef(array);

    bool success = stored == JNI_TRUE;
    if (env->ExceptionCheck()) {
        LOGE("[JsonHandler] Exception while mirroring scene to Java");
        env->ExceptionClear();
        success = false;
    }

    if (attached_here) {
        g_java_bridge.jvm->DetachCurrentThread();
    }
    return success;
}

class JsonHandler {
public:
    void Start() {
        std::call_once(start_once_, [this]{
            std::thread(&JsonHandler::JsonWorker, this).detach();
            std::thread(&JsonHandler::ApiWorker, this).detach();
            LOGI("[JsonHandler] Started");
        });
    }

    void SubmitToJson(JsonItem& json_item) {
        {
            std::lock_guard<std::mutex> lock(json_mutex_);
            json_queue_.push_back(std::make_shared<JsonItem>(std::move(json_item)));
        }

        json_cv_.notify_one();
    }

    void SubmitToApi(ApiItem api_item) {
        if (g_runtime_config.parse_only_debug) {
            LOGI(
                "[JsonHandler] Parse-only debug enabled, skipping API submission scene=%s targets=%zu",
                api_item.scene_name.c_str(),
                api_item.seq_to_order.size()
            );
            return;
        }

        {
            std::lock_guard<std::mutex> lock(api_mutex_);
            api_queue_.push_back(std::make_shared<ApiItem>(std::move(api_item)));
        }

        api_cv_.notify_one();
    }

private:
    static bool IsCapturePaused() {
        return stop_reason.load(std::memory_order_acquire) == StopReason::user_pause;
    }

    void ProcessItem(
        const JsonItem& json_item,
        rapidjson::Document& out,
        rapidjson::Document& api_out,
        ApiBuildContext& api_context) {
        if (!json_item.scene) {
            return;
        }

        auto& alloc = out.GetAllocator();
        auto& api_alloc = api_out.GetAllocator();

        out.CopyFrom(json_item.process_doc, alloc);
        api_out.CopyFrom(json_item.process_doc, api_alloc);
        api_out.RemoveMember("translated");
        out.AddMember("protect", JsonProtectedTokenArray(json_item.scene->protect, alloc), alloc);
        api_out.AddMember("protect", JsonProtectedTokenArray(json_item.scene->protect, api_alloc), api_alloc);

        rapidjson::Value json_items(rapidjson::kArrayType);
        rapidjson::Value api_items(rapidjson::kArrayType);

        for (const SceneItem& item : json_item.scene->scene_items) {
            JsonSceneResItem scene_item = JsonSceneItemArray(item, alloc, api_alloc, api_context);

            json_items.PushBack(scene_item.scene_item, alloc);
            api_items.PushBack(scene_item.api_item, api_alloc);
        }

        rapidjson::Value seq_to_order(rapidjson::kArrayType);

        int seq = 1;
        for (const OrderKey& order : api_context.seq_to_order) {
            rapidjson::Value seq_item(rapidjson::kObjectType);
            seq_item.AddMember("seq", seq++, alloc);
            seq_item.AddMember("order", JsonOrderKey(order, alloc), alloc);

            seq_to_order.PushBack(seq_item, alloc);
        }

        out.AddMember("seq_to_order", seq_to_order, alloc);
        out.AddMember("summary", "", alloc);
        out.AddMember("scene_items", json_items, alloc);
        api_out.AddMember("scene_items", api_items, api_alloc);

    }

    bool WriteJsonToFile(const rapidjson::Document& doc, const std::string& scene_name) {
        const auto& base_dir = g_runtime_config.base_dir;
        if (!doc.IsObject() || base_dir.empty() || scene_name.empty()) {
            return false;
        }

        std::string path = FormalPath(scene_name);
        
        if (path.empty()) {
            return false;
        } else if (HaveFile(path)) {
            if (!g_runtime_config.overwrite_existing) {
                LOGI("[JsonHandler] Scene %s already exists, skipping", scene_name.c_str());
                return true;
            } 
        }

        const std::string temp_path = path + ".tmp";
        const std::string final_path = path + ".json";

        rapidjson::StringBuffer buffer;
        rapidjson::PrettyWriter<rapidjson::StringBuffer> writer(buffer);

        if (!doc.Accept(writer)) {
            LOGE("[JsonHandler] Failed to write JSON for scene %s", scene_name.c_str());
            return false;
        }

        std::ofstream file(temp_path, std::ios::binary | std::ios::trunc);
        if (!file.is_open()) {
            LOGE("[JsonHandler] Failed to open file %s for writing: %s", temp_path.c_str(), std::strerror(errno));
            return false;
        }

        file.write(buffer.GetString(), static_cast<std::streamsize>(buffer.GetSize()));
        file.flush();

        if (!file.good()) {
            LOGE("[JsonHandler] Failed to write JSON to file %s: %s", temp_path.c_str(), std::strerror(errno));
            file.close();
            std::remove(temp_path.c_str());
            return false;
        }

        file.close();

        if (file.fail()) {
            LOGE("[JsonHandler] Failed to close JSON to file %s: %s", temp_path.c_str(), std::strerror(errno));
            std::remove(temp_path.c_str());
            return false;
        }

        if (std::rename(temp_path.c_str(), final_path.c_str()) != 0) {
            LOGE("[JsonHandler] Failed to rename temp file %s to %s: %s", temp_path.c_str(), final_path.c_str(), std::strerror(errno));
            std::remove(temp_path.c_str());
            return false;
        }

        LOGI(
            "[JsonHandler] scene written scene=%s path=%s bytes=%zu",
            scene_name.c_str(),
            final_path.c_str(),
            buffer.GetSize()
        );

        if (!JavaStoreScene(buffer.GetString(), buffer.GetSize())) {
            LOGW(
                "[JsonHandler] Scene was written locally but mirror update failed scene=%s",
                scene_name.c_str()
            );
        }

        return true;
    }

    void JsonWorker() {
        while (true) {
            std::shared_ptr<const JsonItem> json_item;

            {
                std::unique_lock<std::mutex> lock(json_mutex_);

                json_cv_.wait(lock, [this]{
                    return IsCapturePaused() || !json_queue_.empty();
                });

                if (IsCapturePaused()) {
                    json_queue_.clear();
                    json_cv_.wait(lock, [this]{
                        return !IsCapturePaused();
                    });
                    continue;
                }

                json_item = json_queue_.front();
                json_queue_.pop_front();

            }

            rapidjson::Document doc;
            rapidjson::Document api_doc;
            doc.SetObject();
            api_doc.SetObject();
            ApiBuildContext api_context;
            ApiItem api_item;
            api_item.scene_name = json_item->scene_name;

            ProcessItem(*json_item, doc, api_doc, api_context);
            
            if (!g_runtime_config.parse_only_debug) {
                rapidjson::StringBuffer api_buffer;
                rapidjson::PrettyWriter<rapidjson::StringBuffer> api_writer(api_buffer);
                if (api_doc.Accept(api_writer)) {
                    api_item.api_doc.assign(api_buffer.GetString(), api_buffer.GetSize());
                } else {
                    LOGE("[JsonHandler] Failed to serialize API JSON for scene %s", json_item->scene_name.c_str());
                }
            }

            api_item.seq_to_order = std::move(api_context.seq_to_order);

            bool writen = false;
            for (int i = 0; i < 3; i++) {
                writen = WriteJsonToFile(doc, json_item->scene_name);
                if (writen) break; 
            }

            if (writen) {
                SubmitToApi(std::move(api_item));
            } else {
                LOGE("[JsonHandler] Failed to write JSON for scene %s after 3 attempts", json_item->scene_name.c_str());
            }
        }
    }

    static bool JavaRequestTrans(const std::string& request, std::string* response) {
        if (!response || !g_java_bridge.jvm || !g_java_bridge.main_hook_class || !g_java_bridge.request_api_method) {
            return false;
        }

        JNIEnv* env = nullptr;
        bool is_attached = false;

        if (g_java_bridge.jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_java_bridge.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("[JsonHandler] Failed to attach current thread to JVM");
                return false;
            }
            is_attached = true;
        }

        jbyteArray request_array = env->NewByteArray(static_cast<jsize>(request.size()));

        if (!request_array) {
            if (is_attached) {
                g_java_bridge.jvm->DetachCurrentThread();
            }
            return false;
        }

        env->SetByteArrayRegion(request_array, 0, static_cast<jsize>(request.size()), reinterpret_cast<const jbyte*>(request.data()));

        auto response_array = static_cast<jbyteArray>(env->CallStaticObjectMethod(
            g_java_bridge.main_hook_class,
            g_java_bridge.request_api_method,
            request_array
        ));

        env->DeleteLocalRef(request_array);

        bool success = false;

        if (env->ExceptionCheck()) {
            LOGE("[JsonHandler] Exception occurred while calling Java method");
            env->ExceptionClear();
        } else if (response_array) {
            const jsize size = env->GetArrayLength(response_array);

            response->resize(static_cast<size_t>(size));

            env->GetByteArrayRegion(response_array, 0, size, reinterpret_cast<jbyte*>(response->data()));

            env->DeleteLocalRef(response_array);
            success = true;
        }

        if (is_attached) {
            g_java_bridge.jvm->DetachCurrentThread();
        }

        return success;
    }

    void ApiWorker() {
        while (true) {
            std::shared_ptr<const ApiItem> api_item;

            {
                std::unique_lock<std::mutex> lock(api_mutex_);

                api_cv_.wait(lock, [this]{
                    return IsCapturePaused() || !api_queue_.empty();
                });

                if (IsCapturePaused()) {
                    api_queue_.clear();
                    api_cv_.wait(lock, [this]{
                        return !IsCapturePaused();
                    });
                    continue;
                }

                api_item = api_queue_.front();
                api_queue_.pop_front();

            }

            std::string response;
            if (!JavaRequestTrans(api_item->api_doc, &response)) {
                LOGE(
                    "[JsonHandler] Failed to send API request scene=%s targets=%zu",
                    api_item->scene_name.c_str(),
                    api_item->seq_to_order.size()
                );
                continue;
            } else {
                LOGI(
                    "[JsonHandler] API request sent scene=%s targets=%zu response_size=%zu",
                    api_item->scene_name.c_str(),
                    api_item->seq_to_order.size(),
                    response.size()
                );
            }
        }
    }



private:
    std::once_flag start_once_;

    std::mutex json_mutex_;
    std::condition_variable json_cv_;
    std::deque<std::shared_ptr<const JsonItem>> json_queue_;

    std::mutex api_mutex_;
    std::condition_variable api_cv_;
    std::deque<std::shared_ptr<const ApiItem>> api_queue_;

    
};

} // namespace

static JsonHandler g_json_handler;

SceneFileStatus CheckFileStatus(const std::string& scene_name) {
    rapidjson::Document doc;

    if (ReadFile(scene_name, doc)) {
        bool translated =
            doc.FindMember("translated") != doc.MemberEnd() &&
            doc["translated"].IsBool() &&
            doc["translated"].GetBool();

        if (translated) {
            return SceneFileStatus::complete;
        } else {
            return SceneFileStatus::pending;
        }
    } else return SceneFileStatus::not_found;
}

bool LoadFromExistingScene(std::string entry_label) {
    if (entry_label.empty()) {
        LOGW("[JsonHandler] LoadFromExistingScene called with empty entry_label");
        return false;
    }

    if (g_runtime_config.parse_only_debug) {
        LOGI(
            "[JsonHandler] Parse-only debug enabled, keeping pending scene without API submission scene=%s",
            entry_label.c_str()
        );
        return true;
    }

    rapidjson::Document doc;
    if (!ReadFile(entry_label, doc)) {
        LOGW("[JsonHandler] LoadFromExistingScene failed to read scene %s", entry_label.c_str());
        return false;
    }

    const auto translated_member = doc.FindMember("translated");
    const auto seq_to_order_member = doc.FindMember("seq_to_order");
    const auto scene_items_member = doc.FindMember("scene_items");
    const auto summary_member = doc.FindMember("summary");
    if (translated_member == doc.MemberEnd()
        || !translated_member->value.IsBool()
        || translated_member->value.GetBool()
        || seq_to_order_member == doc.MemberEnd()
        || !seq_to_order_member->value.IsArray()
        || scene_items_member == doc.MemberEnd()
        || !scene_items_member->value.IsArray()
        || summary_member == doc.MemberEnd()
        || !summary_member->value.IsString()) {
        LOGW(
            "[JsonHandler] LoadFromExistingScene failed: invalid pending scene root scene=%s",
            entry_label.c_str()
        );
        return false;
    }

    ApiItem api_item;
    api_item.scene_name = entry_label;
    if (!ReadSeqToOrder(seq_to_order_member->value, &api_item.seq_to_order)) {
        LOGW(
            "[JsonHandler] LoadFromExistingScene failed: invalid seq_to_order scene=%s",
            entry_label.c_str()
        );
        return false;
    }

    size_t cursor = 0;
    auto& alloc = doc.GetAllocator();
    if (!ConvertExistingSceneItemsToApi(
            scene_items_member->value,
            api_item.seq_to_order,
            &cursor,
            alloc)
        || cursor != api_item.seq_to_order.size()) {
        LOGW(
            "[JsonHandler] LoadFromExistingScene failed: scene_items and seq_to_order do not match scene=%s converted=%zu targets=%zu",
            entry_label.c_str(),
            cursor,
            api_item.seq_to_order.size()
        );
        return false;
    }

    doc.RemoveMember("seq_to_order");
    doc.RemoveMember("summary");
    doc.RemoveMember("translated");

    rapidjson::StringBuffer api_buffer;
    rapidjson::Writer<rapidjson::StringBuffer> api_writer(api_buffer);
    if (!doc.Accept(api_writer)) {
        LOGE(
            "[JsonHandler] LoadFromExistingScene failed to serialize API JSON scene=%s",
            entry_label.c_str()
        );
        return false;
    }

    api_item.api_doc.assign(api_buffer.GetString(), api_buffer.GetSize());
    g_json_handler.Start();
    g_json_handler.SubmitToApi(std::move(api_item));
    return true;
}

void SubmitToJsonHandler(std::shared_ptr<const Scene> scene) {
    if (!scene) {
        return;
    }

    g_json_handler.Start();
    JsonItem json_item;
    json_item.scene_name = scene->scene;
    
    json_item.process_doc.SetObject();

    auto& alloc = json_item.process_doc.GetAllocator();

    json_item.process_doc.AddMember("scene", JsonString(scene->scene, alloc), alloc);
    json_item.process_doc.AddMember("game_version", JsonString(g_runtime_config.game_version, alloc), alloc);
    json_item.process_doc.AddMember("raw_lang", JsonString(scene->raw_lang, alloc), alloc);
    json_item.process_doc.AddMember("target_lang", JsonString(scene->target_lang, alloc), alloc);
    json_item.process_doc.AddMember("translated", false, alloc);
    json_item.process_doc.AddMember("character", JsonCharacter(scene->character, alloc), alloc);
    json_item.process_doc.AddMember("mentioned_characters", JsonMentionedCharacterArray(scene->mentioned_characters, alloc), alloc);
    json_item.process_doc.AddMember("game_terms", JsonGameTermArray(scene->game_terms, alloc), alloc);

    json_item.scene = std::move(scene);

    g_json_handler.SubmitToJson(json_item);
}
