#include "runtime/choice_back.hpp"
#include "runtime/utage_runtime.hpp"
#include "runtime/continuation_player.hpp"
#include "runtime/story_controls.hpp"
#include "housamo.hpp"
#include "shadowhook.h"
#include "bridge/jni_bridge.hpp"
#include "scene/scene_identity.hpp"

#include <jni.h>
#include <link.h>
#include <cstring>
#include <chrono>
#include <new>
#include <utility>
#include <algorithm>

using namespace het::utage;
namespace continuation = het::continuation;
het::utage::Api het::utage::api;

namespace {
enum Result { Restored = 0, NoTarget = 1, Busy = 2, Unavailable = 3, Failed = 5 };
// Published with the Unity pump hook; immutable after startup. The configured
// GNU build ID gates every version-specific hook and page-top call site.
ChoiceBackConfig runtime;
uintptr_t base = 0;
bool initialized = false;
std::atomic<bool> ready{false};
std::mutex mailboxMutex;
uint64_t epoch = 0; // read/published under mailboxMutex; mutated only on Unity
bool occupied = false;

struct Completion {
    JavaVM* vm = nullptr;
    jobject object = nullptr;
    jmethodID method = nullptr;
    uint64_t epoch = 0;
    bool openStory = false;
    std::u16string questLabel;
    std::string playbackFailure;

    bool TryStart() {
        JNIEnv* env = nullptr;
        bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
        if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        jclass klass = env->GetObjectClass(object);
        jmethodID claim = env->GetMethodID(klass, "tryStart", "()Z");
        bool accepted = claim && env->CallBooleanMethod(object, claim);
        if (env->ExceptionCheck()) { env->ExceptionClear(); accepted = false; }
        env->DeleteLocalRef(klass);
        if (attached) vm->DetachCurrentThread();
        return accepted;
    }

    void Deliver(int result) {
        if (!object) return; // Local recovery after a generated playback failure.
        JNIEnv* env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
        if (env) {
            env->CallVoidMethod(object, method, static_cast<jint>(result));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                LOGW("[ChoiceBack] completion receiver threw");
            }
            env->DeleteGlobalRef(object);
            object = nullptr;
        }
        if (attached) vm->DetachCurrentThread();
    }
};
std::unique_ptr<Completion> pending;
std::unique_ptr<Completion> executing;

// A Snapshot is opaque to the tracker: its native save object, all buffer
// bytes and page-identity root are owned here. No System state is collected.
class Snapshot {
    Root save_;
    Root page_;
public:
    Snapshot(Root save, void* page) : save_(std::move(save)), page_(page) {}
    void* Save() const { return save_.Get(); }
    void* Page() const { return page_.Get(); }
    bool IsPage(void* page) const { return page_.Get() == page; }
    std::unique_ptr<Snapshot> CopyForLoad() const { return Capture(save_.Get(), page_.Get()); }

    static std::unique_ptr<Snapshot> Capture(void* source, void* page) {
        if (!source || !page) return nullptr;
        Root sourceRoot(source);
        Root stream = NewObject("System.IO", "MemoryStream");
        Root writer = NewObject("System.IO", "BinaryWriter");
        Root reader = NewObject("System.IO", "BinaryReader");
        Root copy = NewObject("Utage", "AdvSaveData");
        Root buffer = NewObject("Utage", "BinaryBuffer");
        if (!sourceRoot.Get() || !stream.Get() || !writer.Get() || !reader.Get()
            || !copy.Get() || !buffer.Get()) return nullptr;

        int type = 0;
        void* path = nullptr;
        void* sourceBuffer = nullptr;
        if (!api.Read(source, "<Type>k__BackingField", type)
            || !api.Read(source, "<Path>k__BackingField", path)
            || !api.Read(source, "Buffer", sourceBuffer) || !sourceBuffer) return nullptr;
        void* ctorArgs[] = { &type, path };
        void* streamArgs[] = { stream.Get() };
        if (!api.Call(copy.Get(), ".ctor", 2, ctorArgs)
            || !api.Call(buffer.Get(), ".ctor")
            || !api.Call(stream.Get(), ".ctor")
            || !api.Call(writer.Get(), ".ctor", 1, streamArgs)) return nullptr;
        void* writeArgs[] = { writer.Get() };
        int64_t zero = 0;
        void* positionArgs[] = { &zero };
        if (!api.Call(sourceBuffer, "Write", 1, writeArgs)
            || !api.Call(writer.Get(), "Flush")
            || !api.Call(stream.Get(), "set_Position", 1, positionArgs)
            || !api.Call(reader.Get(), ".ctor", 1, streamArgs)) return nullptr;
        void* readArgs[] = { reader.Get() };
        if (!api.Call(buffer.Get(), "Read", 1, readArgs)) return nullptr;
        void* bufferField = api.Field(copy.Get(), "Buffer");
        if (!bufferField) return nullptr;
        api.field_set_value_object(copy.Get(), bufferField, buffer.Get());

        // Do not call AdvSaveData.Write: it changes source.Date and encodes
        // Texture. Copy the save metadata, with value types boxed by IL2CPP.
        const char* metadata[] = { "<Title>k__BackingField", "<Date>k__BackingField",
                                   "<FileVersion>k__BackingField", "texture" };
        for (const char* name : metadata) {
            void* from = api.Field(source, name);
            void* to = api.Field(copy.Get(), name);
            if (!from || !to) return nullptr;
            Root value(api.field_get_value_object(from, source));
            void* object = value.Get();
            if (object && api.class_is_valuetype(api.object_get_class(object))) {
                api.field_set_value(copy.Get(), to, api.object_unbox(object));
            } else {
                api.field_set_value_object(copy.Get(), to, object);
            }
        }
        auto result = std::unique_ptr<Snapshot>(new (std::nothrow) Snapshot(std::move(copy), page));
        if (!result || !result->page_.Get()) return nullptr;
        return result;
    }
};

struct Item { std::unique_ptr<Snapshot> snapshot; size_t routeSize = 0; };
struct Node {
    Item item;
    std::unique_ptr<Node> previous;
};
std::unique_ptr<Node> top;
std::unique_ptr<Snapshot> candidate;
Root engine;
Root currentPage;
Root loadEngine;
Root loadRoutine;
bool active = false;
bool unavailable = false;
bool choiceRecorded = false;
bool selectedOnPage = false;
bool waiting = false;
bool internalLoad = false;
void* dispatchingSelection = nullptr; // Unity only, scoped to one managed Select call.
uint64_t pageVisit = 0;
uint64_t candidateVisit = 0;
// Playback identity is independent of capture/pause and background Scene parsing.
std::string currentScene;

// The load lease is independent from the history. Lifecycle invalidation can
// clear history without releasing the object still used by the coroutine.
std::unique_ptr<Snapshot> loadLease;
Node* target = nullptr;
bool loadEnded = false;
bool targetWaiting = false;
bool loadStartedPage = false;
bool enteredLoad = false;
std::chrono::steady_clock::time_point deadline;

bool ReadLabel(void* page, std::u16string& label, int& pageNo) {
    Root data(api.Get(page, "get_ScenarioLabelData"));
    Root name(api.Get(data.Get(), "get_ScenarioLabel"));
    if (!name.Get() || !api.Read(page, "<PageNo>k__BackingField", pageNo) || pageNo < 0) return false;
    int32_t length = api.string_length(name.Get());
    if (length <= 0 || length > 8192) return false;
    label.assign(api.string_chars(name.Get()), static_cast<size_t>(length));
    return true;
}

std::string SceneForPage(void* page) {
    auto original = continuation::SceneForGeneratedPage(page);
    if (!original.empty()) return original;
    Root labelData(api.Get(page, "get_ScenarioLabelData"));
    Root label(api.Get(labelData.Get(), "get_ScenarioLabel"));
    Root manager(api.Get(engine.Get(), "get_DataManager"));
    if (!label.Get() || !manager.Get()) return {};
    const void* method = api.Method(api.object_get_class(manager.Get()), "FindScenarioData", 1);
    if (!method) return {};
    Root scenario(FindScenarioDataForPosition(manager.Get(), label.Get(), method));
    Root name(api.Get(scenario.Get(), "get_Name"));
    if (!name.Get()) return {};
    const int32_t length = api.string_length(name.Get());
    if (length <= 0 || length > 235) return {};
    const char16_t* chars = api.string_chars(name.Get());
    std::string scene;
    for (int32_t i = 0; i < length; ++i) {
        if (chars[i] > 127) return {};
        scene.push_back(static_cast<char>(chars[i]));
    }
    return het::translation::scene_identity::IsValid(scene) ? scene : std::string{};
}

void PublishPosition() {
    auto& bridge = g_java_bridge;
    if (!bridge.jvm || !bridge.main_hook_class || !bridge.report_story_position_method) return;
    std::u16string choice;
    int pageNo = -1;
    bool choiceKnown = !top || ReadLabel(top->item.snapshot->Page(), choice, pageNo);
    if (!choiceKnown) {
        LOGW("[ChoiceBack] recorded choice display identity unavailable");
        choice.clear();
        pageNo = -1;
    }
    JNIEnv* env = nullptr;
    bool attached = false;
    if (bridge.jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        attached = bridge.jvm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    if (!env) return;
    jstring scene = !active || !currentScene.empty() ? env->NewStringUTF(currentScene.c_str()) : nullptr;
    jstring label = nullptr;
    if (choiceKnown && !env->ExceptionCheck()) {
        auto generated=top ? continuation::LabelForGeneratedPage(top->item.snapshot->Page()) : std::string{};
        label = generated.empty()
            ? env->NewString(reinterpret_cast<const jchar*>(choice.data()),static_cast<jsize>(choice.size()))
            : env->NewStringUTF(generated.c_str());
    }
    if (!env->ExceptionCheck()) {
        env->CallStaticVoidMethod(bridge.main_hook_class, bridge.report_story_position_method,
            scene, label, static_cast<jint>(pageNo));
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("[ChoiceBack] position delivery to Java failed");
    }
    if (scene) env->DeleteLocalRef(scene);
    if (label) env->DeleteLocalRef(label);
    if (attached) bridge.jvm->DetachCurrentThread();
}

void UpdateScene(void* page) {
    std::string scene = SceneForPage(page);
    if (scene == currentScene) return;
    currentScene = std::move(scene);
    PublishPosition();
}

void Complete(int result) {
    auto completion = std::move(executing);
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        occupied = false;
    }
    if (completion && !completion->playbackFailure.empty()) {
        continuation::ReportError((completion->playbackFailure +
            (result == Restored ? "，已恢复最近选项" : "，游戏未能恢复最近选项")).c_str());
    }
    if (completion) completion->Deliver(result);
}

void Invalidate(const char* reason) {
    unavailable = true;
    LOGW("[ChoiceBack] session unavailable: %s", reason);
}

void ClearHistory() {
    // Iteration avoids recursive destruction of a long visit chain.
    while (top) {
        auto previous = std::move(top->previous);
        top = std::move(previous);
    }
    candidate.reset();
    target = nullptr;
}

bool CancelLoad() {
    if (!loadLease) return true;
    // Engine.CoStartSaveData yields the player reader as a nested IEnumerator;
    // it does not StartCoroutine on the player. Stop precisely our root routine.
    // The native external start/end then clears the separate scenario thread.
    if (!loadEnded && !api.StopRoutine(loadEngine.Get(), loadRoutine.Get())) return false;
    loadEnded = true;
    loadRoutine.Reset();
    loadEngine.Reset();
    loadLease.reset();
    return true;
}

void NewSession(void* value, bool start) {
    bool cancelled = CancelLoad();
    het::story_controls::EndSession();
    continuation::EndSession();
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        ++epoch;
    }
    if (executing) {
        executing->playbackFailure.clear();
        Complete(Unavailable);
    }
    ClearHistory();
    currentPage.Reset();
    engine = Root(value);
    active = start;
    unavailable = !cancelled;
    if (!cancelled) LOGW("[ChoiceBack] previous load could not be stopped");
    choiceRecorded = selectedOnPage = waiting = false;
    ++pageVisit;
    currentScene.clear();
    PublishPosition();
}

void* CurrentData() {
    return api.Get(api.Get(engine.Get(), "get_Page"), "get_CurrentData");
}
bool IsWaiting(void* manager) {
    return api.Bool(manager, "get_IsShowing") && api.Bool(manager, "get_IsWaitInput")
        && !api.Bool(manager, "get_IsSelected", true);
}

using Unary = void (*)(void*, const void*);
using Binary = void (*)(void*, void*, const void*);
using Start = void (*)(void*, void*, int, const void*);
using Predicate = bool (*)(void*, const void*);
Unary endOriginal = nullptr, destroyOriginal = nullptr;
Binary beginOriginal = nullptr, saveOriginal = nullptr, selectOriginal = nullptr, loadOriginal = nullptr;
Start startOriginal = nullptr;
Predicate waitOriginal = nullptr, moveOriginal = nullptr;
using Factory = void* (*)(void*, void*, const void*);
Factory startGameOriginal = nullptr;
Factory loadFactoryOriginal = nullptr;

void BeginPage(void* self, void* data, const void* method) {
    if (ready.load() && api.Get(self, "get_Engine") == engine.Get()) {
        ++pageVisit;
        candidateVisit = 0;
        candidate.reset();
        currentPage = Root(data);
        continuation::BeginPage(engine.Get(),data);
        waiting = choiceRecorded = selectedOnPage = false;
        if (executing && loadLease) {
            loadStartedPage = true;
            if (!loadLease->IsPage(data)) {
                CancelLoad();
                Invalidate("load left target page");
                Complete(Failed);
            }
        }
    }
    beginOriginal(self, data, method);
    if (ready.load() && active && currentPage.Get() == data && !loadLease
        && api.Get(self, "get_Engine") == engine.Get()) UpdateScene(data);
}

void SavePage(void* self, void* value, const void* method) {
    // This is the verified page-top BLR in CoStartPage, not a SavePoint or a
    // user save taken after commands have already changed state.
    const auto caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    saveOriginal(self, value, method);
    if (!ready.load() || !active || unavailable || loadLease || value != engine.Get()
        || caller != base + runtime.save_page_caller || !currentPage.Get()
        || CurrentData() != currentPage.Get()) return;
    candidate = Snapshot::Capture(api.Get(self, "get_CurrentAutoSaveData"), currentPage.Get());
    candidateVisit = pageVisit;
    if (!candidate) Invalidate("page-top snapshot copy failed");
}

bool WaitInput(void* self, const void* method) {
    bool result = waitOriginal(self, method);
    if (!ready.load() || !active || api.Get(self, "get_Engine") != engine.Get()
        || !IsWaiting(self)) return result;
    waiting = true;
    if (loadLease) {
        if (executing && loadStartedPage && loadLease->IsPage(CurrentData())) targetWaiting = true;
        return result;
    }
    if (unavailable) return result;
    if (choiceRecorded) {
        if (selectedOnPage) Invalidate("second choice shares an already executed page-top");
        return result;
    }
    if (!candidate || candidateVisit != pageVisit || !candidate->IsPage(CurrentData())) {
        Invalidate("choice has no matching independent page-top snapshot");
        return result;
    }
    auto node = std::unique_ptr<Node>(new (std::nothrow) Node);
    if (!node) { Invalidate("choice history allocation failed"); return result; }
    node->item.snapshot = std::move(candidate);
    node->item.routeSize = continuation::RouteSize();
    node->previous = std::move(top);
    top = std::move(node);
    choiceRecorded = true;
    currentScene = SceneForPage(CurrentData());
    continuation::AtChoice(engine.Get(),CurrentData());
    PublishPosition();
    LOGI("[ChoiceBack] recorded natural choice visit=%llu", static_cast<unsigned long long>(pageVisit));
    return result;
}

void Select(void* self, void* selection, const void* method) {
    bool ours = ready.load() && api.Get(self, "get_Engine") == engine.Get();
    if (ours && selection && selection == dispatchingSelection) {
        selectOriginal(self,selection,method);
        return;
    }
    if (ours && executing && loadLease) return; // Unity owns the restore transaction.
    if (ours && continuation::HandleSelection(selection)) return;
    if (ours) { continuation::Selected(selection); selectedOnPage = true; waiting = false; }
    selectOriginal(self, selection, method);
}
void StartScenario(void* self, void* label, int page, const void* method) {
    if (ready.load()) NewSession(api.Get(self, "get_Engine"), true);
    startOriginal(self, label, page, method);
}
void EndScenario(void* self, const void* method) {
    if (ready.load() && api.Get(self, "get_Engine") == engine.Get()) NewSession(nullptr, false);
    endOriginal(self, method);
}
void Destroy(void* self, const void* method) {
    bool ours = ready.load() && self == engine.Get();
    bool ownsLoad = ready.load() && self == loadEngine.Get();
    if (ours) NewSession(nullptr, false);
    destroyOriginal(self, method);
    if (ownsLoad) { loadLease.reset(); loadEngine.Reset(); loadRoutine.Reset(); }
}
void* StartGame(void* self, void* label, const void* method) {
    if (ready.load()) NewSession(self, false);
    return startGameOriginal(self, label, method);
}
void Load(void* self, void* save, const void* method) {
    if (ready.load()) {
        if (!internalLoad) NewSession(self, true);
        else if (loadLease && save == loadLease->Save()) enteredLoad = true;
    }
    loadOriginal(self, save, method);
}
bool MoveLoad(void* self, const void* method) {
    void* save = nullptr;
    bool ours = ready.load() && loadLease && api.Read(self, "saveData", save)
        && save == loadLease->Save();
    bool more = moveOriginal(self, method);
    if (ours && !more) loadEnded = true;
    return more;
}
void* CreateLoad(void* self, void* save, const void* method) {
    void* routine = loadFactoryOriginal(self, save, method);
    if (ready.load() && internalLoad && loadLease && save == loadLease->Save()) {
        loadRoutine = Root(routine);
    }
    return routine;
}

struct ImageMatch { uintptr_t base; const std::vector<unsigned char>& build_id; bool matched = false; };
int MatchImage(dl_phdr_info* info, size_t, void* context) {
    auto& match = *static_cast<ImageMatch*>(context);
    if (info->dlpi_addr != match.base) return 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const auto& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_NOTE) continue;
        const auto* cursor = reinterpret_cast<const unsigned char*>(info->dlpi_addr + ph.p_vaddr);
        const auto* end = cursor + ph.p_memsz;
        while (static_cast<size_t>(end - cursor) >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) note;
            std::memcpy(&note, cursor, sizeof(note));
            cursor += sizeof(note);
            size_t names = (static_cast<size_t>(note.n_namesz) + 3) & ~size_t(3);
            size_t descs = (static_cast<size_t>(note.n_descsz) + 3) & ~size_t(3);
            if (names > static_cast<size_t>(end - cursor)
                || descs > static_cast<size_t>(end - cursor) - names) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4
                && std::memcmp(cursor, "GNU", 4) == 0 && note.n_descsz == match.build_id.size()
                && std::memcmp(cursor + names, match.build_id.data(), match.build_id.size()) == 0) match.matched = true;
            cursor += names + descs;
        }
    }
    return 1;
}

bool Install() {
#if !defined(__aarch64__)
    return false;
#else
    if (!api.Init()) {
        LOGW("[ChoiceBack] IL2CPP metadata API unavailable");
        return false;
    }
    struct Hook { uintptr_t rva; void* replacement; void** original; void* stub; };
    Hook hooks[] = {
        {runtime.begin_page, reinterpret_cast<void*>(BeginPage), reinterpret_cast<void**>(&beginOriginal), nullptr},
        {runtime.save_page, reinterpret_cast<void*>(SavePage), reinterpret_cast<void**>(&saveOriginal), nullptr},
        {runtime.wait_input, reinterpret_cast<void*>(WaitInput), reinterpret_cast<void**>(&waitOriginal), nullptr},
        {runtime.select, reinterpret_cast<void*>(Select), reinterpret_cast<void**>(&selectOriginal), nullptr},
        {runtime.start_scenario, reinterpret_cast<void*>(StartScenario), reinterpret_cast<void**>(&startOriginal), nullptr},
        {runtime.end_scenario, reinterpret_cast<void*>(EndScenario), reinterpret_cast<void**>(&endOriginal), nullptr},
        {runtime.destroy, reinterpret_cast<void*>(Destroy), reinterpret_cast<void**>(&destroyOriginal), nullptr},
        {runtime.load, reinterpret_cast<void*>(Load), reinterpret_cast<void**>(&loadOriginal), nullptr},
        {runtime.move_load, reinterpret_cast<void*>(MoveLoad), reinterpret_cast<void**>(&moveOriginal), nullptr},
        {runtime.create_load, reinterpret_cast<void*>(CreateLoad), reinterpret_cast<void**>(&loadFactoryOriginal), nullptr},
        {runtime.start_game, reinterpret_cast<void*>(StartGame), reinterpret_cast<void**>(&startGameOriginal), nullptr},
    };
    for (auto& hook : hooks) {
        hook.stub = shadowhook_hook_func_addr(reinterpret_cast<void*>(base + hook.rva), hook.replacement, hook.original);
        if (!hook.stub) {
            for (auto& installed : hooks) if (installed.stub) shadowhook_unhook(installed.stub);
            LOGW("[ChoiceBack] hook installation failed at RVA=%lx", static_cast<unsigned long>(hook.rva));
            return false;
        }
    }
    LOGI("[ChoiceBack] bound configured arm64 GNU build ID");
    if (!runtime.continuation) {
        LOGW("[Continuation] no addresses configured for this build");
    } else if (!continuation::Install(base, *runtime.continuation)) {
        LOGW("[Continuation] could not install playback hooks for the configured build");
    }
    return true;
#endif
}

void ProcessRequest() {
    if (!active || unavailable || !engine.Get() || executing->epoch != epoch) { Complete(Unavailable); return; }
    bool recoverPlayback = !executing->playbackFailure.empty();
    bool generatedPlayback = continuation::IsPlaying();
    if (loadLease || (!recoverPlayback && !generatedPlayback && (api.Bool(engine.Get(), "get_IsLoading", true)
        || api.Bool(engine.Get(), "get_IsPausingScenario", true)))) { Complete(Busy); return; }
    void* manager = api.Get(engine.Get(), "get_SelectionManager");
    waiting = IsWaiting(manager);
    void* page = api.Get(engine.Get(), "get_Page");
    int32_t status = 0;
    // Sending text and waiting for a page-local effect are ordinary dialogue;
    // only command execution/page transition states must wait before loading.
    bool ordinaryPage = api.Read(page, "status", status)
        && (status == runtime.send_char || status == runtime.wait_effect_on_input_in_page
            || status == runtime.wait_input_in_page || status == runtime.wait_input_br_page);
    if (!recoverPlayback && !generatedPlayback && !waiting && (selectedOnPage || !ordinaryPage)) {
        Complete(Busy); return;
    }
    if (!recoverPlayback && waiting && !choiceRecorded) { Complete(Unavailable); return; }
    target = !recoverPlayback && waiting && top ? top->previous.get() : top.get();
    if (!target) { Complete(NoTarget); return; }
    // A separate deep copy is the load lease: Utage can mutate its loaded
    // buffers without changing the immutable historical snapshot.
    loadLease = target->item.snapshot->CopyForLoad();
    if (!loadLease) { Invalidate("load copy failed"); Complete(Unavailable); return; }
    continuation::LeaveChoice();
    loadEnded = targetWaiting = loadStartedPage = enteredLoad = false;
    loadEngine = Root(engine.Get());
    deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    void* args[] = { loadLease->Save() };
    internalLoad = true;
    bool called = api.Call(engine.Get(), "OpenLoadGame", 1, args);
    internalLoad = false;
    if (!called) {
        if (!enteredLoad || !loadRoutine.Get()) loadEnded = true;
        CancelLoad();
        Invalidate("OpenLoadGame failed");
        Complete(Failed);
    }
}

// The story opener shares the existing Unity mailbox with choice restoration.
// It owns no managed objects between frames and no durable request queue.
// LAH opens replay through its own quest UI. Keep imported HET entries from
// starting a replay until the matching LAH entry and return flow are verified.
void ProcessOpenStory() {
    Complete(Unavailable);
}
} // namespace

bool MatchesGameBuild(uintptr_t base, const ChoiceBackConfig& config) {
#if defined(__aarch64__)
    if (!base || config.build_id.empty()) return false;
    ImageMatch match{base, config.build_id};
    dl_iterate_phdr(MatchImage, &match);
    return match.matched;
#else
    return false;
#endif
}

void ConfigureChoiceBack(uintptr_t il2cpp_base, const ChoiceBackConfig& config) {
    base = il2cpp_base;
    runtime = config;
}

std::string ReadScenarioQuestLabel(void*, void*) {
    return {};
}

void PumpChoiceBack() {
    if (!initialized) {
        initialized = true;
        ready.store(Install());
        if (ready.load()) PublishPosition();
    }
    if (!ready.load()) return;
    if (active) het::story_controls::Update(engine.Get(), loadLease != nullptr);
    if (loadLease) {
        if (executing && loadEnded && targetWaiting && target && loadLease->IsPage(CurrentData())
            && IsWaiting(api.Get(engine.Get(), "get_SelectionManager"))) {
            while (top.get() != target) {
                auto previous = std::move(top->previous);
                top = std::move(previous);
            }
            candidate.reset();
            choiceRecorded = waiting = true;
            selectedOnPage = false;
            loadLease.reset();
            loadEngine.Reset();
            loadRoutine.Reset();
            currentScene = SceneForPage(CurrentData());
            continuation::RestoreRoute(top->item.routeSize);
            continuation::AtChoice(engine.Get(),CurrentData());
            PublishPosition();
            Complete(Restored);
        } else if (executing && std::chrono::steady_clock::now() >= deadline) {
            CancelLoad();
            Invalidate("target choice did not complete within the restore interval");
            Complete(Failed);
        }
        if (!executing && loadEnded) { loadLease.reset(); loadEngine.Reset(); loadRoutine.Reset(); }
    }
    std::string playbackFailure;
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        if (!executing && pending) {
            executing = std::move(pending);
            if (!executing->openStory) continuation::TakePlaybackFailure(&executing->playbackFailure);
        }
        else if (!executing && !occupied && !loadLease &&
            (continuation::TakePlaybackFailure(&playbackFailure) || continuation::TakePlayback())) {
            occupied=true;
        } else return;
    }
    if (!executing) {
        if (playbackFailure.empty() && active && !unavailable && waiting && choiceRecorded &&
            IsWaiting(api.Get(engine.Get(),"get_SelectionManager"))) {
            bool played = continuation::Play(engine.Get(),CurrentData());
            if (played || (waiting && continuation::RetryAtCurrentChoice())) {
                // A failure before Select leaves the current list and its history entry intact.
                if (!played) continuation::ReportError("续写剧本启动失败，请重新选择");
                std::lock_guard<std::mutex> lock(mailboxMutex);
                occupied = false;
                return;
            }
        }
        executing=std::make_unique<Completion>();
        executing->epoch=epoch;
        executing->playbackFailure=playbackFailure.empty() ? "续写剧本启动失败" : std::move(playbackFailure);
    }
    if (executing->openStory) ProcessOpenStory();
    else ProcessRequest();
}

bool DispatchContinuationSelection(void* manager,void* selection) {
    if (!ready.load() || !selectOriginal || !manager || !selection || !IsWaiting(manager)) return false;
    // Reuse the virtual selection's exact MethodInfo (Select(int) is a separate overload).
    const void* method=api.Overload(api.object_get_class(manager),"Select",1,0,api.object_get_class(selection));
    if (!method) return false;
    selectedOnPage=true;
    waiting=false;
    void* args[] = {selection};
    dispatchingSelection=selection;
    bool ok=api.Invoke(method,manager,args);
    dispatchingSelection=nullptr;
    return ok;
}

bool IsRestoringChoice(void* owner) {
    return owner == engine.Get() && executing && loadLease;
}

static void QueueGameRequest(JNIEnv* env, jobject callback, jstring questLabel) {
    jclass klass = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(klass, "onCompleted", "(I)V");
    env->DeleteLocalRef(klass);
    if (!method) return;
    auto request = std::unique_ptr<Completion>(new (std::nothrow) Completion);
    if (!request) { env->CallVoidMethod(callback, method, static_cast<jint>(Unavailable)); return; }
    env->GetJavaVM(&request->vm);
    request->object = env->NewGlobalRef(callback);
    request->method = method;
    request->openStory = questLabel != nullptr;
    if (!request->object) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->CallVoidMethod(callback, method, static_cast<jint>(Unavailable));
        return;
    }
    if (questLabel) {
        const jchar* text = env->GetStringChars(questLabel, nullptr);
        if (!text) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            request->Deliver(Unavailable);
            return;
        }
        request->questLabel.assign(reinterpret_cast<const char16_t*>(text), env->GetStringLength(questLabel));
        env->ReleaseStringChars(questLabel, text);
    }
    int rejected = -1;
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        if (!ready.load()) rejected = Unavailable;
        else if (occupied) rejected = Busy;
        else {
            occupied = true;
            request->epoch = epoch;
            pending = std::move(request);
        }
    }
    if (request) request->Deliver(rejected);
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_bridge_GameChoicePort_nativeRequestBack(JNIEnv* env, jclass, jobject callback) {
    QueueGameRequest(env, callback, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_bridge_GameChoicePort_nativeRequestOpenStory(JNIEnv* env, jclass, jstring label, jobject callback) {
    QueueGameRequest(env, callback, label);
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_bridge_GameChoicePort_nativeCancelOpenStory(JNIEnv* env, jclass, jobject callback) {
    std::unique_ptr<Completion> cancelled;
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        if (pending && pending->openStory && env->IsSameObject(pending->object, callback)) {
            cancelled = std::move(pending);
            occupied = false;
        }
    }
    if (cancelled) cancelled->Deliver(6);
}
