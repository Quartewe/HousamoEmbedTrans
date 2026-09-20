package com.quarty.housamoembedtrans.storage.config;

import android.util.AtomicFile;
import com.quarty.housamoembedtrans.util.IoUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Bundled/user reconciliation. Caller owns ConfigStore and import-recovery locks. */
public final class CharacterDictionaryUpdates {
    public static final class Conflict {
        public final String name;
        public final JSONObject user;
        public final JSONObject bundled;
        public final List<String> fields;
        Conflict(String name, JSONObject user, JSONObject bundled, List<String> fields) {
            this.name = name;
            this.user = user;
            this.bundled = bundled;
            this.fields = fields;
        }
    }

    public static final class Snapshot {
        public final JSONObject dictionary;
        public final List<Conflict> conflicts;
        public final boolean userOverride;
        public final boolean invalidUserOverride;
        final JSONObject baseline;
        Snapshot(JSONObject dictionary, JSONObject baseline, List<Conflict> conflicts,
                 boolean userOverride, boolean invalid) {
            this.dictionary = dictionary;
            this.baseline = baseline;
            this.conflicts = conflicts;
            this.userOverride = userOverride;
            this.invalidUserOverride = invalid;
        }
    }

    private final File userFile;
    private final File stateFile;

    CharacterDictionaryUpdates(File userFile) {
        this.userFile = userFile;
        this.stateFile = new File(userFile.getParentFile(), "chardict-update-state.json");
    }

    Snapshot load(JSONObject bundled, Set<String> excluded) throws Exception {
        JSONObject state = recover();
        JSONObject baseline = state.optJSONObject("baseline");
        if (baseline == null) baseline = new JSONObject();
        if (!IoUtils.atomicFileExists(userFile)) {
            if (!equal(baseline, bundled)) writeState(new JSONObject().put("baseline", bundled));
            return new Snapshot(bundled, bundled, new ArrayList<>(), false, false);
        }
        JSONObject user;
        try {
            user = read(userFile);
            ConfigStore.validateCharacterDictionary(user);
        } catch (Exception invalid) {
            // Preserve the damaged file and baseline; match the existing fallback UI.
            return new Snapshot(bundled, baseline, new ArrayList<>(), false, true);
        }
        Snapshot result = merge(baseline, user, bundled, excluded);
        if (!equal(user, result.dictionary) || !equal(baseline, result.baseline)) {
            commit(result.dictionary, result.baseline);
        }
        return result;
    }

    /** Both candidates already contain all automatically merged fields. */
    static Snapshot merge(JSONObject base, JSONObject user, JSONObject bundled,
                          Set<String> excluded) throws Exception {
        return merge(base, user, bundled, excluded, true);
    }

    private static Snapshot merge(JSONObject base, JSONObject user, JSONObject bundled,
                                  Set<String> excluded, boolean preserveAssetRemovals) throws Exception {
        JSONObject merged = new JSONObject();
        JSONObject nextBase = new JSONObject();
        List<Conflict> conflicts = new ArrayList<>();
        for (String key : keys(base, user, bundled)) {
            Object old = base.opt(key);
            Object local = user.opt(key);
            Object incoming = bundled.opt(key);
            if (excluded.contains(key) && local == null) {
                // A PendingProcess removal must not be resurrected by an asset update.
                nextBase.put(key, incoming);
                continue;
            }
            List<String> fields = new ArrayList<>();
            Object left = mergeValue(old, local, incoming, "", false, fields, preserveAssetRemovals);
            merged.put(key, left);
            if (fields.isEmpty()) {
                nextBase.put(key, incoming);
            } else {
                Object right = mergeValue(old, local, incoming, "", true, new ArrayList<>(), preserveAssetRemovals);
                conflicts.add(new Conflict(key, (JSONObject) left, (JSONObject) right, fields));
                // Retain the old base until this particular conflict is resolved.
                nextBase.put(key, old);
            }
        }
        ConfigStore.validateCharacterDictionary(merged);
        return new Snapshot(merged, nextBase, conflicts, true, false);
    }

    private static Object mergeValue(Object base, Object user, Object bundled,
                                     String path, boolean chooseBundled,
                                     List<String> conflicts, boolean preserveAssetRemovals) throws Exception {
        if (equal(user, bundled)) return user;
        // Asset removal is not authority to erase user data.
        if (preserveAssetRemovals && bundled == null) return user;
        if (base != null && equal(user, base)) return bundled;
        if (base != null && equal(bundled, base)) return user;
        if (base == null) {
            if (empty(user)) return bundled;
            if (empty(bundled)) return user;
        }
        if (user instanceof JSONObject && bundled instanceof JSONObject
            && (base == null || base instanceof JSONObject)) {
            JSONObject result = new JSONObject();
            JSONObject b = base instanceof JSONObject ? (JSONObject) base : new JSONObject();
            JSONObject u = (JSONObject) user;
            JSONObject n = (JSONObject) bundled;
            for (String key : keys(b, u, n)) {
                result.put(key, mergeValue(b.opt(key), u.opt(key), n.opt(key),
                    path.isEmpty() ? key : path + "." + key, chooseBundled, conflicts, preserveAssetRemovals));
            }
            return result;
        }
        // Arrays are ordered records, not sets; divergent edits require a choice.
        conflicts.add(path.isEmpty() ? "$" : path);
        return chooseBundled ? bundled : user;
    }

    private static boolean empty(Object value) {
        return value == null || value == JSONObject.NULL || "".equals(value)
            || (value instanceof JSONArray && ((JSONArray) value).length() == 0)
            || (value instanceof JSONObject && ((JSONObject) value).length() == 0);
    }

    static boolean equal(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            for (String key : keys(x, y)) if (!equal(x.opt(key), y.opt(key))) return false;
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!equal(x.opt(i), y.opt(i))) return false;
            return true;
        }
        return a.equals(b);
    }

    private static Set<String> keys(JSONObject... objects) {
        Set<String> keys = new TreeSet<>();
        for (JSONObject object : objects) object.keys().forEachRemaining(keys::add);
        return keys;
    }

    JSONObject save(JSONObject dictionary, JSONObject editBase, JSONObject bundled,
                    Set<String> excluded) throws Exception {
        Snapshot snapshot = load(bundled, excluded);
        if (snapshot.invalidUserOverride) throw new IOException("invalid character dictionary override");
        if (editBase != null) {
            Snapshot edited = merge(editBase, dictionary, snapshot.dictionary,
                java.util.Collections.emptySet(), false);
            if (!edited.conflicts.isEmpty()) {
                throw new IOException("character dictionary changed while editing; reload before saving");
            }
            dictionary = edited.dictionary;
        }
        commit(dictionary, snapshot.baseline);
        return dictionary;
    }

    void resolve(Conflict expected, boolean chooseBundled, JSONObject bundled,
                 Set<String> excluded) throws Exception {
        Snapshot snapshot = load(bundled, excluded);
        for (Conflict current : snapshot.conflicts) {
            if (!current.name.equals(expected.name)) continue;
            if (!equal(current.user, expected.user) || !equal(current.bundled, expected.bundled)) {
                throw new IOException("character conflict changed; reload before choosing");
            }
            snapshot.dictionary.put(current.name, chooseBundled ? current.bundled : current.user);
            snapshot.baseline.put(current.name, bundled.opt(current.name));
            commit(snapshot.dictionary, snapshot.baseline);
            return;
        }
        throw new IOException("character conflict no longer exists; reload");
    }

    void reset(JSONObject bundled) throws Exception {
        recover();
        commit(null, bundled);
    }

    private void commit(JSONObject dictionary, JSONObject baseline) throws Exception {
        if (dictionary != null) ConfigStore.validateCharacterDictionary(dictionary);
        JSONObject transaction = new JSONObject().put("baseline", baseline)
            .put("pending", dictionary == null ? JSONObject.NULL : dictionary)
            .put("before_sha256", fingerprint(userFile));
        writeState(transaction);
        recover();
    }

    private JSONObject recover() throws Exception {
        if (!IoUtils.atomicFileExists(stateFile)) return new JSONObject();
        JSONObject state = read(stateFile);
        if (state.getInt("format_version") != 1) throw new IOException("unsupported dictionary update state");
        state.getJSONObject("baseline");
        if (!state.has("pending")) return state;
        Object target = state.get("pending");
        String current = fingerprint(userFile);
        String after = target == JSONObject.NULL ? "missing" : digest(bytes((JSONObject) target));
        if (!current.equals(state.getString("before_sha256")) && !current.equals(after)) {
            throw new IOException("character dictionary changed during interrupted update");
        }
        if (target == JSONObject.NULL) {
            new AtomicFile(userFile).delete();
        } else {
            JSONObject dictionary = (JSONObject) target;
            ConfigStore.validateCharacterDictionary(dictionary);
            IoUtils.writeAtomically(userFile, bytes(dictionary));
        }
        JSONObject completed = new JSONObject().put("baseline", state.getJSONObject("baseline"));
        writeState(completed);
        return completed;
    }

    private void writeState(JSONObject json) throws IOException {
        try {
            json.put("format_version", 1);
        } catch (Exception e) {
            throw new IOException("could not encode dictionary update state", e);
        }
        IoUtils.writeAtomically(stateFile, bytes(json));
    }

    private static byte[] bytes(JSONObject json) {
        return (json.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String fingerprint(File file) throws Exception {
        if (!IoUtils.atomicFileExists(file)) return "missing";
        try (InputStream in = new AtomicFile(file).openRead();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return digest(out.toByteArray());
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        }
        return hex.toString();
    }

    private static JSONObject read(File file) throws Exception {
        try (InputStream in = new AtomicFile(file).openRead();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new JSONObject(out.toString("UTF-8"));
        }
    }
}
