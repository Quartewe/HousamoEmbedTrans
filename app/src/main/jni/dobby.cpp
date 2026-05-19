#include <cstdint>
namespace dobby {
int DobbyHook(void* address, void* replace, void** origin) {
    if (origin) *origin = address;
    return 0; // stub — 替换为完整 Dobby 后生效
}
}
