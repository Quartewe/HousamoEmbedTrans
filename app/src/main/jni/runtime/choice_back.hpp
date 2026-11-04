#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct ChoiceBackConfig {
    std::vector<unsigned char> build_id;
    uintptr_t begin_page = 0;
    uintptr_t save_page = 0;
    uintptr_t wait_input = 0;
    uintptr_t select = 0;
    uintptr_t start_scenario = 0;
    uintptr_t end_scenario = 0;
    uintptr_t destroy = 0;
    uintptr_t load = 0;
    uintptr_t move_load = 0;
    uintptr_t create_load = 0;
    uintptr_t start_game = 0;
    uintptr_t save_page_caller = 0;
    int32_t send_char = 0;
    int32_t wait_effect_on_input_in_page = 0;
    int32_t wait_input_in_page = 0;
    int32_t wait_input_br_page = 0;
};

// The existing UnitySynchronizationContext pump owns initialization and all
// game state. Binder threads only publish one request to its mailbox.
// Copies the startup configuration before publishing the Unity pump hook.
// It remains immutable for the lifetime of the game process.
void ConfigureChoiceBack(uintptr_t il2cpp_base, const ChoiceBackConfig& config);
void PumpChoiceBack();

// Copies the table's declared entry while its owner is on the calling game thread.
std::string ReadScenarioQuestLabel(void* scenario, void* data_manager);

// Resolves a played page's label through the original lookup, without starting capture again.
void* FindScenarioDataForPosition(void* manager, void* label, const void* method);
