package com.quarty.housamoembedtrans.util;

import com.quarty.housamoembedtrans.scene.store.SceneStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates persistent translation-job state and API request documents.
 *
 * <p>The request validator deliberately validates only the request fields
 * owned by the translation pipeline. Other root context fields are allowed so
 * the character, term, and future context payloads can evolve independently.
 */
public final class JobValidator {

    private static final int MAX_DEPTH = 256;
    private static final int STATE_FORMAT_VERSION = 1;
    public static final String REQUEST_SHA256_FIELD = "request_sha256";

    private static final char[] HEX_DIGITS =
        "0123456789abcdef".toCharArray();

    private JobValidator() {
        throw new AssertionError("No instances");
    }

    public static final class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class SceneAccessException extends Exception {
        public SceneAccessException(String message) {
            super(message);
        }

        public SceneAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The synchronized scene is permanently absent or was user-deleted. */
    public static final class SceneMissingException extends Exception {
        public SceneMissingException(String message) {
            super(message);
        }

        public SceneMissingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The synchronized scene exists but cannot become a valid source. */
    public static final class SceneInvalidException extends Exception {
        public SceneInvalidException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public enum StateRequestCheck {
        MATCHED,
        HASH_MISSING,
        HASH_MISMATCH
    }

    public static final class RequestText {
        private final int seq;
        private final String speaker;
        private final String text;

        private RequestText(int seq, String speaker, String text) {
            this.seq = seq;
            this.speaker = speaker;
            this.text = text;
        }

        public int getSeq() {
            return seq;
        }

        public String getSpeaker() {
            return speaker;
        }

        public String getText() {
            return text;
        }
    }

    public static final class RequestInfo {
        private final String scene;
        private final String targetLanguage;
        private final Map<Integer, RequestText> textsBySeq;
        private final List<ProtectedToken> protectedTokens;
        private final boolean targetAlreadyTranslated;

        private RequestInfo(
            String scene,
            String targetLanguage,
            Map<Integer, RequestText> textsBySeq,
            List<ProtectedToken> protectedTokens,
            boolean targetAlreadyTranslated
        ) {
            this.scene = scene;
            this.targetLanguage = targetLanguage;
            this.textsBySeq = Collections.unmodifiableMap(
                new LinkedHashMap<>(textsBySeq)
            );
            this.protectedTokens = Collections.unmodifiableList(
                new ArrayList<>(protectedTokens)
            );
            this.targetAlreadyTranslated = targetAlreadyTranslated;
        }

        public String getScene() {
            return scene;
        }

        public String getTargetLanguage() {
            return targetLanguage;
        }

        public Map<Integer, RequestText> getTextsBySeq() {
            return textsBySeq;
        }

        public int getTextCount() {
            return textsBySeq.size();
        }

        public boolean isTargetAlreadyTranslated() {
            return targetAlreadyTranslated;
        }
    }

    /**
     * Strictly parses one UTF-8 JSON object.
     *
     * <p>This overload detects malformed UTF-8 and trailing non-whitespace
     * content before a JSONObject is created.
     */
    public static JSONObject parseJsonObject(
        byte[] bytes,
        int maxBytes,
        String documentName
    ) throws ValidationException {
        String path = documentPath(documentName);
        if (bytes == null) {
            throw error(path, "content is null");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (bytes.length == 0) {
            throw error(path, "content is empty");
        }
        if (bytes.length > maxBytes) {
            throw error(
                path,
                "exceeds byte limit " + maxBytes + ": " + bytes.length
            );
        }

        String source;
        try {
            source = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new ValidationException(
                path + " is not valid UTF-8",
                e
            );
        }

        return parseJsonObject(source, documentName);
    }

    /**
     * Strictly parses exactly one JSON object from a string.
     */
    public static JSONObject parseJsonObject(
        String source,
        String documentName
    ) throws ValidationException {
        String path = documentPath(documentName);
        if (source == null) {
            throw error(path, "content is null");
        }
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        if (source.trim().isEmpty()) {
            throw error(path, "content is empty");
        }

        try {
            JSONTokener tokener = new JSONTokener(source);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONObject)) {
                throw error(path, "root must be an object");
            }
            if (tokener.nextClean() != 0) {
                throw error(path, "contains trailing content");
            }
            return (JSONObject) root;
        } catch (ValidationException e) {
            throw e;
        } catch (JSONException e) {
            throw new ValidationException(
                path + " contains invalid JSON: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Validates the current state.json shape.
     *
     * <p>queue_sequence remains optional here because service startup already
     * assigns one to pre-migration jobs. When present it must be positive.
     */
    public static void validateState(JSONObject state)
        throws ValidationException {
        if (state == null) {
            throw error("$state", "root is null");
        }

        requireNonBlankString(state, "scene", "$state");
        requireNonBlankString(state, "target_lang", "$state");

        int version = requireInt(state, "version", "$state");
        if (version != STATE_FORMAT_VERSION) {
            throw error(
                "$state.version",
                "must be " + STATE_FORMAT_VERSION + " but is " + version
            );
        }

        String status = requireNonBlankString(state, "status", "$state");
        if (!TranslationJobStatus.isValid(status)) {
            throw error(
                "$state.status",
                "has unsupported value " + quote(status)
            );
        }

        requireNonNegativeLong(state, "created_at", "$state");
        requireNonNegativeLong(state, "updated_at", "$state");

        if (state.has("started_at") && !state.isNull("started_at")) {
            requireNonNegativeLong(state, "started_at", "$state");
        }
        if (state.has("queue_sequence") && !state.isNull("queue_sequence")) {
            long queueSequence = requireLong(
                state,
                "queue_sequence",
                "$state"
            );
            if (queueSequence <= 0L) {
                throw error(
                    "$state.queue_sequence",
                    "must be positive"
                );
            }
        }
        if (state.has(REQUEST_SHA256_FIELD)) {
            String requestSha256 = requireString(
                state,
                REQUEST_SHA256_FIELD,
                "$state"
            );
            if (!isLowercaseSha256(requestSha256)) {
                throw error(
                    "$state." + REQUEST_SHA256_FIELD,
                    "must be 64 lowercase hexadecimal characters"
                );
            }
        }

        String deliveryState = state.has("delivery_state")
            && !state.isNull("delivery_state")
            ? requireString(state, "delivery_state", "$state")
            : null;
        Set<String> deliveryValues = new HashSet<>();
        deliveryValues.add("pending");
        deliveryValues.add("acknowledged");
        deliveryValues.add("not_required");
        if (deliveryState != null && !deliveryValues.contains(deliveryState)) {
            throw error(
                "$state.delivery_state",
                "has unsupported value " + quote(deliveryState)
            );
        }
        boolean terminal = TranslationJobStatus.isTerminal(status);
        if (TranslationJobStatus.RESETTING.wireValue().equals(status)) {
            if (!"not_required".equals(deliveryState)) {
                throw error(
                    "$state.delivery_state",
                    "resetting requires not_required"
                );
            }
        } else if (!terminal && deliveryState != null) {
            throw error(
                "$state.delivery_state",
                "is not allowed for non-terminal status " + quote(status)
            );
        }
        if ((TranslationJobStatus.QUEUED.wireValue().equals(status)
                || TranslationJobStatus.RUNNING.wireValue().equals(status))
            && deliveryState != null) {
            throw error(
                "$state.delivery_state",
                "must be absent while execution is active"
            );
        }
    }

    /**
     * Validates an original, complete API request and returns its ordered
     * translatable texts.
     */
    public static RequestInfo validateRequest(JSONObject request)
        throws ValidationException {
        if (request == null) {
            throw error("$request", "root is null");
        }

        String scene = requireNonBlankString(
            request,
            "scene",
            "$request"
        );
        String targetLanguage = requireNonBlankString(
            request,
            "target_lang",
            "$request"
        );

        rejectMember(request, "retry_seqs", "$request");
        rejectMember(request, "retry_feedback", "$request");
        rejectMember(request, "seq_to_order", "$request");

        JSONArray protect = requireArray(request, "protect", "$request");
        List<ProtectedToken> protectedTokens = validateProtectedTokens(
            protect,
            "$request.protect",
            false
        );

        JSONArray sceneItems = requireArray(
            request,
            "scene_items",
            "$request"
        );
        RequestCollector collector = new RequestCollector();
        validateRequestItems(
            sceneItems,
            "$request.scene_items",
            0,
            collector
        );
        if (collector.textsBySeq.isEmpty()) {
            throw error(
                "$request.scene_items",
                "does not contain any translatable text"
            );
        }

        return new RequestInfo(
            scene,
            targetLanguage,
            collector.textsBySeq,
            protectedTokens,
            false
        );
    }

    /**
     * Ensures state.json belongs to an already validated request.
     *
     * <p>A missing hash is a supported migration state. A malformed hash is
     * invalid state, while a well-formed non-matching hash means the persisted
     * request bytes changed after state.json recorded them.
     */
    public static StateRequestCheck validateStateAgainstRequest(
        JSONObject state,
        RequestInfo requestInfo,
        String actualRequestSha256
    ) throws ValidationException {
        validateState(state);
        if (requestInfo == null) {
            throw error("$request", "validated request information is null");
        }
        if (!isLowercaseSha256(actualRequestSha256)) {
            throw error(
                "$request",
                "calculated SHA-256 is invalid"
            );
        }

        String stateScene = requireNonBlankString(
            state,
            "scene",
            "$state"
        );
        if (!stateScene.equals(requestInfo.scene)) {
            throw error(
                "$state.scene",
                "does not match $request.scene"
            );
        }

        String stateLanguage = requireNonBlankString(
            state,
            "target_lang",
            "$state"
        );
        if (!stateLanguage.equals(requestInfo.targetLanguage)) {
            throw error(
                "$state.target_lang",
                "does not match $request.target_lang"
            );
        }

        if (!state.has(REQUEST_SHA256_FIELD)) {
            return StateRequestCheck.HASH_MISSING;
        }

        String storedRequestSha256 = requireString(
            state,
            REQUEST_SHA256_FIELD,
            "$state"
        );
        return storedRequestSha256.equals(actualRequestSha256)
            ? StateRequestCheck.MATCHED
            : StateRequestCheck.HASH_MISMATCH;
    }

    /**
     * Calculates the digest of the exact persisted request.json bytes.
     */
    public static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null");
        }

        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "SHA-256 is unavailable",
                e
            );
        }

        char[] encoded = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xff;
            encoded[index * 2] = HEX_DIGITS[value >>> 4];
            encoded[index * 2 + 1] = HEX_DIGITS[value & 0x0f];
        }
        return new String(encoded);
    }

    /**
     * Compares an API request with the synchronized pending scene file from
     * which it was generated.
     *
     * <p>The scene must still contain seq_to_order. Completed scene files that
     * already removed this temporary mapping are intentionally rejected.
     */
    public static RequestInfo validateRequestAgainstScene(
        JSONObject request,
        SceneStore sceneStore
    ) throws ValidationException,
        SceneAccessException,
        SceneMissingException,
        SceneInvalidException {
        RequestInfo requestInfo = validateRequest(request);
        if (sceneStore == null) {
            throw new SceneAccessException("$scene store is null");
        }

        SceneStore.ValidatedScene storedScene;
        try {
            storedScene = sceneStore.readValidSceneByName(
                requestInfo.scene
            );
        } catch (FileNotFoundException e) {
            throw new SceneMissingException(
                "$scene has no synchronized file named "
                    + quote(requestInfo.scene),
                e
            );
        } catch (IoUtils.InputLimitExceededException e) {
            throw new SceneInvalidException(
                "$scene synchronized file exceeds the size limit "
                    + quote(requestInfo.scene),
                e
            );
        } catch (IOException e) {
            throw new SceneAccessException(
                "$scene could not load synchronized scene "
                    + quote(requestInfo.scene),
                e
            );
        } catch (Exception e) {
            throw new SceneInvalidException(
                "$scene synchronized file is invalid "
                    + quote(requestInfo.scene),
                e
            );
        }
        if (storedScene == null) {
            throw new SceneMissingException(
                "$scene has no synchronized file named "
                    + quote(requestInfo.scene)
            );
        }

        final JSONObject synchronizedScene;
        final SceneInfo sceneInfo;
        try {
            synchronizedScene = parseJsonObject(
                storedScene.bytes,
                SceneStore.MAX_SCENE_BYTES,
                "scene"
            );
            sceneInfo = validateSynchronizedScene(synchronizedScene);
        } catch (ValidationException e) {
            throw new SceneInvalidException(
                "$scene synchronized file is invalid "
                    + quote(requestInfo.scene),
                e
            );
        }

        validateRequestAgainstSceneDocument(
            request,
            requestInfo,
            synchronizedScene,
            sceneInfo
        );
        return new RequestInfo(
            requestInfo.scene,
            requestInfo.targetLanguage,
            requestInfo.textsBySeq,
            requestInfo.protectedTokens,
            sceneInfo.translatedLanguages.contains(
                requestInfo.targetLanguage
            )
        );
    }

    private static void validateRequestAgainstSceneDocument(
        JSONObject request,
        RequestInfo requestInfo,
        JSONObject synchronizedScene,
        SceneInfo sceneInfo
    ) throws ValidationException {
        if (!requestInfo.scene.equals(sceneInfo.scene)) {
            throw error(
                "$request.scene",
                "does not match $scene.scene"
            );
        }
        if (requestInfo.getTextCount() != sceneInfo.texts.size()) {
            throw error(
                "$request.scene_items",
                "contains "
                    + requestInfo.getTextCount()
                    + " text item(s), but the synchronized scene contains "
                    + sceneInfo.texts.size()
            );
        }

        IntCursor seq = new IntCursor(1);
        compareSceneItems(
            requireArray(request, "scene_items", "$request"),
            requireArray(synchronizedScene, "scene_items", "$scene"),
            "$request.scene_items",
            "$scene.scene_items",
            0,
            seq
        );
        if (seq.value != requestInfo.getTextCount() + 1) {
            throw error(
                "$request.scene_items",
                "comparison ended at unexpected seq " + seq.value
            );
        }

        compareProtectedTokens(
            requestInfo.protectedTokens,
            sceneInfo.protectedTokens
        );
    }

    private static SceneInfo validateSynchronizedScene(JSONObject scene)
        throws ValidationException {
        if (scene == null) {
            throw error("$scene", "root is null");
        }

        String sceneName = requireNonBlankString(scene, "scene", "$scene");
        JSONObject translated = requireObjectMember(
            scene,
            "translated",
            "$scene"
        );
        Set<String> translatedLanguages = new HashSet<>();
        Iterator<String> translatedKeys = translated.keys();
        while (translatedKeys.hasNext()) {
            String language = translatedKeys.next();
            Object value = translated.opt(language);
            if (!(value instanceof Boolean)) {
                throw error(
                    "$scene.translated." + language,
                    "must be a boolean"
                );
            }
            if ((Boolean) value) {
                translatedLanguages.add(language);
            }
        }

        JSONArray sceneItems = requireArray(scene, "scene_items", "$scene");
        List<SceneText> texts = new ArrayList<>();
        validateStoredSceneItems(
            sceneItems,
            "$scene.scene_items",
            0,
            texts
        );
        if (texts.isEmpty()) {
            throw error(
                "$scene.scene_items",
                "does not contain any translatable text"
            );
        }

        JSONArray mappings = requireArray(scene, "seq_to_order", "$scene");
        if (mappings.length() != texts.size()) {
            throw error(
                "$scene.seq_to_order",
                "contains "
                    + mappings.length()
                    + " mapping(s), but scene_items contains "
                    + texts.size()
                    + " text item(s)"
            );
        }

        for (int index = 0; index < mappings.length(); index++) {
            String path = "$scene.seq_to_order[" + index + "]";
            JSONObject mapping = requireObject(mappings.opt(index), path);
            int seq = requireInt(mapping, "seq", path);
            int expectedSeq = index + 1;
            if (seq != expectedSeq) {
                throw error(
                    path + ".seq",
                    "must be " + expectedSeq + " but is " + seq
                );
            }

            OrderKey mappedOrder = requireOrderKey(
                mapping,
                "order",
                path
            );
            if (!mappedOrder.equals(texts.get(index).order)) {
                throw error(
                    path + ".order",
                    "does not match the text at traversal position "
                        + expectedSeq
                );
            }
        }

        JSONArray protect = requireArray(scene, "protect", "$scene");
        List<ProtectedToken> protectedTokens = validateProtectedTokens(
            protect,
            "$scene.protect",
            true
        );
        for (int index = 0; index < protectedTokens.size(); index++) {
            ProtectedToken token = protectedTokens.get(index);
            if (!containsOrder(texts, token.order)) {
                throw error(
                    "$scene.protect[" + index + "].order",
                    "does not point to a translatable text item"
                );
            }
        }

        return new SceneInfo(
            sceneName,
            texts,
            protectedTokens,
            translatedLanguages
        );
    }

    private static void validateRequestItems(
        JSONArray items,
        String path,
        int depth,
        RequestCollector collector
    ) throws ValidationException {
        checkDepth(path, depth);

        for (int index = 0; index < items.length(); index++) {
            String itemPath = path + "[" + index + "]";
            JSONObject item = requireObject(items.opt(index), itemPath);
            String type = requireString(item, "type", itemPath);

            switch (type) {
                case "text":
                    validateRequestText(item, itemPath, collector);
                    break;
                case "choice":
                    validateRequestChoice(
                        item,
                        itemPath,
                        depth + 1,
                        collector
                    );
                    break;
                case "if":
                    validateRequestIf(
                        item,
                        itemPath,
                        depth + 1,
                        collector
                    );
                    break;
                default:
                    throw error(
                        itemPath + ".type",
                        "has unsupported value " + quote(type)
                    );
            }
        }
    }

    private static void validateRequestText(
        JSONObject item,
        String path,
        RequestCollector collector
    ) throws ValidationException {
        requireExactType(item, path, "text");
        rejectMember(item, "order", path);
        rejectMember(item, "translations", path);

        int seq = requireInt(item, "seq", path);
        if (seq <= 0) {
            throw error(path + ".seq", "must be positive");
        }
        String speaker = requireString(item, "speaker", path);
        String text = requireString(item, "text", path);
        collector.add(seq, speaker, text, path);
    }

    private static void validateRequestChoice(
        JSONObject item,
        String path,
        int depth,
        RequestCollector collector
    ) throws ValidationException {
        requireExactType(item, path, "choice");
        rejectMember(item, "order", path);
        requireString(item, "merge_label", path);

        JSONArray branches = requireArray(item, "branches", path);
        requireNonEmptyArray(branches, path + ".branches");
        for (int branchIndex = 0;
             branchIndex < branches.length();
             branchIndex++) {
            String branchPath = path + ".branches[" + branchIndex + "]";
            JSONObject branch = requireObject(
                branches.opt(branchIndex),
                branchPath
            );
            requireString(branch, "target_label", branchPath);

            JSONArray options = requireArray(
                branch,
                "options",
                branchPath
            );
            requireNonEmptyArray(options, branchPath + ".options");
            for (int optionIndex = 0;
                 optionIndex < options.length();
                 optionIndex++) {
                String optionPath =
                    branchPath + ".options[" + optionIndex + "]";
                validateRequestText(
                    requireObject(options.opt(optionIndex), optionPath),
                    optionPath,
                    collector
                );
            }

            JSONArray followingText = requireArray(
                branch,
                "following_text",
                branchPath
            );
            validateRequestItems(
                followingText,
                branchPath + ".following_text",
                depth,
                collector
            );
        }
    }

    private static void validateRequestIf(
        JSONObject item,
        String path,
        int depth,
        RequestCollector collector
    ) throws ValidationException {
        requireExactType(item, path, "if");
        rejectMember(item, "order", path);
        requireString(item, "condition", path);
        requireString(item, "target_label", path);
        requireString(item, "merge_label", path);

        JSONArray followingText = requireArray(
            item,
            "following_text",
            path
        );
        validateRequestItems(
            followingText,
            path + ".following_text",
            depth,
            collector
        );
    }

    private static void validateStoredSceneItems(
        JSONArray items,
        String path,
        int depth,
        List<SceneText> texts
    ) throws ValidationException {
        checkDepth(path, depth);

        for (int index = 0; index < items.length(); index++) {
            String itemPath = path + "[" + index + "]";
            JSONObject item = requireObject(items.opt(index), itemPath);
            String type = requireString(item, "type", itemPath);

            switch (type) {
                case "text":
                    validateStoredText(
                        item,
                        itemPath,
                        texts
                    );
                    break;
                case "choice":
                    validateStoredChoice(
                        item,
                        itemPath,
                        depth + 1,
                        texts
                    );
                    break;
                case "if":
                    validateStoredIf(
                        item,
                        itemPath,
                        depth + 1,
                        texts
                    );
                    break;
                default:
                    throw error(
                        itemPath + ".type",
                        "has unsupported value " + quote(type)
                    );
            }
        }
    }

    private static void validateStoredText(
        JSONObject item,
        String path,
        List<SceneText> texts
    ) throws ValidationException {
        requireExactType(item, path, "text");
        OrderKey order = requireOrderKey(item, "order", path);
        requireString(item, "speaker", path);
        requireString(item, "text", path);
        requireObjectMember(item, "translations", path);

        texts.add(new SceneText(order));
    }

    private static void validateStoredChoice(
        JSONObject item,
        String path,
        int depth,
        List<SceneText> texts
    ) throws ValidationException {
        requireExactType(item, path, "choice");
        requireOrderKey(item, "order", path);
        requireString(item, "merge_label", path);

        JSONArray branches = requireArray(item, "branches", path);
        requireNonEmptyArray(branches, path + ".branches");
        for (int branchIndex = 0;
             branchIndex < branches.length();
             branchIndex++) {
            String branchPath = path + ".branches[" + branchIndex + "]";
            JSONObject branch = requireObject(
                branches.opt(branchIndex),
                branchPath
            );
            requireString(branch, "target_label", branchPath);

            JSONArray options = requireArray(
                branch,
                "options",
                branchPath
            );
            requireNonEmptyArray(options, branchPath + ".options");
            for (int optionIndex = 0;
                 optionIndex < options.length();
                 optionIndex++) {
                String optionPath =
                    branchPath + ".options[" + optionIndex + "]";
                validateStoredText(
                    requireObject(options.opt(optionIndex), optionPath),
                    optionPath,
                    texts
                );
            }

            JSONArray followingText = requireArray(
                branch,
                "following_text",
                branchPath
            );
            validateStoredSceneItems(
                followingText,
                branchPath + ".following_text",
                depth,
                texts
            );
        }
    }

    private static void validateStoredIf(
        JSONObject item,
        String path,
        int depth,
        List<SceneText> texts
    ) throws ValidationException {
        requireExactType(item, path, "if");
        requireOrderKey(item, "order", path);
        requireString(item, "condition", path);
        requireString(item, "target_label", path);
        requireString(item, "merge_label", path);

        JSONArray followingText = requireArray(
            item,
            "following_text",
            path
        );
        validateStoredSceneItems(
            followingText,
            path + ".following_text",
            depth,
            texts
        );
    }

    private static void compareSceneItems(
        JSONArray requestItems,
        JSONArray sceneItems,
        String requestPath,
        String scenePath,
        int depth,
        IntCursor expectedSeq
    ) throws ValidationException {
        checkDepth(requestPath, depth);
        if (requestItems.length() != sceneItems.length()) {
            throw error(
                requestPath,
                "contains "
                    + requestItems.length()
                    + " item(s), but "
                    + scenePath
                    + " contains "
                    + sceneItems.length()
            );
        }

        for (int index = 0; index < requestItems.length(); index++) {
            String requestItemPath = requestPath + "[" + index + "]";
            String sceneItemPath = scenePath + "[" + index + "]";
            JSONObject requestItem = requireObject(
                requestItems.opt(index),
                requestItemPath
            );
            JSONObject sceneItem = requireObject(
                sceneItems.opt(index),
                sceneItemPath
            );
            String requestType = requireString(
                requestItem,
                "type",
                requestItemPath
            );
            String sceneType = requireString(
                sceneItem,
                "type",
                sceneItemPath
            );
            if (!requestType.equals(sceneType)) {
                throw error(
                    requestItemPath + ".type",
                    "does not match " + sceneItemPath + ".type"
                );
            }

            switch (requestType) {
                case "text":
                    compareText(
                        requestItem,
                        sceneItem,
                        requestItemPath,
                        sceneItemPath,
                        expectedSeq
                    );
                    break;
                case "choice":
                    compareChoice(
                        requestItem,
                        sceneItem,
                        requestItemPath,
                        sceneItemPath,
                        depth + 1,
                        expectedSeq
                    );
                    break;
                case "if":
                    compareIf(
                        requestItem,
                        sceneItem,
                        requestItemPath,
                        sceneItemPath,
                        depth + 1,
                        expectedSeq
                    );
                    break;
                default:
                    throw error(
                        requestItemPath + ".type",
                        "has unsupported value " + quote(requestType)
                    );
            }
        }
    }

    private static void compareText(
        JSONObject requestText,
        JSONObject sceneText,
        String requestPath,
        String scenePath,
        IntCursor expectedSeq
    ) throws ValidationException {
        int actualSeq = requireInt(requestText, "seq", requestPath);
        if (actualSeq != expectedSeq.value) {
            throw error(
                requestPath + ".seq",
                "must be "
                    + expectedSeq.value
                    + " at this traversal position, but is "
                    + actualSeq
            );
        }

        compareStringMember(
            requestText,
            sceneText,
            "speaker",
            requestPath,
            scenePath
        );
        compareStringMember(
            requestText,
            sceneText,
            "text",
            requestPath,
            scenePath
        );
        expectedSeq.value++;
    }

    private static void compareChoice(
        JSONObject requestChoice,
        JSONObject sceneChoice,
        String requestPath,
        String scenePath,
        int depth,
        IntCursor expectedSeq
    ) throws ValidationException {
        compareStringMember(
            requestChoice,
            sceneChoice,
            "merge_label",
            requestPath,
            scenePath
        );

        JSONArray requestBranches = requireArray(
            requestChoice,
            "branches",
            requestPath
        );
        JSONArray sceneBranches = requireArray(
            sceneChoice,
            "branches",
            scenePath
        );
        if (requestBranches.length() != sceneBranches.length()) {
            throw error(
                requestPath + ".branches",
                "count does not match " + scenePath + ".branches"
            );
        }

        for (int index = 0; index < requestBranches.length(); index++) {
            String requestBranchPath =
                requestPath + ".branches[" + index + "]";
            String sceneBranchPath = scenePath + ".branches[" + index + "]";
            JSONObject requestBranch = requireObject(
                requestBranches.opt(index),
                requestBranchPath
            );
            JSONObject sceneBranch = requireObject(
                sceneBranches.opt(index),
                sceneBranchPath
            );
            compareStringMember(
                requestBranch,
                sceneBranch,
                "target_label",
                requestBranchPath,
                sceneBranchPath
            );

            JSONArray requestOptions = requireArray(
                requestBranch,
                "options",
                requestBranchPath
            );
            JSONArray sceneOptions = requireArray(
                sceneBranch,
                "options",
                sceneBranchPath
            );
            if (requestOptions.length() != sceneOptions.length()) {
                throw error(
                    requestBranchPath + ".options",
                    "count does not match " + sceneBranchPath + ".options"
                );
            }
            for (int optionIndex = 0;
                 optionIndex < requestOptions.length();
                 optionIndex++) {
                String requestOptionPath =
                    requestBranchPath + ".options[" + optionIndex + "]";
                String sceneOptionPath =
                    sceneBranchPath + ".options[" + optionIndex + "]";
                compareText(
                    requireObject(
                        requestOptions.opt(optionIndex),
                        requestOptionPath
                    ),
                    requireObject(
                        sceneOptions.opt(optionIndex),
                        sceneOptionPath
                    ),
                    requestOptionPath,
                    sceneOptionPath,
                    expectedSeq
                );
            }

            compareSceneItems(
                requireArray(
                    requestBranch,
                    "following_text",
                    requestBranchPath
                ),
                requireArray(
                    sceneBranch,
                    "following_text",
                    sceneBranchPath
                ),
                requestBranchPath + ".following_text",
                sceneBranchPath + ".following_text",
                depth,
                expectedSeq
            );
        }
    }

    private static void compareIf(
        JSONObject requestIf,
        JSONObject sceneIf,
        String requestPath,
        String scenePath,
        int depth,
        IntCursor expectedSeq
    ) throws ValidationException {
        compareStringMember(
            requestIf,
            sceneIf,
            "condition",
            requestPath,
            scenePath
        );
        compareStringMember(
            requestIf,
            sceneIf,
            "target_label",
            requestPath,
            scenePath
        );
        compareStringMember(
            requestIf,
            sceneIf,
            "merge_label",
            requestPath,
            scenePath
        );

        compareSceneItems(
            requireArray(requestIf, "following_text", requestPath),
            requireArray(sceneIf, "following_text", scenePath),
            requestPath + ".following_text",
            scenePath + ".following_text",
            depth,
            expectedSeq
        );
    }

    private static List<ProtectedToken> validateProtectedTokens(
        JSONArray tokens,
        String path,
        boolean requireOrder
    ) throws ValidationException {
        List<ProtectedToken> result = new ArrayList<>();
        Set<String> labels = new HashSet<>();

        for (int index = 0; index < tokens.length(); index++) {
            String tokenPath = path + "[" + index + "]";
            JSONObject token = requireObject(tokens.opt(index), tokenPath);
            String label = requireNonBlankString(
                token,
                "label",
                tokenPath
            );
            String origin = requireNonBlankString(
                token,
                "origin",
                tokenPath
            );
            if (!labels.add(label)) {
                throw error(
                    tokenPath + ".label",
                    "duplicates protected label " + quote(label)
                );
            }

            OrderKey order;
            if (requireOrder) {
                order = requireOrderKey(token, "order", tokenPath);
            } else {
                rejectMember(token, "order", tokenPath);
                order = null;
            }
            result.add(new ProtectedToken(label, origin, order));
        }

        return result;
    }

    private static void compareProtectedTokens(
        List<ProtectedToken> requestTokens,
        List<ProtectedToken> sceneTokens
    ) throws ValidationException {
        if (requestTokens.size() != sceneTokens.size()) {
            throw error(
                "$request.protect",
                "contains "
                    + requestTokens.size()
                    + " token(s), but $scene.protect contains "
                    + sceneTokens.size()
            );
        }

        for (int index = 0; index < requestTokens.size(); index++) {
            ProtectedToken requestToken = requestTokens.get(index);
            ProtectedToken sceneToken = sceneTokens.get(index);
            if (!requestToken.label.equals(sceneToken.label)) {
                throw error(
                    "$request.protect[" + index + "].label",
                    "does not match $scene.protect[" + index + "].label"
                );
            }
            if (!requestToken.origin.equals(sceneToken.origin)) {
                throw error(
                    "$request.protect[" + index + "].origin",
                    "does not match $scene.protect[" + index + "].origin"
                );
            }
        }
    }

    private static boolean containsOrder(
        List<SceneText> texts,
        OrderKey expected
    ) {
        for (SceneText text : texts) {
            if (text.order.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void compareStringMember(
        JSONObject left,
        JSONObject right,
        String field,
        String leftPath,
        String rightPath
    ) throws ValidationException {
        String leftValue = requireString(left, field, leftPath);
        String rightValue = requireString(right, field, rightPath);
        if (!leftValue.equals(rightValue)) {
            throw error(
                leftPath + "." + field,
                "does not match " + rightPath + "." + field
            );
        }
    }

    private static void requireExactType(
        JSONObject item,
        String path,
        String expected
    ) throws ValidationException {
        String actual = requireString(item, "type", path);
        if (!expected.equals(actual)) {
            throw error(
                path + ".type",
                "must be " + quote(expected) + " but is " + quote(actual)
            );
        }
    }

    private static void rejectMember(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        if (object.has(field)) {
            throw error(path + "." + field, "must not be present");
        }
    }

    private static JSONObject requireObjectMember(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        return requireObject(
            requireMember(object, field, path),
            path + "." + field
        );
    }

    private static JSONObject requireObject(Object value, String path)
        throws ValidationException {
        if (!(value instanceof JSONObject)) {
            throw error(path, "must be an object");
        }
        return (JSONObject) value;
    }

    private static JSONArray requireArray(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        Object value = requireMember(object, field, path);
        if (!(value instanceof JSONArray)) {
            throw error(path + "." + field, "must be an array");
        }
        return (JSONArray) value;
    }

    private static void requireNonEmptyArray(JSONArray array, String path)
        throws ValidationException {
        if (array.length() == 0) {
            throw error(path, "must not be empty");
        }
    }

    private static String requireString(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        Object value = requireMember(object, field, path);
        if (!(value instanceof String)) {
            throw error(path + "." + field, "must be a string");
        }
        return (String) value;
    }

    private static String requireNonBlankString(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        String value = requireString(object, field, path);
        if (value.trim().isEmpty()) {
            throw error(path + "." + field, "must not be blank");
        }
        return value;
    }

    private static int requireInt(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        long value = requireLong(object, field, path);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw error(path + "." + field, "is outside integer range");
        }
        return (int) value;
    }

    private static long requireNonNegativeLong(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        long value = requireLong(object, field, path);
        if (value < 0L) {
            throw error(path + "." + field, "must not be negative");
        }
        return value;
    }

    private static long requireLong(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        Object value = requireMember(object, field, path);
        if (!(value instanceof Number)) {
            throw error(path + "." + field, "must be an integer");
        }

        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw error(path + "." + field, "must be a 64-bit integer");
        }
    }

    private static OrderKey requireOrderKey(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        JSONObject order = requireObjectMember(object, field, path);
        String orderPath = path + "." + field;
        return new OrderKey(
            requireInt(order, "label_index", orderPath),
            requireInt(order, "page_no", orderPath),
            requireInt(order, "cmd_index", orderPath),
            requireInt(order, "sub_index", orderPath)
        );
    }

    private static Object requireMember(
        JSONObject object,
        String field,
        String path
    ) throws ValidationException {
        if (!object.has(field) || object.isNull(field)) {
            throw error(path + "." + field, "is required");
        }
        return object.opt(field);
    }

    private static void checkDepth(String path, int depth)
        throws ValidationException {
        if (depth > MAX_DEPTH) {
            throw error(path, "nesting is too deep");
        }
    }

    private static String documentPath(String documentName) {
        if (documentName == null || documentName.trim().isEmpty()) {
            return "$json";
        }
        return "$" + documentName.trim();
    }

    private static boolean isLowercaseSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean decimal = character >= '0' && character <= '9';
            boolean lowercaseHex =
                character >= 'a' && character <= 'f';
            if (!decimal && !lowercaseHex) {
                return false;
            }
        }
        return true;
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static ValidationException error(String path, String message) {
        return new ValidationException(path + " " + message);
    }

    private static final class RequestCollector {
        private final Map<Integer, RequestText> textsBySeq =
            new LinkedHashMap<>();
        private int expectedSeq = 1;

        private void add(
            int seq,
            String speaker,
            String text,
            String path
        ) throws ValidationException {
            if (seq != expectedSeq) {
                throw error(
                    path + ".seq",
                    "must be "
                        + expectedSeq
                        + " at this traversal position, but is "
                        + seq
                );
            }
            if (textsBySeq.put(
                    seq,
                    new RequestText(seq, speaker, text)
                ) != null) {
                throw error(path + ".seq", "duplicates seq " + seq);
            }
            expectedSeq++;
        }
    }

    private static final class SceneInfo {
        private final String scene;
        private final List<SceneText> texts;
        private final List<ProtectedToken> protectedTokens;
        private final Set<String> translatedLanguages;

        private SceneInfo(
            String scene,
            List<SceneText> texts,
            List<ProtectedToken> protectedTokens,
            Set<String> translatedLanguages
        ) {
            this.scene = scene;
            this.texts = texts;
            this.protectedTokens = protectedTokens;
            this.translatedLanguages = translatedLanguages;
        }
    }

    private static final class SceneText {
        private final OrderKey order;

        private SceneText(OrderKey order) {
            this.order = order;
        }
    }

    private static final class ProtectedToken {
        private final String label;
        private final String origin;
        private final OrderKey order;

        private ProtectedToken(
            String label,
            String origin,
            OrderKey order
        ) {
            this.label = label;
            this.origin = origin;
            this.order = order;
        }
    }

    private static final class OrderKey {
        private final int labelIndex;
        private final int pageNo;
        private final int commandIndex;
        private final int subIndex;

        private OrderKey(
            int labelIndex,
            int pageNo,
            int commandIndex,
            int subIndex
        ) {
            this.labelIndex = labelIndex;
            this.pageNo = pageNo;
            this.commandIndex = commandIndex;
            this.subIndex = subIndex;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OrderKey)) {
                return false;
            }
            OrderKey that = (OrderKey) other;
            return labelIndex == that.labelIndex
                && pageNo == that.pageNo
                && commandIndex == that.commandIndex
                && subIndex == that.subIndex;
        }

        @Override
        public int hashCode() {
            int result = labelIndex;
            result = 31 * result + pageNo;
            result = 31 * result + commandIndex;
            result = 31 * result + subIndex;
            return result;
        }
    }

    private static final class IntCursor {
        private int value;

        private IntCursor(int value) {
            this.value = value;
        }
    }
}
