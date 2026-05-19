#include "housamo.hpp"
#include <sys/mman.h>
#include <map>
#include <mutex>
#include <string>

// ═══════════ 全局状态 ═══════════
uintptr_t g_il2cpp_base = 0;
il2cpp_string_new_t g_il2cpp_string_new = nullptr;

// 字段偏移 (Frida 验证的 fallback)
static size_t off_AdvPage_TextData              = 0x90;
static size_t off_AdvPage_CharacterInfo          = 0xA8;
static size_t off_TextData_ParsedText            = 0x10;
static size_t off_TextParserBase_originalText    = 0x38;
static size_t off_AdvCharacterInfo_NameText      = 0x18;

// ═══════════ Dobby 调用声明 ═══════════
namespace dobby { int DobbyHook(void* addr, void* replace, void** orig); }

typedef void (*PageTextChange_t)(void*, void*);
typedef void (*AddSelection_t)(void*, void*, void*, void*, void*, void*, void*, void*);
static PageTextChange_t orig_PageTextChange = nullptr;
static AddSelection_t   orig_AddSelection   = nullptr;

// ═══════════ 翻译缓存 ═══════════
std::map<std::string, std::string> s_cache;
std::mutex s_cache_mutex;

// ═══════════ 工具 ═══════════
static std::string il2cpp_str(Il2CppString* s) {
    if (!s || s->length <= 0 || s->length > 8192) return "";
    return std::string((char16_t*)s->chars, (char16_t*)s->chars + s->length);
}

static Il2CppString* read_il2cpp_str_ptr(void* obj, size_t off) {
    if (!obj) return nullptr;
    return *(Il2CppString**)((uintptr_t)obj + off);
}

// ═══════════ Hook 1: PageTextChange ═══════════
static void hook_PageTextChange(void* self, void* page) {
    if (!page) { 
        if (orig_PageTextChange) orig_PageTextChange(self, page); 
        return; 
    }

    void* textData = *(void**)((uintptr_t)page + off_AdvPage_TextData);
    if (!textData) { 
        if (orig_PageTextChange) orig_PageTextChange(self, page); 
        return; 
    }
    
    void* parsed = *(void**)((uintptr_t)textData + off_TextData_ParsedText);
    if (!parsed) { 
        if (orig_PageTextChange) orig_PageTextChange(self, page); 
        return; 
    }

    Il2CppString* orig = *(Il2CppString**)((uintptr_t)parsed + off_TextParserBase_originalText);
    if (!orig || orig->length == 0) { 
        if (orig_PageTextChange) orig_PageTextChange(self, page);
         return; 
    }

    std::string jp = il2cpp_str(orig);

    // 角色名
    std::string name;
    void* ci = *(void**)((uintptr_t)page + off_AdvPage_CharacterInfo);
    if (ci) {
        Il2CppString* ns = read_il2cpp_str_ptr(ci, off_AdvCharacterInfo_NameText);
        if (ns) name = il2cpp_str(ns);
    }

    // 查缓存
    std::string zh;
    { std::lock_guard<std::mutex> lk(s_cache_mutex);
      auto it = s_cache.find(jp);
      if (it != s_cache.end()) zh = it->second; }

    if (!zh.empty() && g_il2cpp_string_new) {
        *(void**)((uintptr_t)parsed + off_TextParserBase_originalText) = g_il2cpp_string_new(zh.c_str());
        LOGI("[TRANS] %s → %s", jp.c_str(), zh.c_str());
    } else {
        LOGI("[ADV] [%s] %s", name.c_str(), jp.c_str());
    }

    if (orig_PageTextChange) orig_PageTextChange(self, page);
}

// ═══════════ Hook 2: AddSelection ═══════════
static void hook_AddSelection(void* self, void* label, void* text,
                               void* exp, void* prefab, void* x, void* y, void* row) {
    Il2CppString* ts = (Il2CppString*)text;
    Il2CppString* ls = (Il2CppString*)label;
    std::string sel = ts ? il2cpp_str(ts) : (ls ? il2cpp_str(ls) : "");
    if (!sel.empty()) LOGI("[SEL] %s", sel.c_str());
    if (orig_AddSelection) orig_AddSelection(self, label, text, exp, prefab, x, y, row);
}

// ═══════════ 安装 Dobby Hook ═══════════
void il2cpp_hooks_install() {
    if (!g_il2cpp_base) { LOGE("[!] base not set"); return; }

    uintptr_t a = g_il2cpp_base + RVA_PAGE_TEXT_CHANGE;
    dobby::DobbyHook((void*)a, (void*)hook_PageTextChange, (void**)&orig_PageTextChange);
    LOGI("[+] Hook PageTextChange @ 0x%lx", a);

    a = g_il2cpp_base + RVA_ADD_SELECTION;
    dobby::DobbyHook((void*)a, (void*)hook_AddSelection, (void**)&orig_AddSelection);
    LOGI("[+] Hook AddSelection @ 0x%lx", a);
}
