#include "runtime/story_auto.hpp"
#include "runtime/utage_runtime.hpp"
#include <chrono>

namespace het::story_auto {
namespace {
using namespace het::utage;
using Clock = std::chrono::steady_clock;
struct Vec2 { float x, y; };
struct Color { float r, g, b, a; };
struct Control {
    const char* path;
    Root object{}, toggle{}, label{};
    Color normal{};
};
Control windows[] = {
    {"UI/MessageWindowManager/MessageWindow/RootChildren"},
    {"UI/MessageWindowManager/MessageWindow_theater/RootChildren"}
};
Root config, ui, selection;
bool requested = false;
bool bound = false;
Clock::time_point nextBind{};

void* Named(void* owner, const char* method, const char* name) {
    if (!owner) return nullptr;
    void* args[] = {api.string_new(name)};
    // GetComponent also has a System.Type overload.
    static void* stringClass = api.Class("System", "String");
    const void* fn = api.Overload(api.object_get_class(owner), method, 1, 0, stringClass);
    if (!fn) return nullptr;
    void* ex = nullptr;
    void* result = api.runtime_invoke(fn, owner, args, &ex);
    return ex ? nullptr : result;
}

void* StaticCall(void* klass, const char* name, void** args, int count, int parameter, void* type) {
    const void* method = api.Overload(klass, name, count, parameter, type);
    if (!method) return nullptr;
    void* exception = nullptr;
    void* result = api.runtime_invoke(method, nullptr, args, &exception);
    return exception ? nullptr : result;
}

void DestroyObject(void* object, bool immediate) {
    if (!object) return;
    static void* klass = api.Class("UnityEngine", "Object");
    void* args[] = {object};
    StaticCall(klass, immediate ? "DestroyImmediate" : "Destroy", args, 1, 0, klass);
}

void* CloneUnder(void* original, void* parent) {
    static const void* instantiate = []() -> const void* {
        void* object = api.Class("UnityEngine", "Object");
        void* transform = api.Class("UnityEngine", "Transform");
        if (!object || !transform) return nullptr;
        void* it = nullptr;
        while (const void* method = api.class_get_methods(object, &it)) {
            if (std::strcmp(api.method_get_name(method), "Instantiate") == 0 &&
                api.method_get_param_count(method) == 2 &&
                api.class_from_type(api.method_get_param(method, 0)) == object &&
                api.class_from_type(api.method_get_param(method, 1)) == transform)
                return method;
        }
        return nullptr;
    }();
    if (!instantiate || !original) return nullptr;
    void* args[] = {original, parent};
    void* exception = nullptr;
    void* result = api.runtime_invoke(instantiate, nullptr, args, &exception);
    return exception ? nullptr : result;
}

bool SetBool(void* owner, const char* method, bool value) {
    void* args[] = {&value};
    return api.Call(owner, method, 1, args);
}

template<class T> bool ReadValue(void* owner, const char* getter, T& out) {
    void* boxed = api.Get(owner, getter);
    if (!boxed) return false;
    out = *static_cast<T*>(api.object_unbox(boxed));
    return true;
}

void ShowState(Control& control) {
    if (!control.toggle.Get()) return;
    SetBool(control.toggle.Get(), "set_isOn", requested);
    Color color = requested ? Color{1.0f, 0.83f, 0.32f, control.normal.a} : control.normal;
    void* args[] = {&color};
    api.Call(control.label.Get(), "set_color", 1, args);
}

bool Bind(Control& control, void* engineTransform) {
    void* parent = Named(engineTransform, "Find", control.path);
    void* log = Named(parent, "Find", "BackLogButton");
    void* close = Named(parent, "Find", "CloseButton");
    Vec2 pos, size, closePos, closeSize;
    if (!ReadValue(log, "get_anchoredPosition", pos) || !ReadValue(log, "get_sizeDelta", size)
        || !ReadValue(close, "get_anchoredPosition", closePos) || !ReadValue(close, "get_sizeDelta", closeSize))
        return false;

    Root clone(CloneUnder(api.Get(log, "get_gameObject"), parent));
    if (!clone.Get()) return false;
    SetBool(clone.Get(), "SetActive", false);
    void* rect = api.Get(clone.Get(), "get_transform");
    void* label = Named(Named(rect, "Find", "Label"), "GetComponent", "Text");
    void* image = Named(clone.Get(), "GetComponent", "Image");
    void* logButton = Named(clone.Get(), "GetComponent", "Button");
    void* typeArgs[] = {api.string_new("UnityEngine.UI.Toggle, UnityEngine.UI")};
    void* toggleType = StaticCall(api.Class("System", "Type"), "GetType", typeArgs, 1, 0, api.Class("System", "String"));
    if (!label || !image || !logButton || !toggleType || !ReadValue(label, "get_color", control.normal)) {
        DestroyObject(clone.Get(), false);
        return false;
    }

    // The clone contains LOG's persistent click listener. Replace that Selectable before showing it.
    DestroyObject(Named(clone.Get(), "GetComponent", "UguiButtonSe"), true);
    DestroyObject(logButton, true);
    void* toggle = nullptr;
    void* addArgs[] = {toggleType};
    void* graphicArgs[] = {image};
    void* titleArgs[] = {api.string_new("AUTO")};
    void* nameArgs[] = {api.string_new("HetAutoButton")};
    const float gap = (closePos.x - closeSize.x / 2) - (pos.x + size.x / 2);
    pos.x -= size.x + gap;
    void* positionArgs[] = {&pos};
    if (Named(clone.Get(), "GetComponent", "Button") ||
        !api.Call(clone.Get(), "AddComponent", 1, addArgs, &toggle) || !toggle
        || !api.Call(toggle, "set_targetGraphic", 1, graphicArgs)
        || !api.Call(label, "set_text", 1, titleArgs)
        || !api.Call(rect, "set_anchoredPosition", 1, positionArgs)
        || !api.Call(clone.Get(), "set_name", 1, nameArgs)) {
        DestroyObject(clone.Get(), false);
        return false;
    }
    control.object = std::move(clone);
    control.toggle = Root(toggle);
    control.label = Root(label);
    ShowState(control);
    SetBool(control.object.Get(), "SetActive", true);
    return true;
}
} // namespace

void Update(void* engine, bool restoring) {
    if (!engine || !g_runtime_config.show_story_auto_button) return;
    if (!bound && Clock::now() >= nextBind) {
        nextBind = Clock::now() + std::chrono::seconds(1);
        if (!config.Get()) {
            config = Root(api.Get(engine, "get_Config"));
        }
        if (!ui.Get()) ui = Root(api.Get(engine, "get_UiManager"));
        if (!selection.Get()) selection = Root(api.Get(engine, "get_SelectionManager"));
        if (!config.Get() || !ui.Get() || !selection.Get()) return;
        void* transform = api.Get(engine, "get_transform");
        bound = true;
        for (auto& control : windows) {
            if (!control.object.Get() && !Bind(control, transform)) bound = false;
        }
        if (bound) LOGI("[StoryAuto] dialogue controls ready");
    }

    for (auto& control : windows) {
        if (!control.toggle.Get()) continue;
        bool on = api.Bool(control.toggle.Get(), "get_isOn", requested);
        if (on != requested) {
            requested = on;
            if (!on) SetBool(config.Get(), "set_IsAutoBrPage", false);
            if (on) {
                float speed = g_runtime_config.story_auto_speed;
                void* args[] = {&speed};
                api.Call(config.Get(), "set_AutoBrPageSpeed", 1, args);
            }
            for (auto& window : windows) ShowState(window);
            break;
        }
    }
    // Default UI status excludes the backlog and the hidden message window.
    if (!requested) return;
    bool play = !restoring && api.Int(ui.Get(), "get_Status") == 0
        && !api.Bool(selection.Get(), "get_IsShowing", true);
    if (config.Get() && play != api.Bool(config.Get(), "get_IsAutoBrPage"))
        SetBool(config.Get(), "set_IsAutoBrPage", play);
}

void EndSession() {
    if (requested && config.Get()) SetBool(config.Get(), "set_IsAutoBrPage", false);
    for (auto& control : windows) {
        if (control.object.Get()) {
            SetBool(control.object.Get(), "SetActive", false);
            DestroyObject(control.object.Get(), false);
        }
        control.object.Reset();
        control.toggle.Reset();
        control.label.Reset();
    }
    config.Reset();
    ui.Reset();
    selection.Reset();
    requested = bound = false;
    nextBind = {};
}
} // namespace het::story_auto
