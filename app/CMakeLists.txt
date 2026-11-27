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
    src/main/jni/runtime/quest_writer.cpp
    src/main/jni/runtime/choice_back.cpp
    src/main/jni/runtime/story_auto.cpp
    src/main/jni/runtime/continuation_player.cpp
    src/main/jni/runtime/continuation_sheet.cpp
    src/main/jni/data/data_manager.cpp
    src/main/jni/data/text_ac.cpp
    src/main/jni/scene/page_rec.cpp
    src/main/jni/scene/scene_production_policy.cpp
    src/main/jni/scene/scenario_catcher.cpp
    src/main/jni/scene/scene_builder.cpp
    src/main/jni/translation/codec/bridge_response_codec.cpp
    src/main/jni/translation/codec/document_codec_internal.cpp
    src/main/jni/translation/pipeline/native_translation_pipeline.cpp
    src/main/jni/translation/codec/request_document_codec.cpp
    src/main/jni/translation/codec/scene_document_codec.cpp
    src/main/jni/translation/store/scene_store.cpp
    src/main/jni/translation/pipeline/translation_dispatcher.cpp
    src/main/jni/translation/codec/translation_result_codec.cpp
    src/main/jni/bridge/translation_callback_bridge.cpp
    src/main/jni/bridge/native_log.cpp
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

# Root has a short-lived executable injector and a separate in-game bootstrap.
add_library(het_bootstrap SHARED src/main/jni/root/bootstrap.cpp)
target_compile_options(het_bootstrap PRIVATE -std=c++17 -fno-exceptions -fno-rtti)
target_link_libraries(het_bootstrap PRIVATE log)

# The game payload stays ARM64. The emulator package builds only its standalone
# Root executable with the host x86_64 toolchain, without adding an x86 APK ABI.
set(HET_ROOT_ABI "arm64-v8a" CACHE STRING "Root executable ABI")
if(HET_ROOT_ABI STREQUAL ANDROID_ABI)
    add_subdirectory(src/main/jni/root)
    add_dependencies(het_bootstrap het_injector_package)
elseif(HET_ROOT_ABI STREQUAL "x86_64")
    include(ExternalProject)
    ExternalProject_Add(het_injector_build
        SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/root"
        BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/root-x86_64"
        CMAKE_ARGS
            "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
            "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
            "-DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}"
            "-DANDROID_ABI=x86_64"
            "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
            "-DANDROID_STL=c++_static"
            "-DHET_ROOT_PACKAGE_DIR=${HET_ROOT_PACKAGE_DIR}"
        BUILD_ALWAYS TRUE
        INSTALL_COMMAND ""
    )
    add_dependencies(het_bootstrap het_injector_build)
else()
    message(FATAL_ERROR "Unsupported Root executable ABI: ${HET_ROOT_ABI}")
endif()
