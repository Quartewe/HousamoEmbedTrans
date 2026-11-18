#pragma once
#include "runtime/utage_runtime.hpp"
#include "rapidjson/document.h"
#include <array>

namespace het::continuation {
struct GeneratedStep {
    std::string path;
    std::string speaker;
    bool choice = false;
    std::unordered_map<std::string, std::string> optionPaths;
};
struct Sheet {
    utage::Root scenario;
    utage::Root table;
    utage::Root key;
    std::string entry;
    std::string scene;
    std::string record;
    std::string merge;
    ContinuationWindow mergeWindow;
    std::string sourceHash;
    OrderKey original;
    size_t lineageStart = 0;
    std::unordered_map<std::string, GeneratedStep> steps;
};
bool BuildSheet(void* manager, const rapidjson::Value& steps, const std::string& prefix, Sheet& sheet);
void UnregisterSheet(Sheet& sheet);
} // namespace het::continuation
