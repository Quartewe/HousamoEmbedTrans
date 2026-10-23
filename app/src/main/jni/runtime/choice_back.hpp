#pragma once

#include <cstdint>

// The existing UnitySynchronizationContext pump owns initialization and all
// game state. Binder threads only publish one request to its mailbox.
void ConfigureChoiceBack(uintptr_t il2cpp_base);
void PumpChoiceBack();
