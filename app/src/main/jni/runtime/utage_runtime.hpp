#pragma once
#include "housamo.hpp"
#include "shadowhook.h"
#include <cstring>
#include <utility>
#include <algorithm>
namespace het::utage {
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
    void (*field_static_get_value)(void*, void*) = nullptr;
    void* (*string_new)(const char*) = nullptr;
    uint32_t (*gchandle_new)(void*, bool) = nullptr;
    void* (*gchandle_get_target)(uint32_t) = nullptr;
    void (*gchandle_free)(uint32_t) = nullptr;
    int32_t (*string_length)(void*) = nullptr;
    const char16_t* (*string_chars)(void*) = nullptr;

    void* (*array_new)(void*, uintptr_t) = nullptr;
    void (*gc_wbarrier_set_field)(void*, void**, void*) = nullptr;
    uint32_t (*gchandle_new_weakref)(void*, bool) = nullptr;
    void* (*string_new_len)(const char*, uint32_t) = nullptr;
    bool Init() {
        handle = shadowhook_dlopen("libil2cpp.so");
        if (!handle)
            return false;
#define LOAD(name)                                                                                           \
    name = reinterpret_cast<decltype(name)>(shadowhook_dlsym(handle, "il2cpp_" #name));                      \
    if (!name)                                                                                               \
    return false
        LOAD(domain_get);
        LOAD(domain_get_assemblies);
        LOAD(assembly_get_image);
        LOAD(class_from_name);
        LOAD(class_get_parent);
        LOAD(class_get_method_from_name);
        LOAD(class_get_methods);
        LOAD(method_get_name);
        LOAD(method_get_param_count);
        LOAD(method_get_param);
        LOAD(class_from_type);
        LOAD(class_get_field_from_name);
        LOAD(object_get_class);
        LOAD(class_is_valuetype);
        LOAD(object_unbox);
        LOAD(object_new);
        LOAD(runtime_invoke);
        LOAD(field_get_value_object);
        LOAD(field_get_value);
        LOAD(field_set_value);
        LOAD(field_set_value_object);
        LOAD(gchandle_new);
        LOAD(gchandle_get_target);
        LOAD(gchandle_free);
        LOAD(field_static_get_value);
        LOAD(string_new);
        LOAD(string_length);
        LOAD(string_chars);
#undef LOAD
        // Optional exports for runtime-authored scenario rows and weak source identities.
        array_new = reinterpret_cast<decltype(array_new)>(shadowhook_dlsym(handle, "il2cpp_array_new"));
        gc_wbarrier_set_field = reinterpret_cast<decltype(gc_wbarrier_set_field)>(
            shadowhook_dlsym(handle, "il2cpp_gc_wbarrier_set_field"));
        gchandle_new_weakref = reinterpret_cast<decltype(gchandle_new_weakref)>(
            shadowhook_dlsym(handle, "il2cpp_gchandle_new_weakref"));
        string_new_len =
            reinterpret_cast<decltype(string_new_len)>(shadowhook_dlsym(handle, "il2cpp_string_new_len"));
        return true;
    }
    void* Class(const char* ns, const char* name) {
        size_t count = 0;
        const void** assemblies = domain_get_assemblies(domain_get(), &count);
        for (size_t i = 0; i < count; ++i) {
            void* found = class_from_name(assembly_get_image(assemblies[i]), ns, name);
            if (found)
                return found;
        }
        return nullptr;
    }
    const void* Method(void* klass, const char* name, int argc) {
        for (; klass; klass = class_get_parent(klass)) {
            const void* result = class_get_method_from_name(klass, name, argc);
            if (result)
                return result;
        }
        return nullptr;
    }
    const void* Overload(void* klass, const char* name, int argc, int typedIndex, void* type) {
        for (; klass; klass = class_get_parent(klass)) {
            void* iter = nullptr;
            while (const void* m = class_get_methods(klass, &iter)) {
                if (std::strcmp(method_get_name(m), name) == 0 &&
                    method_get_param_count(m) == static_cast<uint32_t>(argc) &&
                    class_from_type(method_get_param(m, typedIndex)) == type)
                    return m;
            }
        }
        return nullptr;
    }
    bool Invoke(const void* m, void* obj, void** args) {
        if (!m || !obj)
            return false;
        void* ex = nullptr;
        runtime_invoke(m, obj, args, &ex);
        return ex == nullptr;
    }
    void* Field(void* object, const char* name) {
        for (void* klass = object_get_class(object); klass; klass = class_get_parent(klass)) {
            void* field = class_get_field_from_name(klass, name);
            if (field)
                return field;
        }
        return nullptr;
    }
    bool Call(void* object, const char* name, int argc = 0, void** args = nullptr, void** out = nullptr) {
        if (!object)
            return false;
        const void* method = Method(object_get_class(object), name, argc);
        if (!method)
            return false;
        void* exception = nullptr;
        void* instance = class_is_valuetype(object_get_class(object)) ? object_unbox(object) : object;
        void* value = runtime_invoke(method, instance, args, &exception);
        if (exception) {
            LOGW("[ChoiceBack] managed operation failed: %s", name);
            return false;
        }
        if (out)
            *out = value;
        return true;
    }
    bool StopRoutine(void* owner, void* routine) {
        if (!routine)
            return true;
        void* mono = Class("UnityEngine", "MonoBehaviour");
        void* enumerator = Class("System.Collections", "IEnumerator");
        if (!owner || !mono || !enumerator)
            return false;
        void* iter = nullptr;
        while (const void* method = class_get_methods(mono, &iter)) {
            if (std::strcmp(method_get_name(method), "StopCoroutine") != 0 ||
                method_get_param_count(method) != 1 ||
                class_from_type(method_get_param(method, 0)) != enumerator)
                continue;
            void* args[] = {routine};
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
    template <typename T> bool Read(void* object, const char* name, T& result) {
        if (!object)
            return false;
        void* field = Field(object, name);
        if (!field)
            return false;
        field_get_value(object, field, &result);
        return true;
    }
    void* Static(const char* ns, const char* type, const char* name) {
        void* klass = Class(ns, type);
        const void* method = klass ? Method(klass, name, 0) : nullptr;
        if (!method)
            return nullptr;
        void* exception = nullptr;
        void* result = runtime_invoke(method, nullptr, nullptr, &exception);
        return exception ? nullptr : result;
    }
    void* StaticField(const char* ns, const char* type, const char* name) {
        void* klass = Class(ns, type);
        void* field = klass ? class_get_field_from_name(klass, name) : nullptr;
        void* value = nullptr;
        if (field)
            field_static_get_value(field, &value);
        return value;
    }
    int Int(void* object, const char* getter, int missing = -1) {
        void* value = Get(object, getter);
        return value ? *static_cast<int*>(object_unbox(value)) : missing;
    }
    std::string Text(void* value) { return value ? read_il2cpp_string(value) : std::string{}; }
    bool SameText(void* value, const std::u16string& text) {
        return value && string_length(value) == static_cast<int32_t>(text.size()) &&
               std::equal(text.begin(), text.end(), string_chars(value));
    }
};
extern Api api;

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
        if (this != &other) {
            Reset();
            handle_ = std::exchange(other.handle_, 0);
        }
        return *this;
    }
    void Reset() {
        if (handle_)
            api.gchandle_free(std::exchange(handle_, 0));
    }
    void* Get() const { return handle_ ? api.gchandle_get_target(handle_) : nullptr; }
};

inline Root NewObject(const char* ns, const char* name) {
    void* klass = api.Class(ns, name);
    return Root(klass ? api.object_new(klass) : nullptr);
}

} // namespace het::utage
