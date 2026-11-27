#include "bootstrap_contract.h"
#include "KittyInjector.hpp"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <poll.h>
#include <asm/ptrace.h>
#include <sys/uio.h>
#include <string>
#include <sstream>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <cstddef>
#include <memory>
#if defined(__x86_64__)
#include <cpuid.h>
#include <sys/user.h>
#endif

namespace {
constexpr const char* kGame = "jp.co.lifewonders.liveahero";
using Clock = std::chrono::steady_clock;

int result(const char* code, int exitCode) {
    std::printf("HET_ROOT RESULT %s\n", code);
    std::fflush(stdout);
    return exitCode;
}

pid_t findGame(uid_t uid) {
    for (pid_t pid : KittyMemoryEx::getProcessIDs(kGame)) {
        struct stat info{};
        std::string proc = "/proc/" + std::to_string(pid);
        if (stat(proc.c_str(), &info) == 0 && info.st_uid == uid) return pid;
    }
    return 0;
}

struct ProcessIdentity {
    pid_t pid = 0;
    unsigned long long started = 0;
    bool operator==(const ProcessIdentity&) const = default;
};

ProcessIdentity identity(pid_t pid) {
    if (!pid) return {};
    std::ifstream file("/proc/" + std::to_string(pid) + "/stat");
    std::string stat;
    if (!std::getline(file, stat)) return {};
    auto end = stat.rfind(')');
    if (end == std::string::npos) return {};
    std::istringstream fields(stat.substr(end + 1));
    std::string value;
    for (int field = 3; field < 22; ++field) {
        if (!(fields >> value)) return {};
    }
    unsigned long long started = 0;
    if (!(fields >> started)) return {};
    return {pid, started};
}

// The HET service owns this pipe. EOF cancels waiting and permits orderly detach.
class OwnerPipe {
    std::string pending;
public:
    bool open = true;
    bool ready = false;
    unsigned long long modeChangedAt = 0;
    bool retry = false;

    bool receive(int timeoutMs) {
        if (!open) return false;
        pollfd input{STDIN_FILENO, POLLIN, 0};
        int count = poll(&input, 1, timeoutMs);
        if (count < 0) {
            if (errno != EINTR) open = false;
        } else if (count > 0) {
            char buffer[64];
            ssize_t size = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (size <= 0) {
                open = false;
            } else {
                pending.append(buffer, static_cast<size_t>(size));
                size_t newline;
                while ((newline = pending.find('\n')) != std::string::npos) {
                    auto command = pending.substr(0, newline);
                    pending.erase(0, newline + 1);
                    if (command.starts_with("GO ")) {
                        modeChangedAt = std::stoull(command.substr(3));
                        ready = true;
                    } else if (command == "RETRY") {
                        retry = true;
                    }
                }
            }
        }
        return open;
    }
};

// The one root process owns ptrace for its whole lifetime. Never use EXITKILL.
class Attachment {
public:
    KittyTraceMgr& trace;
    bool attached = false;
    bool hasRegisters = false;
    user_regs_struct registers{};
#if defined(__aarch64__)
    user_fpsimd_state floatingPoint{};
    std::vector<uint8_t> sve;
#elif defined(__x86_64__)
    std::vector<uint8_t> floatingPoint;
    int floatingPointRegset = NT_PRFPREG;
#else
#error "HET Root supports only ARM64 and x86_64 helpers"
#endif
    explicit Attachment(KittyTraceMgr& value) : trace(value) {}
    bool captureRegisters() {
        if (!trace.getRegs(&registers)) return false;
#if defined(__aarch64__)
        iovec fp{&floatingPoint, sizeof(floatingPoint)};
        if (ptrace(PTRACE_GETREGSET, trace.pid(), NT_PRFPREG, &fp) != 0
            || fp.iov_len != sizeof(floatingPoint)) return false;

        // Only normal AArch64 calls are supported. An active streaming/ZA
        // context needs the SME calling convention, so do not call into it.
        for (int type : {NT_ARM_SSVE, NT_ARM_ZA}) {
            user_sve_header header{}; // Both regset headers have this layout.
            iovec view{&header, sizeof(header)};
            if (ptrace(PTRACE_GETREGSET, trace.pid(), type, &view) == 0) {
                if (header.size > sizeof(header)) return false;
            } else if (errno != EINVAL && errno != EIO && errno != ENODEV) {
                return false;
            }
        }
        user_sve_header header{};
        iovec view{&header, sizeof(header)};
        if (ptrace(PTRACE_GETREGSET, trace.pid(), NT_ARM_SVE, &view) == 0) {
            if (header.size <= sizeof(header) || header.size > header.max_size) return false;
            sve.resize(header.size);
            view = {sve.data(), sve.size()};
            if (ptrace(PTRACE_GETREGSET, trace.pid(), NT_ARM_SVE, &view) != 0
                || view.iov_len != sve.size()) return false;
        } else if (errno != EINVAL && errno != EIO && errno != ENODEV) {
            return false;
        }
#elif defined(__x86_64__)
        // Preserve the full XSAVE image, including AVX state used by Houdini.
        // CPUID reports the maximum standard-format image size for this CPU.
        unsigned int eax, ebx, ecx, edx;
        if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx)) return false;
        size_t size = sizeof(user_fpregs_struct);
        if ((ecx & bit_XSAVE) && (ecx & bit_OSXSAVE)) {
            if (!__get_cpuid_count(0xD, 0, &eax, &ebx, &ecx, &edx)
                || ecx < size) return false;
            size = ecx;
            floatingPointRegset = NT_X86_XSTATE;
        }
        floatingPoint.resize(size);
        iovec fp{floatingPoint.data(), floatingPoint.size()};
        if (ptrace(PTRACE_GETREGSET, trace.pid(), floatingPointRegset, &fp) != 0
            || fp.iov_len < sizeof(user_fpregs_struct)) return false;
        floatingPoint.resize(fp.iov_len);
#endif
        hasRegisters = true;
        return true;
    }
    bool detach() {
        if (!attached) return true;
        bool restored = true;
        if (hasRegisters) {
#if defined(__aarch64__)
            iovec fp{&floatingPoint, sizeof(floatingPoint)};
            restored = ptrace(PTRACE_SETREGSET, trace.pid(), NT_PRFPREG, &fp) == 0;
            if (!sve.empty()) {
                iovec view{sve.data(), sve.size()};
                restored = (ptrace(PTRACE_SETREGSET, trace.pid(), NT_ARM_SVE, &view) == 0)
                    && restored;
            }
#elif defined(__x86_64__)
            iovec fp{floatingPoint.data(), floatingPoint.size()};
            restored = ptrace(PTRACE_SETREGSET, trace.pid(), floatingPointRegset, &fp) == 0;
#endif
            restored = trace.setRegs(&registers) && restored;
        }
        if (!trace.detach()) return false;
        attached = false;
        return restored;
    }
    ~Attachment() {
        if (!detach()) result("DETACH_FAILED", 1);
    }
};

#if defined(__x86_64__)
// Called with the game stopped and its complete register state saved. The bridge
// owns the namespace until process exit; only the temporary arguments are freed.
uintptr_t createBootstrapNamespace(KittyInjector& injector, const char* libraryPath) {
    auto& memory = *injector.KMgr();
    if (!memory.nbScanner.init()) {
        std::fprintf(stderr, "HET NativeBridge: cannot inspect bridge callbacks.\n");
        return 0;
    }
    const auto bridge = memory.nbScanner.nbItfData();
    if (bridge.version < KT_NB_NAMESPACE_VERSION || !bridge.createNamespace
        || !bridge.linkNamespaces || !bridge.getExportedNamespace) {
        std::fprintf(stderr, "HET NativeBridge: namespace callbacks unavailable (version=%d).\n",
                     bridge.version);
        return 0;
    }
    struct Arguments {
        char name[14] = "het-bootstrap";
        char parent[8] = "default";
        // DT_NEEDED of our bootstrap, linked against the NDK system libraries.
        char dependencies[40] = "libc.so:libdl.so:libm.so:liblog.so";
        char directory[4096]{};
    } arguments;
    std::snprintf(arguments.directory, sizeof(arguments.directory), "%s", libraryPath);
    auto& sys = injector._rsyscall;
    uintptr_t remote = sys.rmmap(0, sizeof(arguments), PROT_READ | PROT_WRITE,
                                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (!remote) {
        std::fprintf(stderr, "HET NativeBridge: cannot allocate namespace arguments.\n");
        return 0;
    }
    bool released = false;
    auto releaseArguments = [&](void* address) {
        released = sys.rmunmap(reinterpret_cast<uintptr_t>(address), sizeof(arguments));
        if (!released) std::fprintf(stderr, "HET NativeBridge: cannot release namespace arguments.\n");
    };
    std::unique_ptr<void, decltype(releaseArguments)> scratch(
        reinterpret_cast<void*>(remote), releaseArguments);
    auto prepare = [&]() -> uintptr_t {
        if (memory.writeMem(remote, &arguments, sizeof(arguments)) != sizeof(arguments)) {
            std::fprintf(stderr, "HET NativeBridge: cannot write namespace arguments.\n");
            return 0;
        }
        auto failed = [&](const char* stage, const kitty_rp_call_t& call) -> uintptr_t {
            std::fprintf(stderr, "HET NativeBridge: %s failed (call=%d).\n", stage, call.status);
            if (call.status == KT_RP_CALL_SUCCESS && bridge.getError) {
                auto error = memory.trace.callFunction(reinterpret_cast<uintptr_t>(bridge.getError));
                if (error.status == KT_RP_CALL_SUCCESS && error.result.ptr) {
                    char message[512]{};
                    memory.readMem(error.result.ptr, message, sizeof(message) - 1);
                    std::fprintf(stderr, "HET NativeBridge: %s\n", message);
                }
            }
            return 0;
        };
        auto parent = memory.trace.callFunction(
            reinterpret_cast<uintptr_t>(bridge.getExportedNamespace), remote + offsetof(Arguments, parent));
        if (parent.status != KT_RP_CALL_SUCCESS || !parent.result.ptr) {
            return failed("getExportedNamespace(default)", parent);
        }
        // AOSP ANDROID_NAMESPACE_TYPE_ISOLATED; only HET's installed directory is permitted.
        constexpr uint64_t isolated = 1;
        uintptr_t directory = remote + offsetof(Arguments, directory);
        auto created = memory.trace.callFunction(reinterpret_cast<uintptr_t>(bridge.createNamespace),
            remote + offsetof(Arguments, name), directory, directory, isolated, directory, parent.result.ptr);
        if (created.status != KT_RP_CALL_SUCCESS || !created.result.ptr) {
            return failed("createNamespace(het-bootstrap)", created);
        }
        auto linked = memory.trace.callFunction(reinterpret_cast<uintptr_t>(bridge.linkNamespaces),
            created.result.ptr, parent.result.ptr, remote + offsetof(Arguments, dependencies));
        if (linked.status != KT_RP_CALL_SUCCESS || static_cast<uint8_t>(linked.result.val) == 0) {
            return failed("linkNamespaces(system dependencies)", linked);
        }
        std::fprintf(stderr, "HET NativeBridge: namespace het-bootstrap ready at %p.\n",
                     reinterpret_cast<void*>(created.result.ptr));
        return created.result.ptr;
    };
    uintptr_t prepared = prepare();
    scratch.reset();
    return released ? prepared : 0;
}
#endif

const char* waitForBootstrap(KittyMemoryMgr& memory, uintptr_t stateAddress,
                            uintptr_t errorAddress, ProcessIdentity target, OwnerPipe& owner) {
    auto deadline = Clock::now() + std::chrono::seconds(30);
    do {
        if (!owner.receive(0)) return "CANCELED";
        if (identity(target.pid) != target) return "TARGET_EXITED";
        int state = HET_ROOT_IDLE;
        if (memory.readMem(stateAddress, &state, sizeof(state)) != sizeof(state)) {
            return "TARGET_EXITED";
        }
        switch (state) {
            case HET_ROOT_HANDED_OFF: return "HANDOFF";
            case HET_ROOT_ALREADY: return "ALREADY";
            case HET_ROOT_RESTART: return "RESTART";
            case HET_ROOT_FAILED: {
                char error[256]{};
                memory.readMem(errorAddress, error, sizeof(error) - 1);
                std::fprintf(stderr, "HET bootstrap: %s\n", error);
                return "BOOTSTRAP_FAILED";
            }
            case HET_ROOT_IDLE: return "RESTART"; // An earlier load never completed its handoff.
            default: break;
        }
        owner.receive(100);
    } while (Clock::now() < deadline);
    return "BOOTSTRAP_TIMEOUT";
}

const char* inject(const HetRootRequest& request, ProcessIdentity target, OwnerPipe& owner) {
    const pid_t pid = target.pid;
    KittyMemoryMgr memory;
    memory.trace = KittyTraceMgr(pid, 0, true, 5000);
    if (identity(pid) != target || KittyMemoryEx::getProcessName(pid) != kGame) {
        return "TARGET_EXITED";
    }
    if (!memory.initialize(pid, EK_MEM_OP_IO, true)) return "ATTACH_FAILED";
    // Architecture checks must precede architecture-specific register access.
    const auto machine = memory.elfScanner.getProgramElf().header().e_machine;
    if (machine != kInjectorEM) {
        if (machine == EM_X86_64) return "WRONG_PACKAGE_X86";
        if (machine == EM_AARCH64) return "WRONG_PACKAGE_ARM64";
        return "ABI_MISMATCH";
    }
    auto existing = memory.elfScanner.findElf("/libhet_bootstrap.so");
    if (existing.isValid()) {
        uintptr_t state = existing.findSymbol("het_root_state");
        uintptr_t error = existing.findSymbol("het_root_error");
        if (!state || !error) return "RESTART";
        // Service reconnection must not load another library or invoke JNI_OnLoad again.
        return waitForBootstrap(memory, state, error, target, owner);
    }
#if defined(__x86_64__)
    // A translated game library proves the bridge has started loading ARM64.
    // Wait without tracing so the game can finish creating its bridge namespace.
    if (!memory.elfScanner.findElf("/libil2cpp.so", EScanElfType::Emulated).isValid()) {
        return "WAIT_RUNTIME";
    }
#endif
    if (!owner.receive(0)) return "CANCELED";

    std::puts("HET_ROOT ATTACHING");
    Attachment attachment(memory.trace);
    if (!memory.trace.seize()) return "ATTACH_FAILED";
    attachment.attached = true;
    uintptr_t stateAddress = 0, errorAddress = 0;
    bool alreadyHandedOff = false;
    auto load = [&]() -> const char* {
        int stopStatus = 0;
        if (ptrace(PTRACE_INTERRUPT, pid, nullptr, nullptr) != 0
            || memory.trace.wait(&stopStatus, __WALL, 5000) != pid
            || !WIFSTOPPED(stopStatus)) return "ATTACH_FAILED";
        if (!attachment.captureRegisters()) return "REGISTER_STATE";
        if (identity(pid) != target) return "TARGET_EXITED";
        if (!owner.receive(0)) return "CANCELED";

        bool handedRequest = false;
        inject_elf_config_t config;
        config.sdk = KittyUtils::Android::getSDK();
        config.timeout = 5000;
        config.selinux_state = Utils::selinux_state();
        config.beforeEntryPoint = [&](inject_elf_info_t& library) {
            stateAddress = library.elf.findSymbol("het_root_state");
            errorAddress = library.elf.findSymbol("het_root_error");
            uintptr_t requestAddress = library.elf.findSymbol("het_root_request");
            int state = HET_ROOT_IDLE;
            if (stateAddress && errorAddress && requestAddress
                && memory.readMem(stateAddress, &state, sizeof(state)) == sizeof(state)) {
                handedRequest = state != HET_ROOT_IDLE
                    || memory.writeMem(requestAddress, const_cast<HetRootRequest*>(&request),
                                       sizeof(request)) == sizeof(request);
                alreadyHandedOff = state == HET_ROOT_HANDED_OFF || state == HET_ROOT_ALREADY;
            }
            if (!handedRequest) library.pJNI_OnLoad = 0;
        };
        KittyInjector injector;
        if (!injector.init(&memory, config)) return "LOAD_FAILED";
        if (!owner.receive(0)) return "CANCELED";
#if defined(__x86_64__)
        injector._cfg.nativeBridgeNamespace = createBootstrapNamespace(injector, request.library_path);
        if (!injector._cfg.nativeBridgeNamespace) return "NAMESPACE_FAILED";
        if (!owner.receive(0)) return "CANCELED";
#endif
        std::string bootstrap = std::string(request.library_path) + "/libhet_bootstrap.so";
        auto library = injector.inject(bootstrap);
        if (!library.is_valid() || !library.pJvm || !library.pJNI_OnLoad || !handedRequest) {
            return "LOAD_FAILED";
        }
        return nullptr;
    };
    const char* failure = load();
    if (!attachment.detach()) return "DETACH_FAILED";
    if (failure) return failure;
    if (alreadyHandedOff) return "ALREADY";
    // The main thread must be running before Java handoff can finish.
    return waitForBootstrap(memory, stateAddress, errorAddress, target, owner);
}

int watch(const HetRootRequest& request, uid_t gameUid, OwnerPipe& owner) {
    ProcessIdentity attempted{};
    bool retryable = false;
    bool waiting = false;
    bool waitingRuntime = false;
    const auto clockTicks = static_cast<unsigned long long>(sysconf(_SC_CLK_TCK));
    while (owner.receive(0)) {
        ProcessIdentity target = identity(findGame(gameUid));
        if (!target.pid) {
            if (!waiting) std::puts("HET_ROOT WAIT_TARGET");
            waiting = true;
            waitingRuntime = false;
        } else {
            waiting = false;
            if (target != attempted || (owner.retry && retryable)) {
                attempted = target;
                owner.retry = false;
                // A restart during a delayed su prompt already satisfies the route switch.
                if (target.started < owner.modeChangedAt * clockTicks / 1000) {
                    result("RESTART", 0);
                    retryable = false;
                    owner.receive(500);
                    continue;
                }
                const char* code = inject(request, target, owner);
                if (!owner.open) break;
                if (identity(target.pid) != target) code = "TARGET_EXITED";
                bool runtimePending = std::strcmp(code, "WAIT_RUNTIME") == 0;
                if (!runtimePending) result(code, 0);
                else if (!waitingRuntime) std::puts("HET_ROOT WAIT_RUNTIME");
                waitingRuntime = runtimePending;
                if (runtimePending) attempted = {}; // No load or remote call has started.
                if (std::strcmp(code, "DETACH_FAILED") == 0) return 1;
                // Uncertain/partial initialization is never retried in the same process.
                retryable = std::strcmp(code, "ATTACH_FAILED") == 0
                    || std::strcmp(code, "REGISTER_STATE") == 0
                    || std::strcmp(code, "ABI_MISMATCH") == 0;
            }
        }
        owner.retry = false;
        owner.receive(500);
    }
    return 0;
}
}

int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN); // HET may exit; the tracer must still restore and detach.
    setvbuf(stdout, nullptr, _IOLBF, 0);
    if (getuid() != 0) return result("ROOT_DENIED", 1);
    // HET owns path/UID validation; these are its private positional arguments.
    if (argc != 4) return result("ARGUMENTS", 1);
    HetRootRequest request{};
    std::snprintf(request.apk_path, sizeof(request.apk_path), "%s", argv[1]);
    std::snprintf(request.library_path, sizeof(request.library_path), "%s", argv[2]);
    std::puts("HET_ROOT AUTHORIZED");
    // A timed-out su prompt must never attach after HET has abandoned the request.
    OwnerPipe owner;
    auto deadline = Clock::now() + std::chrono::seconds(5);
    while (!owner.ready && Clock::now() < deadline && owner.receive(100)) { }
    if (!owner.open || !owner.ready) return result("ROOT_DENIED", 1);
    try {
        return watch(request, static_cast<uid_t>(std::stoul(argv[3])), owner);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "HET injector: %s\n", error.what());
        return result("INJECTOR_FAILED", 1);
    }
}
