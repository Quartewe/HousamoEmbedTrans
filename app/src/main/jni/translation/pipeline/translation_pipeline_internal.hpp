#pragma once

#include "translation/codec/document_codec.hpp"

#include <cstdint>
#include <memory>
#include <string>

namespace het::translation {

namespace translation_dispatcher {

bool Submit(
    std::shared_ptr<const TranslationRequest> request,
    std::uint64_t captured_epoch);
void ClearOnPause();

std::shared_ptr<const TranslationRequest> TakePendingRequest(
    const std::string& request_id);

std::shared_ptr<const TranslationRequest> PeekPendingRequest(
    const std::string& request_id);

}  // namespace translation_dispatcher
}  // namespace het::translation
