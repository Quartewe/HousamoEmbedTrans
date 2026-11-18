#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include "rapidjson/document.h"

namespace het::continuation {
bool Install(uintptr_t base, const std::vector<unsigned char>& buildId);
void EndSession();
void BeginPage(void* engine, void* page);
std::string SceneForGeneratedPage(void* page);
bool PlayDialogue(void* engine, void* selection, const std::string& scene, const std::string& merge, const rapidjson::Value& steps);
}
