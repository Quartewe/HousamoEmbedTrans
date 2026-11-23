#pragma once
#include <cstdint>
#include <string>
#include <vector>

struct ContinuationRvas;

namespace het::continuation {
bool Install(uintptr_t base, const ContinuationRvas& rva);
bool CaptureEnabled();
bool IsPlaying();
void EndSession();
void LeaveChoice();
void BeginPage(void* engine, void* page);
void AtChoice(void* engine, void* page);
bool HandleSelection(void* selection);
void Selected(void* selection);
size_t RouteSize();
void RestoreRoute(size_t size);
std::string SceneForGeneratedPage(void* page);
std::string LabelForGeneratedPage(void* page);
// Claims only a previously accepted delivery for this visit.
bool TakePlayback();
bool TakePlaybackFailure(std::string* reason);
bool Play(void* engine, void* page);
bool RetryAtCurrentChoice();
void ReportError(const char* message);
} // namespace het::continuation
