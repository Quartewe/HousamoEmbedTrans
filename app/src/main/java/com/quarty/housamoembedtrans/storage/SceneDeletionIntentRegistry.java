package com.quarty.housamoembedtrans.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Process-local Scene deletion intent tokens shared by SceneStore instances. */
public final class SceneDeletionIntentRegistry {
    public static final class Intent {
        public final String sceneName;
        public final long token;

        private Intent(String sceneName, long token) {
            this.sceneName = sceneName;
            this.token = token;
        }
    }

    private final Map<String, Long> intents = new HashMap<>();
    private long nextToken;

    public synchronized Intent record(String sceneName) {
        long token = ++nextToken;
        intents.put(sceneName, token);
        return new Intent(sceneName, token);
    }

    public synchronized Map<String, Intent> snapshot() {
        Map<String, Intent> copy = new HashMap<>();
        for (Map.Entry<String, Long> entry : intents.entrySet()) {
            copy.put(
                entry.getKey(),
                new Intent(entry.getKey(), entry.getValue())
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    public synchronized List<String> names() {
        List<String> names = new ArrayList<>(intents.keySet());
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    public synchronized boolean contains(String sceneName) {
        return intents.containsKey(sceneName);
    }

    public synchronized void clear(String sceneName) {
        intents.remove(sceneName);
    }

    public synchronized boolean clearMatching(String sceneName, long token) {
        Long current = intents.get(sceneName);
        if (current == null || current.longValue() != token) {
            return false;
        }
        intents.remove(sceneName);
        return true;
    }
}
