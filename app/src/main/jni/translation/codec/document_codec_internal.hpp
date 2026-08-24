#pragma once

#include "housamo.hpp"

#include <rapidjson/document.h>

#include <string>
#include <vector>

namespace het::translation::document_codec::internal {

// These helpers are the single RapidJSON-level source of truth for the
// persistent Scene invariants shared by request reconstruction and writeback.
// Keep this header private to the DocumentCodec implementation files.
bool ParseOrderKey(
    const rapidjson::Value& value,
    OrderKey* output);

bool ParseSeqToOrder(
    const rapidjson::Value& value,
    std::vector<OrderKey>* output);

bool IsLanguageStringMap(const rapidjson::Value& value);

bool IsLanguageBooleanMap(const rapidjson::Value& value);

bool IsTargetTranslated(
    const rapidjson::Value& translated,
    const std::string& target_lang);

}  // namespace het::translation::document_codec::internal
