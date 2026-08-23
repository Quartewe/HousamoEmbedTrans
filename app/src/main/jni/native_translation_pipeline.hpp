#pragma once

#include <memory>
#include <string>

#include "scene_production_policy.hpp"

struct Scene;
enum class SceneFileStatus;

SceneFileStatus GetSceneFileStatus(const std::string& scene_name);
bool SubmitExistingScene(const std::string& scene_name);
void SubmitCapturedScene(
    std::shared_ptr<const Scene> scene,
    het::scene_sync::SceneProductionLease production_lease);
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
