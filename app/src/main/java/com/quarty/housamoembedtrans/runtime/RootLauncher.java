package com.quarty.housamoembedtrans.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.BuildConfig;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Service-owned root session. One worker serializes teardown across Service instances. */
public final class RootLauncher implements AutoCloseable {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
        runnable -> new Thread(runnable, "het-root-session"));
    private static volatile RootLauncher active;
    private static volatile int statusMessageRes;

    private final Context app;
    private final Object gate = new Object();
    private volatile boolean closed;
    // gate owns requested settings and foreground retries; only WORKER owns the Process.
    private String selectedMode;
    private long requestVersion;
    private long modeVersion;
    private long modeChangedAt;
    private long retryVersion;

    public RootLauncher(Context context) {
        app = context.getApplicationContext();
        active = this;
        statusMessageRes = 0;
        WORKER.execute(this::run);
    }

    public static int getStatusMessageRes() { return statusMessageRes; }

    /** Called only after a config write commits. Never starts a Service. */
    public static void configurationChanged(String mode) {
        RootLauncher owner = active;
        if (owner == null) return;
        synchronized (owner.gate) {
            if (owner.closed) return;
            owner.selectMode(mode);
            ++owner.requestVersion;
            owner.gate.notifyAll();
        }
    }

    /** A real HET foreground entry permits one retry; other STARTs only refresh settings. */
    public void refresh(boolean foregroundEntry) {
        synchronized (gate) {
            if (closed) return;
            ++requestVersion;
            if (foregroundEntry) ++retryVersion;
            gate.notifyAll();
        }
    }

    @Override public void close() {
        synchronized (gate) {
            closed = true;
            gate.notifyAll();
        }
        if (active == this) {
            active = null;
            statusMessageRes = 0;
        }
    }

    private void selectMode(String mode) {
        if (selectedMode != null && !selectedMode.equals(mode)) {
            ++modeVersion;
            modeChangedAt = SystemClock.elapsedRealtime();
        }
        selectedMode = mode;
    }

    private void show(int resource) {
        if (!closed && active == this) statusMessageRes = resource;
    }

    private Snapshot settings() throws Exception {
        // Same lock order as config publication: config lock, then this owner's gate.
        return ConfigStore.withConfigAccess(() -> {
            String mode = new ConfigStore(app).load().config.getJSONObject("UserSettings")
                .getString("InjectionMode");
            synchronized (gate) {
                selectMode(mode);
                return new Snapshot(selectedMode, requestVersion, modeVersion,
                    retryVersion, modeChangedAt);
            }
        });
    }

    private void run() {
        long observed = 0;
        long startedMode = 0;
        long startedRetry = 0;
        long grantedMode = -1;
        boolean attempted = false;
        while (!closed) {
            try {
                synchronized (gate) {
                    while (!closed && requestVersion == observed) gate.wait();
                }
                if (closed) return;
                Snapshot next = settings();
                observed = next.request;
                if (!"root".equals(next.mode)) {
                    show(0);
                    attempted = false;
                    grantedMode = -1;
                    continue;
                }
                // Repeated service STARTs cannot turn a failed su request into a prompt loop.
                if (attempted && next.modeChange == startedMode && next.retry == startedRetry) {
                    continue;
                }
                startedMode = next.modeChange;
                startedRetry = next.retry;
                attempted = true;
                if (grantedMode != next.modeChange) {
                    int result = requestRootAuthorization(next.modeChange);
                    Snapshot afterCheck = settings();
                    if (closed || afterCheck.modeChange != next.modeChange) continue;
                    // Foreground events received during the prompt belong to this attempt.
                    next = afterCheck;
                    observed = next.request;
                    startedRetry = next.retry;
                    show(result);
                    if (result != R.string.root_granted) continue;
                    grantedMode = next.modeChange;
                }
                Snapshot consumed = runSession(next);
                observed = consumed.request;
                startedRetry = consumed.retry;
            } catch (Exception failure) {
                Log.e("HET.Root", "Root session failed", failure);
                show(R.string.root_start_failed);
                synchronized (gate) { observed = requestVersion; }
            }
        }
    }

    private int requestRootAuthorization(long modeChange) throws InterruptedException {
        show(R.string.root_requesting);
        File nullDevice = new File("/dev/null");
        Process check;
        try {
            // A fixed, read-only identity check, independent of game and helper availability.
            check = new ProcessBuilder("su", "-c", "test \"$(/system/bin/id -u)\" = 0")
                .redirectInput(nullDevice).redirectOutput(nullDevice).redirectErrorStream(true)
                .start();
        } catch (IOException error) {
            Log.w("HET.Root", "Could not start Root permission check", error);
            return R.string.root_su_unavailable;
        }
        try {
            long deadline = SystemClock.elapsedRealtime() + 60_000L;
            while (!check.waitFor(200, TimeUnit.MILLISECONDS)) {
                synchronized (gate) {
                    if (closed || modeVersion != modeChange) return 0;
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    return R.string.root_authorization_timeout;
                }
            }
            return check.exitValue() == 0 ? R.string.root_granted : R.string.root_denied;
        } finally {
            if (check.isAlive()) check.destroyForcibly();
        }
    }

    private Snapshot runSession(Snapshot current) throws Exception {
        // Read the actual ART executable: installed ARM64 libraries do not imply
        // an ARM64 host on a NativeBridge emulator. Check before executing a helper.
        int machine = executionMachine();
        if (machine != BuildConfig.ROOT_ELF_MACHINE) {
            show(machine == 62 ? R.string.root_package_x86
                : machine == 183 ? R.string.root_package_arm64 : R.string.root_unsupported);
            return current;
        }
        ApplicationInfo het = app.getApplicationInfo();
        ApplicationInfo game;
        try {
            game = app.getPackageManager().getApplicationInfo(HetBridgeContract.TARGET_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException missing) {
            show(R.string.root_game_missing);
            return current;
        }
        File helper = new File(het.nativeLibraryDir, "libhet_injector.so");
        File bootstrap = new File(het.nativeLibraryDir, "libhet_bootstrap.so");
        if (!helper.isFile() || !helper.canExecute() || !bootstrap.isFile()) {
            show(R.string.root_package_missing);
            return current;
        }
        requirePath(het.sourceDir);
        requirePath(het.nativeLibraryDir);
        String command = "exec " + quote(helper.getAbsolutePath()) + " "
            + quote(het.sourceDir) + " " + quote(het.nativeLibraryDir) + " " + game.uid;
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (IOException error) {
            Log.w("HET.Root", "Could not start Root helper", error);
            show(R.string.root_injector_failed);
            return current;
        }
        Session session = new Session();
        Thread output = new Thread(() -> readOutput(process, session), "het-root-output");
        output.start();
        boolean goSent = false;
        long helperReadyBy = SystemClock.elapsedRealtime() + 60_000L;
        try {
            while (!closed) {
                boolean changed;
                synchronized (gate) { changed = requestVersion != current.request; }
                if (changed) {
                    Snapshot next = settings();
                    if (!"root".equals(next.mode) || next.modeChange != current.modeChange) {
                        // Leave this request pending so the outer loop applies the new route.
                        return current;
                    }
                    if (goSent && next.retry != current.retry) send(process, "RETRY\n");
                    current = next;
                }
                if (!goSent && session.helperReady) {
                    synchronized (gate) {
                        if (closed) break;
                        send(process, "GO " + current.changedAt + "\n");
                        goSent = true;
                    }
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    output.join();
                    if (session.result == null) show(R.string.root_injector_failed);
                    return current;
                }
                if (session.outputEnded) {
                    show(R.string.root_injector_failed);
                    return current;
                }
                if (!goSent && SystemClock.elapsedRealtime() >= helperReadyBy) {
                    show(R.string.root_helper_timeout);
                    return current;
                }
            }
            return current;
        } finally {
            // EOF is the stop request. After GO the helper restores/detaches before exiting.
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            if (!goSent) process.destroy();
            process.waitFor();
            output.join();
        }
    }

    private static void send(Process process, String command) throws Exception {
        process.getOutputStream().write(command.getBytes(StandardCharsets.US_ASCII));
        process.getOutputStream().flush();
    }

    private void readOutput(Process process, Session session) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i("HET.Root", line);
                if ("HET_ROOT AUTHORIZED".equals(line)) {
                    session.helperReady = true;
                } else if ("HET_ROOT WAIT_TARGET".equals(line)) {
                    show(R.string.root_waiting_target);
                } else if ("HET_ROOT WAIT_RUNTIME".equals(line)) {
                    show(R.string.root_waiting_runtime);
                } else if ("HET_ROOT ATTACHING".equals(line)) {
                    show(R.string.root_attaching);
                } else if (line.startsWith("HET_ROOT RESULT ")) {
                    session.result = line.substring("HET_ROOT RESULT ".length());
                    show(resultMessage(session.result));
                }
            }
        } catch (Exception failure) {
            if (!closed) Log.e("HET.Root", "Could not read injector output", failure);
        } finally {
            session.outputEnded = true;
        }
    }

    private static int resultMessage(String result) {
        switch (result) {
            case "HANDOFF": return R.string.root_handoff;
            case "ALREADY": return R.string.root_already;
            case "RESTART": return R.string.root_restart;
            case "ROOT_DENIED": return R.string.root_helper_permission;
            case "TARGET_EXITED": return R.string.root_waiting_target;
            case "WRONG_PACKAGE_X86": return R.string.root_package_x86;
            case "WRONG_PACKAGE_ARM64": return R.string.root_package_arm64;
            case "ABI_MISMATCH": return R.string.root_unsupported;
            case "REGISTER_STATE": return R.string.root_register_state;
            case "ATTACH_FAILED": return R.string.root_attach_failed;
            case "LOAD_FAILED": return R.string.root_load_failed;
            case "NAMESPACE_FAILED": return R.string.root_namespace_failed;
            case "DETACH_FAILED": return R.string.root_detach_failed;
            case "BOOTSTRAP_FAILED": return R.string.root_bootstrap_failed;
            case "BOOTSTRAP_TIMEOUT": return R.string.root_bootstrap_timeout;
            default: return R.string.root_injector_failed;
        }
    }

    private static int executionMachine() throws IOException {
        byte[] header = new byte[20];
        try (DataInputStream input = new DataInputStream(new FileInputStream("/proc/self/exe"))) {
            input.readFully(header);
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
            || header[4] != 2 || header[5] != 1) return 0;
        return (header[18] & 0xff) | ((header[19] & 0xff) << 8);
    }

    private static final class Session {
        volatile boolean helperReady;
        volatile boolean outputEnded;
        volatile String result;
    }

    private static final class Snapshot {
        final String mode;
        final long request, modeChange, retry, changedAt;
        Snapshot(String mode, long request, long modeChange, long retry, long changedAt) {
            this.mode = mode;
            this.request = request;
            this.modeChange = modeChange;
            this.retry = retry;
            this.changedAt = changedAt;
        }
    }

    private static void requirePath(String path) {
        if (path == null || !new File(path).isAbsolute() || path.indexOf('\0') >= 0
            || path.getBytes(StandardCharsets.UTF_8).length >= 4096) {
            throw new IllegalArgumentException("Invalid installed HET resource path");
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
