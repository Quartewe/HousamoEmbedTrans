cmake_minimum_required(VERSION 3.22.1)
project(het_root_injector LANGUAGES C CXX)

include(FetchContent)
find_package(Git REQUIRED)

# Resolve main at configure time, then cache its source archive under the build
# directory. Archives avoid nested Git repositories and upstream build targets.
foreach(dependency IN ITEMS AndKittyInjector KittyMemoryEx)
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" ls-remote --exit-code
            "https://github.com/MJx0/${dependency}.git" refs/heads/main
        RESULT_VARIABLE dependency_status
        OUTPUT_VARIABLE dependency_ref
        ERROR_VARIABLE dependency_error
        OUTPUT_STRIP_TRAILING_WHITESPACE
        TIMEOUT 30
    )
    if(NOT dependency_status STREQUAL "0"
       OR NOT dependency_ref MATCHES "^[0-9a-f]+[ \t]+refs/heads/main$")
        message(FATAL_ERROR "Could not resolve ${dependency}/main: ${dependency_error}")
    endif()
    string(REGEX MATCH "^[0-9a-f]+" dependency_revision "${dependency_ref}")
    string(TOLOWER "${dependency}" content_name)
    message(STATUS "${dependency}/main: ${dependency_revision}")
    FetchContent_Declare(${content_name}
        URL "https://codeload.github.com/MJx0/${dependency}/tar.gz/${dependency_revision}"
        TLS_VERIFY TRUE
    )
    FetchContent_GetProperties(${content_name})
    if(NOT ${content_name}_POPULATED)
        FetchContent_Populate(${content_name})
    endif()
endforeach()

# Keep the upstream source in the build cache; only this API extension is tracked.
# Stop Git discovery at the cache so apply never uses the enclosing HET repository.
set(namespace_patch "${CMAKE_CURRENT_LIST_DIR}/patches/andkitty-nativebridge-namespace.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${namespace_patch}")
set(patch_git ${CMAKE_COMMAND} -E env "GIT_CEILING_DIRECTORIES=${andkittyinjector_SOURCE_DIR}"
    "${GIT_EXECUTABLE}")
execute_process(COMMAND ${patch_git} apply --reverse --check "${namespace_patch}"
    WORKING_DIRECTORY "${andkittyinjector_SOURCE_DIR}"
    RESULT_VARIABLE patch_present OUTPUT_QUIET ERROR_QUIET)
if(NOT patch_present STREQUAL "0")
    execute_process(COMMAND ${patch_git} apply --check "${namespace_patch}"
        WORKING_DIRECTORY "${andkittyinjector_SOURCE_DIR}"
        RESULT_VARIABLE patch_check ERROR_VARIABLE patch_error)
    if(NOT patch_check STREQUAL "0")
        message(FATAL_ERROR "AndKittyInjector/main needs an updated namespace patch: ${patch_error}")
    endif()
    execute_process(COMMAND ${patch_git} apply "${namespace_patch}"
        WORKING_DIRECTORY "${andkittyinjector_SOURCE_DIR}"
        RESULT_VARIABLE patch_status ERROR_VARIABLE patch_error)
    if(NOT patch_status STREQUAL "0")
        message(FATAL_ERROR "Could not apply AndKittyInjector namespace patch: ${patch_error}")
    endif()
endif()

set(ANDKITTY_SRC_DIR "${andkittyinjector_SOURCE_DIR}/AndKittyInjector/src")
set(KITTY_MEMORY_DIR "${kittymemoryex_SOURCE_DIR}/KittyMemoryEx")
file(GLOB KITTY_MEMORY_SOURCES CONFIGURE_DEPENDS "${KITTY_MEMORY_DIR}/*.cpp")
add_executable(het_injector
    injector.cpp
    ${ANDKITTY_SRC_DIR}/Injector/KittyInjector.cpp
    ${ANDKITTY_SRC_DIR}/Utils/Utils.cpp
    ${ANDKITTY_SRC_DIR}/Utils/BinderShell.cpp
    ${KITTY_MEMORY_SOURCES}
)
set_target_properties(het_injector PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_include_directories(het_injector PRIVATE
    ${ANDKITTY_SRC_DIR}/Injector ${KITTY_MEMORY_DIR})
target_compile_definitions(het_injector PRIVATE kNO_KEYSTONE)
target_compile_options(het_injector PRIVATE -fexceptions -frtti -ffunction-sections -fdata-sections)
target_link_options(het_injector PRIVATE -Wl,--gc-sections)
target_link_libraries(het_injector PRIVATE log dl)

# This PIE executable is carried in the ARM64 APK library directory so Android
# extracts it to an executable installation path. Only su executes it; ART and
# the game still select the ARM64 business libraries in both release variants.
add_custom_target(het_injector_package ALL
    COMMAND ${CMAKE_COMMAND} -E make_directory "${HET_ROOT_PACKAGE_DIR}/arm64-v8a"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:het_injector>"
        "${HET_ROOT_PACKAGE_DIR}/arm64-v8a/libhet_injector.so"
    DEPENDS het_injector
    VERBATIM
)
