package com.quarty.housamoembedtrans;

import com.bytedance.shadowhook.ShadowHook;
import com.quarty.housamoembedtrans.bridge.HetBridgeContract;
import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.bridge.TranslationServiceClient;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.scene.sync.GameSceneMirrorSource;
import com.quarty.housamoembedtrans.scene.sync.SceneMirrorExportCoordinator;
import com.quarty.housamoembedtrans.scene.sync.SceneSyncStartupSnapshot;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.translation.IGameScenePort;
import com.quarty.housamoembedtrans.util.IoUtils;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.IXposedHookZygoteInit;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.net.Uri;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import org.json.JSONObject;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LSPosed 模块入口 — Housamo AI 实时翻译。
 *
 * 工作流程:
 *   1. LSPosed 在目标应用加载时回调 handleLoadPackage
 *   2. 确认是 Housamo (jp.co.lifewonders.housamo)
 *   3. 初始化 ShadowHook → System.loadLibrary("housamo_trans") → 触发 JNI_OnLoad
 *   4. JNI_OnLoad 中定位 libil2cpp.so → ShadowHook → 翻译管线就绪
 */

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String RUNTIME_FILE_NAME = "runtime.json";
    private static final String CHARDICT_FILE_NAME = "chardict.json";
    private static final String GAMETERMS_FILE_NAME = "gameterms.json";
    private static final String TERM_ASSET_DIRECTORY = "term/";
    private static final long SERVICE_CONNECT_TIMEOUT_MS = 5_000L;
    private static final int MAX_TRANSLATION_REQUEST_BYTES = 32 * 1024 * 1024;
    private static String sModulePath = null;
    private static boolean s_loaded = false;
    private static boolean s_attach_hook_installed = false;
    private static boolean s_initializing = false;
    private static volatile TranslationServiceClient sTranslationClient;
    private static volatile GameScenePort sGameScenePort;
    private static volatile Context sTargetContext;
    private static volatile boolean sOverwriteExistingJson;
    private static volatile boolean sMissingQuestPatchNativeLogged;
    private static volatile boolean sMissingSceneResultNativeLogged;
    private static volatile boolean sMissingFailureNativeLogged;
    private static final TranslationServiceClient.ResultSink
        TRANSLATION_RESULT_SINK =
            new TranslationServiceClient.ResultSink() {
                @Override
                public void onQuestPatch(
                    String requestId,
                    byte[] patchJson
                ) {
                    handleQuestPatch(requestId, patchJson);
                }

                @Override
                public boolean onSceneCompleted(
                    String requestId,
                    String scene,
                    String targetLanguage,
                    byte[] resultJson,
                    String leaseToken,
                    long connectionGeneration
                ) {
                    return handleSceneResult(
                        requestId,
                        scene,
                        targetLanguage,
                        resultJson,
                        leaseToken,
                        connectionGeneration
                    );
                }

                @Override
                public boolean onTranslationFailed(
                    String requestId,
                    String errorType,
                    String message,
                    String leaseToken,
                    long connectionGeneration
                ) {
                    return handleTranslationFailure(
                        requestId,
                        errorType,
                        message,
                        leaseToken,
                        connectionGeneration
                    );
                }
            };

    /**
     * Game-process implementation of the one registered Scene port.  The
     * adapter is created only after nativeStart and the injected game mirror
     * store are ready, so a fast Binder registration cannot hit an unloaded
     * native policy entry point.
     */
    private static final class GameScenePort extends IGameScenePort.Stub {
        private final SceneStore sceneStore;
        private final SceneMirrorExportCoordinator exportCoordinator;
        private final AtomicReference<
            SceneMirrorExportCoordinator.ExportSession
        > currentExport = new AtomicReference<>();
        private final AtomicReference<ApplyActivity> currentActivity =
            new AtomicReference<>();
        private final Object policyHoldLock = new Object();
        /** Registration generation currently admitted by TranslationService. */
        private long activeSceneSyncGeneration;
        /** One export Binder call between generation admission and hold setup. */
        private long pendingExportGeneration;
        /** Generation that most recently acquired the native sync hold. */
        private long activeHoldGeneration;
        /**
         * Activity that acquired the currently tracked hold, or {@code null}
         * for the generation/full-sync lease established before an apply
         * activity exists.  A one-shot failure may reset only its own marker.
         */
        private ApplyActivity activeHoldOwner;
        /** Generation owning the currently published export session. */
        private long activeExportGeneration;
        private final int sceneWorkerCount;
        private GameScenePort(
            Context context,
            File sceneDirectory,
            int sceneWorkerCount
        )
            throws Exception {
            if (sceneWorkerCount < 1 || sceneWorkerCount > 4) {
                throw new IllegalArgumentException(
                    "sceneWorkerCount must be between 1 and 4"
                );
            }
            this.sceneWorkerCount = sceneWorkerCount;
            JSONObject schema = new JSONObject(
                readModuleAsset(SceneStore.SCHEMA_ASSET_PATH)
            );
            SceneStore store = new SceneStore(context, sceneDirectory, schema);
            this.sceneStore = store;
            this.exportCoordinator = new SceneMirrorExportCoordinator(
                new SceneMirrorExportCoordinator.ProductionGate() {
                    @Override
                    public boolean beginHold() {
                        return beginNativeHoldForPendingExport();
                    }

                    @Override
                    public void waitForActiveZero() {
                        try {
                            nativeWaitForSceneProductionIdle();
                        } catch (UnsatisfiedLinkError e) {
                            // Surface linkage failure as an ordinary export
                            // error so SceneMirrorExportCoordinator runs its
                            // abort/finally path and the HET-side lease can
                            // fail open instead of leaving inFlight stuck.
                            throw new IllegalStateException(
                                "Native Scene production wait is unavailable",
                                e
                            );
                        }
                    }
                },
                new GameSceneMirrorSource(store)
            );
        }

        @Override
        public boolean activateSceneSyncGeneration(long generation) {
            if (generation <= 0L) {
                return false;
            }
            ApplyActivity activityToAbort = null;
            SceneMirrorExportCoordinator.ExportSession exportToAbort = null;
            synchronized (policyHoldLock) {
                // The service owns an opaque per-registration token.  It is
                // deliberately not compared numerically: a new service
                // instance may start its sequence again after a process
                // restart, and a stale Binder must never be admitted merely
                // because its token happens to be larger or smaller.
                if (generation == activeSceneSyncGeneration) {
                    return false;
                }
                boolean held;
                try {
                    // A newly activated port must never expose the native
                    // fail-open default before its first complete policy
                    // replacement. Preserve any previous blocked set and add
                    // a global hold; the first valid replacement atomically
                    // publishes the new generation's complete set.
                    held = nativeBeginSceneSyncHold();
                } catch (UnsatisfiedLinkError e) {
                    XposedBridge.log(
                        "[HousamoTrans] Scene policy native hold "
                            + "is unavailable during generation activation"
                    );
                    return false;
                }
                if (!held) {
                    return false;
                }
                activeSceneSyncGeneration = generation;
                pendingExportGeneration = 0L;
                activeHoldGeneration = generation;
                activeHoldOwner = null;
                if (activeExportGeneration != 0L
                    && activeExportGeneration != generation) {
                    exportToAbort = currentExport.getAndSet(null);
                    activeExportGeneration = 0L;
                }
                ApplyActivity current = currentActivity.get();
                if (current != null && current.generation != generation
                    && currentActivity.compareAndSet(current, null)) {
                    activityToAbort = current;
                }
            }
            if (exportToAbort != null) {
                exportToAbort.abort();
            }
            if (activityToAbort != null) {
                activityToAbort.finish();
            }
            return true;
        }

        @Override
        public void deactivateSceneSyncGeneration(long generation) {
            if (generation <= 0L) {
                return;
            }
            ApplyActivity activityToAbort = null;
            SceneMirrorExportCoordinator.ExportSession exportToAbort = null;
            synchronized (policyHoldLock) {
                if (activeSceneSyncGeneration != generation) {
                    return;
                }
                activeSceneSyncGeneration = 0L;
                pendingExportGeneration = 0L;
                activeHoldGeneration = 0L;
                activeHoldOwner = null;
                // Deactivation is also the fail-open boundary for a cycle
                // whose hold was already consumed.  Reset the complete native
                // policy while the generation token is still exclusively
                // owned, so a newer generation cannot be cleared by a late
                // unregister callback.
                resetNativeScenePolicyUnconditionally(
                    "generation deactivation"
                );

                if (activeExportGeneration == generation) {
                    exportToAbort = currentExport.getAndSet(null);
                    activeExportGeneration = 0L;
                }

                ApplyActivity current = currentActivity.get();
                if (current != null && current.generation == generation
                    && currentActivity.compareAndSet(current, null)) {
                    activityToAbort = current;
                }
            }
            if (exportToAbort != null) {
                exportToAbort.abort();
            }
            if (activityToAbort != null) {
                activityToAbort.finish();
            }
        }

        private boolean reserveExportActivity(
            ApplyActivity activity,
            long generation
        ) {
            synchronized (policyHoldLock) {
                if (generation <= 0L
                    || activeSceneSyncGeneration != generation
                    || pendingExportGeneration != 0L
                    || activeHoldOwner != null) {
                    // A flushed one-shot REPLACE result remains owned by its
                    // activity until the HET caller sends the generation-aware
                    // complete ACK.  Do not let a same-generation export
                    // acquire a second owner before that ACK arrives.
                    return false;
                }
                if (!currentActivity.compareAndSet(null, activity)) {
                    return false;
                }
                pendingExportGeneration = generation;
                return true;
            }
        }

        private void clearPendingExportGeneration(long generation) {
            synchronized (policyHoldLock) {
                if (pendingExportGeneration == generation) {
                    pendingExportGeneration = 0L;
                }
            }
        }

        private boolean beginNativeHoldForPendingExport() {
            synchronized (policyHoldLock) {
                if (pendingExportGeneration == 0L
                    || pendingExportGeneration != activeSceneSyncGeneration) {
                    return false;
                }
                boolean held;
                try {
                    held = nativeBeginSceneSyncHold();
                } catch (UnsatisfiedLinkError e) {
                    XposedBridge.log(
                        "[HousamoTrans] Scene policy native hold "
                            + "is unavailable"
                    );
                    return false;
                }
                if (held) {
                    // The native hold becomes owned by this generation at
                    // the exact moment nativeBegin succeeds.  Deactivation
                    // can therefore never miss the hold while acceptExport
                    // is still wiring the session.
                    activeHoldGeneration = pendingExportGeneration;
                    activeHoldOwner = null;
                }
                return held;
            }
        }

        private void resetActiveHoldForGeneration(
            long generation,
            String reason
        ) {
            synchronized (policyHoldLock) {
                if (activeHoldGeneration != generation) {
                    return;
                }
                activeHoldGeneration = 0L;
                activeHoldOwner = null;
                resetNativeScenePolicyUnconditionally(reason);
            }
        }

        /**
         * Fails open only the hold owned by one one-shot apply activity.
         *
         * <p>A one-shot policy command is admitted when no full export
         * activity is active, but its Binder task can still race generation
         * deactivation or a newer registration.  Both the activity identity
         * and generation therefore have to match before Java bookkeeping or
         * the native policy is reset.  This keeps a late failure from
         * clearing a full-sync/new-generation hold.  A task that was rejected
         * by Native clears its owner marker before reporting the failure, so
         * the guard also prevents a later cancellation from resetting an
         * unrelated generation hold.</p>
         */
        private void resetOneShotHoldForActivity(
            ApplyActivity activity,
            String reason
        ) {
            if (activity == null || activity.fullSync) {
                return;
            }
            synchronized (policyHoldLock) {
                if (activeSceneSyncGeneration != activity.generation
                    || (currentActivity.get() != activity
                        && activeHoldOwner != activity)) {
                    return;
                }
                activeHoldGeneration = 0L;
                activeHoldOwner = null;
                resetNativeScenePolicyUnconditionally(reason);
            }
        }

        /**
         * Fail-open cleanup for a lost TranslationService connection.  The
         * callback has no generation token, so it first invalidates the
         * current token under the same lock used by activation/beginHold;
         * this prevents a concurrent export from being reset after a newer
         * registration has already taken ownership.
         */
        private void resetSceneProductionPolicyForConnectionLoss() {
            SceneMirrorExportCoordinator.ExportSession export;
            ApplyActivity activity;
            synchronized (policyHoldLock) {
                activeSceneSyncGeneration = 0L;
                pendingExportGeneration = 0L;
                activeHoldGeneration = 0L;
                activeHoldOwner = null;
                activeExportGeneration = 0L;
                export = currentExport.getAndSet(null);
                activity = currentActivity.getAndSet(null);
                resetNativeScenePolicyUnconditionally(
                    "TranslationService connection loss"
                );
            }
            if (export != null) {
                export.abort();
            }
            if (activity != null) {
                activity.finish();
            }
        }

        private void resetNativeScenePolicyUnconditionally(String reason) {
            try {
                nativeResetSceneProductionPolicy();
            } catch (UnsatisfiedLinkError e) {
                XposedBridge.log(
                    "[HousamoTrans] Native Scene policy reset unavailable "
                        + "during " + reason
                );
            }
        }

        @Override
        public ParcelFileDescriptor exportSceneSnapshot(long generation) {
            ApplyActivity activity = new ApplyActivity(
                sceneWorkerCount,
                true,
                generation
            );
            if (!reserveExportActivity(activity, generation)) {
                activity.finish();
                return null;
            }
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createPipe();
            } catch (IOException e) {
                clearPendingExportGeneration(generation);
                resetActiveHoldForGeneration(
                    generation,
                    "Scene export pipe creation failed"
                );
                currentActivity.compareAndSet(activity, null);
                activity.finish();
                XposedBridge.log(
                    "[HousamoTrans] Could not create Scene export pipe: "
                        + e.getMessage()
                );
                return null;
            }

            ParcelFileDescriptor readEnd = pipe[0];
            ParcelFileDescriptor writeEnd = pipe[1];
            ParcelFileDescriptor.AutoCloseOutputStream output =
                new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
            ExecutorService writerPool = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(
                        runnable,
                        "HET-game-scene-export"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
            );
            Executor writerExecutor = command -> {
                try {
                    writerPool.execute(() -> {
                        try {
                            command.run();
                        } finally {
                            writerPool.shutdown();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    writerPool.shutdownNow();
                    throw e;
                }
            };
            SceneMirrorExportCoordinator.ExportSession session;
            try {
                session = exportCoordinator.acceptExport(
                    writerExecutor,
                    output
                );
            } catch (RuntimeException e) {
                clearPendingExportGeneration(generation);
                resetActiveHoldForGeneration(
                    generation,
                    "Scene export acceptance failed"
                );
                writerPool.shutdownNow();
                currentActivity.compareAndSet(activity, null);
                activity.finish();
                closeQuietly(readEnd);
                closeQuietly(writeEnd);
                XposedBridge.log(
                    "[HousamoTrans] Could not accept Scene export: "
                        + e.getClass().getSimpleName()
                );
                return null;
            }
            if (session == null) {
                clearPendingExportGeneration(generation);
                resetActiveHoldForGeneration(
                    generation,
                    "Scene export session unavailable"
                );
                // acceptExport has already cancelled the queued session.  Let
                // its wrapper run the session finally block so the coordinator
                // remains the sole owner of the inFlight release.
                writerPool.shutdown();
                currentActivity.compareAndSet(activity, null);
                activity.finish();
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                try {
                    readEnd.close();
                } catch (IOException ignored) {
                }
                return null;
            }
            final SceneMirrorExportCoordinator.ExportSession acceptedSession =
                session;
            boolean generationStillCurrent;
            synchronized (policyHoldLock) {
                generationStillCurrent =
                    activeSceneSyncGeneration == generation
                        && pendingExportGeneration == generation;
                if (pendingExportGeneration == generation) {
                    pendingExportGeneration = 0L;
                }
                if (generationStillCurrent) {
                    activeHoldGeneration = generation;
                    activeHoldOwner = activity;
                    activeExportGeneration = generation;
                    currentExport.set(acceptedSession);
                    acceptedSession.setCompletionListener(
                        () -> {
                            synchronized (policyHoldLock) {
                                if (currentExport.compareAndSet(
                                    acceptedSession,
                                    null
                                ) && activeExportGeneration == generation) {
                                    activeExportGeneration = 0L;
                                }
                            }
                        }
                    );
                } else if (activeHoldGeneration == generation) {
                    // The native hold is acquired before the export session
                    // is fully published.  If this generation was invalidated
                    // in that window, clear exactly its hold while retaining
                    // any pending reservation belonging to a newer token.
                    activeHoldGeneration = 0L;
                    activeHoldOwner = null;
                    resetNativeScenePolicyUnconditionally(
                        "stale Scene export generation"
                    );
                }
            }
            if (!generationStillCurrent) {
                acceptedSession.abort();
                // The wrapper may still be queued.  A graceful shutdown lets
                // the cancelled session run its finally block and release
                // SceneMirrorExportCoordinator.inFlight.
                writerPool.shutdown();
                currentActivity.compareAndSet(activity, null);
                activity.finish();
                try {
                    readEnd.close();
                } catch (IOException ignored) {
                }
                return null;
            }
            return readEnd;
        }

        /**
         * Fails open the native policy for this service generation.  The
         * generation and active-hold markers together identify the live
         * export/policy lease.  A consumed or explicitly fail-opened hold is
         * intentionally ignored, so a late same-generation cleanup cannot
         * clear a newer successful replacement.  Clearing the marker before
         * JNI keeps repeated cleanup idempotent.
         */
        @Override
        public void resetSceneProductionPolicy(long generation) {
            if (generation <= 0L) {
                return;
            }
            synchronized (policyHoldLock) {
                if (activeSceneSyncGeneration != generation
                    || activeHoldGeneration != generation) {
                    return;
                }
                activeHoldGeneration = 0L;
                activeHoldOwner = null;
                try {
                    nativeResetSceneProductionPolicy();
                } catch (UnsatisfiedLinkError e) {
                    XposedBridge.log(
                        "[HousamoTrans] Native Scene policy reset is "
                            + "unavailable during export cleanup"
                    );
                }
            }
        }

        @Override
        public void completeSceneProductionPolicy(long generation) {
            if (generation <= 0L) {
                return;
            }
            synchronized (policyHoldLock) {
                if (activeSceneSyncGeneration == generation
                    && activeHoldGeneration == generation) {
                    activeHoldGeneration = 0L;
                    activeHoldOwner = null;
                }
            }
        }

        @Override
        public void abortSceneSyncActivity(long generation) {
            if (generation <= 0L) {
                return;
            }
            SceneMirrorExportCoordinator.ExportSession export = null;
            synchronized (policyHoldLock) {
                if (activeSceneSyncGeneration != generation) {
                    return;
                }
                if (activeExportGeneration == generation) {
                    activeExportGeneration = 0L;
                    export = currentExport.getAndSet(null);
                }
                ApplyActivity activity = currentActivity.get();
                if (activity != null && activity.generation == generation
                    && currentActivity.compareAndSet(activity, null)) {
                    // finish() cancels tasks and closes their endpoints while
                    // the same lock that guards the generation check is held.
                    // A REPLACE task that already owns this lock completes
                    // first; otherwise its pre-native commit check observes
                    // finished/currentActivity and cannot publish.
                    activity.finish();
                }
            }
            if (export != null) {
                export.abort();
            }
        }

        @Override
        public void setCapturePaused(boolean paused) {
            try {
                nativeSetCapturePaused(paused);
            } catch (UnsatisfiedLinkError e) {
                XposedBridge.log(
                    "[HousamoTrans] Native capture pause control is unavailable"
                );
            }
        }

        @Override
        public boolean applySceneChanges(
            long generation,
            ParcelFileDescriptor requestReadFd,
            ParcelFileDescriptor resultWriteFd
        ) {
            if (requestReadFd == null || resultWriteFd == null) {
                closeQuietly(requestReadFd);
                closeQuietly(resultWriteFd);
                return false;
            }
            ApplyActivity activity;
            boolean oneShot = false;
            boolean admitted;
            synchronized (policyHoldLock) {
                admitted = generation > 0L
                    && activeSceneSyncGeneration == generation;
                activity = currentActivity.get();
                if (admitted && activity != null
                    && (activity.generation != generation
                        || !activity.fullSync)) {
                    // A one-shot manual apply owns the sole activity until
                    // its result/abort path finishes.  Do not admit a second
                    // request only to have the first terminal task cancel it
                    // underneath.
                    admitted = false;
                }
                if (admitted && activity == null && activeHoldOwner != null) {
                    // A successful one-shot REPLACE has already flushed its
                    // result and finished its Activity, but its native policy
                    // lease is still waiting for HET's generation-aware ACK.
                    // Reject a new one-shot in that window so its completion
                    // cannot consume the new owner's marker.
                    admitted = false;
                }
                if (admitted && activity == null) {
                    ApplyActivity candidate = new ApplyActivity(
                        1,
                        false,
                        generation
                    );
                    if (currentActivity.compareAndSet(null, candidate)) {
                        activity = candidate;
                        oneShot = true;
                    } else {
                        candidate.finish();
                        admitted = false;
                    }
                }
            }
            if (!admitted) {
                closeQuietly(requestReadFd);
                closeQuietly(resultWriteFd);
                return false;
            }
            // Activation may have completed immediately after the admission
            // critical section and detached this identity.  Re-check before
            // attaching the task; cleanup is identity-CAS only and therefore
            // cannot clear a newer generation's activity.
            synchronized (policyHoldLock) {
                if (activeSceneSyncGeneration != generation
                    || currentActivity.get() != activity) {
                    admitted = false;
                }
            }
            if (!admitted) {
                closeQuietly(requestReadFd);
                closeQuietly(resultWriteFd);
                if (oneShot) {
                    currentActivity.compareAndSet(activity, null);
                    activity.finish();
                }
                return false;
            }
            ApplyTask task = new ApplyTask(
                activity,
                oneShot,
                requestReadFd,
                resultWriteFd
            );
            activity.activeTasks.add(task);
            try {
                activity.executor.execute(task);
                return true;
            } catch (RejectedExecutionException e) {
                activity.activeTasks.remove(task);
                task.cancel();
                if (oneShot) {
                    currentActivity.compareAndSet(activity, null);
                    activity.finish();
                }
                return false;
            }
        }

        private void close() {
            resetSceneProductionPolicyForConnectionLoss();
        }

        private void abortCurrentSyncActivity() {
            SceneMirrorExportCoordinator.ExportSession export;
            ApplyActivity activity;
            synchronized (policyHoldLock) {
                export = currentExport.getAndSet(null);
                activeExportGeneration = 0L;
                activity = currentActivity.getAndSet(null);
            }
            if (export != null) {
                export.abort();
            }
            if (activity != null) {
                activity.finish();
            }
        }

        private boolean isSceneSyncGenerationCurrent(long generation) {
            synchronized (policyHoldLock) {
                return generation > 0L
                    && activeSceneSyncGeneration == generation;
            }
        }

        private final class ApplyActivity {
            private final boolean fullSync;
            private final long generation;
            private final ThreadPoolExecutor executor;
            private final Set<ApplyTask> activeTasks =
                ConcurrentHashMap.newKeySet();
            private final AtomicBoolean finished = new AtomicBoolean();

            private ApplyActivity(
                int workerCount,
                boolean fullSync,
                long generation
            ) {
                this.fullSync = fullSync;
                this.generation = generation;
                executor = new ThreadPoolExecutor(
                    workerCount,
                    workerCount,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(workerCount),
                    runnable -> {
                        Thread thread = new Thread(
                            runnable,
                            "HET-game-scene-apply"
                        );
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy()
                );
            }

            private void finish() {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                for (ApplyTask task : activeTasks) {
                    task.cancel();
                }
                for (Runnable pending : executor.shutdownNow()) {
                    if (pending instanceof ApplyTask) {
                        ((ApplyTask) pending).cancel();
                    }
                }
            }
        }

        private final class ApplyTask implements Runnable {
            private final ApplyActivity activity;
            private final boolean oneShot;
            private final ParcelFileDescriptor requestReadFd;
            private final ParcelFileDescriptor resultWriteFd;
            private final AtomicReference<SceneStore.RawSceneWriteSession>
                stagedWrite = new AtomicReference<>();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            /**
             * Only REPLACE_BLOCKED_SCENES owns the process-wide native policy
             * hold.  WRITE_SCENE also uses a one-shot ApplyActivity for
             * admission/cleanup, but it must never be allowed to reset the
             * policy belonging to another Scene or sync generation.
             */
            private final AtomicBoolean ownsPolicyHold =
                new AtomicBoolean();
            /**
             * Set once this REPLACE task may have established a native hold,
             * and cleared only after Native rejected the replacement or an
             * explicit generation-aware reset.  A successfully flushed result
             * remains held until the HET caller acknowledges it through
             * completeSceneProductionPolicy(generation).
             */
            private final AtomicBoolean policyHoldNeedsReset =
                new AtomicBoolean();
            /** A flushed successful policy result is waiting for HET ACK. */
            private final AtomicBoolean policyResultDelivered =
                new AtomicBoolean();

            private ApplyTask(
                ApplyActivity activity,
                boolean oneShot,
                ParcelFileDescriptor requestReadFd,
                ParcelFileDescriptor resultWriteFd
            ) {
                this.activity = activity;
                this.oneShot = oneShot;
                this.requestReadFd = requestReadFd;
                this.resultWriteFd = resultWriteFd;
            }

            @Override
            public void run() {
                if (cancelled.get()
                    || !isSceneSyncGenerationCurrent(activity.generation)) {
                    closeEndpoints();
                    finishActivityIfTerminal(false);
                    return;
                }
                SceneStore.RawSceneWriteSession write = null;
                boolean success = false;
                boolean terminalCommand = false;
                boolean deleteCommand = false;
                boolean resultWriteSucceeded = false;
                int resultCode = SceneSyncWireCodec.APPLY_INTERNAL_FAILURE;
                try (
                    InputStream input =
                        new ParcelFileDescriptor.AutoCloseInputStream(
                            requestReadFd
                        );
                    OutputStream output =
                        new ParcelFileDescriptor.AutoCloseOutputStream(
                            resultWriteFd
                        )
                ) {
                    try {
                        SceneSyncWireCodec.ApplyRequest request =
                            SceneSyncWireCodec.decodeApplyRequest(
                                input,
                                (sceneName, bodyLength, body) -> {
                                    if (cancelled.get()
                                        || !isSceneSyncGenerationCurrent(
                                            activity.generation
                                        )) {
                                        throw new IOException(
                                            "Scene apply was cancelled"
                                        );
                                    }
                                    if (stagedWrite.get() != null) {
                                        throw new SceneSyncWireCodec.ProtocolException(
                                            "multiple WRITE_SCENE bodies"
                                        );
                                    }
                                    SceneStore.RawSceneWriteSession staged =
                                        sceneStore.beginRawSceneWrite(sceneName);
                                    stagedWrite.set(staged);
                                    staged.copyFrom(body, bodyLength);
                                }
                            );
                        terminalCommand =
                            request.command.type
                                == SceneSyncWireCodec.RecordType.REPLACE_BLOCKED_SCENES;
                        ownsPolicyHold.set(terminalCommand);
                        policyHoldNeedsReset.set(terminalCommand);
                        deleteCommand =
                            request.command.type
                                == SceneSyncWireCodec.RecordType.DELETE_SCENE;
                        if (!isSceneSyncGenerationCurrent(activity.generation)) {
                            throw new IOException(
                                "Scene apply generation is no longer current"
                            );
                        }
                        switch (request.command.type) {
                            case WRITE_SCENE:
                                if (stagedWrite.get() == null) {
                                    throw new SceneSyncWireCodec.ProtocolException(
                                        "WRITE_SCENE body is missing"
                                    );
                                }
                                write = stagedWrite.get();
                                synchronized (policyHoldLock) {
                                    ensureCommitAllowedLocked();
                                    SceneStore.MutationReceipt<Void> receipt =
                                        write.commit();
                                    resultCode = receipt.disposition
                                        == SceneStore.MutationDisposition.DEFERRED
                                        ? SceneSyncWireCodec.APPLY_DEFERRED
                                        : SceneSyncWireCodec.APPLY_NONE;
                                }
                                success = true;
                                break;
                            case DELETE_SCENE:
                                if (stagedWrite.get() != null) {
                                    throw new SceneSyncWireCodec.ProtocolException(
                                        "DELETE_SCENE carried a Scene body"
                                    );
                                }
                                synchronized (policyHoldLock) {
                                    ensureCommitAllowedLocked();
                                    SceneStore.MutationReceipt<Void> receipt =
                                        sceneStore.deleteSceneForSync(
                                        request.command.sceneName
                                        );
                                    resultCode = receipt.disposition
                                        == SceneStore.MutationDisposition.DEFERRED
                                        ? SceneSyncWireCodec.APPLY_DEFERRED
                                        : SceneSyncWireCodec.APPLY_NONE;
                                }
                                success = true;
                                break;
                            case REPLACE_BLOCKED_SCENES:
                                if (stagedWrite.get() != null) {
                                    throw new SceneSyncWireCodec.ProtocolException(
                                        "policy command carried a Scene body"
                                    );
                                }
                                boolean policyUpdated;
                                synchronized (policyHoldLock) {
                                    ensureCommitAllowedLocked();
                                    // Every replacement attempt is owned by
                                    // this activity.  Mark the cleanup need
                                    // before entering Native so a rejected or
                                    // interrupted call can fail open even when
                                    // the generation hold was established
                                    // before this one-shot activity existed.
                                    activeHoldGeneration =
                                        activity.generation;
                                    activeHoldOwner = activity;
                                    policyHoldNeedsReset.set(true);
                                    try {
                                        if (!nativeBeginSceneSyncHold()) {
                                            throw new IOException(
                                                "Native Scene policy hold "
                                                    + "was not acquired"
                                            );
                                        }
                                    } catch (UnsatisfiedLinkError e) {
                                        throw new IOException(
                                            "Native Scene policy hold is "
                                                + "unavailable",
                                            e
                                        );
                                    }
                                }
                                try {
                                    nativeWaitForSceneProductionIdle();
                                } catch (UnsatisfiedLinkError e) {
                                    // Keep the native hold for the enclosing
                                    // failure cleanup path; a one-shot reset
                                    // is still restricted to this identity.
                                    throw new IOException(
                                        "Native Scene policy wait is "
                                            + "unavailable",
                                        e
                                    );
                                }
                                synchronized (policyHoldLock) {
                                    ensureCommitAllowedLocked();
                                    if (activeHoldGeneration
                                        != activity.generation
                                        || activeHoldOwner != activity) {
                                        throw new IOException(
                                            "Scene policy hold was lost"
                                        );
                                    }
                                    try {
                                        policyUpdated =
                                            nativeReplaceBlockedScenes(
                                                request.command.blockedScenes
                                                    .toArray(new String[0])
                                            );
                                    } catch (UnsatisfiedLinkError e) {
                                        policyUpdated = false;
                                        XposedBridge.log(
                                            "[HousamoTrans] Native blocked "
                                                + "Scene policy update is "
                                                + "unavailable"
                                        );
                                    }
                                    // Native explicitly fail-opens on a
                                    // rejected complete replacement.  Clear
                                    // only this attempt's Java marker; a true
                                    // replacement keeps the marker until the
                                    // result bytes are durably flushed below.
                                    if (!policyUpdated) {
                                        activeHoldGeneration = 0L;
                                        activeHoldOwner = null;
                                        policyHoldNeedsReset.set(false);
                                    }
                                }
                                if (!policyUpdated) {
                                    resultCode =
                                        SceneSyncWireCodec.APPLY_POLICY_UPDATE_FAILED;
                                } else {
                                    success = true;
                                    resultCode = SceneSyncWireCodec.APPLY_NONE;
                                }
                                break;
                            default:
                                throw new SceneSyncWireCodec.ProtocolException(
                                    "only WRITE_SCENE, DELETE_SCENE, or "
                                        + "REPLACE_BLOCKED_SCENES "
                                        + "is accepted"
                                );
                        }
                    } catch (SceneSyncWireCodec.ProtocolException e) {
                        resultCode = cancelled.get()
                            ? SceneSyncWireCodec.APPLY_OPERATION_CANCELED
                            : SceneSyncWireCodec.APPLY_REQUEST_PROTOCOL_INVALID;
                    } catch (SceneStore.RawSceneWriteFailure e) {
                        resultCode = cancelled.get()
                            ? SceneSyncWireCodec.APPLY_OPERATION_CANCELED
                            : SceneSyncWireCodec.APPLY_WRITE_FAILED;
                    } catch (IOException e) {
                        resultCode = cancelled.get()
                            ? SceneSyncWireCodec.APPLY_OPERATION_CANCELED
                            : stagedWrite.get() != null
                                ? SceneSyncWireCodec.APPLY_WRITE_FAILED
                                : deleteCommand
                                    ? SceneSyncWireCodec.APPLY_DELETE_FAILED
                                : SceneSyncWireCodec.APPLY_REQUEST_STREAM_FAILED;
                    } catch (RuntimeException e) {
                        resultCode = SceneSyncWireCodec.APPLY_INTERNAL_FAILURE;
                        XposedBridge.log(
                            "[HousamoTrans] Scene apply task failed: "
                                + e.getClass().getSimpleName()
                                + ": "
                                + e.getMessage()
                        );
                    } finally {
                        SceneStore.RawSceneWriteSession staged =
                            stagedWrite.get();
                        if (!success && staged != null) {
                            staged.abort();
                        }
                    }
                    try {
                        SceneSyncWireCodec.writeApplyResult(
                            output,
                            success,
                            resultCode
                        );
                        synchronized (policyHoldLock) {
                            output.flush();
                            resultWriteSucceeded = true;
                            if (success && ownsPolicyHold.get()) {
                                policyResultDelivered.set(true);
                            }
                        }
                        // A flushed APPLY_RESULT only acknowledges delivery to
                        // the HET caller. Keep the generation/owner marker until
                        // that caller explicitly confirms the policy with
                        // completeSceneProductionPolicy(generation).
                    } catch (IOException e) {
                        XposedBridge.log(
                            "[HousamoTrans] Could not return Scene apply result: "
                                + e.getMessage()
                        );
                    }
                } catch (IOException e) {
                    // Either endpoint was closed/rejected before a result
                    // could be emitted; the staged file has already been
                    // failed above when applicable.
                    SceneStore.RawSceneWriteSession staged =
                        stagedWrite.get();
                    if (staged != null && !success) {
                        staged.abort();
                    }
                } finally {
                    closeEndpoints();
                    if (ownsPolicyHold.get()
                        && policyHoldNeedsReset.get()
                        && (!success || !resultWriteSucceeded)) {
                        // REPLACE_BLOCKED_SCENES is the only one-shot command
                        // that owns the global native policy.  Every wait,
                        // decode/apply, native-link, and result-stream failure
                        // must release that exact activity's hold; never use a
                        // generation-only reset that could clear a newer
                        // full-sync activity.
                        resetOneShotHoldForActivity(
                            activity,
                            "one-shot Scene policy apply failed"
                        );
                    }
                    finishActivityIfTerminal(terminalCommand);
                }
            }

            private void finishActivityIfTerminal(boolean terminalCommand) {
                activity.activeTasks.remove(this);
                if (oneShot || terminalCommand) {
                    currentActivity.compareAndSet(activity, null);
                    activity.finish();
                }
            }

            /**
             * Linearizes the final Scene file side effect with generation
             * deactivation.  Request-body decoding intentionally happens
             * outside policyHoldLock; only commit/delete uses this boundary.
             */
            private void ensureCommitAllowedLocked() throws IOException {
                if (activeSceneSyncGeneration != activity.generation
                    || activity.finished.get()
                    || cancelled.get()
                    || currentActivity.get() != activity) {
                    throw new IOException(
                        "Scene apply generation is no longer current or was "
                            + "aborted"
                    );
                }
            }

            private void cancel() {
                cancelled.set(true);
                // Close the pipe first: copyFrom may be blocked in the
                // bounded request body, and its synchronized write-session
                // monitor must not be needed to unblock that read.
                closeEndpoints();
                SceneStore.RawSceneWriteSession staged = stagedWrite.get();
                if (staged != null) {
                    staged.abort();
                }
                // Cancellation can happen before the request command is
                // decoded.  In that case it is a WRITE_SCENE (or no command
                // at all) until proven otherwise, so do not clear the global
                // blocked-set policy.  A decoded REPLACE_BLOCKED_SCENES task
                // records ownership before acquiring its native hold and is
                // still cleaned by the guarded reset below.
                synchronized (policyHoldLock) {
                    if (ownsPolicyHold.get()
                        && policyHoldNeedsReset.get()
                        && !policyResultDelivered.get()) {
                        resetOneShotHoldForActivity(
                            activity,
                            "one-shot Scene policy apply cancelled"
                        );
                    }
                }
            }

            private void closeEndpoints() {
                closeQuietly(requestReadFd);
                closeQuietly(resultWriteFd);
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static final class RVA {
        long findScenarioData = 0;
        long initBase = 0;
        long initText = 0;
        long pageTextChange = 0;
        long addSelection = 0;
        long showSelection = 0;
    }

    private static final class Layout {
        Il2CppStringLayout il2CppString = new Il2CppStringLayout();
        Il2CppArrayLayout il2CppArray = new Il2CppArrayLayout();
        Il2CppListLayout il2CppList = new Il2CppListLayout();
        AdvScenarioPageDataLayout advScenarioPageData = new AdvScenarioPageDataLayout();
        ScenarioLabelDataLayout scenarioLabelData = new ScenarioLabelDataLayout();
        AdvScenarioDataLayout advScenarioData = new AdvScenarioDataLayout();
        Il2CppDictionaryLayout il2CppDictionary = new Il2CppDictionaryLayout();
        DictionaryEntryLayout dictionaryEntry = new DictionaryEntryLayout();
        AdvCommandLayout advCommand = new AdvCommandLayout();
        StringGridRowLayout stringGridRow = new StringGridRowLayout();
        AdvCommandCharacterLayout advCommandCharacter = new AdvCommandCharacterLayout();
        AdvCommandSelectionLayout advCommandSelection = new AdvCommandSelectionLayout();
        AdvCommandJumpLayout advCommandJump = new AdvCommandJumpLayout();
        TextColumnsLayout textColumns = new TextColumnsLayout();

        private static final class Il2CppStringLayout {
            long length = 0;
            long chars = 0;
        }

        private static final class Il2CppArrayLayout {
            long length = 0;
            long firstElement = 0;
            int pointerSize = 0;
        }

        private static final class Il2CppListLayout {
            long items = 0;
            long size = 0;
        }

        private static final class AdvScenarioPageDataLayout {
            long commandList = 0;
            long textDataList = 0;
            long scenarioLabelData = 0;
            long pageNo = 0;
            long messageWindowName = 0;
        }

        private static final class ScenarioLabelDataLayout {
            long pageDataList = 0;
            long scenarioLabel = 0;
            long next = 0;
            long commandList = 0;
            long scenarioLabelCommand = 0;
        }

        private static final class AdvScenarioDataLayout {
            long name = 0;
            long jumpDataList = 0;
            long scenarioLabels = 0;
        }

        private static final class Il2CppDictionaryLayout {
            long entries = 0;
            long count = 0;
        }

        private static final class DictionaryEntryLayout {
            long hashCode = 0;
            long key = 0;
            long value = 0;
            long size = 0;
        }

        private static final class AdvCommandLayout {
            long rowData = 0;
            long type = 0;
        }

        private static final class StringGridRowLayout {
            long rowIndex = 0;
            long strings = 0;
        }

        private static final class AdvCommandCharacterLayout {
            long characterInfo = 0;
            long nameText = 0;
        }

        private static final class AdvCommandSelectionLayout {
            long jumpLabel = 0;
        }

        private static final class AdvCommandJumpLayout {
            long jumpLabel = 0;
            long expressionParser = 0;
            int conditionColumn = 0;
        }

        private static final class TextColumnsLayout {
            int raw = 0;
            int en = 0;
            int zhTw = 0;
            int zhCn = 0;
        }
    }

    private static final class CharacterWeight {
        float highRelevance = 4.0f;
        float midRelevance = 3.0f;
        float densityHigh = 1.5f;
        float textLowScore = 3.0f;
        float textMentionedScore = 1.0f;
        int relatedNum = 1;
        int lowTermScore = 3;
    }

    private static final class StartupConfig {
        RVA rva;
        Layout layout;
        CharacterWeight characterWeight;
        int sceneWorkerCount;
        SceneSyncStartupSnapshot sceneSyncSnapshot;
        boolean enablePageRecDebug;
        boolean enableParseOnlyDebug;
        boolean overwriteExistingJson;
        String targetLanguage;
        String gameVersion;
    }

    @FunctionalInterface
    private interface StartupJsonValidator {
        void validate(JSONObject json) throws Exception;
    }

    // Tools
    private static long parseRVA(String value) {
        // 支持十六进制（0x开头）和十进制格式的 RVA 输入
        value = value.trim();

        if (value.startsWith("0x") || value.startsWith("0X")) {
            return Long.parseUnsignedLong(value.substring(2), 16);
        }

        return Long.parseUnsignedLong(value, 10);
    }

    private static long getConfigLong(JSONObject json, String key) throws Exception {
        Object value = json.get(key);

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return parseRVA(String.valueOf(value));
    }

    private static int getConfigInt(JSONObject json, String key) throws Exception {
        long value = getConfigLong(json, key);

        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of int range: " + value);
        }

        return (int) value;
    }

    private static float getConfigFloat(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        double parsed;

        if (value instanceof Number) {
            parsed = ((Number) value).doubleValue();
        } else {
            parsed = Double.parseDouble(String.valueOf(value).trim());
        }

        if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed <= 0.0 || parsed > 100000.0) {
            throw new IllegalArgumentException(key + " must be a finite positive number: " + value);
        }

        return (float) parsed;
    }

    private static String readModuleAsset(String name) throws Exception {
        // 获取模块 APK 路径并读取 assets 目录下的指定文件内容
        try (ZipFile zip = new ZipFile(sModulePath)) {

            String assetPath = bundledAssetPath(name);
            ZipEntry entry = zip.getEntry("assets/" + assetPath);
            if (entry == null) {
                throw new FileNotFoundException("assets/" + assetPath);
            }

            try (InputStream input = zip.getInputStream(entry)) {
                return IoUtils.readUtf8Limited(input, -1);
            }
        }
    }

    private static String bundledAssetPath(String name) {
        if (CHARDICT_FILE_NAME.equals(name)
            || GAMETERMS_FILE_NAME.equals(name)) {
            return TERM_ASSET_DIRECTORY + name;
        }
        return name;
    }

    private static String readPreferredModuleJson(Context context, String name)
        throws Exception {
        Uri uri = new Uri.Builder()
            .scheme("content")
            .authority(HetBridgeContract.USER_FILES_AUTHORITY)
            .appendPath(name)
            .build();

        try {
            context.getContentResolver().takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // A temporary grant may not be present yet. openInputStream below
            // still works on devices where the exported provider is visible.
        }

        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input != null) {
                XposedBridge.log("[HousamoTrans] Using user override: " + name);
                return IoUtils.readUtf8Limited(input, -1);
            }
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] No usable user override for "
                    + name
                    + "; falling back to module asset ("
                    + e.getClass().getSimpleName()
                    + ")"
            );
        }

        return readModuleAsset(name);
    }

    private static RVA Init_RVA(JSONObject json) throws Exception {
        RVA rva = new RVA();
        JSONObject rva_config = json.getJSONObject("RVA");
        rva.findScenarioData = parseRVA(rva_config.getString("RVA_FindScenarioData"));
        rva.initBase = parseRVA(rva_config.getString("RVA_InitBase"));
        rva.initText = parseRVA(rva_config.getString("RVA_InitText"));
        rva.pageTextChange = parseRVA(rva_config.getString("RVA_PageTextChange"));
        rva.addSelection = parseRVA(rva_config.getString("RVA_AddSelection"));
        rva.showSelection = parseRVA(rva_config.getString("RVA_ShowSelection"));
        return rva;
    }

    private static Layout Init_Layout(JSONObject json) throws Exception {
        Layout layout = new Layout();
        JSONObject layoutConfig = json.getJSONObject("Layout");

        JSONObject il2CppString = layoutConfig.getJSONObject("Il2CppString");
        layout.il2CppString.length = getConfigLong(il2CppString, "Length");
        layout.il2CppString.chars = getConfigLong(il2CppString, "Chars");

        JSONObject il2CppArray = layoutConfig.getJSONObject("Il2CppArray");
        layout.il2CppArray.length = getConfigLong(il2CppArray, "Length");
        layout.il2CppArray.firstElement = getConfigLong(il2CppArray, "FirstElement");
        layout.il2CppArray.pointerSize = getConfigInt(il2CppArray, "PointerSize");

        JSONObject il2CppList = layoutConfig.getJSONObject("Il2CppList");
        layout.il2CppList.items = getConfigLong(il2CppList, "Items");
        layout.il2CppList.size = getConfigLong(il2CppList, "Size");

        JSONObject pageData = layoutConfig.getJSONObject("AdvScenarioPageData");
        layout.advScenarioPageData.commandList = getConfigLong(pageData, "CommandList");
        layout.advScenarioPageData.textDataList = getConfigLong(pageData, "TextDataList");
        layout.advScenarioPageData.scenarioLabelData = getConfigLong(pageData, "ScenarioLabelData");
        layout.advScenarioPageData.pageNo = getConfigLong(pageData, "PageNo");
        layout.advScenarioPageData.messageWindowName = getConfigLong(pageData, "MessageWindowName");

        JSONObject scenarioLabelData = layoutConfig.getJSONObject("ScenarioLabelData");
        layout.scenarioLabelData.pageDataList = getConfigLong(scenarioLabelData, "PageDataList");
        layout.scenarioLabelData.scenarioLabel = getConfigLong(scenarioLabelData, "ScenarioLabel");
        layout.scenarioLabelData.next = getConfigLong(scenarioLabelData, "Next");
        layout.scenarioLabelData.commandList = getConfigLong(scenarioLabelData, "CommandList");
        layout.scenarioLabelData.scenarioLabelCommand = getConfigLong(scenarioLabelData, "ScenarioLabelCommand");

        JSONObject scenarioData = layoutConfig.getJSONObject("AdvScenarioData");
        layout.advScenarioData.name = getConfigLong(scenarioData, "Name");
        layout.advScenarioData.jumpDataList = getConfigLong(scenarioData, "JumpDataList");
        layout.advScenarioData.scenarioLabels = getConfigLong(scenarioData, "ScenarioLabels");

        JSONObject il2CppDictionary = layoutConfig.getJSONObject("Il2CppDictionary");
        layout.il2CppDictionary.entries = getConfigLong(il2CppDictionary, "Entries");
        layout.il2CppDictionary.count = getConfigLong(il2CppDictionary, "Count");

        JSONObject dictionaryEntry = layoutConfig.getJSONObject("DictionaryEntry");
        layout.dictionaryEntry.hashCode = getConfigLong(dictionaryEntry, "HashCode");
        layout.dictionaryEntry.key = getConfigLong(dictionaryEntry, "Key");
        layout.dictionaryEntry.value = getConfigLong(dictionaryEntry, "Value");
        layout.dictionaryEntry.size = getConfigLong(dictionaryEntry, "Size");

        JSONObject advCommand = layoutConfig.getJSONObject("AdvCommand");
        layout.advCommand.rowData = getConfigLong(advCommand, "RowData");
        layout.advCommand.type = getConfigLong(advCommand, "Type");

        JSONObject stringGridRow = layoutConfig.getJSONObject("StringGridRow");
        layout.stringGridRow.rowIndex = getConfigLong(stringGridRow, "RowIndex");
        layout.stringGridRow.strings = getConfigLong(stringGridRow, "Strings");

        JSONObject character = layoutConfig.getJSONObject("AdvCommandCharacter");
        layout.advCommandCharacter.characterInfo = getConfigLong(character, "CharacterInfo");
        layout.advCommandCharacter.nameText = getConfigLong(character, "NameText");

        JSONObject selection = layoutConfig.getJSONObject("AdvCommandSelection");
        layout.advCommandSelection.jumpLabel = getConfigLong(selection, "JumpLabel");

        JSONObject jump = layoutConfig.getJSONObject("AdvCommandJump");
        layout.advCommandJump.jumpLabel = getConfigLong(jump, "JumpLabel");
        layout.advCommandJump.expressionParser = getConfigLong(jump, "ExpressionParser");
        layout.advCommandJump.conditionColumn = getConfigInt(jump, "ConditionColumn");

        JSONObject textColumns = layoutConfig.getJSONObject("TextColumns");
        layout.textColumns.raw = getConfigInt(textColumns, "Raw");
        layout.textColumns.en = getConfigInt(textColumns, "En");
        layout.textColumns.zhTw = getConfigInt(textColumns, "ZhTw");
        layout.textColumns.zhCn = getConfigInt(textColumns, "ZhCn");

        return layout;
    }

    private static boolean Init_EnablePageRecDebug(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.getBoolean("EnablePageRecDebug");
    }

    private static boolean Init_EnableParseOnlyDebug(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.optBoolean("EnableParseOnlyDebug", false);
    }

    private static boolean Init_OverwriteExistingJson(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.getBoolean("OverwriteExistingJson");
    }

    private static CharacterWeight Init_CharacterWeight(JSONObject json) throws Exception {
        CharacterWeight weight = new CharacterWeight();
        JSONObject userSettings = json.getJSONObject("UserSettings");
        JSONObject weightConfig = userSettings.getJSONObject("CharacterWeight");

        weight.highRelevance = getConfigFloat(weightConfig, "HighRelevance");
        weight.midRelevance = getConfigFloat(weightConfig, "MidRelevance");
        weight.densityHigh = getConfigFloat(weightConfig, "DensityHigh");
        weight.textLowScore = getConfigFloat(weightConfig, "TextLowScore");
        weight.textMentionedScore = getConfigFloat(
            weightConfig,
            "TextMentionedScore"
        );
        weight.relatedNum = getConfigInt(weightConfig, "RelatedNum");
        weight.lowTermScore = getConfigInt(weightConfig, "LowTermScore");

        if (weight.highRelevance < weight.midRelevance) {
            throw new IllegalArgumentException(
                "CharacterWeight.HighRelevance must be >= MidRelevance"
            );
        }

        if (weight.textLowScore < weight.textMentionedScore) {
            throw new IllegalArgumentException(
                "CharacterWeight.TextLowScore must be >= TextMentionedScore"
            );
        }

        if (weight.relatedNum < 1) {
            throw new IllegalArgumentException(
                "CharacterWeight.RelatedNum must be >= 1"
            );
        }

        if (weight.lowTermScore < 1) {
            throw new IllegalArgumentException(
                "CharacterWeight.LowTermScore must be >= 1"
            );
        }

        return weight;
    }

    private static String Init_TargetLanguage(JSONObject json) throws Exception {
        JSONObject userSettings = json.getJSONObject("UserSettings");
        return userSettings.getString("TargetLanguage");
    }

    private static String Init_GameVersion(JSONObject json) throws Exception {
        return json.getString("GameVersion");
    }

    private static StartupConfig parseStartupConfig(
        JSONObject userConfig,
        JSONObject runtimeConfig
    ) throws Exception {
        JSONObject runtimeConfigs = runtimeConfig.getJSONObject("RuntimeConfigs");

        StartupConfig config = new StartupConfig();
        config.rva = Init_RVA(runtimeConfigs);
        config.layout = Init_Layout(runtimeConfigs);
        config.characterWeight = Init_CharacterWeight(userConfig);
        JSONObject userSettings = userConfig.getJSONObject("UserSettings");
        config.sceneSyncSnapshot = SceneSyncStartupSnapshot.of(
            ConfigStore.getSceneWorkerCount(userSettings)
        );
        config.sceneWorkerCount = config.sceneSyncSnapshot.getSceneWorkerCount();
        config.enablePageRecDebug = Init_EnablePageRecDebug(userConfig);
        config.enableParseOnlyDebug = Init_EnableParseOnlyDebug(userConfig);
        config.overwriteExistingJson = Init_OverwriteExistingJson(userConfig);
        config.targetLanguage = Init_TargetLanguage(userConfig);
        config.gameVersion = Init_GameVersion(runtimeConfig);
        return config;
    }

    private static StartupConfig loadStartupConfig(Context context) throws Exception {
        JSONObject userConfig = loadStartupJson(
            context,
            CONFIG_FILE_NAME,
            MainHook::validateUserStartupJson
        );
        JSONObject runtimeConfig = loadStartupJson(
            context,
            RUNTIME_FILE_NAME,
            MainHook::validateRuntimeStartupJson
        );
        return parseStartupConfig(userConfig, runtimeConfig);
    }

    private static JSONObject loadStartupJson(
        Context context,
        String name,
        StartupJsonValidator validator
    ) throws Exception {
        String preferred = readPreferredModuleJson(context, name);
        try {
            JSONObject json = new JSONObject(preferred);
            validator.validate(json);
            return json;
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] Preferred "
                    + name
                    + " is invalid; "
                    + "retrying the bundled default ("
                    + e.getClass().getSimpleName()
                    + ": "
                    + e.getMessage()
                    + ")"
            );
            JSONObject json = new JSONObject(readModuleAsset(name));
            validator.validate(json);
            return json;
        }
    }

    private static void validateUserStartupJson(JSONObject json) throws Exception {
        if (json.getString("Version").trim().isEmpty()) {
            throw new IllegalArgumentException("config.Version must not be empty");
        }
        Init_CharacterWeight(json);
        Init_EnablePageRecDebug(json);
        Init_EnableParseOnlyDebug(json);
        Init_OverwriteExistingJson(json);
        Init_TargetLanguage(json);
        ConfigStore.normalizeSceneSyncSettings(json.getJSONObject("UserSettings"));
    }

    private static void validateRuntimeStartupJson(JSONObject json) throws Exception {
        if (Init_GameVersion(json).trim().isEmpty()) {
            throw new IllegalArgumentException("runtime.GameVersion must not be empty");
        }
        JSONObject runtimeConfigs = json.getJSONObject("RuntimeConfigs");
        Init_RVA(runtimeConfigs);
        Init_Layout(runtimeConfigs);
    }

    /** Deterministically binds a request ID to the exact request bytes. */
    private static String createTranslationRequestId(byte[] requestJson) {
        if (requestJson == null || requestJson.length == 0) {
            throw new IllegalArgumentException("request is empty");
        }

        return UUID.nameUUIDFromBytes(requestJson).toString();
    }

    /**
     * New native-pipeline bridge. The request bytes are deliberately opaque:
     * this method only validates their transport envelope and derives the ID
     * from those exact bytes.
     */
    private static byte[] resolveTranslationRequestId(byte[] requestJson) {
        if (requestJson == null || requestJson.length == 0) {
            return errorBytes("input", 0, "request is empty", false);
        }
        if (requestJson.length > MAX_TRANSLATION_REQUEST_BYTES) {
            return errorBytes("input", 0, "request is too large", false);
        }

        try {
            String requestId = createTranslationRequestId(requestJson);
            if (requestId.isEmpty()) {
                return errorBytes(
                    "internal",
                    0,
                    "request ID resolution returned an empty ID",
                    false
                );
            }
            return new JSONObject()
                .put("request_id", requestId)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] Request ID resolution failed: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes("internal", 0, safeMessage(e), false);
        }
    }

    private static byte[] submitTranslation(
        String requestId,
        byte[] requestJson
    ) {
        if (requestId == null || requestId.isEmpty()) {
            return errorBytes("input", 0, "requestId is empty", false);
        }

        if (requestJson == null || requestJson.length == 0) {
            return errorBytes("input", 0, "request is empty", false);
        }
        if (requestJson.length > MAX_TRANSLATION_REQUEST_BYTES) {
            return errorBytes("input", 0, "request is too large", false);
        }

        TranslationServiceClient client = sTranslationClient;
        if (client == null) {
            return errorBytes(
                "service",
                0,
                "TranslationService client is not initialized",
                true
            );
        }

        try {
            /*
            * 保证 native 传回来的 requestId 确实属于这份请求。
            * 这样不会把 ApiItem A 错误绑定到请求 B。
            */
            String expectedRequestId =
                createTranslationRequestId(requestJson);

            if (!expectedRequestId.equals(requestId)) {
                return errorBytes(
                    "input",
                    0,
                    "requestId does not match request payload",
                    false
                );
            }

            if (!client.start()) {
                return errorBytes(
                    "service",
                    0,
                    "could not start TranslationService",
                    true
                );
            }

            client.bind();

            if (!client.awaitConnected(SERVICE_CONNECT_TIMEOUT_MS)) {
                return errorBytes(
                    "service",
                    0,
                    "TranslationService did not connect within "
                        + SERVICE_CONNECT_TIMEOUT_MS
                        + " ms",
                    true
                );
            }

            boolean created = client.enqueue(
                requestId,
                requestJson,
                sOverwriteExistingJson
            );

            JSONObject accepted = new JSONObject()
                .put("accepted", true)
                .put("request_id", requestId)
                .put("created", created);

            XposedBridge.log(
                "[HousamoTrans] Translation task accepted requestId="
                    + requestId
                    + " created="
                    + created
            );

            return accepted
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return errorBytes(
                "service",
                0,
                "interrupted while waiting for TranslationService",
                true
            );
        } catch (RemoteException e) {
            XposedBridge.log(
                "[HousamoTrans] TranslationService Binder submission failed: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "service",
                0,
                safeMessage(e),
                true
            );
        } catch (IOException e) {
            XposedBridge.log(
                "[HousamoTrans] Translation request pipe/storage failed: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "storage",
                0,
                safeMessage(e),
                true
            );
        } catch (TranslationServiceClient.ClientClosedException e) {
            XposedBridge.log(
                "[HousamoTrans] Translation client is permanently closed: "
                    + safeMessage(e)
            );
            return errorBytes(
                "internal",
                0,
                safeMessage(e),
                false
            );
        } catch (TranslationServiceClient.AdmissionRejectedException e) {
            XposedBridge.log(
                "[HousamoTrans] Translation admission rejected disposition="
                    + e.getDisposition()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                e.getDisposition(),
                0,
                safeMessage(e),
                false
            );
        } catch (TranslationServiceClient.ServiceUnavailableException e) {
            XposedBridge.log(
                "[HousamoTrans] TranslationService is temporarily unavailable: "
                    + safeMessage(e)
            );
            return errorBytes(
                "service",
                0,
                safeMessage(e),
                true
            );
        } catch (IllegalArgumentException e) {
            XposedBridge.log(
                "[HousamoTrans] Translation request was rejected: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "input",
                0,
                safeMessage(e),
                false
            );
        } catch (SecurityException e) {
            XposedBridge.log(
                "[HousamoTrans] Translation request permission denied: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "permission",
                0,
                safeMessage(e),
                false
            );
        } catch (IllegalStateException e) {
            XposedBridge.log(
                "[HousamoTrans] Unexpected translation client state: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "internal",
                0,
                safeMessage(e),
                false
            );
        } catch (RuntimeException e) {
            XposedBridge.log(
                "[HousamoTrans] Unexpected translation submission failure: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "internal",
                0,
                safeMessage(e),
                false
            );
        } catch (Exception e) {
            XposedBridge.log(
                "[HousamoTrans] Unexpected checked translation submission failure: "
                    + e.getClass().getSimpleName()
                    + ": "
                    + safeMessage(e)
            );
            return errorBytes(
                "internal",
                0,
                safeMessage(e),
                false
            );
        }
    }

    private static void handleQuestPatch(
        String requestId,
        byte[] patchJson
    ) {
        // Quest patches are intentionally best-effort and ignored by the
        // terminal-result delivery path.  Reading the PFD still happens in
        // TranslationServiceClient so the Binder transport is drained, but
        // no native or Scene write is performed here.
        XposedBridge.log(
            "[HousamoTrans] Ignoring Quest patch requestId=" + requestId
        );
    }

    private static boolean handleSceneResult(
        String requestId,
        String scene,
        String targetLanguage,
        byte[] resultJson,
        String leaseToken,
        long connectionGeneration
    ) {
        TranslationServiceClient client = sTranslationClient;
        try {
            if (leaseToken == null || leaseToken.isEmpty()) {
                XposedBridge.log(
                    "[HousamoTrans] Ignoring completion without delivery lease "
                        + "requestId=" + requestId
                );
                return false;
            }
            if (client == null) {
                XposedBridge.log(
                    "[HousamoTrans] Completion client disappeared after "
                        + "delivery lease requestId=" + requestId
                );
                return false;
            }
            // Keep the persisted Scene identity outside the result body.  The
            // native bridge validates both values before it can apply a
            // terminal result or create its receipt.
            boolean accepted = nativeApplySceneResult(
                requestId,
                scene,
                targetLanguage,
                resultJson
            );
            if (!accepted) {
                XposedBridge.log(
                    "[HousamoTrans] Native rejected completion requestId="
                        + requestId
                );
                return releaseTerminalLease(
                    client,
                    requestId,
                    "completed",
                    leaseToken,
                    connectionGeneration
                );
            }
            if (!client.acknowledgeTerminal(
                requestId,
                "completed",
                leaseToken,
                connectionGeneration
            )) {
                XposedBridge.log(
                    "[HousamoTrans] Completion ACK was not persisted "
                        + "requestId=" + requestId
                );
                return releaseTerminalLease(
                    client,
                    requestId,
                    "completed",
                    leaseToken,
                    connectionGeneration
                );
            }
            nativeAcknowledgeTranslationTerminal(requestId, "completed");
            XposedBridge.log(
                "[HousamoTrans] Applied Scene result requestId="
                    + requestId
                    + " scene="
                    + scene
                    + " targetLang="
                    + targetLanguage
            );
            return true;
        } catch (UnsatisfiedLinkError e) {
            if (!sMissingSceneResultNativeLogged) {
                sMissingSceneResultNativeLogged = true;
                XposedBridge.log(
                    "[HousamoTrans] Final scene callback reached Java, "
                        + "but the native result bridge is unavailable; "
                        + "the terminal remains pending for retry"
                );
            }
            return releaseTerminalLease(
                client,
                requestId,
                "completed",
                leaseToken,
                connectionGeneration
            );
        } catch (RemoteException e) {
            XposedBridge.log(
                "[HousamoTrans] Completion delivery Binder call failed "
                    + "requestId=" + requestId + ": " + safeMessage(e)
            );
            return releaseTerminalLease(
                client,
                requestId,
                "completed",
                leaseToken,
                connectionGeneration
            );
        } catch (RuntimeException e) {
            XposedBridge.log(
                "[HousamoTrans] Final scene callback failed requestId="
                    + requestId
                    + ": "
                    + safeMessage(e)
            );
            return releaseTerminalLease(
                client,
                requestId,
                "completed",
                leaseToken,
                connectionGeneration
            );
        }
    }

    private static boolean handleTranslationFailure(
        String requestId,
        String errorType,
        String message,
        String leaseToken,
        long connectionGeneration
    ) {
        TranslationServiceClient client = sTranslationClient;
        try {
            if (leaseToken == null || leaseToken.isEmpty()) {
                XposedBridge.log(
                    "[HousamoTrans] Ignoring failure without delivery lease "
                        + "requestId=" + requestId
                );
                return false;
            }
            if (client == null) {
                XposedBridge.log(
                    "[HousamoTrans] Failure client disappeared after delivery "
                        + "lease requestId=" + requestId
                );
                return false;
            }
            boolean accepted = nativeOnTranslationFailed(
                requestId,
                errorType,
                message
            );
            if (!accepted) {
                return releaseTerminalLease(
                    client,
                    requestId,
                    "failed",
                    leaseToken,
                    connectionGeneration
                );
            }
            if (!client.acknowledgeTerminal(
                requestId,
                "failed",
                leaseToken,
                connectionGeneration
            )) {
                return releaseTerminalLease(
                    client,
                    requestId,
                    "failed",
                    leaseToken,
                    connectionGeneration
                );
            }
            nativeAcknowledgeTranslationTerminal(requestId, "failed");
            XposedBridge.log(
                "[HousamoTrans] Failure ACK persisted requestId="
                    + requestId
            );
            return true;
        } catch (UnsatisfiedLinkError e) {
            if (!sMissingFailureNativeLogged) {
                sMissingFailureNativeLogged = true;
                XposedBridge.log(
                    "[HousamoTrans] Failure callback reached Java, "
                        + "but the native failure bridge is unavailable; "
                        + "the terminal remains pending for retry"
                );
            }
            return releaseTerminalLease(
                client,
                requestId,
                "failed",
                leaseToken,
                connectionGeneration
            );
        } catch (RemoteException e) {
            XposedBridge.log(
                "[HousamoTrans] Failure delivery Binder call failed "
                    + "requestId=" + requestId + ": " + safeMessage(e)
            );
            return releaseTerminalLease(
                client,
                requestId,
                "failed",
                leaseToken,
                connectionGeneration
            );
        } catch (RuntimeException e) {
            XposedBridge.log(
                "[HousamoTrans] Native failure callback failed requestId="
                    + requestId
                    + ": "
                    + safeMessage(e)
            );
            return releaseTerminalLease(
                client,
                requestId,
                "failed",
                leaseToken,
                connectionGeneration
            );
        } finally {
            XposedBridge.log(
                "[HousamoTrans] Translation failed requestId="
                    + requestId
                    + " type="
                    + errorType
                    + " message="
                + message
            );
        }
    }

    private static boolean releaseTerminalLease(
        TranslationServiceClient client,
        String requestId,
        String terminalKind,
        String leaseToken,
        long connectionGeneration
    ) {
        if (leaseToken == null || leaseToken.isEmpty()) {
            // No lease was acquired, so there is nothing for this fallback
            // path to release.
            return true;
        }
        if (client == null) {
            // Keep the lease token visible to TranslationServiceClient's
            // local finally block, which still owns the cached Binder.
            return false;
        }
        try {
            return client.releaseTerminalDelivery(
                requestId,
                terminalKind,
                leaseToken,
                connectionGeneration
            );
        } catch (RemoteException | RuntimeException e) {
            XposedBridge.log(
                "[HousamoTrans] Could not release terminal lease requestId="
                    + requestId
                    + ": "
                    + safeMessage(e)
            );
            return false;
        }
    }

    private static byte[] errorBytes(String type, int status, String message) {
        return errorBytes(
            type,
            status,
            message,
            "service".equals(type) || "storage".equals(type)
        );
    }

    private static byte[] errorBytes(
        String type,
        int status,
        String message,
        boolean retryable
    ) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", type);
            error.put("status", status);
            error.put("message", truncate(message, 4096));
            error.put("retryable", retryable);
            return new JSONObject()
                .put("error", error)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return ("{\"error\":{\"type\":\"internal\","
                + "\"status\":0,\"message\":\"failed to encode bridge error\","
                + "\"retryable\":false}}")
                .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength
            ? value
            : value.substring(0, maxLength);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
            ? throwable.getClass().getSimpleName()
            : message;
    }

    /**
     * Native Scene production rejection side-channel.  The native hook only
     * calls this after validating the canonical AdvScenarioData.Name; the
     * client deliberately treats the Binder report as best effort.
     */
    private static void reportSceneProductionRejected(
        String sceneName,
        int reasonCode
    ) {
        TranslationServiceClient client = sTranslationClient;
        if (client != null) {
            client.reportSceneProductionRejected(sceneName, reasonCode);
        }
    }

    /** Binder death/unregister fail-open callback for the game native policy. */
    private static void resetSceneProductionPolicySafely() {
        GameScenePort gameScenePort = sGameScenePort;
        if (gameScenePort != null) {
            gameScenePort.resetSceneProductionPolicyForConnectionLoss();
            return;
        }
        try {
            nativeResetSceneProductionPolicy();
        } catch (UnsatisfiedLinkError e) {
            // The service may die before nativeStart/library load; there is
            // no native policy to reset in that startup window.
            XposedBridge.log(
                "[HousamoTrans] Native Scene policy reset unavailable before "
                    + "library initialization"
            );
        }
    }

    /**
     * Binder loss terminates only the current mirror stream.  The port stays
     * usable for a later registration after the client binds again; native
     * policy reset is handled separately by the client callback.
     */
    private static void abortGameSceneExportSafely() {
        GameScenePort gameScenePort = sGameScenePort;
        if (gameScenePort != null) {
            gameScenePort.abortCurrentSyncActivity();
        }
    }

    /**
     * Host-test seam for the initialization-failure cleanup identity check.
     * The static client field must be cleared only when it still references the
     * client that failed to initialize; a replacement client (if one ever
     * appears) must not be torn down by an older failure path.
     */
    static boolean isSameClient(Object stored, Object current) {
        return stored == current;
    }

    private static void installApplicationEntry(LoadPackageParam lpparam) {
        if (s_attach_hook_installed) {
            return;
        }
        s_attach_hook_installed = true;

        XposedHelpers.findAndHookMethod(
            Application.class,
            "attach",
            Context.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.args[0];
                    initializeTarget(context, lpparam);
                }
            }
        );
    }

    private static void initializeTarget(Context context, LoadPackageParam lpparam) {
        synchronized (MainHook.class) {
            if (s_loaded || s_initializing) {
                return;
            }
            s_initializing = true;
        }

        String baseDir = lpparam.appInfo.dataDir + "/files/housamo_embed_trans";
        Context applicationContext = context.getApplicationContext();
        sTargetContext = applicationContext != null
            ? applicationContext
            : context;

        if (applicationContext == null) {
            XposedBridge.log(
                "[HousamoTrans] Application context is not ready during attach; "
                    + "using the base context"
            );
        }

        try {
            // Read and validate the complete startup snapshot before opening
            // the HET connection.  The connection and native side therefore
            // observe one immutable worker choice for this game process.
            StartupConfig startup = loadStartupConfig(context);
            sOverwriteExistingJson = startup.overwriteExistingJson;
            TranslationServiceClient client =
                new TranslationServiceClient(
                    sTargetContext,
                    XposedBridge::log,
                    TRANSLATION_RESULT_SINK,
                    startup.sceneSyncSnapshot,
                    null,
                    MainHook::resetSceneProductionPolicySafely,
                    MainHook::abortGameSceneExportSafely
                );
            sTranslationClient = client;
            client.start();
            client.bind();

            XposedBridge.log(
                "[HousamoTrans] Target application attached, initializing ShadowHook..."
            );

            String chardictJson = readPreferredModuleJson(context, CHARDICT_FILE_NAME);
            String gametermsJson = readPreferredModuleJson(context, GAMETERMS_FILE_NAME);

            ShadowHook.init(new ShadowHook.ConfigBuilder()
                .setMode(ShadowHook.Mode.UNIQUE)
                .build());
            XposedBridge.log("[HousamoTrans] ShadowHook init ok");

            IoUtils.ensureDirectory(new File(baseDir));
            File targetSceneDirectory = new File(baseDir, SceneStore.DIRECTORY_NAME);
            IoUtils.ensureDirectory(targetSceneDirectory);
            System.loadLibrary("housamo_trans");
            XposedBridge.log("[HousamoTrans] Native library loaded successfully.");

            nativeStart(
                startup.gameVersion,
                startup.rva,
                startup.layout,
                startup.characterWeight,
                startup.sceneWorkerCount,
                startup.enablePageRecDebug,
                startup.enableParseOnlyDebug,
                startup.overwriteExistingJson,
                startup.targetLanguage,
                chardictJson,
                gametermsJson,
                baseDir
            );
            // Bind may have completed earlier, but no Game Scene port is
            // registered until native policy, the injected mirror directory,
            // and the adapter are all ready.
            GameScenePort gameScenePort = new GameScenePort(
                sTargetContext,
                targetSceneDirectory,
                startup.sceneWorkerCount
            );
            sGameScenePort = gameScenePort;
            client.setGameScenePort(gameScenePort);

            s_loaded = true;
            XposedBridge.log(
                "[HousamoTrans] Native hook setup complete. gameVersion="
                    + startup.gameVersion
                    + " targetLanguage="
                    + startup.targetLanguage
                    + " sceneWorkerCount="
                    + startup.sceneWorkerCount
                    + " parseOnlyDebug="
                    + startup.enableParseOnlyDebug
                    + " apiOwner=het-service"
            );
        } catch (Throwable t) {
            TranslationServiceClient client = sTranslationClient;
            GameScenePort gameScenePort = sGameScenePort;
            sGameScenePort = null;
            if (isSameClient(sTranslationClient, client)) {
                sTranslationClient = null;
            }
            if (gameScenePort != null) {
                gameScenePort.close();
            }
            if (client != null) {
                client.close();
            }
            XposedBridge.log(
                "[HousamoTrans] FATAL: Initialization failed: "
                    + t.getClass().getSimpleName()
                    + ": "
                    + t.getMessage()
            );
        } finally {
            synchronized (MainHook.class) {
                s_initializing = false;
            }
        }
    }

    private static native void nativeStart(
        String gameVersion,
        RVA rva,
        Layout layout,
        CharacterWeight characterWeight,
        int sceneWorkerCount,
        boolean enablePageRecDebug,
        boolean enableParseOnlyDebug,
        boolean overwriteExistingJson,
        String targetLanguage,
        String chardictJson,
        String gametermsJson,
        String baseDir
    );

    /** Native Scene Production Policy control-plane seam for the game port. */
    private static native boolean nativeBeginSceneSyncHold();

    private static native void nativeWaitForSceneProductionIdle();

    private static native boolean nativeReplaceBlockedScenes(
        String[] sceneNames
    );

    private static native void nativeResetSceneProductionPolicy();

    private static native void nativeSetCapturePaused(boolean paused);

    /*
     * Native terminal callback bridge.  The implementations live in
     * translation_callback_bridge.cpp; callers still catch UnsatisfiedLinkError
     * so a partially deployed native library leaves the terminal persisted for
     * later reattachment instead of acknowledging it prematurely.
     */
    private static native void nativeApplyQuestPatch(
        String requestId,
        byte[] patchJson
    );

    private static native boolean nativeApplySceneResult(
        String requestId,
        String scene,
        String targetLanguage,
        byte[] resultJson
    );

    private static native boolean nativeOnTranslationFailed(
        String requestId,
        String errorType,
        String message
    );

    private static native boolean nativeAcknowledgeTranslationTerminal(
        String requestId,
        String terminalKind
    );

    @Override
    public void initZygote(StartupParam startupParam) {
        sModulePath = startupParam.modulePath;
        XposedBridge.log("[HousamoTrans] Module path: " + sModulePath);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!HetBridgeContract.TARGET_PACKAGE.equals(lpparam.packageName)
            || !HetBridgeContract.TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        installApplicationEntry(lpparam);
    }
}
