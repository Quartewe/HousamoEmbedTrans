#include "runtime/continuation_player.hpp"
#include "runtime/continuation_source.hpp"
#include "runtime/continuation_sheet.hpp"
#include "runtime/choice_back.hpp"
#include "bridge/jni_bridge.hpp"
#include "rapidjson/stringbuffer.h"
#include "rapidjson/writer.h"
#include <atomic>
#include <chrono>
#include <cmath>

namespace het::continuation {
using namespace utage;
using Json = rapidjson::Value;
using Document = rapidjson::Document;
using Alloc = Document::AllocatorType;
namespace {
std::atomic<bool> installed{false};
std::mutex sheetsMutex;
std::unordered_set<void*> temporary;
std::vector<std::unique_ptr<Sheet>> sheets;
Root currentEngine, currentPage, runningSelection;
Sheet* currentSheet = nullptr;
bool enteringSheet = false;
std::string currentLabel;
uint64_t serial = 0;
const std::string processId = std::to_string(std::chrono::system_clock::now().time_since_epoch().count());
std::string PageLabel(void* page) {
    return api.Text(api.Get(api.Get(page, "get_ScenarioLabelData"), "get_ScenarioLabel"));
}
Sheet* SheetFor(void* scenario) {
    for (auto& sheet : sheets)
        if (sheet->scenario.Get() == scenario)
            return sheet.get();
    return nullptr;
}
void* PageScenario(void* engine, void* page) {
    void* label = api.Get(api.Get(page, "get_ScenarioLabelData"), "get_ScenarioLabel");
    void* manager = api.Get(engine, "get_DataManager");
    if (!label || !manager)
        return nullptr;
    return FindScenarioDataForPosition(manager, label,
                                       api.Method(api.object_get_class(manager), "FindScenarioData", 1));
}
using Binary = void (*)(void*,void*,const void*);
using AddReadFn = void (*)(void*,void*,int,const void*);
Binary originalText=nullptr, originalHistory=nullptr;
AddReadFn originalRead=nullptr;
bool HasGeneratedLabel(const std::string& label) {
    for (const auto& sheet : sheets) {
        const size_t prefix = sheet->entry.rfind('_') + 1;
        if (label.compare(0, prefix, sheet->entry, 0, prefix) == 0)
            return true;
    }
    return false;
}
void Text(void* page, void* cmd, const void* method) {
    if (installed && currentEngine.Get() == api.Get(page, "get_Engine") &&
        currentPage.Get() == api.Get(page, "get_CurrentData")) {
        if (currentSheet) {
            auto it = currentSheet->steps.find(currentLabel);
            if (it != currentSheet->steps.end()) {
                auto& step = it->second;
                Root info, empty(api.string_new(""));
                if (!step.speaker.empty()) {
                    info = NewObject("Utage", "AdvCharacterInfo");
                    Root name(api.string_new_len(step.speaker.data(), step.speaker.size()));
                    bool hide = true;
                    void* args[] = {empty.Get(), name.Get(), empty.Get(), empty.Get(), &hide, nullptr};
                    if (!api.Call(info.Get(), ".ctor", 6, args)) {
                        LOGW("[Continuation] dialogue speaker initialization failed");
                        return;
                    }
                }
                void* set[] = {info.Get()};
                if (!api.Call(page, "set_CharacterInfo", 1, set)) {
                    LOGW("[Continuation] dialogue speaker update failed");
                    return;
                }
            }
        }
    }
    originalText(page, cmd, method);
}
void AddRead(void* self, void* label, int page, const void* method) {
    if (!sheets.empty() && HasGeneratedLabel(api.Text(label)))
        return;
    originalRead(self, label, page, method);
}
void AddHistory(void* self, void* selection, const void* method) {
    if (selection != runningSelection.Get() && !currentSheet)
        originalHistory(self, selection, method);
}
}
bool IsTemporary(void* scenario) {
    if (!installed || !scenario)
        return false;
    std::lock_guard<std::mutex> lock(sheetsMutex);
    return temporary.count(scenario) != 0;
}
bool Install(uintptr_t base, const std::vector<unsigned char>& buildId) {
    // update-1380, arm64. choice_back verified the loaded ELF note before
    // reaching this point. Other revisions keep their existing choice tracker.
    static const std::vector<unsigned char> expected = {0xce, 0xa0, 0x6d, 0x00, 0x0d, 0x37, 0xd5,
                                                        0x44, 0xe9, 0x9c, 0xa4, 0x75, 0x84, 0x64,
                                                        0xee, 0xfa, 0x39, 0x3c, 0x3e, 0xb2};
    if (buildId != expected || !api.array_new || !api.gc_wbarrier_set_field || !api.string_new_len ||
        !api.gchandle_new_weakref)
        return false;
    struct Hook {
        uintptr_t rva;
        void* replacement;
        void** original;
        void* stub;
    };
    Hook hooks[] = {
        {0x2466d40, reinterpret_cast<void*>(Text), reinterpret_cast<void**>(&originalText), nullptr},
        {0x2466ab4, reinterpret_cast<void*>(AddRead), reinterpret_cast<void**>(&originalRead), nullptr},
        {0x246d00c, reinterpret_cast<void*>(AddHistory), reinterpret_cast<void**>(&originalHistory), nullptr},
    };
    for (auto& hook : hooks) {
        hook.stub = shadowhook_hook_func_addr(reinterpret_cast<void*>(base + hook.rva), hook.replacement,
                                              hook.original);
        if (!hook.stub) {
            for (auto& item : hooks)
                if (item.stub)
                    shadowhook_unhook(item.stub);
            return false;
        }
    }
    installed = true;
    return true;
}
void EndSession() {
    if (!installed)
        return;
    currentSheet = nullptr;
    enteringSheet = false;
    currentPage.Reset();
    currentEngine.Reset();
    currentLabel.clear();
    for (auto& sheet : sheets)
        UnregisterSheet(*sheet);
    {
        std::lock_guard<std::mutex> lock(sheetsMutex);
        temporary.clear();
    }
    sheets.clear();
}
void BeginPage(void* engine, void* page) {
    if (!installed)
        return;
    currentPage = Root(page);
    currentEngine = Root(engine);
    currentLabel = PageLabel(page);
    void* scenario = PageScenario(engine, page);
    currentSheet = SheetFor(scenario);
    enteringSheet = false;
}
std::string SceneForGeneratedPage(void* page) {
    return currentSheet && currentPage.Get() == page ? currentSheet->scene : "";
}
bool PlayDialogue(void* engine, void* selection, const std::string& scene, const std::string& merge, const rapidjson::Value& steps) {
    Root manager(api.Get(engine, "get_DataManager")), select(api.Get(engine, "get_SelectionManager"));
    auto sheet = std::make_unique<Sheet>();
    sheet->scene = scene;
    sheet->merge = merge;
    auto prefix = "HETContinuation_" + processId + "_" + std::to_string(++serial);
    if (!BuildSheet(manager.Get(), steps, prefix, *sheet))
        return false;
    Root entry(api.string_new(sheet->entry.c_str()));
    void* field = api.Field(selection, "jumpLabel");
    if (!field) {
        UnregisterSheet(*sheet);
        return false;
    }
    api.field_set_value_object(selection, field, entry.Get());
    {
        std::lock_guard<std::mutex> lock(sheetsMutex);
        temporary.insert(sheet->scenario.Get());
    }
    sheets.push_back(std::move(sheet));
    // Same wait completion, onSelected UI cleanup and jump reservation as an
    // ordinary selection. No original branch expression is attached to this item.
    runningSelection = Root(selection);
    enteringSheet = true;
    bool ok = DispatchContinuationSelection(select.Get(), runningSelection.Get());
    if (!ok) enteringSheet = false;
    runningSelection.Reset();
    return ok;
}
}
bool IsContinuationScenario(void* scenario) { return het::continuation::IsTemporary(scenario); }
bool IsContinuationLabel(const std::string& label) {
    // Init can report the sheet's pages before its scenario is registered in the data manager.
    static const std::string prefix = "HETContinuation_" + het::continuation::processId + "_";
    return label.compare(!label.empty() && label.front() == '*' ? 1 : 0, prefix.size(), prefix) == 0;
}
