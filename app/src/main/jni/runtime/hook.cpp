#include "native_translation_pipeline.hpp"
#include "housamo.hpp"
#include "io/scene_identity.hpp"
#include "shadowhook.h"

#include <cstdint>
#include <utility>

using RawFuncPtr = void (*)(void* self, void* pageData, void* method);
using RawFindScenarioDataPtr = void* (*)(void* self, void* label, void* method);

// 原函数指针
static RawFuncPtr RawInitBase = nullptr; // 所有内容
static RawFuncPtr RawInitText = nullptr; // Text专用
static RawFindScenarioDataPtr RawFindScenarioData = nullptr;

// ShadowHook stub 指针（用于 unhook）
static void* StubInitBase = nullptr;
static void* StubInitText = nullptr;
static void* StubFindScenarioData = nullptr;

static void* HookFindScenarioData(void* self, void* label, void* method) {
    void* scenario_data = nullptr;
    if (RawFindScenarioData) {
        scenario_data = RawFindScenarioData(self, label, method);
    }

    if (stop_reason.load(std::memory_order_acquire) == StopReason::user_pause) {
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
        // SceneName is sourced from AdvScenarioData.Name.  Do not use the
        // FindScenarioData entry label as a policy identity or report value.
        LOGW(
            "[FindScenarioData] invalid scenario scene name entry=%s",
            entry_label.c_str());
        return scenario_data;
    }

    // Existing complete/pending files are still sent to the API as before.
    // The production lease only gates a genuinely new capture path.
    switch (GetSceneFileStatus(scene_name)) {
        case SceneFileStatus::complete:
        //     if (!SubmitToQuestRewriter(entry_label)) {
        //         LOGE("[FindScenarioData] failed to rewrite scene to quest entry=%s", entry_label.c_str());
        //     }
            LOGI("[FindScenarioData] scene file already complete, skipping scene=%s", scene_name.c_str());
            return scenario_data;
        case SceneFileStatus::pending:
            if (!SubmitExistingScene(scene_name)) {
                LOGE("[FindScenarioData] failed to post existing scene to api scene=%s", scene_name.c_str());
            }
            return scenario_data;
        case SceneFileStatus::not_found:
            break;
    }

    auto production_lease = EnterSceneProduction(scene_name);
    if (!production_lease.allowed()) {
        if (production_lease.reason()
            != het::scene_sync::RejectReason::invalid_scene_name) {
            ReportSceneProductionRejected(scene_name, production_lease.reason());
        }
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
            std::move(production_lease))) {
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

