#pragma once

#include "housamo.hpp"

#include <string>
#include <vector>

namespace het::translation {

struct TranslationRequest {
    std::string scene_name;
    std::string target_lang;
    std::string payload_json;
    std::vector<OrderKey> seq_to_order;
};

struct TranslationResult {
    std::string provider;
    std::string model;
    std::string target_lang;
    std::string summary;
    std::vector<std::string> translations;
};

enum class SceneInspection {
    invalid,
    pending,
    complete,
};

enum class BridgeResponseKind {
    success,
    retryable_failure,
    permanent_failure,
    invalid,
};

struct RequestIdResolutionResponse {
    BridgeResponseKind kind = BridgeResponseKind::invalid;
    std::string request_id;
    std::string error_type;
    std::string error_message;
    int error_status = 0;
};

struct SubmissionResponse {
    BridgeResponseKind kind = BridgeResponseKind::invalid;
    bool created = false;
    std::string error_type;
    std::string error_message;
    int error_status = 0;
};

namespace document_codec {

bool EncodeCapturedScene(
    const Scene& scene,
    std::string* scene_json,
    TranslationRequest* request,
    std::string* error);

bool BuildRequestFromExistingScene(
    const std::string& scene_json,
    const std::string& target_lang,
    TranslationRequest* request,
    std::string* error,
    bool allow_already_translated = false);

SceneInspection InspectScene(
    const std::string& scene_json,
    const std::string& expected_scene,
    const std::string& target_lang,
    std::string* error);

bool NormalizeRequestPayload(
    const std::string& request_json,
    std::string* normalized_json,
    std::string* error);

bool DecodeTranslationResult(
    const std::string& result_json,
    const TranslationRequest& request,
    TranslationResult* result,
    std::string* error);

RequestIdResolutionResponse DecodeRequestIdResolutionResponse(
    const std::string& response_json);

SubmissionResponse DecodeSubmissionResponse(
    const std::string& response_json,
    const std::string& expected_request_id);

bool ApplyTranslationToScene(
    const std::string& scene_json,
    const TranslationRequest& request,
    const TranslationResult& result,
    std::string* translated_scene_json,
    std::string* error);

}  // namespace document_codec
}  // namespace het::translation
