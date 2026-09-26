package com.quarty.housamoembedtrans.management.transfer;

import android.content.Context;
import android.util.AtomicFile;

import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.storage.json.JsonSchemaValidator;
import com.quarty.housamoembedtrans.ui.ManagementImportModel;
import com.quarty.housamoembedtrans.ui.ManagementImportSessionStore;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One-time local seed, run on service startup before Scene Sync opens. */
public final class BundledPresetImporter {
    private BundledPresetImporter() {}

    public static synchronized void importOnce(Context context,
                                              ManagementImportCoordinator coordinator)
        throws Exception {
        AtomicFile stateFile = new AtomicFile(new File(context.getFilesDir(),
            "bundled_preset_import.json"));
        JSONObject state = new JSONObject();
        if (stateFile.getBaseFile().exists()
            || new File(stateFile.getBaseFile().getPath() + ".bak").exists()) {
            try (InputStream input = stateFile.openRead()) {
                state = new JSONObject(new String(IoUtils.readAllBytesLimited(input, 64 * 1024),
                    StandardCharsets.UTF_8));
            }
        }
        // Deliberately not keyed to app version: upgrades and user deletions
        // must not cause the bundled examples to be restored automatically.
        if (state.optBoolean("completed", false)) return;

        String token = state.optString("session_token", "");
        if (token.isEmpty()) {
            JSONObject manifest = new JSONObject(new String(
                readAsset(context, "preset/manifest.json"), StandardCharsets.UTF_8));
            if (manifest.getInt("version") != 1) {
                throw new IOException("Unsupported bundled preset manifest version");
            }
            JsonSchemaValidator schema = new JsonSchemaValidator(new JSONObject(new String(
                readAsset(context, "schema/scene_schema.json"), StandardCharsets.UTF_8)));
            List<ManagementImportModel.Document> documents = new ArrayList<>();
            JSONArray files = manifest.getJSONArray("files");
            for (int index = 0; index < files.length(); index++) {
                String name = files.getString(index);
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new IOException("Invalid bundled preset path: " + name);
                }
                documents.add(ManagementImportModel.parseDocument(name,
                    readAsset(context, "preset/" + name), schema));
            }
            ManagementImportModel.PreparedImport prepared = ManagementImportModel.prepareImport(
                ManagementImportModel.combineImportDocuments(documents),
                ManagementImportModel.ExistingSnapshot.fromJson(coordinator.snapshot()),
                Collections.emptyMap(), ManagementImportModel.ConflictAction.SKIP,
                ManagementImportModel.TaskAction.KEEP);
            JSONObject preview = prepared.toJson();
            token = ManagementImportSessionStore.write(context, preview);
            state.put("session_token", token);
            state.put("snapshot_fingerprint", preview.getString("snapshot_fingerprint"));
            state.put("completed", false);
            // Persist before entering the coordinator's commit point. A restart
            // must resume this exact session, including an already committed one.
            writeState(stateFile, state);
        }

        JSONObject result = coordinator.applySession(token,
            state.getString("snapshot_fingerprint"));
        if (!result.optBoolean("ok") || !"applied".equals(result.optString("state"))) {
            // A pre-commit rejection may be prepared again against fresh data.
            // A durable recovery_pending result must keep its session identity.
            if (!result.optBoolean("ok")) {
                writeState(stateFile, new JSONObject());
                ManagementImportSessionStore.delete(context, token);
            }
            throw new IOException("Bundled preset import incomplete: " + result);
        }
        writeState(stateFile, new JSONObject().put("completed", true));
        ManagementImportSessionStore.delete(context, token);
        Log.i("HET-Preset", "Bundled presets imported; existing objects were preserved");
    }

    private static byte[] readAsset(Context context, String path) throws IOException {
        try (InputStream input = context.getAssets().open(path)) {
            return IoUtils.readAllBytesLimited(input, ManagementImportModel.MAX_DOCUMENT_BYTES);
        }
    }

    private static void writeState(AtomicFile file, JSONObject state) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(state.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            file.failWrite(output);
            throw error;
        }
    }
}
