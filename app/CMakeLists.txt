cmake_minimum_required(VERSION 3.22.1)
project("housamo_trans")

# Configure Dobby sub-project
# Disable unnecessary Dobby build options and logs
set(Plugin_Platform "Android" CACHE STRING "")
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "")
add_subdirectory(src/main/jni/Dobby)

# Source files
add_library(housamo_trans SHARED
    src/main/jni/init.cpp
    src/main/jni/hook.cpp
    src/main/jni/ilcpp_reader.cpp
)

# Header file paths
target_include_directories(housamo_trans PRIVATE
    src/main/jni
    src/main/jni/Dobby/include
)

# Link libraries
find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(housamo_trans
    ${log-lib}
    ${dl-lib}
    dobby
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
