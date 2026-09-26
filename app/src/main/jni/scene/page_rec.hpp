#pragma once

#include "housamo.hpp"
#include <functional>

// Runs on the FindScenarioData caller thread; the resolver and managed pointers
// must never escape this call. Only initialized label names are retained.
void ExportPageRecScenarios(
    void* current_scenario, const std::string& entry_label,
    std::uint64_t captured_epoch,
    const std::function<void*(const std::string&)>& resolve);

bool ParsePageRecScene(void* scenario_data, const std::string& entry_label,
                      Scene* scene, std::vector<std::string>* labels);
Scene BuildSceneDocument(ScenarioParseResult result);
