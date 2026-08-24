#pragma once

#include <string>

namespace het::translation {

enum class SceneCommitResult {
    committed,
    already_exists,
    failed,
};

namespace scene_store {

bool Exists(const std::string& scene_name);

bool Read(
    const std::string& scene_name,
    std::string* scene_json,
    std::string* error);

SceneCommitResult Commit(
    const std::string& scene_name,
    const std::string& scene_json,
    bool overwrite_existing,
    std::string* error);

std::string PathForLog(const std::string& scene_name);

}  // namespace scene_store
}  // namespace het::translation
