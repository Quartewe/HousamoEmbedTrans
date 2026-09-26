package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

/**
 * Immutable, Android-independent preparation model for management transfers.
 *
 * <p>The model deliberately stops before durable mutation.  A caller provides
 * a snapshot of the real stores and receives an immutable plan carrying both
 * the source documents and the snapshot fingerprint.  A store coordinator
 * must compare that fingerprint again and commit the plan under its own
 * durable transaction protocol.</p>
 */
public final class ManagementImportModel {
    public static final int MODEL_VERSION = 1;
    public static final int MAX_DOCUMENT_BYTES =
        ManagementTransfer.MAX_DOCUMENT_BYTES;

    public static final String KIND_SCENE = "scene";
    public static final String KIND_CONTEXT = "context";
    public static final String KIND_GROUP = "group";
    public static final String KIND_CHARACTER = "character";
    public static final String KIND_TERM = "term";

    public enum ConflictAction {
        OVERWRITE,
        COPY,
        SKIP
    }

    public enum TaskAction {
        KEEP,
        CANCEL
    }

    private ManagementImportModel() {
    }

    /** One formally recognized object in one selected file. */
    public static final class Record {
        public final String kind;
        public final String canonicalId;
        public final JSONObject document;
        public final String sourceName;
        public final List<String> identityValues;

        private Record(
            String kind,
            String canonicalId,
            JSONObject document,
            String sourceName,
            List<String> identityValues
        ) {
            this.kind = kind;
            this.canonicalId = canonicalId;
            this.document = copy(document);
            this.sourceName = sourceName;
            this.identityValues = Collections.unmodifiableList(
                new ArrayList<>(identityValues)
            );
        }

        public String key() {
            return kind + ":" + canonicalId;
        }
    }

    /** A parsed file.  The original root is retained for preview/export. */
    public static final class Document {
        public final String sourceName;
        public final String type;
        public final JSONObject root;
        public final List<Record> records;
        public final String fingerprint;

        private Document(
            String sourceName,
            String type,
            JSONObject root,
            List<Record> records
        ) {
            this.sourceName = sourceName;
            this.type = type;
            this.root = copy(root);
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            this.fingerprint = sha256(
                (sourceName + "\n" + type + "\n" + this.root.toString())
                    .getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    /** Combined input after all selected files have been parsed. */
    public static final class Batch {
        public final List<Document> documents;
        public final List<Record> records;
        public final String fingerprint;

        private Batch(List<Document> documents, List<Record> records) {
            this.documents = Collections.unmodifiableList(
                new ArrayList<>(documents)
            );
            this.records = Collections.unmodifiableList(new ArrayList<>(records));
            StringBuilder value = new StringBuilder();
            for (Document document : documents) {
                value.append(document.fingerprint).append('\n');
            }
            this.fingerprint = sha256(
                value.toString().getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    /** Read-only store snapshot supplied by the real management coordinator. */
    public static final class ExistingSnapshot {
        private final Map<String, Map<String, JSONObject>> objects;
        private final Map<String, List<String>> activeJobsByScene;
        public final String fingerprint;

        private ExistingSnapshot(
            Map<String, Map<String, JSONObject>> objects,
            Map<String, List<String>> activeJobsByScene,
            String fingerprint
        ) {
            // Callers construct private maps; fromJson copies each document once.
            this.objects = objects;
            this.activeJobsByScene = copyJobMap(activeJobsByScene);
            this.fingerprint = fingerprint == null || fingerprint.trim().isEmpty()
                ? fingerprintObjects(this.objects, this.activeJobsByScene)
                : fingerprint;
        }

        public static ExistingSnapshot empty() {
            return new ExistingSnapshot(
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                null
            );
        }

        /**
         * Builds the snapshot from a coordinator-owned JSON envelope.  The
         * envelope is intentionally simple so it can cross an Activity result
         * without making the UI know store implementation classes.
         */
        public static ExistingSnapshot fromJson(JSONObject envelope) {
            return fromJson(envelope, true);
        }

        /** Hash a coordinator-owned envelope without copying its documents. */
        public static String fingerprintOfJson(JSONObject envelope) {
            return fromJson(envelope, false).fingerprint;
        }

        private static ExistingSnapshot fromJson(JSONObject envelope, boolean copyDocuments) {
            if (envelope == null) {
                return empty();
            }
            Map<String, Map<String, JSONObject>> objects = new LinkedHashMap<>();
            for (String kind : allKinds()) {
                JSONObject map = envelope.optJSONObject(kind + "s");
                if (map == null) {
                    map = envelope.optJSONObject(kind);
                }
                Map<String, JSONObject> entries = new LinkedHashMap<>();
                if (map != null) {
                    java.util.Iterator<String> keys = map.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject value = map.optJSONObject(key);
                        if (value != null) {
                            entries.put(key, copyDocuments ? copy(value) : value);
                        }
                    }
                }
                objects.put(kind, entries);
            }
            Map<String, List<String>> jobs = new LinkedHashMap<>();
            JSONObject jobMap = envelope.optJSONObject("active_jobs");
            if (jobMap != null) {
                java.util.Iterator<String> keys = jobMap.keys();
                while (keys.hasNext()) {
                    String scene = keys.next();
                    JSONArray ids = jobMap.optJSONArray(scene);
                    if (ids == null) {
                        continue;
                    }
                    List<String> requestIds = new ArrayList<>();
                    for (int index = 0; index < ids.length(); index++) {
                        String requestId = ids.optString(index, "").trim();
                        if (!requestId.isEmpty()) {
                            requestIds.add(requestId);
                        }
                    }
                    jobs.put(scene, requestIds);
                }
            }
            return new ExistingSnapshot(
                objects,
                jobs,
                copyDocuments ? envelope.optString("fingerprint", null) : null
            );
        }

        public JSONObject toJson() {
            JSONObject output = new JSONObject();
            try {
                output.put("version", MODEL_VERSION);
                output.put("fingerprint", fingerprint);
                for (String kind : allKinds()) {
                    JSONObject map = new JSONObject();
                    Map<String, JSONObject> values = objects.get(kind);
                    if (values != null) {
                        for (Map.Entry<String, JSONObject> entry : values.entrySet()) {
                            map.put(entry.getKey(), copy(entry.getValue()));
                        }
                    }
                    output.put(kind + "s", map);
                }
                JSONObject jobs = new JSONObject();
                for (Map.Entry<String, List<String>> entry
                    : activeJobsByScene.entrySet()) {
                    JSONArray values = new JSONArray();
                    for (String requestId : entry.getValue()) {
                        values.put(requestId);
                    }
                    jobs.put(entry.getKey(), values);
                }
                output.put("active_jobs", jobs);
            } catch (JSONException error) {
                throw new IllegalStateException("could not encode store snapshot", error);
            }
            return output;
        }

        private JSONObject find(String kind, String identity) {
            Map<String, JSONObject> values = objects.get(kind);
            if (values == null) {
                return null;
            }
            JSONObject direct = values.get(identity);
            if (direct != null) {
                return direct;
            }
            for (JSONObject candidate : values.values()) {
                for (String value : identityValues(kind, candidate, null)) {
                    if (identity.equals(value)) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        private Map<String, JSONObject> values(String kind) {
            Map<String, JSONObject> values = objects.get(kind);
            return values == null ? Collections.emptyMap() : values;
        }

        private Set<String> identities(String kind) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            Map<String, JSONObject> values = objects.get(kind);
            if (values == null) {
                return result;
            }
            for (Map.Entry<String, JSONObject> entry : values.entrySet()) {
                addIdentity(result, entry.getKey());
                result.addAll(identityValues(kind, entry.getValue(), null));
            }
            return result;
        }

        private List<String> jobsForScene(String sceneName) {
            List<String> values = activeJobsByScene.get(sceneName);
            return values == null ? Collections.emptyList() : values;
        }
    }

    public static final class Conflict {
        public final Record incoming;
        public final JSONObject existing;
        public final String key;
        public final List<String> activeRequestIds;
        public ConflictAction action;

        private Conflict(
            Record incoming,
            JSONObject existing,
            List<String> activeRequestIds,
            ConflictAction action
        ) {
            this.incoming = incoming;
            this.existing = existing == null ? null : copy(existing);
            this.key = incoming.key();
            this.activeRequestIds = Collections.unmodifiableList(
                new ArrayList<>(activeRequestIds)
            );
            this.action = action;
        }

        public boolean hasActiveTaskConflict() {
            return !activeRequestIds.isEmpty();
        }
    }

    public static final class Operation {
        public final Record incoming;
        public final String action;
        public final String targetId;
        public final JSONObject targetDocument;
        public final List<String> targetIdentityValues;

        private Operation(
            Record incoming,
            String action,
            String targetId,
            JSONObject targetDocument
        ) {
            this.incoming = incoming;
            this.action = action;
            this.targetId = targetId;
            this.targetDocument = copy(targetDocument);
            this.targetIdentityValues = Collections.unmodifiableList(
                identityValues(
                    incoming.kind,
                    this.targetDocument,
                    targetId
                )
            );
        }
    }

    /** Immutable plan shown in the preview and handed to the store layer. */
    public static final class PreparedImport {
        public final Batch batch;
        public final ExistingSnapshot snapshot;
        public final List<Conflict> conflicts;
        public final List<Operation> operations;
        public final TaskAction taskAction;
        public final List<String> taskRequestIds;
        public final String fingerprint;

        private PreparedImport(
            Batch batch,
            ExistingSnapshot snapshot,
            List<Conflict> conflicts,
            List<Operation> operations,
            TaskAction taskAction,
            List<String> taskRequestIds
        ) {
            this.batch = batch;
            this.snapshot = snapshot;
            this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
            this.operations = Collections.unmodifiableList(new ArrayList<>(operations));
            this.taskAction = taskAction;
            this.taskRequestIds = Collections.unmodifiableList(
                new ArrayList<>(taskRequestIds)
            );
            StringBuilder value = new StringBuilder()
                .append(batch.fingerprint).append('\n')
                .append(snapshot.fingerprint).append('\n')
                .append(taskAction).append('\n');
            for (Operation operation : operations) {
                value.append(operation.incoming.key())
                    .append(':').append(operation.action)
                    .append(':').append(operation.targetId).append('\n');
            }
            for (String requestId : taskRequestIds) {
                value.append(requestId).append('\n');
            }
            fingerprint = sha256(
                value.toString().getBytes(StandardCharsets.UTF_8)
            );
        }

        /** Full JSON preview; no field is flattened or discarded. */
        public JSONObject toJson() {
            try {
                JSONObject output = new JSONObject()
                    .put("version", MODEL_VERSION)
                    .put("format", "het-management-import-preview")
                    .put("fingerprint", fingerprint)
                    .put("batch_fingerprint", batch.fingerprint)
                    .put("snapshot_fingerprint", snapshot.fingerprint)
                    .put("task_action", taskAction.name().toLowerCase());
                JSONArray documents = new JSONArray();
                for (Document document : batch.documents) {
                    documents.put(new JSONObject()
                        .put("file", document.sourceName)
                        .put("type", document.type)
                        .put("fingerprint", document.fingerprint)
                        .put("content", copy(document.root)));
                }
                output.put("documents", documents);
                JSONArray conflictsJson = new JSONArray();
                for (Conflict conflict : conflicts) {
                    conflictsJson.put(new JSONObject()
                        .put("key", conflict.key)
                        .put("type", conflict.incoming.kind)
                        .put("source_file", conflict.incoming.sourceName)
                        .put("canonical_id", conflict.incoming.canonicalId)
                        .put("action", conflict.action.name().toLowerCase())
                        .put("active_request_ids", new JSONArray(conflict.activeRequestIds)));
                }
                output.put("conflicts", conflictsJson);
                JSONArray operationsJson = new JSONArray();
                for (Operation operation : operations) {
                    operationsJson.put(new JSONObject()
                        .put("key", operation.incoming.key())
                        .put("type", operation.incoming.kind)
                        .put("source_file", operation.incoming.sourceName)
                        .put("action", operation.action)
                        .put("target_id", operation.targetId)
                        .put("content", copy(operation.targetDocument)));
                }
                output.put("operations", operationsJson);
                output.put("task_request_ids", new JSONArray(taskRequestIds));
                return output;
            } catch (JSONException error) {
                throw new IllegalStateException("could not encode import preview", error);
            }
        }
    }

    /** Parses one selected file without touching any store or management state. */
    public static Document parseDocument(String sourceName, byte[] bytes) {
        return parseDocumentInternal(sourceName, bytes, null, false);
    }

    /**
     * Parses one selected file and, when supplied, applies the same Scene
     * schema validator used by the Scene store before the file can enter the
     * management preflight model.
     */
    public static Document parseDocument(
        String sourceName,
        byte[] bytes,
        JsonSchemaValidator sceneSchemaValidator
    ) {
        return parseDocumentInternal(
            sourceName,
            bytes,
            sceneSchemaValidator,
            true
        );
    }

    private static Document parseDocumentInternal(
        String sourceName,
        byte[] bytes,
        JsonSchemaValidator sceneSchemaValidator,
        boolean validateSceneSchema
    ) {
        String safeName = sourceName == null || sourceName.trim().isEmpty()
            ? "document.json"
            : sourceName.trim();
        if (bytes == null || bytes.length == 0) {
            throw failure(safeName, "IMPORT_EMPTY", "文件为空");
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw failure(safeName, "IMPORT_TOO_LARGE", "文件超过大小限制");
        }
        JSONObject root;
        try {
            root = new JSONObject(decodeUtf8(bytes));
        } catch (Exception error) {
            throw failure(safeName, "IMPORT_INVALID_JSON", "JSON 无法解析", error);
        }
        List<Document> expanded = parseRootDocuments(safeName, root);
        if (validateSceneSchema) {
            validateSceneDocuments(
                safeName,
                expanded,
                sceneSchemaValidator
            );
        }
        if (expanded.size() != 1) {
            List<Record> records = new ArrayList<>();
            for (Document document : expanded) {
                records.addAll(document.records);
            }
            return new Document(safeName, "bundle", root, records);
        }
        return expanded.get(0);
    }

    private static void validateSceneDocuments(
        String sourceName,
        List<Document> documents,
        JsonSchemaValidator sceneSchemaValidator
    ) {
        for (Document document : documents) {
            if (!KIND_SCENE.equals(document.type)) {
                continue;
            }
            if (sceneSchemaValidator == null) {
                throw failure(
                    sourceName,
                    "IMPORT_SCHEMA_UNAVAILABLE",
                    "Scene schema validator is unavailable"
                );
            }
            for (Record record : document.records) {
                try {
                    sceneSchemaValidator.validate(record.document);
                } catch (JsonSchemaValidator.ValidationException error) {
                    throw failure(
                        sourceName,
                        "IMPORT_SCHEMA_INVALID",
                        error.getMessage(),
                        error
                    );
                }
            }
        }
    }

    /** Combines all ready files and rejects same-type identity collisions. */
    public static Batch combineImportDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            throw failure("import", "IMPORT_EMPTY", "没有可导入文件");
        }
        List<Document> copyDocuments = new ArrayList<>();
        List<Record> records = new ArrayList<>();
        Map<String, String> identitySources = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || document.records.isEmpty()) {
                throw failure(
                    document == null ? "import" : document.sourceName,
                    "IMPORT_EMPTY",
                    "文件没有可导入对象"
                );
            }
            copyDocuments.add(document);
            for (Record record : document.records) {
                for (String identity : record.identityValues) {
                    String key = record.kind + ":" + identity;
                    String previous = identitySources.putIfAbsent(
                        key,
                        record.sourceName
                    );
                    if (previous != null) {
                        throw failure(
                            record.sourceName,
                            "IMPORT_DUPLICATE_ID",
                            "文件「" + record.sourceName + "」与文件「"
                                + previous + "」包含相同"
                                + kindLabel(record.kind) + " canonical ID「"
                                + identity + "」"
                        );
                    }
                }
                records.add(record);
            }
        }
        return new Batch(copyDocuments, records);
    }

    /**
     * Builds conflict decisions and deterministic relation mappings.  The
     * returned plan is still in memory and can be discarded without effects.
     */
    public static PreparedImport prepareImport(
        Batch batch,
        ExistingSnapshot snapshot,
        Map<String, ConflictAction> decisions,
        ConflictAction defaultAction,
        TaskAction taskAction
    ) {
        if (batch == null || batch.records.isEmpty()) {
            throw failure("import", "IMPORT_EMPTY", "没有可导入对象");
        }
        ExistingSnapshot current = snapshot == null
            ? ExistingSnapshot.empty()
            : snapshot;
        ConflictAction fallback = defaultAction == null
            ? ConflictAction.OVERWRITE
            : defaultAction;
        TaskAction chosenTaskAction = taskAction == null
            ? TaskAction.KEEP
            : taskAction;

        List<Conflict> conflicts = new ArrayList<>();
        for (Record record : batch.records) {
            JSONObject existing = findExisting(current, record);
            if (existing != null) {
                List<String> jobs = KIND_SCENE.equals(record.kind)
                    ? current.jobsForScene(record.canonicalId)
                    : Collections.emptyList();
                ConflictAction selected = decisions == null
                    ? null
                    : decisions.get(record.key());
                conflicts.add(new Conflict(
                    record,
                    existing,
                    jobs,
                    selected == null ? fallback : selected
                ));
            }
        }

        Map<String, String> sceneMap = new LinkedHashMap<>();
        Map<String, String> contextMap = new LinkedHashMap<>();
        Map<String, String> groupMap = new LinkedHashMap<>();
        Map<String, String> charMap = new LinkedHashMap<>();
        Map<String, String> termMap = new LinkedHashMap<>();
        Map<String, ConflictAction> actionByKey = new HashMap<>();
        for (Conflict conflict : conflicts) {
            actionByKey.put(conflict.key, conflict.action);
        }
        Set<String> usedByKind = new HashSet<>();
        for (String kind : allKinds()) {
            for (String identity : current.identities(kind)) {
                usedByKind.add(kind + ":" + identity);
            }
        }
        // Reserve every incoming identity before allocating a copy.  This
        // prevents an early `foo -> foo_copy` decision from colliding with a
        // later source object that already owns `foo_copy` (including a
        // character id/key alias).
        for (Record record : batch.records) {
            for (String identity : record.identityValues) {
                usedByKind.add(record.kind + ":" + identity);
            }
        }
        List<Operation> operations = new ArrayList<>();
        for (Record record : batch.records) {
            ConflictAction action = actionByKey.get(record.key());
            if (action == null) {
                action = ConflictAction.OVERWRITE;
            }
            boolean conflict = actionByKey.containsKey(record.key());
            if (!conflict) {
                action = null;
            }
            if (conflict && action == ConflictAction.SKIP) {
                mapIdentity(record.kind, record.canonicalId, record.canonicalId,
                    sceneMap, contextMap, groupMap, charMap, termMap);
                operations.add(new Operation(record, "skip", record.canonicalId,
                    record.document));
                continue;
            }
            String target = record.canonicalId;
            if (conflict && action == ConflictAction.COPY) {
                target = uniqueCopyId(record.kind, record.canonicalId, usedByKind);
            }
            usedByKind.add(record.kind + ":" + target);
            mapIdentity(record.kind, record.canonicalId, target,
                sceneMap, contextMap, groupMap, charMap, termMap);
            JSONObject targetDocument = rebindDocument(
                record,
                target,
                sceneMap,
                contextMap,
                groupMap
            );
            operations.add(new Operation(
                record,
                conflict
                    ? action.name().toLowerCase()
                    : "create",
                target,
                targetDocument
            ));
        }
        // Relation maps must be applied after every target identity is known.
        List<Operation> rebound = new ArrayList<>();
        for (Operation operation : operations) {
            if ("skip".equals(operation.action)) {
                rebound.add(operation);
                continue;
            }
            rebound.add(new Operation(
                operation.incoming,
                operation.action,
                operation.targetId,
                rebindDocument(
                    operation.incoming,
                    operation.targetId,
                    sceneMap,
                    contextMap,
                    groupMap
                )
            ));
        }

        LinkedHashSet<String> taskIds = new LinkedHashSet<>();
        if (chosenTaskAction == TaskAction.CANCEL) {
            for (Conflict conflict : conflicts) {
                if (conflict.action == ConflictAction.OVERWRITE) {
                    taskIds.addAll(conflict.activeRequestIds);
                }
            }
        }
        return new PreparedImport(
            batch,
            current,
            conflicts,
            rebound,
            chosenTaskAction,
            new ArrayList<>(taskIds)
        );
    }

    /** Validates a JSON envelope returned by an Activity result. */
    public static void validatePreviewEnvelope(JSONObject value) {
        if (value == null
            || !"het-management-import-preview".equals(
                value.optString("format", "")
            )
            || value.optInt("version", -1) != MODEL_VERSION) {
            throw failure("import", "IMPORT_PREVIEW_INVALID", "导入预览已失效");
        }
        // The store coordinator should receive the original in-memory plan;
        // this guard is intentionally only a wire-level validation helper.
        if (value.optString("fingerprint", "").trim().isEmpty()
            || value.optJSONArray("documents") == null
            || value.optJSONArray("operations") == null) {
            throw failure("import", "IMPORT_PREVIEW_INVALID", "导入预览字段不完整");
        }
    }

    private static List<Document> parseRootDocuments(
        String sourceName,
        JSONObject root
    ) {
        String format = root.optString("format", "");
        if ("het-management".equals(format)
            || "het-management-transfer".equals(format)) {
            if (root.optInt("version", -1) < 1) {
                throw failure(sourceName, "IMPORT_INVALID_VERSION", "文件版本不受支持");
            }
            String declared = root.optString("type", "");
            String declaredKind = declared == null ? "" : declared.trim();
            boolean typedEnvelope = !declaredKind.isEmpty()
                && !"bundle".equalsIgnoreCase(declaredKind)
                && !"het-management-transfer".equalsIgnoreCase(declaredKind);
            if (typedEnvelope) {
                declaredKind = canonicalKind(declaredKind);
            }
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                return parseItemsEnvelope(sourceName, root, declared, items);
            }
            List<Document> output = new ArrayList<>();
            for (String kind : allKinds()) {
                JSONArray array = root.optJSONArray(kind + "s");
                if (array == null) {
                    continue;
                }
                if (typedEnvelope && !declaredKind.equals(kind)) {
                    throw failure(
                        sourceName,
                        "IMPORT_DECLARATION_MISMATCH",
                        "文件声明类型「" + declared + "」与数组类型「"
                            + kind + "」不一致"
                    );
                }
                output.add(parseArrayDocument(sourceName, kind, root, array));
            }
            if (output.isEmpty()) {
                throw failure(sourceName, "IMPORT_UNKNOWN_TYPE", "无法识别正式导入类型");
            }
            return output;
        }
        if (root.has("scene") || root.has("scene_items")) {
            return Collections.singletonList(
                parseSingleDocument(sourceName, KIND_SCENE, root)
            );
        }
        // A formal Group also has a root-level contexts array.  Check the
        // single entity shape before the old snapshot array shape.
        if (root.has("id") && root.has("contexts")) {
            return Collections.singletonList(
                parseSingleDocument(sourceName, KIND_GROUP, root)
            );
        }
        if (root.has("id") && root.has("scenes")) {
            return Collections.singletonList(
                parseSingleDocument(sourceName, KIND_CONTEXT, root)
            );
        }
        if (root.has("contexts") && root.has("groups")
            && root.optJSONArray("contexts") != null
            && root.optJSONArray("groups") != null) {
            List<Document> output = new ArrayList<>();
            output.add(parseArrayDocument(
                sourceName,
                KIND_CONTEXT,
                root,
                root.optJSONArray("contexts")
            ));
            output.add(parseArrayDocument(
                sourceName,
                KIND_GROUP,
                root,
                root.optJSONArray("groups")
            ));
            return output;
        }
        if (root.has("contexts") && root.optJSONArray("contexts") != null) {
            return Collections.singletonList(
                parseArrayDocument(sourceName, KIND_CONTEXT, root,
                    root.optJSONArray("contexts"))
            );
        }
        if (root.has("groups") && root.optJSONArray("groups") != null) {
            return Collections.singletonList(
                parseArrayDocument(sourceName, KIND_GROUP, root,
                    root.optJSONArray("groups"))
            );
        }
        if (looksLikeDictionaryFile(sourceName, root, KIND_CHARACTER)) {
            return Collections.singletonList(
                parseDictionaryDocument(sourceName, KIND_CHARACTER, root)
            );
        }
        if (looksLikeDictionaryFile(sourceName, root, KIND_TERM)) {
            return Collections.singletonList(
                parseDictionaryDocument(sourceName, KIND_TERM, root)
            );
        }
        throw failure(sourceName, "IMPORT_UNKNOWN_TYPE", "无法识别正式导入类型");
    }

    private static List<Document> parseItemsEnvelope(
        String sourceName,
        JSONObject root,
        String declared,
        JSONArray items
    ) {
        if (items.length() == 0) {
            throw failure(sourceName, "IMPORT_EMPTY", "文件没有可导入对象");
        }
        String declaredKind = declared == null ? "" : declared.trim();
        if (!declaredKind.isEmpty()
            && !"bundle".equalsIgnoreCase(declaredKind)
            && !"het-management-transfer".equalsIgnoreCase(declaredKind)) {
            declaredKind = canonicalKind(declaredKind);
        }
        Map<String, List<Record>> byKind = new LinkedHashMap<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                throw failure(sourceName, "IMPORT_INVALID_OBJECT", "items 含有非对象值");
            }
            String kind = canonicalKind(item.optString("kind", declaredKind));
            JSONObject payload = item.optJSONObject("payload");
            if (payload == null) {
                payload = item;
            }
            if (!declaredKind.isEmpty()
                && !"bundle".equalsIgnoreCase(declaredKind)
                && !"het-management-transfer".equalsIgnoreCase(declaredKind)
                && !declaredKind.equals(kind)) {
                throw failure(
                    sourceName,
                    "IMPORT_DECLARATION_MISMATCH",
                    "文件声明类型「" + declared + "」与对象类型「"
                        + kind + "」不一致"
                );
            }
            Record record = parseRecord(sourceName, kind, payload, null);
            byKind.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(record);
        }
        List<Document> output = new ArrayList<>();
        for (Map.Entry<String, List<Record>> entry : byKind.entrySet()) {
            output.add(new Document(sourceName, entry.getKey(), root, entry.getValue()));
        }
        return output;
    }

    private static Document parseSingleDocument(
        String sourceName,
        String kind,
        JSONObject root
    ) {
        Record record = parseRecord(sourceName, kind, root, null);
        return new Document(sourceName, kind, root,
            Collections.singletonList(record));
    }

    private static Document parseArrayDocument(
        String sourceName,
        String kind,
        JSONObject root,
        JSONArray values
    ) {
        if (values == null || values.length() == 0) {
            throw failure(sourceName, "IMPORT_EMPTY", "文件没有可导入对象");
        }
        List<Record> records = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) {
                throw failure(sourceName, "IMPORT_INVALID_OBJECT", "数组含有非对象值");
            }
            records.add(parseRecord(sourceName, kind, value, null));
        }
        return new Document(sourceName, kind, root, records);
    }

    private static Document parseDictionaryDocument(
        String sourceName,
        String kind,
        JSONObject root
    ) {
        if (root.length() == 0) {
            throw failure(sourceName, "IMPORT_EMPTY", "词典文件为空");
        }
        List<Record> records = new ArrayList<>();
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject value = root.optJSONObject(key);
            if (value == null) {
                throw failure(sourceName, "IMPORT_INVALID_OBJECT", "词典条目必须是对象");
            }
            records.add(parseRecord(sourceName, kind, value, key));
        }
        if (records.isEmpty()) {
            throw failure(sourceName, "IMPORT_EMPTY", "词典文件为空");
        }
        return new Document(sourceName, kind, root, records);
    }

    private static Record parseRecord(
        String sourceName,
        String kind,
        JSONObject document,
        String dictionaryKey
    ) {
        String canonical = dictionaryKey;
        if (canonical == null || canonical.trim().isEmpty()) {
            if (KIND_SCENE.equals(kind)) {
                canonical = document.optString("scene", "");
            } else {
                canonical = document.optString("id", "");
            }
        }
        canonical = canonical == null ? "" : canonical.trim();
        if (canonical.isEmpty()) {
            throw failure(sourceName, "IMPORT_INVALID_ID", "对象缺少 canonical ID");
        }
        List<String> identities = identityValues(kind, document, canonical);
        return new Record(kind, canonical, document, sourceName, identities);
    }

    private static boolean looksLikeDictionaryFile(
        String sourceName,
        JSONObject root,
        String expected
    ) {
        String base = sourceName == null ? "" : sourceName.toLowerCase();
        if (KIND_CHARACTER.equals(expected)
            && base.endsWith("chardict.json")) {
            return true;
        }
        if (KIND_TERM.equals(expected)
            && base.endsWith("gameterms.json")) {
            return true;
        }
        if (root.length() == 0) {
            return false;
        }
        boolean character = true;
        boolean term = true;
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            JSONObject value = root.optJSONObject(keys.next());
            if (value == null) {
                return false;
            }
            character &= value.has("name") || value.has("alias")
                || value.has("relationships") || value.has("speech_style");
            term &= value.has("en") || value.has("zh-cn")
                || value.has("zh-tw") || value.has("description");
        }
        return KIND_CHARACTER.equals(expected) ? character : term;
    }

    private static String canonicalKind(String kind) {
        if (kind == null) {
            throw failure("import", "IMPORT_UNKNOWN_TYPE", "对象类型为空");
        }
        String normalized = kind.trim().toLowerCase();
        if ("scenes".equals(normalized)) return KIND_SCENE;
        if ("contexts".equals(normalized)) return KIND_CONTEXT;
        if ("groups".equals(normalized)) return KIND_GROUP;
        if ("characters".equals(normalized)) return KIND_CHARACTER;
        if ("terms".equals(normalized)) return KIND_TERM;
        if (KIND_SCENE.equals(normalized)
            || KIND_CONTEXT.equals(normalized)
            || KIND_GROUP.equals(normalized)
            || KIND_CHARACTER.equals(normalized)
            || KIND_TERM.equals(normalized)) {
            return normalized;
        }
        throw failure("import", "IMPORT_UNKNOWN_TYPE", "对象类型不受支持: " + kind);
    }

    private static JSONObject findExisting(
        ExistingSnapshot snapshot,
        Record record
    ) {
        for (String identity : record.identityValues) {
            JSONObject existing = snapshot.find(record.kind, identity);
            if (existing != null) {
                return existing;
            }
        }
        return null;
    }

    private static String uniqueCopyId(
        String kind,
        String source,
        Set<String> used
    ) {
        String base = source + "_copy";
        String candidate = base;
        int suffix = 2;
        while (used.contains(kind + ":" + candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static void mapIdentity(
        String kind,
        String source,
        String target,
        Map<String, String> scenes,
        Map<String, String> contexts,
        Map<String, String> groups,
        Map<String, String> characters,
        Map<String, String> terms
    ) {
        switch (kind) {
            case KIND_SCENE:
                scenes.put(source, target);
                break;
            case KIND_CONTEXT:
                contexts.put(source, target);
                break;
            case KIND_GROUP:
                groups.put(source, target);
                break;
            case KIND_CHARACTER:
                characters.put(source, target);
                break;
            case KIND_TERM:
                terms.put(source, target);
                break;
            default:
                throw new IllegalArgumentException("unsupported import kind");
        }
    }

    private static JSONObject rebindDocument(
        Record record,
        String targetId,
        Map<String, String> sceneMap,
        Map<String, String> contextMap,
        Map<String, String> groupMap
    ) {
        JSONObject target = copy(record.document);
        try {
            if (KIND_SCENE.equals(record.kind)) {
                target.put("scene", targetId);
            } else if (KIND_CONTEXT.equals(record.kind)) {
                target.put("id", targetId);
                JSONArray scenes = target.optJSONArray("scenes");
                if (scenes != null) {
                    for (int index = 0; index < scenes.length(); index++) {
                        JSONObject entry = scenes.optJSONObject(index);
                        if (entry == null) continue;
                        String source = entry.optString("scene", "");
                        if (sceneMap.containsKey(source)) {
                            entry.put("scene", sceneMap.get(source));
                        }
                    }
                }
            } else if (KIND_GROUP.equals(record.kind)) {
                target.put("id", targetId);
                JSONArray contexts = target.optJSONArray("contexts");
                if (contexts != null) {
                    for (int index = 0; index < contexts.length(); index++) {
                        JSONObject entry = contexts.optJSONObject(index);
                        if (entry == null) continue;
                        String source = entry.optString("context_id", "");
                        if (contextMap.containsKey(source)) {
                            entry.put("context_id", contextMap.get(source));
                        }
                    }
                }
            } else if (KIND_CHARACTER.equals(record.kind)
                && target.has("id")) {
                target.put("id", targetId);
                if (target.has("key")) target.put("key", targetId);
            }
        } catch (JSONException error) {
            throw new IllegalStateException("could not rebind import relations", error);
        }
        return target;
    }

    private static List<String> identityValues(
        String kind,
        JSONObject document,
        String fallback
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (fallback != null && !fallback.trim().isEmpty()) {
            values.add(fallback.trim());
        }
        if (document != null) {
            if (KIND_SCENE.equals(kind)) {
                addIdentity(values, document.optString("scene", ""));
            } else if (KIND_CHARACTER.equals(kind)
                || KIND_TERM.equals(kind)) {
                addIdentity(values, document.optString("id", ""));
                addIdentity(values, document.optString("key", ""));
            } else {
                addIdentity(values, document.optString("id", ""));
            }
        }
        return new ArrayList<>(values);
    }

    private static void addIdentity(Set<String> values, String value) {
        if (value != null && !value.trim().isEmpty()) {
            values.add(value.trim());
        }
    }

    private static String primaryIdentity(String kind, JSONObject document) {
        List<String> values = identityValues(kind, document, null);
        return values.isEmpty() ? "" : values.get(0);
    }

    private static Map<String, List<String>> copyJobMap(
        Map<String, List<String>> source
    ) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                result.put(
                    entry.getKey(),
                    Collections.unmodifiableList(
                        new ArrayList<>(entry.getValue() == null
                            ? Collections.emptyList()
                            : entry.getValue())
                    )
                );
            }
        }
        return result;
    }

    private static String fingerprintObjects(
        Map<String, Map<String, JSONObject>> objects,
        Map<String, List<String>> jobs
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String kind : allKinds()) {
                updateFingerprint(digest, kind + "\n");
                Map<String, JSONObject> entries = objects.get(kind);
                if (entries != null) {
                    List<String> keys = new ArrayList<>(entries.keySet());
                    Collections.sort(keys);
                    for (String key : keys) {
                        updateFingerprint(digest, key + "=");
                        updateFingerprint(digest, String.valueOf(entries.get(key)));
                        updateFingerprint(digest, "\n");
                    }
                }
            }
            if (jobs != null) {
                List<String> scenes = new ArrayList<>(jobs.keySet());
                Collections.sort(scenes);
                for (String scene : scenes) {
                    updateFingerprint(digest, "job:" + scene + "=" + jobs.get(scene) + "\n");
                }
            }
            StringBuilder output = new StringBuilder(64);
            for (byte item : digest.digest()) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void updateFingerprint(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> allKinds() {
        List<String> kinds = new ArrayList<>();
        kinds.add(KIND_SCENE);
        kinds.add(KIND_CONTEXT);
        kinds.add(KIND_GROUP);
        kinds.add(KIND_CHARACTER);
        kinds.add(KIND_TERM);
        return kinds;
    }

    private static String kindLabel(String kind) {
        if (KIND_SCENE.equals(kind)) return "Scene";
        if (KIND_CONTEXT.equals(kind)) return "Context";
        if (KIND_GROUP.equals(kind)) return "Group";
        if (KIND_CHARACTER.equals(kind)) return "角色";
        if (KIND_TERM.equals(kind)) return "术语";
        return kind;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
        return chars.toString();
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("JSON object cannot be copied", error);
        }
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static IllegalArgumentException failure(
        String file,
        String code,
        String message
    ) {
        return failure(file, code, message, null);
    }

    private static IllegalArgumentException failure(
        String file,
        String code,
        String message,
        Throwable cause
    ) {
        String detail = code + " [" + file + "]: " + message;
        return cause == null
            ? new IllegalArgumentException(detail)
            : new IllegalArgumentException(detail, cause);
    }
}
