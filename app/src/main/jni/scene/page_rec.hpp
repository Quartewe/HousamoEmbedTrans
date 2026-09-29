#pragma once

#include "housamo.hpp"
#include <functional>

// Runs on the FindScenarioData caller thread; the resolver and managed pointers
// must never escape this call. Only initialized label names are retained.
void ExportPageRecScenarios(
    void* current_scenario, const std::string& entry_label,
    std::uint64_t captured_epoch,
    const std::function<void*(const std::string&)>& resolve);

// Reports target-language official text separately so export can finish while
// task admission skips the Scene. Outputs are valid only when parsing succeeds.
bool ParsePageRecScene(void* scenario_data, const std::string& entry_label,
                      Scene* scene, std::vector<std::string>* labels,
                      bool* has_target_official_translation);
Scene BuildSceneDocument(ScenarioParseResult result);
