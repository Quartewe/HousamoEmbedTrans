package com.quarty.housamoembedtrans.context.store;

import com.quarty.housamoembedtrans.storage.json.AtomicJsonFileIo;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Durable state for the user-controlled Context/Group summary round.
 *
 * <p>This state deliberately lives beside the strict Context/Group documents.
 * The document schemas are import/export contracts and cannot gain transient
 * closure fields without changing those contracts. Each owner has one atomic
 * sidecar, and the epoch is advanced whenever a closed round is reopened.</p>
 */
final class ManualClosureStateStore {
    static final int FORMAT_VERSION = 1;
    static final String STATUS_NONE = "none";
    static final String STATUS_OPEN = "open";
    static final String STATUS_CLOSED = "closed";

    static final class State {
        final String ownerType;
        final String ownerId;
        final String status;
        final long epoch;
        final String targetLang;
        final String cutoff;
        final String sourceHash;
        final String requestId;
        final String completedRequestId;
        final long openedAt;
        final long closedAt;
        final long updatedAt;

        State(
            String ownerType,
            String ownerId,
            String status,
            long epoch,
            String targetLang,
            String cutoff,
            String sourceHash,
            String requestId,
            String completedRequestId,
            long openedAt,
            long closedAt,
            long updatedAt
        ) {
            this.ownerType = ownerType;
            this.ownerId = ownerId;
            this.status = status;
            this.epoch = epoch;
            this.targetLang = targetLang;
            this.cutoff = cutoff;
            this.sourceHash = sourceHash;
            this.requestId = requestId;
            this.completedRequestId = completedRequestId;
            this.openedAt = openedAt;
            this.closedAt = closedAt;
            this.updatedAt = updatedAt;
        }

        boolean isOpen() {
            return STATUS_OPEN.equals(status);
        }

        boolean isClosed() {
            return STATUS_CLOSED.equals(status);
        }

        boolean isNone() {
            return STATUS_NONE.equals(status);
        }
    }

    private final File directory;
    private final AtomicJsonFileIo io;

    ManualClosureStateStore(File directory, AtomicJsonFileIo io) {
        if (directory == null || io == null) {
            throw new IllegalArgumentException("directory and io are required");
        }
        this.directory = directory;
        this.io = io;
    }

    State read(String ownerType, String ownerId)
        throws IOException, org.json.JSONException {
        File file = fileFor(ownerType, ownerId);
        if (!io.exists(file)) {
            return none(ownerType, ownerId);
        }
        JSONObject json = new JSONObject(
            new String(io.read(file), StandardCharsets.UTF_8)
        );
        if (json.optInt("version", -1) != FORMAT_VERSION
            || !ownerType.equals(json.optString("owner_type", ""))
            || !ownerId.equals(json.optString("owner_id", ""))) {
            throw new IOException("invalid manual closure sidecar: " + file);
        }
        String status = json.optString("status", STATUS_NONE);
        if (!STATUS_NONE.equals(status)
            && !STATUS_OPEN.equals(status)
            && !STATUS_CLOSED.equals(status)) {
            throw new IOException("invalid manual closure status: " + status);
        }
        long epoch = json.optLong("epoch", 0L);
        if (epoch < 0L) {
            throw new IOException("invalid manual closure epoch");
        }
        return new State(
            ownerType,
            ownerId,
            status,
            epoch,
            json.optString("target_lang", ""),
            json.optString("cutoff", ""),
            json.optString("source_hash", ""),
            json.optString("request_id", ""),
            json.optString("completed_request_id", ""),
            json.optLong("opened_at", 0L),
            json.optLong("closed_at", 0L),
            json.optLong("updated_at", 0L)
        );
    }

    void write(State state) throws IOException, org.json.JSONException {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (!directory.isDirectory()
            && !directory.mkdirs()
            && !directory.isDirectory()) {
            throw new IOException("could not create manual closure directory");
        }
        JSONObject json = new JSONObject();
        json.put("version", FORMAT_VERSION);
        json.put("owner_type", state.ownerType);
        json.put("owner_id", state.ownerId);
        json.put("status", state.status);
        json.put("epoch", state.epoch);
        json.put("target_lang", state.targetLang);
        json.put("cutoff", state.cutoff);
        json.put("source_hash", state.sourceHash);
        json.put("request_id", state.requestId);
        json.put("completed_request_id", state.completedRequestId);
        json.put("opened_at", state.openedAt);
        json.put("closed_at", state.closedAt);
        json.put("updated_at", state.updatedAt);
        io.write(
            fileFor(state.ownerType, state.ownerId),
            json.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    void delete(String ownerType, String ownerId) throws IOException {
        io.delete(fileFor(ownerType, ownerId));
    }

    static State none(String ownerType, String ownerId) {
        return new State(
            ownerType,
            ownerId,
            STATUS_NONE,
            0L,
            "",
            "",
            "",
            "",
            "",
            0L,
            0L,
            0L
        );
    }

    private File fileFor(String ownerType, String ownerId) {
        String prefix = "context".equals(ownerType) ? "context_" : "group_";
        return new File(directory, prefix + ownerId + ".json");
    }
}
