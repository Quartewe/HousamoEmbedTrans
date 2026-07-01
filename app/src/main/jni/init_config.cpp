#include "housamo.hpp"
#include <inttypes.h>

RuntimeConfig g_runtime_config;

struct RvaField {
    const char* name;
    uintptr_t value;
};

struct OffsetField {
    const char* name;
    size_t value;
};

struct ColumnField {
    const char* name;
    int value;
};

static bool valid_rva(uintptr_t rva) {
    return rva > 0x1000 && rva < 0x80000000;
}

static bool valid_offset(size_t offset) {
    return offset > 0 && offset < 0x1000;
}

static bool valid_nullable_offset(size_t offset) {
    return offset < 0x1000;
}

static bool valid_column(int column) {
    return column >= 0 && column < 128;
}

bool valid_rva_config(const RvaConfig& config) {
    bool all_valid = true;
    const RvaField fields[] = {
        {"find_scenario_data", config.find_scenario_data},
        {"init_base", config.init_base},
        {"init_text", config.init_text},
        {"page_text_change", config.page_text_change},
        {"add_selection", config.add_selection},
        {"show_selection", config.show_selection},
    };
    for (const RvaField& field : fields) {
        if (!valid_rva(field.value)) {
            LOGE("invalid RVA: %s = 0x%" PRIxPTR, field.name, field.value);
            all_valid = false;
        } else {
            LOGI("valid RVA: %s = 0x%" PRIxPTR, field.name, field.value);
        }
    }
    return all_valid;
}

bool valid_layout_config(const LayoutConfig& config) {
    bool all_valid = true;
    const OffsetField offsets[] = {
        {"il2cpp_string.length", config.il2cpp_string.length},
        {"il2cpp_string.chars", config.il2cpp_string.chars},
        {"il2cpp_array.length", config.il2cpp_array.length},
        {"il2cpp_array.first_element", config.il2cpp_array.first_element},
        {"il2cpp_list.items", config.il2cpp_list.items},
        {"il2cpp_list.size", config.il2cpp_list.size},
        {"adv_scenario_page_data.command_list", config.adv_scenario_page_data.command_list},
        {"adv_scenario_page_data.text_data_list", config.adv_scenario_page_data.text_data_list},
        {"adv_scenario_page_data.scenario_label_data", config.adv_scenario_page_data.scenario_label_data},
        {"adv_scenario_page_data.page_no", config.adv_scenario_page_data.page_no},
        {"adv_scenario_page_data.message_window_name", config.adv_scenario_page_data.message_window_name},
        {"adv_command.row_data", config.adv_command.row_data},
        {"adv_command.type", config.adv_command.type},
        {"string_grid_row.row_index", config.string_grid_row.row_index},
        {"string_grid_row.strings", config.string_grid_row.strings},
        {"adv_command_character.character_info", config.adv_command_character.character_info},
        {"adv_command_character.name_text", config.adv_command_character.name_text},
        {"adv_command_selection.jump_label", config.adv_command_selection.jump_label},
        {"adv_command_jump.jump_label", config.adv_command_jump.jump_label},
        {"adv_command_jump.expression_parser", config.adv_command_jump.expression_parser},
        {"scenario_label_data.page_data_list", config.scenario_label_data.page_data_list},
        {"scenario_label_data.scenario_label", config.scenario_label_data.scenario_label},
        {"scenario_label_data.next", config.scenario_label_data.next},
        {"scenario_label_data.command_list", config.scenario_label_data.command_list},
        {"scenario_label_data.scenario_label_command", config.scenario_label_data.scenario_label_command},
        {"adv_scenario_data.name", config.adv_scenario_data.name},
        {"adv_scenario_data.jump_data_list", config.adv_scenario_data.jump_data_list},
        {"adv_scenario_data.scenario_labels", config.adv_scenario_data.scenario_labels},
        {"il2cpp_dictionary.entries", config.il2cpp_dictionary.entries},
        {"il2cpp_dictionary.count", config.il2cpp_dictionary.count},
        {"dictionary_entry.key", config.dictionary_entry.key},
        {"dictionary_entry.value", config.dictionary_entry.value},
        {"dictionary_entry.size", config.dictionary_entry.size},
    };
    for (const OffsetField& field : offsets) {
        if (!valid_offset(field.value)) {
            LOGE("invalid layout offset: %s = 0x%zx", field.name, field.value);
            all_valid = false;
        } else {
            LOGI("valid layout offset: %s = 0x%zx", field.name, field.value);
        }
    }

    if (!valid_nullable_offset(config.dictionary_entry.hash_code)) {
        LOGE("invalid layout offset: dictionary_entry.hash_code = 0x%zx",
             config.dictionary_entry.hash_code);
        all_valid = false;
    } else {
        LOGI("valid layout offset: dictionary_entry.hash_code = 0x%zx",
             config.dictionary_entry.hash_code);
    }

    if (config.il2cpp_array.pointer_size != 8) {
        LOGE("invalid pointer size: %d", config.il2cpp_array.pointer_size);
        all_valid = false;
    } else {
        LOGI("valid pointer size: %d", config.il2cpp_array.pointer_size);
    }

    const ColumnField columns[] = {
        {"adv_command_jump.condition_column", config.adv_command_jump.condition_column},
        {"text_columns.raw", config.text_columns.raw},
        {"text_columns.en", config.text_columns.en},
        {"text_columns.zh_tw", config.text_columns.zh_tw},
        {"text_columns.zh_cn", config.text_columns.zh_cn},
    };

    for (const ColumnField& field : columns) {
        if (!valid_column(field.value)) {
            LOGE("invalid layout column: %s = %d", field.name, field.value);
            all_valid = false;
        } else {
            LOGI("valid layout column: %s = %d", field.name, field.value);
        }
    }

    if (!(config.text_columns.raw < config.text_columns.en
        && config.text_columns.en < config.text_columns.zh_tw
        && config.text_columns.zh_tw < config.text_columns.zh_cn)) {
        LOGE("invalid text column order: raw=%d en=%d zh_tw=%d zh_cn=%d",
             config.text_columns.raw,
             config.text_columns.en,
             config.text_columns.zh_tw,
             config.text_columns.zh_cn);
        all_valid = false;
    }

    return all_valid;
}

bool make_runtime_config(
    const RvaConfig& java_rva,
    const LayoutConfig& java_layout,
    const CharacterWeightConfig& character_weight,
    const std::string& target_lang,
    bool enable_page_rec_debug,
    RuntimeConfig* out) {
    if (out == nullptr) {
        LOGE("make_runtime_config failed: out is nullptr");
        return false;
    }

    if (!valid_rva_config(java_rva)) {
        LOGE("make_runtime_config failed: invalid rva config");
        return false; 
    }
    if (!valid_layout_config(java_layout)) {
        LOGE("make_runtime_config failed: invalid layout config");
        return false;
    }
    if (target_lang.empty()) {
        LOGE("make_runtime_config failed: target_lang is empty");
        return false;
    } else if (target_lang != "en" && target_lang != "zh-tw" && target_lang != "zh-cn") {
        LOGW("unsupported target_lang=%s, might couldnt work properly", target_lang.c_str());
    }

    out->rva = java_rva;
    out->layout = java_layout;
    out->character_weight = character_weight;
    out->target_lang = target_lang;
    out->enable_page_rec_debug = enable_page_rec_debug;
    LOGI("character weight config: high_relevance=%.3f mid_relevance=%.3f density_high=%.3f"
         " text_low_score=%.3f text_mentioned_score=%.3f related_num=%d low_term_score=%d target_lang=%s",
         out->character_weight.high_relevance,
         out->character_weight.mid_relevance,
         out->character_weight.density_high,
         out->character_weight.text_low_score,
         out->character_weight.text_mentioned_score,
         out->character_weight.related_num,
         out->character_weight.low_term_score,
         out->target_lang.c_str());
    return true;
}
