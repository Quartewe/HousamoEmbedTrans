#include "translation_pipeline_internal.hpp"
#include "scene_identity.hpp"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <system_error>

#if defined(_WIN32)
#include <windows.h>
#endif

#if defined(__ANDROID__)
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace het::translation::scene_store {
namespace {

constexpr size_t kMaxSceneBytes = 32U * 1024U * 1024U;
constexpr size_t kMaxFileNameBytes = 240U;

bool Fail(std::string* error, const std::string& message) {
    if (error) {
        *error = message;
    }
    return false;
}

bool BuildPath(
    const std::string& scene_name,
    std::filesystem::path* output,
    std::string* error) {
    if (!output) {
        return Fail(error, "scene output path is null");
    }
    if (!scene_identity::IsValid(scene_name)) {
        return Fail(error, "scene name is invalid or contains a file suffix");
    }
    if (g_runtime_config.base_dir.empty()) {
        return Fail(error, "native base directory is empty");
    }

    const std::string safe = scene_name + ".json";
    if (safe.size() > kMaxFileNameBytes) {
        return Fail(error, "scene-derived file name is too long");
    }

    *output = std::filesystem::path(g_runtime_config.base_dir)
        / "scenes"
        / safe;
    return true;
}

SceneCommitResult RenameWithoutReplacing(
    const std::filesystem::path& temporary,
    const std::filesystem::path& final_path,
    std::string* error) {
#if defined(__ANDROID__)
    if (::syscall(
            __NR_renameat2,
            AT_FDCWD,
            temporary.string().c_str(),
            AT_FDCWD,
            final_path.string().c_str(),
            RENAME_NOREPLACE) == 0) {
        return SceneCommitResult::committed;
    }
#else
    if (std::rename(
            temporary.string().c_str(),
            final_path.string().c_str()) == 0) {
        return SceneCommitResult::committed;
    }
#endif

    const int rename_error = errno;
    std::error_code filesystem_error;
    if (rename_error == EEXIST
        || std::filesystem::exists(final_path, filesystem_error)) {
        std::filesystem::remove(temporary, filesystem_error);
        return SceneCommitResult::already_exists;
    }
    Fail(
        error,
        std::string("could not atomically commit new scene file: ")
            + std::strerror(rename_error));
    return SceneCommitResult::failed;
}

}  // namespace

std::string PathForLog(const std::string& scene_name) {
    std::filesystem::path path;
    if (!BuildPath(scene_name, &path, nullptr)) {
        return {};
    }
    return path.string();
}

bool Exists(const std::string& scene_name) {
    std::filesystem::path path;
    if (!BuildPath(scene_name, &path, nullptr)) {
        return false;
    }
    std::error_code error;
    return std::filesystem::is_regular_file(path, error) && !error;
}

bool Read(
    const std::string& scene_name,
    std::string* scene_json,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (!scene_json) {
        return Fail(error, "scene output is null");
    }

    std::filesystem::path path;
    if (!BuildPath(scene_name, &path, error)) {
        return false;
    }
    std::error_code filesystem_error;
    const uintmax_t size = std::filesystem::file_size(path, filesystem_error);
    if (filesystem_error) {
        return Fail(error, "scene file is missing or unreadable");
    }
    if (size == 0 || size > kMaxSceneBytes) {
        return Fail(error, "scene file size is invalid");
    }

    std::ifstream input(path, std::ios::binary);
    if (!input.is_open()) {
        return Fail(
            error,
            std::string("could not open scene file: ") + std::strerror(errno));
    }
    std::string bytes(static_cast<size_t>(size), '\0');
    input.read(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    if (!input || input.gcount() != static_cast<std::streamsize>(bytes.size())) {
        return Fail(error, "could not read the complete scene file");
    }
    *scene_json = std::move(bytes);
    return true;
}

SceneCommitResult Commit(
    const std::string& scene_name,
    const std::string& scene_json,
    bool overwrite_existing,
    std::string* error) {
    if (error) {
        error->clear();
    }
    if (scene_json.empty() || scene_json.size() > kMaxSceneBytes) {
        Fail(error, "scene bytes are empty or exceed 32 MiB");
        return SceneCommitResult::failed;
    }

    std::filesystem::path path;
    if (!BuildPath(scene_name, &path, error)) {
        return SceneCommitResult::failed;
    }
    std::error_code filesystem_error;
    std::filesystem::create_directories(path.parent_path(), filesystem_error);
    if (filesystem_error) {
        Fail(error, "could not create scene directory");
        return SceneCommitResult::failed;
    }
    if (!overwrite_existing
        && std::filesystem::is_regular_file(path, filesystem_error)
        && !filesystem_error) {
        return SceneCommitResult::already_exists;
    }

    const std::filesystem::path temporary = path.string() + ".tmp";
    std::ofstream output(
        temporary,
        std::ios::binary | std::ios::trunc);
    if (!output.is_open()) {
        Fail(
            error,
            std::string("could not open temporary scene file: ")
                + std::strerror(errno));
        return SceneCommitResult::failed;
    }
    output.write(
        scene_json.data(),
        static_cast<std::streamsize>(scene_json.size()));
    output.flush();
    if (!output.good()) {
        Fail(error, "could not write the complete temporary scene file");
        return SceneCommitResult::failed;
    }
    output.close();
    if (!output.good()) {
        Fail(error, "could not close the temporary scene file");
        return SceneCommitResult::failed;
    }

    if (!overwrite_existing) {
        return RenameWithoutReplacing(temporary, path, error);
    }

#if defined(_WIN32)
    constexpr DWORD kReplaceFlags =
        MOVEFILE_REPLACE_EXISTING + MOVEFILE_WRITE_THROUGH;
    if (!MoveFileExW(
            temporary.c_str(),
            path.c_str(),
            kReplaceFlags)) {
        Fail(error, "could not atomically replace scene file");
        return SceneCommitResult::failed;
    }
#else
    if (std::rename(temporary.string().c_str(), path.string().c_str()) != 0) {
        Fail(
            error,
            std::string("could not atomically replace scene file: ")
                + std::strerror(errno));
        return SceneCommitResult::failed;
    }
#endif
    return SceneCommitResult::committed;
}

}  // namespace het::translation::scene_store
