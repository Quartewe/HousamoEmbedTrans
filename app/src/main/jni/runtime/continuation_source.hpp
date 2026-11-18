#pragma once
#include "housamo.hpp"

void PublishContinuationSource(void* scenario, const ScenarioParseResult& parsed, const QuestPtrSet& targets);
bool IsContinuationScenario(void* scenario);
bool IsContinuationInputButton(void* selection);
bool IsContinuationLabel(const std::string& label);
