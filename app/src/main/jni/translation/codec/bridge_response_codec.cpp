#include "document_codec.hpp"

#include <rapidjson/document.h>

#include <utility>

namespace het::translation::document_codec {
namespace {

bool ReadString(
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

template <typename Response>
bool DecodeError(const rapidjson::Value& root, Response* response) {
    const auto error_member = root.FindMember("error");
    if (error_member == root.MemberEnd()) {
        return false;
    }
    if (!error_member->value.IsObject()) {
        response->kind = BridgeResponseKind::invalid;
        return true;
    }

    const rapidjson::Value& error = error_member->value;
    std::string error_type;
    std::string error_message;
    const auto status = error.FindMember("status");
    const auto retryable = error.FindMember("retryable");
    if (root.MemberCount() != 1U
        || !ReadString(error, "type", &error_type)
        || error_type.empty()
        || !ReadString(error, "message", &error_message)
        || status == error.MemberEnd()
        || !status->value.IsInt()
        || retryable == error.MemberEnd()
        || !retryable->value.IsBool()) {
        response->kind = BridgeResponseKind::invalid;
        return true;
    }
    response->error_type = std::move(error_type);
    response->error_message = std::move(error_message);
    response->error_status = status->value.GetInt();
    response->kind = retryable->value.GetBool()
        ? BridgeResponseKind::retryable_failure
        : BridgeResponseKind::permanent_failure;
    return true;
}

}  // namespace

RequestIdResolutionResponse DecodeRequestIdResolutionResponse(
    const std::string& response_json) {
    RequestIdResolutionResponse response;
    if (response_json.empty()) {
        return response;
    }

    rapidjson::Document root;
    root.Parse(response_json.data(), response_json.size());
    if (root.HasParseError() || !root.IsObject()) {
        return response;
    }
    if (DecodeError(root, &response)) {
        return response;
    }

    if (root.MemberCount() != 1U
        || !ReadString(root, "request_id", &response.request_id)
        || response.request_id.empty()) {
        return response;
    }
    response.kind = BridgeResponseKind::success;
    return response;
}

SubmissionResponse DecodeSubmissionResponse(
    const std::string& response_json,
    const std::string& expected_request_id) {
    SubmissionResponse response;
    if (response_json.empty() || expected_request_id.empty()) {
        return response;
    }

    rapidjson::Document root;
    root.Parse(response_json.data(), response_json.size());
    if (root.HasParseError() || !root.IsObject()) {
        return response;
    }
    if (DecodeError(root, &response)) {
        return response;
    }

    const auto accepted = root.FindMember("accepted");
    const auto created = root.FindMember("created");
    std::string request_id;
    if (root.MemberCount() != 3U
        || accepted == root.MemberEnd() || !accepted->value.IsBool()
        || !accepted->value.GetBool()
        || created == root.MemberEnd() || !created->value.IsBool()
        || !ReadString(root, "request_id", &request_id)
        || request_id != expected_request_id) {
        return response;
    }

    response.kind = BridgeResponseKind::success;
    response.created = created->value.GetBool();
    return response;
}

}  // namespace het::translation::document_codec
