#include "runtime/choice_back.hpp"
#include "housamo.hpp"
#include "shadowhook.h"

#include <jni.h>
#include <link.h>
#include <cstring>
#include <chrono>
#include <new>
#include <utility>

namespace {
enum Result { Restored = 0, NoTarget = 1, Busy = 2, Unavailable = 3, Failed = 5 };
enum class PageStatus : int {
    None = 0,
    SendChar = 1,
    WaitEffectOnInputInPage = 2,
    WaitInputInPage = 3,
    OtherCommandInPage = 4,
    WaitEffectOnEndPage = 5,
    WaitInputBrPage = 6
};

// Housamo 5.20.0 / 1378, arm64-v8a. The load-image GNU build ID gates every
// version-specific hook. No address/offset below is used on another image.
constexpr unsigned char kBuildId[] = {
    0x30,0xe2,0xc4,0x3b,0x8b,0xba,0x72,0x4a,0xd0,0xfd,
    0x08,0xad,0x88,0xe1,0x61,0xbf,0xd7,0x3e,0xdd,0xcb
};
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

    void Deliver(int result) {
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

struct Api {
    void* handle = nullptr;
    void* (*domain_get)() = nullptr;
    const void** (*domain_get_assemblies)(void*, size_t*) = nullptr;
    void* (*assembly_get_image)(const void*) = nullptr;
    void* (*class_from_name)(void*, const char*, const char*) = nullptr;
    void* (*class_get_parent)(void*) = nullptr;
    const void* (*class_get_method_from_name)(void*, const char*, int) = nullptr;
    const void* (*class_get_methods)(void*, void**) = nullptr;
    const char* (*method_get_name)(const void*) = nullptr;
    uint32_t (*method_get_param_count)(const void*) = nullptr;
    const void* (*method_get_param)(const void*, uint32_t) = nullptr;
    void* (*class_from_type)(const void*) = nullptr;
    void* (*class_get_field_from_name)(void*, const char*) = nullptr;
    void* (*object_get_class)(void*) = nullptr;
    bool (*class_is_valuetype)(void*) = nullptr;
    void* (*object_unbox)(void*) = nullptr;
    void* (*object_new)(void*) = nullptr;
    void* (*runtime_invoke)(const void*, void*, void**, void**) = nullptr;
    void* (*field_get_value_object)(void*, void*) = nullptr;
    void (*field_get_value)(void*, void*, void*) = nullptr;
    void (*field_set_value)(void*, void*, void*) = nullptr;
    void (*field_set_value_object)(void*, void*, void*) = nullptr;
    uint32_t (*gchandle_new)(void*, bool) = nullptr;
    void* (*gchandle_get_target)(uint32_t) = nullptr;
    void (*gchandle_free)(uint32_t) = nullptr;

    bool Init() {
        handle = shadowhook_dlopen("libil2cpp.so");
        if (!handle) return false;
#define LOAD(name) name = reinterpret_cast<decltype(name)>(shadowhook_dlsym(handle, "il2cpp_" #name)); if (!name) return false
        LOAD(domain_get); LOAD(domain_get_assemblies); LOAD(assembly_get_image);
        LOAD(class_from_name); LOAD(class_get_parent); LOAD(class_get_method_from_name);
        LOAD(class_get_methods); LOAD(method_get_name); LOAD(method_get_param_count);
        LOAD(method_get_param); LOAD(class_from_type);
        LOAD(class_get_field_from_name); LOAD(object_get_class); LOAD(class_is_valuetype);
        LOAD(object_unbox); LOAD(object_new); LOAD(runtime_invoke);
        LOAD(field_get_value_object); LOAD(field_get_value); LOAD(field_set_value);
        LOAD(field_set_value_object); LOAD(gchandle_new); LOAD(gchandle_get_target); LOAD(gchandle_free);
#undef LOAD
        return true;
    }
    void* Class(const char* ns, const char* name) {
        size_t count = 0;
        const void** assemblies = domain_get_assemblies(domain_get(), &count);
        for (size_t i = 0; i < count; ++i) {
            void* found = class_from_name(assembly_get_image(assemblies[i]), ns, name);
            if (found) return found;
        }
        return nullptr;
    }
    const void* Method(void* klass, const char* name, int argc) {
        for (; klass; klass = class_get_parent(klass)) {
            const void* result = class_get_method_from_name(klass, name, argc);
            if (result) return result;
        }
        return nullptr;
    }
    void* Field(void* object, const char* name) {
        for (void* klass = object_get_class(object); klass; klass = class_get_parent(klass)) {
            void* field = class_get_field_from_name(klass, name);
            if (field) return field;
        }
        return nullptr;
    }
    bool Call(void* object, const char* name, int argc = 0, void** args = nullptr, void** out = nullptr) {
        if (!object) return false;
        const void* method = Method(object_get_class(object), name, argc);
        if (!method) return false;
        void* exception = nullptr;
        void* value = runtime_invoke(method, object, args, &exception);
        if (exception) {
            LOGW("[ChoiceBack] managed operation failed: %s", name);
            return false;
        }
        if (out) *out = value;
        return true;
    }
    bool StopRoutine(void* owner, void* routine) {
        if (!routine) return true;
        void* mono = Class("UnityEngine", "MonoBehaviour");
        void* enumerator = Class("System.Collections", "IEnumerator");
        if (!owner || !mono || !enumerator) return false;
        void* iter = nullptr;
        while (const void* method = class_get_methods(mono, &iter)) {
            if (std::strcmp(method_get_name(method), "StopCoroutine") != 0
                || method_get_param_count(method) != 1
                || class_from_type(method_get_param(method, 0)) != enumerator) continue;
            void* args[] = { routine };
            void* exception = nullptr;
            runtime_invoke(method, owner, args, &exception);
            return !exception;
        }
        return false;
    }
    void* Get(void* object, const char* name) {
        void* out = nullptr;
        return Call(object, name, 0, nullptr, &out) ? out : nullptr;
    }
    bool Bool(void* object, const char* name, bool fallback = false) {
        void* boxed = Get(object, name);
        return boxed ? *static_cast<bool*>(object_unbox(boxed)) : fallback;
    }
    template<typename T> bool Read(void* object, const char* name, T& result) {
        if (!object) return false;
        void* field = Field(object, name);
        if (!field) return false;
        field_get_value(object, field, &result);
        return true;
    }
} api;

class Root {
    uint32_t handle_ = 0;
public:
    Root() = default;
    explicit Root(void* value) : handle_(value ? api.gchandle_new(value, false) : 0) {}
    ~Root() { Reset(); }
    Root(const Root&) = delete;
    Root& operator=(const Root&) = delete;
    Root(Root&& other) noexcept : handle_(std::exchange(other.handle_, 0)) {}
    Root& operator=(Root&& other) noexcept {
        if (this != &other) { Reset(); handle_ = std::exchange(other.handle_, 0); }
        return *this;
    }
    void Reset() { if (handle_) api.gchandle_free(std::exchange(handle_, 0)); }
    void* Get() const { return handle_ ? api.gchandle_get_target(handle_) : nullptr; }
};

Root NewObject(const char* ns, const char* name) {
    void* klass = api.Class(ns, name);
    return Root(klass ? api.object_new(klass) : nullptr);
}

// A Snapshot is opaque to the tracker: its native save object, all buffer
// bytes and page-identity root are owned here. No System state is collected.
class Snapshot {
    Root save_;
    Root page_;
public:
    Snapshot(Root save, void* page) : save_(std::move(save)), page_(page) {}
    void* Save() const { return save_.Get(); }
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

struct Item { std::unique_ptr<Snapshot> snapshot; };
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
uint64_t pageVisit = 0;
uint64_t candidateVisit = 0;

// The load lease is independent from the history. Lifecycle invalidation can
// clear history without releasing the object still used by the coroutine.
std::unique_ptr<Snapshot> loadLease;
Node* target = nullptr;
bool loadEnded = false;
bool targetWaiting = false;
bool loadStartedPage = false;
bool enteredLoad = false;
std::chrono::steady_clock::time_point deadline;

void Complete(int result) {
    auto completion = std::move(executing);
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        occupied = false;
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
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        ++epoch;
    }
    if (executing) Complete(Unavailable);
    ClearHistory();
    currentPage.Reset();
    engine = Root(value);
    active = start;
    unavailable = !cancelled;
    if (!cancelled) LOGW("[ChoiceBack] previous load could not be stopped");
    choiceRecorded = selectedOnPage = waiting = false;
    ++pageVisit;
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
}

void SavePage(void* self, void* value, const void* method) {
    // This is the verified page-top BLR in CoStartPage, not a SavePoint or a
    // user save taken after commands have already changed state.
    const auto caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    saveOriginal(self, value, method);
    if (!ready.load() || !active || unavailable || loadLease || value != engine.Get()
        || caller != base + 0x2380FC8 || !currentPage.Get()
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
    node->previous = std::move(top);
    top = std::move(node);
    choiceRecorded = true;
    LOGI("[ChoiceBack] recorded natural choice visit=%llu", static_cast<unsigned long long>(pageVisit));
    return result;
}

void Select(void* self, void* selection, const void* method) {
    bool ours = ready.load() && api.Get(self, "get_Engine") == engine.Get();
    if (ours && executing && loadLease) return; // Unity owns the restore transaction.
    if (ours) { selectedOnPage = true; waiting = false; }
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

struct ImageMatch { uintptr_t base; bool matched = false; };
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
                && std::memcmp(cursor, "GNU", 4) == 0 && note.n_descsz == sizeof(kBuildId)
                && std::memcmp(cursor + names, kBuildId, sizeof(kBuildId)) == 0) match.matched = true;
            cursor += names + descs;
        }
    }
    return 1;
}

bool Install() {
#if !defined(__aarch64__)
    return false;
#else
    ImageMatch match{base};
    dl_iterate_phdr(MatchImage, &match);
    if (!match.matched || !api.Init()) {
        LOGW("[ChoiceBack] target build ID or IL2CPP metadata API unavailable");
        return false;
    }
    struct Hook { uintptr_t rva; void* replacement; void** original; void* stub; };
    Hook hooks[] = {
        {0x2368A04, reinterpret_cast<void*>(BeginPage), reinterpret_cast<void**>(&beginOriginal), nullptr},
        {0x23735DC, reinterpret_cast<void*>(SavePage), reinterpret_cast<void**>(&saveOriginal), nullptr},
        {0x2369784, reinterpret_cast<void*>(WaitInput), reinterpret_cast<void**>(&waitOriginal), nullptr},
        {0x236F9F4, reinterpret_cast<void*>(Select), reinterpret_cast<void**>(&selectOriginal), nullptr},
        {0x237DD7C, reinterpret_cast<void*>(StartScenario), reinterpret_cast<void**>(&startOriginal), nullptr},
        {0x237E88C, reinterpret_cast<void*>(EndScenario), reinterpret_cast<void**>(&endOriginal), nullptr},
        {0x2848B0C, reinterpret_cast<void*>(Destroy), reinterpret_cast<void**>(&destroyOriginal), nullptr},
        {0x284A00C, reinterpret_cast<void*>(Load), reinterpret_cast<void**>(&loadOriginal), nullptr},
        {0x231C2F4, reinterpret_cast<void*>(MoveLoad), reinterpret_cast<void**>(&moveOriginal), nullptr},
        {0x284A048, reinterpret_cast<void*>(CreateLoad), reinterpret_cast<void**>(&loadFactoryOriginal), nullptr},
        {0x284A194, reinterpret_cast<void*>(StartGame), reinterpret_cast<void**>(&startGameOriginal), nullptr},
    };
    for (auto& hook : hooks) {
        hook.stub = shadowhook_hook_func_addr(reinterpret_cast<void*>(base + hook.rva), hook.replacement, hook.original);
        if (!hook.stub) {
            for (auto& installed : hooks) if (installed.stub) shadowhook_unhook(installed.stub);
            LOGW("[ChoiceBack] hook installation failed at RVA=%lx", static_cast<unsigned long>(hook.rva));
            return false;
        }
    }
    LOGI("[ChoiceBack] bound Housamo 5.20.0/1378 arm64 GNU build ID");
    return true;
#endif
}

void ProcessRequest() {
    if (!active || unavailable || !engine.Get() || executing->epoch != epoch) { Complete(Unavailable); return; }
    if (loadLease || api.Bool(engine.Get(), "get_IsLoading", true)
        || api.Bool(engine.Get(), "get_IsPausingScenario", true)) { Complete(Busy); return; }
    void* manager = api.Get(engine.Get(), "get_SelectionManager");
    waiting = IsWaiting(manager);
    void* page = api.Get(engine.Get(), "get_Page");
    PageStatus status = PageStatus::None;
    // Sending text and waiting for a page-local effect are ordinary dialogue;
    // only command execution/page transition states must wait before loading.
    bool ordinaryPage = api.Read(page, "status", status)
        && (status == PageStatus::SendChar || status == PageStatus::WaitEffectOnInputInPage
            || status == PageStatus::WaitInputInPage || status == PageStatus::WaitInputBrPage);
    if (!waiting && (selectedOnPage || !ordinaryPage)) {
        Complete(Busy); return;
    }
    if (waiting && !choiceRecorded) { Complete(Unavailable); return; }
    target = waiting && top ? top->previous.get() : top.get();
    if (!target) { Complete(NoTarget); return; }
    // A separate deep copy is the load lease: Utage can mutate its loaded
    // buffers without changing the immutable historical snapshot.
    loadLease = target->item.snapshot->CopyForLoad();
    if (!loadLease) { Invalidate("load copy failed"); Complete(Unavailable); return; }
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
} // namespace

void ConfigureChoiceBack(uintptr_t il2cpp_base) { base = il2cpp_base; }

void PumpChoiceBack() {
    if (!initialized) {
        initialized = true;
        ready.store(Install());
    }
    if (!ready.load()) return;
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
            Complete(Restored);
        } else if (executing && std::chrono::steady_clock::now() >= deadline) {
            CancelLoad();
            Invalidate("target choice did not complete within the restore interval");
            Complete(Failed);
        }
        if (!executing && loadEnded) { loadLease.reset(); loadEngine.Reset(); loadRoutine.Reset(); }
    }
    {
        std::lock_guard<std::mutex> lock(mailboxMutex);
        if (!executing && pending) executing = std::move(pending);
        else return;
    }
    ProcessRequest();
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_bridge_GameChoicePort_nativeRequestBack(JNIEnv* env, jclass, jobject callback) {
    jclass klass = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(klass, "onCompleted", "(I)V");
    env->DeleteLocalRef(klass);
    if (!method) return;
    auto request = std::unique_ptr<Completion>(new (std::nothrow) Completion);
    if (!request) { env->CallVoidMethod(callback, method, static_cast<jint>(Unavailable)); return; }
    env->GetJavaVM(&request->vm);
    request->object = env->NewGlobalRef(callback);
    request->method = method;
    if (!request->object) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->CallVoidMethod(callback, method, static_cast<jint>(Unavailable));
        return;
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
