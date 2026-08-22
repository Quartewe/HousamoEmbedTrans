package com.quarty.housamoembedtrans.storage;

import com.quarty.housamoembedtrans.util.JobValidator;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared persistent directory primitive for API job families (Translation and
 * Summary).
 *
 * <p>Each {@link PersistentApiJobStore} is bound to one physical root
 * directory, so {@code files/translation_jobs} and {@code files/summary_jobs}
 * remain independent request-id namespaces. The store owns request-id/path
 * validation, bounded reads, AtomicFile writes through {@link AtomicJsonFileIo},
 * request hashing, state file access, directory deletion and temp-file cleanup.
 * Business state machines live in the family-specific stores.</p>
 */
public final class PersistentApiJobStore {

    public static final String REQUEST_FILE_NAME = "request.json";
    public static final String STATE_FILE_NAME = "state.json";

    /** Which request-id syntax is legal for this job family. */
    public enum RequestIdFormat {
        /** Canonical lower-case UUID used by Translation jobs. */
        UUID,
        /** Lower-case 64-character SHA-256 hex used by Summary jobs. */
        SHA256_HEX
    }

    /** Family callback used by request-first crash repair. */
    @FunctionalInterface
    public interface RequestIdentityVerifier {
        void verify(String requestId, byte[] requestBytes) throws Exception;
    }

    public enum RequestFirstStatus {
        COMPLETE,
        STATE_MISSING,
        REQUEST_INVALID
    }

    /** Detached result of inspecting request.json before mutable state.json. */
    public static final class RequestFirstInspection {
        public final RequestFirstStatus status;
        public final byte[] requestBytes;
        public final JSONObject state;
        public final Exception invalidRequest;

        private RequestFirstInspection(
            RequestFirstStatus status,
            byte[] requestBytes,
            JSONObject state,
            Exception invalidRequest
        ) {
            this.status = status;
            this.requestBytes = requestBytes;
            this.state = state;
            this.invalidRequest = invalidRequest;
        }
    }

    private final File root;
    private final RequestIdFormat requestIdFormat;
    private final int maxRequestBytes;
    private final int maxStateBytes;
    private final AtomicJsonFileIo io;

    public PersistentApiJobStore(
        File root,
        RequestIdFormat requestIdFormat,
        int maxRequestBytes,
        int maxStateBytes,
        AtomicJsonFileIo io
    ) {
        if (root == null || requestIdFormat == null || io == null) {
            throw new IllegalArgumentException(
                "root, requestIdFormat and io are required"
            );
        }
        if (maxRequestBytes <= 0 || maxStateBytes <= 0) {
            throw new IllegalArgumentException(
                "maxRequestBytes and maxStateBytes must be positive"
            );
        }
        this.root = root;
        this.requestIdFormat = requestIdFormat;
        this.maxRequestBytes = maxRequestBytes;
        this.maxStateBytes = maxStateBytes;
        this.io = io;
    }

    /** Production seam backed by Android {@code AtomicFile}. */
    public static PersistentApiJobStore createForAndroid(
        File root,
        RequestIdFormat requestIdFormat,
        int maxRequestBytes,
        int maxStateBytes
    ) {
        return new PersistentApiJobStore(
            root,
            requestIdFormat,
            maxRequestBytes,
            maxStateBytes,
            new AndroidAtomicJsonFileIo()
        );
    }

    public File getRoot() {
        return root;
    }

    public RequestIdFormat getRequestIdFormat() {
        return requestIdFormat;
    }

    public void ensureRoot() throws IOException {
        if (root.isDirectory()) {
            return;
        }
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException(
                "could not create job directory " + root.getAbsolutePath()
            );
        }
    }

    public void validateRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            throw new IllegalArgumentException(
                "Request ID cannot be null or empty"
            );
        }
        switch (requestIdFormat) {
            case UUID:
                validateUuidRequestId(requestId);
                break;
            case SHA256_HEX:
                validateSha256RequestId(requestId);
                break;
            default:
                throw new IllegalStateException(
                    "unsupported request id format " + requestIdFormat
                );
        }
    }

    public boolean isValidRequestId(String requestId) {
        try {
            validateRequestId(requestId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public File jobDirectory(String requestId) {
        validateRequestId(requestId);
        return new File(root, requestId);
    }

    public boolean jobDirectoryExists(String requestId) {
        return isValidRequestId(requestId)
            && jobDirectory(requestId).isDirectory();
    }

    /** Lists only directories whose names are valid request ids, sorted. */
    public List<File> listValidJobDirectories() throws IOException {
        ensureRoot();
        File[] files = root.listFiles();
        if (files == null) {
            throw new IOException(
                "Failed to list job directory: " + root.getAbsolutePath()
            );
        }
        List<File> directories = new ArrayList<>();
        for (File file : files) {
            if (!file.isDirectory()) {
                continue;
            }
            if (isValidRequestId(file.getName())) {
                directories.add(file);
            }
        }
        Collections.sort(directories, (left, right) ->
            left.getName().compareTo(right.getName())
        );
        return directories;
    }

    /**
     * Classifies the common request-first admission crash window. A missing or
     * semantically invalid immutable request is returned as REQUEST_INVALID;
     * an I/O failure while reading an existing file is propagated so callers
     * retry instead of deleting potentially valid work.
     */
    public RequestFirstInspection inspectRequestFirst(
        File jobDirectory,
        RequestIdentityVerifier verifier
    ) throws IOException {
        if (jobDirectory == null || !jobDirectory.isDirectory()) {
            throw new IOException("job directory does not exist");
        }
        if (verifier == null) {
            throw new IllegalArgumentException("request verifier is required");
        }
        File requestFile = new File(jobDirectory, REQUEST_FILE_NAME);
        if (!io.exists(requestFile)) {
            return new RequestFirstInspection(
                RequestFirstStatus.REQUEST_INVALID,
                null,
                null,
                new IOException("request.json is missing")
            );
        }
        byte[] requestBytes = readRequest(jobDirectory);
        try {
            verifier.verify(jobDirectory.getName(), requestBytes);
        } catch (Exception e) {
            return new RequestFirstInspection(
                RequestFirstStatus.REQUEST_INVALID,
                requestBytes,
                null,
                e
            );
        }
        JSONObject state = readState(jobDirectory);
        return new RequestFirstInspection(
            state == null
                ? RequestFirstStatus.STATE_MISSING
                : RequestFirstStatus.COMPLETE,
            requestBytes,
            state,
            null
        );
    }

    public byte[] readRequest(File jobDirectory) throws IOException {
        if (jobDirectory == null || !jobDirectory.isDirectory()) {
            throw new IOException(
                "job directory does not exist: "
                    + (jobDirectory == null ? "null" : jobDirectory.getAbsolutePath())
            );
        }
        File requestFile = new File(jobDirectory, REQUEST_FILE_NAME);
        if (!io.exists(requestFile)) {
            throw new IOException(
                "Request file does not exist: " + requestFile.getAbsolutePath()
            );
        }
        byte[] bytes = io.read(requestFile);
        if (bytes.length > maxRequestBytes) {
            throw new IOException(
                "request exceeds byte limit " + maxRequestBytes
                    + ": " + requestFile.getAbsolutePath()
            );
        }
        return bytes;
    }

    public void writeRequest(File jobDirectory, byte[] bytes)
        throws IOException {
        if (jobDirectory == null || !jobDirectory.isDirectory()) {
            throw new IOException(
                "job directory does not exist: "
                    + (jobDirectory == null ? "null" : jobDirectory.getAbsolutePath())
            );
        }
        if (bytes == null) {
            throw new IOException("request bytes are null");
        }
        if (bytes.length == 0) {
            throw new IOException("request bytes are empty");
        }
        if (bytes.length > maxRequestBytes) {
            throw new IOException(
                "request exceeds byte limit " + maxRequestBytes
                    + ": " + bytes.length
            );
        }
        io.write(new File(jobDirectory, REQUEST_FILE_NAME), bytes);
    }

    /** Returns null when the state file is absent. */
    public JSONObject readState(File jobDirectory) throws IOException {
        if (jobDirectory == null || !jobDirectory.isDirectory()) {
            return null;
        }
        File stateFile = new File(jobDirectory, STATE_FILE_NAME);
        if (!io.exists(stateFile)) {
            return null;
        }
        byte[] bytes = io.read(stateFile);
        if (bytes.length > maxStateBytes) {
            throw new IOException(
                "state exceeds byte limit " + maxStateBytes
                    + ": " + stateFile.getAbsolutePath()
            );
        }
        try {
            return JobValidator.parseJsonObject(bytes, maxStateBytes, "state");
        } catch (JobValidator.ValidationException e) {
            throw new IOException(
                "Failed to parse state file: " + stateFile.getAbsolutePath(),
                e
            );
        }
    }

    public void writeState(File jobDirectory, JSONObject state)
        throws IOException {
        if (jobDirectory == null || !jobDirectory.isDirectory()) {
            throw new IOException(
                "job directory does not exist: "
                    + (jobDirectory == null ? "null" : jobDirectory.getAbsolutePath())
            );
        }
        if (state == null) {
            throw new IOException("state is null");
        }
        final byte[] stateBytes;
        try {
            stateBytes = (state.toString(2) + "\n").getBytes(
                StandardCharsets.UTF_8
            );
        } catch (org.json.JSONException e) {
            throw new IOException("could not serialize state", e);
        }
        if (stateBytes.length > maxStateBytes) {
            throw new IOException(
                "state exceeds byte limit " + maxStateBytes
                    + ": " + stateBytes.length
            );
        }
        io.write(new File(jobDirectory, STATE_FILE_NAME), stateBytes);
    }

    /**
     * Deletes a job directory recursively, including AtomicFile backups and
     * any family-specific payload/temp files. A missing directory is a no-op.
     */
    public void deleteJobDirectory(File jobDirectory) throws IOException {
        if (jobDirectory == null || !jobDirectory.exists()) {
            return;
        }
        deleteRecursively(jobDirectory);
    }

    /** SHA-256 hex of the exact persisted request bytes. */
    public static String sha256Hex(byte[] bytes) {
        return JobValidator.sha256Hex(bytes);
    }

    public static void validateRequestId(
        String requestId,
        RequestIdFormat format
    ) {
        if (format == null) {
            throw new IllegalArgumentException("request id format is required");
        }
        if (requestId == null || requestId.isEmpty()) {
            throw new IllegalArgumentException(
                "Request ID cannot be null or empty"
            );
        }
        switch (format) {
            case UUID:
                validateUuidRequestId(requestId);
                break;
            case SHA256_HEX:
                validateSha256RequestId(requestId);
                break;
            default:
                throw new IllegalStateException(
                    "unsupported request id format " + format
                );
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
            if (!file.delete() && file.exists()) {
                throw new IOException(
                    "could not delete directory: " + file.getAbsolutePath()
                );
            }
            return;
        }
        if (file.exists()) {
            io.delete(file);
        }
    }

    private static void validateUuidRequestId(String requestId) {
        try {
            UUID parsed = UUID.fromString(requestId);
            if (!parsed.toString().equals(requestId)) {
                throw new IllegalArgumentException(
                    "Request ID is not a valid UUID"
                );
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null
                && e.getMessage().startsWith("Request ID is not a valid UUID")) {
                throw e;
            }
            throw new IllegalArgumentException(
                "Request ID is not a valid UUID",
                e
            );
        }
    }

    private static void validateSha256RequestId(String requestId) {
        if (requestId.length() != 64) {
            throw new IllegalArgumentException(
                "Request ID must be a 64-character SHA-256 hex string"
            );
        }
        String lower = requestId.toLowerCase(Locale.ROOT);
        if (!lower.equals(requestId)) {
            throw new IllegalArgumentException(
                "Request ID must use lowercase hexadecimal characters"
            );
        }
        for (int index = 0; index < requestId.length(); index++) {
            char c = requestId.charAt(index);
            boolean digit = c >= '0' && c <= '9';
            boolean lowerHex = c >= 'a' && c <= 'f';
            if (!digit && !lowerHex) {
                throw new IllegalArgumentException(
                    "Request ID must use hexadecimal characters"
                );
            }
        }
    }
}
