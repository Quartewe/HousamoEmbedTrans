package com.quarty.housamoembedtrans;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Batch import/export UI for schema-valid scene files. */
public final class SceneFilesActivity extends AppCompatActivity {

    private SceneStore sceneStore;
    private TextView summary;
    private TextView lastResult;
    private MaterialButton importButton;
    private MaterialButton exportButton;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<Uri> exportLauncher;
    private int sceneCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scene_files);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.scene_files_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        sceneStore = new SceneStore(this);
        summary = findViewById(R.id.tv_scene_summary);
        lastResult = findViewById(R.id.tv_scene_last_result);
        importButton = findViewById(R.id.btn_import_scenes);
        exportButton = findViewById(R.id.btn_export_scenes);
        exportButton.setEnabled(false);

        importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            this::importScenes
        );
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            this::exportScenes
        );

        importButton.setOnClickListener(view -> importLauncher.launch(new String[] {
            "application/json",
            "text/json",
            "text/plain",
            "application/octet-stream"
        }));
        exportButton.setOnClickListener(view -> exportLauncher.launch(null));
        refreshSummaryAsync();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void importScenes(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }

        setBusy(true);
        ioExecutor.execute(() -> {
            int imported = 0;
            int rejected = 0;
            StringBuilder details = new StringBuilder();
            for (Uri uri : uris) {
                try {
                    InputStream input = getContentResolver().openInputStream(uri);
                    if (input == null) {
                        throw new IOException("document could not be opened");
                    }
                    sceneStore.importScene(input);
                    imported++;
                } catch (Exception e) {
                    rejected++;
                    appendFailure(details, displayName(uri), safeMessage(e));
                }
            }

            int finalImported = imported;
            int finalRejected = rejected;
            int finalSceneCount = sceneStore.listValidScenes().size();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                updateSummary(finalSceneCount);
                setBusy(false);
                showResult(getString(
                    R.string.scene_import_result,
                    finalImported,
                    finalRejected
                ), details.toString());
            });
        });
    }

    private void exportScenes(Uri treeUri) {
        if (treeUri == null) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The one-time grant remains valid for this export even when a
            // document provider does not offer persistable permissions.
        }

        setBusy(true);
        ioExecutor.execute(() -> {
            int exported = 0;
            int failed = 0;
            StringBuilder details = new StringBuilder();
            try {
                List<SceneStore.ValidatedScene> scenes = sceneStore.listValidScenes();
                Map<String, Uri> existing = listDocuments(treeUri);
                for (SceneStore.ValidatedScene scene : scenes) {
                    try {
                        Uri documentUri = existing.get(scene.fileName);
                        if (documentUri == null) {
                            Uri parent = parentDocumentUri(treeUri);
                            documentUri = DocumentsContract.createDocument(
                                getContentResolver(),
                                parent,
                                "application/json",
                                scene.fileName
                            );
                        }
                        if (documentUri == null) {
                            throw new IOException("document provider refused the file");
                        }

                        try (OutputStream output = getContentResolver()
                            .openOutputStream(documentUri, "wt")) {
                            if (output == null) {
                                throw new IOException("document could not be opened for writing");
                            }
                            output.write(scene.bytes);
                            output.flush();
                        }
                        exported++;
                    } catch (Exception e) {
                        failed++;
                        appendFailure(details, scene.fileName, safeMessage(e));
                    }
                }
            } catch (Exception e) {
                failed++;
                appendFailure(details, getString(R.string.scene_export_folder), safeMessage(e));
            }

            int finalExported = exported;
            int finalFailed = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false);
                showResult(getString(
                    R.string.scene_export_result,
                    finalExported,
                    finalFailed
                ), details.toString());
            });
        });
    }

    private Map<String, Uri> listDocuments(Uri treeUri) throws Exception {
        String parentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentId
        );
        String[] projection = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        Map<String, Uri> documents = new HashMap<>();

        try (Cursor cursor = getContentResolver().query(
            childrenUri,
            projection,
            null,
            null,
            null
        )) {
            if (cursor == null) {
                throw new IOException("document provider returned no directory listing");
            }
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    documents.put(
                        name,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    );
                }
            }
        }
        return documents;
    }

    private static Uri parentDocumentUri(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        );
    }

    private void refreshSummaryAsync() {
        ioExecutor.execute(() -> {
            int count = sceneStore.listValidScenes().size();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    updateSummary(count);
                    setBusy(false);
                }
            });
        });
    }

    private void updateSummary(int count) {
        sceneCount = count;
        summary.setText(getString(R.string.scene_files_summary, count));
    }

    private void setBusy(boolean busy) {
        importButton.setEnabled(!busy);
        exportButton.setEnabled(!busy && sceneCount > 0);
    }

    private void showResult(String message, String details) {
        lastResult.setText(TextUtils.isEmpty(details)
            ? message
            : message + "\n\n" + details);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // Fall through to URI representation.
        }
        return uri.toString();
    }

    private static void appendFailure(StringBuilder details, String name, String reason) {
        if (details.length() > 0) {
            details.append('\n');
        }
        details.append("• ").append(name).append(": ").append(reason);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message)
            ? throwable.getClass().getSimpleName()
            : message;
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
