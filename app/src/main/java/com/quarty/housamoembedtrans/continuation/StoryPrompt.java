package com.quarty.housamoembedtrans.continuation;

import static com.quarty.housamoembedtrans.continuation.ContinuationFingerprint.order;

import android.content.Context;

import com.quarty.housamoembedtrans.context.history.SceneSummaryResolver;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.logging.Log;
import com.quarty.housamoembedtrans.translation.request.SceneTranslationRequestBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable source indexes and one reusable choice window for this exact Scene revision. */
final class StoryPrompt {
    final byte[] bytes;
    final JSONObject scene;
    final ContinuationFingerprint fingerprint;
    private final boolean localizedWithoutPositions;
    private final Positions choicePositions = new Positions();
    private final Positions textPositions = new Positions();
    private final Map<String, JSONObject> texts = new HashMap<>();
    private final Map<Integer, List<JSONObject>> dialogueByLabel = new HashMap<>();
    private final Map<String, JSONObject> choices = new HashMap<>();
    private final Set<String> selectedOptionOrders = new HashSet<>();
    private final Map<String, LineTail> following = new HashMap<>();
    private final Map<String, String> protect = new HashMap<>();
    private final List<JSONObject> characters = new ArrayList<>();
    private final List<JSONObject> terms = new ArrayList<>();

    private byte[] cachedKey;
    private Local cachedLocal;

    private static final class LineTail {
        final JSONObject item;
        final LineTail next;

        LineTail(JSONObject item, LineTail next) {
            this.item = item;
            this.next = next;
        }
    }

    StoryPrompt(byte[] bytes) throws Exception {
        this.bytes = bytes;
        scene = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        fingerprint = new ContinuationFingerprint(scene);
        JSONObject proof = fingerprint.proof;
        JSONArray positions = proof == null ? null : proof.optJSONArray("positions");
        localizedWithoutPositions = positions == null && proof != null
            && !proof.getString("original_hash").equals(proof.getString("captured_hash"));
        if (positions != null) {
            for (int i = 0; i < positions.length(); i++) {
                JSONObject pair = positions.getJSONObject(i);
                Positions destination = "choice".equals(pair.getString("type")) ? choicePositions : textPositions;
                destination.add(pair);
            }
        }
        JSONArray tokens = scene.getJSONArray("protect");
        for (int i = 0; i < tokens.length(); i++) {
            JSONObject token = tokens.getJSONObject(i);
            protect.put(token.getString("label"), token.getString("origin"));
        }
        index(scene.getJSONArray("scene_items"), null);
        for (Map.Entry<String, JSONObject> entry : texts.entrySet()) {
            if (selectedOptionOrders.contains(entry.getKey())) continue;
            JSONObject row = entry.getValue();
            int label = row.getJSONObject("order").getInt("label_index");
            dialogueByLabel.computeIfAbsent(label, ignored -> new ArrayList<>()).add(row);
        }
        for (List<JSONObject> rows : dialogueByLabel.values()) {
            rows.sort(Comparator.comparingInt((JSONObject row) -> row.optJSONObject("order").optInt("page_no"))
                .thenComparingInt(row -> row.optJSONObject("order").optInt("cmd_index")));
        }

        JSONObject cast = scene.optJSONObject("character");
        if (cast != null) {
            addEntries(characters, cast.optJSONArray("high_weight"));
            addEntries(characters, cast.optJSONArray("low_weight"));
        }
        addEntries(terms, scene.optJSONArray("game_terms"));
    }

    private static void addEntries(List<JSONObject> destination, JSONArray entries) throws JSONException {
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) destination.add(entries.getJSONObject(i));
    }

    private static final class Positions {
        private final Map<String, String> originalToView = new HashMap<>();
        private final Set<String> changedViews = new HashSet<>();
        void add(JSONObject pair) throws JSONException {
            String view = order(pair.getJSONObject("captured"));
            changedViews.add(view);
            JSONObject original = pair.optJSONObject("original");
            if (original != null) originalToView.put(order(original), view);
        }
        String view(String original) {
            String view = originalToView.get(original);
            return view != null ? view : (changedViews.contains(original) ? null : original);
        }
    }

    boolean matchesNode(JSONObject node) throws Exception {
        if (!fingerprint.matchesOriginal(node.getString("source_hash"))) return false;
        JSONObject root = node.optJSONObject("original_order");
        if (root == null) root = node.getJSONObject("source").optJSONObject("order");
        if (root != null && !choices.containsKey(choicePositions.view(order(root)))) return false;
        // A legacy two-hash Scene may still match the choice root when only a later option moved.
        JSONArray route = localizedWithoutPositions ? node.optJSONArray("route") : null;
        for (int i = 0; route != null && i < route.length(); i++) {
            JSONObject ref = route.getJSONObject(i);
            if (ref.has("record_id") || ref.has("captured_hash") || ref.optBoolean("stage")) continue;
            JSONObject position = ref.optJSONObject("order");
            if (!texts.containsKey(order(position == null ? ref : position))) return false;
        }
        return true;
    }

    // Sibling branches share an immutable tail through their enclosing merge.
    private void index(JSONArray items, LineTail next) throws JSONException {
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.getJSONObject(i);
            String key = order(item.getJSONObject("order"));
            switch (item.getString("type")) {
                case "text":
                    texts.put(key, item);
                    next = new LineTail(item, next);
                    break;
                case "choice":
                    choices.put(key, item);
                    following.put(key, next);
                    JSONArray branches = item.getJSONArray("branches");
                    for (int j = 0; j < branches.length(); j++) {
                        JSONObject branch = branches.getJSONObject(j);
                        JSONArray options = branch.getJSONArray("options");
                        for (int k = 0; k < options.length(); k++) {
                            JSONObject option = options.getJSONObject(k);
                            String optionOrder = order(option.getJSONObject("order"));
                            texts.put(optionOrder, option);
                            selectedOptionOrders.add(optionOrder);
                        }
                        index(branch.getJSONArray("following_text"), next);
                    }
                    next = null;
                    break;
                case "if":
                    index(item.getJSONArray("following_text"), next);
                    next = null;
                    break;
                default:
                    throw new JSONException("未知原剧情条目");
            }
        }
    }

    private JSONObject line(JSONObject item, String language) throws JSONException {
        JSONObject translations = item.optJSONObject("translations");
        String text = translations == null ? "" : translations.optString(language);
        if (!ContinuationContent.hasText(text)) text = item.getString("text");
        if (text.contains("__HET__")) {
            for (Map.Entry<String, String> token : protect.entrySet()) {
                text = text.replace(token.getKey(), token.getValue());
            }
        }
        return new JSONObject().put("speaker", item.optString("speaker")).put("text", text);
    }

    private static JSONObject atPath(JSONObject record, String path) throws JSONException {
        if (!path.startsWith("/steps/")) throw new JSONException("无效生成内容位置");
        Object value = record;
        for (String part : path.substring(1).split("/", -1)) {
            if (value instanceof JSONObject) {
                value = ((JSONObject) value).get(part);
            } else if (value instanceof JSONArray) {
                try {
                    value = ((JSONArray) value).get(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    throw new JSONException("无效生成分支位置");
                }
            } else {
                throw new JSONException("未知生成节点");
            }
        }
        if (!(value instanceof JSONObject)) throw new JSONException("生成位置未对应节点");
        return (JSONObject) value;
    }

    private static final class Ancestry {
        final Map<String, JSONObject> records = new HashMap<>();
        String viewChoice;
        JSONObject currentChoice;
    }

    private Ancestry ancestry(JSONObject node, JSONArray history) throws Exception {
        JSONObject source = node.getJSONObject("source");
        Ancestry chain = new Ancestry();
        while ("generated".equals(source.getString("type"))) {
            String id = source.getString("record_id");
            JSONObject parent = ContinuationContent.byId(history, id);
            if (parent == null || !fingerprint.matchesOriginal(parent.getString("source_hash"))
                    || chain.records.put(id, parent) != null) {
                throw new JSONException("当前节点所属的续写记录不可用");
            }
            JSONObject step = atPath(parent, source.getString("node_path"));
            if (!"choice".equals(step.optString("type"))) throw new JSONException("生成位置未对应选项");
            if (chain.currentChoice == null) chain.currentChoice = step;
            source = parent.getJSONObject("source");
        }
        String stable = order(source.getJSONObject("order"));
        if (node.has("original_order") && !stable.equals(order(node.getJSONObject("original_order")))) {
            throw new JSONException("续写选项与原合流位置不一致");
        }
        chain.viewChoice = choicePositions.view(stable);
        return chain;
    }

    private static JSONObject generatedLine(JSONObject ref, Ancestry chain) throws JSONException {
        JSONObject parent = chain.records.get(ref.getString("record_id"));
        if (parent == null) return null;
        if (ref.optBoolean("input")) {
            return new JSONObject().put("speaker", "mc").put("text", parent.getString("input"));
        }
        JSONObject entry = atPath(parent, ref.getString("node_path"));
        if ("dialogue".equals(entry.optString("type"))) {
            return ContinuationContent.project(entry, "speaker", "text");
        }
        // A selected option is a {text,steps} object, including when its nested list is empty.
        if (entry.has("text") && entry.has("steps")) {
            return new JSONObject().put("speaker", "mc").put("text", entry.getString("text"));
        }
        return null;
    }

    private static final class PlayedLine {
        final JSONObject text;
        final JSONObject order;
        final int routeIndex;

        PlayedLine(JSONObject text, JSONObject order, int routeIndex) {
            this.text = text;
            this.order = order;
            this.routeIndex = routeIndex;
        }
    }

    private static final class Window {
        final JSONArray recent = new JSONArray();
        final JSONArray after = new JSONArray();
        final Set<String> originalLines = new HashSet<>();
        final Set<String> actualStage = new HashSet<>();
        final Map<Integer, JSONObject> futureEnds = new HashMap<>();
        JSONObject lastAfter;
    }

    private Window window(JSONArray route, Ancestry chain, String language, JSONArray mergeWindow) throws Exception {
        List<PlayedLine> played = new ArrayList<>();
        for (int i = 0; i < route.length(); i++) {
            JSONObject ref = route.getJSONObject(i);
            if (ref.optBoolean("stage")) continue;
            JSONObject rendered;
            JSONObject position = null;
            if (ref.has("record_id")) {
                rendered = generatedLine(ref, chain);
            } else {
                position = ref.optJSONObject("order");
                if (position == null) position = ref;
                String key = order(position);
                if (ref.has("captured_hash")) {
                    if (!fingerprint.matchesCaptured(ref.getString("captured_hash"))) continue;
                } else key = textPositions.view(key);
                JSONObject item = texts.get(key);
                if (item == null) continue;
                position = item.getJSONObject("order");
                rendered = line(item, language);
                if (selectedOptionOrders.contains(key)) rendered.put("speaker", "mc");
            }
            if (rendered != null) played.add(new PlayedLine(rendered, position, i));
        }

        Window window = new Window();
        int start = Math.max(0, played.size() - 10);
        while (start > 0) {
            PlayedLine previous = played.get(start - 1);
            if (!question(previous.text) && !samePage(previous.order, played.get(start).order)) break;
            start--;
        }
        for (int i = start; i < played.size(); i++) {
            PlayedLine row = played.get(i);
            window.recent.put(row.text);
            if (row.order != null) window.originalLines.add(order(row.order));
        }
        int firstRoute = start == 0 ? 0 : played.get(start - 1).routeIndex + 1;
        for (int i = firstRoute; i < route.length(); i++) {
            JSONObject ref = route.getJSONObject(i);
            if (ref.optBoolean("stage")) window.actualStage.add(order(ref.getJSONObject("order")));
        }

        if (mergeWindow == null) {
            for (LineTail tail = following.get(chain.viewChoice); tail != null; tail = tail.next)
                if (!appendAfter(window, tail.item, language)) break;
            return window;
        }
        for (int i = 0; i < mergeWindow.length(); ++i) {
            JSONObject span = mergeWindow.getJSONObject(i);
            int label = span.getInt("label_index");
            JSONObject stop = span.optJSONObject("stop");
            for (JSONObject row : dialogueByLabel.getOrDefault(label, Collections.emptyList())) {
                if (stop != null && beforeOrAt(stop, row.getJSONObject("order"))) break;
                if (!appendAfter(window, row, language)) return window;
            }
            window.futureEnds.put(label, stop);
        }
        return window;
    }

    private boolean appendAfter(Window window, JSONObject row, String language) throws JSONException {
        JSONObject position = row.getJSONObject("order");
        int count = window.after.length();
        if (count >= 5 && !question(window.after.getJSONObject(count - 1)) && !samePage(window.lastAfter, position))
            return false;
        window.after.put(line(row, language));
        window.originalLines.add(order(position));
        window.futureEnds.put(position.getInt("label_index"), position);
        window.lastAfter = position;
        return true;
    }

    private JSONArray options(JSONObject original, Ancestry chain, String language) throws JSONException {
        JSONArray result = new JSONArray();
        if (chain.currentChoice != null) {
            JSONArray choices = chain.currentChoice.getJSONArray("options");
            for (int i = 0; i < choices.length(); i++) result.put(choices.getJSONObject(i).getString("text"));
            return result;
        }
        JSONArray branches = original.getJSONArray("branches");
        for (int i = 0; i < branches.length(); i++) {
            JSONArray choices = branches.getJSONObject(i).getJSONArray("options");
            for (int j = 0; j < choices.length(); j++) result.put(line(choices.getJSONObject(j), language).getString("text"));
        }
        return result;
    }
    private JSONArray stageReferences(Window window, JSONObject original, boolean actors, boolean backgrounds, boolean legacyWindow)
            throws JSONException {
        JSONArray result = new JSONArray();
        JSONObject stage = scene.optJSONObject("stage");
        if (stage == null) return result;

        // Include independent stage instructions in the merge label up to the selected text.
        JSONArray labels = stage.optJSONArray("labels");
        if (legacyWindow && labels != null && window.after.length() > 0) {
            String merge = original.getString("merge_label");
            for (int i = 0; i < labels.length(); i++) {
                if (merge.equals(labels.getString(i)) && !window.futureEnds.containsKey(i)) window.futureEnds.put(i, null);
            }
        }
        JSONArray states = stage.optJSONArray("states");
        if (states == null) return result;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < states.length(); i++) {
            JSONObject state = states.getJSONObject(i);
            JSONObject position = state.getJSONObject("order");
            String key = order(position);
            int label = position.getInt("label_index");
            JSONObject end = window.futureEnds.get(label);
            boolean selected = window.actualStage.contains(key)
                || window.futureEnds.containsKey(label) && (end == null || beforeOrAt(position, end));
            JSONArray linked = state.getJSONArray("text_orders");
            for (int j = 0; !selected && j < linked.length(); j++) {
                selected = window.originalLines.contains(order(linked.getJSONObject(j)));
            }
            if (!selected || !seen.add(key)) continue;
            JSONObject clean = ContinuationContent.project(state, "visibility");
            JSONObject actor = state.optJSONObject("character");
            JSONObject background = state.optJSONObject("background");
            if (actor != null) clean.put("character", actors ? actor : ContinuationContent.project(actor, "label", "pattern"));
            if (background != null) {
                clean.put("background", backgrounds ? background : ContinuationContent.project(background, "resource"));
            }
            if (actor != null && actors || background != null && backgrounds) clean.put("transition", state.opt("transition"));
            result.put(clean);
        }
        return result;
    }

    private static JSONObject screen(JSONObject screen, boolean actors) throws JSONException {
        JSONObject view = ContinuationContent.project(screen, "background");
        JSONArray cast = screen.optJSONArray("characters");
        JSONArray visible = new JSONArray();
        if (cast != null) {
            for (int i = 0; i < cast.length(); i++) {
                JSONObject actor = cast.getJSONObject(i);
                visible.put(actors ? actor : ContinuationContent.project(actor, "character", "name", "pattern"));
            }
        }
        return view.put("characters", visible);
    }

    private JSONObject resources(boolean actors, boolean backgrounds) throws JSONException {
        JSONObject stage = scene.optJSONObject("stage");
        JSONObject catalog = stage == null ? null : stage.optJSONObject("resources");
        JSONObject allowed = new JSONObject();
        if (catalog == null) return allowed;
        if (actors) {
            allowed.put("characters", catalog.optJSONObject("characters"));
            allowed.put("layers", catalog.optJSONObject("layers"));
        }
        if (backgrounds) allowed.put("backgrounds", catalog.optJSONArray("backgrounds"));
        return allowed;
    }

    // Only human-readable fields in the local story are evidence of a name or term.
    private static String corpus(Window window, JSONArray options, JSONArray branches, JSONObject view, JSONArray states)
            throws JSONException {
        StringBuilder story = new StringBuilder();
        appendLines(story, window.recent);
        appendLines(story, window.after);
        appendOptions(story, options);
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.getJSONObject(i);
            appendOptions(story, branch.getJSONArray("options"));
            appendLines(story, branch.getJSONArray("evidence"));
        }
        append(story, view.optString("background"));
        JSONArray visible = view.getJSONArray("characters");
        for (int i = 0; i < visible.length(); i++) {
            JSONObject actor = visible.getJSONObject(i);
            append(story, actor.optString("name"));
            append(story, actor.optString("character"));
        }
        for (int i = 0; i < states.length(); i++) {
            JSONObject state = states.getJSONObject(i);
            JSONObject actor = state.optJSONObject("character");
            JSONObject background = state.optJSONObject("background");
            if (actor != null) append(story, actor.optString("label"));
            if (background != null) append(story, background.optString("resource"));
        }
        return story.toString();
    }

    private static void append(StringBuilder story, String text) {
        if (!text.isEmpty()) story.append(text).append('\n');
    }

    private static void appendLines(StringBuilder story, JSONArray lines) throws JSONException {
        for (int i = 0; i < lines.length(); i++) {
            JSONObject row = lines.getJSONObject(i);
            if (row.opt("speaker") instanceof String) append(story, row.getString("speaker"));
            append(story, row.getString("text"));
        }
    }

    private static void appendOptions(StringBuilder story, JSONArray options) throws JSONException {
        for (int i = 0; i < options.length(); i++) append(story, options.getString(i));
    }

    private static final class Matches {
        final Set<JSONObject> cast = new HashSet<>();
        final Set<JSONObject> aliases = new HashSet<>();
        final Set<JSONObject> terms = new HashSet<>();
        final Set<String> relationshipTargets = new HashSet<>();

        void include(Matches other) {
            cast.addAll(other.cast);
            aliases.addAll(other.aliases);
            terms.addAll(other.terms);
            relationshipTargets.addAll(other.relationshipTargets);
        }
    }

    private Matches matches(String corpus) throws JSONException {
        Matches hits = new Matches();
        for (JSONObject person : characters) {
            boolean involved = mentioned(person, corpus, "name");
            JSONArray aliases = person.optJSONArray("aliases");
            if (aliases != null) {
                for (int i = 0; i < aliases.length(); i++) {
                    JSONObject alias = aliases.getJSONObject(i);
                    if (mentioned(alias, corpus, "name")) {
                        hits.aliases.add(alias);
                        involved = true;
                    }
                }
            }
            if (involved) {
                hits.cast.add(person);
                // Relationship targets in Scene use the source name, even if the dialogue used a translation.
                hits.relationshipTargets.add(person.getString("name"));
            }
            // A target can be mentioned directly even when there is no profile for them in this Scene.
            JSONArray relationships = person.optJSONArray("relationships");
            if (relationships != null) {
                for (int i = 0; i < relationships.length(); i++) {
                    String target = relationships.getJSONObject(i).getString("target");
                    if (!target.isEmpty() && corpus.contains(target)) hits.relationshipTargets.add(target);
                }
            }
        }
        for (JSONObject term : terms) {
            if (mentioned(term, corpus, "term")) hits.terms.add(term);
        }
        return hits;
    }

    private void addProfiles(JSONObject input, Matches hits) throws JSONException {
        JSONArray profiles = new JSONArray();
        for (JSONObject person : characters) {
            if (!hits.cast.contains(person)) continue;
            JSONObject brief = ContinuationContent.project(person, "name", "i18n", "speech_style");
            JSONArray aliases = person.optJSONArray("aliases");
            JSONArray matched = new JSONArray();
            if (aliases != null) {
                for (int i = 0; i < aliases.length(); i++) {
                    JSONObject alias = aliases.getJSONObject(i);
                    if (hits.aliases.contains(alias)) matched.put(alias);
                }
            }
            if (matched.length() > 0) brief.put("aliases", matched);
            JSONArray relationships = person.optJSONArray("relationships");
            JSONArray related = new JSONArray();
            if (relationships != null) {
                for (int i = 0; i < relationships.length(); i++) {
                    JSONObject relationship = relationships.getJSONObject(i);
                    if (hits.relationshipTargets.contains(relationship.getString("target"))) related.put(relationship);
                }
            }
            if (related.length() > 0) brief.put("relationships", related);
            profiles.put(brief);
        }
        JSONArray matchedTerms = new JSONArray();
        for (JSONObject term : terms) {
            if (hits.terms.contains(term)) matchedTerms.put(term);
        }
        input.put("characters", profiles).put("terms", matchedTerms);
    }

    static final class Local {
        final String body;
        final String catalog;
        final Matches baseMatches;

        Local(JSONObject body, JSONObject catalog, Matches baseMatches) {
            this.body = body.toString();
            this.catalog = catalog.toString();
            this.baseMatches = baseMatches;
        }

        JSONObject resources() throws JSONException {
            return new JSONObject(catalog);
        }
    }

    Local local(Context context, JSONObject node, String language, boolean actors, boolean backgrounds) throws Exception {
        JSONObject identity = ContinuationContent.project(node, "scene", "source_hash", "source", "original_order", "route", "screen", "merge_window");
        String summary = previousSummary(context, scene.getString("scene"), language);
        byte[] key = SceneTranslationRequestBuilder.toCanonicalBytes(identity
            .put("language", language).put("allow_character", actors).put("allow_background", backgrounds)
            .put("previous_summary", summary));
        synchronized (this) {
            if (Arrays.equals(cachedKey, key)) return cachedLocal;
        }

        // Read Context and saved records outside the cache lock. Ancestor records are immutable by ID.
        JSONArray history = "generated".equals(node.getJSONObject("source").getString("type"))
            ? ContinuationContent.all(context, node.getString("scene")) : new JSONArray();
        synchronized (this) {
            if (Arrays.equals(cachedKey, key)) return cachedLocal;
            Ancestry chain = ancestry(node, history);
            JSONObject original = choices.get(chain.viewChoice);
            if (original == null) throw new JSONException("当前选项在原剧本中没有对应位置");
            JSONArray spans = node.optJSONArray("merge_window");
            Window window = window(node.getJSONArray("route"), chain, language, spans);
            JSONArray options = options(original, chain, language);
            JSONArray branches = original.getJSONArray("branches");
            JSONArray premises = branches.length() > 1 ? branchReferences(branches, window.after, language) : new JSONArray();
            JSONArray states = stageReferences(window, original, actors, backgrounds, spans == null);
            JSONObject view = screen(node.getJSONObject("screen"), actors);
            JSONObject catalog = resources(actors, backgrounds);
            JSONObject body = new JSONObject()
                .put("recent", window.recent)
                .put("current_options", options)
                .put("after_merge", window.after)
                .put("screen", view)
                .put("stage_reference", states)
                .put("language", language)
                .put("previous_summary", summary);
            if (premises.length() > 0) body.put("alternative_branch_references", premises);
            Matches hits = matches(corpus(window, options, premises, view, states));
            Local prepared = new Local(body, catalog, hits);
            cachedLocal = prepared;
            cachedKey = key;
            return prepared;
        }
    }

    JSONArray messages(Local local, String input, String systemPrompt, JSONObject catalog) throws JSONException {
        // Give each submission its own payload. Nothing from this input is published to the node cache.
        JSONObject body = new JSONObject(local.body).put("input", input);
        if (catalog.length() > 0) body.put("resources", catalog);
        Matches hits = matches(input);
        hits.include(local.baseMatches);
        addProfiles(body, hits);
        return new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", systemPrompt))
            .put(new JSONObject().put("role", "user").put("content", body.toString()));
    }

    static String instructions(JSONObject generation) throws JSONException {
        int target = generation.getInt("dialogue_target");
        boolean actors = generation.getBoolean("allow_character");
        boolean bg = generation.getBoolean("allow_background");
        StringBuilder instructions = new StringBuilder(
            "玩家输入是主角刚说出的原话。从其他角色的回应或旁白开始，使用 language 指定的语言。"
            + "recent 是实际经历，current_options 是眼前可选的发言，after_merge 是随后原剧情。"
            + "alternative_branch_references 是未发生的原分支备选依据。写到能自然回到合流位置为止。"
            + "只输出一个 JSON 对象。成功时返回 status 为 ok 和有序的 steps 数组。"
            + "dialogue step 的字段是 type=dialogue、speaker、text。旁白可用空名字。每条完整路线至少有一条对白。");
        if (target != 0) instructions.append("每条路线生成 ").append(target).append(" 条左右的对白。");
        if (generation.getBoolean("allow_choices")) {
            instructions.append("可用 type=choice，options 是 {text,steps} 数组。主角的候选发言写在选项里。分支后继续外层步骤。");
        }
        if (actors) {
            instructions.append("可用 type=stage 的 action=character（character、可选 pattern/layer/x/y/fade）")
                .append("或 character_off（target、可选 fade）。target 也可为图层或 all。")
                .append("坐标是相对图层基准的原生单位，可为负数或位于屏幕外。");
        }
        if (bg) instructions.append("可用 type=stage 的 action=bg（bg、可选 fade）。");
        if (actors || bg) {
            instructions.append("只选 resources 中的资源。fade 是非负秒数，可省略使用默认。可用无参数 action=wait_fade 等待过渡。");
        }
        return instructions.append("只用上文列出的类型和动作，不返回跳转标签。")
            .append("拒绝时返回 status=error、error={code:refused,message:原因}。").toString();
    }

    private static boolean samePage(JSONObject a, JSONObject b) throws JSONException {
        return a != null && b != null && a.getInt("label_index") == b.getInt("label_index")
            && a.getInt("page_no") == b.getInt("page_no");
    }

    private static boolean beforeOrAt(JSONObject a, JSONObject b) throws JSONException {
        int page = Integer.compare(a.getInt("page_no"), b.getInt("page_no"));
        return page < 0 || page == 0 && a.getInt("cmd_index") <= b.getInt("cmd_index");
    }

    private static final Pattern NAMED_REFERENCE =
        Pattern.compile("[「『《“]([^「」『』《》“”\\n]{2,40})[」』》”]");

    // Cite a branch only when the merge text shares a term or a quoted name.
    private JSONArray branchReferences(JSONArray branches, JSONArray after, String language) throws JSONException {
        StringBuilder upcoming = new StringBuilder();
        for (int i = 0; i < after.length(); i++) append(upcoming, after.getJSONObject(i).getString("text"));
        String text = upcoming.toString();
        Set<String> anchors = new HashSet<>();
        Matcher names = NAMED_REFERENCE.matcher(text);
        while (names.find()) anchors.add(names.group(1));
        for (JSONObject term : terms) {
            if (!mentioned(term, text, "term")) continue;
            String canonical = term.getString("term");
            if (!canonical.isEmpty()) anchors.add(canonical);
            JSONObject translations = term.optJSONObject("i18n");
            if (translations == null) continue;
            for (Iterator<String> it = translations.keys(); it.hasNext();) {
                String translated = translations.optString(it.next());
                if (!translated.isEmpty()) anchors.add(translated);
            }
        }

        JSONArray references = new JSONArray();
        if (anchors.isEmpty()) return references;
        for (int i = 0; i < branches.length(); i++) {
            JSONObject branch = branches.getJSONObject(i);
            JSONArray rows = branch.getJSONArray("following_text");
            JSONArray evidence = new JSONArray();
            JSONObject previous = null;
            boolean previousIncluded = false;
            boolean includeAnswer = false;
            for (int j = 0; j < rows.length(); j++) {
                JSONObject row = rows.getJSONObject(j);
                if (!"text".equals(row.getString("type"))) {
                    previous = null;
                    previousIncluded = false;
                    includeAnswer = false;
                    continue;
                }
                JSONObject candidate = line(row, language);
                String sentence = candidate.getString("text");
                boolean matches = false;
                for (String anchor : anchors) {
                    if (sentence.contains(anchor)) {
                        matches = true;
                        break;
                    }
                }
                if (matches && !previousIncluded && previous != null && question(previous)) evidence.put(previous);
                previousIncluded = matches || includeAnswer;
                if (previousIncluded) evidence.put(candidate);
                includeAnswer = matches && question(candidate);
                previous = candidate;
            }
            if (evidence.length() > 0) {
                JSONArray options = new JSONArray();
                JSONArray entries = branch.getJSONArray("options");
                for (int j = 0; j < entries.length(); j++) {
                    options.put(line(entries.getJSONObject(j), language).getString("text"));
                }
                references.put(new JSONObject().put("options", options).put("evidence", evidence));
            }
        }
        return references;
    }

    private static boolean question(JSONObject line) {
        String text = line.optString("text").trim();
        return text.endsWith("？") || text.endsWith("?") || text.endsWith("？」");
    }

    private static boolean mentioned(JSONObject item, String corpus, String key) {
        String name = item.optString(key);
        if (!name.isEmpty() && corpus.contains(name)) return true;
        JSONObject translated = item.optJSONObject("i18n");
        if (translated == null) return false;
        for (Iterator<String> it = translated.keys(); it.hasNext();) {
            String value = translated.optString(it.next());
            if (!value.isEmpty() && corpus.contains(value)) return true;
        }
        return false;
    }

    private static String previousSummary(Context context, String scene, String language) {
        try {
            SceneContextStore store = new SceneContextStore(context);
            String active = store.getActiveContextId();
            if (active == null || active.isEmpty()) return null;
            JSONArray scenes = store.getContext(active).getJSONArray("scenes");
            String previous = null;
            for (int i = 0; i < scenes.length(); i++) {
                JSONObject entry = scenes.getJSONObject(i);
                if (scene.equals(entry.getString("scene"))) return previous;
                SceneSummaryResolver.Resolved resolved =
                    SceneSummaryResolver.resolve(entry.optJSONObject("summaries"), language);
                if (resolved != null) previous = resolved.text;
            }
        } catch (Exception e) {
            Log.w("HET.Continuation", "前情摘要不可用", e);
        }
        return null;
    }
}
