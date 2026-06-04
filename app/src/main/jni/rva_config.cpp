#include "housamo.hpp"
#include <inttypes.h>

struct RvaField {
    const char* name;
    uintptr_t value;
};

static bool valid_rva(uintptr_t rva) {
    return rva > 0x1000 && rva < 0x80000000;
}

bool valid_rva_config(const RvaConfig& config) {
    bool all_valid = true;
    const RvaField fields[] = {
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

bool make_rva_config(const RvaConfig javaconfig, RvaConfig* out) {
    if (out == nullptr) { LOGE("make_rva_config failed: out is nullptr"); return false; };
    if (!valid_rva_config(javaconfig)) {return false; }
    *out = javaconfig;
    return true;
}