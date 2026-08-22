#include <fstream>
#include <thread>
#include <chrono>
#include <jni.h>
#include <inttypes.h>
#include "housamo.hpp"
#include "jni_bridge.hpp"

JavaBridge g_java_bridge;

static jfieldID get_field_id(JNIEnv* env, jobject obj, const char* name, const char* signature) {
    if (obj == nullptr) {
        LOGE("get_field_id failed: object is nullptr for field %s", name);
        return nullptr;
    }

    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) {
        LOGE("get_field_id failed: class is nullptr for field %s", name);
        return nullptr;
    }

    jfieldID field_id = env->GetFieldID(cls, name, signature);
    env->DeleteLocalRef(cls);

    if (field_id == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        LOGE("get_field_id failed: %s %s", name, signature);
    }

    return field_id;
}

static uintptr_t get_uintptr_field(JNIEnv* env, jobject obj, const char* name) {
    jfieldID field_id = get_field_id(env, obj, name, "J");
    if (field_id == nullptr) {
        return 0;
    }

    return static_cast<uintptr_t>(env->GetLongField(obj, field_id));
}

static size_t get_size_field(JNIEnv* env, jobject obj, const char* name) {
    return static_cast<size_t>(get_uintptr_field(env, obj, name));
}

static int get_int_field(JNIEnv* env, jobject obj, const char* name) {
    jfieldID field_id = get_field_id(env, obj, name, "I");
    if (field_id == nullptr) {
        return 0;
    }

    return env->GetIntField(obj, field_id);
}

static float get_float_field(JNIEnv* env, jobject obj, const char* name) {
    jfieldID field_id = get_field_id(env, obj, name, "F");
    if (field_id == nullptr) {
        return 0.0f;
    }

    return env->GetFloatField(obj, field_id);
}

static jobject get_object_field(JNIEnv* env, jobject obj, const char* name, const char* signature) {
    jfieldID field_id = get_field_id(env, obj, name, signature);
    if (field_id == nullptr) {
        return nullptr;
    }

    jobject child = env->GetObjectField(obj, field_id);
    if (child == nullptr) {
        LOGE("get_object_field failed: %s is nullptr", name);
    }

    return child;
}

static RvaConfig jcls_to_rvaconfig(JNIEnv* env, jobject rvaConfigObj) {
    RvaConfig out;
    out.find_scenario_data = get_uintptr_field(env, rvaConfigObj, "findScenarioData");
    out.init_base = get_uintptr_field(env, rvaConfigObj, "initBase");
    out.init_text = get_uintptr_field(env, rvaConfigObj, "initText");
    out.page_text_change = get_uintptr_field(env, rvaConfigObj, "pageTextChange");
    out.add_selection = get_uintptr_field(env, rvaConfigObj, "addSelection");
    out.show_selection = get_uintptr_field(env, rvaConfigObj, "showSelection");
    return out;
}

static CharacterWeightConfig jcls_to_character_weight_config(
    JNIEnv* env,
    jobject characterWeightObj) {
    CharacterWeightConfig out;
    out.high_relevance = get_float_field(env, characterWeightObj, "highRelevance");
    out.mid_relevance = get_float_field(env, characterWeightObj, "midRelevance");
    out.density_high = get_float_field(env, characterWeightObj, "densityHigh");
    out.text_low_score = get_float_field(env, characterWeightObj, "textLowScore");
    out.text_mentioned_score = get_float_field(env, characterWeightObj, "textMentionedScore");
    out.related_num = get_int_field(env, characterWeightObj, "relatedNum");
    out.low_term_score = get_int_field(env, characterWeightObj, "lowTermScore");
    return out;
}

static LayoutConfig jcls_to_layoutconfig(JNIEnv* env, jobject layoutObj) {
    static constexpr const char* kIl2CppStringSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$Il2CppStringLayout;";
    static constexpr const char* kIl2CppArraySig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$Il2CppArrayLayout;";
    static constexpr const char* kIl2CppListSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$Il2CppListLayout;";
    static constexpr const char* kPageDataSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$AdvScenarioPageDataLayout;";
    static constexpr const char* kScenarioLabelDataSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$ScenarioLabelDataLayout;";
    static constexpr const char* kScenarioDataSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$AdvScenarioDataLayout;";
    static constexpr const char* kIl2CppDictionarySig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$Il2CppDictionaryLayout;";
    static constexpr const char* kDictionaryEntrySig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$DictionaryEntryLayout;";
    static constexpr const char* kAdvCommandSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$AdvCommandLayout;";
    static constexpr const char* kStringGridRowSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$StringGridRowLayout;";
    static constexpr const char* kCharacterSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$AdvCommandCharacterLayout;";
    static constexpr const char* kSelectionSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$AdvCommandSelectionLayout;";
    static constexpr const char* kJumpSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$AdvCommandJumpLayout;";
    static constexpr const char* kTextColumnsSig = "Lcom/quarty/housamoembedtrans/MainHook$Layout$TextColumnsLayout;";

    LayoutConfig out;

    jobject il2cpp_string = get_object_field(env, layoutObj, "il2CppString", kIl2CppStringSig);
    out.il2cpp_string.length = get_size_field(env, il2cpp_string, "length");
    out.il2cpp_string.chars = get_size_field(env, il2cpp_string, "chars");
    if (il2cpp_string != nullptr) env->DeleteLocalRef(il2cpp_string);

    jobject il2cpp_array = get_object_field(env, layoutObj, "il2CppArray", kIl2CppArraySig);
    out.il2cpp_array.length = get_size_field(env, il2cpp_array, "length");
    out.il2cpp_array.first_element = get_size_field(env, il2cpp_array, "firstElement");
    out.il2cpp_array.pointer_size = get_int_field(env, il2cpp_array, "pointerSize");
    if (il2cpp_array != nullptr) env->DeleteLocalRef(il2cpp_array);

    jobject il2cpp_list = get_object_field(env, layoutObj, "il2CppList", kIl2CppListSig);
    out.il2cpp_list.items = get_size_field(env, il2cpp_list, "items");
    out.il2cpp_list.size = get_size_field(env, il2cpp_list, "size");
    if (il2cpp_list != nullptr) env->DeleteLocalRef(il2cpp_list);

    jobject page_data = get_object_field(env, layoutObj, "advScenarioPageData", kPageDataSig);
    out.adv_scenario_page_data.command_list = get_size_field(env, page_data, "commandList");
    out.adv_scenario_page_data.text_data_list = get_size_field(env, page_data, "textDataList");
    out.adv_scenario_page_data.scenario_label_data = get_size_field(env, page_data, "scenarioLabelData");
    out.adv_scenario_page_data.page_no = get_size_field(env, page_data, "pageNo");
    out.adv_scenario_page_data.message_window_name = get_size_field(env, page_data, "messageWindowName");
    if (page_data != nullptr) env->DeleteLocalRef(page_data);

    jobject scenario_label_data = get_object_field(env, layoutObj, "scenarioLabelData", kScenarioLabelDataSig);
    out.scenario_label_data.page_data_list = get_size_field(env, scenario_label_data, "pageDataList");
    out.scenario_label_data.scenario_label = get_size_field(env, scenario_label_data, "scenarioLabel");
    out.scenario_label_data.next = get_size_field(env, scenario_label_data, "next");
    out.scenario_label_data.command_list = get_size_field(env, scenario_label_data, "commandList");
    out.scenario_label_data.scenario_label_command = get_size_field(env, scenario_label_data, "scenarioLabelCommand");
    if (scenario_label_data != nullptr) env->DeleteLocalRef(scenario_label_data);

    jobject scenario_data = get_object_field(env, layoutObj, "advScenarioData", kScenarioDataSig);
    out.adv_scenario_data.name = get_size_field(env, scenario_data, "name");
    out.adv_scenario_data.jump_data_list = get_size_field(env, scenario_data, "jumpDataList");
    out.adv_scenario_data.scenario_labels = get_size_field(env, scenario_data, "scenarioLabels");
    if (scenario_data != nullptr) env->DeleteLocalRef(scenario_data);

    jobject il2cpp_dictionary = get_object_field(env, layoutObj, "il2CppDictionary", kIl2CppDictionarySig);
    out.il2cpp_dictionary.entries = get_size_field(env, il2cpp_dictionary, "entries");
    out.il2cpp_dictionary.count = get_size_field(env, il2cpp_dictionary, "count");
    if (il2cpp_dictionary != nullptr) env->DeleteLocalRef(il2cpp_dictionary);

    jobject dictionary_entry = get_object_field(env, layoutObj, "dictionaryEntry", kDictionaryEntrySig);
    out.dictionary_entry.hash_code = get_size_field(env, dictionary_entry, "hashCode");
    out.dictionary_entry.key = get_size_field(env, dictionary_entry, "key");
    out.dictionary_entry.value = get_size_field(env, dictionary_entry, "value");
    out.dictionary_entry.size = get_size_field(env, dictionary_entry, "size");
    if (dictionary_entry != nullptr) env->DeleteLocalRef(dictionary_entry);

    jobject adv_command = get_object_field(env, layoutObj, "advCommand", kAdvCommandSig);
    out.adv_command.row_data = get_size_field(env, adv_command, "rowData");
    out.adv_command.type = get_size_field(env, adv_command, "type");
    if (adv_command != nullptr) env->DeleteLocalRef(adv_command);

    jobject string_grid_row = get_object_field(env, layoutObj, "stringGridRow", kStringGridRowSig);
    out.string_grid_row.row_index = get_size_field(env, string_grid_row, "rowIndex");
    out.string_grid_row.strings = get_size_field(env, string_grid_row, "strings");
    if (string_grid_row != nullptr) env->DeleteLocalRef(string_grid_row);

    jobject character = get_object_field(env, layoutObj, "advCommandCharacter", kCharacterSig);
    out.adv_command_character.character_info = get_size_field(env, character, "characterInfo");
    out.adv_command_character.name_text = get_size_field(env, character, "nameText");
    if (character != nullptr) env->DeleteLocalRef(character);

    jobject selection = get_object_field(env, layoutObj, "advCommandSelection", kSelectionSig);
    out.adv_command_selection.jump_label = get_size_field(env, selection, "jumpLabel");
    if (selection != nullptr) env->DeleteLocalRef(selection);

    jobject jump = get_object_field(env, layoutObj, "advCommandJump", kJumpSig);
    out.adv_command_jump.jump_label = get_size_field(env, jump, "jumpLabel");
    out.adv_command_jump.expression_parser = get_size_field(env, jump, "expressionParser");
    out.adv_command_jump.condition_column = get_int_field(env, jump, "conditionColumn");
    if (jump != nullptr) env->DeleteLocalRef(jump);

    jobject text_columns = get_object_field(env, layoutObj, "textColumns", kTextColumnsSig);
    out.text_columns.raw = get_int_field(env, text_columns, "raw");
    out.text_columns.en = get_int_field(env, text_columns, "en");
    out.text_columns.zh_tw = get_int_field(env, text_columns, "zhTw");
    out.text_columns.zh_cn = get_int_field(env, text_columns, "zhCn");
    if (text_columns != nullptr) env->DeleteLocalRef(text_columns);

    return out;
}

static std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
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

static void InitThread(RuntimeConfig config) {
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

bool InitJniBridge(JNIEnv* env, jclass main_hook_class) {
    if (!env || !main_hook_class) {
        LOGE("InitJniBridge failed: invalid argument");
        return false;
    }

    // 防止 nativeStart 意外重复调用时泄漏 GlobalRef
    if (g_java_bridge.main_hook_class != nullptr) {
        LOGW("JNI bridge already initialized");
        return true;
    }

    auto global_class = static_cast<jclass>(
        env->NewGlobalRef(main_hook_class)
    );

    if (!global_class) {
        LOGE("Failed to create MainHook global reference");
        return false;
    }

    jmethodID request_method = env->GetStaticMethodID(
        global_class,
        "requestTranslation",
        "([B)[B"
    );

    if (!request_method) {
        LOGE("MainHook.requestTranslation(byte[]) not found");

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        env->DeleteGlobalRef(global_class);
        return false;
    }

    jmethodID store_scene_method = env->GetStaticMethodID(
        global_class,
        "storeScene",
        "([B)Z"
    );

    if (!store_scene_method) {
        LOGE("MainHook.storeScene(byte[]) not found");

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        env->DeleteGlobalRef(global_class);
        return false;
    }

    // 所有查找都成功后再发布，避免留下半初始化状态
    g_java_bridge.main_hook_class = global_class;
    g_java_bridge.request_api_method = request_method;
    g_java_bridge.store_scene_method = store_scene_method;

    LOGI("JNI bridge initialized");
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_quarty_housamoembedtrans_MainHook_nativeStart(
    JNIEnv* env,
    jclass clazz,
    jstring gameVersion,
    jobject rvaConfigObj,
    jobject layOutObj,
    jobject characterWeightObj,
    jint sceneWorkerCount,
    jboolean enablePageRecDebug,
    jboolean enableParseOnlyDebug,
    jboolean overwriteExistingJson,
    jstring targetLanguage,
    jstring chardictJson,
    jstring gametermsJson,
    jstring baseDir
) {
    RvaConfig java_rva = jcls_to_rvaconfig(env, rvaConfigObj);
    LayoutConfig java_layout = jcls_to_layoutconfig(env, layOutObj);
    CharacterWeightConfig character_weight = jcls_to_character_weight_config(env, characterWeightObj);

    std::string game_version = jstring_to_string(env, gameVersion);
    std::string target_lang = jstring_to_string(env, targetLanguage);
    std::string chardict_json = jstring_to_string(env, chardictJson);
    std::string gameterms_json = jstring_to_string(env, gametermsJson);
    std::string base_dir = jstring_to_string(env, baseDir);

    RuntimeConfig config;

    if(!InitJniBridge(env, clazz)) {
        LOGE("Failed to initialize JNI bridge");
        return;
    }

    if (!make_runtime_config(
            game_version,
            java_rva,
            java_layout,
            character_weight,
            static_cast<int>(sceneWorkerCount),
            target_lang,
            enablePageRecDebug == JNI_TRUE,
            enableParseOnlyDebug == JNI_TRUE,
            overwriteExistingJson == JNI_TRUE,
            base_dir,
            &config)) {
        return;
    }

    g_runtime_config = config;

    LOGI("Received RVA config from Java: FindScenarioData=0x%" PRIxPTR
         ", InitBase=0x%" PRIxPTR ", InitText=0x%" PRIxPTR
         ", SceneWorkerCount=%d, PageRecDebug=%d, ParseOnlyDebug=%d, OverwriteExistingJson=%d",
         config.rva.find_scenario_data,
         config.rva.init_base,
         config.rva.init_text,
         config.scene_worker_count,
         config.enable_page_rec_debug ? 1 : 0,
         config.parse_only_debug ? 1 : 0,
         config.overwrite_existing ? 1 : 0);
    LOGI("Starting initialization thread...");

    StartJsonManager(std::move(chardict_json), std::move(gameterms_json));
    std::thread(InitThread, config).detach();// 启动一个新的线程来执行初始化逻辑，避免阻塞JNI_OnLoad函数
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) { // JNI_OnLoad函数是JNI库被加载时调用的函数，返回JNI版本号
    g_java_bridge.jvm = vm;
    LOGI("JNI_OnLoad called");
    // std::thread(InitThread).detach(); (已移交给Java层调用nativeStart函数来启动线程)
    return JNI_VERSION_1_6;
}
