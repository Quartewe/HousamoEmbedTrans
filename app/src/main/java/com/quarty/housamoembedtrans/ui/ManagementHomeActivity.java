package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** First-level management lists and the read-only Context/Group tree. */
public final class ManagementHomeActivity extends AppCompatActivity {

    private static final int MODE_LIST = 0;
    private static final int MODE_TREE = 1;
    private static final int TAB_SCENES = 0;
    private static final int TAB_CONTEXTS = 1;
    private static final int TAB_GROUPS = 2;
    private static final int TAB_CHARACTERS = 3;
    private static final int TAB_TERMS = 4;
    private static final int TAB_COUNT = 5;

    private static final String STATE_MODE = "management_home.mode";
    private static final String STATE_TAB = "management_home.tab";
    private static final String STATE_SEARCH = "management_home.search";
    private static final String STATE_QUERIES = "management_home.queries";
    private static final String STATE_TREE_QUERY = "management_home.tree_query";
    private static final String STATE_EXPANDED = "management_home.expanded";
    private static final String STATE_SCROLL = "management_home.scroll";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    /** One query is shared by the list and package/tree tabs, like the prototype. */
    private final String[] tabQueries = new String[TAB_COUNT];
    private final int[] scrollPositions = new int[TAB_COUNT + 1];
    private final Set<String> expandedNodes = new HashSet<>();

    private MaterialToolbar toolbar;
    private MaterialButton managementViewButton;
    private MaterialButton managementClearActiveButton;
    private MaterialButton managementMoreButton;
    private TabLayout tabLayout;
    private EditText searchInput;
    private TextView statusView;
    private LinearLayout content;
    private androidx.core.widget.NestedScrollView scrollView;
    private FloatingActionButton addFab;
    private ManagementHomeBatchDataSource batchDataSource;
    private ManagementBatchController managementBatchController;

    private int mode = MODE_LIST;
    private int selectedTab = TAB_SCENES;
    private String searchQuery = "";
    private String treeQuery = "";
    private ManagementHomeData.Snapshot snapshot;
    private String loadErrorMessage = "";
    private long loadGeneration;
    private boolean lifecycleStarted;
    private boolean destroyed;
    private boolean updatingSearch;
    private boolean updatingNavigation;
    private boolean batchMode;
    private boolean clearingActivePointers;
    private boolean stylePreview;
    private String stylePreviewKind = "";
    private org.json.JSONObject stylePreviewPayload;
    private ManagementBatchSelection.Session batchSelection;
    private long renderGeneration;
    /** Current render only; repeated tree occurrences share a selection key. */
    private final List<BatchCheckBinding> batchChecks = new ArrayList<>();

    private static final class BatchCheckBinding {
        final String kind;
        final String id;
        final String key;
        final CheckBox check;

        BatchCheckBinding(String kind, String id, CheckBox check) {
            this.kind = kind;
            this.id = id;
            this.key = kind + ":" + id;
            this.check = check;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_management_home);
        SystemBarInsets.apply(findViewById(R.id.root_management_home));

        stylePreview = StylePreview.isEnabled(this);
        batchSelection = stylePreview
            ? ManagementBatchSelection.newSession()
            : ManagementBatchSelection.globalSession();
        stylePreviewKind = StylePreview.kindOf(getIntent());
        stylePreviewPayload = StylePreview.payloadOf(getIntent());
        if (stylePreview && stylePreviewPayload == null) {
            stylePreviewPayload = StylePreview.sample(
                stylePreviewKind.isEmpty()
                    ? StylePreview.KIND_MANAGEMENT_HOME
                    : stylePreviewKind
            );
        }
        restoreState(savedInstanceState);
        setupViews();
        setupTabs();
        setupSearch();
        setupManagementActions();
        batchDataSource = stylePreview
            ? new ManagementHomeBatchDataSource(this, stylePreviewPayload)
            : new ManagementHomeBatchDataSource(this);
        managementBatchController = ManagementBatchController.attach(
            this,
            findViewById(R.id.container_management_home_batch),
            batchDataSource,
            savedInstanceState,
            stylePreview,
            batchSelection
        );
        syncActiveBatchKind();
        PrimaryNavigation.attach(
            this,
            findViewById(R.id.primary_navigation),
            PrimaryNavigation.Destination.MANAGEMENT
        );
        if (stylePreview) {
            setupPreviewNavigation();
            snapshot = ManagementHomeData.fromPreview(stylePreviewPayload);
            statusView.setVisibility(View.GONE);
        }
        render();
        if (stylePreview
            && StylePreview.KIND_MANAGEMENT_EXPORT.equals(stylePreviewKind)
            && managementBatchController != null) {
            managementBatchController.enter();
        }
    }

    private void setupPreviewNavigation() {
        View navigation = findViewById(R.id.primary_navigation);
        if (navigation == null) {
            return;
        }
        View home = navigation.findViewById(R.id.nav_home);
        if (home != null) home.setOnClickListener(view -> {
            startActivity(StylePreview.intentFor(this, StylePreview.KIND_HOME));
            finish();
        });
        View tasks = navigation.findViewById(R.id.nav_tasks);
        View management = navigation.findViewById(R.id.nav_management);
        View settings = navigation.findViewById(R.id.nav_settings);
        if (tasks != null) {
            tasks.setOnClickListener(view -> {
                startActivity(StylePreview.intentFor(
                    this,
                    StylePreview.KIND_TASKS
                ));
                finish();
            });
        }
        if (management != null) {
            management.setOnClickListener(view -> {
                // The current preview page is already the management sample.
            });
        }
        if (settings != null) {
            settings.setOnClickListener(view -> startActivity(
                new Intent(this, StylePreviewActivity.class).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            ));
        }
    }

    private void setupViews() {
        toolbar = findViewById(R.id.toolbar_management_home);
        toolbar.setTitle(R.string.management_home_title);
        tabLayout = findViewById(R.id.tabs_management_home);
        searchInput = findViewById(R.id.et_management_home_search);
        ViewGroup.LayoutParams searchParams = searchInput.getLayoutParams();
        if (searchParams != null) {
            searchParams.height = dp(44);
            searchInput.setLayoutParams(searchParams);
        }
        searchInput.setMinHeight(0);
        searchInput.setMinimumHeight(0);
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        statusView = findViewById(R.id.tv_management_home_status);
        content = findViewById(R.id.container_management_home_content);
        scrollView = findViewById(R.id.scroll_management_home);
        addFab = findViewById(R.id.management_rebuild_fab);
        addFab.setOnClickListener(view -> showManagementAddMenu(view));
        managementViewButton = findViewById(R.id.btn_management_view);
        managementClearActiveButton = findViewById(
            R.id.btn_management_clear_active
        );
        managementMoreButton = findViewById(R.id.btn_management_more);
    }

    private void setupTabs() {
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabLayout.setTabGravity(TabLayout.GRAVITY_START);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (updatingNavigation) {
                    selectedTab = tabId(tab);
                    return;
                }
                if (!updatingNavigation) {
                    rememberScrollPosition();
                }
                selectedTab = tabId(tab);
                syncSearchFromState();
                syncActiveBatchKind();
                render();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                rememberScrollPosition();
            }
        });
        syncTabsForMode();
    }

    /** Rebuilds the visible tab strip when switching between list/package mode. */
    private void syncTabsForMode() {
        if (tabLayout == null) {
            return;
        }
        int selectedPosition = 0;
        updatingNavigation = true;
        try {
            tabLayout.removeAllTabs();
            if (mode == MODE_TREE) {
                addTab(
                    R.string.management_rebuild_tab_structure,
                    TAB_SCENES
                );
                addTab(
                    R.string.management_home_tab_characters,
                    TAB_CHARACTERS
                );
                addTab(
                    R.string.management_home_tab_terms,
                    TAB_TERMS
                );
                selectedPosition = selectedTab == TAB_CHARACTERS
                    ? 1
                    : selectedTab == TAB_TERMS ? 2 : 0;
            } else {
                addTab(R.string.management_home_tab_scenes, TAB_SCENES);
                addTab(R.string.management_home_tab_contexts, TAB_CONTEXTS);
                addTab(R.string.management_home_tab_groups, TAB_GROUPS);
                addTab(
                    R.string.management_home_tab_characters,
                    TAB_CHARACTERS
                );
                addTab(R.string.management_home_tab_terms, TAB_TERMS);
                selectedPosition = selectedTab;
            }
            styleManagementTabViews();
            TabLayout.Tab tab = tabLayout.getTabAt(selectedPosition);
            if (tab != null) {
                tab.select();
            }
        } finally {
            updatingNavigation = false;
        }
    }

    /** Keeps the stateful segment background at the prototype's 31dp height. */
    private void styleManagementTabViews() {
        if (tabLayout.getChildCount() == 0
            || !(tabLayout.getChildAt(0) instanceof ViewGroup)) {
            return;
        }
        ViewGroup indicator = (ViewGroup) tabLayout.getChildAt(0);
        int minHeight = dp(31);
        indicator.setPadding(dp(3), dp(4), dp(3), dp(4));
        for (int index = 0; index < indicator.getChildCount(); index++) {
            View child = indicator.getChildAt(index);
            child.setMinimumHeight(minHeight);
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params != null) {
                params.height = minHeight;
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                child.setLayoutParams(params);
            }
        }
    }

    private void addTab(int labelResource, int tabId) {
        tabLayout.addTab(
            tabLayout.newTab()
                .setText(labelResource)
                .setTag(tabId),
            false
        );
    }

    private int tabId(TabLayout.Tab tab) {
        Object tag = tab == null ? null : tab.getTag();
        return tag instanceof Integer ? (Integer) tag : TAB_SCENES;
    }

    private void setupSearch() {
        syncSearchFromState();
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                CharSequence value,
                int start,
                int count,
                int after
            ) {
            }

            @Override
            public void onTextChanged(
                CharSequence value,
                int start,
                int before,
                int count
            ) {
                if (updatingSearch) {
                    return;
                }
                String query = value == null ? "" : value.toString();
                searchQuery = query;
                treeQuery = query;
                for (int index = 0; index < tabQueries.length; index++) {
                    tabQueries[index] = query;
                }
                rememberScrollPosition();
                render();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
    }

    private void setupManagementActions() {
        toolbar.setNavigationIcon(R.drawable.ic_management_rebuild_io);
        toolbar.setNavigationContentDescription(
            R.string.management_home_io
        );
        toolbar.setNavigationOnClickListener(this::showManagementIoMenu);
        managementViewButton.setOnClickListener(view -> {
            toggleViewMode();
        });
        managementClearActiveButton.setOnClickListener(view -> {
            showClearActivePointersDialog();
        });
        managementMoreButton.setOnClickListener(view -> {
            showManagementMoreMenu(view);
        });
        updateToolbarActions();
    }

    private void showManagementIoMenu(View anchor) {
        StyledPopupMenu popup = new StyledPopupMenu(this, anchor, Gravity.START);
        popup.setTitle(getString(R.string.management_home_io_title));
        MenuItem importItem = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            1,
            0,
            getString(R.string.management_home_more_import)
        );
        MenuItem exportItem = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            2,
            1,
            getString(R.string.management_home_more_export)
        );
        MenuItem syncItem = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            3,
            2,
            getString(R.string.management_home_more_sync)
        );
        importItem.setOnMenuItemClickListener(item -> {
            if (stylePreview) {
                startActivity(StylePreview.intentFor(
                    this,
                    StylePreview.KIND_MANAGEMENT_IMPORT
                ));
            } else {
                startActivity(ManagementImportActivity.newIntent(this, false));
            }
            return true;
        });
        exportItem.setOnMenuItemClickListener(item -> {
            enterHomeBatchModeForExport();
            return true;
        });
        syncItem.setOnMenuItemClickListener(item -> {
            if (stylePreview) {
                startActivity(StylePreview.intentFor(
                    this,
                    StylePreview.KIND_SCENE_SYNC
                ));
            } else {
                startActivity(new Intent(this, SceneFilesActivity.class));
            }
            return true;
        });
        popup.show();
    }

    private void showManagementMoreMenu(View anchor) {
        StyledPopupMenu popup = new StyledPopupMenu(this, anchor, Gravity.END);
        popup.setTitle(getString(managementMoreMenuTitle()));
        if (mode == MODE_TREE && selectedTab == TAB_SCENES) {
            MenuItem expand = popup.getMenu().add(
                MenuItem.SHOW_AS_ACTION_NEVER,
                10,
                0,
                getString(R.string.management_home_tree_expand_all)
            );
            MenuItem collapse = popup.getMenu().add(
                MenuItem.SHOW_AS_ACTION_NEVER,
                11,
                1,
                getString(R.string.management_home_tree_collapse_all)
            );
            expand.setOnMenuItemClickListener(item -> {
                expandAllTreeNodes();
                return true;
            });
            collapse.setOnMenuItemClickListener(item -> {
                collapseAllTreeNodes();
                return true;
            });
            popup.show();
            return;
        }
        MenuItem batch = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            5,
            0,
            getString(R.string.management_home_more_batch)
        );
        batch.setOnMenuItemClickListener(item -> {
            enterHomeBatchMode();
            return true;
        });
        popup.show();
    }

    private int managementMoreMenuTitle() {
        if (mode == MODE_TREE && selectedTab == TAB_SCENES) {
            return R.string.management_home_tree_menu_title;
        }
        switch (selectedTab) {
            case TAB_CONTEXTS:
                return R.string.management_home_context_menu_title;
            case TAB_GROUPS:
                return R.string.management_home_group_menu_title;
            case TAB_CHARACTERS:
                return R.string.management_home_character_menu_title;
            case TAB_TERMS:
                return R.string.management_home_term_menu_title;
            case TAB_SCENES:
            default:
                return R.string.management_home_scene_menu_title;
        }
    }

    private void showManagementAddMenu(View anchor) {
        if (batchMode || destroyed || anchor == null) {
            return;
        }
        if (stylePreview) {
            showPreviewNotice();
            return;
        }
        if (mode == MODE_TREE && selectedTab == TAB_SCENES) {
            StyledPopupMenu popup = new StyledPopupMenu(this, anchor, Gravity.END);
            addCreateMenuItem(
                popup,
                20,
                R.string.scene_context_new_context,
                "context"
            );
            addCreateMenuItem(
                popup,
                21,
                R.string.scene_context_new_group,
                "group"
            );
            popup.show();
            return;
        }
        if (selectedTab == TAB_CONTEXTS) {
            startActivity(new Intent(this, SceneContextActivity.class)
                .putExtra(
                    SceneContextActivity.EXTRA_MANAGEMENT_CREATE_KIND,
                    "context"
                ));
            return;
        }
        if (selectedTab == TAB_GROUPS) {
            startActivity(new Intent(this, SceneContextActivity.class)
                .putExtra(
                    SceneContextActivity.EXTRA_MANAGEMENT_CREATE_KIND,
                    "group"
                ));
            return;
        }
        if (selectedTab == TAB_CHARACTERS) {
            openCharacterCreator();
            return;
        }
        if (selectedTab == TAB_TERMS) {
            startActivity(new Intent(this, GameTermsActivity.class)
                .putExtra(GameTermsActivity.EXTRA_CREATE_TERM, true));
            return;
        } else {
            Toast.makeText(
                this,
                R.string.management_rebuild_scene_add_unavailable,
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void addCreateMenuItem(
        StyledPopupMenu popup,
        int id,
        int labelResource,
        String kind
    ) {
        MenuItem item = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            id,
            popup.getMenu().size(),
            getString(labelResource)
        );
        item.setOnMenuItemClickListener(clicked -> {
            startActivity(new Intent(this, SceneContextActivity.class)
                .putExtra(
                    SceneContextActivity.EXTRA_MANAGEMENT_CREATE_KIND,
                    kind
                ));
            return true;
        });
    }

    private void updateFab() {
        if (addFab == null) {
            return;
        }
        addFab.setVisibility(batchMode ? View.GONE : View.VISIBLE);
    }

    private void toggleViewMode() {
        if (batchMode || destroyed) {
            return;
        }
        rememberScrollPosition();
        mode = mode == MODE_TREE ? MODE_LIST : MODE_TREE;
        if (mode == MODE_TREE
            && selectedTab != TAB_SCENES
            && selectedTab != TAB_CHARACTERS
            && selectedTab != TAB_TERMS) {
            selectedTab = TAB_SCENES;
        }
        syncTabsForMode();
        syncSearchFromState();
        syncActiveBatchKind();
        render();
    }

    private void expandAllTreeNodes() {
        if (mode != MODE_TREE || selectedTab != TAB_SCENES
            || snapshot == null || batchMode) {
            return;
        }
        rememberScrollPosition();
        expandedNodes.clear();
        for (ManagementHomeData.TreeNode root : snapshot.treeRoots) {
            collectExpandableNodes(root);
        }
        render();
    }

    private void collectExpandableNodes(ManagementHomeData.TreeNode node) {
        if (node == null || node.children.isEmpty()) {
            return;
        }
        expandedNodes.add(node.key);
        for (ManagementHomeData.TreeNode child : node.children) {
            collectExpandableNodes(child);
        }
    }

    private void collapseAllTreeNodes() {
        if (mode != MODE_TREE || selectedTab != TAB_SCENES || batchMode) {
            return;
        }
        rememberScrollPosition();
        expandedNodes.clear();
        render();
    }

    private void showClearActivePointersDialog() {
        if (stylePreview || batchMode || clearingActivePointers || !hasActivePointers()) {
            updateToolbarActions();
            return;
        }
        new UiMaterialAlertDialogBuilder(this)
            .setTitle(R.string.management_home_clear_active_title)
            .setMessage(R.string.management_home_clear_active_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                R.string.management_home_clear_active_action,
                (dialog, which) -> clearActivePointers()
            )
            .show();
    }

    private boolean hasActivePointers() {
        return snapshot != null
            && (!android.text.TextUtils.isEmpty(snapshot.activeContextId)
                || !android.text.TextUtils.isEmpty(snapshot.activeGroupId));
    }

    private void clearActivePointers() {
        if (stylePreview || batchMode || clearingActivePointers || !hasActivePointers()) {
            updateToolbarActions();
            return;
        }
        clearingActivePointers = true;
        final long request = ++loadGeneration;
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(R.string.management_home_clearing_active);
        updateToolbarActions();
        ioExecutor.execute(() -> {
            try {
                SceneContextStore contextStore = new SceneContextStore(this);
                contextStore.setActivePointers(null, null);
                runOnUiThread(() -> {
                    clearingActivePointers = false;
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    loadSnapshotAsync();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    clearingActivePointers = false;
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    statusView.setText(getString(
                        R.string.management_home_clear_active_failed,
                        safeMessage(error)
                    ));
                    updateToolbarActions();
                });
            }
        });
    }

    private void updateToolbarActions() {
        if (managementViewButton != null) {
            managementViewButton.setIconResource(
                mode == MODE_TREE
                    ? R.drawable.ic_management_rebuild_tree
                    : R.drawable.ic_management_rebuild_list
            );
            managementViewButton.setContentDescription(
                getString(mode == MODE_TREE
                    ? R.string.management_home_mode_list
                    : R.string.management_home_mode_tree)
            );
            managementViewButton.setEnabled(!batchMode && !destroyed);
        }
        if (managementClearActiveButton != null) {
            managementClearActiveButton.setEnabled(
                !stylePreview
                    && !batchMode
                    && !clearingActivePointers
                    && !destroyed
                    && hasActivePointers()
            );
        }
        if (managementMoreButton != null) {
            managementMoreButton.setEnabled(!batchMode && !destroyed);
        }
    }

    private void showPreviewNotice() {
        Toast.makeText(
            this,
            R.string.management_home_preview_read_only,
            Toast.LENGTH_SHORT
        ).show();
    }

    private void enterHomeBatchMode() {
        if (managementBatchController != null) {
            managementBatchController.enter();
        }
    }

    private void enterHomeBatchModeForExport() {
        if (batchMode) {
            return;
        }
        if (mode == MODE_TREE) {
            rememberScrollPosition();
            updatingNavigation = true;
            try {
                mode = MODE_LIST;
                selectedTab = TAB_SCENES;
                syncTabsForMode();
                TabLayout.Tab sceneTab = tabLayout.getTabAt(TAB_SCENES);
                if (sceneTab != null
                    && tabLayout.getSelectedTabPosition() != TAB_SCENES) {
                    sceneTab.select();
                } else {
                    syncSearchFromState();
                    render();
                }
            } finally {
                updatingNavigation = false;
            }
        }
        enterHomeBatchMode();
    }

    void setHomeBatchMode(boolean enabled) {
        batchMode = enabled;
        syncActiveBatchKind();
        updateToolbarActions();
        render();
    }

    private void syncActiveBatchKind() {
        if (managementBatchController == null) {
            return;
        }
        String kind;
        switch (selectedTab) {
            case TAB_CONTEXTS:
                kind = ManagementBatchController.KIND_CONTEXT;
                break;
            case TAB_GROUPS:
                kind = ManagementBatchController.KIND_GROUP;
                break;
            case TAB_CHARACTERS:
                kind = ManagementBatchController.KIND_CHARACTER;
                break;
            case TAB_TERMS:
                kind = ManagementBatchController.KIND_TERM;
                break;
            case TAB_SCENES:
            default:
                kind = ManagementBatchController.KIND_SCENE;
                break;
        }
        managementBatchController.setActiveKind(kind);
    }

    void refreshHomeBatchRows() {
        if (batchMode && !destroyed && !isFinishing() && !isDestroyed()) {
            updateBatchChecks(null);
            notifyBatchHostRowsChanged();
        }
    }

    private void updateBatchChecks(String changedKey) {
        for (BatchCheckBinding binding : batchChecks) {
            if (changedKey != null && !changedKey.equals(binding.key)) continue;
            boolean checked = batchSelection.contains(binding.key);
            if (binding.check.isChecked() != checked) {
                binding.check.setChecked(checked);
            }
            boolean ready = batchDataSource.isItemReady(binding.kind, binding.id);
            if (binding.check.isEnabled() != ready) {
                binding.check.setEnabled(ready);
            }
        }
    }

    void reloadHomeAfterBatchMove() {
        loadSnapshotAsync();
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleStarted = true;
        if (managementBatchController != null) {
            managementBatchController.onStart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PrimaryNavigation.animateContentOnResume(this);
        loadSnapshotAsync();
    }

    @Override
    protected void onPause() {
        rememberScrollPosition();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (managementBatchController != null) {
            managementBatchController.onStop();
        }
        lifecycleStarted = false;
        loadGeneration++;
        clearingActivePointers = false;
        super.onStop();
    }

    private void loadSnapshotAsync() {
        final long request = ++loadGeneration;
        loadErrorMessage = "";
        if (stylePreview) {
            snapshot = ManagementHomeData.fromPreview(stylePreviewPayload);
            batchDataSource.setDisplaySnapshot(snapshot);
            statusView.setVisibility(View.GONE);
            render();
            if (managementBatchController != null
                && managementBatchController.isActive()) {
                managementBatchController.refreshHostCatalog();
            }
            return;
        }
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(R.string.management_home_loading);
        ioExecutor.execute(() -> {
            try {
                ManagementHomeData.Snapshot loaded =
                    ManagementHomeData.load(this);
                runOnUiThread(() -> {
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    if (snapshot != null) {
                        rememberScrollPosition();
                    }
                    snapshot = loaded;
                    loadErrorMessage = "";
                    batchDataSource.setDisplaySnapshot(loaded);
                    statusView.setVisibility(View.GONE);
                    statusView.setText(getString(
                        R.string.management_home_loaded,
                        loaded.scenes.size(),
                        loaded.contexts.size(),
                        loaded.groups.size(),
                        loaded.characters.size(),
                        loaded.terms.size()
                    ));
                    render();
                    if (managementBatchController != null
                        && managementBatchController.isActive()) {
                        managementBatchController.refreshHostCatalog();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(request)) {
                        return;
                    }
                    snapshot = null;
                    loadErrorMessage = safeMessage(error);
                    batchDataSource.setDisplaySnapshot(null);
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(getString(
                        R.string.management_home_load_failed,
                        safeMessage(error)
                    ));
                    render();
                });
            }
        });
    }

    private boolean isCurrentRequest(long request) {
        return !destroyed
            && lifecycleStarted
            && !isFinishing()
            && request == loadGeneration;
    }

    private void render() {
        if (content == null) {
            return;
        }
        renderGeneration++;
        tabLayout.setVisibility(View.VISIBLE);
        updateFab();
        updateToolbarActions();
        batchDataSource.beginRender();
        batchChecks.clear();
        content.removeAllViews();
        if (snapshot == null) {
            if (loadErrorMessage.isEmpty()) {
                addEmpty(R.string.management_home_waiting_for_data);
            } else {
                addError(getString(
                    R.string.management_home_load_failed,
                    loadErrorMessage
                ));
            }
            notifyBatchHostRowsChanged();
            return;
        }
        if (mode == MODE_TREE && selectedTab == TAB_SCENES) {
            renderTree();
        } else if (mode == MODE_TREE) {
            renderPackageDictionary();
        } else {
            renderList();
        }
        notifyBatchHostRowsChanged();
    }

    private void notifyBatchHostRowsChanged() {
        if (managementBatchController != null
            && managementBatchController.isActive()) {
            managementBatchController.onHostRowsChanged();
        }
    }

    private void renderList() {
        String query = searchQuery;
        switch (selectedTab) {
            case TAB_SCENES:
                for (ManagementHomeData.SceneItem scene : snapshot.scenes) {
                    if (matches(query, scene.name, sceneSearchText(scene))) {
                        addRow(
                            scene.name,
                            sceneSubtitle(scene),
                            sceneRowSide(scene),
                            false,
                            ManagementBatchController.KIND_SCENE,
                            scene.name,
                            () -> openScene(scene.name)
                        );
                    }
                }
                break;
            case TAB_CONTEXTS:
                for (ManagementHomeData.ContextItem context : snapshot.contexts) {
                    if (matches(
                        query,
                        context.displayName,
                        context.searchText
                    )) {
                        addRow(
                            context.displayName,
                            contextSubtitle(context),
                            formatUpdatedAt(context.updatedAt),
                            context.id.equals(snapshot.activeContextId),
                            ManagementBatchController.KIND_CONTEXT,
                            context.id,
                            () -> openContext(context.id)
                        );
                    }
                }
                break;
            case TAB_GROUPS:
                for (ManagementHomeData.GroupItem group : snapshot.groups) {
                    if (matches(query, group.displayName, group.searchText)) {
                        int visibleContexts = 0;
                        for (ManagementHomeData.GroupContextRef reference
                            : group.contexts) {
                            if (containsContext(reference.contextId)) {
                                visibleContexts++;
                            }
                        }
                        addRow(
                            group.displayName,
                            groupSubtitle(group, visibleContexts),
                            formatUpdatedAt(group.updatedAt),
                            group.id.equals(snapshot.activeGroupId),
                            ManagementBatchController.KIND_GROUP,
                            group.id,
                            () -> openGroup(group.id)
                        );
                    }
                }
                break;
            case TAB_CHARACTERS:
                renderDictionary(
                    snapshot.characters,
                    query,
                    false,
                    ManagementBatchController.KIND_CHARACTER
                );
                break;
            case TAB_TERMS:
                renderDictionary(
                    snapshot.terms,
                    query,
                    true,
                    ManagementBatchController.KIND_TERM
                );
                break;
            default:
                break;
        }
        if (content.getChildCount() == 0) {
            addEmpty(searchQuery.trim().isEmpty()
                ? R.string.management_home_no_records
                : R.string.management_home_search_empty);
        }
        restoreScrollPosition();
    }

    private void renderDictionary(
        List<ManagementHomeData.DictionaryItem> entries,
        String query,
        boolean terms,
        String kind
    ) {
        for (ManagementHomeData.DictionaryItem entry : entries) {
            String title = dictionaryLabel(entry, terms);
            if (!matches(query, title, entry.searchText)) {
                continue;
            }
            String subtitle = dictionarySubtitle(entry, terms);
            if (subtitle.isEmpty() && terms) {
                subtitle = getString(
                    R.string.management_home_term_record
                );
            }
            final String key = entry.key;
            addRow(
                title,
                subtitle,
                dictionarySide(entry, terms),
                false,
                kind,
                key,
                () -> {
                    if (terms) {
                        openTerm(key);
                    } else {
                        openCharacter(key);
                    }
                }
            );
        }
    }

    private void renderPackageDictionary() {
        if (snapshot == null) {
            return;
        }
        if (selectedTab == TAB_CHARACTERS) {
            renderDictionary(
                snapshot.characters,
                searchQuery,
                false,
                ManagementBatchController.KIND_CHARACTER
            );
        } else {
            renderDictionary(
                snapshot.terms,
                searchQuery,
                true,
                ManagementBatchController.KIND_TERM
            );
        }
        if (content.getChildCount() == 0) {
            addEmpty(searchQuery.trim().isEmpty()
                ? R.string.management_home_no_records
                : R.string.management_home_search_empty);
        }
        restoreScrollPosition();
    }

    private void openCharacterCreator() {
        startActivity(new Intent(this, CharacterDictionaryActivity.class)
            .putExtra(CharacterDictionaryActivity.EXTRA_CREATE_CHARACTER, true));
    }

    private void renderTree() {
        String query = searchQuery;
        Set<String> visible = new HashSet<>();
        Set<String> searchExpanded = new HashSet<>();
        for (ManagementHomeData.TreeNode root : snapshot.treeRoots) {
            collectTreeMatches(root, query, visible, searchExpanded);
        }
        for (ManagementHomeData.TreeNode root : snapshot.treeRoots) {
            if (!visible.contains(root.key)) {
                continue;
            }
            if (root.kind == ManagementHomeData.NODE_GROUP
                || root.kind == ManagementHomeData.NODE_UNCATEGORIZED) {
                MaterialCardView surface = createCard(0);
                LinearLayout treeBody = new LinearLayout(this);
                treeBody.setOrientation(LinearLayout.VERTICAL);
                surface.addView(treeBody);
                renderTreeNode(
                    root,
                    0,
                    visible,
                    searchExpanded,
                    treeBody
                );
            } else {
                renderTreeNode(
                    root,
                    0,
                    visible,
                    searchExpanded,
                    content
                );
            }
        }
        if (content.getChildCount() == 0) {
            addEmpty(
                query.trim().isEmpty()
                    ? R.string.management_home_tree_empty
                    : R.string.management_home_search_empty
            );
        }
        restoreScrollPosition();
    }

    private boolean collectTreeMatches(
        ManagementHomeData.TreeNode node,
        String query,
        Set<String> visible,
        Set<String> searchExpanded
    ) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            visible.add(node.key);
            markTreeSubtreeVisible(node, visible);
            return true;
        }
        boolean selfMatches = matches(
            normalized,
            treeNodeLabel(node),
            treeNodeSearchText(node)
        );
        boolean childMatches = false;
        for (ManagementHomeData.TreeNode child : node.children) {
            childMatches |= collectTreeMatches(
                child,
                normalized,
                visible,
                searchExpanded
            );
        }
        if (selfMatches) {
            markTreeSubtreeVisible(node, visible);
            if (!node.children.isEmpty()) {
                searchExpanded.add(node.key);
            }
            return true;
        }
        if (childMatches) {
            visible.add(node.key);
            if (!node.children.isEmpty()) {
                searchExpanded.add(node.key);
            }
            return true;
        }
        return false;
    }

    private void markTreeSubtreeVisible(
        ManagementHomeData.TreeNode node,
        Set<String> visible
    ) {
        visible.add(node.key);
        for (ManagementHomeData.TreeNode child : node.children) {
            markTreeSubtreeVisible(child, visible);
        }
    }

    private void renderTreeNode(
        ManagementHomeData.TreeNode node,
        int level,
        Set<String> visible,
        Set<String> searchExpanded,
        ViewGroup parent
    ) {
        if (!visible.contains(node.key)) {
            return;
        }
        boolean expanded = expandedNodes.contains(node.key)
            || searchExpanded.contains(node.key);
        boolean active = node.kind == ManagementHomeData.NODE_CONTEXT
            && node.targetId != null
            && node.targetId.equals(snapshot.activeContextId)
            || node.kind == ManagementHomeData.NODE_GROUP
                && node.targetId != null
                && node.targetId.equals(snapshot.activeGroupId);
        addTreeRow(parent, node, level, expanded, active);
        if (!expanded) {
            return;
        }
        for (ManagementHomeData.TreeNode child : node.children) {
            renderTreeNode(
                child,
                level + 1,
                visible,
                searchExpanded,
                parent
            );
        }
    }

    private void addTreeRow(
        ViewGroup parent,
        ManagementHomeData.TreeNode node,
        int level,
        boolean expanded,
        boolean active
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int rowHeight = node.kind == ManagementHomeData.NODE_GROUP
            || node.kind == ManagementHomeData.NODE_UNCATEGORIZED
            ? 60
            : node.kind == ManagementHomeData.NODE_CONTEXT ? 52 : 40;
        row.setMinimumHeight(dp(rowHeight));
        row.setPadding(dp(8 + level * 20), dp(3), dp(8), dp(3));

        String batchKind = treeBatchKind(node);
        if (batchMode && batchKind != null && node.targetId != null) {
            CheckBox check = new CheckBox(this);
            String key = batchKind + ":" + node.targetId;
            boolean ready = batchDataSource.isItemReady(
                batchKind,
                node.targetId
            );
            check.setChecked(batchSelection.contains(key));
            check.setEnabled(ready);
            batchChecks.add(new BatchCheckBinding(batchKind, node.targetId, check));
            check.setClickable(false);
            check.setFocusable(false);
            check.setContentDescription(getString(
                R.string.management_home_batch_select,
                treeNodeLabel(node)
            ));
            row.addView(check, new LinearLayout.LayoutParams(dp(44), dp(40)));
            batchDataSource.markVisible(batchKind, node.targetId);
        }

        MaterialButton disclosure = new MaterialButton(this);
        disclosure.setText("");
        disclosure.setGravity(Gravity.CENTER);
        disclosure.setIconResource(R.drawable.ic_task_chevron);
        disclosure.setIconTint(android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.het_primary)
        ));
        disclosure.setIconSize(dp(16));
        disclosure.setIconPadding(0);
        disclosure.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        disclosure.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.TRANSPARENT
            )
        );
        disclosure.setStrokeWidth(0);
        disclosure.setInsetTop(0);
        disclosure.setInsetBottom(0);
        disclosure.setPadding(0, 0, 0, 0);
        disclosure.setMinWidth(0);
        disclosure.setMinHeight(0);
        disclosure.setStateListAnimator(null);
        disclosure.setRotation(expanded ? 90f : 0f);
        disclosure.setVisibility(
            node.children.isEmpty() ? View.INVISIBLE : View.VISIBLE
        );
        disclosure.setContentDescription(getString(
            expanded
                ? R.string.management_home_collapse_node
                : R.string.management_home_expand_node,
            treeNodeLabel(node)
        ));
        row.addView(disclosure, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(textView(
            treeNodeTitle(node, active),
            treeNodeTitleSize(node),
            true
        ));
        TextView subtitle = textView(
            treeNodeSubtitle(node),
            treeNodeSubtitleSize(node),
            false
        );
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        copy.addView(subtitle, subtitleParams);
        row.addView(copy, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1
        ));

        if (!node.children.isEmpty()) {
            disclosure.setOnClickListener(view -> toggleNode(node.key));
            if (node.targetId == null) {
                row.setOnClickListener(view -> toggleNode(node.key));
            }
        }
        if (node.targetId != null) {
            row.setClickable(true);
            row.setFocusable(true);
            if (batchMode && batchKind != null) {
                row.setOnClickListener(view -> toggleBatchSelection(
                    batchKind,
                    node.targetId
                ));
            } else {
                row.setOnClickListener(view -> openTreeNode(node));
            }
            TextView title = (TextView) copy.getChildAt(0);
            if (!batchMode) {
                title.setFocusable(true);
                title.setClickable(true);
                title.setOnClickListener(view -> openTreeNode(node));
            }
        }
        if (active) {
            row.setBackgroundColor(
                ContextCompat.getColor(this, R.color.het_good_container)
            );
        }
        parent.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void toggleNode(String key) {
        rememberScrollPosition();
        if (expandedNodes.contains(key)) {
            expandedNodes.remove(key);
        } else {
            expandedNodes.add(key);
        }
        render();
    }

    private MaterialCardView createCard(int level) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(
            ContextCompat.getColor(this, R.color.het_surface_container)
        );
        card.setRadius(dp(14));
        card.setCardElevation(0);
        card.setStrokeWidth(0);
        card.setMinimumHeight(dp(56));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(9);
        params.leftMargin = dp(level * 20);
        content.addView(card, params);
        return card;
    }

    private void addRow(
        String title,
        String subtitle,
        String side,
        boolean active,
        String kind,
        String canonicalId,
        Runnable action
    ) {
        MaterialCardView card = createCard(0);
        if (batchMode && kind != null && canonicalId != null) {
            addBatchRow(
                card,
                title,
                subtitle,
                side,
                active,
                kind,
                canonicalId
            );
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(textView(
            active
                ? getString(R.string.management_home_active_name, title)
                : title,
            12,
            true
        ));
        TextView subtitleView = textView(subtitle, 10, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        copy.addView(subtitleView, subtitleParams);
        row.addView(copy, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1
        ));
        if (side != null && !side.trim().isEmpty()) {
            TextView sideView = textView(side, 10, false);
            sideView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            sideView.setMaxLines(2);
            sideView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(sideView, new LinearLayout.LayoutParams(
                dp(86),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        card.addView(row);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> action.run());
        if (active) {
            applyActiveBackground(card);
        }
    }

    private void addBatchRow(
        MaterialCardView card,
        String title,
        String subtitle,
        String side,
        boolean active,
        String kind,
        String canonicalId
    ) {
        String key = kind + ":" + canonicalId;
        boolean ready = batchDataSource.isItemReady(kind, canonicalId);
        batchDataSource.markVisible(kind, canonicalId);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(12), dp(6));

        CheckBox check = new CheckBox(this);
        check.setChecked(batchSelection.contains(key));
        check.setEnabled(ready);
        batchChecks.add(new BatchCheckBinding(kind, canonicalId, check));
        check.setClickable(false);
        check.setFocusable(false);
        check.setContentDescription(getString(
            R.string.management_home_batch_select,
            title
        ));
        row.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(textView(
            active
                ? getString(R.string.management_home_active_name, title)
                : title,
            12,
            true
        ));
        TextView subtitleView = textView(subtitle, 10, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        copy.addView(subtitleView, subtitleParams);
        row.addView(copy, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1
        ));
        if (side != null && !side.trim().isEmpty()) {
            TextView sideView = textView(side, 10, false);
            sideView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            sideView.setMaxLines(2);
            sideView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(sideView, new LinearLayout.LayoutParams(
                dp(86),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        card.addView(row);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> toggleBatchSelection(
            kind,
            canonicalId
        ));
        if (active) {
            applyActiveBackground(card);
        }
    }

    private void toggleBatchSelection(String kind, String canonicalId) {
        if (!batchMode || managementBatchController == null
            || !managementBatchController.isActive()
            || !batchDataSource.isItemReady(kind, canonicalId)) {
            return;
        }
        String key = kind + ":" + canonicalId;
        batchSelection.set(
            key,
            !batchSelection.contains(key)
        );
        updateBatchChecks(key);
        notifyBatchHostRowsChanged();
    }

    private String treeBatchKind(ManagementHomeData.TreeNode node) {
        if (node == null || node.targetId == null) {
            return null;
        }
        switch (node.kind) {
            case ManagementHomeData.NODE_GROUP:
                return ManagementBatchController.KIND_GROUP;
            case ManagementHomeData.NODE_CONTEXT:
                return ManagementBatchController.KIND_CONTEXT;
            case ManagementHomeData.NODE_SCENE:
                return ManagementBatchController.KIND_SCENE;
            default:
                return null;
        }
    }

    private void applyActiveBackground(MaterialCardView card) {
        card.setCardBackgroundColor(
            ContextCompat.getColor(this, R.color.het_good_container)
        );
    }

    private TextView textView(String value, int sizeSp, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp + 2f);
        text.setTextColor(ContextCompat.getColor(
            this,
            bold ? R.color.het_on_surface : R.color.het_on_surface_muted
        ));
        if (bold) {
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return text;
    }

    private void addEmpty(int resourceId) {
        TextView empty = textView(getString(resourceId), 14, false);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(12), dp(24), dp(12), dp(24));
        content.addView(empty, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addError(String message) {
        TextView error = textView(message, 14, false);
        error.setTextColor(ContextCompat.getColor(this, R.color.het_error));
        error.setGravity(Gravity.CENTER_HORIZONTAL);
        error.setPadding(dp(12), dp(24), dp(12), dp(24));
        content.addView(error, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private String treeNodeTitle(
        ManagementHomeData.TreeNode node,
        boolean active
    ) {
        String label = treeNodeLabel(node);
        return active
            ? getString(R.string.management_home_active_name, label)
            : label;
    }

    private int treeNodeTitleSize(ManagementHomeData.TreeNode node) {
        return node.kind == ManagementHomeData.NODE_GROUP
            || node.kind == ManagementHomeData.NODE_UNCATEGORIZED
            ? 12
            : node.kind == ManagementHomeData.NODE_CONTEXT ? 11 : 10;
    }

    private int treeNodeSubtitleSize(ManagementHomeData.TreeNode node) {
        return node.kind == ManagementHomeData.NODE_SCENE ? 9 : 10;
    }

    private String treeNodeLabel(ManagementHomeData.TreeNode node) {
        return node.kind == ManagementHomeData.NODE_UNCATEGORIZED
            ? getString(R.string.management_home_uncategorized)
            : node.label;
    }

    private String treeNodeSubtitle(ManagementHomeData.TreeNode node) {
        String summary = node.summary;
        String updated = formatUpdatedAt(node.updatedAt);
        String detail = joinSummary(summary, updated);
        if (!detail.isEmpty()) {
            return getString(
                R.string.management_rebuild_tree_node_detail,
                treeNodeCountLabel(node),
                detail
            );
        }
        switch (node.kind) {
            case ManagementHomeData.NODE_GROUP:
                return getString(R.string.management_home_group_summary, node.count);
            case ManagementHomeData.NODE_CONTEXT:
                return getString(
                    R.string.management_home_context_scene_summary,
                    node.count
                );
            case ManagementHomeData.NODE_SCENE:
                return getString(
                    R.string.management_home_scene_summary,
                    node.count
                );
            case ManagementHomeData.NODE_UNCATEGORIZED:
                return getString(
                    R.string.management_home_uncategorized_summary,
                    node.count
                );
            default:
                return "";
        }
    }

    private String treeNodeSearchText(ManagementHomeData.TreeNode node) {
        return node.searchText;
    }

    private String treeNodeCountLabel(ManagementHomeData.TreeNode node) {
        switch (node.kind) {
            case ManagementHomeData.NODE_GROUP:
                return getString(R.string.management_home_group_summary, node.count);
            case ManagementHomeData.NODE_CONTEXT:
                return getString(
                    R.string.management_home_context_scene_summary,
                    node.count
                );
            case ManagementHomeData.NODE_SCENE:
                return getString(
                    R.string.management_home_scene_summary,
                    node.count
                );
            case ManagementHomeData.NODE_UNCATEGORIZED:
                return getString(
                    R.string.management_home_uncategorized_summary,
                    node.count
                );
            default:
                return "";
        }
    }

    private String sceneSearchText(ManagementHomeData.SceneItem scene) {
        StringBuilder text = new StringBuilder(scene.searchText);
        for (ManagementHomeData.ContextItem context : snapshot.contexts) {
            for (ManagementHomeData.SceneRef sceneRef : context.scenes) {
                if (scene.name.equals(sceneRef.sceneName)) {
                    text.append('\n').append(context.displayName);
                    text.append('\n').append(context.searchText);
                }
            }
        }
        for (ManagementHomeData.GroupItem group : snapshot.groups) {
            for (ManagementHomeData.GroupContextRef reference : group.contexts) {
                for (ManagementHomeData.ContextItem context : snapshot.contexts) {
                    if (reference.contextId.equals(context.id)) {
                        for (ManagementHomeData.SceneRef sceneRef : context.scenes) {
                            if (scene.name.equals(sceneRef.sceneName)) {
                                text.append('\n').append(group.displayName);
                                text.append('\n').append(group.searchText);
                            }
                        }
                    }
                }
            }
        }
        return text.toString();
    }

    private String sceneSubtitle(ManagementHomeData.SceneItem scene) {
        String contextNames = scene == null ? "" : scene.contextNames;
        contextNames = contextNames == null ? "" : contextNames.trim();
        if (contextNames.isEmpty()) {
            contextNames = getString(R.string.management_home_uncategorized);
        } else {
            contextNames = contextNames.replace('\n', '、');
        }
        return getString(
            R.string.management_rebuild_scene_contexts,
            contextNames
        ) + "\n" + getString(
            R.string.management_rebuild_scene_translations,
            sceneLanguageText(scene == null ? null : scene.languages)
        );
    }

    private String sceneRowSide(ManagementHomeData.SceneItem scene) {
        return formatUpdatedAt(scene == null ? 0L : scene.updatedAt);
    }

    private String sceneLanguageText(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return getString(R.string.management_rebuild_no_translated_languages);
        }
        List<String> labels = new ArrayList<>();
        for (String language : languages) {
            labels.add(languageLabel(language));
        }
        return android.text.TextUtils.join("、", labels);
    }

    private String languageLabel(String language) {
        if (language == null || language.trim().isEmpty()) return "—";
        if ("zh-cn".equalsIgnoreCase(language)) return "简体中文";
        if ("zh-tw".equalsIgnoreCase(language)) return "繁体中文";
        if ("en".equalsIgnoreCase(language)) return "English";
        if ("ja".equalsIgnoreCase(language)
            || "ja-jp".equalsIgnoreCase(language)) return "日本語";
        if ("ko".equalsIgnoreCase(language)) return "한국어";
        return language;
    }

    private String contextSubtitle(ManagementHomeData.ContextItem context) {
        String base = getString(
            R.string.management_home_context_summary,
            context.scenes.size(),
            context.groupCount
        );
        String detail = joinSummary(
            context.summary,
            ""
        );
        return detail.isEmpty() ? base : base + " · " + detail;
    }

    private String groupSubtitle(
        ManagementHomeData.GroupItem group,
        int visibleContexts
    ) {
        String base = getString(
            R.string.management_home_group_summary,
            visibleContexts
        );
        String detail = joinSummary(
            group.summary,
            ""
        );
        return detail.isEmpty() ? base : base + " · " + detail;
    }

    private String formatUpdatedAt(long updatedAt) {
        if (updatedAt <= 0L) {
            return "";
        }
        return DateUtils.getRelativeTimeSpanString(
            updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString();
    }

    private String joinSummary(String first, String second) {
        String left = first == null ? "" : first.trim();
        String right = second == null ? "" : second.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + " · " + right;
    }

    private String dictionaryLabel(
        ManagementHomeData.DictionaryItem entry,
        boolean terms
    ) {
        if (!terms && "mc".equals(entry.key)) {
            return getString(R.string.management_batch_main_character_label);
        }
        return entry.subtitle.isEmpty() ? entry.key : entry.subtitle;
    }

    private String dictionarySubtitle(
        ManagementHomeData.DictionaryItem entry,
        boolean terms
    ) {
        if (!terms && "mc".equals(entry.key)) {
            return getString(R.string.mc_settings);
        }
        return entry.key.equals(entry.subtitle) || entry.key.isEmpty()
            ? ""
            : entry.key;
    }

    private String dictionarySide(
        ManagementHomeData.DictionaryItem entry,
        boolean terms
    ) {
        if (terms || entry == null) {
            return "";
        }
        if (entry.aliasCount == 0) {
            return getString(R.string.management_rebuild_no_aliases);
        }
        return getString(
            R.string.management_rebuild_alias_count,
            entry.aliasCount
        );
    }

    private boolean containsContext(String contextId) {
        for (ManagementHomeData.ContextItem context : snapshot.contexts) {
            if (context.id.equals(contextId)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String query, String title, String searchText) {
        String needle = query == null
            ? ""
            : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        String haystack = ((title == null ? "" : title) + "\n"
            + (searchText == null ? "" : searchText)).toLowerCase(Locale.ROOT);
        return haystack.contains(needle);
    }

    private void openTreeNode(ManagementHomeData.TreeNode node) {
        if (node.kind == ManagementHomeData.NODE_SCENE) {
            openScene(node.targetId);
        } else if (node.kind == ManagementHomeData.NODE_CONTEXT) {
            openContext(node.targetId);
        } else if (node.kind == ManagementHomeData.NODE_GROUP) {
            openGroup(node.targetId);
        }
    }

    private void openScene(String sceneName) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_SCENE_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName));
    }

    private void openContext(String contextId) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_CONTEXT_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, ContextManagementDetailActivity.class)
            .putExtra(ContextManagementDetailActivity.EXTRA_KIND, "context")
            .putExtra(ContextManagementDetailActivity.EXTRA_ID, contextId));
    }

    private void openGroup(String groupId) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_GROUP_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, ContextManagementDetailActivity.class)
            .putExtra(ContextManagementDetailActivity.EXTRA_KIND, "group")
            .putExtra(ContextManagementDetailActivity.EXTRA_ID, groupId));
    }

    private void openCharacter(String name) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_CHARACTER_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, DictionaryManagementDetailActivity.class)
            .putExtra(DictionaryManagementDetailActivity.EXTRA_KIND, "character")
            .putExtra(DictionaryManagementDetailActivity.EXTRA_KEY, name));
    }

    private void openTerm(String name) {
        if (stylePreview) {
            startActivity(StylePreview.intentFor(
                this,
                StylePreview.KIND_TERM_DETAIL
            ));
            return;
        }
        startActivity(new Intent(this, DictionaryManagementDetailActivity.class)
            .putExtra(DictionaryManagementDetailActivity.EXTRA_KIND, "term")
            .putExtra(DictionaryManagementDetailActivity.EXTRA_KEY, name));
    }

    private void open(Class<?> target) {
        startActivity(new Intent(this, target));
    }

    private void syncSearchFromState() {
        if (searchInput == null) {
            return;
        }
        String query = searchQuery;
        updatingSearch = true;
        searchInput.setText(query == null ? "" : query);
        if (searchInput.getText() != null) {
            searchInput.setSelection(searchInput.getText().length());
        }
        updatingSearch = false;
    }

    private void rememberScrollPosition() {
        if (scrollView != null) {
            scrollPositions[scrollIndex()] = scrollView.getScrollY();
        }
    }

    private void restoreScrollPosition() {
        if (scrollView == null) {
            return;
        }
        final long expectedRender = renderGeneration;
        final int expectedIndex = scrollIndex();
        final int position = Math.max(0, scrollPositions[scrollIndex()]);
        scrollView.post(() -> scrollView.post(() -> {
            if (expectedRender != renderGeneration
                || expectedIndex != scrollIndex()) {
                return;
            }
            scrollView.scrollTo(0, position);
        }));
    }

    private int scrollIndex() {
        return mode == MODE_TREE && selectedTab == TAB_SCENES
            ? TAB_COUNT
            : selectedTab;
    }

    private void restoreState(Bundle state) {
        for (int index = 0; index < tabQueries.length; index++) {
            tabQueries[index] = "";
        }
        if (state == null) {
            return;
        }
        mode = state.getInt(STATE_MODE, MODE_LIST) == MODE_TREE
            ? MODE_TREE
            : MODE_LIST;
        selectedTab = clampTab(state.getInt(STATE_TAB, TAB_SCENES));
        if (mode == MODE_TREE
            && selectedTab != TAB_SCENES
            && selectedTab != TAB_CHARACTERS
            && selectedTab != TAB_TERMS) {
            selectedTab = TAB_SCENES;
        }
        String savedSearch = state.getString(STATE_SEARCH, null);
        String[] savedQueries = state.getStringArray(STATE_QUERIES);
        if (savedQueries != null) {
            for (int index = 0; index < tabQueries.length; index++) {
                if (index < savedQueries.length && savedQueries[index] != null) {
                    tabQueries[index] = savedQueries[index];
                }
            }
        }
        treeQuery = state.getString(STATE_TREE_QUERY, "");
        if (savedSearch != null) {
            searchQuery = savedSearch;
        } else if (!treeQuery.trim().isEmpty()) {
            searchQuery = treeQuery;
        } else {
            for (String query : tabQueries) {
                if (query != null && !query.trim().isEmpty()) {
                    searchQuery = query;
                    break;
                }
            }
        }
        java.util.ArrayList<String> savedExpanded = state.getStringArrayList(STATE_EXPANDED);
        if (savedExpanded != null) {
            expandedNodes.addAll(savedExpanded);
        }
        int[] savedScroll = state.getIntArray(STATE_SCROLL);
        if (savedScroll != null) {
            System.arraycopy(
                savedScroll,
                0,
                scrollPositions,
                0,
                Math.min(savedScroll.length, scrollPositions.length)
            );
        }
    }

    private int clampTab(int value) {
        return Math.max(TAB_SCENES, Math.min(TAB_TERMS, value));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        rememberScrollPosition();
        if (managementBatchController != null) {
            managementBatchController.saveState(outState);
        }
        outState.putInt(STATE_MODE, mode);
        outState.putInt(STATE_TAB, selectedTab);
        outState.putString(STATE_SEARCH, searchQuery);
        outState.putStringArray(STATE_QUERIES, tabQueries);
        outState.putString(STATE_TREE_QUERY, treeQuery);
        outState.putStringArrayList(
            STATE_EXPANDED,
            new java.util.ArrayList<>(expandedNodes)
        );
        outState.putIntArray(STATE_SCROLL, scrollPositions);
        super.onSaveInstanceState(outState);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        lifecycleStarted = false;
        loadGeneration++;
        if (managementBatchController != null) {
            managementBatchController.close();
            managementBatchController = null;
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
