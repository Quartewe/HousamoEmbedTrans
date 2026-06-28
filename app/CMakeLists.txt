cmake_minimum_required(VERSION 3.22.1)
project("housamo_trans")

# ShadowHook — 通过 Prefab 查找
find_package(shadowhook REQUIRED CONFIG)

# Source files
add_library(housamo_trans SHARED
    src/main/jni/init.cpp
    src/main/jni/hook.cpp
    src/main/jni/ilcpp_reader.cpp
    src/main/jni/init_config.cpp
    src/main/jni/page_rec.cpp
    src/main/jni/text_ac.cpp
    src/main/jni/scene_builder.cpp
    src/main/jni/scenario_catcher.cpp
    src/main/jni/character_dict.cpp
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
