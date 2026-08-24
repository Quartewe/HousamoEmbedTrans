#pragma once

#include <memory>
#include <string>
#include <cstdint>

#include "scene_production_policy.hpp"

struct Scene;

enum class SceneFileStatus {
    not_found,
    complete,
    pending,
};

SceneFileStatus GetSceneFileStatus(const std::string& scene_name);
bool SubmitExistingScene(
    const std::string& scene_name,
    std::uint64_t captured_epoch);
void SubmitCapturedScene(
    std::shared_ptr<const Scene> scene,
    het::scene_sync::SceneProductionLease production_lease,
    std::uint64_t captured_epoch);
void NotifyNativeTranslationPipelineStopChanged();
void ClearNativeTranslationPipelineOnPause();

// Terminal callback consumption is split from delivery acknowledgement.  A
// true return means native accepted the outcome and Java may synchronously ACK
// it through ITranslationService; a false return keeps HET delivery pending.
bool HandleCompletedTranslation(
    const std::string& request_id,
    const std::string& scene_name,
    const std::string& target_lang,
    const std::string& result_json);
bool HandleTranslationFailure(
    const std::string& request_id,
    const std::string& error_type,
    const std::string& message);
bool AcknowledgeTranslationTerminal(
    const std::string& request_id,
    const std::string& terminal_kind);
