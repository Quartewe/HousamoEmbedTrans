package com.quarty.housamoembedtrans.scene.store;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable, root-scoped Scene deletion intents.
 *
 * <p>The registry is deliberately owned by one SceneStore root rather than
 * by the VM. Every operation reloads the file while holding both a shared
 * in-process monitor and an inter-process lock, so another HET process cannot
 * make a token disappear merely by restarting. The JSON file is replaced by
 * {@link IoUtils#writeAtomically(File, byte[])} after each mutation.</p>
 */
public final class SceneDeletionIntentRegistry {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_REGISTRY_BYTES = 1024 * 1024;
    private static final int MAX_SCENE_NAME_BYTES = 235;
    private static final String STATE_DIRECTORY_NAME = ".metadata";
    private static final String REGISTRY_FILE_NAME =
        ".scene-deletion-intents.json";
    private static final String LOCK_FILE_NAME =
        ".scene-deletion-intents.lock";

    /** Monitors only coordinate callers in this VM; intent state is durable. */
    private static final Map<String, Object> ROOT_LOCKS =
        new ConcurrentHashMap<>();

    public static final class Intent {
        public final String sceneName;
        public final long token;

        private Intent(String sceneName, long token) {
            this.sceneName = sceneName;
            this.token = token;
        }
    }

    private static final class State {
        private long nextToken;
        private final Map<String, Long> intents = new HashMap<>();
    }

    @FunctionalInterface
    private interface StateOperation<T> {
        T apply(State state) throws IOException;
    }

    @FunctionalInterface
    public interface BeforeClearAction {
        void run() throws IOException;
    }

    private final File stateDirectory;
    private final File registryFile;
    private final File lockFile;
    private final String rootKey;

    public SceneDeletionIntentRegistry(File rootDirectory) {
        if (rootDirectory == null) {
            throw new IllegalArgumentException("rootDirectory is null");
        }
        stateDirectory = new File(rootDirectory, STATE_DIRECTORY_NAME);
        registryFile = new File(stateDirectory, REGISTRY_FILE_NAME);
        lockFile = new File(stateDirectory, LOCK_FILE_NAME);
        rootKey = canonicalPath(registryFile);
    }

    public Intent record(String sceneName) throws IOException {
        validateSceneName(sceneName);
        return withState(state -> {
            long token = state.nextToken + 1L;
            if (token <= 0L) {
                throw new IOException("Scene deletion intent token overflow");
            }
            state.nextToken = token;
            state.intents.put(sceneName, token);
            writeState(state);
            return new Intent(sceneName, token);
        });
    }

    public Map<String, Intent> snapshot() throws IOException {
        return withState(state -> {
            Map<String, Intent> copy = new HashMap<>();
            for (Map.Entry<String, Long> entry : state.intents.entrySet()) {
                copy.put(
                    entry.getKey(),
                    new Intent(entry.getKey(), entry.getValue())
                );
            }
            return Collections.unmodifiableMap(copy);
        });
    }

    public List<String> names() throws IOException {
        return withState(state -> {
            List<String> names = new ArrayList<>(state.intents.keySet());
            Collections.sort(names);
            return Collections.unmodifiableList(names);
        });
    }

    public boolean contains(String sceneName) throws IOException {
        validateSceneName(sceneName);
        return withState(state -> state.intents.containsKey(sceneName));
    }

    /** Clears the current intent for a name; repeated calls are harmless. */
    public void clear(String sceneName) throws IOException {
        validateSceneName(sceneName);
        withState(state -> {
            if (state.intents.remove(sceneName) != null) {
                writeState(state);
            }
            return null;
        });
    }

    /**
     * Clears only the token captured by one sync operation.
     *
     * <p>The operation is idempotent: a second call returns {@code false}
     * because the token has already been removed, while a newer token is left
     * untouched.</p>
     */
    public boolean clearMatching(String sceneName, long token)
        throws IOException {
        return clearMatching(sceneName, token, null);
    }

    /**
     * Clears a token and runs the residual-file cleanup while the same root
     * lock is held. A mismatched/newer token never invokes the callback.
     */
    public boolean clearMatching(
        String sceneName,
        long token,
        BeforeClearAction beforeClear
    ) throws IOException {
        validateSceneName(sceneName);
        if (token <= 0L) {
            return false;
        }
        return withState(state -> {
            Long current = state.intents.get(sceneName);
            if (current == null || current.longValue() != token) {
                return false;
            }
            if (beforeClear != null) {
                beforeClear.run();
            }
            state.intents.remove(sceneName);
            writeState(state);
            return true;
        });
    }

    private <T> T withState(StateOperation<T> operation) throws IOException {
        IoUtils.ensureDirectory(stateDirectory);
        Object processLock = ROOT_LOCKS.computeIfAbsent(
            rootKey,
            ignored -> new Object()
        );
        synchronized (processLock) {
            try (RandomAccessFile randomAccessFile =
                    new RandomAccessFile(lockFile, "rw")) {
                FileChannel channel = randomAccessFile.getChannel();
                FileLock fileLock;
                try {
                    fileLock = channel.lock();
                } catch (OverlappingFileLockException e) {
                    throw new IOException(
                        "Scene deletion intent lock is already held",
                        e
                    );
                }
                try {
                    return operation.apply(readState());
                } finally {
                    fileLock.release();
                }
            }
        }
    }

    private State readState() throws IOException {
        State state = new State();
        if (!IoUtils.atomicFileExists(registryFile)) {
            return state;
        }

        final JSONObject root;
        try (InputStream input = new AtomicFile(registryFile).openRead()) {
            byte[] bytes = IoUtils.readAllBytesLimited(input, MAX_REGISTRY_BYTES);
            root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException(
                "Scene deletion intent registry is malformed",
                e
            );
        }

        try {
            if (root.getInt("version") != FORMAT_VERSION) {
                throw new IOException(
                    "unsupported Scene deletion intent registry version"
                );
            }
            state.nextToken = root.getLong("next_token");
            if (state.nextToken < 0L) {
                throw new IOException(
                    "Scene deletion intent next token is negative"
                );
            }
            JSONObject intents = root.getJSONObject("intents");
            Iterator<String> keys = intents.keys();
            while (keys.hasNext()) {
                String sceneName = keys.next();
                validateSceneName(sceneName);
                long token = intents.getLong(sceneName);
                if (token <= 0L || token > state.nextToken) {
                    throw new IOException(
                        "Scene deletion intent token is outside registry range"
                    );
                }
                state.intents.put(sceneName, token);
            }
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException(
                "Scene deletion intent registry fields are invalid",
                e
            );
        }
        return state;
    }

    private void writeState(State state) throws IOException {
        JSONObject root = new JSONObject();
        JSONObject intents = new JSONObject();
        try {
            List<String> names = new ArrayList<>(state.intents.keySet());
            names.sort(Comparator.naturalOrder());
            for (String sceneName : names) {
                intents.put(sceneName, state.intents.get(sceneName));
            }
            root.put("version", FORMAT_VERSION);
            root.put("next_token", state.nextToken);
            root.put("intents", intents);
        } catch (JSONException e) {
            throw new IOException(
                "could not encode Scene deletion intent registry",
                e
            );
        }
        IoUtils.writeAtomically(
            registryFile,
            root.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void validateSceneName(String sceneName) {
        if (sceneName == null || sceneName.isEmpty()) {
            throw new IllegalArgumentException(
                "Scene deletion intent name is empty"
            );
        }
        if (sceneName.getBytes(StandardCharsets.UTF_8).length
            > MAX_SCENE_NAME_BYTES) {
            throw new IllegalArgumentException(
                "Scene deletion intent name exceeds the limit"
            );
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
