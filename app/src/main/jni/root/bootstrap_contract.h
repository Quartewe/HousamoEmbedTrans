#pragma once

// Private helper/bootstrap handoff. Configuration still comes from the Provider.
struct HetRootRequest {
    char apk_path[4096];
    char library_path[4096];
};

enum HetRootState {
    HET_ROOT_IDLE = 0,
    HET_ROOT_STARTING = 1,
    HET_ROOT_HANDED_OFF = 2,
    HET_ROOT_ALREADY = 3,
    HET_ROOT_RESTART = 4,
    HET_ROOT_FAILED = 5
};
