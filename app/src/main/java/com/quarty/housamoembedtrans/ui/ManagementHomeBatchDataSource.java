package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.management.pending.PendingProcessStore;
import com.quarty.housamoembedtrans.scene.store.SceneStore;
import com.quarty.housamoembedtrans.storage.config.ConfigStore;

import android.content.Context;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Batch data source for the management home page.
 *
 * <p>The display snapshot is intentionally separate from this catalog.  The
 * home page can render small display records while the controller's worker
 * reads complete consumer payloads from the owning stores.  The UI callback
 * only walks the immutable catalog already produced by that worker.</p>
 */
final class ManagementHomeBatchDataSource
    implements ManagementBatchController.BatchDataSource {

    private static final String KIND_SCENE = ManagementBatchController.KIND_SCENE;
    private static final String KIND_CONTEXT = ManagementBatchController.KIND_CONTEXT;
    private static final String KIND_GROUP = ManagementBatchController.KIND_GROUP;
    private static final String KIND_CHARACTER = ManagementBatchController.KIND_CHARACTER;
    private static final String KIND_TERM = ManagementBatchController.KIND_TERM;

    private final ManagementHomeActivity activity;
    private final Object lock = new Object();
    private final LinkedHashMap<String, ManagementBatchController.Item> catalog =
        new LinkedHashMap<>();
    private final LinkedHashSet<String> visibleKeys = new LinkedHashSet<>();
    private boolean catalogReady;

    ManagementHomeBatchDataSource(ManagementHomeActivity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity is required");
        }
        this.activity = activity;
    }

    void setDisplaySnapshot(ManagementHomeData.Snapshot snapshot) {
        synchronized (lock) {
            catalog.clear();
            catalogReady = false;
            visibleKeys.clear();
        }
    }

    void invalidateCatalog() {
        synchronized (lock) {
            catalog.clear();
            catalogReady = false;
            visibleKeys.clear();
        }
    }

    /** Starts a new UI render; subsequent rows define the current filter. */
    void beginRender() {
        synchronized (lock) {
            visibleKeys.clear();
        }
    }

    /** Records one selectable row actually rendered by the home adapter. */
    void markVisible(String kind, String canonicalId) {
        if (kind == null || canonicalId == null
            || kind.trim().isEmpty() || canonicalId.trim().isEmpty()) {
            return;
        }
        synchronized (lock) {
            visibleKeys.add(kind + ":" + canonicalId);
        }
    }

    /** A row is selectable only after its complete payload has been cached. */
    boolean isItemReady(String kind, String canonicalId) {
        if (kind == null || canonicalId == null) {
            return false;
        }
        synchronized (lock) {
            return catalogReady && catalog.containsKey(kind + ":" + canonicalId);
        }
    }

    @Override
    public String initialKind() {
        return KIND_SCENE;
    }

    @Override
    public Set<String> ownedKinds() {
        return new LinkedHashSet<>(java.util.Arrays.asList(
            KIND_SCENE,
            KIND_CONTEXT,
            KIND_GROUP,
            KIND_CHARACTER,
            KIND_TERM
        ));
    }

    @Override
    public String currentFilter() {
        // The host records the exact visible keys during render.  Returning a
        // text filter here would make the controller maintain a second view.
        return "";
    }

    /**
     * Reads complete records on the controller executor.  Pending objects are
     * excluded before publishing the cache so a later UI-only visible pass
     * cannot re-register an object hidden by PendingProcess.
     */
    @Override
    public List<ManagementBatchController.Item> snapshotItems()
        throws Exception {
        invalidateCatalog();
        Context context = activity.getApplicationContext() != null
            ? activity.getApplicationContext()
            : activity;
        PendingProcessStore.ReferenceSnapshot pending =
            new PendingProcessStore(context).snapshotReferences();
        List<ManagementBatchController.Item> loaded = snapshotItems(
            context,
            pending
        );
        LinkedHashMap<String, ManagementBatchController.Item> nextCatalog =
            new LinkedHashMap<>();
        for (ManagementBatchController.Item item : loaded) {
            nextCatalog.put(item.key(), item);
        }
        synchronized (lock) {
            catalog.clear();
            catalog.putAll(nextCatalog);
            catalogReady = true;
        }
        return loaded;
    }

    /**
     * Reads the complete current management catalog for an export boundary.
     * The caller may pass the pending reference snapshot used to linearize
     * this read with a concurrent PendingProcess publication.
     */
    static List<ManagementBatchController.Item> snapshotItems(
        Context context,
        PendingProcessStore.ReferenceSnapshot pending
    ) throws Exception {
        if (context == null || pending == null) {
            throw new IllegalArgumentException(
                "context and pending snapshot are required"
            );
        }
        Context safeContext = context.getApplicationContext() != null
            ? context.getApplicationContext()
            : context;
        List<ManagementBatchController.Item> loaded = new ArrayList<>();

        SceneStore sceneStore = new SceneStore(safeContext);
        for (SceneStore.ValidatedScene scene : sceneStore.listValidScenes()) {
            if (scene == null || isEmpty(scene.sceneName)
                || pending.isPending(KIND_SCENE, scene.sceneName)) {
                continue;
            }
            JSONObject payload = new JSONObject(new String(
                scene.bytes,
                StandardCharsets.UTF_8
            ));
            loaded.add(new ManagementBatchController.Item(
                KIND_SCENE,
                scene.sceneName,
                safeContext.getString(
                    R.string.management_batch_scene_label,
                    scene.sceneName
                ),
                payload
            ));
        }

        SceneContextStore contextStore = new SceneContextStore(safeContext);
        for (JSONObject document : contextStore.listContexts()) {
            addStoreItem(
                loaded,
                KIND_CONTEXT,
                document,
                R.string.management_batch_context_label,
                pending,
                safeContext
            );
        }
        for (JSONObject document : contextStore.listGroups()) {
            addStoreItem(
                loaded,
                KIND_GROUP,
                document,
                R.string.management_batch_group_label,
                pending,
                safeContext
            );
        }

        ConfigStore configStore = new ConfigStore(safeContext);
        addDictionaryItems(
            loaded,
            KIND_CHARACTER,
            configStore.loadJson(ConfigStore.CHARDICT_FILE_NAME).json,
            R.string.management_batch_character_label,
            pending,
            safeContext
        );
        addDictionaryItems(
            loaded,
            KIND_TERM,
            configStore.loadJson(ConfigStore.GAMETERMS_FILE_NAME).json,
            R.string.management_batch_term_label,
            pending,
            safeContext
        );
        return loaded;
    }

    /**
     * Returns only rows marked by the current UI render.  This method never
     * opens a store or reads a file; before the worker catalog is ready it
     * deliberately returns an empty list so selection cannot register a
     * placeholder payload.
     */
    @Override
    public List<ManagementBatchController.Item> currentVisibleItems() {
        synchronized (lock) {
            if (!catalogReady) {
                return Collections.emptyList();
            }
            List<ManagementBatchController.Item> output = new ArrayList<>();
            for (String key : visibleKeys) {
                ManagementBatchController.Item item = catalog.get(key);
                if (item != null) {
                    output.add(item);
                }
            }
            return output;
        }
    }

    @Override
    public void onBatchModeChanged(boolean enabled) {
        if (enabled) {
            invalidateCatalog();
        }
        activity.setHomeBatchMode(enabled);
    }

    @Override
    public void onBatchSelectionChanged() {
        activity.refreshHomeBatchRows();
    }

    @Override
    public void onBatchItemsMoved(List<String> succeededKeys) {
        activity.reloadHomeAfterBatchMove();
    }

    private static void addStoreItem(
        List<ManagementBatchController.Item> output,
        String kind,
        JSONObject source,
        int labelRes,
        PendingProcessStore.ReferenceSnapshot pending,
        Context context
    ) throws Exception {
        if (source == null) {
            return;
        }
        String id = source.optString("id", "").trim();
        if (id.isEmpty() || pending.isPending(kind, id)) {
            return;
        }
        output.add(new ManagementBatchController.Item(
            kind,
            id,
            context.getString(
                labelRes,
                source.optString("display_name", id)
            ),
            new JSONObject(source.toString())
        ));
    }

    private static void addDictionaryItems(
        List<ManagementBatchController.Item> output,
        String kind,
        JSONObject dictionary,
        int labelRes,
        PendingProcessStore.ReferenceSnapshot pending,
        Context context
    ) throws Exception {
        if (dictionary == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = dictionary.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!isEmpty(key)) {
                keys.add(key);
            }
        }
        Collections.sort(keys);
        for (String key : keys) {
            JSONObject record = dictionary.optJSONObject(key);
            if (record == null || pending.isPending(kind, key)) {
                continue;
            }
            String label = KIND_CHARACTER.equals(kind) && "mc".equals(key)
                ? context.getString(R.string.management_batch_main_character_label)
                : context.getString(labelRes, key);
            output.add(new ManagementBatchController.Item(
                kind,
                key,
                label,
                new JSONObject(record.toString())
            ));
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
