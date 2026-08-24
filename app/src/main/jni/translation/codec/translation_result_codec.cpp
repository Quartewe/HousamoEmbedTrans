#include "document_codec.hpp"

#include "scene/scene_identity.hpp"

#include <rapidjson/document.h>

#include <string>
#include <utility>

namespace het::translation::document_codec {
namespace {

bool Fail(std::string* error, const char* message) {
    if (error) {
        *error = message;
    }
    return false;
}

bool ReadRequiredString(
    const rapidjson::Value& object,
    const char* name,
    std::string* output) {
    if (!object.IsObject() || !name || !output) {
        return false;
    }
    const auto member = object.FindMember(name);
    if (member == object.MemberEnd()
        || !member->value.IsString()
        || member->value.GetStringLength() == 0) {
        return false;
    }
    output->assign(
        member->value.GetString(),
        member->value.GetStringLength());
    return true;
}

}  // namespace

bool DecodeTranslationResult(
    const std::string& result_json,
    const TranslationRequest& request,
    TranslationResult* result,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (!result || result_json.empty()
        || !scene_identity::IsValid(request.scene_name)
        || request.target_lang.empty()
        || request.seq_to_order.empty()) {
        return Fail(error, "translation result or request is incomplete");
    }

    rapidjson::Document document;
    document.Parse(result_json.data(), result_json.size());
    if (document.HasParseError() || !document.IsObject()) {
        return Fail(error, "translation result JSON is invalid");
    }

    TranslationResult decoded;
    if (!ReadRequiredString(document, "provider", &decoded.provider)
        || !ReadRequiredString(document, "model", &decoded.model)
        || !ReadRequiredString(
            document,
            "target_lang",
            &decoded.target_lang)
        || !ReadRequiredString(document, "summary", &decoded.summary)) {
        return Fail(
            error,
            "translation result metadata is missing or invalid");
    }
    if (decoded.target_lang != request.target_lang) {
        return Fail(
            error,
            "translation result target_lang does not match request");
    }

    const auto translations = document.FindMember("translations");
    if (translations == document.MemberEnd()
        || !translations->value.IsArray()
        || translations->value.Size() != request.seq_to_order.size()) {
        return Fail(
            error,
            "translation result count does not match request");
    }

    decoded.translations.reserve(translations->value.Size());
    int expected_seq = 1;
    for (const rapidjson::Value& item : translations->value.GetArray()) {
        if (!item.IsObject()) {
            return Fail(error, "translation result item is not an object");
        }
        const auto seq = item.FindMember("seq");
        const auto text = item.FindMember("text");
        if (seq == item.MemberEnd() || !seq->value.IsInt()
            || seq->value.GetInt() != expected_seq
            || text == item.MemberEnd() || !text->value.IsString()
            || text->value.GetStringLength() == 0) {
            return Fail(
                error,
                "translation result seq or text is invalid");
        }
        decoded.translations.emplace_back(
            text->value.GetString(),
            text->value.GetStringLength());
        ++expected_seq;
    }

    *result = std::move(decoded);
    return true;
}

}  // namespace het::translation::document_codec
