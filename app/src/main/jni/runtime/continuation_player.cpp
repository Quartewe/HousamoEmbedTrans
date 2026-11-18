#include "runtime/continuation_player.hpp"
#include "runtime/continuation_source.hpp"
#include "runtime/continuation_sheet.hpp"
#include "runtime/choice_back.hpp"
#include "scene/choice_join.hpp"
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
struct Source {
    uint32_t weak = 0;
    std::string scene, hash, capturedHash;
    std::unordered_map<void*, std::vector<OrderKey>> pages;
    struct ContextAdvance {
        int nextLabel;
        OrderKey stop;
    };
    std::vector<ContextAdvance> nextContext;
    ChoiceJoins joins;
    const std::vector<std::string> labelNames;
    std::unordered_map<OrderKey, OrderKey, OrderKeyHash> choiceForOption, originalText;
    std::unordered_map<std::string, int> labels;
    std::unordered_set<OrderKey, OrderKeyHash> stageOrders;
    explicit Source(const ScenarioParseResult& parsed) :
        joins(parsed.continuation_flow.size()), labelNames(parsed.stage.labels) {
        for (const auto& label : parsed.continuation_flow) {
            int from = static_cast<int>(nextContext.size());
            for (int to : label.targets) joins.add(from, to);
            nextContext.push_back({label.next_context, label.stop});
        }
    }
    ~Source() {
        if (weak)
            api.gchandle_free(weak);
    }
};
std::mutex sourcesMutex;
std::unordered_map<void*, std::shared_ptr<Source>> sources;
std::mutex sheetsMutex;
std::unordered_set<void*> temporary;
std::vector<std::unique_ptr<Sheet>> sheets;
struct RouteEntry {
    std::string scene, record, path;
    OrderKey order;
    bool stage = false;
    std::string capturedHash; // Only a localized row with no JP counterpart uses this.
};
std::vector<RouteEntry> route;
struct Node {
    std::string scene, hash, merge, record, path;
    OrderKey original;
    size_t lineageStart = 0;
    ContinuationWindow mergeWindow;
};
Node node;
Root button, currentEngine, currentPage;
// QuestWriter may refresh visible captions off the playback thread. It only compares this identity.
std::atomic<void*> inputButtonIdentity{nullptr};
std::shared_ptr<Source> currentSource;
Sheet* currentSheet = nullptr;
bool enteringSheet = false;
std::string currentLabel;
std::string playbackFailure;
Root runningSelection;
uint64_t serial = 0;
const std::string processId = std::to_string(std::chrono::system_clock::now().time_since_epoch().count());
struct Delivery {
    std::string token, record, steps;
};
std::mutex deliveryMutex;
std::string currentToken;
std::string acceptedRecord;
std::unique_ptr<Delivery> queued, claimed;

Json Str(const std::string& s, Alloc& a) {
    return Json(s.data(), static_cast<rapidjson::SizeType>(s.size()), a);
}
Json Order(const OrderKey& k, Alloc& a) {
    Json v(rapidjson::kObjectType);
    v.AddMember("label_index", k.label_index, a)
        .AddMember("page_no", k.page_no, a)
        .AddMember("cmd_index", k.cmd_index, a)
        .AddMember("sub_index", k.sub_index, a);
    return v;
}
std::string Dump(const Json& v) {
    rapidjson::StringBuffer b;
    rapidjson::Writer<rapidjson::StringBuffer> w(b);
    v.Accept(w);
    return {b.GetString(), b.GetSize()};
}
void* Item(void* list, int index) {
    void* out = nullptr;
    void* args[] = {&index};
    return api.Call(list, "get_Item", 1, args, &out) ? out : nullptr;
}
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
std::shared_ptr<Source> FindSource(void* scenario) {
    std::lock_guard<std::mutex> lock(sourcesMutex);
    auto it = sources.find(scenario);
    return it != sources.end() && api.gchandle_get_target(it->second->weak) == scenario ? it->second
                                                                                        : nullptr;
}
struct JavaCall {
    JNIEnv* env = nullptr;
    bool attached = false;
    JavaCall() {
        auto& bridge = g_java_bridge;
        if (!bridge.jvm || !bridge.main_hook_class)
            return;
        if (bridge.jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
            attached = bridge.jvm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    ~JavaCall() {
        if (env && env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGW("[Continuation] Java callback failed");
        }
        if (attached)
            g_java_bridge.jvm->DetachCurrentThread();
    }
    jmethodID Method(const char* name, const char* sig) {
        return env ? env->GetStaticMethodID(g_java_bridge.main_hook_class, name, sig) : nullptr;
    }
    jstring String(const std::string& value) {
        Root s(api.string_new_len(value.data(), static_cast<uint32_t>(value.size())));
        return s.Get() ? env->NewString(reinterpret_cast<const jchar*>(api.string_chars(s.Get())),
                                        api.string_length(s.Get()))
                       : nullptr;
    }
};
bool Enabled() {
    JavaCall call;
    auto method = call.Method("isContinuationEnabled", "()Z");
    return method && call.env->CallStaticBooleanMethod(g_java_bridge.main_hook_class, method);
}
void Notify(const char* methodName, const std::string* payload = nullptr) {
    JavaCall call;
    auto method = call.Method(methodName, payload ? "(Ljava/lang/String;)V" : "()V");
    if (!method)
        return;
    if (payload) {
        jstring str = call.String(*payload);
        if (str) {
            call.env->CallStaticVoidMethod(g_java_bridge.main_hook_class, method, str);
            call.env->DeleteLocalRef(str);
        }
    } else
        call.env->CallStaticVoidMethod(g_java_bridge.main_hook_class, method);
}
std::string SourceHash(const std::string& source_document) {
    JavaCall call;
    auto method = call.Method("continuationSourceHash", "(Ljava/lang/String;)Ljava/lang/String;");
    if (!method)
        return {};
    auto data = call.String(source_document);
    if (!data)
        return {};
    auto result =
        static_cast<jstring>(call.env->CallStaticObjectMethod(g_java_bridge.main_hook_class, method, data));
    call.env->DeleteLocalRef(data);
    if (!result)
        return {};
    auto chars = call.env->GetStringUTFChars(result, nullptr);
    std::string hash = chars ? chars : "";
    if (chars)
        call.env->ReleaseStringUTFChars(result, chars);
    call.env->DeleteLocalRef(result);
    return hash;
}
using PositionMap = std::unordered_map<OrderKey, OrderKey, OrderKeyHash>;
void RecordChoices(Source& source, const std::vector<SceneItem>& items, const PositionMap& originalGroups) {
    for (const auto& item : items) {
        if (auto* choice = std::get_if<ChoiceBlock>(&item.value)) {
            auto mapped = originalGroups.find(choice->order);
            const OrderKey group = mapped == originalGroups.end() ? choice->order : mapped->second;
            for (const auto& branch : choice->branches) {
                for (const auto& option : branch.options) source.choiceForOption.emplace(option.order, group);
                RecordChoices(source, branch.following_text, originalGroups);
            }
        } else if (auto* condition = std::get_if<IfBlock>(&item.value))
            RecordChoices(source, condition->following_text, originalGroups);
    }
}
OrderKey CommandOrder(void* page, void* command, void* row = nullptr) {
    if (!currentSource)
        return {};
    auto it = currentSource->pages.find(page);
    if (it == currentSource->pages.end())
        return {};
    void* list = api.Get(page, "get_CommandList");
    int count = api.Int(list, "get_Count");
    OrderKey matched;
    for (auto& key : it->second) {
        if (row && !currentSource->choiceForOption.count(key)) continue;
        if (key.cmd_index < 0 || key.cmd_index >= count)
            continue;
        void* element = Item(list, key.cmd_index);
        if ((command && element == command) || (row && api.Get(element, "get_RowData") == row)) {
            if (matched.label_index >= 0 && !(matched == key)) return {};
            matched = key;
        }
    }
    return matched;
}

void RecordOriginalLine(const OrderKey& view) {
    if (view.label_index < 0 || !currentSource) return;
    auto found = currentSource->originalText.find(view);
    if (found == currentSource->originalText.end())
        route.push_back({currentSource->scene, {}, {}, view});
    else if (found->second.label_index >= 0)
        route.push_back({currentSource->scene, {}, {}, found->second});
    else
        route.push_back({currentSource->scene, {}, {}, view, false, currentSource->capturedHash});
}

Json Screen(Alloc& a) {
    Json screen(rapidjson::kObjectType), characters(rapidjson::kArrayType);
    void* graphic = api.Get(currentEngine.Get(), "get_GraphicManager");
    void* group = api.Get(graphic, "get_BgManager");
    void* bgArgs[] = {api.Get(graphic, "get_BgSpriteName")};
    void* bg = nullptr;
    api.Call(group,"FindObject",1,bgArgs,&bg);
    auto background=api.Text(api.Get(api.Get(bg,"get_LastResource"),"get_Key"));
    {
        group = api.Get(graphic, "get_CharacterManager");
        Root objects(api.Get(group, "AllGraphics"));
        int count = api.Int(objects.Get(), "get_Count");
        for (int i = 0; i < count; ++i) {
            void* obj = Item(objects.Get(), i);
            void* last = api.Get(obj, "get_LastResource");
            if (!last)
                continue;
            void* setting = api.Get(last, "get_SettingData");
            Json info(rapidjson::kObjectType);
            std::string name = api.Text(api.Get(setting, "get_Name"));
            if (name.empty())
                name = api.Text(api.Get(obj, "get_name"));
            info.AddMember("character", Str(name, a), a)
                .AddMember("pattern", Str(api.Text(api.Get(setting, "get_Pattern")), a), a);
            void* layer = api.Get(api.Get(obj, "get_Layer"), "get_SettingData");
            info.AddMember("layer", Str(api.Text(api.Get(layer, "get_Key")), a), a);
            Root pos(api.Get(api.Get(obj, "get_transform"), "get_localPosition"));
            float x = 0, y = 0;
            if (api.Read(pos.Get(), "x", x) && api.Read(pos.Get(), "y", y) && std::isfinite(x) &&
                std::isfinite(y))
                info.AddMember("x", x, a).AddMember("y", y, a);
            characters.PushBack(info, a);
        }
    }
    screen.AddMember("background", Str(background, a), a).AddMember("characters", characters, a);
    return screen;
}
std::string NodeJson() {
    Document doc(rapidjson::kObjectType);
    auto& a = doc.GetAllocator();
    Json source(rapidjson::kObjectType), history(rapidjson::kArrayType);
    if (node.record.empty())
        source.AddMember("type", "original", a).AddMember("order", Order(node.original, a), a);
    else
        source.AddMember("type", "generated", a)
            .AddMember("record_id", Str(node.record, a), a)
            .AddMember("node_path", Str(node.path, a), a);
    for (size_t i = 0; i < route.size(); ++i) {
        const auto& entry = route[i];
        if (entry.scene != node.scene)
            continue;
        if (entry.record.empty()) {
            if (entry.stage) {
                Json ref(rapidjson::kObjectType);
                ref.AddMember("order", Order(entry.order, a), a).AddMember("stage", true, a);
                history.PushBack(ref, a);
            } else if (!entry.capturedHash.empty()) {
                Json ref(rapidjson::kObjectType);
                ref.AddMember("captured_hash", Str(entry.capturedHash, a), a).AddMember("order", Order(entry.order, a), a);
                history.PushBack(ref, a);
            } else
                history.PushBack(Order(entry.order, a), a);
        } else if (!node.record.empty() && i >= node.lineageStart) {
            Json ref(rapidjson::kObjectType);
            ref.AddMember("record_id", Str(entry.record, a), a);
            if (entry.path.empty())
                ref.AddMember("input", true, a);
            else
                ref.AddMember("node_path", Str(entry.path, a), a);
            history.PushBack(ref, a);
        }
    }
    doc.AddMember("scene", Str(node.scene, a), a)
        .AddMember("source_hash", Str(node.hash, a), a)
        .AddMember("source", source, a)
        .AddMember("original_order", Order(node.original, a), a)
        .AddMember("route", history, a)
        .AddMember("screen", Screen(a), a);
    Json window(rapidjson::kArrayType);
    for (const auto& span : node.mergeWindow) {
        Json segment(rapidjson::kObjectType);
        segment.AddMember("label_index", span.label_index, a);
        if (span.stop.label_index >= 0) segment.AddMember("stop", Order(span.stop, a), a);
        window.PushBack(segment, a);
    }
    doc.AddMember("merge_window", window, a);
    std::lock_guard<std::mutex> lock(deliveryMutex);
    doc.AddMember("play_token", Str(currentToken, a), a);
    return Dump(doc);
}

using Unary = void (*)(void*, const void*);
using Binary = void (*)(void*, void*, const void*);
using AddReadFn = void (*)(void*, void*, int, const void*);
using Predicate = bool (*)(void*, const void*);
Unary originalShow = nullptr;
Binary originalText = nullptr, originalHistory = nullptr, originalTap = nullptr;
AddReadFn originalRead = nullptr;
Predicate originalLoadEnd = nullptr, originalMovePage = nullptr;
thread_local void* invokingGeneratedPage = nullptr;
Binary originalCharacter = nullptr, originalOff = nullptr, originalBg = nullptr, originalBgOff = nullptr;

void StageExecuted(void* command, void* engine) {
    if (currentEngine.Get() != engine || !currentPage.Get())
        return;
    if (currentSheet) {
        auto step = currentSheet->steps.find(currentLabel);
        if (step != currentSheet->steps.end())
            route.push_back({currentSheet->scene, currentSheet->record, step->second.path, {}, true});
        return;
    }
    if (!currentSource)
        return;
    auto found = currentSource->labels.find(currentLabel);
    if (found == currentSource->labels.end())
        return;
    void* list = api.Get(currentPage.Get(), "get_CommandList");
    void* index = nullptr;
    void* args[] = {command};
    if (!api.Call(list, "IndexOf", 1, args, &index) || !index)
        return;
    OrderKey key{found->second, api.Int(currentPage.Get(), "get_PageNo"),
                 *static_cast<int*>(api.object_unbox(index)), 0};
    if (currentSource->stageOrders.count(key))
        route.push_back({currentSource->scene, {}, {}, key, true});
}
void Character(void* cmd, void* engine, const void* m) {
    originalCharacter(cmd, engine, m);
    StageExecuted(cmd, engine);
}
void CharacterOff(void* cmd, void* engine, const void* m) {
    originalOff(cmd, engine, m);
    StageExecuted(cmd, engine);
}
void Background(void* cmd, void* engine, const void* m) {
    originalBg(cmd, engine, m);
    StageExecuted(cmd, engine);
}
void BackgroundOff(void* cmd, void* engine, const void* m) {
    originalBgOff(cmd, engine, m);
    StageExecuted(cmd, engine);
}
bool HasGeneratedLabel(const std::string& label) {
    for (const auto& sheet : sheets) {
        const size_t prefix = sheet->entry.rfind('_') + 1;
        if (label.compare(0, prefix, sheet->entry, 0, prefix) == 0)
            return true;
    }
    return false;
}
bool LoadEnd(void* command, const void* method) {
    bool loaded = originalLoadEnd(command, method);
    if (!invokingGeneratedPage)
        return loaded;
    void* files = api.Get(command, "get_LoadFileList");
    int count = api.Int(files, "get_Count");
    for (int i = 0; i < count; ++i) {
        auto file = Item(files, i);
        if (!api.Bool(file, "get_IsLoadError"))
            continue;
        playbackFailure = "资源加载失败：" + api.Text(api.Get(file, "get_LoadErrorMsg"));
        return false;
    }
    return loaded;
}
bool MovePage(void* routine, const void* method) {
    if (invokingGeneratedPage == routine || sheets.empty())
        return originalMovePage(routine, method);
    void* page = nullptr;
    if (!api.Read(routine, "pageData", page) || !HasGeneratedLabel(PageLabel(page)))
        return originalMovePage(routine, method);
    if (!playbackFailure.empty())
        return true; // Keep coroutine parked until recovery takes ownership.
    void* prev = invokingGeneratedPage;
    invokingGeneratedPage = routine;
    auto m = method ? method : api.Method(api.object_get_class(routine), "MoveNext", 0);
    void* exception = nullptr;
    void* result = m ? api.runtime_invoke(m, routine, nullptr, &exception) : nullptr;
    invokingGeneratedPage = prev;
    if (exception || !result) {
        playbackFailure = "剧情演出执行失败";
        if (exception) {
            auto detail = api.Text(api.Get(exception, "get_Message"));
            if (!detail.empty())
                playbackFailure += "：" + detail;
        }
        return true;
    }
    return *static_cast<bool*>(api.object_unbox(result));
}
void Tap(void* ui, void* item, const void* method) {
    // In build 1380 OnTap calls Select(data), then Close() even if Select was consumed.
    if (IsRestoringChoice(api.Get(ui, "get_Engine"))) return;
    if (button.Get() && HandleSelection(api.Get(item, "get_Data"))) return;
    originalTap(ui, item, method);
}

void Show(void* manager, const void* method) {
    void* list = api.Get(manager, "get_Selections");
    if (installed && Enabled() && currentPage.Get() && currentEngine.Get() == api.Get(manager, "get_Engine")) {
        int count = api.Int(list, "get_Count", 0);
        auto sprites = api.Get(manager, "get_SpriteSelections");
        int spriteCount = api.Int(sprites, "get_Count", 0);
        if (count + spriteCount == 0) { originalShow(manager, method); return; }
        for (int i = 0; button.Get() && i < count; ++i) {
            if (Item(list, i) == button.Get()) { originalShow(manager, method); return; }
        }
        if (button.Get()) LeaveChoice();
        auto first = Item(count ? list : sprites, 0);
        Node next;
        if (currentSheet) {
            auto it = currentSheet->steps.find(currentLabel);
            if (it != currentSheet->steps.end() && it->second.choice)
                next = {currentSheet->scene,       currentSheet->sourceHash, currentSheet->merge,
                        currentSheet->record,      it->second.path,          currentSheet->original,
                        currentSheet->lineageStart, currentSheet->mergeWindow};
        } else if (currentSource && !currentSource->nextContext.empty()) {
            OrderKey group;
            std::vector<int> starts;
            for (int i = 0; i < count + spriteCount; ++i) {
                auto selection = i < count ? Item(list, i) : Item(sprites, i - count);
                auto label = currentSource->labels.find(api.Text(api.Get(selection, "get_JumpLabel")));
                int target = label == currentSource->labels.end() ? -1 : label->second;
                if (std::find(starts.begin(), starts.end(), target) == starts.end()) starts.push_back(target);
                auto key = CommandOrder(currentPage.Get(), nullptr, api.Get(selection, "get_RowData"));
                auto owner = currentSource->choiceForOption.find(key);
                if (owner == currentSource->choiceForOption.end()) continue;
                if (owner->second.label_index < 0 || (group.label_index >= 0 && !(group == owner->second))) {
                    group = {};
                    break;
                }
                group = owner->second;
            }
            if (group.label_index >= 0) {
                next = {currentSource->scene, currentSource->hash, {}, {}, {}, group, route.size(), {}};
                int at = currentSource->joins.nearest(starts);
                if (at >= 0) next.merge = currentSource->labelNames[at];
                std::unordered_set<int> visited;
                while (at >= 0 && visited.insert(at).second) {
                    const auto& nextSpan = currentSource->nextContext[at];
                    next.mergeWindow.push_back({at, nextSpan.stop});
                    at = nextSpan.nextLabel;
                }
            }
        }
        if (!next.scene.empty() && !next.merge.empty()) {
            Root merge(api.string_new(next.merge.c_str()));
            void* manager = api.Get(currentEngine.Get(), "get_DataManager");
            auto method =
                manager ? api.Method(api.object_get_class(manager), "FindScenarioData", 1) : nullptr;
            void* actual = method ? FindScenarioDataForPosition(manager, merge.Get(), method) : nullptr;
            auto source = FindSource(actual);
            if (!source || source->scene != next.scene || source->hash != next.hash)
                next = {};
        }
        {
            // Use the game's ordinary selection constructor and prefab layout.
            // The original row supplies the UI/save metadata. The command has
            // no expression and never dispatches its borrowed original branch.
            std::array<uint8_t, 8> emptyNullable{};
            Root caption(api.string_new("自由输入")), blank(api.string_new(""));
            void* args[] = {blank.Get(),
                            caption.Get(),
                            nullptr,
                            api.Get(first, "get_PrefabName"),
                            emptyNullable.data(),
                            emptyNullable.data(),
                            api.Get(first, "get_RowData")};
            int index = api.Int(list, "get_Count");
            if (api.Call(manager, "AddSelection", 7, args) && api.Int(list, "get_Count") == index + 1) {
                button = Root(Item(list, index));
                inputButtonIdentity.store(button.Get());
                node = std::move(next);
            }
        }
    }
    originalShow(manager, method);
}
void Text(void* page, void* cmd, const void* method) {
    if (installed && currentEngine.Get() == api.Get(page, "get_Engine") &&
        currentPage.Get() == api.Get(page, "get_CurrentData")) {
        if (currentSheet) {
            auto it = currentSheet->steps.find(currentLabel);
            if (it != currentSheet->steps.end()) {
                auto& step = it->second;
                route.push_back({currentSheet->scene, currentSheet->record, step.path, {}});
                Root info, empty(api.string_new(""));
                if (!step.speaker.empty()) {
                    info = NewObject("Utage", "AdvCharacterInfo");
                    Root name(api.string_new_len(step.speaker.data(), step.speaker.size()));
                    bool hide = true;
                    void* args[] = {empty.Get(), name.Get(), empty.Get(), empty.Get(), &hide, nullptr};
                    if (!api.Call(info.Get(), ".ctor", 6, args)) {
                        playbackFailure = "对白姓名初始化失败";
                        return;
                    }
                }
                void* set[] = {info.Get()};
                if (!api.Call(page, "set_CharacterInfo", 1, set)) {
                    playbackFailure = "对白姓名显示失败";
                    return;
                }
            }
        } else {
            auto key = CommandOrder(currentPage.Get(), cmd);
            RecordOriginalLine(key);
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
} // namespace

void Publish(void* scenario, const ScenarioParseResult& parsed, const QuestPtrSet& targets) {
    if (!installed || !scenario || targets.empty())
        return;
    const auto previous = FindSource(scenario);
    const auto& proof = parsed.continuation_source;
    if (previous && (!previous->nextContext.empty() || parsed.continuation_flow.empty()) &&
        (proof.original_hash.empty() || (previous->hash == proof.original_hash
        && (proof.captured_hash == proof.original_hash || previous->capturedHash == proof.captured_hash))))
        return;
    auto s = std::make_shared<Source>(parsed);
    s->weak = api.gchandle_new_weakref(scenario, false);
    if (!s->weak)
        return;
    s->scene = parsed.scene;
    s->hash = parsed.continuation_source.original_hash.empty()
        ? SourceHash(EncodeContinuationSource(parsed)) : parsed.continuation_source.original_hash;
    if (s->hash.empty())
        return;
    s->capturedHash = proof.captured_hash.empty() ? s->hash : proof.captured_hash;
    PositionMap originalGroups;
    for (const auto& pos : proof.positions) {
        (pos.choice ? originalGroups : s->originalText).emplace(pos.captured, pos.original);
    }
    RecordChoices(*s, parsed.scene_items, originalGroups);
    for (size_t i = 0; i < parsed.stage.labels.size(); ++i)
        s->labels.emplace(parsed.stage.labels[i], static_cast<int>(i));
    for (auto& state : parsed.stage.states)
        s->stageOrders.insert(state.order);
    for (auto& target : targets)
        s->pages[target.second].push_back(target.first);
    std::lock_guard<std::mutex> lock(sourcesMutex);
    for (auto it = sources.begin(); it != sources.end();) {
        if (!api.gchandle_get_target(it->second->weak))
            it = sources.erase(it);
        else
            ++it;
    }
    sources[scenario] = std::move(s);
}
bool IsTemporary(void* scenario) {
    if (!installed || !scenario)
        return false;
    std::lock_guard<std::mutex> lock(sheetsMutex);
    return temporary.count(scenario) != 0;
}
bool CaptureEnabled() { return installed && Enabled(); }
bool IsPlaying() { return currentSheet != nullptr || enteringSheet; }
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
        {0x246cdb8, reinterpret_cast<void*>(Show), reinterpret_cast<void**>(&originalShow), nullptr},
        {0x248667c, reinterpret_cast<void*>(Tap), reinterpret_cast<void**>(&originalTap), nullptr},
        {0x2466d40, reinterpret_cast<void*>(Text), reinterpret_cast<void**>(&originalText), nullptr},
        {0x2466ab4, reinterpret_cast<void*>(AddRead), reinterpret_cast<void**>(&originalRead), nullptr},
        {0x246d00c, reinterpret_cast<void*>(AddHistory), reinterpret_cast<void**>(&originalHistory), nullptr},
        {0x2424c5c, reinterpret_cast<void*>(Character), reinterpret_cast<void**>(&originalCharacter),
         nullptr},
        {0x24250a8, reinterpret_cast<void*>(CharacterOff), reinterpret_cast<void**>(&originalOff), nullptr},
        {0x2424818, reinterpret_cast<void*>(Background), reinterpret_cast<void**>(&originalBg), nullptr},
        {0x2424b80, reinterpret_cast<void*>(BackgroundOff), reinterpret_cast<void**>(&originalBgOff),
         nullptr},
        {0x241cb6c, reinterpret_cast<void*>(LoadEnd), reinterpret_cast<void**>(&originalLoadEnd), nullptr},
        {0x247dfac, reinterpret_cast<void*>(MovePage), reinterpret_cast<void**>(&originalMovePage), nullptr}};
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
void LeaveChoice() {
    if (!installed)
        return;
    bool notify = false;
    {
        std::lock_guard<std::mutex> lock(deliveryMutex);
        notify = button.Get() || !currentToken.empty();
        currentToken.clear();
        acceptedRecord.clear();
        queued.reset();
    }
    inputButtonIdentity.store(nullptr);
    button.Reset();
    node = {};
    if (notify)
        Notify("closeContinuationInput");
}
void EndSession() {
    if (!installed)
        return;
    LeaveChoice();
    route.clear();
    currentSource.reset();
    currentSheet = nullptr;
    enteringSheet = false;
    playbackFailure.clear();
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
    LeaveChoice();
    currentPage = Root(page);
    currentEngine = Root(engine);
    currentLabel = PageLabel(page);
    void* scenario = PageScenario(engine, page);
    currentSource = FindSource(scenario);
    currentSheet = SheetFor(scenario);
    enteringSheet = false;
}
void AtChoice(void* engine, void* page) {
    if (!installed || !button.Get() || engine != currentEngine.Get() || page != currentPage.Get() ||
        node.scene.empty() || node.merge.empty())
        return;
    if (node.record.empty()) node.lineageStart=route.size();
    {
        std::lock_guard<std::mutex> lock(deliveryMutex);
        if (!currentToken.empty())
            return;
        currentToken = processId + "-" + std::to_string(++serial);
    }
    std::string json = NodeJson();
    Notify("prepareContinuation", &json);
}
bool HandleSelection(void* selection) {
    if (!installed || selection != button.Get() || !selection)
        return false;
    bool valid;
    {
        std::lock_guard<std::mutex> lock(deliveryMutex);
        valid = !currentToken.empty();
    }
    if (!Enabled()) {
        ReportError("自由输入已关闭");
    } else if (!valid) {
        ReportError(node.scene.empty() ? "无法定位当前选项的原剧本" :
                    node.merge.empty() ? "这组选项没有可返回的共同剧情位置" : "当前选项没有可用的回退存档");
    } else {
        std::string json = NodeJson();
        Notify("showContinuationInput", &json);
    }
    return true;
}
void Selected(void* selection) {
    if (!installed)
        return;
    if (currentSheet) {
        auto it = currentSheet->steps.find(currentLabel);
        if (it != currentSheet->steps.end()) {
            auto option = it->second.optionPaths.find(api.Text(api.Get(selection, "get_JumpLabel")));
            if (option != it->second.optionPaths.end())
                route.push_back({currentSheet->scene, currentSheet->record, option->second, {}});
        }
    } else {
        auto key = CommandOrder(currentPage.Get(), nullptr, api.Get(selection, "get_RowData"));
        RecordOriginalLine(key);
    }
    LeaveChoice();
}
size_t RouteSize() { return route.size(); }
void RestoreRoute(size_t size) {
    if (size < route.size())
        route.resize(size);
}
std::string SceneForGeneratedPage(void* page) {
    return currentSheet && currentPage.Get() == page ? currentSheet->scene : "";
}
std::string LabelForGeneratedPage(void* page) {
    if (sheets.empty())
        return {};
    const auto label = PageLabel(page);
    for (const auto& sheet : sheets) {
        auto found = sheet->steps.find(label);
        if (found != sheet->steps.end() && found->second.choice)
            return "续写选项 " + found->second.path;
    }
    return {};
}
void ReportError(const char* message) {
    std::string text(message);
    Notify("showContinuationError", &text);
}
bool TakePlayback() {
    std::lock_guard<std::mutex> lock(deliveryMutex);
    if (!queued)
        return false;
    claimed = std::move(queued);
    return true;
}
bool TakePlaybackFailure(std::string* reason) {
    if (playbackFailure.empty())
        return false;
    *reason = std::move(playbackFailure);
    playbackFailure.clear();
    return true;
}
bool RetryAtCurrentChoice() {
    std::lock_guard<std::mutex> lock(deliveryMutex);
    if (currentToken.empty()) return false;
    acceptedRecord.clear();
    return true;
}
bool Play(void* engine, void* page) {
    auto delivery = std::move(claimed);
    if (!delivery || !button.Get() || currentPage.Get() != page || currentEngine.Get() != engine)
        return false;
    {
        std::lock_guard<std::mutex> lock(deliveryMutex);
        if (delivery->token != currentToken)
            return false;
    }
    Document steps;
    if (steps.Parse<rapidjson::kParseIterativeFlag>(delivery->steps.data(), delivery->steps.size())
            .HasParseError() ||
        !steps.IsArray())
        return false;
    Root manager(api.Get(engine, "get_DataManager")), select(api.Get(engine, "get_SelectionManager"));
    auto sheet = std::make_unique<Sheet>();
    sheet->record = delivery->record;
    sheet->scene = node.scene;
    sheet->sourceHash = node.hash;
    sheet->merge = node.merge;
    sheet->mergeWindow = node.mergeWindow;
    sheet->original = node.original;
    sheet->lineageStart = node.lineageStart;
    auto prefix = "HETContinuation_" + processId + "_" + std::to_string(++serial);
    if (!BuildSheet(manager.Get(), steps, prefix, *sheet))
        return false;
    Root entry(api.string_new(sheet->entry.c_str()));
    void* field = api.Field(button.Get(), "jumpLabel");
    if (!entry.Get() || !field) {
        UnregisterSheet(*sheet);
        return false;
    }
    api.field_set_value_object(button.Get(), field, entry.Get());
    {
        std::lock_guard<std::mutex> lock(sheetsMutex);
        temporary.insert(sheet->scenario.Get());
    }
    sheets.push_back(std::move(sheet));
    route.push_back({node.scene, delivery->record, {}, {}});
    // Same wait completion, onSelected UI cleanup and jump reservation as an
    // ordinary selection. No original branch expression is attached to this item.
    runningSelection = Root(button.Get());
    enteringSheet = true;
    bool ok = DispatchContinuationSelection(select.Get(), runningSelection.Get());
    if (!ok) enteringSheet = false;
    runningSelection.Reset();
    LeaveChoice();
    return ok;
}
std::string JniText(JNIEnv* env, jstring s) {
    if (!s)
        return {};
    jclass cls = env->GetObjectClass(s);
    auto getBytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring utf = env->NewStringUTF("UTF-8");
    auto b = static_cast<jbyteArray>(env->CallObjectMethod(s, getBytes, utf));
    std::string value;
    if (b) {
        value.resize(env->GetArrayLength(b));
        env->GetByteArrayRegion(b, 0, value.size(), reinterpret_cast<jbyte*>(value.data()));
        env->DeleteLocalRef(b);
    }
    env->DeleteLocalRef(utf);
    env->DeleteLocalRef(cls);
    return value;
}
bool Queue(std::string token, std::string record, std::string steps) {
    std::lock_guard<std::mutex> lock(deliveryMutex);
    if (token.empty() || token != currentToken || record.empty())
        return false;
    if (!acceptedRecord.empty())
        return acceptedRecord == record;
    acceptedRecord = record;
    queued = std::make_unique<Delivery>(Delivery{std::move(token), std::move(record), std::move(steps)});
    return true;
}
} // namespace het::continuation

std::string HashContinuationSource(const std::string& source_document) {
    return het::continuation::SourceHash(source_document);
}
void PublishContinuationSource(void* scenario, const ScenarioParseResult& parsed,
                               const QuestPtrSet& targets) {
    het::continuation::Publish(scenario, parsed, targets);
}
bool IsContinuationScenario(void* scenario) { return het::continuation::IsTemporary(scenario); }
bool IsContinuationInputButton(void* selection) {
    return selection && selection == het::continuation::inputButtonIdentity.load();
}
bool IsContinuationLabel(const std::string& label) {
    // Init can report the sheet's pages before its scenario is registered in the data manager.
    static const std::string prefix = "HETContinuation_" + het::continuation::processId + "_";
    return label.compare(!label.empty() && label.front() == '*' ? 1 : 0, prefix.size(), prefix) == 0;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_quarty_housamoembedtrans_MainHook_nativePlayContinuation(
    JNIEnv* env, jclass, jstring token, jstring record, jstring steps) {
    using namespace het::continuation;
    return Queue(JniText(env, token), JniText(env, record), JniText(env, steps));
}
