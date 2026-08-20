package com.quarty.housamoembedtrans.storage;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Central archive for API results that lost write-back eligibility.
 *
 * <p>Records live under {@code files/rejected_api_results/<record_id>.json}
 * and are intentionally never auto-pruned. Each record stores
 * {@code record_id, job_kind, request_id, reason, kind, payload, created_at}.
 * Writing a record is atomic through {@link AtomicJsonFileIo}; the caller is
 * responsible for user-visible notification through the returned record.</p>
 */
public final class RejectedApiResultStore {

    public static final String DIRECTORY_NAME = "rejected_api_results";
    private static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;

    private final File directory;
    private final AtomicJsonFileIo io;

    public RejectedApiResultStore(File directory, AtomicJsonFileIo io) {
        if (directory == null || io == null) {
            throw new IllegalArgumentException(
                "directory and io are required"
            );
        }
        this.directory = directory;
        this.io = io;
    }

    public static RejectedApiResultStore createForAndroid(File directory) {
        return new RejectedApiResultStore(
            directory,
            new AndroidAtomicJsonFileIo()
        );
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * Archives one rejected API result and returns the persisted record.
     *
     * @param jobKind   translation or summary
     * @param requestId originating Translation/Summary request id
     * @param reason    stable machine reason such as {@code context_changed}
     * @param kind      whether the payload was {@code legal} or {@code illegal}
     * @param payload   raw API result content; may be any JSON value
     */
    public synchronized JSONObject archive(
        String jobKind,
        String requestId,
        String reason,
        String kind,
        Object payload
    ) throws IOException, org.json.JSONException {
        if (jobKind == null || jobKind.trim().isEmpty()) {
            throw new IllegalArgumentException("job_kind must not be empty");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("reason must not be empty");
        }
        if (kind == null || kind.trim().isEmpty()) {
            throw new IllegalArgumentException("kind must not be empty");
        }
        if (payload == null) {
            payload = JSONObject.NULL;
        }
        String recordId = UUID.randomUUID().toString();
        JSONObject record = new JSONObject()
            .put("record_id", recordId)
            .put("job_kind", jobKind)
            .put("request_id", requestId == null ? "" : requestId)
            .put("reason", reason)
            .put("kind", kind)
            .put("payload", payload)
            .put("created_at", System.currentTimeMillis());
        byte[] bytes = (record.toString(2) + "\n").getBytes(
            StandardCharsets.UTF_8
        );
        if (bytes.length > MAX_RECORD_BYTES) {
            throw new IOException(
                "rejected API result record exceeds byte limit " + recordId
            );
        }
        ensureDirectory();
        io.write(new File(directory, recordId + ".json"), bytes);
        return record;
    }

    public synchronized boolean exists(String recordId) {
        return recordId != null
            && isSafeRecordId(recordId)
            && io.exists(fileFor(recordId));
    }

    public synchronized JSONObject read(String recordId)
        throws IOException {
        if (!isSafeRecordId(recordId)) {
            throw new IOException("invalid rejected API record id: " + recordId);
        }
        File file = fileFor(recordId);
        if (!io.exists(file)) {
            throw new IOException("rejected API result does not exist: " + recordId);
        }
        byte[] bytes = io.read(file);
        if (bytes.length > MAX_RECORD_BYTES) {
            throw new IOException(
                "rejected API result exceeds byte limit: " + recordId
            );
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(
                "rejected API result is not valid JSON: " + recordId,
                e
            );
        }
    }

    public synchronized void delete(String recordId) throws IOException {
        if (!isSafeRecordId(recordId)) {
            return;
        }
        io.delete(fileFor(recordId));
    }

    public synchronized List<String> listRecordIds() {
        if (!directory.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isFile()
                && name.endsWith(".json")
                && isSafeRecordId(name.substring(0, name.length() - 5))) {
                ids.add(name.substring(0, name.length() - 5));
            }
        }
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException(
                "could not create rejected API result directory: "
                    + directory.getAbsolutePath()
            );
        }
    }

    private File fileFor(String recordId) {
        return new File(directory, recordId + ".json");
    }

    private static boolean isSafeRecordId(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!((c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
