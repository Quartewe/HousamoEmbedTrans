package com.quarty.housamoembedtrans.translation.request;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds the dictionary context of one provider request from the current
 * formal dictionaries and the source text in the Scene.
 *
 * <p>The native capture path stores the result of a dictionary match in the
 * Scene.  That result is intentionally not treated as a dictionary snapshot:
 * a queued job can outlive an edit to chardict.json or gameterms.json.  This
 * class performs the small, request-local match again and emits only records
 * which are relevant to the current Scene.</p>
 */
public final class FormalDictionaryMatcher {
    private static final String TARGET_EN = "en";
    private static final String TARGET_ZH_TW = "zh-tw";
    private static final String TARGET_ZH_CN = "zh-cn";

    /* These are the defaults used by the native SceneBuilder. */
    private static final double DEFAULT_HIGH_RELEVANCE = 4.0d;
    private static final double DEFAULT_MID_RELEVANCE = 3.0d;
    private static final double DEFAULT_DENSITY_HIGH = 1.5d;
    private static final double DEFAULT_TEXT_LOW_SCORE = 3.0d;
    private static final double DEFAULT_TEXT_MENTIONED_SCORE = 1.0d;
    private static final int DEFAULT_RELATED_NUM = 1;
    private static final int DEFAULT_LOW_TERM_SCORE = 3;

    private FormalDictionaryMatcher() {
        throw new AssertionError("No instances");
    }

    /**
     * Matches one request Scene.  The input dictionaries are read-only from
     * this class; neither the Scene nor either dictionary is mutated.
     *
     * <p>{@code internalTerms} is deliberately only a string-to-string
     * object.  It has no description and cannot add character metadata.  A
     * non-empty formal target-language value always wins over an internal
     * value with the same source key.</p>
     */
    public static JSONObject matchScene(
        JSONObject originalScene,
        JSONObject characterDictionary,
        JSONObject gameTermDictionary,
        JSONObject internalTerms
    ) throws Exception {
        return matchScene(
            originalScene,
            characterDictionary,
            gameTermDictionary,
            internalTerms,
            null
        );
    }

    /**
     * Matches a Scene with the current CharacterWeight settings.  The
     * settings object is read-only and may be {@code null}; null selects the
     * native defaults for callers which do not have a config snapshot.
     */
    public static JSONObject matchScene(
        JSONObject originalScene,
        JSONObject characterDictionary,
        JSONObject gameTermDictionary,
        JSONObject internalTerms,
        JSONObject characterWeight
    ) throws Exception {
        if (originalScene == null) {
            throw new IllegalArgumentException("scene cannot be null");
        }
        if (characterDictionary == null || gameTermDictionary == null) {
            throw new IllegalArgumentException("formal dictionaries are required");
        }

        JSONObject scene = new JSONObject(originalScene.toString());
        String targetLanguage = requireTargetLanguage(
            scene.optString("target_lang", "")
        );

        MatchWeights weights = MatchWeights.fromObject(characterWeight);
        List<SourceText> sourceTexts = collectSourceTexts(
            scene.optJSONArray("scene_items")
        );
        JSONArray sceneItems = scene.optJSONArray("scene_items");
        int itemCount = sceneItems == null ? 0 : sceneItems.length();
        MatchResult result = match(
            sourceTexts,
            characterDictionary,
            gameTermDictionary,
            internalTerms,
            weights,
            targetLanguage,
            itemCount
        );

        JSONObject character = new JSONObject();
        character.put("mc", result.mc);
        character.put("high_weight", result.highWeight);
        character.put("low_weight", result.lowWeight);
        scene.put("character", character);
        scene.put("mentioned_characters", result.mentionedCharacters);
        scene.put("game_terms", result.gameTerms);
        return scene;
    }

    /**
     * Returns the currently effective formal translation for each source key.
     * Both canonical character names and alias names are included.  Empty
     * target-language fields are intentionally omitted so an internal term can
     * supply the missing value.  Character records take precedence over a
     * game-term record if the same source key occurs in both dictionaries.
     */
    public static Map<String, String> currentFormalTranslations(
        JSONObject characterDictionary,
        JSONObject gameTermDictionary,
        String targetLanguage
    ) throws Exception {
        if (characterDictionary == null || gameTermDictionary == null) {
            throw new IllegalArgumentException("formal dictionaries are required");
        }
        String language = requireTargetLanguage(targetLanguage);
        Map<String, String> result = new LinkedHashMap<>();

        for (String name : sortedKeys(characterDictionary)) {
            JSONObject record = characterDictionary.optJSONObject(name);
            if (record == null) {
                continue;
            }
            putNonEmpty(result, name, translatedValue(record, language));
        }

        /* Match the AC construction order: every canonical character pattern
         * has precedence over an alias which happens to share its source key. */
        for (String name : sortedKeys(characterDictionary)) {
            JSONObject record = characterDictionary.optJSONObject(name);
            if (record == null) {
                continue;
            }
            JSONArray aliases = record.optJSONArray("alias");
            if (aliases == null) {
                continue;
            }
            for (int index = 0; index < aliases.length(); index++) {
                JSONObject alias = aliases.optJSONObject(index);
                if (alias == null) {
                    continue;
                }
                putNonEmpty(
                    result,
                    alias.optString("name", ""),
                    translatedValue(alias, language)
                );
            }
        }

        for (String term : sortedKeys(gameTermDictionary)) {
            JSONObject record = gameTermDictionary.optJSONObject(term);
            if (record != null) {
                putNonEmpty(result, term, translatedValue(record, language));
            }
        }
        return result;
    }

    private static MatchResult match(
        List<SourceText> sourceTexts,
        JSONObject characterDictionary,
        JSONObject gameTermDictionary,
        JSONObject internalTerms,
        MatchWeights weights,
        String targetLanguage,
        int itemCount
    ) throws Exception {
        List<Pattern> characterPatterns = new ArrayList<>();
        Map<String, JSONObject> characterRecords = new LinkedHashMap<>();
        List<String> characterNames = sortedKeys(characterDictionary);
        Set<String> seenPatterns = new HashSet<>();

        for (String name : characterNames) {
            JSONObject record = characterDictionary.optJSONObject(name);
            if (record == null || name.isEmpty()) {
                continue;
            }
            characterRecords.put(name, record);
            addPattern(
                characterPatterns,
                seenPatterns,
                new Pattern(name, name, "", false, true)
            );
        }
        for (String name : characterNames) {
            JSONObject record = characterDictionary.optJSONObject(name);
            if (record == null) {
                continue;
            }
            JSONArray aliases = record.optJSONArray("alias");
            if (aliases == null) {
                continue;
            }
            for (int index = 0; index < aliases.length(); index++) {
                JSONObject alias = aliases.optJSONObject(index);
                if (alias == null) {
                    continue;
                }
                String aliasName = alias.optString("name", "");
                if (!aliasName.isEmpty()) {
                    addPattern(
                        characterPatterns,
                        seenPatterns,
                        new Pattern(
                            aliasName,
                            name,
                            alias.optString("called", ""),
                            true,
                            true
                        )
                    );
                }
            }
        }

        List<Pattern> termPatterns = new ArrayList<>();
        Set<String> seenTerms = new HashSet<>();
        for (String term : sortedKeys(gameTermDictionary)) {
            JSONObject record = gameTermDictionary.optJSONObject(term);
            if (record != null && !term.isEmpty()) {
                addPattern(
                    termPatterns,
                    seenTerms,
                    new Pattern(term, term, "", false, false)
                );
            }
        }
        if (internalTerms != null) {
            for (String term : sortedKeys(internalTerms)) {
                String translation = stringValue(internalTerms.opt(term));
                if (!term.isEmpty()
                    && !translation.isEmpty()
                    && !seenPatterns.contains(term)) {
                    addPattern(
                        termPatterns,
                        seenTerms,
                        new Pattern(term, term, "", false, false)
                    );
                }
            }
        }

        PatternMatcher characterMatcher = new PatternMatcher(characterPatterns);
        PatternMatcher termMatcher = new PatternMatcher(termPatterns);

        Map<String, Signal> signals = new LinkedHashMap<>();
        Map<String, AliasHit> aliasHits = new LinkedHashMap<>();
        Map<String, Double> termScores = new LinkedHashMap<>();
        List<String> termFirstSeen = new ArrayList<>();
        Set<String> termFirstSeenSet = new HashSet<>();
        List<String> firstSeen = new ArrayList<>();
        Set<String> firstSeenSet = new HashSet<>();
        for (SourceText source : sourceTexts) {
            if (source.text.isEmpty()) {
                continue;
            }
            String speaker = source.speaker;
            if (!speaker.isEmpty() && !"mc".equals(speaker)) {
                signalFor(
                    signals,
                    speaker,
                    firstSeen,
                    firstSeenSet
                ).speaker += 10.0d;
            }

            /* The HET Scene contract retains speaker/text, but not the native
             * Character command or its display-column value.  Scan the
             * retained speaker as the available show input; do not invent
             * Character events which cannot be reconstructed from the Scene. */
            for (Hit hit : characterMatcher.scan(speaker)) {
                signalFor(
                    signals,
                    hit.pattern.canonical,
                    firstSeen,
                    firstSeenSet
                ).show += hit.score;
            }
            for (Hit hit : termMatcher.scan(speaker)) {
                addTermScore(
                    termScores,
                    termFirstSeen,
                    termFirstSeenSet,
                    hit.pattern.canonical,
                    hit.score
                );
            }

            for (Hit hit : characterMatcher.scan(source.text)) {
                if (hit.pattern.alias
                    && !hit.pattern.called.isEmpty()
                    && !hit.pattern.called.equals(speaker)) {
                    continue;
                }
                signalFor(
                    signals,
                    hit.pattern.canonical,
                    firstSeen,
                    firstSeenSet
                ).text += hit.score;
                if (hit.pattern.alias) {
                    String matchedAliasKey = aliasKey(
                        hit.pattern.canonical,
                        hit.pattern.pattern
                    );
                    AliasHit existing = aliasHits.get(matchedAliasKey);
                    if (existing == null) {
                        existing = new AliasHit(hit.pattern);
                        aliasHits.put(matchedAliasKey, existing);
                    }
                    existing.score += hit.score;
                }
            }
            for (Hit hit : termMatcher.scan(source.text)) {
                addTermScore(
                    termScores,
                    termFirstSeen,
                    termFirstSeenSet,
                    hit.pattern.canonical,
                    hit.score
                );
            }
        }

        /* Native always emits the player record even when it is not mentioned. */
        JSONObject mcRecord = characterRecords.get("mc");
        JSONObject mc = characterItem(
            "mc",
            mcRecord,
            aliasHits,
            internalTerms,
            targetLanguage,
            weights.textMentionedScore
        );

        Map<String, JSONObject> related = buildRelatedIndex(characterRecords);
        Set<String> highNames = new LinkedHashSet<>();
        List<String> highNamesOrdered = new ArrayList<>();
        List<String> lowNamesOrdered = new ArrayList<>();
        List<String> mentionedNamesOrdered = new ArrayList<>();
        List<String> candidates = new ArrayList<>(signals.keySet());
        Collections.sort(candidates, new FirstSeenComparator(firstSeen));
        double itemCountValue = Math.max(1.0d, itemCount);
        for (String name : candidates) {
            if ("mc".equals(name)) {
                continue;
            }
            Signal signal = signals.get(name);
            if (signal == null) {
                continue;
            }
            double speakerTurns = signal.speaker / 10.0d;
            double showHits = signal.show / 2.0d;
            double textHits = signal.text;
            double density = (signal.speaker + signal.show + signal.text)
                / Math.sqrt(itemCountValue);
            double relevance = 5.0d * Math.log1p(speakerTurns)
                + 2.0d * Math.log1p(showHits)
                + Math.log1p(textHits);
            boolean strongSignal = speakerTurns >= 2.0d
                || (speakerTurns >= 1.0d && showHits >= 1.0d);
            boolean high = strongSignal
                || relevance >= weights.highRelevance
                || (relevance >= weights.midRelevance
                    && density >= weights.densityHigh);
            if (high) {
                highNames.add(name);
                highNamesOrdered.add(name);
            }
        }
        for (String name : candidates) {
            if ("mc".equals(name) || highNames.contains(name)) {
                continue;
            }
            Signal signal = signals.get(name);
            if (signal == null) {
                continue;
            }
            double textHits = signal.text;
            boolean low = textHits >= weights.textLowScore
                || (textHits > weights.textMentionedScore
                    && relatedCount(name, highNames, related)
                        >= weights.relatedNum);
            if (low) {
                lowNamesOrdered.add(name);
            } else if (textHits >= weights.textMentionedScore) {
                mentionedNamesOrdered.add(name);
            }
        }

        JSONArray high = new JSONArray();
        JSONArray low = new JSONArray();
        JSONArray mentioned = new JSONArray();
        Map<String, Integer> lowTermCounts = new LinkedHashMap<>();
        List<String> characterTermOrder = new ArrayList<>();
        Set<String> characterTermSeen = new LinkedHashSet<>();
        for (String name : highNamesOrdered) {
            JSONObject record = characterRecords.get(name);
            high.put(characterItem(
                name,
                record,
                aliasHits,
                internalTerms,
                targetLanguage,
                weights.textMentionedScore
            ));
            addCharacterTerms(
                record,
                characterTermOrder,
                characterTermSeen
            );
        }
        for (String name : lowNamesOrdered) {
            JSONObject record = characterRecords.get(name);
            low.put(characterItem(
                name,
                record,
                aliasHits,
                internalTerms,
                targetLanguage,
                weights.textMentionedScore
            ));
            addCharacterTerms(record, lowTermCounts);
        }
        for (String name : mentionedNamesOrdered) {
            JSONObject record = characterRecords.get(name);
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put(
                "i18n",
                characterI18n(record, name, internalTerms, targetLanguage)
            );
            mentioned.put(item);
        }

        List<String> orderedTerms = new ArrayList<>();
        Set<String> addedTerms = new LinkedHashSet<>();
        for (String term : termFirstSeen) {
            Double score = termScores.get(term);
            if (score != null && score >= weights.textMentionedScore
                && addedTerms.add(term)) {
                orderedTerms.add(term);
            }
        }
        for (String term : characterTermOrder) {
            if (addedTerms.add(term)) {
                orderedTerms.add(term);
            }
        }
        for (Map.Entry<String, Integer> entry : lowTermCounts.entrySet()) {
            if (entry.getValue() >= weights.lowTermScore) {
                if (addedTerms.add(entry.getKey())) {
                    orderedTerms.add(entry.getKey());
                }
            }
        }

        JSONArray terms = new JSONArray();
        for (String term : orderedTerms) {
            JSONObject formal = gameTermDictionary.optJSONObject(term);
            String internal = internalTerms == null
                ? ""
                : stringValue(internalTerms.opt(term));
            terms.put(gameTermItem(
                term,
                formal,
                internal,
                targetLanguage
            ));
        }

        return new MatchResult(mc, high, low, mentioned, terms);
    }

    private static Signal signalFor(
        Map<String, Signal> signals,
        String name,
        List<String> firstSeen,
        Set<String> firstSeenSet
    ) {
        Signal signal = signals.get(name);
        if (signal == null) {
            signal = new Signal();
            signals.put(name, signal);
        }
        if (firstSeenSet.add(name)) {
            firstSeen.add(name);
        }
        return signal;
    }

    private static void addCharacterTerms(
        JSONObject record,
        List<String> termOrder,
        Set<String> termSeen
    ) {
        if (record == null) {
            return;
        }
        String[] fields = {"guild", "school", "origin_world"};
        for (String field : fields) {
            JSONArray values = record.optJSONArray(field);
            if (values == null) {
                continue;
            }
            for (int index = 0; index < values.length(); index++) {
                String term = values.optString(index, "");
                if (term.isEmpty()) {
                    continue;
                }
                if (termSeen.add(term)) {
                    termOrder.add(term);
                }
            }
        }
    }

    private static void addCharacterTerms(
        JSONObject record,
        Map<String, Integer> lowTermCounts
    ) {
        if (record == null) {
            return;
        }
        String[] fields = {"guild", "school", "origin_world"};
        for (String field : fields) {
            JSONArray values = record.optJSONArray(field);
            if (values == null) {
                continue;
            }
            for (int index = 0; index < values.length(); index++) {
                String term = values.optString(index, "");
                if (!term.isEmpty()) {
                    addCount(lowTermCounts, term);
                }
            }
        }
    }

    private static Map<String, JSONObject> buildRelatedIndex(
        Map<String, JSONObject> records
    ) {
        Map<String, JSONObject> result = new HashMap<>();
        for (Map.Entry<String, JSONObject> entry : records.entrySet()) {
            JSONArray relationships = entry.getValue().optJSONArray("relationships");
            if (relationships == null) {
                continue;
            }
            for (int index = 0; index < relationships.length(); index++) {
                JSONObject relation = relationships.optJSONObject(index);
                if (relation == null) {
                    continue;
                }
                String target = relation.optString("target", "");
                if (!target.isEmpty() && !target.equals(entry.getKey())) {
                    result.put(
                        relatedKey(entry.getKey(), target),
                        relation
                    );
                    result.put(
                        relatedKey(target, entry.getKey()),
                        relation
                    );
                }
            }
        }
        return result;
    }

    private static int relatedCount(
        String name,
        Set<String> highNames,
        Map<String, JSONObject> related
    ) {
        int count = 0;
        for (String high : highNames) {
            if (related.containsKey(relatedKey(name, high))) {
                count++;
            }
        }
        return count;
    }

    private static String relatedKey(String left, String right) {
        return left + "\u0000" + right;
    }

    private static String aliasKey(String canonical, String alias) {
        return canonical + "\u0000" + alias;
    }

    private static JSONObject characterItem(
        String name,
        JSONObject formal,
        Map<String, AliasHit> aliasHits,
        JSONObject internalTerms,
        String targetLanguage,
        double aliasThreshold
    ) throws Exception {
        JSONObject result = new JSONObject();
        result.put("name", name);
        JSONArray aliases = new JSONArray();
        if (formal != null && aliasHits != null) {
            JSONArray sourceAliases = formal.optJSONArray("alias");
            if (sourceAliases != null) {
                for (int index = 0; index < sourceAliases.length(); index++) {
                    JSONObject alias = sourceAliases.optJSONObject(index);
                    if (alias == null) {
                        continue;
                    }
                    String aliasName = alias.optString("name", "");
                    AliasHit hit = aliasHits.get(aliasKey(name, aliasName));
                    if (hit != null && hit.score >= aliasThreshold) {
                        aliases.put(aliasItem(
                            alias,
                            internalTerms,
                            targetLanguage
                        ));
                    }
                }
            }
        }
        result.put("aliases", aliases);
        result.put(
            "i18n",
            characterI18n(formal, name, internalTerms, targetLanguage)
        );
        result.put("school", stringArray(formal, "school"));
        result.put("guild", stringArray(formal, "guild"));
        result.put("origin_world", stringArray(formal, "origin_world"));
        result.put("relationships", copyArray(formal, "relationships"));
        result.put("info", formal == null ? "" : formal.optString("info", ""));
        result.put(
            "description",
            formal == null ? "" : formal.optString("description", "")
        );
        result.put(
            "speech_style",
            formal == null ? "" : formal.optString("speech_style", "")
        );
        return result;
    }

    private static JSONObject aliasItem(
        JSONObject alias,
        JSONObject internalTerms,
        String targetLanguage
    ) throws Exception {
        JSONObject result = new JSONObject();
        String name = alias.optString("name", "");
        result.put("name", name);
        result.put(
            "i18n",
            i18nWithInternal(alias, name, internalTerms, targetLanguage)
        );
        result.put("called", alias.optString("called", ""));
        return result;
    }

    private static JSONObject characterI18n(
        JSONObject record,
        String sourceName,
        JSONObject internalTerms,
        String targetLanguage
    ) throws Exception {
        if (record == null) {
            return i18nWithInternal(
                null,
                sourceName,
                internalTerms,
                targetLanguage
            );
        }
        return i18nWithInternal(
            record,
            sourceName,
            internalTerms,
            targetLanguage
        );
    }

    private static JSONObject i18n(JSONObject record) throws Exception {
        JSONObject result = new JSONObject();
        result.put("en", record == null ? "" : record.optString("en", ""));
        result.put(
            "zh_tw",
            record == null ? "" : record.optString("zh-tw", "")
        );
        result.put(
            "zh_cn",
            record == null ? "" : record.optString("zh-cn", "")
        );
        return result;
    }

    private static JSONObject emptyI18n() throws Exception {
        return i18n(null);
    }

    private static JSONObject gameTermItem(
        String term,
        JSONObject formal,
        String internal,
        String targetLanguage
    ) throws Exception {
        JSONObject result = new JSONObject();
        result.put("term", term);
        JSONObject translated = formal == null
            ? emptyI18n()
            : i18n(formal);
        if ((formal == null || translatedTarget(formal, targetLanguage).isEmpty())
            && !internal.isEmpty()) {
            /* Internal terms are target-language values; preserve all formal
             * fields and fill only the target field when formal is missing. */
            translated.put(i18nKey(targetLanguage), internal);
        }
        result.put("i18n", translated);
        result.put(
            "description",
            formal == null ? "" : formal.optString("description", "")
        );
        return result;
    }

    private static JSONObject i18nWithInternal(
        JSONObject formal,
        String sourceName,
        JSONObject internalTerms,
        String targetLanguage
    ) throws Exception {
        JSONObject result = formal == null ? emptyI18n() : i18n(formal);
        if (translatedTarget(formal, targetLanguage).isEmpty()
            && internalTerms != null
            && sourceName != null
            && !sourceName.isEmpty()) {
            String internal = stringValue(internalTerms.opt(sourceName));
            if (!internal.isEmpty()) {
                result.put(i18nKey(targetLanguage), internal);
            }
        }
        return result;
    }

    private static String i18nKey(String targetLanguage) {
        if (TARGET_ZH_TW.equals(targetLanguage)) {
            return "zh_tw";
        }
        if (TARGET_ZH_CN.equals(targetLanguage)) {
            return "zh_cn";
        }
        // Provider-only input: custom language values use their exact key.
        // This rebuilt object is never written back as a persisted Scene.
        return targetLanguage;
    }

    private static JSONArray stringArray(JSONObject record, String key)
        throws Exception {
        JSONArray result = new JSONArray();
        if (record == null) {
            return result;
        }
        JSONArray source = record.optJSONArray(key);
        if (source == null) {
            return result;
        }
        for (int index = 0; index < source.length(); index++) {
            String value = source.optString(index, "");
            if (!value.isEmpty()) {
                result.put(value);
            }
        }
        return result;
    }

    private static JSONArray copyArray(JSONObject record, String key)
        throws Exception {
        return record == null || record.optJSONArray(key) == null
            ? new JSONArray()
            : new JSONArray(record.optJSONArray(key).toString());
    }

    private static List<SourceText> collectSourceTexts(JSONArray items)
        throws Exception {
        List<SourceText> result = new ArrayList<>();
        collectSourceTexts(items, result);
        return result;
    }

    private static void collectSourceTexts(
        JSONArray items,
        List<SourceText> result
    ) throws Exception {
        if (items == null) {
            return;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String type = item.optString("type", "");
            if ("text".equals(type)) {
                result.add(new SourceText(
                    item.optString("text", ""),
                    item.optString("speaker", "")
                ));
            } else if ("if".equals(type)) {
                collectSourceTexts(item.optJSONArray("following_text"), result);
            } else if ("choice".equals(type)) {
                JSONArray branches = item.optJSONArray("branches");
                if (branches == null) {
                    continue;
                }
                for (int branchIndex = 0; branchIndex < branches.length(); branchIndex++) {
                    JSONObject branch = branches.optJSONObject(branchIndex);
                    if (branch == null) {
                        continue;
                    }
                    collectSourceTexts(branch.optJSONArray("options"), result);
                    collectSourceTexts(
                        branch.optJSONArray("following_text"),
                        result
                    );
                }
            }
        }
    }

    /** UTF-16 AC matcher; overlapping outputs are retained like native AC. */
    private static final class PatternMatcher {
        private final List<Pattern> patterns;
        private final List<TrieNode> nodes = new ArrayList<>();

        private PatternMatcher(List<Pattern> patterns) {
            this.patterns = patterns;
            nodes.add(new TrieNode());
            for (int patternIndex = 0;
                 patternIndex < patterns.size();
                 patternIndex++) {
                addPattern(patternIndex);
            }
            buildFailureLinks();
        }

        private void addPattern(int patternIndex) {
            Pattern pattern = patterns.get(patternIndex);
            int state = 0;
            for (int index = 0; index < pattern.pattern.length(); index++) {
                char character = pattern.pattern.charAt(index);
                Integer next = nodes.get(state).next.get(character);
                if (next == null) {
                    next = nodes.size();
                    nodes.get(state).next.put(character, next);
                    nodes.add(new TrieNode());
                }
                state = next;
            }
            nodes.get(state).outputs.add(patternIndex);
        }

        private void buildFailureLinks() {
            Deque<Integer> pending = new ArrayDeque<>();
            for (Integer child : nodes.get(0).next.values()) {
                nodes.get(child).failure = 0;
                pending.addLast(child);
            }
            while (!pending.isEmpty()) {
                int state = pending.removeFirst();
                TrieNode node = nodes.get(state);
                for (Map.Entry<Character, Integer> edge : node.next.entrySet()) {
                    char character = edge.getKey();
                    int child = edge.getValue();
                    int failure = node.failure;
                    while (failure != 0
                        && !nodes.get(failure).next.containsKey(character)) {
                        failure = nodes.get(failure).failure;
                    }
                    Integer fallback = nodes.get(failure).next.get(character);
                    if (fallback == null || fallback == child) {
                        fallback = 0;
                    }
                    nodes.get(child).failure = fallback;
                    nodes.get(child).outputs.addAll(
                        nodes.get(fallback).outputs
                    );
                    pending.addLast(child);
                }
            }
        }

        private List<Hit> scan(String text) {
            List<Hit> result = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return result;
            }
            int state = 0;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                while (state != 0
                    && !nodes.get(state).next.containsKey(character)) {
                    state = nodes.get(state).failure;
                }
                Integer next = nodes.get(state).next.get(character);
                state = next == null ? 0 : next;
                for (Integer patternIndex : nodes.get(state).outputs) {
                    Pattern pattern = patterns.get(patternIndex);
                    int end = index + 1;
                    int begin = end - pattern.pattern.length();
                    double score = pattern.character
                        && touchesKatakanaBoundary(text, begin, end)
                        ? 0.1d
                        : 1.0d;
                    result.add(new Hit(pattern, score));
                }
            }
            return result;
        }
    }

    private static final class TrieNode {
        private final Map<Character, Integer> next = new LinkedHashMap<>();
        private final List<Integer> outputs = new ArrayList<>();
        private int failure;
    }

    private static boolean touchesKatakanaBoundary(
        String text,
        int begin,
        int end
    ) {
        if (begin > 0 && isKatakanaLike(text.codePointBefore(begin))) {
            return true;
        }
        return end < text.length() && isKatakanaLike(text.codePointAt(end));
    }

    private static boolean isKatakanaLike(int codePoint) {
        return (codePoint >= 0x30A0 && codePoint <= 0x30FF)
            || (codePoint >= 0x31F0 && codePoint <= 0x31FF)
            || (codePoint >= 0xFF66 && codePoint <= 0xFF9D)
            || codePoint == 0xFF70;
    }

    private static void addPattern(
        List<Pattern> patterns,
        Set<String> seen,
        Pattern pattern
    ) {
        if (pattern.pattern.isEmpty() || !seen.add(pattern.pattern)) {
            return;
        }
        patterns.add(pattern);
    }

    private static void addCount(Map<String, Integer> counts, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        Integer current = counts.get(key);
        counts.put(key, current == null ? 1 : current + 1);
    }

    private static void addTermScore(
        Map<String, Double> scores,
        List<String> firstSeen,
        Set<String> firstSeenSet,
        String key,
        double score
    ) {
        if (key == null || key.isEmpty()) {
            return;
        }
        Double current = scores.get(key);
        scores.put(key, current == null ? score : current + score);
        if (firstSeenSet.add(key)) {
            firstSeen.add(key);
        }
    }

    private static void putNonEmpty(
        Map<String, String> values,
        String key,
        String value
    ) {
        if (key != null && !key.isEmpty() && value != null && !value.isEmpty()
            && !values.containsKey(key)) {
            values.put(key, value);
        }
    }

    private static String translatedValue(JSONObject record, String language) {
        if (record == null) {
            return "";
        }
        String key = i18nKey(language);
        // Formal dictionaries have only these three localized fields. Never
        // interpret an arbitrary custom code as another record field (info,
        // description, etc.). The request's i18n uses underscore keys.
        if (TARGET_EN.equals(key)) return record.optString("en", "").trim();
        if ("zh_tw".equals(key)) return record.optString("zh-tw", "").trim();
        if ("zh_cn".equals(key)) return record.optString("zh-cn", "").trim();
        return "";
    }

    private static String translatedTarget(JSONObject record, String language) {
        return translatedValue(record, language);
    }

    private static String stringValue(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String requireTargetLanguage(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            throw new IllegalArgumentException("target language is required");
        }
        return targetLanguage;
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        if (object == null) {
            return keys;
        }
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            keys.add(iterator.next());
        }
        Collections.sort(keys);
        return keys;
    }

    private static final class SourceText {
        private final String text;
        private final String speaker;

        private SourceText(String text, String speaker) {
            this.text = text == null ? "" : text;
            this.speaker = speaker == null ? "" : speaker;
        }
    }

    private static final class Pattern {
        private final String pattern;
        private final String canonical;
        private final String called;
        private final boolean alias;
        private final boolean character;

        private Pattern(
            String pattern,
            String canonical,
            String called,
            boolean alias,
            boolean character
        ) {
            this.pattern = pattern;
            this.canonical = canonical;
            this.called = called;
            this.alias = alias;
            this.character = character;
        }
    }

    private static final class Hit {
        private final Pattern pattern;
        private final double score;

        private Hit(Pattern pattern, double score) {
            this.pattern = pattern;
            this.score = score;
        }
    }

    private static final class Signal {
        private double speaker;
        private double show;
        private double text;
    }

    private static final class AliasHit {
        private final Pattern pattern;
        private double score;

        private AliasHit(Pattern pattern) {
            this.pattern = pattern;
        }
    }

    private static final class MatchResult {
        private final JSONObject mc;
        private final JSONArray highWeight;
        private final JSONArray lowWeight;
        private final JSONArray mentionedCharacters;
        private final JSONArray gameTerms;

        private MatchResult(
            JSONObject mc,
            JSONArray highWeight,
            JSONArray lowWeight,
            JSONArray mentionedCharacters,
            JSONArray gameTerms
        ) {
            this.mc = mc;
            this.highWeight = highWeight;
            this.lowWeight = lowWeight;
            this.mentionedCharacters = mentionedCharacters;
            this.gameTerms = gameTerms;
        }
    }

    private static final class MatchWeights {
        private final double highRelevance;
        private final double midRelevance;
        private final double densityHigh;
        private final double textLowScore;
        private final double textMentionedScore;
        private final int relatedNum;
        private final int lowTermScore;

        private MatchWeights(
            double highRelevance,
            double midRelevance,
            double densityHigh,
            double textLowScore,
            double textMentionedScore,
            int relatedNum,
            int lowTermScore
        ) {
            this.highRelevance = highRelevance;
            this.midRelevance = midRelevance;
            this.densityHigh = densityHigh;
            this.textLowScore = textLowScore;
            this.textMentionedScore = textMentionedScore;
            this.relatedNum = relatedNum;
            this.lowTermScore = lowTermScore;
        }

        private static MatchWeights fromObject(JSONObject configured) {
            double highRelevance = positiveOrDefault(
                configured,
                "HighRelevance",
                DEFAULT_HIGH_RELEVANCE
            );
            double midRelevance = positiveOrDefault(
                configured,
                "MidRelevance",
                DEFAULT_MID_RELEVANCE
            );
            double densityHigh = positiveOrDefault(
                configured,
                "DensityHigh",
                DEFAULT_DENSITY_HIGH
            );
            double textLowScore = positiveOrDefault(
                configured,
                "TextLowScore",
                DEFAULT_TEXT_LOW_SCORE
            );
            double textMentionedScore = positiveOrDefault(
                configured,
                "TextMentionedScore",
                DEFAULT_TEXT_MENTIONED_SCORE
            );
            int relatedNum = positiveIntOrDefault(
                configured,
                "RelatedNum",
                DEFAULT_RELATED_NUM
            );
            int lowTermScore = positiveIntOrDefault(
                configured,
                "LowTermScore",
                DEFAULT_LOW_TERM_SCORE
            );
            return new MatchWeights(
                highRelevance,
                midRelevance,
                densityHigh,
                textLowScore,
                textMentionedScore,
                relatedNum,
                lowTermScore
            );
        }

        private static double positiveOrDefault(
            JSONObject object,
            String key,
            double fallback
        ) {
            if (object == null) {
                return fallback;
            }
            double value = object.optDouble(key, fallback);
            return Double.isFinite(value) && value > 0.0d
                ? value
                : fallback;
        }

        private static int positiveIntOrDefault(
            JSONObject object,
            String key,
            int fallback
        ) {
            if (object == null || !object.has(key)) {
                return fallback;
            }
            Object value = object.opt(key);
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (Double.isFinite(number)
                    && number >= 1.0d
                    && number <= Integer.MAX_VALUE
                    && number == Math.rint(number)) {
                    return (int) number;
                }
            }
            return fallback;
        }
    }

    private static final class FirstSeenComparator
        implements Comparator<String> {
        private final Map<String, Integer> order = new HashMap<>();

        private FirstSeenComparator(List<String> firstSeen) {
            for (int index = 0; index < firstSeen.size(); index++) {
                order.put(firstSeen.get(index), index);
            }
        }

        @Override
        public int compare(String left, String right) {
            int leftOrder = order.containsKey(left)
                ? order.get(left) : Integer.MAX_VALUE;
            int rightOrder = order.containsKey(right)
                ? order.get(right) : Integer.MAX_VALUE;
            if (leftOrder != rightOrder) {
                return Integer.compare(leftOrder, rightOrder);
            }
            return left.compareTo(right);
        }
    }
}
