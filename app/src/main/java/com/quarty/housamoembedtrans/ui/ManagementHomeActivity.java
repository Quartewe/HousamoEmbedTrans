package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.CheckBox;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.util.HashSet;
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
    private static final String STATE_QUERIES = "management_home.queries";
    private static final String STATE_TREE_QUERY = "management_home.tree_query";
    private static final String STATE_EXPANDED = "management_home.expanded";
    private static final String STATE_SCROLL = "management_home.scroll";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final String[] tabQueries = new String[TAB_COUNT];
    private final int[] scrollPositions = new int[TAB_COUNT + 1];
    private final Set<String> expandedNodes = new HashSet<>();

    private MaterialToolbar toolbar;
    private MenuItem managementViewMenuItem;
    private MenuItem managementClearActiveMenuItem;
    private MenuItem managementMoreMenuItem;
    private TabLayout tabLayout;
    private EditText searchInput;
    private TextView statusView;
    private LinearLayout content;
    private androidx.core.widget.NestedScrollView scrollView;
    private ManagementHomeBatchDataSource batchDataSource;
    private ManagementBatchController managementBatchController;

    private int mode = MODE_LIST;
    private int selectedTab = TAB_SCENES;
    private String treeQuery = "";
    private ManagementHomeData.Snapshot snapshot;
    private long loadGeneration;
    private boolean lifecycleStarted;
    private boolean destroyed;
    private boolean updatingSearch;
    private boolean updatingNavigation;
    private boolean batchMode;
    private boolean clearingActivePointers;
    private long renderGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_management_home);
        SystemBarInsets.apply(findViewById(R.id.root_management_home));

        restoreState(savedInstanceState);
        setupViews();
        setupTabs();
        setupSearch();
        setupManagementActions();
        batchDataSource = new ManagementHomeBatchDataSource(this);
        managementBatchController = ManagementBatchController.attach(
            this,
            findViewById(R.id.container_management_home_batch),
            batchDataSource,
            savedInstanceState
        );
        PrimaryNavigation.attach(
            this,
            findViewById(R.id.primary_navigation),
            PrimaryNavigation.Destination.MANAGEMENT
        );
        render();
    }

    private void setupViews() {
        toolbar = findViewById(R.id.toolbar_management_home);
        toolbar.setTitle(R.string.management_home_title);
        tabLayout = findViewById(R.id.tabs_management_home);
        searchInput = findViewById(R.id.et_management_home_search);
        statusView = findViewById(R.id.tv_management_home_status);
        content = findViewById(R.id.container_management_home_content);
        scrollView = findViewById(R.id.scroll_management_home);
    }

    private void setupTabs() {
        tabLayout.addTab(
            tabLayout.newTab().setText(R.string.management_home_tab_scenes),
            false
        );
        tabLayout.addTab(
            tabLayout.newTab().setText(R.string.management_home_tab_contexts),
            false
        );
        tabLayout.addTab(
            tabLayout.newTab().setText(R.string.management_home_tab_groups),
            false
        );
        tabLayout.addTab(
            tabLayout.newTab().setText(R.string.management_home_tab_characters),
            false
        );
        tabLayout.addTab(
            tabLayout.newTab().setText(R.string.management_home_tab_terms),
            false
        );
        tabLayout.getTabAt(selectedTab).select();
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (!updatingNavigation) {
                    rememberScrollPosition();
                }
                selectedTab = tab.getPosition();
                syncSearchFromState();
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
                if (mode == MODE_TREE) {
                    treeQuery = query;
                } else {
                    tabQueries[selectedTab] = query;
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
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_upload);
        toolbar.setNavigationContentDescription(
            R.string.management_home_io
        );
        toolbar.setNavigationOnClickListener(this::showManagementIoMenu);
        toolbar.inflateMenu(R.menu.menu_management_home);
        managementViewMenuItem = toolbar.getMenu().findItem(
            R.id.action_management_view
        );
        managementClearActiveMenuItem = toolbar.getMenu().findItem(
            R.id.action_management_clear_active
        );
        managementMoreMenuItem = toolbar.getMenu().findItem(
            R.id.action_management_more
        );
        managementViewMenuItem.setOnMenuItemClickListener(item -> {
            toggleViewMode();
            return true;
        });
        managementClearActiveMenuItem.setOnMenuItemClickListener(item -> {
            showClearActivePointersDialog();
            return true;
        });
        managementMoreMenuItem.setOnMenuItemClickListener(item -> {
            showManagementMoreMenu(toolbar);
            return true;
        });
        updateToolbarActions();
    }

    private void showManagementIoMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.START);
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
        importItem.setOnMenuItemClickListener(item -> {
            startActivity(ManagementImportActivity.newIntent(this, false));
            return true;
        });
        exportItem.setOnMenuItemClickListener(item -> {
            enterHomeBatchModeForExport();
            return true;
        });
        popup.show();
    }

    private void showManagementMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        if (mode == MODE_TREE) {
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
        MenuItem scene = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            1,
            2,
            getString(R.string.management_home_more_scene_files)
        );
        MenuItem contexts = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            2,
            3,
            getString(R.string.management_home_more_contexts)
        );
        MenuItem characters = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            3,
            4,
            getString(R.string.management_home_more_characters)
        );
        MenuItem terms = popup.getMenu().add(
            MenuItem.SHOW_AS_ACTION_NEVER,
            4,
            5,
            getString(R.string.management_home_more_terms)
        );
        batch.setOnMenuItemClickListener(item -> {
            enterHomeBatchMode();
            return true;
        });
        scene.setOnMenuItemClickListener(item -> {
            open(SceneFilesActivity.class);
            return true;
        });
        contexts.setOnMenuItemClickListener(item -> {
            open(SceneContextActivity.class);
            return true;
        });
        characters.setOnMenuItemClickListener(item -> {
            open(CharacterDictionaryActivity.class);
            return true;
        });
        terms.setOnMenuItemClickListener(item -> {
            open(GameTermsActivity.class);
            return true;
        });
        popup.show();
    }

    private void toggleViewMode() {
        if (batchMode || destroyed) {
            return;
        }
        rememberScrollPosition();
        mode = mode == MODE_TREE ? MODE_LIST : MODE_TREE;
        syncSearchFromState();
        render();
    }

    private void expandAllTreeNodes() {
        if (mode != MODE_TREE || snapshot == null || batchMode) {
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
        if (mode != MODE_TREE || batchMode) {
            return;
        }
        rememberScrollPosition();
        expandedNodes.clear();
        render();
    }

    private void showClearActivePointersDialog() {
        if (batchMode || clearingActivePointers || !hasActivePointers()) {
            updateToolbarActions();
            return;
        }
        new MaterialAlertDialogBuilder(this)
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
        if (batchMode || clearingActivePointers || !hasActivePointers()) {
            updateToolbarActions();
            return;
        }
        clearingActivePointers = true;
        final long request = ++loadGeneration;
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
        if (managementViewMenuItem != null) {
            managementViewMenuItem.setTitle(
                mode == MODE_TREE
                    ? R.string.management_home_mode_list
                    : R.string.management_home_mode_tree
            );
            managementViewMenuItem.setContentDescription(
                getString(mode == MODE_TREE
                    ? R.string.management_home_mode_list
                    : R.string.management_home_mode_tree)
            );
            managementViewMenuItem.setEnabled(!batchMode && !destroyed);
        }
        if (managementClearActiveMenuItem != null) {
            managementClearActiveMenuItem.setEnabled(
                !batchMode
                    && !clearingActivePointers
                    && !destroyed
                    && hasActivePointers()
            );
        }
        if (managementMoreMenuItem != null) {
            managementMoreMenuItem.setEnabled(!batchMode && !destroyed);
        }
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
        updateToolbarActions();
        render();
    }

    void refreshHomeBatchRows() {
        if (batchMode && !destroyed && !isFinishing() && !isDestroyed()) {
            rememberScrollPosition();
            render();
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
                    batchDataSource.setDisplaySnapshot(loaded);
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
                    batchDataSource.setDisplaySnapshot(null);
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
        tabLayout.setVisibility(mode == MODE_TREE ? View.GONE : View.VISIBLE);
        updateToolbarActions();
        batchDataSource.beginRender();
        content.removeAllViews();
        if (snapshot == null) {
            addEmpty(R.string.management_home_waiting_for_data);
            notifyBatchHostRowsChanged();
            return;
        }
        if (mode == MODE_TREE) {
            renderTree();
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
        String query = tabQueries[selectedTab];
        switch (selectedTab) {
            case TAB_SCENES:
                for (ManagementHomeData.SceneItem scene : snapshot.scenes) {
                    if (matches(query, scene.name, scene.name)) {
                        addRow(
                            scene.name,
                            getString(
                                R.string.management_home_scene_summary,
                                scene.languageCount
                            ),
                            false,
                            ManagementBatchController.KIND_SCENE,
                            scene.name,
                            () -> openScene(scene.name)
                        );
                    }
                }
                break;
            case TAB_CONTEXTS:
                if (!batchMode) addContextCreateEntry(true);
                for (ManagementHomeData.ContextItem context : snapshot.contexts) {
                    if (matches(
                        query,
                        context.displayName,
                        context.displayName
                    )) {
                        addRow(
                            context.displayName,
                            getString(
                                R.string.management_home_context_summary,
                                context.scenes.size(),
                                context.groupCount
                            ),
                            context.id.equals(snapshot.activeContextId),
                            ManagementBatchController.KIND_CONTEXT,
                            context.id,
                            () -> openContext(context.id)
                        );
                    }
                }
                break;
            case TAB_GROUPS:
                if (!batchMode) addContextCreateEntry(false);
                for (ManagementHomeData.GroupItem group : snapshot.groups) {
                    if (matches(query, group.displayName, group.displayName)) {
                        int visibleContexts = 0;
                        for (ManagementHomeData.GroupContextRef reference
                            : group.contexts) {
                            if (containsContext(reference.contextId)) {
                                visibleContexts++;
                            }
                        }
                        addRow(
                            group.displayName,
                            getString(
                                R.string.management_home_group_summary,
                                visibleContexts
                            ),
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
            addEmpty(R.string.management_home_empty);
        }
        restoreScrollPosition();
    }

    private void renderDictionary(
        List<ManagementHomeData.DictionaryItem> entries,
        String query,
        boolean terms,
        String kind
    ) {
        if (!batchMode) {
            if (terms) {
                MaterialCardView card = createCard(0);
                MaterialButton button = homeButton(
                    R.style.Widget_HET_Button_Primary
                );
                button.setText(R.string.add_game_term);
                button.setAllCaps(false);
                button.setOnClickListener(view -> startActivity(
                    new Intent(this, GameTermsActivity.class)
                        .putExtra(GameTermsActivity.EXTRA_CREATE_TERM, true)));
                card.addView(button, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                addCharacterCreateEntry();
            }
        }
        for (ManagementHomeData.DictionaryItem entry : entries) {
            String title = dictionaryLabel(entry, terms);
            if (!matches(query, title, entry.searchText)) {
                continue;
            }
            String subtitle = dictionarySubtitle(entry, terms);
            if (subtitle.isEmpty()) {
                subtitle = getString(
                    terms
                        ? R.string.management_home_term_record
                        : R.string.management_home_character_record
                );
            }
            final String key = entry.key;
            addRow(
                title,
                subtitle,
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

    /** Keeps the character tab's create action one tap away from its list. */
    private void addCharacterCreateEntry() {
        MaterialCardView card = createCard(0);
        MaterialButton button = homeButton(R.style.Widget_HET_Button_Primary);
        button.setText(R.string.management_home_add_character);
        button.setAllCaps(false);
        button.setOnClickListener(view -> openCharacterCreator());
        card.addView(button, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void openCharacterCreator() {
        startActivity(new Intent(this, CharacterDictionaryActivity.class)
            .putExtra(CharacterDictionaryActivity.EXTRA_CREATE_CHARACTER, true));
    }

    private void addContextCreateEntry(boolean context) {
        MaterialCardView card = createCard(0);
        MaterialButton button = homeButton(R.style.Widget_HET_Button_Primary);
        button.setText(context ? R.string.scene_context_new_context
            : R.string.scene_context_new_group);
        button.setAllCaps(false);
        button.setOnClickListener(view -> startActivity(
            new Intent(this, SceneContextActivity.class).putExtra(
                SceneContextActivity.EXTRA_MANAGEMENT_CREATE_KIND,
                context ? "context" : "group")));
        card.addView(button, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void renderTree() {
        String query = treeQuery;
        Set<String> visible = new HashSet<>();
        Set<String> searchExpanded = new HashSet<>();
        for (ManagementHomeData.TreeNode root : snapshot.treeRoots) {
            collectTreeMatches(root, query, visible, searchExpanded);
        }
        for (ManagementHomeData.TreeNode root : snapshot.treeRoots) {
            renderTreeNode(root, 0, visible, searchExpanded);
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
        Set<String> searchExpanded
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
        addTreeRow(node, level, expanded, active);
        if (!expanded) {
            return;
        }
        for (ManagementHomeData.TreeNode child : node.children) {
            renderTreeNode(child, level + 1, visible, searchExpanded);
        }
    }

    private void addTreeRow(
        ManagementHomeData.TreeNode node,
        int level,
        boolean expanded,
        boolean active
    ) {
        MaterialCardView card = createCard(level);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        String batchKind = treeBatchKind(node);
        if (batchMode && batchKind != null && node.targetId != null) {
            CheckBox check = new CheckBox(this);
            String key = batchKind + ":" + node.targetId;
            boolean ready = batchDataSource.isItemReady(
                batchKind,
                node.targetId
            );
            check.setChecked(ManagementBatchSelection.contains(key));
            check.setEnabled(ready);
            check.setClickable(false);
            check.setFocusable(false);
            check.setContentDescription(getString(
                R.string.management_home_batch_select,
                treeNodeLabel(node)
            ));
            row.addView(check, new LinearLayout.LayoutParams(dp(44), dp(40)));
            batchDataSource.markVisible(batchKind, node.targetId);
        }

        TextView disclosure = new TextView(this);
        disclosure.setGravity(Gravity.CENTER);
        disclosure.setText(node.children.isEmpty()
            ? ""
            : getString(
                expanded
                    ? R.string.management_home_collapse_icon
                    : R.string.management_home_expand_icon
            ));
        disclosure.setContentDescription(getString(
            expanded
                ? R.string.management_home_collapse_node
                : R.string.management_home_expand_node,
            treeNodeLabel(node)
        ));
        row.addView(disclosure, new LinearLayout.LayoutParams(dp(32), dp(40)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(textView(treeNodeTitle(node, active), 16, true));
        TextView subtitle = textView(treeNodeSubtitle(node), 13, false);
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
        card.addView(row);

        if (!node.children.isEmpty()) {
            disclosure.setOnClickListener(view -> toggleNode(node.key));
            if (node.targetId == null) {
                card.setOnClickListener(view -> toggleNode(node.key));
            }
        }
        if (node.targetId != null) {
            card.setClickable(true);
            card.setFocusable(true);
            if (batchMode && batchKind != null) {
                card.setOnClickListener(view -> toggleBatchSelection(
                    batchKind,
                    node.targetId
                ));
            } else {
                card.setOnClickListener(view -> openTreeNode(node));
            }
            TextView title = (TextView) copy.getChildAt(0);
            if (!batchMode) {
                title.setFocusable(true);
                title.setClickable(true);
                title.setOnClickListener(view -> openTreeNode(node));
            }
        }
        if (active) {
            applyActiveBackground(card);
        }
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        params.leftMargin = dp(level * 20);
        content.addView(card, params);
        return card;
    }

    private MaterialButton homeButton(int styleResource) {
        MaterialButton button = new MaterialButton(this);
        boolean primary = styleResource == R.style.Widget_HET_Button_Primary;
        button.setAllCaps(false);
        button.setMinHeight(dp(40));
        button.setMinWidth(0);
        button.setCornerRadius(dp(20));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setBackgroundTintList(ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                primary
                    ? R.color.het_primary_container
                    : R.color.het_surface_container_high
            )
        ));
        button.setTextColor(ContextCompat.getColor(
            this,
            primary ? R.color.het_on_primary_container : R.color.het_on_surface
        ));
        button.setStrokeWidth(primary ? 0 : dp(1));
        if (!primary) {
            button.setStrokeColor(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.het_outline_soft)
            ));
        }
        return button;
    }

    private void addRow(
        String title,
        String subtitle,
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
                active,
                kind,
                canonicalId
            );
            return;
        }
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), dp(9), dp(12), dp(9));
        copy.addView(textView(
            active
                ? getString(R.string.management_home_active_name, title)
                : title,
            16,
            true
        ));
        TextView subtitleView = textView(subtitle, 13, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(2);
        copy.addView(subtitleView, subtitleParams);
        card.addView(copy);
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
        row.setPadding(dp(4), dp(4), dp(12), dp(4));

        CheckBox check = new CheckBox(this);
        check.setChecked(ManagementBatchSelection.contains(key));
        check.setEnabled(ready);
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
            16,
            true
        ));
        TextView subtitleView = textView(subtitle, 13, false);
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
        ManagementBatchSelection.set(
            key,
            !ManagementBatchSelection.contains(key)
        );
        batchDataSource.onBatchSelectionChanged();
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
        text.setTextSize(sizeSp);
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

    private String treeNodeTitle(
        ManagementHomeData.TreeNode node,
        boolean active
    ) {
        String label = treeNodeLabel(node);
        return active
            ? getString(R.string.management_home_active_name, label)
            : label;
    }

    private String treeNodeLabel(ManagementHomeData.TreeNode node) {
        return node.kind == ManagementHomeData.NODE_UNCATEGORIZED
            ? getString(R.string.management_home_uncategorized)
            : node.label;
    }

    private String treeNodeSubtitle(ManagementHomeData.TreeNode node) {
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
        return treeNodeLabel(node);
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
        startActivity(new Intent(this, SceneManagementDetailActivity.class)
            .putExtra(SceneManagementDetailActivity.EXTRA_SCENE_NAME, sceneName));
    }

    private void openContext(String contextId) {
        startActivity(new Intent(this, ContextManagementDetailActivity.class)
            .putExtra(ContextManagementDetailActivity.EXTRA_KIND, "context")
            .putExtra(ContextManagementDetailActivity.EXTRA_ID, contextId));
    }

    private void openGroup(String groupId) {
        startActivity(new Intent(this, ContextManagementDetailActivity.class)
            .putExtra(ContextManagementDetailActivity.EXTRA_KIND, "group")
            .putExtra(ContextManagementDetailActivity.EXTRA_ID, groupId));
    }

    private void openCharacter(String name) {
        startActivity(new Intent(this, DictionaryManagementDetailActivity.class)
            .putExtra(DictionaryManagementDetailActivity.EXTRA_KIND, "character")
            .putExtra(DictionaryManagementDetailActivity.EXTRA_KEY, name));
    }

    private void openTerm(String name) {
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
        String query = mode == MODE_TREE ? treeQuery : tabQueries[selectedTab];
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
        return mode == MODE_TREE ? TAB_COUNT : selectedTab;
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
        String[] savedQueries = state.getStringArray(STATE_QUERIES);
        if (savedQueries != null) {
            for (int index = 0; index < tabQueries.length; index++) {
                if (index < savedQueries.length && savedQueries[index] != null) {
                    tabQueries[index] = savedQueries[index];
                }
            }
        }
        treeQuery = state.getString(STATE_TREE_QUERY, "");
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
