package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads independent dictionary JSON documents before any UI confirmation. */
public final class ManagementDictionaryTransfer {
    private ManagementDictionaryTransfer() {
    }

    public static final class Batch {
        public final JSONObject records;
        public final List<String> keys;

        private Batch(JSONObject records, List<String> keys) {
            this.records = records;
            this.keys = keys;
        }
    }

    public static Batch readAndValidate(
        Context context,
        List<Uri> uris,
        String dictionaryFileName
    ) throws Exception {
        if (context == null || uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("no dictionary documents selected");
        }
        if (!ConfigStore.CHARDICT_FILE_NAME.equals(dictionaryFileName)
            && !ConfigStore.GAMETERMS_FILE_NAME.equals(dictionaryFileName)) {
            throw new IllegalArgumentException("unsupported dictionary type");
        }
        JSONObject records = new JSONObject();
        List<String> keys = new ArrayList<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (Uri uri : uris) {
            if (uri == null) {
                throw new IOException("dictionary document URI is empty");
            }
            JSONObject source;
            try (InputStream input = context.getContentResolver()
                .openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("could not open dictionary document");
                }
                source = new JSONObject(new String(
                    readBounded(input),
                    StandardCharsets.UTF_8
                ));
            }
            if (ConfigStore.CHARDICT_FILE_NAME.equals(dictionaryFileName)) {
                ConfigStore.validateCharacterDictionary(source);
            } else {
                ConfigStore.validateGameTermDictionary(source);
            }
            java.util.Iterator<String> names = source.keys();
            while (names.hasNext()) {
                String key = names.next();
                if (sources.containsKey(key)) {
                    throw new IllegalArgumentException(
                        "duplicate dictionary key across selected files: " + key
                    );
                }
                JSONObject record = source.optJSONObject(key);
                if (record == null) {
                    throw new IllegalArgumentException(
                        "dictionary record is not an object: " + key
                    );
                }
                if (ConfigStore.CHARDICT_FILE_NAME.equals(dictionaryFileName)) {
                    ConfigStore.validateCharacterRecord(key, record);
                } else {
                    ConfigStore.validateGameTermRecord(key, record);
                }
                records.put(key, new JSONObject(record.toString()));
                keys.add(key);
                sources.put(key, uri.toString());
            }
        }
        if (ConfigStore.CHARDICT_FILE_NAME.equals(dictionaryFileName)) {
            ConfigStore.validateCharacterDictionary(records);
        } else {
            ConfigStore.validateGameTermDictionary(records);
        }
        return new Batch(records, java.util.Collections.unmodifiableList(keys));
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > ManagementTransfer.MAX_DOCUMENT_BYTES) {
                throw new IOException("dictionary document exceeds size limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
