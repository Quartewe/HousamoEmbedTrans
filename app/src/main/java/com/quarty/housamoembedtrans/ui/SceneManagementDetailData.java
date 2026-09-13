package com.quarty.housamoembedtrans.ui;

import com.quarty.housamoembedtrans.scene.store.SceneStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only projection used by the Android Scene detail surface.
 *
 * <p>The projection deliberately keeps the source Scene JSON separate from
 * the dictionary records.  A dictionary hit enriches display fields only;
 * an unmatched embedded record remains a temporary Scene entry and cannot be
 * written back through this class.</p>
 */
public final class SceneManagementDetailData {
    private SceneManagementDetailData() {}

    public static final class CharacterEntry {
        public final String role;
        public final String name;
        public final String en;
        public final String zhTw;
        public final String zhCn;
        public final String info;
        public final String description;
        public final String speechStyle;
        public final JSONArray aliases;
        public final JSONArray school;
        public final JSONArray guild;
        public final JSONArray originWorld;
        public final JSONArray relationships;
        public final boolean temporary;

        private CharacterEntry(
            String role,
            String name,
            String en,
            String zhTw,
            String zhCn,
            String info,
            String description,
            String speechStyle,
            JSONArray aliases,
            JSONArray school,
            JSONArray guild,
            JSONArray originWorld,
            JSONArray relationships,
            boolean temporary
        ) {
            this.role = role;
            this.name = name;
            this.en = en;
            this.zhTw = zhTw;
            this.zhCn = zhCn;
            this.info = info;
            this.description = description;
            this.speechStyle = speechStyle;
            this.aliases = aliases;
            this.school = school;
            this.guild = guild;
            this.originWorld = originWorld;
            this.relationships = relationships;
            this.temporary = temporary;
        }

        public boolean isMainCharacter() {
            return "mc".equals(role);
        }
    }

    public static final class MentionedCharacterEntry {
        public final String name;
        public final String en;
        public final String zhTw;
        public final String zhCn;
        public final boolean temporary;

        private MentionedCharacterEntry(
            String name,
            String en,
            String zhTw,
            String zhCn,
            boolean temporary
        ) {
            this.name = name;
            this.en = en;
            this.zhTw = zhTw;
            this.zhCn = zhCn;
            this.temporary = temporary;
        }
    }

    public static final class TermEntry {
        public final String term;
        public final String en;
        public final String zhTw;
        public final String zhCn;
        public final String description;
        public final boolean temporary;

        private TermEntry(
            String term,
            String en,
            String zhTw,
            String zhCn,
            String description,
            boolean temporary
        ) {
            this.term = term;
            this.en = en;
            this.zhTw = zhTw;
            this.zhCn = zhCn;
            this.description = description;
            this.temporary = temporary;
        }
    }

    public static final class SourceItem {
        public final JSONObject source;
        public final int originalIndex;
        public final String orderLabel;
        public final int sequence;

        private SourceItem(
            JSONObject source,
            int originalIndex,
            String orderLabel,
            int sequence
        ) {
            this.source = source;
            this.originalIndex = originalIndex;
            this.orderLabel = orderLabel;
            this.sequence = sequence;
        }
    }

    public static final class SceneData {
        public final JSONObject source;
        public final String sceneName;
        public final String gameVersion;
        public final String rawLanguage;
        public final String targetLanguage;
        public final CharacterEntry mainCharacter;
        public final List<CharacterEntry> highWeightCharacters;
        public final List<CharacterEntry> lowWeightCharacters;
        public final List<MentionedCharacterEntry> mentionedCharacters;
        public final List<TermEntry> terms;
        public final List<SourceItem> sourceItems;
        public final List<JSONObject> protectedTokens;
        public final Map<String, Integer> sequenceByOrder;
        /** Exact language identities reported by SceneStore validation. */
        public final List<String> languages;

        private SceneData(
            JSONObject source,
            String sceneName,
            String gameVersion,
            String rawLanguage,
            String targetLanguage,
            CharacterEntry mainCharacter,
            List<CharacterEntry> highWeightCharacters,
            List<CharacterEntry> lowWeightCharacters,
            List<MentionedCharacterEntry> mentionedCharacters,
            List<TermEntry> terms,
            List<SourceItem> sourceItems,
            List<JSONObject> protectedTokens,
            Map<String, Integer> sequenceByOrder,
            List<String> languages
        ) {
            this.source = source;
            this.sceneName = sceneName;
            this.gameVersion = gameVersion;
            this.rawLanguage = rawLanguage;
            this.targetLanguage = targetLanguage;
            this.mainCharacter = mainCharacter;
            this.highWeightCharacters = Collections.unmodifiableList(
                new ArrayList<>(highWeightCharacters)
            );
            this.lowWeightCharacters = Collections.unmodifiableList(
                new ArrayList<>(lowWeightCharacters)
            );
            this.mentionedCharacters = Collections.unmodifiableList(
                new ArrayList<>(mentionedCharacters)
            );
            this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
            this.sourceItems = Collections.unmodifiableList(
                new ArrayList<>(sourceItems)
            );
            this.protectedTokens = Collections.unmodifiableList(
                new ArrayList<>(protectedTokens)
            );
            this.sequenceByOrder = Collections.unmodifiableMap(
                new LinkedHashMap<>(sequenceByOrder)
            );
            this.languages = Collections.unmodifiableList(
                languages == null
                    ? new ArrayList<>()
                    : new ArrayList<>(languages)
            );
        }
    }

    public static SceneData read(
        SceneStore.ValidatedScene validated,
        JSONObject characterDictionary,
        JSONObject termDictionary
    ) throws Exception {
        if (validated == null || validated.bytes == null) {
            throw new IllegalArgumentException("validated Scene is required");
        }
        JSONObject source = new JSONObject(new String(
            validated.bytes,
            StandardCharsets.UTF_8
        ));
        return fromJson(
            source,
            characterDictionary,
            termDictionary,
            validated.languages
        );
    }

    public static SceneData fromJson(
        JSONObject source,
        JSONObject characterDictionary,
        JSONObject termDictionary
    ) {
        return fromJson(
            source,
            characterDictionary,
            termDictionary,
            Collections.<String>emptyList()
        );
    }

    private static SceneData fromJson(
        JSONObject source,
        JSONObject characterDictionary,
        JSONObject termDictionary,
        List<String> languages
    ) {
        if (source == null) {
            throw new IllegalArgumentException("Scene JSON is required");
        }
        JSONObject character = source.optJSONObject("character");
        CharacterEntry mc = characterEntry(
            character == null ? null : character.optJSONObject("mc"),
            characterDictionary,
            "mc"
        );
        List<CharacterEntry> high = characterArray(
            character == null ? null : character.optJSONArray("high_weight"),
            characterDictionary,
            "high_weight"
        );
        List<CharacterEntry> low = characterArray(
            character == null ? null : character.optJSONArray("low_weight"),
            characterDictionary,
            "low_weight"
        );

        List<MentionedCharacterEntry> mentioned = new ArrayList<>();
        JSONArray mentionedArray = source.optJSONArray("mentioned_characters");
        if (mentionedArray != null) {
            for (int index = 0; index < mentionedArray.length(); index++) {
                JSONObject entry = mentionedArray.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String name = firstNonEmpty(
                    entry.optString("name", ""),
                    entry.optString("key", "")
                );
                if (name.isEmpty()) {
                    continue;
                }
                JSONObject dictionaryRecord = exactRecord(
                    characterDictionary,
                    name
                );
                JSONObject i18n = entry.optJSONObject("i18n");
                mentioned.add(new MentionedCharacterEntry(
                    name,
                    firstNonEmpty(
                        i18n == null ? "" : i18n.optString("en", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("en", "")
                    ),
                    firstNonEmpty(
                        i18n == null ? "" : i18n.optString("zh_tw", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("zh-tw", "")
                    ),
                    firstNonEmpty(
                        i18n == null ? "" : i18n.optString("zh_cn", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("zh-cn", "")
                    ),
                    dictionaryRecord == null
                ));
            }
        }

        List<TermEntry> terms = new ArrayList<>();
        JSONArray termArray = source.optJSONArray("game_terms");
        if (termArray != null) {
            for (int index = 0; index < termArray.length(); index++) {
                JSONObject entry = termArray.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                String term = firstNonEmpty(
                    entry.optString("term", ""),
                    entry.optString("name", ""),
                    entry.optString("key", "")
                );
                if (term.isEmpty()) {
                    continue;
                }
                JSONObject dictionaryRecord = exactRecord(termDictionary, term);
                JSONObject i18n = entry.optJSONObject("i18n");
                terms.add(new TermEntry(
                    term,
                    firstNonEmpty(
                        i18n == null ? "" : i18n.optString("en", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("en", "")
                    ),
                    firstNonEmpty(
                        i18n == null ? "" : i18n.optString("zh_tw", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("zh-tw", "")
                    ),
                    firstNonEmpty(
                        i18n == null ? "" : i18n.optString("zh_cn", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("zh-cn", "")
                    ),
                    firstNonEmpty(
                        entry.optString("description", ""),
                        dictionaryRecord == null
                            ? ""
                            : dictionaryRecord.optString("description", "")
                    ),
                    dictionaryRecord == null
                ));
            }
        }

        Map<String, Integer> sequenceByOrder = sequenceMap(
            source.opt("seq_to_order")
        );
        List<SourceItem> sourceItems = sourceItems(
            source.optJSONArray("scene_items"),
            sequenceByOrder
        );
        List<JSONObject> protectedTokens = new ArrayList<>();
        JSONArray protect = source.optJSONArray("protect");
        if (protect != null) {
            for (int index = 0; index < protect.length(); index++) {
                JSONObject token = protect.optJSONObject(index);
                if (token != null
                    && !token.optString("label", "").trim().isEmpty()
                    && token.has("origin")
                    && !token.isNull("origin")) {
                    protectedTokens.add(token);
                }
            }
        }

        return new SceneData(
            source,
            source.optString("scene", ""),
            source.optString("game_version", ""),
            source.optString("raw_lang", ""),
            source.optString("target_lang", ""),
            mc,
            high,
            low,
            mentioned,
            terms,
            sourceItems,
            protectedTokens,
            sequenceByOrder,
            languages
        );
    }

    private static List<CharacterEntry> characterArray(
        JSONArray values,
        JSONObject dictionary,
        String role
    ) {
        List<CharacterEntry> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject entry = values.optJSONObject(index);
            if (entry != null) {
                CharacterEntry resolved = characterEntry(entry, dictionary, role);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
        }
        return result;
    }

    private static CharacterEntry characterEntry(
        JSONObject source,
        JSONObject dictionary,
        String role
    ) {
        if (source == null) {
            return null;
        }
        String name = firstNonEmpty(
            source.optString("name", ""),
            source.optString("key", "")
        );
        if (name.isEmpty()) {
            return null;
        }
        JSONObject dictionaryRecord = exactRecord(dictionary, name);
        JSONObject i18n = source.optJSONObject("i18n");
        JSONObject record = dictionaryRecord == null ? source : dictionaryRecord;
        return new CharacterEntry(
            role,
            name,
            firstNonEmpty(
                i18n == null ? "" : i18n.optString("en", ""),
                record.optString("en", "")
            ),
            firstNonEmpty(
                i18n == null ? "" : i18n.optString("zh_tw", ""),
                record.optString("zh-tw", "")
            ),
            firstNonEmpty(
                i18n == null ? "" : i18n.optString("zh_cn", ""),
                record.optString("zh-cn", "")
            ),
            record.optString("info", source.optString("info", "")),
            record.optString("description", source.optString("description", "")),
            record.optString("speech_style", source.optString("speech_style", "")),
            cloneArray(record.optJSONArray("alias"), source.optJSONArray("aliases")),
            cloneArray(record.optJSONArray("school"), source.optJSONArray("school")),
            cloneArray(record.optJSONArray("guild"), source.optJSONArray("guild")),
            cloneArray(record.optJSONArray("origin_world"), source.optJSONArray("origin_world")),
            cloneArray(record.optJSONArray("relationships"), source.optJSONArray("relationships")),
            dictionaryRecord == null
        );
    }

    private static JSONObject exactRecord(JSONObject dictionary, String identity) {
        if (dictionary == null || identity == null || identity.trim().isEmpty()) {
            return null;
        }
        String exact = identity.trim();
        JSONObject direct = dictionary.optJSONObject(exact);
        if (direct != null) {
            return direct;
        }
        for (String key : dictionary.keySet()) {
            if (exact.equals(key)) {
                return dictionary.optJSONObject(key);
            }
            JSONObject record = dictionary.optJSONObject(key);
            if (record != null && exact.equals(record.optString("key", ""))) {
                return record;
            }
        }
        return null;
    }

    private static JSONArray cloneArray(JSONArray preferred, JSONArray fallback) {
        JSONArray selected = preferred != null ? preferred : fallback;
        if (selected == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(selected.toString());
        } catch (org.json.JSONException ignored) {
            return new JSONArray();
        }
    }

    public static String displayCharacterName(CharacterEntry entry) {
        return displayCharacterName(entry, Locale.getDefault());
    }

    public static String displayCharacterName(CharacterEntry entry, Locale locale) {
        if (entry == null) {
            return "";
        }
        return localizedName(entry.en, entry.zhTw, entry.zhCn, entry.name, locale);
    }

    public static String displayMentionedName(MentionedCharacterEntry entry) {
        return displayMentionedName(entry, Locale.getDefault());
    }

    public static String displayMentionedName(
        MentionedCharacterEntry entry,
        Locale locale
    ) {
        if (entry == null) {
            return "";
        }
        return localizedName(entry.en, entry.zhTw, entry.zhCn, entry.name, locale);
    }

    public static String displayTermName(TermEntry entry) {
        return displayTermName(entry, Locale.getDefault());
    }

    public static String displayTermName(TermEntry entry, Locale locale) {
        if (entry == null) {
            return "";
        }
        return localizedName(entry.en, entry.zhTw, entry.zhCn, entry.term, locale);
    }

    public static String relationshipTargetLabel(
        String target,
        JSONObject characterDictionary
    ) {
        return relationshipTargetLabel(
            target,
            characterDictionary,
            Locale.getDefault(),
            "主角"
        );
    }

    public static String relationshipTargetLabel(
        String target,
        JSONObject characterDictionary,
        Locale locale,
        String mainCharacterLabel
    ) {
        String value = target == null ? "" : target.trim();
        if (value.isEmpty()) {
            return "";
        }
        if ("mc".equals(value)) {
            return firstNonEmpty(mainCharacterLabel, value);
        }
        JSONObject record = exactRecord(characterDictionary, value);
        if (record == null) {
            return value;
        }
        return localizedName(
            record.optString("en", ""),
            record.optString("zh-tw", ""),
            record.optString("zh-cn", ""),
            value,
            locale
        );
    }

    public static List<String> stringArray(JSONArray values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.length(); index++) {
            Object value = values.opt(index);
            if (value instanceof JSONObject) {
                String name = firstNonEmpty(
                    ((JSONObject) value).optString("name", ""),
                    ((JSONObject) value).optString("value", "")
                );
                if (!name.isEmpty()) {
                    result.add(name);
                }
            } else if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    public static List<String> relationshipLabels(
        JSONArray values,
        JSONObject characterDictionary
    ) {
        return relationshipLabels(
            values,
            characterDictionary,
            Locale.getDefault(),
            "主角"
        );
    }

    public static List<String> relationshipLabels(
        JSONArray values,
        JSONObject characterDictionary,
        Locale locale,
        String mainCharacterLabel
    ) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject relation = values.optJSONObject(index);
            if (relation == null) {
                continue;
            }
            String target = relationshipTargetLabel(
                relation.optString("target", ""),
                characterDictionary,
                locale,
                mainCharacterLabel
            );
            String type = relation.optString("type", "").trim();
            String label = type.isEmpty() ? target : target + " · " + type;
            if (!label.trim().isEmpty()) {
                result.add(label);
            }
        }
        return result;
    }

    private static String localizedName(
        String en,
        String zhTw,
        String zhCn,
        String fallback,
        Locale locale
    ) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        if (isTraditionalChinese(effective)) {
            return firstNonEmpty(zhTw, zhCn, en, fallback);
        }
        if ("zh".equalsIgnoreCase(effective.getLanguage())) {
            return firstNonEmpty(zhCn, zhTw, en, fallback);
        }
        return firstNonEmpty(en, zhCn, zhTw, fallback);
    }

    private static boolean isTraditionalChinese(Locale locale) {
        if (locale == null || !"zh".equalsIgnoreCase(locale.getLanguage())) {
            return false;
        }
        String script = locale.getScript();
        if ("Hant".equalsIgnoreCase(script)) {
            return true;
        }
        String country = locale.getCountry();
        return "TW".equalsIgnoreCase(country)
            || "HK".equalsIgnoreCase(country)
            || "MO".equalsIgnoreCase(country);
    }

    public static List<SourceItem> sourceItems(JSONArray values) {
        return sourceItems(values, Collections.<String, Integer>emptyMap());
    }

    private static List<SourceItem> sourceItems(
        JSONArray values,
        Map<String, Integer> sequenceByOrder
    ) {
        List<SourceItem> result = new ArrayList<>();
        appendSourceItems(values, result, sequenceByOrder);
        return result;
    }

    private static void appendSourceItems(
        JSONArray values,
        List<SourceItem> output,
        Map<String, Integer> sequenceByOrder
    ) {
        if (values == null) {
            return;
        }
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) {
                continue;
            }
            int originalIndex = output.size();
            int sequence = sequenceFor(item.opt("order"), sequenceByOrder);
            output.add(new SourceItem(
                item,
                originalIndex,
                orderLabel(item, originalIndex, sequenceByOrder),
                sequence
            ));

            String type = item.optString("type", "");
            if ("choice".equals(type)) {
                JSONArray branches = item.optJSONArray("branches");
                if (branches == null) {
                    continue;
                }
                for (int branchIndex = 0; branchIndex < branches.length(); branchIndex++) {
                    JSONObject branch = branches.optJSONObject(branchIndex);
                    if (branch != null) {
                        appendSourceItems(
                            branch.optJSONArray("following_text"),
                            output,
                            sequenceByOrder
                        );
                    }
                }
            } else if ("if".equals(type)) {
                appendSourceItems(
                    item.optJSONArray("following_text"),
                    output,
                    sequenceByOrder
                );
            }
        }
    }

    public static String restoreProtectedText(
        String value,
        List<JSONObject> protectedTokens
    ) {
        String text = value == null ? "" : value;
        if (text.isEmpty() || protectedTokens == null || protectedTokens.isEmpty()) {
            return text;
        }
        Map<String, String> replacements = new LinkedHashMap<>();
        for (JSONObject token : protectedTokens) {
            if (token == null
                || !token.has("origin")
                || token.isNull("origin")) {
                continue;
            }
            String label = token.optString("label", "");
            if (!label.trim().isEmpty()) {
                replacements.put(label, token.optString("origin", ""));
            }
        }
        if (replacements.isEmpty()) {
            return text;
        }
        List<String> labels = new ArrayList<>(replacements.keySet());
        Collections.sort(labels, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int length = right.length() - left.length();
                return length != 0 ? length : left.compareTo(right);
            }
        });
        StringBuilder patternText = new StringBuilder();
        for (String label : labels) {
            if (patternText.length() > 0) {
                patternText.append('|');
            }
            patternText.append(Pattern.quote(label));
        }
        Matcher matcher = Pattern.compile(patternText.toString()).matcher(text);
        StringBuffer restored = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                restored,
                Matcher.quoteReplacement(replacements.get(matcher.group()))
            );
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    public static String translationFor(
        JSONObject item,
        String preferredLanguage
    ) {
        JSONObject translations = item == null
            ? null
            : item.optJSONObject("translations");
        if (translations == null) {
            return "";
        }
        if (preferredLanguage != null && !preferredLanguage.isEmpty()) {
            String selected = translations.optString(preferredLanguage, "");
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        for (String key : translations.keySet()) {
            String value = translations.optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    public static String orderLabel(JSONObject item, int fallbackIndex) {
        return orderLabel(
            item,
            fallbackIndex,
            Collections.<String, Integer>emptyMap()
        );
    }

    public static String orderLabel(
        JSONObject item,
        int fallbackIndex,
        Map<String, Integer> sequenceByOrder
    ) {
        Object order = item == null ? null : item.opt("order");
        int sequence = sequenceFor(order, sequenceByOrder);
        if (sequence > 0) {
            return String.valueOf(sequence);
        }
        if (order instanceof JSONObject) {
            JSONObject key = (JSONObject) order;
            StringBuilder value = new StringBuilder();
            appendOrderPart(value, key, "label_index");
            appendOrderPart(value, key, "page_no");
            appendOrderPart(value, key, "cmd_index");
            appendOrderPart(value, key, "sub_index");
            if (value.length() > 0) {
                return value.toString();
            }
        }
        if (order != null && order != JSONObject.NULL) {
            String text = String.valueOf(order).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return String.valueOf(fallbackIndex + 1);
    }

    public static int sequenceFor(
        Object order,
        Map<String, Integer> sequenceByOrder
    ) {
        if (sequenceByOrder == null || sequenceByOrder.isEmpty()) {
            return 0;
        }
        Integer sequence = sequenceByOrder.get(orderSignature(order));
        return sequence == null ? 0 : sequence;
    }

    private static Map<String, Integer> sequenceMap(Object value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value instanceof JSONArray) {
            JSONArray entries = (JSONArray) value;
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.optJSONObject(index);
                if (entry == null) {
                    continue;
                }
                Object order = entry.opt("order");
                if (!(order instanceof JSONObject)) {
                    order = entry;
                }
                int sequence = entry.has("seq")
                    ? entry.optInt("seq", index + 1)
                    : entry.optInt("sequence", index + 1);
                putSequence(result, order, sequence);
            }
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : object.keySet()) {
                Object order = object.opt(key);
                int sequence;
                try {
                    sequence = Integer.parseInt(key);
                } catch (NumberFormatException ignored) {
                    sequence = result.size() + 1;
                }
                putSequence(result, order, sequence);
            }
        }
        return result;
    }

    private static void putSequence(
        Map<String, Integer> target,
        Object order,
        int sequence
    ) {
        String signature = orderSignature(order);
        if (!signature.isEmpty() && sequence > 0) {
            target.put(signature, sequence);
        }
    }

    private static String orderSignature(Object value) {
        if (value instanceof JSONObject) {
            JSONObject order = (JSONObject) value;
            StringBuilder signature = new StringBuilder();
            appendSignaturePart(signature, order, "label_index");
            appendSignaturePart(signature, order, "page_no");
            appendSignaturePart(signature, order, "cmd_index");
            appendSignaturePart(signature, order, "sub_index");
            return signature.toString();
        }
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(value);
    }

    private static void appendSignaturePart(
        StringBuilder signature,
        JSONObject value,
        String key
    ) {
        if (!value.has(key)) {
            return;
        }
        if (signature.length() > 0) {
            signature.append('/');
        }
        signature.append(key).append('=').append(value.optInt(key, 0));
    }

    private static void appendOrderPart(
        StringBuilder output,
        JSONObject order,
        String key
    ) {
        if (!order.has(key)) {
            return;
        }
        if (output.length() > 0) {
            output.append('.');
        }
        output.append(order.optInt(key, 0));
    }

    private static int compareOrder(Object left, Object right) {
        int[] leftParts = orderParts(left);
        int[] rightParts = orderParts(right);
        for (int index = 0; index < leftParts.length; index++) {
            if (leftParts[index] != rightParts[index]) {
                return leftParts[index] < rightParts[index] ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] orderParts(Object value) {
        if (value instanceof JSONObject) {
            JSONObject order = (JSONObject) value;
            return new int[] {
                order.optInt("label_index", Integer.MAX_VALUE),
                order.optInt("page_no", Integer.MAX_VALUE),
                order.optInt("cmd_index", Integer.MAX_VALUE),
                order.optInt("sub_index", Integer.MAX_VALUE)
            };
        }
        if (value == null || value == JSONObject.NULL) {
            return new int[] {
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
            };
        }
        try {
            return new int[] {Integer.parseInt(String.valueOf(value)), 0, 0, 0};
        } catch (NumberFormatException ignored) {
            return new int[] {
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
            };
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
