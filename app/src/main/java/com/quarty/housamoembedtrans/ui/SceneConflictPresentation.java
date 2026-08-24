package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.storage.SceneStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable, UI-neutral projection of one complete Scene conflict.
 *
 * <p>This class never reads conflict files itself. Callers must first read the
 * exact {@code ConflictRecord} candidates and validate both byte arrays with
 * {@link SceneStore#validate(byte[])}. Accepting {@link SceneStore.ValidatedScene}
 * here makes that validation boundary explicit before JSON is parsed for
 * presentation.</p>
 */
public final class SceneConflictPresentation {

    /** Stable identity shared by all recursively nested Scene items. */
    public static final class OrderKey implements Comparable<OrderKey> {
        public final int labelIndex;
        public final int pageNo;
        public final int commandIndex;
        public final int subIndex;

        private OrderKey(
            int labelIndex,
            int pageNo,
            int commandIndex,
            int subIndex
        ) {
            this.labelIndex = labelIndex;
            this.pageNo = pageNo;
            this.commandIndex = commandIndex;
            this.subIndex = subIndex;
        }

        @Override
        public int compareTo(OrderKey other) {
            int result = Integer.compare(labelIndex, other.labelIndex);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(pageNo, other.pageNo);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(commandIndex, other.commandIndex);
            return result != 0
                ? result
                : Integer.compare(subIndex, other.subIndex);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof OrderKey)) {
                return false;
            }
            OrderKey other = (OrderKey) value;
            return labelIndex == other.labelIndex
                && pageNo == other.pageNo
                && commandIndex == other.commandIndex
                && subIndex == other.subIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(labelIndex, pageNo, commandIndex, subIndex);
        }

        private String internalKey() {
            return labelIndex + ":" + pageNo + ":" + commandIndex + ":" + subIndex;
        }
    }

    /** Root-level language and translation metadata for one candidate. */
    public static final class SideSummary {
        public final String rawLanguage;
        public final String targetLanguage;
        public final List<String> languages;
        public final Map<String, Boolean> translated;
        public final Map<String, String> summaries;
        public final Map<String, String> providers;
        public final Map<String, String> models;

        private SideSummary(
            String rawLanguage,
            String targetLanguage,
            Set<String> languages,
            Map<String, Boolean> translated,
            Map<String, String> summaries,
            Map<String, String> providers,
            Map<String, String> models
        ) {
            this.rawLanguage = rawLanguage;
            this.targetLanguage = targetLanguage;
            this.languages = Collections.unmodifiableList(
                new ArrayList<>(languages)
            );
            this.translated = immutableMap(translated);
            this.summaries = immutableMap(summaries);
            this.providers = immutableMap(providers);
            this.models = immutableMap(models);
        }
    }

    /** One side of an aligned text item. Null means that side has no item. */
    public static final class TextSide {
        public final String speaker;
        public final String originalText;
        public final Map<String, String> translations;

        private TextSide(
            String speaker,
            String originalText,
            Map<String, String> translations
        ) {
            this.speaker = speaker;
            this.originalText = originalText;
            this.translations = immutableMap(translations);
        }
    }

    /** Text candidates aligned solely by the complete stable OrderKey. */
    public static final class AlignedText {
        public final OrderKey order;
        public final TextSide game;
        public final TextSide het;

        private AlignedText(OrderKey order, TextSide game, TextSide het) {
            this.order = order;
            this.game = game;
            this.het = het;
        }
    }

    public final String sceneName;
    public final SideSummary game;
    public final SideSummary het;
    public final int structureChangeCount;
    public final int originalChangeCount;
    public final int translationChangeCount;
    public final List<AlignedText> alignedTexts;

    private SceneConflictPresentation(
        String sceneName,
        SideSummary game,
        SideSummary het,
        int structureChangeCount,
        int originalChangeCount,
        int translationChangeCount,
        List<AlignedText> alignedTexts
    ) {
        this.sceneName = sceneName;
        this.game = game;
        this.het = het;
        this.structureChangeCount = structureChangeCount;
        this.originalChangeCount = originalChangeCount;
        this.translationChangeCount = translationChangeCount;
        this.alignedTexts = Collections.unmodifiableList(
            new ArrayList<>(alignedTexts)
        );
    }

    /**
     * Parses two already validated candidates and produces a deterministic
     * projection. Both validated Scene names must match the conflict identity.
     */
    public static SceneConflictPresentation fromValidatedCandidates(
        String sceneName,
        SceneStore.ValidatedScene gameCandidate,
        SceneStore.ValidatedScene hetCandidate
    ) throws JSONException {
        if (sceneName == null || sceneName.isEmpty()
            || gameCandidate == null || hetCandidate == null) {
            throw new IllegalArgumentException(
                "sceneName and both validated candidates are required"
            );
        }
        if (!sceneName.equals(gameCandidate.sceneName)
            || !sceneName.equals(hetCandidate.sceneName)) {
            throw new IllegalArgumentException(
                "candidate Scene names do not match the conflict identity"
            );
        }

        CandidateProjection game = parse(gameCandidate.bytes);
        CandidateProjection het = parse(hetCandidate.bytes);
        TreeSet<OrderKey> structureOrders = new TreeSet<>();
        structureOrders.addAll(game.structures.keySet());
        structureOrders.addAll(het.structures.keySet());
        int structureChanges = 0;
        for (OrderKey order : structureOrders) {
            if (!Objects.equals(
                game.structures.get(order),
                het.structures.get(order)
            )) {
                structureChanges++;
            }
        }

        TreeSet<OrderKey> textOrders = new TreeSet<>();
        textOrders.addAll(game.texts.keySet());
        textOrders.addAll(het.texts.keySet());
        ArrayList<AlignedText> aligned = new ArrayList<>(textOrders.size());
        int originalChanges = 0;
        int translationChanges = 0;
        for (OrderKey order : textOrders) {
            TextSide gameText = game.texts.get(order);
            TextSide hetText = het.texts.get(order);
            aligned.add(new AlignedText(order, gameText, hetText));
            if (!sameOriginal(gameText, hetText)) {
                originalChanges++;
            }
            if (!sameTranslations(gameText, hetText)) {
                translationChanges++;
            }
        }

        return new SceneConflictPresentation(
            sceneName,
            game.summary,
            het.summary,
            structureChanges,
            originalChanges,
            translationChanges,
            aligned
        );
    }

    private static CandidateProjection parse(byte[] bytes) throws JSONException {
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        TreeMap<OrderKey, StructureNode> structures = new TreeMap<>();
        TreeMap<OrderKey, TextSide> texts = new TreeMap<>();
        TreeSet<String> languages = new TreeSet<>();

        Map<String, Boolean> translated = booleanMap(root.getJSONObject("translated"));
        Map<String, String> summaries = stringMap(root.getJSONObject("summary"));
        Map<String, String> providers = stringMap(root.getJSONObject("provider"));
        Map<String, String> models = stringMap(root.getJSONObject("model"));
        languages.addAll(translated.keySet());
        languages.addAll(summaries.keySet());
        languages.addAll(providers.keySet());
        languages.addAll(models.keySet());

        walkItems(
            root.getJSONArray("scene_items"),
            "root",
            structures,
            texts,
            languages
        );
        String targetLanguage = root.optString("target_lang", "");
        if (!targetLanguage.isEmpty()) {
            languages.add(targetLanguage);
        }
        return new CandidateProjection(
            new SideSummary(
                root.getString("raw_lang"),
                targetLanguage,
                languages,
                translated,
                summaries,
                providers,
                models
            ),
            structures,
            texts
        );
    }

    private static void walkItems(
        JSONArray items,
        String container,
        TreeMap<OrderKey, StructureNode> structures,
        TreeMap<OrderKey, TextSide> texts,
        Set<String> languages
    ) throws JSONException {
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String type = item.getString("type");
            OrderKey order = orderKey(item.getJSONObject("order"));
            ArrayList<String> metadata = new ArrayList<>();
            if ("text".equals(type)) {
                Map<String, String> translations = stringMap(
                    item.getJSONObject("translations")
                );
                languages.addAll(translations.keySet());
                TextSide previous = texts.put(
                    order,
                    new TextSide(
                        item.getString("speaker"),
                        item.getString("text"),
                        translations
                    )
                );
                if (previous != null) {
                    throw new JSONException(
                        "duplicate text OrderKey " + order.internalKey()
                    );
                }
            } else if ("choice".equals(type)) {
                metadata.add(item.getString("merge_label"));
                JSONArray branches = item.getJSONArray("branches");
                metadata.add(Integer.toString(branches.length()));
                for (int branchIndex = 0; branchIndex < branches.length(); branchIndex++) {
                    JSONObject branch = branches.getJSONObject(branchIndex);
                    metadata.add(branch.getString("target_label"));
                    JSONArray options = branch.getJSONArray("options");
                    JSONArray following = branch.getJSONArray("following_text");
                    appendChildOrders(metadata, options);
                    appendChildOrders(metadata, following);
                    String branchContainer = childContainer(
                        container,
                        order,
                        "branch",
                        branchIndex
                    );
                    walkItems(
                        options,
                        branchContainer + "/options",
                        structures,
                        texts,
                        languages
                    );
                    walkItems(
                        following,
                        branchContainer + "/following",
                        structures,
                        texts,
                        languages
                    );
                }
            } else if ("if".equals(type)) {
                metadata.add(item.getString("condition"));
                metadata.add(item.getString("target_label"));
                metadata.add(item.getString("merge_label"));
                JSONArray following = item.getJSONArray("following_text");
                appendChildOrders(metadata, following);
                walkItems(
                    following,
                    childContainer(container, order, "following", 0),
                    structures,
                    texts,
                    languages
                );
            } else {
                throw new JSONException("unsupported Scene item type");
            }

            StructureNode previous = structures.put(
                order,
                new StructureNode(type, container, metadata)
            );
            if (previous != null) {
                throw new JSONException(
                    "duplicate Scene item OrderKey " + order.internalKey()
                );
            }
        }
    }

    private static void appendChildOrders(
        List<String> metadata,
        JSONArray children
    ) throws JSONException {
        metadata.add(Integer.toString(children.length()));
        for (int index = 0; index < children.length(); index++) {
            JSONObject child = children.getJSONObject(index);
            metadata.add(child.getString("type"));
            metadata.add(
                orderKey(child.getJSONObject("order")).internalKey()
            );
        }
    }

    private static String childContainer(
        String parent,
        OrderKey order,
        String kind,
        int index
    ) {
        return parent + "/" + order.internalKey() + "/" + kind + "/" + index;
    }

    private static OrderKey orderKey(JSONObject value) throws JSONException {
        return new OrderKey(
            value.getInt("label_index"),
            value.getInt("page_no"),
            value.getInt("cmd_index"),
            value.getInt("sub_index")
        );
    }

    private static Map<String, String> stringMap(JSONObject value)
        throws JSONException {
        TreeMap<String, String> sorted = new TreeMap<>();
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            sorted.put(key, value.getString(key));
        }
        return sorted;
    }

    private static Map<String, Boolean> booleanMap(JSONObject value)
        throws JSONException {
        TreeMap<String, Boolean> sorted = new TreeMap<>();
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            sorted.put(key, value.getBoolean(key));
        }
        return sorted;
    }

    private static boolean sameOriginal(TextSide left, TextSide right) {
        return left != null
            && right != null
            && left.speaker.equals(right.speaker)
            && left.originalText.equals(right.originalText);
    }

    private static boolean sameTranslations(TextSide left, TextSide right) {
        return left != null
            && right != null
            && left.translations.equals(right.translations);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static final class CandidateProjection {
        private final SideSummary summary;
        private final TreeMap<OrderKey, StructureNode> structures;
        private final TreeMap<OrderKey, TextSide> texts;

        private CandidateProjection(
            SideSummary summary,
            TreeMap<OrderKey, StructureNode> structures,
            TreeMap<OrderKey, TextSide> texts
        ) {
            this.summary = summary;
            this.structures = structures;
            this.texts = texts;
        }
    }

    private static final class StructureNode {
        private final String type;
        private final String container;
        private final List<String> metadata;

        private StructureNode(
            String type,
            String container,
            List<String> metadata
        ) {
            this.type = type;
            this.container = container;
            this.metadata = Collections.unmodifiableList(
                new ArrayList<>(metadata)
            );
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof StructureNode)) {
                return false;
            }
            StructureNode other = (StructureNode) value;
            return type.equals(other.type)
                && container.equals(other.container)
                && metadata.equals(other.metadata);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, container, metadata);
        }
    }
}
