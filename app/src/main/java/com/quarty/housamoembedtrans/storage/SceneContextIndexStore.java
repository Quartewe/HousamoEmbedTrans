package com.quarty.housamoembedtrans.storage;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stores the Scene Context Index ({@code files/scene_contexts/index.json}).
 *
 * <p>The Index owns UUID-to-fixed-internal-filename mappings and the Active
 * Context/Group pointers. Missing storage is represented by a fresh default
 * Index; an existing malformed Index is an explicit error and is never
 * silently reset.</p>
 */
public final class SceneContextIndexStore {

    public static final String FILE_NAME = "index.json";
    public static final int FORMAT_VERSION = 1;

    private final File file;
    private final ContextGroupSchemaValidator validator;
    private final AtomicJsonFileIo io;

    public SceneContextIndexStore(
        File file,
        ContextGroupSchemaValidator validator,
        AtomicJsonFileIo io
    ) {
        if (file == null || validator == null || io == null) {
            throw new IllegalArgumentException(
                "file, validator and io are required"
            );
        }
        this.file = file;
        this.validator = validator;
        this.io = io;
    }

    public File getFile() {
        return file;
    }

    public boolean exists() {
        return io.exists(file);
    }

    /** Returns the validated Index, or a fresh default when absent. */
    public JSONObject readOrCreate()
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        if (!io.exists(file)) {
            return createDefaultIndex();
        }
        JSONObject index = parseJsonObject(io.read(file), "index");
        validator.validateIndex(index);
        return index;
    }

    /** Validates and atomically writes the Index. */
    public void write(JSONObject index)
        throws IOException, ContextGroupSchemaValidator.ValidationException {
        if (index == null) {
            throw new IOException("index is null");
        }
        validator.validateIndex(index);
        io.write(file, index.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Deletes the Index; used only by tests or explicit reset tooling. */
    public void delete() throws IOException {
        io.delete(file);
    }

    static JSONObject createDefaultIndex() throws IOException {
        JSONObject index = new JSONObject();
        try {
            index.put("version", FORMAT_VERSION);
            index.put("active_context_id", JSONObject.NULL);
            index.put("active_group_id", JSONObject.NULL);
            index.put("contexts", new JSONObject());
            index.put("groups", new JSONObject());
        } catch (org.json.JSONException e) {
            throw new IOException("could not create default index", e);
        }
        return index;
    }

    private static JSONObject parseJsonObject(byte[] bytes, String name)
        throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException(name + " is empty");
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException(name + " is not valid JSON", e);
        }
    }
}
