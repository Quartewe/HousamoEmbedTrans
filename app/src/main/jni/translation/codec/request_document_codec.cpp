#include "document_codec.hpp"
#include "document_codec_internal.hpp"
#include "scene/scene_identity.hpp"

#include <rapidjson/document.h>
#include <rapidjson/stringbuffer.h>
#include <rapidjson/writer.h>

#include <algorithm>
#include <cstring>
#include <unordered_set>
#include <utility>
#include <vector>

namespace het::translation::document_codec {
namespace {

using Allocator = rapidjson::Document::AllocatorType;

struct ExistingConversionContext {
    const std::vector<OrderKey>& seq_to_order;
    size_t cursor = 0;
};

bool Fail(std::string* error, const std::string& message) {
    if (error) {
        *error = message;
    }
    return false;
}

rapidjson::Value JsonString(const std::string& value, Allocator& allocator) {
    return rapidjson::Value(
        value.data(),
        static_cast<rapidjson::SizeType>(value.size()),
        allocator);
}

bool StringEquals(const rapidjson::Value& value, const char* expected) {
    const size_t size = std::strlen(expected);
    return value.IsString()
        && value.GetStringLength() == size
        && std::memcmp(value.GetString(), expected, size) == 0;
}

bool ReadRequiredString(
    const rapidjson::Value& object,
    const char* name,
    std::string* output) {
    if (!object.IsObject() || !output) {
        return false;
    }
    const auto member = object.FindMember(name);
    if (member == object.MemberEnd() || !member->value.IsString()) {
        return false;
    }
    output->assign(
        member->value.GetString(),
        member->value.GetStringLength());
    return true;
}

bool CopyRequiredMember(
    const rapidjson::Value& source,
    const char* name,
    rapidjson::Type expected_type,
    rapidjson::Value* destination,
    Allocator& allocator) {
    const auto member = source.FindMember(name);
    if (member == source.MemberEnd()
        || member->value.GetType() != expected_type) {
        return false;
    }
    rapidjson::Value copy;
    copy.CopyFrom(member->value, allocator);
    destination->AddMember(rapidjson::StringRef(name), copy, allocator);
    return true;
}

bool ConvertExistingItems(
    const rapidjson::Value& source,
    rapidjson::Value* destination,
    ExistingConversionContext* context,
    Allocator& allocator,
    std::string* error);

bool ConvertExistingText(
    const rapidjson::Value& source,
    rapidjson::Value* destination,
    ExistingConversionContext* context,
    Allocator& allocator,
    std::string* error) {
    if (!source.IsObject() || !destination || !context) {
        return Fail(error, "invalid text item");
    }
    std::string speaker;
    std::string text;
    if (!ReadRequiredString(source, "speaker", &speaker)
        || !ReadRequiredString(source, "text", &text)) {
        return Fail(error, "text item is missing speaker or text");
    }
    const auto order_member = source.FindMember("order");
    const auto translations_member = source.FindMember("translations");
    if (order_member == source.MemberEnd()
        || translations_member == source.MemberEnd()
        || !internal::IsLanguageStringMap(translations_member->value)
        || context->cursor >= context->seq_to_order.size()) {
        return Fail(error, "text item has invalid local metadata");
    }

    OrderKey order;
    if (!internal::ParseOrderKey(order_member->value, &order)
        || !(order == context->seq_to_order[context->cursor])) {
        return Fail(error, "text OrderKey does not match seq_to_order");
    }

    destination->SetObject();
    destination->AddMember("type", "text", allocator);
    destination->AddMember(
        "seq",
        static_cast<int>(context->cursor + 1),
        allocator);
    destination->AddMember("speaker", JsonString(speaker, allocator), allocator);
    destination->AddMember("text", JsonString(text, allocator), allocator);
    ++context->cursor;
    return true;
}

bool ReadStructuralOrder(const rapidjson::Value& source) {
    if (!source.IsObject()) {
        return false;
    }
    const auto order = source.FindMember("order");
    OrderKey parsed;
    return order != source.MemberEnd()
        && internal::ParseOrderKey(order->value, &parsed);
}

bool ConvertExistingChoice(
    const rapidjson::Value& source,
    rapidjson::Value* destination,
    ExistingConversionContext* context,
    Allocator& allocator,
    std::string* error) {
    std::string merge_label;
    if (!ReadStructuralOrder(source)
        || !ReadRequiredString(source, "merge_label", &merge_label)) {
        return Fail(error, "choice has invalid order or merge_label");
    }
    const auto branches = source.FindMember("branches");
    if (branches == source.MemberEnd() || !branches->value.IsArray()
        || branches->value.Empty()) {
        return Fail(error, "choice has no branches");
    }

    destination->SetObject();
    destination->AddMember("type", "choice", allocator);
    destination->AddMember(
        "merge_label",
        JsonString(merge_label, allocator),
        allocator);
    rapidjson::Value output_branches(rapidjson::kArrayType);

    for (const rapidjson::Value& branch : branches->value.GetArray()) {
        std::string target_label;
        if (!branch.IsObject()
            || !ReadRequiredString(branch, "target_label", &target_label)) {
            return Fail(error, "choice branch has invalid target_label");
        }
        const auto options = branch.FindMember("options");
        const auto following = branch.FindMember("following_text");
        if (options == branch.MemberEnd() || !options->value.IsArray()
            || options->value.Empty()
            || following == branch.MemberEnd()
            || !following->value.IsArray()) {
            return Fail(error, "choice branch has invalid options or following_text");
        }

        rapidjson::Value output_branch(rapidjson::kObjectType);
        output_branch.AddMember(
            "target_label",
            JsonString(target_label, allocator),
            allocator);
        rapidjson::Value output_options(rapidjson::kArrayType);
        for (const rapidjson::Value& option : options->value.GetArray()) {
            if (!option.IsObject()) {
                return Fail(error, "choice option is not a text item");
            }
            const auto type = option.FindMember("type");
            if (type == option.MemberEnd()
                || !StringEquals(type->value, "text")) {
                return Fail(error, "choice option is not a text item");
            }
            rapidjson::Value output_option;
            if (!ConvertExistingText(
                    option,
                    &output_option,
                    context,
                    allocator,
                    error)) {
                return false;
            }
            output_options.PushBack(output_option, allocator);
        }
        output_branch.AddMember("options", output_options, allocator);

        rapidjson::Value output_following;
        if (!ConvertExistingItems(
                following->value,
                &output_following,
                context,
                allocator,
                error)) {
            return false;
        }
        output_branch.AddMember(
            "following_text",
            output_following,
            allocator);
        output_branches.PushBack(output_branch, allocator);
    }
    destination->AddMember("branches", output_branches, allocator);
    return true;
}

bool ConvertExistingIf(
    const rapidjson::Value& source,
    rapidjson::Value* destination,
    ExistingConversionContext* context,
    Allocator& allocator,
    std::string* error) {
    std::string condition;
    std::string target_label;
    std::string merge_label;
    if (!ReadStructuralOrder(source)
        || !ReadRequiredString(source, "condition", &condition)
        || !ReadRequiredString(source, "target_label", &target_label)
        || !ReadRequiredString(source, "merge_label", &merge_label)) {
        return Fail(error, "if item has invalid structural fields");
    }
    const auto following = source.FindMember("following_text");
    if (following == source.MemberEnd() || !following->value.IsArray()) {
        return Fail(error, "if item has invalid following_text");
    }

    destination->SetObject();
    destination->AddMember("type", "if", allocator);
    destination->AddMember(
        "condition",
        JsonString(condition, allocator),
        allocator);
    destination->AddMember(
        "target_label",
        JsonString(target_label, allocator),
        allocator);
    destination->AddMember(
        "merge_label",
        JsonString(merge_label, allocator),
        allocator);
    rapidjson::Value output_following;
    if (!ConvertExistingItems(
            following->value,
            &output_following,
            context,
            allocator,
            error)) {
        return false;
    }
    destination->AddMember(
        "following_text",
        output_following,
        allocator);
    return true;
}

bool ConvertExistingItems(
    const rapidjson::Value& source,
    rapidjson::Value* destination,
    ExistingConversionContext* context,
    Allocator& allocator,
    std::string* error) {
    if (!source.IsArray() || !destination || !context) {
        return Fail(error, "scene_items is not an array");
    }
    destination->SetArray();
    for (const rapidjson::Value& item : source.GetArray()) {
        if (!item.IsObject()) {
            return Fail(error, "scene item is not an object");
        }
        const auto type = item.FindMember("type");
        if (type == item.MemberEnd() || !type->value.IsString()) {
            return Fail(error, "scene item has no type");
        }

        rapidjson::Value output;
        bool converted = false;
        if (StringEquals(type->value, "text")) {
            converted = ConvertExistingText(
                item,
                &output,
                context,
                allocator,
                error);
        } else if (StringEquals(type->value, "choice")) {
            converted = ConvertExistingChoice(
                item,
                &output,
                context,
                allocator,
                error);
        } else if (StringEquals(type->value, "if")) {
            converted = ConvertExistingIf(
                item,
                &output,
                context,
                allocator,
                error);
        } else {
            return Fail(error, "scene item has unknown type");
        }
        if (!converted) {
            return false;
        }
        destination->PushBack(output, allocator);
    }
    return true;
}

bool MemberNameLess(
    const rapidjson::Value::ConstMemberIterator& left,
    const rapidjson::Value::ConstMemberIterator& right) {
    const char* left_begin = left->name.GetString();
    const char* right_begin = right->name.GetString();
    const char* left_end = left_begin + left->name.GetStringLength();
    const char* right_end = right_begin + right->name.GetStringLength();
    return std::lexicographical_compare(
        left_begin,
        left_end,
        right_begin,
        right_end);
}

bool WriteCanonicalValue(
    const rapidjson::Value& value,
    rapidjson::Writer<rapidjson::StringBuffer>* writer) {
    if (value.IsNull()) {
        return writer->Null();
    }
    if (value.IsBool()) {
        return writer->Bool(value.GetBool());
    }
    if (value.IsInt()) {
        return writer->Int(value.GetInt());
    }
    if (value.IsUint()) {
        return writer->Uint(value.GetUint());
    }
    if (value.IsInt64()) {
        return writer->Int64(value.GetInt64());
    }
    if (value.IsUint64()) {
        return writer->Uint64(value.GetUint64());
    }
    if (value.IsDouble()) {
        return writer->Double(value.GetDouble());
    }
    if (value.IsString()) {
        return writer->String(value.GetString(), value.GetStringLength());
    }
    if (value.IsArray()) {
        if (!writer->StartArray()) {
            return false;
        }
        for (const rapidjson::Value& item : value.GetArray()) {
            if (!WriteCanonicalValue(item, writer)) {
                return false;
            }
        }
        return writer->EndArray(value.Size());
    }
    if (value.IsObject()) {
        if (!writer->StartObject()) {
            return false;
        }
        std::vector<rapidjson::Value::ConstMemberIterator> members;
        members.reserve(value.MemberCount());
        for (auto member = value.MemberBegin(); member != value.MemberEnd(); ++member) {
            members.push_back(member);
        }
        std::sort(members.begin(), members.end(), MemberNameLess);
        for (const auto& member : members) {
            if (!writer->Key(
                    member->name.GetString(),
                    member->name.GetStringLength())
                || !WriteCanonicalValue(member->value, writer)) {
                return false;
            }
        }
        return writer->EndObject(value.MemberCount());
    }
    return false;
}

}  // namespace

bool NormalizeRequestPayload(
    const std::string& request_json,
    std::string* normalized_json,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (!normalized_json || request_json.empty()) {
        return Fail(error, "request JSON is empty");
    }

    rapidjson::Document document;
    document.Parse(request_json.data(), request_json.size());
    if (document.HasParseError() || !document.IsObject()) {
        return Fail(error, "request JSON is invalid");
    }

    rapidjson::StringBuffer buffer;
    rapidjson::Writer<rapidjson::StringBuffer> writer(buffer);
    if (!WriteCanonicalValue(document, &writer)) {
        return Fail(error, "request JSON could not be canonicalized");
    }
    normalized_json->assign(buffer.GetString(), buffer.GetSize());
    return true;
}

bool BuildRequestFromExistingScene(
    const std::string& scene_json,
    const std::string& target_lang,
    TranslationRequest* request,
    std::string* error,
    bool allow_already_translated) {
    if (error) {
        error->clear();
    }
    if (!request || scene_json.empty() || target_lang.empty()) {
        return Fail(error, "scene JSON, target language, or output is empty");
    }

    rapidjson::Document scene;
    scene.Parse(scene_json.data(), scene_json.size());
    if (scene.HasParseError() || !scene.IsObject()) {
        return Fail(error, "scene JSON is invalid");
    }

    std::string scene_name;
    std::string game_version;
    std::string raw_lang;
    if (!ReadRequiredString(scene, "scene", &scene_name)
        || !scene_identity::IsValid(scene_name)
        || !ReadRequiredString(scene, "game_version", &game_version)
        || game_version.empty()
        || !ReadRequiredString(scene, "raw_lang", &raw_lang)
        || raw_lang.empty()) {
        return Fail(error, "scene identity fields are invalid");
    }

    const auto translated = scene.FindMember("translated");
    const auto provider = scene.FindMember("provider");
    const auto model = scene.FindMember("model");
    const auto summary = scene.FindMember("summary");
    const auto seq_to_order = scene.FindMember("seq_to_order");
    const auto scene_items = scene.FindMember("scene_items");
    const auto protect = scene.FindMember("protect");
    if (translated == scene.MemberEnd()
        || !internal::IsLanguageBooleanMap(translated->value)
        || (!allow_already_translated
            && internal::IsTargetTranslated(translated->value, target_lang))
        || provider == scene.MemberEnd()
        || !internal::IsLanguageStringMap(provider->value)
        || model == scene.MemberEnd()
        || !internal::IsLanguageStringMap(model->value)
        || summary == scene.MemberEnd()
        || !internal::IsLanguageStringMap(summary->value)
        || seq_to_order == scene.MemberEnd()
        || scene_items == scene.MemberEnd()
        || protect == scene.MemberEnd()
        || !protect->value.IsArray()) {
        return Fail(error, "scene root metadata is invalid or already translated");
    }

    std::vector<OrderKey> mapping;
    if (!internal::ParseSeqToOrder(seq_to_order->value, &mapping)) {
        return Fail(error, "seq_to_order is invalid");
    }
    std::unordered_set<OrderKey, OrderKeyHash> mapped_orders(
        mapping.begin(),
        mapping.end());

    rapidjson::Document api;
    api.SetObject();
    Allocator& allocator = api.GetAllocator();
    api.AddMember("scene", JsonString(scene_name, allocator), allocator);
    api.AddMember("game_version", JsonString(game_version, allocator), allocator);
    api.AddMember("raw_lang", JsonString(raw_lang, allocator), allocator);
    if (!CopyRequiredMember(
            scene,
            "character",
            rapidjson::kObjectType,
            &api,
            allocator)
        || !CopyRequiredMember(
            scene,
            "mentioned_characters",
            rapidjson::kArrayType,
            &api,
            allocator)
        || !CopyRequiredMember(
            scene,
            "game_terms",
            rapidjson::kArrayType,
            &api,
            allocator)) {
        return Fail(error, "scene context fields are invalid");
    }

    rapidjson::Value request_protect(rapidjson::kArrayType);
    for (const rapidjson::Value& token : protect->value.GetArray()) {
        std::string label;
        std::string origin;
        if (!token.IsObject()
            || !ReadRequiredString(token, "label", &label) || label.empty()
            || !ReadRequiredString(token, "origin", &origin) || origin.empty()) {
            return Fail(error, "protect token is invalid");
        }
        const auto order_member = token.FindMember("order");
        OrderKey order;
        if (order_member == token.MemberEnd()
            || !internal::ParseOrderKey(order_member->value, &order)
            || mapped_orders.find(order) == mapped_orders.end()) {
            return Fail(error, "protect token OrderKey is not translatable");
        }
        rapidjson::Value output_token(rapidjson::kObjectType);
        output_token.AddMember("label", JsonString(label, allocator), allocator);
        output_token.AddMember("origin", JsonString(origin, allocator), allocator);
        request_protect.PushBack(output_token, allocator);
    }
    api.AddMember("protect", request_protect, allocator);
    api.AddMember("target_lang", JsonString(target_lang, allocator), allocator);

    ExistingConversionContext context{mapping};
    rapidjson::Value output_items;
    if (!ConvertExistingItems(
            scene_items->value,
            &output_items,
            &context,
            allocator,
            error)
        || context.cursor != mapping.size()) {
        if (error && error->empty()) {
            *error = "scene item count does not match seq_to_order";
        }
        return false;
    }
    api.AddMember("scene_items", output_items, allocator);

    rapidjson::StringBuffer raw_buffer;
    rapidjson::Writer<rapidjson::StringBuffer> raw_writer(raw_buffer);
    if (!api.Accept(raw_writer)) {
        return Fail(error, "failed to serialize reconstructed request");
    }

    std::string normalized;
    if (!NormalizeRequestPayload(
            std::string(raw_buffer.GetString(), raw_buffer.GetSize()),
            &normalized,
            error)) {
        return false;
    }

    TranslationRequest output;
    output.scene_name = std::move(scene_name);
    output.target_lang = target_lang;
    output.payload_json = std::move(normalized);
    output.seq_to_order = std::move(mapping);
    *request = std::move(output);
    return true;
}

SceneInspection InspectScene(
    const std::string& scene_json,
    const std::string& expected_scene,
    const std::string& target_lang,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (scene_json.empty()
        || !scene_identity::IsValid(expected_scene)
        || target_lang.empty()) {
        Fail(error, "scene inspection input is empty");
        return SceneInspection::invalid;
    }
    rapidjson::Document scene;
    scene.Parse(scene_json.data(), scene_json.size());
    if (scene.HasParseError() || !scene.IsObject()) {
        Fail(error, "scene JSON is invalid");
        return SceneInspection::invalid;
    }
    std::string actual_scene;
    if (!ReadRequiredString(scene, "scene", &actual_scene)
        || !scene_identity::IsValid(actual_scene)
        || actual_scene != expected_scene) {
        Fail(error, "scene identity does not match the requested file");
        return SceneInspection::invalid;
    }
    const auto translated = scene.FindMember("translated");
    if (translated == scene.MemberEnd()
        || !internal::IsLanguageBooleanMap(translated->value)) {
        Fail(error, "translated metadata is invalid");
        return SceneInspection::invalid;
    }
    return internal::IsTargetTranslated(translated->value, target_lang)
        ? SceneInspection::complete
        : SceneInspection::pending;
}

}  // namespace het::translation::document_codec
