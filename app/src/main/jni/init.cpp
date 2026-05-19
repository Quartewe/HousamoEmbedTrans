#include "housamo.hpp"
#include <thread>
#include <chrono>
#include <jni.h>

static bool find_il2cpp_base() {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;
    char buf[256];
    while (fgets(buf, sizeof(buf), fp)) {
        if (strstr(buf, "libil2cpp.so") && strstr(buf, "r-xp")) {
            g_il2cpp_base = strtoull(buf, nullptr, 16);
            break;
        }
    }
    fclose(fp);
    return g_il2cpp_base != 0;
}

static void init_async() {
    // 轮询等待 libil2cpp.so 加载（最多 60 秒）
    for (int i = 0; i < 300; i++) {
        if (find_il2cpp_base()) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
    if (!g_il2cpp_base) { LOGE("[!] libil2cpp not loaded after 60s"); return; }
    LOGI("[init] libil2cpp base = 0x%lx", g_il2cpp_base);

    // dlsym IL2CPP API
    void *h = dlopen("libil2cpp.so", RTLD_NOLOAD);
    if (!h) { LOGE("[!] dlopen failed"); return; }
    g_il2cpp_string_new = (il2cpp_string_new_t)dlsym(h, "il2cpp_string_new");
    LOGI("[init] il2cpp_string_new = %p", (void*)g_il2cpp_string_new);

    // 安装 Dobby Hook
    il2cpp_hooks_install();

    // 启动翻译工作线程
    transl_worker_start();

    LOGI("[init] LSPosed module fully initialized");
}

void housamo_init() {
    LOGI("[init] Starting async initialization...");
    std::thread(init_async).detach();
}

// ═══════════ JNI_OnLoad — System.loadLibrary 时自动调用 ═══════════
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("[JNI] JNI_OnLoad called, launching housamo_init...");
    housamo_init();
    return JNI_VERSION_1_6;
}
