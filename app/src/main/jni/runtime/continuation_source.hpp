#pragma once
#include "housamo.hpp"

std::string HashContinuationSource(const std::string& source_document);
void PublishContinuationSource(void* scenario, const ScenarioParseResult& parsed, const QuestPtrSet& targets);
bool IsContinuationScenario(void* scenario);
bool IsContinuationInputButton(void* selection);
bool IsContinuationLabel(const std::string& label);
