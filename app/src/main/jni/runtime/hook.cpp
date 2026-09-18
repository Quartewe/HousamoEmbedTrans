#include "translation/native_translation_pipeline.hpp"
#include "housamo.hpp"
#include "scene/scene_identity.hpp"
#include "shadowhook.h"

#include <cstdint>
#include <utility>

using RawFuncPtr = void (*)(void* self, void* pageData, void* method);
using RawFindScenarioDataPtr = void* (*)(void* self, void* label, void* method);
using PageTextChangeFn = void (*)(void* self, void* adv_page, void* method);
using SelectionInitFn = void (*)(void* self, void* data, void* button_clicked_event, void* method);
using SelectionClearAllFn = void (*)(void* self, void* method);

// 原函数指针
static RawFuncPtr RawInitBase = nullptr; // 所有内容
static RawFuncPtr RawInitText = nullptr; // Text专用
static RawFindScenarioDataPtr RawFindScenarioData = nullptr;
static PageTextChangeFn RawPageTextChange = nullptr;
static SelectionInitFn RawSelectionInit = nullptr;
static SelectionClearAllFn RawSelectionClearAll = nullptr;

// ShadowHook stub 指针（用于 unhook）
static void* StubInitBase = nullptr;
static void* StubInitText = nullptr;
static void* StubFindScenarioData = nullptr;
static void* StubPageTextChange = nullptr;
static void* StubSelectionInit = nullptr;
static void* StubSelectionClearAll = nullptr;

static void* HookFindScenarioData(void* self, void* label, void* method) {
    // Sample once at the capture admission boundary.  Every downstream
    // path compares this token; none is allowed to create a replacement epoch.
    const std::uint64_t captured_epoch = capture_pause_epoch.load(
        std::memory_order_acquire);
    void* scenario_data = nullptr;
    if (RawFindScenarioData) {
        scenario_data = RawFindScenarioData(self, label, method);
    }

    if (stop_reason.load(std::memory_order_acquire) == StopReason::user_pause
        || captured_epoch != capture_pause_epoch.load(
            std::memory_order_acquire)) {
        LOGI("[FindScenarioData] user pause, skipping scenario catch");
        return scenario_data;
    }

    std::string entry_label = read_il2cpp_string(label);

    if (!valid_ptr(scenario_data)) {
        LOGW("[FindScenarioData] scenarioData invalid entry=%s", entry_label.c_str());
        return scenario_data;
    }

    if (entry_label.empty()) {
        LOGW("[FindScenarioData] entry label is empty scenarioData=%p", scenario_data);
        return scenario_data;
    }

    const auto& scenario_layout = g_runtime_config.layout.adv_scenario_data;
    const std::string scene_name = read_il2cpp_string(
        read_ptr(scenario_data, scenario_layout.name));
    if (!het::translation::scene_identity::IsValid(scene_name)) {
        LOGW(
            "[FindScenarioData] invalid scenario scene name entry=%s",
            entry_label.c_str());
        return scenario_data;
    }

    auto production_lease = EnterSceneProduction(scene_name);
    if (!production_lease.allowed()) {
        ReportSceneProductionRejected(scene_name, production_lease.reason());
        LOGI(
            "[FindScenarioData] Scene production rejected scene=%s reason=%d",
            scene_name.c_str(),
            static_cast<int>(production_lease.reason())
        );
        return scenario_data;
    }

    if (!CatchScenario(
            scenario_data,
            entry_label,
            std::move(production_lease),
            captured_epoch)) {
        LOGE("[FindScenarioData] failed to catch scenario scene=%s entry=%s",
             scene_name.c_str(), entry_label.c_str());
    }
    return scenario_data;
}

static void HookInitBase(void* self, void* pageData, void* method) {
    if (pageData == nullptr) {
        LOGE("[InitBase] pageData is nullptr!"); 
        if (RawInitBase) {
            RawInitBase(self, pageData, method);
        }
        return;
    };
    if (RawInitBase) {
        // 调用原函数
        RawInitBase(self, pageData, method);
    }
    CommandExamine(pageData, "base");
    return;
}

static void HookInitText(void* self, void* pageData, void* method) {
    if (pageData == nullptr) {
        LOGE("[InitText] pageData is nullptr!"); 
        if (RawInitText) {
            RawInitText(self, pageData, method);
            return;
        }
    };
    if (RawInitText) {
        // 调用原函数
        RawInitText(self, pageData, method);  
    }
    CommandExamine(pageData, "text");  
    return;
}

static void ObservePageTextChange(void* self, void* adv_page, void* method) {
    if (adv_page == nullptr) {
        LOGE("[PageTextChange] adv_page is nullptr!"); 
    };
    if (RawPageTextChange) {
        // 调用原函数
        RawPageTextChange(self, adv_page, method);  
    }
    SubmitPageTextChangeFn(adv_page);
    return;
}

static void ObserveSelectionClearAll(void* self, void* method) {
    ClearSelectionItems();
    if (RawSelectionClearAll) {
        // 调用原函数
        RawSelectionClearAll(self, method);  
    }
    LOGI("[SelectionClearAll] Cleared all selection items");
    return;
}

static void ObserveSelectionChange(void* self, void* data, void* button_clicked_event, void* method) {
    if (data == nullptr) {
        LOGE("[SelectionInit] data is nullptr!"); 
    };
    if (RawSelectionInit) {
        // 调用原函数
        RawSelectionInit(self, data, button_clicked_event, method);  
    }
    SubmitSelectionItem(self);
    return;
}

bool install_hook(uintptr_t il2cpp_base, const RuntimeConfig& config) {
    // 主链路：FindScenarioData 返回完整 AdvScenarioData 后做静态解析。
    void* targetFindScenarioData = reinterpret_cast<void*>(
        il2cpp_base + config.rva.find_scenario_data);

    StubFindScenarioData = shadowhook_hook_func_addr(
        targetFindScenarioData,
        reinterpret_cast<void*>(HookFindScenarioData),
        reinterpret_cast<void**>(&RawFindScenarioData)
    );
    if (StubFindScenarioData == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook FindScenarioData failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("shadowhook FindScenarioData success stub=%p", StubFindScenarioData);

    void* targetPageTextChange = reinterpret_cast<void*>(
        il2cpp_base + config.rva.page_text_change
    );

    StubPageTextChange = shadowhook_hook_func_addr(
        targetPageTextChange,
        reinterpret_cast<void*>(ObservePageTextChange),
        reinterpret_cast<void**>(&RawPageTextChange)
    );

    if (StubPageTextChange == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook PageTextChange failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }

    LOGI("shadowhook PageTextChange success stub=%p", StubPageTextChange);

    StubSelectionClearAll = shadowhook_hook_func_addr(
        reinterpret_cast<void*>(il2cpp_base + config.rva.ugui_selection_clear_all),
        reinterpret_cast<void*>(ObserveSelectionClearAll),
        reinterpret_cast<void**>(&RawSelectionClearAll)
    );
    
    if (StubSelectionClearAll == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook SelectionClearAll failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }

    LOGI("shadowhook SelectionClearAll success stub=%p", StubSelectionClearAll);

    StubSelectionInit = shadowhook_hook_func_addr(
        reinterpret_cast<void*>(il2cpp_base + config.rva.ugui_selection_init),
        reinterpret_cast<void*>(ObserveSelectionChange),
        reinterpret_cast<void**>(&RawSelectionInit)
    );

    if (StubSelectionInit == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook SelectionInit failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("shadowhook SelectionInit success stub=%p", StubSelectionInit);

    if (!config.enable_page_rec_debug) {
        LOGI("[PageRec] debug hook disabled");
        return true;
    }

    // 调试链路：只在配置显式开启时安装 InitBase/InitText 对照 hook。
    void* targetBase = reinterpret_cast<void*>(il2cpp_base + config.rva.init_base);
    void* targetText = reinterpret_cast<void*>(il2cpp_base + config.rva.init_text);

    // 使用 ShadowHook 安装钩子
    StubInitBase = shadowhook_hook_func_addr(
        targetBase,
        reinterpret_cast<void*>(HookInitBase),
        reinterpret_cast<void**>(&RawInitBase)
    );
    if (StubInitBase == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook InitBase failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("shadowhook InitBase success stub=%p", StubInitBase);

    StubInitText = shadowhook_hook_func_addr(
        targetText,
        reinterpret_cast<void*>(HookInitText),
        reinterpret_cast<void**>(&RawInitText)
    );
    if (StubInitText == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook InitText failed: %d %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    LOGI("shadowhook InitText success stub=%p", StubInitText);

    return true;
}

