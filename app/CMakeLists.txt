cmake_minimum_required(VERSION 3.22.1)
project("housamo_trans")

# ShadowHook — 通过 Prefab 查找
find_package(shadowhook REQUIRED CONFIG)

# Source files
add_library(housamo_trans SHARED
    src/main/jni/init.cpp
    src/main/jni/init_config.cpp
    src/main/jni/runtime/hook.cpp
    src/main/jni/runtime/ilcpp_reader.cpp
    src/main/jni/data/data_manager.cpp
    src/main/jni/data/text_ac.cpp
    src/main/jni/scene/page_rec.cpp
    src/main/jni/scene_production_policy.cpp
    src/main/jni/scene/scenario_catcher.cpp
    src/main/jni/scene/scene_builder.cpp
    src/main/jni/io/bridge_response_codec.cpp
    src/main/jni/io/document_codec_internal.cpp
    src/main/jni/io/native_translation_pipeline.cpp
    src/main/jni/io/request_document_codec.cpp
    src/main/jni/io/scene_document_codec.cpp
    src/main/jni/io/scene_store.cpp
    src/main/jni/io/translation_dispatcher.cpp
    src/main/jni/io/translation_result_codec.cpp
    src/main/jni/io/translation_callback_bridge.cpp
)

# Header file paths
target_include_directories(housamo_trans PRIVATE
    src/main/jni
    src/main/jni/third_party/rapidjson/include
)

# Link libraries
find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(housamo_trans
    ${log-lib}
    ${dl-lib}
    shadowhook::shadowhook
)

# Compile options
target_compile_options(housamo_trans PRIVATE
    -std=c++17
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fdata-sections
    -ffunction-sections
)

# Strip unused symbols
target_link_options(housamo_trans PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)
