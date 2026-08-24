#pragma once

#include <string>

namespace het::translation::scene_identity {

// SceneName is the single native identity: a bare, path-safe name.  The
// SceneStore boundary alone appends `.json` when deriving a formal filename.
inline bool IsValid(const std::string& scene_name) {
    if (scene_name.empty() || scene_name.size() > 235U) {
        return false;
    }
    for (unsigned char value : scene_name) {
        const bool allowed =
            (value >= 'a' && value <= 'z')
            || (value >= 'A' && value <= 'Z')
            || (value >= '0' && value <= '9')
            || value == '_'
            || value == '-';
        if (!allowed) {
            return false;
        }
    }
    return true;
}

}  // namespace het::translation::scene_identity
