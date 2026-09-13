package com.quarty.housamoembedtrans.storage.config;

import com.quarty.housamoembedtrans.util.IoUtils;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Persists the editable natural-language portions of the provider prompts.
 *
 * <p>The bundled prompt remains the source of the structured contract.  A
 * saved document only contains the three natural-language sections exposed by
 * the settings UI, so a malformed or incomplete override can never replace
 * the output protocol, input schema, or retry rules.</p>
 */
public final class PromptStore {
    public static final String TRANSLATION = "translation";
    public static final String SUMMARY = "summary";

    /** Kept out of the editor and inserted into every translation prompt. */
    public static final String TRANSLATION_PROTOCOL =
        "Translate the player-visible scenario text from raw_lang into target_lang.";

    private static final int MAX_PROMPT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SECTION_LENGTH = 128 * 1024;
    private static final String TRANSLATION_ASSET = "term/prompt.txt";
    private static final String SUMMARY_ASSET = "summary_prompt.txt";
    private static final String DIRECTORY_NAME = "prompt_drafts";
    private static final String TRANSLATION_FILE = "translation.json";
    private static final String SUMMARY_FILE = "summary.json";
    private static final String KEY_ROLE = "role";
    private static final String KEY_STYLE = "style";
    private static final String KEY_CONTEXT = "context";
    private static final String EDITABLE_HEADING = "## HET editable guidance";
    /** Fixed asset text that belongs to the protocol, not the editable style. */
    private static final String TRANSLATION_ASSET_CONTEXT_INTRO =
        "When context conflicts or is ambiguous, use this priority:";
    /** The concise wording is exposed through the editable summary style. */
    private static final String SUMMARY_ASSET_CONCISE_RULE =
        "- Keep the summary concise but information-dense.";
    private static final Object LOCK = new Object();

    private static final PromptDocument DEFAULT_TRANSLATION =
        new PromptDocument(
            "You are a professional localization translator for Tokyo Afterschool Summoners (Housamo).",
            "Produce faithful, natural localization that preserves each character's meaning, emotion, personality, register, and speech style. Do not omit, censor, summarize, explain, or add information that is not present in the source text.",
            "When context conflicts or is ambiguous, use the current source text and its local scene context first. Use official information and existing target-language values to disambiguate, but never use context to rewrite or contradict what the source text actually says. Translate only player-visible text and preserve distinctions that matter to characterization."
        );
    private static final PromptDocument DEFAULT_SUMMARY =
        new PromptDocument(
            "You are a careful Housamo / Tokyo Afterschool Summoners story continuity assistant.",
            "Given the request kind and summary input, produce a single concise, information-dense summary. Preserve story continuity, character relationships, locations, and unresolved threads.",
            "Do not invent events that are not supported by the provided summary input. Keep the summary faithful to the supplied context and return only the requested summary content."
        );

    /** The natural-language sections that may be edited by the user. */
    public static final class PromptDocument {
        private final String role;
        private final String style;
        private final String context;

        public PromptDocument(String role, String style, String context) {
            this.role = requireSection(role, KEY_ROLE);
            this.style = requireSection(style, KEY_STYLE);
            this.context = requireSection(context, KEY_CONTEXT);
        }

        public String getRole() {
            return role;
        }

        public String getStyle() {
            return style;
        }

        public String getContext() {
            return context;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                .put(KEY_ROLE, role)
                .put(KEY_STYLE, style)
                .put(KEY_CONTEXT, context);
        }

        public static PromptDocument fromJson(JSONObject json) {
            if (json == null) {
                throw new IllegalArgumentException("prompt document is null");
            }
            return new PromptDocument(
                json.optString(KEY_ROLE, ""),
                json.optString(KEY_STYLE, ""),
                json.optString(KEY_CONTEXT, "")
            );
        }

        public PromptDocument copy() {
            return new PromptDocument(role, style, context);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PromptDocument)) {
                return false;
            }
            PromptDocument document = (PromptDocument) other;
            return role.equals(document.role)
                && style.equals(document.style)
                && context.equals(document.context);
        }

        @Override
        public int hashCode() {
            int result = role.hashCode();
            result = 31 * result + style.hashCode();
            return 31 * result + context.hashCode();
        }
    }

    private final Context context;
    private final File directory;

    public PromptStore(Context context) {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;
        this.directory = new File(this.context.getFilesDir(), DIRECTORY_NAME);
    }

    /** Loads an override, falling back to the bundled natural-language parts. */
    public PromptDocument load(String kind) throws IOException {
        validateKind(kind);
        synchronized (LOCK) {
            PromptDocument defaults = loadDefaults(kind);
            File file = fileFor(kind);
            if (!IoUtils.atomicFileExists(file)) {
                return defaults;
            }
            try {
                JSONObject json = readJson(new AtomicFile(file));
                return PromptDocument.fromJson(json);
            } catch (Exception error) {
                // A damaged override must not stop translation startup.  The
                // file remains available for inspection and can be replaced
                // by the next explicit save from Settings.
                return defaults;
            }
        }
    }

    /** Returns the bundled editable sections without reading user state. */
    public PromptDocument loadDefaults(String kind) throws IOException {
        validateKind(kind);
        synchronized (LOCK) {
            return TRANSLATION.equals(kind)
                ? DEFAULT_TRANSLATION.copy()
                : DEFAULT_SUMMARY.copy();
        }
    }

    /** Atomically persists one kind while leaving the other kind untouched. */
    public void save(String kind, PromptDocument document) throws IOException {
        validateKind(kind);
        if (document == null) {
            throw new IllegalArgumentException("prompt document is null");
        }
        synchronized (LOCK) {
            IoUtils.ensureDirectory(directory);
            try {
                IoUtils.writeAtomically(
                    fileFor(kind),
                    document.toJson().toString().getBytes(StandardCharsets.UTF_8)
                );
            } catch (JSONException error) {
                throw new IOException("could not encode prompt document", error);
            }
        }
    }

    /** Builds the complete translation system prompt for a new request. */
    public String loadTranslationPrompt() throws IOException {
        synchronized (LOCK) {
            return composeTranslation(readAsset(TRANSLATION), load(TRANSLATION));
        }
    }

    /** Builds the complete summary system prompt for a new request. */
    public String loadSummaryPrompt() throws IOException {
        synchronized (LOCK) {
            return composeSummary(readAsset(SUMMARY), load(SUMMARY));
        }
    }

    private String readAsset(String kind) throws IOException {
        String path = TRANSLATION.equals(kind) ? TRANSLATION_ASSET : SUMMARY_ASSET;
        try (InputStream input = context.getAssets().open(path)) {
            return IoUtils.readUtf8Limited(input, MAX_PROMPT_BYTES);
        }
    }

    private String composeTranslation(
        String template,
        PromptDocument document
    ) throws IOException {
        String originalRole = firstNonEmptyLine(template);
        String result = replaceFirstLineStartingWith(
            template,
            "Translate the player-visible scenario text from",
            TRANSLATION_PROTOCOL
        );
        result = removeFirstExact(result, DEFAULT_TRANSLATION.style);
        result = removeFirstExact(result, TRANSLATION_ASSET_CONTEXT_INTRO);
        result = replaceFirstExact(result, originalRole, "");
        return assembleEditableSections(result, document);
    }

    private String composeSummary(
        String template,
        PromptDocument document
    ) throws IOException {
        String originalRole = firstNonEmptyLine(template);
        String result = replaceFirstExact(
            template,
            originalRole,
            ""
        );
        result = removeFirstExact(result, SUMMARY_ASSET_CONCISE_RULE);
        return assembleEditableSections(result, document);
    }

    private String firstNonEmptyLine(String text) throws IOException {
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        throw new IOException("prompt asset has no content");
    }

    private String assembleEditableSections(
        String fixedTemplate,
        PromptDocument document
    ) {
        return document.role
            + "\n\n"
            + EDITABLE_HEADING
            + "\n\n"
            + document.style
            + "\n\n"
            + document.context
            + "\n\n"
            + removeLeadingLineBreaks(fixedTemplate);
    }

    private static String removeLeadingLineBreaks(String value) {
        int start = 0;
        while (start < value.length()
            && (value.charAt(start) == '\r' || value.charAt(start) == '\n')) {
            start++;
        }
        return value.substring(start);
    }

    private String replaceFirstExact(String text, String oldValue, String value)
        throws IOException {
        int index = text.indexOf(oldValue);
        if (index < 0) {
            throw new IOException("prompt asset section is not stable");
        }
        return text.substring(0, index)
            + value
            + text.substring(index + oldValue.length());
    }

    private String removeFirstExact(String text, String value) {
        int index = text.indexOf(value);
        if (index < 0) {
            return text;
        }
        int end = index + value.length();
        while (end < text.length()
            && (text.charAt(end) == '\r' || text.charAt(end) == '\n')) {
            end++;
        }
        return text.substring(0, index) + text.substring(end);
    }

    private String replaceFirstLineStartingWith(
        String text,
        String prefix,
        String replacement
    ) throws IOException {
        String[] lines = text.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().startsWith(prefix)) {
                lines[index] = replacement;
                return joinLines(lines);
            }
        }
        throw new IOException("prompt asset protocol section is missing");
    }

    private static String joinLines(String[] lines) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                result.append('\n');
            }
            result.append(lines[index]);
        }
        return result.toString();
    }

    private JSONObject readJson(AtomicFile file) throws IOException, JSONException {
        try (InputStream input = file.openRead()) {
            return new JSONObject(
                IoUtils.readUtf8Limited(input, MAX_PROMPT_BYTES)
            );
        }
    }

    private File fileFor(String kind) {
        return new File(
            directory,
            TRANSLATION.equals(kind) ? TRANSLATION_FILE : SUMMARY_FILE
        );
    }

    private static void validateKind(String kind) {
        if (!TRANSLATION.equals(kind) && !SUMMARY.equals(kind)) {
            throw new IllegalArgumentException("unknown prompt kind: " + kind);
        }
    }

    private static String requireSection(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
        if (value.length() > MAX_SECTION_LENGTH) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value.trim();
    }
}
