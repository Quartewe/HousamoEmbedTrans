cmake_minimum_required(VERSION 3.22.1)
project("housamo_trans")

# ═══════ 源文件 ═══════
add_library(housamo_trans SHARED
    src/main/jni/init.cpp
    src/main/jni/il2cpp_hooks.cpp
    src/main/jni/translation.cpp
    src/main/jni/dobby.cpp
)

# ═══════ 头文件路径 ═══════
target_include_directories(housamo_trans PRIVATE
    src/main/jni
)

# ═══════ 链接库 ═══════
find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(housamo_trans
    ${log-lib}
    ${dl-lib}
)

# ═══════ 编译选项 ═══════
target_compile_options(housamo_trans PRIVATE
    -std=c++17
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fdata-sections
    -ffunction-sections
)

# ═══════ 去除未使用的符号 ═══════
target_link_options(housamo_trans PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)
