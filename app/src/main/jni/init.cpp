#include <fstream>
#include <string>
#include <thread>
#include <chrono>
#include <jni.h>
#include <inttypes.h>
#include "housamo.hpp"

static RvaConfig jcls_to_rvaconfig(JNIEnv* env, jobject rvaConfigObj) {
    // 反射获取RVA配置
    RvaConfig out;
    jclass cls = env->GetObjectClass(rvaConfigObj);

    jfieldID fidInitBase = env->GetFieldID(cls, "initBase", "J");
    out.init_base = static_cast<long>(env->GetLongField(rvaConfigObj, fidInitBase));
    jfieldID fidInitText = env->GetFieldID(cls, "initText", "J");
    out.init_text = static_cast<long>(env->GetLongField(rvaConfigObj, fidInitText));
    jfieldID fidPageTextChange = env->GetFieldID(cls, "pageTextChange", "J");
    out.page_text_change = static_cast<long>(env->GetLongField(rvaConfigObj, fidPageTextChange));
    jfieldID fidAddSelection = env->GetFieldID(cls, "addSelection", "J");
    out.add_selection = static_cast<long>(env->GetLongField(rvaConfigObj, fidAddSelection));
    jfieldID fidShowSelection = env->GetFieldID(cls, "showSelection", "J");
    out.show_selection = static_cast<long>(env->GetLongField(rvaConfigObj, fidShowSelection));

    return out;
}

// 捕获il2cpp基址的函数
static uintptr_t CatchIl2CppBase() {
    LOGI("Starting to search for il2cpp base address...");
    for (int i = 0; i < 30; ++i) {
        std::ifstream f("/proc/self/maps");
        std::string line;
        if (!f.is_open()) {
            LOGW("Failed to open /proc/self/maps");
            std::this_thread::sleep_for(std::chrono::seconds(1));
            continue;
        }
        LOGI("-------- ATTEMPT %d: Searching for libil2cpp.so... --------", i + 1);
        LOGI("Checking /proc/self/maps...");
        while (std::getline(f, line)) {
            if (line.find("libil2cpp.so") != std::string::npos && // 确保是libil2cpp.so的行
                line.find("00000000") != std::string::npos // 确保基址是0x00000000，避免误捕获其他库的地址
                ) {
                uintptr_t addr = static_cast<uintptr_t>(std::stoull(line.substr(0, line.find('-')), nullptr, 16)); 
                // 内存地址范围              权限   文件偏移    设备   inode   文件路径
                // 7a12345000-7a23456000    r-xp   00000000   ...          libil2cpp.so
                LOGI("libil2cpp base = 0x%" PRIxPTR, addr);
                return addr;
            }
        }
        LOGW("libil2cpp.so not found in ATTEMPT %d", i + 1);
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOGE("Failed to find il2cpp base address");
    return 0;
}

static void InitThread(RvaConfig config) {
    LOGI("Initialization thread started");

    uintptr_t il2cpp_base = CatchIl2CppBase();
    if (il2cpp_base == 0) {
        LOGE("Initialization failed: Could not find il2cpp base address");
        return;
    }

    if (!install_hook(il2cpp_base, config)) {
        LOGE("Initialization failed: Could not install hook");
        return;
    }
    LOGI("Initialization completed successfully");
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_MainHook_nativeStart(
    JNIEnv* env,
    jclass clazz,
    jobject rvaConfigObj
) {
    RvaConfig javaconfig = jcls_to_rvaconfig(env, rvaConfigObj);
    RvaConfig config;

    if (!make_rva_config(javaconfig, &config)) {
        return;
    }

    LOGI("Received RVA config from Java: InitBase=0x%" PRIxPTR ", InitText=0x%" PRIxPTR, config.init_base, config.init_text);
    LOGI("Starting initialization thread...");
    std::thread(InitThread, config).detach();// 启动一个新的线程来执行初始化逻辑，避免阻塞JNI_OnLoad函数
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) { // JNI_OnLoad函数是JNI库被加载时调用的函数，返回JNI版本号
    LOGI("JNI_OnLoad called");
    // std::thread(InitThread).detach(); (已移交给Java层调用nativeStart函数来启动线程)
    return JNI_VERSION_1_6;
}

