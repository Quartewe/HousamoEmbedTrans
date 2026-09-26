package com.quarty.housamoembedtrans.management.transfer;

import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.management.pending.PendingProcessManager;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;
import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import com.quarty.housamoembedtrans.ui.ManagementImportModel;
import com.quarty.housamoembedtrans.ui.ManagementImportSessionStore;
import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service-owned coordinator for the management import handoff.
 *
 * <p>The Activity only creates a validated preview session.  This class is
 * the owner of the real store admission and the durable cross-store intent.
 * There is one commit point: a COMMITTING manifest.  Before that point a
 * failed operation is discarded.  After that point the manifest is replayed
 * forward on the same store APIs; no UI rollback or partial-success error is
 * fabricated.</p>
 */
public final class ManagementImportCoordinator {
    private static final int JOURNAL_VERSION = 1;
    private static final String DIRECTORY_NAME = "management_import_txn";
    private static final String STATE_PREPARED = "PREPARED";
    private static final String STATE_COMMITTING = "COMMITTING";
    private static final String STATE_COMMITTED = "COMMITTED";
    private static final int MAX_JOURNAL_BYTES = 64 * 1024 * 1024;

    private final Context context;
    private final SceneStore sceneStore;
    private final SceneContextStore sceneContextStore;
    private final TranslationJobStore jobStore;
    private final ConfigStore configStore;
    private final File transactionRoot;
    private final ManagementImportRecoveryGate recoveryGate;
    private final Object coordinatorLock = new Object();

    private interface AdmissionWork<T> {
        T run(SceneStore.MutationAdmission.FullSyncLease lease)
            throws Exception;
    }

    private static final class SnapshotChangedException extends IOException {
        private static final long serialVersionUID = 1L;

        SnapshotChangedException(String message) {
            super(message);
        }
    }

    public ManagementImportCoordinator(
        Context context,
        SceneStore sceneStore,
        SceneContextStore sceneContextStore,
        TranslationJobStore jobStore
    ) {
        if (context == null
            || sceneStore == null
            || sceneContextStore == null
            || jobStore == null) {
            throw new IllegalArgumentException(
                "context and management stores are required"
            );
        }
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
        this.sceneStore = sceneStore;
        this.sceneContextStore = sceneContextStore;
        this.jobStore = jobStore;
        this.configStore = new ConfigStore(this.context);
        this.transactionRoot = new File(
            this.context.getFilesDir(),
            DIRECTORY_NAME
        );
        this.recoveryGate = ManagementImportRecoveryGate.forFilesRoot(
            this.context.getFilesDir()
        );
    }

    /**
     * Reads a real store snapshot for the Activity preflight.  The Binder
     * caller receives this through a pipe, so large Scene documents never go
     * through an Intent extra.
     */
    public JSONObject snapshot() throws Exception {
        synchronized (coordinatorLock) {
            // A snapshot must not become the preflight input for a new
            // import while an older COMMITTING transaction is still pending.
            // Recovery failures are intentionally propagated, so callers do
            // not receive a snapshot that could lead to another durable
            // commit intent being created on top of the unresolved one.
            recoverLocked();
            List<TranslationJobStore.ReviewJob> jobs =
                jobStore.listReviewJobs();
            return SceneContextStore.withRootAccess(() ->
                buildSnapshotLocked(jobs)
            );
        }
    }

    /**
     * Applies one Activity session after rechecking the exact snapshot
     * fingerprint.  The returned envelope distinguishes an ordinary
     * pre-commit rejection from a durable post-COMMITTING recovery state.
     */
    public JSONObject applySession(
        String sessionToken,
        String expectedSnapshotFingerprint
    ) throws Exception {
        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            return error("invalid_session", "导入预览会话不存在");
        }
        if (expectedSnapshotFingerprint == null
            || expectedSnapshotFingerprint.trim().isEmpty()) {
            return error("invalid_snapshot", "导入快照指纹不存在");
        }

        JSONObject preview = ManagementImportSessionStore.read(
            context,
            sessionToken
        );
        ManagementImportModel.validatePreviewEnvelope(preview);
        String previewSnapshot = preview.optString(
            "snapshot_fingerprint",
            ""
        ).trim();
        if (!expectedSnapshotFingerprint.equals(previewSnapshot)) {
            return error("snapshot_changed", "导入预检快照已变化，请重新预检");
        }

        synchronized (coordinatorLock) {
            // Startup recovery always runs before looking up the requested
            // session.  This also installs the gate for an old COMMITTING
            // journal which predates the marker file.
            recoverLocked();
            JSONObject existingTransaction =
                resumeExistingSessionTransaction(sessionToken);
            if (existingTransaction != null) {
                return existingTransaction;
            }
            // The session token did not identify an existing transaction.
            // snapshot() recovers every other durable intent before this
            // path creates a fresh transaction directory.
            File transactionDirectory = null;
            SceneStore.MutationAdmission.FullSyncLease sceneLease = null;
            ManagementImportRecoveryGate.OwnerPermit[] ownerPermit =
                new ManagementImportRecoveryGate.OwnerPermit[1];
            boolean[] gateCleared = new boolean[1];
            try {
                JSONObject initial = snapshot();
                requireFingerprint(initial, expectedSnapshotFingerprint);

                // This admission may wait for a running Scene operation.  It
                // is deliberately acquired before ROOT/job locks.
                sceneLease = SceneStore.beginFullSyncAdmission(sceneStore);
                final SceneStore.MutationAdmission.FullSyncLease acquiredLease =
                    sceneLease;
                final String transactionId = UUID.randomUUID().toString();
                transactionDirectory = transactionDirectory(transactionId);
                final File journalDirectory = transactionDirectory;

                // PendingProcess publication and Scene policy publication use
                // this barrier.  Take it before the existing job/root locks
                // so the import's target check and the pending index share a
                // single ordering edge with PendingProcessManager.
                synchronized (PendingProcessManager.POLICY_PUBLICATION_LOCK) {
                    jobStore.withManagementMutation(() ->
                        SceneContextStore.withRootAccess(() -> {
                            // The Scene lease is already held, so this
                            // root->job read cannot wait for Scene admission
                            // and remains in the existing admission order.
                            List<TranslationJobStore.ReviewJob> jobs =
                                jobStore.listReviewJobs();
                            JSONObject current = buildSnapshotLocked(jobs);
                            requireFingerprint(
                                current,
                                expectedSnapshotFingerprint
                            );
                            JSONObject manifest = createManifest(
                                transactionId,
                                sessionToken,
                                preview,
                                current
                            );
                            validatePlanBeforeCommit(
                                preview,
                                manifest.getJSONArray("operations")
                            );
                            // PREPARED is durable before the recovery gate is
                            // published.  If marker publication or the
                            // second fingerprint check fails, the journal is
                            // still discardable and the gate can be removed
                            // safely.
                            writeManifest(journalDirectory, manifest);
                            recoveryGate.activate(transactionId);
                            ownerPermit[0] = recoveryGate.acquireOwnerPermit(
                                transactionId
                            );

                            // ConfigStore has its own process lock.  The
                            // first snapshot can race a direct dictionary
                            // write before the marker becomes active, so
                            // recheck every target after marker publication
                            // and before COMMITTING is written.  The policy
                            // barrier remains held through apply, so a
                            // PendingProcess publication cannot turn this
                            // known Scene rejection into a post-commit one.
                            validateTargetAvailabilityBeforeCommit(
                                manifest.getJSONArray("operations")
                            );
                            List<TranslationJobStore.ReviewJob> protectedJobs =
                                jobStore.listReviewJobs();
                            requireFingerprint(
                                buildSnapshotLocked(protectedJobs),
                                expectedSnapshotFingerprint
                            );
                            manifest.put("state", STATE_COMMITTING);
                            writeManifest(journalDirectory, manifest);
                            applyManifestLocked(manifest, acquiredLease);
                            manifest.put("state", STATE_COMMITTED);
                            writeManifest(journalDirectory, manifest);
                            recoveryGate.clear(transactionId);
                            gateCleared[0] = true;
                            return null;
                        })
                    );
                }

                ManagementImportRecoveryGate.notifyCleared(
                    context.getFilesDir()
                );

                return new JSONObject()
                    .put("ok", true)
                    .put("state", "applied")
                    .put("transaction_id", transactionId);
            } catch (SnapshotChangedException changed) {
                String state = durableState(transactionDirectory);
                if (STATE_PREPARED.equals(state)
                    && clearPreparedGate(
                        transactionDirectory,
                        ownerPermit,
                        gateCleared
                    )) {
                    deleteRecursively(transactionDirectory);
                    return error("snapshot_changed", changed.getMessage());
                }
                if (transactionDirectory != null
                    && (STATE_COMMITTING.equals(state)
                        || STATE_COMMITTED.equals(state)
                        || recoveryGate.isBlocked())) {
                    return new JSONObject()
                        .put("ok", true)
                        .put("state", "recovery_pending")
                        .put("transaction_id", transactionDirectory.getName())
                        .put("message", safeMessage(changed));
                }
                if (transactionDirectory != null) {
                    deleteRecursively(transactionDirectory);
                }
                return error("snapshot_changed", changed.getMessage());
            } catch (Exception failure) {
                String state = durableState(transactionDirectory);
                if (STATE_PREPARED.equals(state)
                    && clearPreparedGate(
                        transactionDirectory,
                        ownerPermit,
                        gateCleared
                    )) {
                    deleteRecursively(transactionDirectory);
                    return error("import_not_applied", safeMessage(failure));
                }
                if (transactionDirectory != null
                    && (STATE_COMMITTING.equals(state)
                        || STATE_COMMITTED.equals(state)
                        || recoveryGate.isBlocked())) {
                    // The intent is durable.  Report that recovery owns the
                    // remaining work; never claim that a partial formal
                    // mutation failed and was rolled back.
                    return new JSONObject()
                        .put("ok", true)
                        .put("state", "recovery_pending")
                        .put("transaction_id", transactionDirectory.getName())
                        .put("message", safeMessage(failure));
                }
                if (transactionDirectory != null) {
                    deleteRecursively(transactionDirectory);
                }
                return error("import_not_applied", safeMessage(failure));
            } finally {
                if (sceneLease != null) {
                    sceneLease.close();
                }
                if (ownerPermit[0] != null) {
                    ownerPermit[0].close();
                }
            }
        }
    }

    /**
     * Replays all durable COMMITTING intents.  PREPARED manifests have not
     * crossed the commit point and are safe to discard.
     */
    public void recover() throws Exception {
        synchronized (coordinatorLock) {
            recoverLocked();
        }
    }

    /**
     * Runs durable management-import recovery while {@link #coordinatorLock}
     * is already held.  Callers use this form from snapshot/apply paths so a
     * failed old recovery prevents creation of a newer transaction.
     */
    private void recoverLocked() throws Exception {
        if (!transactionRoot.exists()) {
            if (recoveryGate.isBlocked()) {
                throw new IOException(
                    "management recovery marker has no journal"
                );
            }
            return;
        }
        File[] directories = transactionRoot.listFiles(File::isDirectory);
        if (directories == null) {
            throw new IOException("could not enumerate management imports");
        }
        boolean hadDeferredCommittingJournal = false;
        final boolean markerWasActive = recoveryGate.isBlocked();
        final String activeMarkerTransaction = recoveryGate.activeTransactionId();
        if (markerWasActive && activeMarkerTransaction == null) {
            throw new IOException("management recovery marker is damaged");
        }
        for (File directory : directories) {
            File manifestFile = new File(directory, "manifest.json");
            if (!IoUtils.atomicFileExists(manifestFile)) {
                if (recoveryGate.isBlocked()) {
                    throw new IOException(
                        "management recovery journal is missing"
                    );
                }
                deleteRecursively(directory);
                continue;
            }
            JSONObject manifest = readManifest(directory);
            String state = manifest.optString("state", "");
            String transactionId = transactionIdFor(directory, manifest);
            if (recoveryGate.isBlocked()) {
                String active = recoveryGate.activeTransactionId();
                if (!transactionId.equals(active)
                    && STATE_COMMITTING.equals(state)) {
                    // Finish the marker owner first.  A second old
                    // COMMITTING journal can be recovered on the next pass
                    // after the active marker is cleared.
                    hadDeferredCommittingJournal = true;
                    continue;
                }
                if (!transactionId.equals(active)
                    && (STATE_PREPARED.equals(state)
                        || STATE_COMMITTED.equals(state))) {
                    // These journals do not own the active marker.  They can
                    // be discarded/retained according to their own state,
                    // but must never clear another transaction's marker.
                    if (STATE_PREPARED.equals(state)
                        || !ManagementImportSessionStore.exists(
                            context,
                            manifest.optString("session_token", "")
                        )) {
                        deleteRecursively(directory);
                    }
                    continue;
                }
            }
            if (STATE_PREPARED.equals(state)) {
                clearGateIfMatching(transactionId);
                deleteRecursively(directory);
                continue;
            }
            if (STATE_COMMITTED.equals(state)) {
                clearGateIfMatching(transactionId);
                String token = manifest.optString("session_token", "");
                if (!ManagementImportSessionStore.exists(context, token)) {
                    deleteRecursively(directory);
                }
                continue;
            }
            if (!STATE_COMMITTING.equals(state)) {
                throw new IOException(
                    "unknown management import journal state: " + state
                );
            }
            ensureGateForTransaction(transactionId);
            recoverOne(directory, manifest);
        }
        if (recoveryGate.isBlocked()) {
            throw new IOException("management recovery marker has no journal");
        }
        if (hadDeferredCommittingJournal || markerWasActive) {
            // A marker owner may have appeared after an older journal in the
            // directory listing.  Re-scan once the owner is clear so no old
            // COMMITTING journal can be mistaken for a clean startup.
            recoverLocked();
        }
    }

    private void recoverOne(File directory, JSONObject manifest)
        throws Exception {
        SceneStore.MutationAdmission.FullSyncLease lease = null;
        ManagementImportRecoveryGate.OwnerPermit ownerPermit = null;
        String transactionId = transactionIdFor(directory, manifest);
        try {
            ensureGateForTransaction(transactionId);
            ownerPermit = recoveryGate.acquireOwnerPermit(transactionId);
            lease = SceneStore.beginFullSyncAdmission(sceneStore);
            final SceneStore.MutationAdmission.FullSyncLease acquiredLease = lease;
            jobStore.withManagementMutation(() ->
                SceneContextStore.withRootAccess(() -> {
                    applyManifestLocked(manifest, acquiredLease);
                    manifest.put("state", STATE_COMMITTED);
                    writeManifest(directory, manifest);
                    return null;
                })
            );
            recoveryGate.clear(transactionId);
            ManagementImportRecoveryGate.notifyCleared(
                context.getFilesDir()
            );
            String sessionToken = manifest.optString("session_token", "");
            if (sessionToken.isEmpty()
                || !ManagementImportSessionStore.exists(context, sessionToken)) {
                deleteRecursively(directory);
            }
        } finally {
            if (lease != null) {
                lease.close();
            }
            if (ownerPermit != null) {
                ownerPermit.close();
            }
        }
    }

    /**
     * Reuses a durable transaction for a Binder retry after an unknown pipe
     * result. COMMITTED is a successful idempotent answer; COMMITTING is
     * recovered before answering. PREPARED never crossed the commit point.
     */
    private JSONObject resumeExistingSessionTransaction(String sessionToken)
        throws Exception {
        if (!transactionRoot.isDirectory()) {
            return null;
        }
        File[] directories = transactionRoot.listFiles(File::isDirectory);
        if (directories == null) {
            throw new IOException("could not enumerate management imports");
        }
        for (File directory : directories) {
            File manifestFile = new File(directory, "manifest.json");
            if (!IoUtils.atomicFileExists(manifestFile)) {
                continue;
            }
            JSONObject manifest = readManifest(directory);
            if (!sessionToken.equals(
                    manifest.optString("session_token", "")
                )) {
                continue;
            }
            String state = manifest.optString("state", "");
            String transactionId = transactionIdFor(directory, manifest);
            if (STATE_PREPARED.equals(state)) {
                clearGateIfMatching(transactionId);
                deleteRecursively(directory);
                return null;
            }
            if (STATE_COMMITTED.equals(state)) {
                clearGateIfMatching(transactionId);
                ManagementImportRecoveryGate.notifyCleared(
                    context.getFilesDir()
                );
                return new JSONObject()
                    .put("ok", true)
                    .put("state", "applied")
                    .put("transaction_id", directory.getName());
            }
            if (!STATE_COMMITTING.equals(state)) {
                throw new IOException("unknown management import journal state");
            }
            try {
                ensureGateForTransaction(transactionId);
                recoverOne(directory, manifest);
                return new JSONObject()
                    .put("ok", true)
                    .put("state", "applied")
                    .put("transaction_id", directory.getName());
            } catch (Exception failure) {
                return new JSONObject()
                    .put("ok", true)
                    .put("state", "recovery_pending")
                    .put("transaction_id", directory.getName())
                    .put("message", safeMessage(failure));
            }
        }
        return null;
    }

    private JSONObject buildSnapshotLocked(
        List<TranslationJobStore.ReviewJob> jobs
    ) throws Exception {
        JSONObject snapshot = new JSONObject();
        snapshot.put("version", ManagementImportModel.MODEL_VERSION);
        snapshot.put("scenes", new JSONObject());
        snapshot.put("contexts", new JSONObject());
        snapshot.put("groups", new JSONObject());
        snapshot.put("characters", new JSONObject());
        snapshot.put("terms", new JSONObject());
        snapshot.put("active_jobs", new JSONObject());

        JSONObject scenes = snapshot.getJSONObject("scenes");
        for (String sceneName : sceneStore.listFormalSceneNamesStrict()) {
            SceneStore.RawSceneSnapshot scene =
                sceneStore.readRawSceneSnapshot(sceneName);
            scenes.put(
                sceneName,
                new JSONObject(new String(scene.bytes, StandardCharsets.UTF_8))
            );
        }
        JSONObject contexts = snapshot.getJSONObject("contexts");
        for (JSONObject value : sceneContextStore.listContexts()) {
            String id = value.optString("id", "").trim();
            if (!id.isEmpty()) {
                contexts.put(id, copy(value));
            }
        }
        JSONObject groups = snapshot.getJSONObject("groups");
        for (JSONObject value : sceneContextStore.listGroups()) {
            String id = value.optString("id", "").trim();
            if (!id.isEmpty()) {
                groups.put(id, copy(value));
            }
        }
        ConfigStore.JsonLoadResult characterDictionary =
            configStore.loadJson(ConfigStore.CHARDICT_FILE_NAME);
        ConfigStore.JsonLoadResult termDictionary =
            configStore.loadJson(ConfigStore.GAMETERMS_FILE_NAME);
        copyObjectInto(
            snapshot.getJSONObject("characters"),
            characterDictionary.json
        );
        copyObjectInto(snapshot.getJSONObject("terms"), termDictionary.json);

        JSONObject activeJobs = snapshot.getJSONObject("active_jobs");
        if (jobs != null) {
            for (TranslationJobStore.ReviewJob job : jobs) {
                String scene = job.getScene() == null
                    ? ""
                    : job.getScene().trim();
                String requestId = job.getRequestId() == null
                    ? ""
                    : job.getRequestId().trim();
                if (scene.isEmpty() || requestId.isEmpty()) {
                    continue;
                }
                JSONArray requestIds = activeJobs.optJSONArray(scene);
                if (requestIds == null) {
                    requestIds = new JSONArray();
                    activeJobs.put(scene, requestIds);
                }
                requestIds.put(requestId);
            }
        }
        snapshot.put("fingerprint",
            ManagementImportModel.ExistingSnapshot.fingerprintOfJson(snapshot));
        return snapshot;
    }

    private JSONObject createManifest(
        String transactionId,
        String sessionToken,
        JSONObject preview,
        JSONObject currentSnapshot
    ) throws Exception {
        JSONObject manifest = new JSONObject()
            .put("version", JOURNAL_VERSION)
            .put("state", STATE_PREPARED)
            .put("transaction_id", transactionId)
            .put("session_token", sessionToken)
            .put("preview", copy(preview))
            .put("snapshot_fingerprint", currentSnapshot.optString(
                "fingerprint",
                ""
            ))
            .put("operations", journalOperations(preview, currentSnapshot))
            .put("cancelled_tasks", new JSONArray());
        return manifest;
    }

    private JSONArray journalOperations(
        JSONObject preview,
        JSONObject currentSnapshot
    ) throws Exception {
        JSONArray source = preview.optJSONArray("operations");
        if (source == null) {
            throw new IOException("import preview has no operations");
        }
        JSONArray output = new JSONArray();
        Set<String> uniqueTargets = new HashSet<>();
        for (int index = 0; index < source.length(); index++) {
            JSONObject operation = source.optJSONObject(index);
            if (operation == null) {
                throw new IOException("import operation is not an object");
            }
            String type = operation.optString("type", "").trim();
            String targetId = operation.optString("target_id", "").trim();
            String action = operation.optString("action", "").trim();
            JSONObject content = operation.optJSONObject("content");
            requireOperation(type, targetId, action, content);
            if (!"skip".equals(action)
                && !uniqueTargets.add(type + ":" + targetId)) {
                throw new IOException(
                    "duplicate import target: " + type + ":" + targetId
                );
            }
            JSONObject journal = copy(operation);
            if (!"skip".equals(action)) {
                journal.put(
                    "before_hash",
                    currentDocumentHash(currentSnapshot, type, targetId)
                );
                journal.put("after_hash", documentHash(type, content));
            }
            output.put(journal);
        }
        return output;
    }

    /**
     * Repeats every pure store validator before the durable commit intent is
     * published.  This is intentionally separate from applyManifestLocked:
     * a malformed Scene, Context/Group bundle, dictionary, or task list must
     * never be discovered after another store has already been changed.
     */
    private void validatePlanBeforeCommit(
        JSONObject preview,
        JSONArray operations
    ) throws Exception {
        validateTargetAvailabilityBeforeCommit(operations);
        JSONArray contexts = new JSONArray();
        JSONArray groups = new JSONArray();
        JSONObject characters = new JSONObject();
        JSONObject terms = new JSONObject();
        for (int index = 0; index < operations.length(); index++) {
            JSONObject operation = operations.getJSONObject(index);
            String type = operation.optString("type", "");
            String action = operation.optString("action", "");
            if ("skip".equals(action)) {
                continue;
            }
            JSONObject content = operation.getJSONObject("content");
            String targetId = operation.getString("target_id");
            if (ManagementImportModel.KIND_SCENE.equals(type)) {
                sceneStore.validateRawSceneBytes(
                    targetId,
                    content.toString().getBytes(StandardCharsets.UTF_8)
                );
            } else if (ManagementImportModel.KIND_CONTEXT.equals(type)) {
                contexts.put(copy(content));
            } else if (ManagementImportModel.KIND_GROUP.equals(type)) {
                groups.put(copy(content));
            } else if (ManagementImportModel.KIND_CHARACTER.equals(type)) {
                ConfigStore.validateCharacterRecord(targetId, content);
                characters.put(targetId, copy(content));
            } else if (ManagementImportModel.KIND_TERM.equals(type)) {
                ConfigStore.validateGameTermRecord(targetId, content);
                terms.put(targetId, copy(content));
            }
        }
        if (contexts.length() != 0 || groups.length() != 0) {
            JSONObject bundle = new JSONObject()
                .put("version", SceneContextStore.FORMAT_VERSION)
                .put("contexts", contexts)
                .put("groups", groups);
            // inspectImportBundle includes PendingProcess references and the
            // full reference graph, but performs no formal write.
            sceneContextStore.inspectImportBundle(bundle);
        }
        if (characters.length() != 0) {
            ConfigStore.validateCharacterDictionary(characters);
        }
        if (terms.length() != 0) {
            ConfigStore.validateGameTermDictionary(terms);
        }
        String taskAction = preview.optString("task_action", "keep");
        if (!"keep".equalsIgnoreCase(taskAction)
            && !"cancel".equalsIgnoreCase(taskAction)) {
            throw new IOException("unsupported import task action: " + taskAction);
        }
        JSONArray taskIds = preview.optJSONArray("task_request_ids");
        if (taskIds != null) {
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < taskIds.length(); index++) {
                String requestId = taskIds.optString(index, "").trim();
                if (requestId.isEmpty() || !seen.add(requestId)) {
                    throw new IOException("invalid or duplicate task request id");
                }
            }
        }
    }

    /**
     * Rejects only the stores touched by this batch while the durable commit
     * intent is still discardable.  Callers hold
     * {@link PendingProcessManager#POLICY_PUBLICATION_LOCK}; that lock is
     * shared with PendingProcessManager and Scene policy publication, so the
     * pending Scene-family snapshot remains valid through COMMITTING/apply.
     */
    private void validateTargetAvailabilityBeforeCommit(JSONArray operations)
        throws Exception {
        if (operations == null) {
            throw new IOException("import operations are missing");
        }
        Set<String> pendingScenes = null;
        boolean needsCharacters = false;
        boolean needsTerms = false;
        for (int index = 0; index < operations.length(); index++) {
            JSONObject operation = operations.getJSONObject(index);
            if ("skip".equals(operation.optString("action", ""))) {
                continue;
            }
            String type = operation.optString("type", "");
            if (ManagementImportModel.KIND_SCENE.equals(type)) {
                if (pendingScenes == null) {
                    pendingScenes = sceneStore
                        .snapshotManagementPendingSceneNames();
                }
                String targetId = operation.getString("target_id");
                if (pendingScenes.contains(targetId)) {
                    throw new IOException(
                        "Scene import target is management pending: "
                            + targetId
                    );
                }
            } else if (ManagementImportModel.KIND_CHARACTER.equals(type)) {
                needsCharacters = true;
            } else if (ManagementImportModel.KIND_TERM.equals(type)) {
                needsTerms = true;
            }
        }
        if (needsCharacters) {
            requireDictionaryTargetWritable(ConfigStore.CHARDICT_FILE_NAME);
        }
        if (needsTerms) {
            requireDictionaryTargetWritable(ConfigStore.GAMETERMS_FILE_NAME);
        }
    }

    private void requireDictionaryTargetWritable(String fileName)
        throws Exception {
        ConfigStore.JsonLoadResult current = configStore.loadJson(fileName);
        if (current.invalidUserOverride) {
            throw new IOException(
                "dictionary import target is unavailable: " + fileName
                    + " has an invalid user override"
            );
        }
    }

    private void applyManifestLocked(
        JSONObject manifest,
        SceneStore.MutationAdmission.FullSyncLease sceneLease
    ) throws Exception {
        JSONArray operations = manifest.optJSONArray("operations");
        if (operations == null) {
            throw new IOException("management import journal has no operations");
        }
        JSONObject current = buildSnapshotLocked(jobStore.listReviewJobs());
        applyContextGroupOperations(manifest, operations, current);
        current = buildSnapshotLocked(jobStore.listReviewJobs());
        applyDictionaryOperations(manifest, operations, current);
        applySceneOperations(manifest, operations, sceneLease);
        applyCancellationOperations(manifest);
    }

    private void applyContextGroupOperations(
        JSONObject manifest,
        JSONArray operations,
        JSONObject current
    ) throws Exception {
        JSONArray contexts = new JSONArray();
        JSONArray groups = new JSONArray();
        Map<String, SceneContextStore.ImportConflictPolicy> contextPolicies =
            new LinkedHashMap<>();
        Map<String, SceneContextStore.ImportConflictPolicy> groupPolicies =
            new LinkedHashMap<>();
        for (int index = 0; index < operations.length(); index++) {
            JSONObject operation = operations.getJSONObject(index);
            String type = operation.optString("type", "");
            String action = operation.optString("action", "");
            if ("skip".equals(action)) {
                continue;
            }
            if (ManagementImportModel.KIND_CONTEXT.equals(type)) {
                if (!isAlreadyApplied(current, operation)) {
                    contexts.put(copy(operation.getJSONObject("content")));
                    contextPolicies.put(
                        operation.getString("target_id"),
                        SceneContextStore.ImportConflictPolicy.OVERWRITE
                    );
                }
            } else if (ManagementImportModel.KIND_GROUP.equals(type)
                && !isAlreadyApplied(current, operation)) {
                groups.put(copy(operation.getJSONObject("content")));
                groupPolicies.put(
                    operation.getString("target_id"),
                    SceneContextStore.ImportConflictPolicy.OVERWRITE
                );
            }
        }
        if (contexts.length() == 0 && groups.length() == 0) {
            return;
        }
        JSONObject bundle = new JSONObject()
            .put("version", SceneContextStore.FORMAT_VERSION)
            .put("contexts", contexts)
            .put("groups", groups);
        sceneContextStore.inspectImportBundle(bundle);
        sceneContextStore.importBundle(
            bundle,
            contextPolicies,
            groupPolicies,
            SceneContextStore.ImportConflictPolicy.OVERWRITE
        );
        markComponent(manifest, "context_group");
    }

    private void applyDictionaryOperations(
        JSONObject manifest,
        JSONArray operations,
        JSONObject current
    ) throws Exception {
        Map<String, JSONObject> characterRecords = new LinkedHashMap<>();
        Map<String, JSONObject> termRecords = new LinkedHashMap<>();
        Set<String> approvedCharacters = new LinkedHashSet<>();
        Set<String> approvedTerms = new LinkedHashSet<>();
        Set<String> expectedCharacters = new LinkedHashSet<>();
        Set<String> expectedTerms = new LinkedHashSet<>();
        JSONObject currentCharacters = current.optJSONObject("characters");
        JSONObject currentTerms = current.optJSONObject("terms");
        for (int index = 0; index < operations.length(); index++) {
            JSONObject operation = operations.getJSONObject(index);
            String type = operation.optString("type", "");
            String action = operation.optString("action", "");
            if ("skip".equals(action)
                || (!ManagementImportModel.KIND_CHARACTER.equals(type)
                    && !ManagementImportModel.KIND_TERM.equals(type))
                || isAlreadyApplied(current, operation)) {
                continue;
            }
            String targetId = operation.getString("target_id");
            JSONObject content = operation.getJSONObject("content");
            if (ManagementImportModel.KIND_CHARACTER.equals(type)) {
                ConfigStore.validateCharacterRecord(targetId, content);
                characterRecords.put(targetId, copy(content));
                if ("overwrite".equals(action)) {
                    approvedCharacters.add(targetId);
                    expectedCharacters.add(targetId);
                }
            } else {
                ConfigStore.validateGameTermRecord(targetId, content);
                termRecords.put(targetId, copy(content));
                if ("overwrite".equals(action)) {
                    approvedTerms.add(targetId);
                    expectedTerms.add(targetId);
                }
            }
        }
        if (!characterRecords.isEmpty()) {
            JSONObject imported = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : characterRecords.entrySet()) {
                imported.put(entry.getKey(), entry.getValue());
            }
            configStore.mergeImportedDictionary(
                ConfigStore.CHARDICT_FILE_NAME,
                imported,
                approvedCharacters,
                expectedCharacters
            );
        }
        if (!termRecords.isEmpty()) {
            JSONObject imported = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : termRecords.entrySet()) {
                imported.put(entry.getKey(), entry.getValue());
            }
            configStore.mergeImportedDictionary(
                ConfigStore.GAMETERMS_FILE_NAME,
                imported,
                approvedTerms,
                expectedTerms
            );
        }
        if (!characterRecords.isEmpty() || !termRecords.isEmpty()) {
            markComponent(manifest, "dictionaries");
        }
    }

    private void applySceneOperations(
        JSONObject manifest,
        JSONArray operations,
        SceneStore.MutationAdmission.FullSyncLease sceneLease
    ) throws Exception {
        for (int index = 0; index < operations.length(); index++) {
            JSONObject operation = operations.getJSONObject(index);
            if (!ManagementImportModel.KIND_SCENE.equals(operation.optString("type", ""))
                || "skip".equals(operation.optString("action", ""))) {
                continue;
            }
            String sceneName = operation.getString("target_id");
            // The full-sync lease protects this read/check/write sequence.
            // Read only this target, retaining the journal's before/after check.
            JSONObject current = new JSONObject();
            JSONObject scenes = new JSONObject();
            current.put("scenes", scenes);
            if (sceneStore.listFormalSceneNamesStrict().contains(sceneName)) {
                SceneStore.RawSceneSnapshot existing = sceneStore.readRawSceneSnapshot(sceneName);
                scenes.put(sceneName, new JSONObject(new String(existing.bytes, StandardCharsets.UTF_8)));
            }
            if (isAlreadyApplied(current, operation)) {
                continue;
            }
            byte[] bytes = operation.getJSONObject("content")
                .toString().getBytes(StandardCharsets.UTF_8);
            SceneStore.RawSceneSnapshot validated =
                sceneStore.validateRawSceneBytes(sceneName, bytes);
            sceneLease.saveRawSceneSnapshot(sceneStore, validated);
        }
        markComponent(manifest, "scenes");
    }

    private void applyCancellationOperations(JSONObject manifest)
        throws Exception {
        JSONObject preview = manifest.getJSONObject("preview");
        if (!"cancel".equalsIgnoreCase(
                preview.optString("task_action", "keep")
            )) {
            markComponent(manifest, "tasks");
            return;
        }
        JSONArray ids = preview.optJSONArray("task_request_ids");
        JSONArray cancelled = manifest.optJSONArray("cancelled_tasks");
        if (cancelled == null) {
            cancelled = new JSONArray();
            manifest.put("cancelled_tasks", cancelled);
        }
        Set<String> done = new HashSet<>();
        for (int index = 0; index < cancelled.length(); index++) {
            done.add(cancelled.optString(index, ""));
        }
        if (ids != null) {
            for (int index = 0; index < ids.length(); index++) {
                String requestId = ids.optString(index, "").trim();
                if (requestId.isEmpty() || done.contains(requestId)) {
                    continue;
                }
                TranslationJobStore.CancellationResult result =
                    jobStore.requestCancellation(requestId);
                // A job which completed after preview is deliberately left
                // completed. Service-owned cancellation never forces it back.
                if (result.getDisposition()
                        != TranslationJobStore.CancellationDisposition.NOT_FOUND
                    || !done.contains(requestId)) {
                    cancelled.put(requestId);
                    done.add(requestId);
                }
                writeManifest(
                    transactionDirectory(manifest.getString("transaction_id")),
                    manifest
                );
            }
        }
        markComponent(manifest, "tasks");
    }

    private void markComponent(JSONObject manifest, String name)
        throws Exception {
        JSONObject completed = manifest.optJSONObject("completed");
        if (completed == null) {
            completed = new JSONObject();
            manifest.put("completed", completed);
        }
        completed.put(name, true);
        writeManifest(
            transactionDirectory(manifest.getString("transaction_id")),
            manifest
        );
    }

    private boolean isAlreadyApplied(
        JSONObject current,
        JSONObject operation
    ) throws Exception {
        String after = operation.optString("after_hash", "");
        if (after.isEmpty()) {
            return false;
        }
        String present = currentDocumentHash(
            current,
            operation.optString("type", ""),
            operation.optString("target_id", "")
        );
        if (after.equals(present)) {
            return true;
        }
        String before = operation.optString("before_hash", "");
        if (before.equals(present)) {
            return false;
        }
        throw new IOException(
            "management import target changed after commit intent: "
                + operation.optString("type", "") + ":"
                + operation.optString("target_id", "")
        );
    }

    private String currentDocumentHash(
        JSONObject snapshot,
        String type,
        String targetId
    ) throws Exception {
        JSONObject map = snapshot.optJSONObject(type + "s");
        if (map == null) {
            return "";
        }
        JSONObject value = map.optJSONObject(targetId);
        return value == null ? "" : documentHash(type, value);
    }

    private static String documentHash(String type, JSONObject value) {
        JSONObject normalized = copy(value);
        if (ManagementImportModel.KIND_CONTEXT.equals(type)
            || ManagementImportModel.KIND_GROUP.equals(type)) {
            normalized.remove("storage_name");
            normalized.remove("revision");
            normalized.remove("created_at");
            normalized.remove("updated_at");
        }
        return sha256(canonicalJson(normalized).getBytes(StandardCharsets.UTF_8));
    }

    /** Stable recursive JSON form used by the recovery before/after hashes. */
    private static String canonicalJson(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) {
                keys.add(iterator.next());
            }
            Collections.sort(keys);
            StringBuilder output = new StringBuilder("{");
            for (String key : keys) {
                if (output.length() > 1) {
                    output.append(',');
                }
                output.append(JSONObject.quote(key)).append(':')
                    .append(canonicalJson(object.opt(key)));
            }
            return output.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder output = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index != 0) {
                    output.append(',');
                }
                output.append(canonicalJson(array.opt(index)));
            }
            return output.append(']').toString();
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        return String.valueOf(value);
    }

    private static void requireOperation(
        String type,
        String targetId,
        String action,
        JSONObject content
    ) throws IOException {
        if (!ManagementImportModel.KIND_SCENE.equals(type)
            && !ManagementImportModel.KIND_CONTEXT.equals(type)
            && !ManagementImportModel.KIND_GROUP.equals(type)
            && !ManagementImportModel.KIND_CHARACTER.equals(type)
            && !ManagementImportModel.KIND_TERM.equals(type)) {
            throw new IOException("unsupported import operation type: " + type);
        }
        if (targetId == null || targetId.trim().isEmpty()) {
            throw new IOException("import operation target is empty");
        }
        if (!"create".equals(action)
            && !"overwrite".equals(action)
            && !"copy".equals(action)
            && !"skip".equals(action)) {
            throw new IOException("unsupported import operation action: " + action);
        }
        if (!"skip".equals(action) && content == null) {
            throw new IOException("import operation content is missing");
        }
    }

    private void requireFingerprint(
        JSONObject snapshot,
        String expected
    ) throws SnapshotChangedException {
        String actual = snapshot.optString("fingerprint", "").trim();
        if (!expected.equals(actual)) {
            throw new SnapshotChangedException(
                "管理数据在预检后发生变化，请重新预检"
            );
        }
    }

    private File transactionDirectory(String transactionId) {
        return new File(transactionRoot, transactionId);
    }

    private String durableState(File directory) {
        try {
            if (directory == null || !directory.isDirectory()) {
                return "";
            }
            JSONObject manifest = readManifest(directory);
            return manifest.optString("state", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String transactionIdFor(File directory, JSONObject manifest)
        throws IOException {
        String transactionId = manifest == null
            ? ""
            : manifest.optString("transaction_id", "").trim();
        if (transactionId.isEmpty() && directory != null) {
            transactionId = directory.getName();
        }
        if (transactionId.isEmpty()) {
            throw new IOException("management import transaction id is missing");
        }
        if (directory != null
            && !directory.getName().equals(transactionId)) {
            throw new IOException(
                "management import transaction directory does not match manifest"
            );
        }
        return transactionId;
    }

    private void ensureGateForTransaction(String transactionId)
        throws IOException {
        if (recoveryGate.isBlocked()) {
            if (!transactionId.equals(recoveryGate.activeTransactionId())) {
                throw new IOException(
                    "management recovery marker does not match transaction"
                );
            }
            return;
        }
        recoveryGate.activate(transactionId);
    }

    private void clearGateIfMatching(String transactionId) throws IOException {
        if (!recoveryGate.isBlocked()) {
            return;
        }
        if (!transactionId.equals(recoveryGate.activeTransactionId())) {
            throw new IOException(
                "management recovery marker does not match transaction"
            );
        }
        ManagementImportRecoveryGate.OwnerPermit ownerPermit =
            recoveryGate.acquireOwnerPermit(transactionId);
        try {
            recoveryGate.clear(transactionId);
        } finally {
            ownerPermit.close();
        }
        ManagementImportRecoveryGate.notifyCleared(context.getFilesDir());
    }

    private boolean clearPreparedGate(
        File transactionDirectory,
        ManagementImportRecoveryGate.OwnerPermit[] ownerPermit,
        boolean[] gateCleared
    ) {
        if (!recoveryGate.isBlocked()) {
            return true;
        }
        String transactionId = transactionDirectory == null
            ? ""
            : transactionDirectory.getName();
        try {
            if (!transactionId.equals(recoveryGate.activeTransactionId())) {
                return false;
            }
            if (ownerPermit[0] == null) {
                ownerPermit[0] = recoveryGate.acquireOwnerPermit(transactionId);
            }
            recoveryGate.clear(transactionId);
            gateCleared[0] = true;
            ManagementImportRecoveryGate.notifyCleared(context.getFilesDir());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void writeManifest(File directory, JSONObject manifest)
        throws IOException {
        if (directory == null || manifest == null) {
            throw new IOException("management import journal is unavailable");
        }
        if (!directory.isDirectory()
            && !directory.mkdirs()
            && !directory.isDirectory()) {
            throw new IOException("could not create management import journal");
        }
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_JOURNAL_BYTES) {
            throw new IOException("management import journal is too large");
        }
        AtomicFile file = new AtomicFile(new File(directory, "manifest.json"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
        } catch (Exception error) {
            file.failWrite(output);
            throw error instanceof IOException
                ? (IOException) error
                : new IOException("could not write management import journal", error);
        }
    }

    private JSONObject readManifest(File directory) throws Exception {
        File file = new File(directory, "manifest.json");
        if (!IoUtils.atomicFileExists(file)) {
            throw new IOException("management import journal is missing");
        }
        byte[] bytes;
        try (InputStream input = new AtomicFile(file).openRead()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_JOURNAL_BYTES) {
                    throw new IOException("management import journal is too large");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        JSONObject manifest = new JSONObject(
            new String(bytes, StandardCharsets.UTF_8)
        );
        if (manifest.optInt("version", -1) != JOURNAL_VERSION) {
            throw new IOException("unsupported management import journal version");
        }
        return manifest;
    }

    private static void copyObjectInto(JSONObject destination, JSONObject source)
        throws JSONException {
        if (source == null) {
            return;
        }
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            destination.put(key, copy(source.getJSONObject(key)));
        }
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new IllegalStateException("could not copy JSON", error);
        }
    }

    private static JSONObject error(String code, String message)
        throws JSONException {
        return new JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message == null ? code : message);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "导入失败" : error.getClass().getSimpleName();
        }
        return message.length() > 4096 ? message.substring(0, 4096) : message;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(bytes);
            StringBuilder output = new StringBuilder(result.length * 2);
            for (byte value : result) {
                output.append(String.format("%02x", value & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete() && file.exists()) {
            // The transaction remains discoverable if the filesystem refuses
            // cleanup; recovery can retry it on the next Service start.
        }
    }
}
