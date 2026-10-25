#pragma once

#include <cstdint>

// The existing UnitySynchronizationContext pump owns initialization and all
// game state. Binder threads only publish one request to its mailbox.
void ConfigureChoiceBack(uintptr_t il2cpp_base);
void PumpChoiceBack();

// Resolves a played page's label through the original lookup, without starting capture again.
void* FindScenarioDataForPosition(void* manager, void* label, const void* method);
