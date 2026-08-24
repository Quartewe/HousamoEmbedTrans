package com.quarty.housamoembedtrans.context.review;
import com.quarty.housamoembedtrans.context.store.ContextStore;
import com.quarty.housamoembedtrans.context.store.GroupStore;
import com.quarty.housamoembedtrans.context.store.SceneContextStore;
import com.quarty.housamoembedtrans.summary.job.SummaryJobStore;

import com.quarty.housamoembedtrans.translation.job.TranslationJobStore;
import com.quarty.housamoembedtrans.util.IoUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outer before-image journal for one Context/Group Review save.
 *
 * <p>SceneContextStore's internal {@code .txn} protects its own multi-file
 * mutations. Review additionally rewrites Translation Job mappings and may
 * remove/admit Summary Jobs, so those stores need one root-scoped recovery
 * marker as well. The journal has an explicit path allow-list; it is never
 * fed into SceneContextStore's entity transaction resolver.</p>
 */
public final class ReviewTransactionJournal implements AutoCloseable {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_SNAPSHOT_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_JOURNAL_BYTES = 64 * 1024 * 1024;
    private static final String JOURNAL_DIR = ".review_txn";
    private static final String JOURNAL_PREFIX = "review-";

    private final File filesRoot;
    private final File journalFile;
    private final JSONObject document;
    private boolean finished;

    @FunctionalInterface
    interface AtomicWriter {
        void write(File file, byte[] bytes) throws IOException;
    }

    private ReviewTransactionJournal(
        File filesRoot,
        File journalFile,
        JSONObject document
    ) {
        this.filesRoot = filesRoot;
        this.journalFile = journalFile;
        this.document = document;
    }

    /** Starts a journal after taking before-images of the review scope. */
    public static ReviewTransactionJournal begin(
        SceneContextStore sceneStore,
        TranslationJobStore translationStore,
        SummaryJobStore summaryStore
    ) throws Exception {
        if (sceneStore == null
            || translationStore == null
            || summaryStore == null) {
            throw new IllegalArgumentException("review stores are required");
        }
        File filesRoot = sceneStore.getDirectory().getParentFile();
        if (filesRoot == null) {
            throw new IOException("SceneContextStore has no files root");
        }
        recover(filesRoot);
        List<String> paths = listAllowedPaths(filesRoot);
        JSONArray initialFiles = new JSONArray();
        for (String path : paths) {
            initialFiles.put(path);
        }
        JSONArray initialDirectories = new JSONArray();
        for (String path : listAllowedDirectories(filesRoot)) {
            initialDirectories.put(path);
        }
        JSONObject root = new JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("initial_files", initialFiles)
            .put("initial_directories", initialDirectories);
        JSONArray entries = new JSONArray();
        for (String path : paths) {
            File file = resolveAllowedPath(filesRoot, path);
            byte[] before = file.isFile()
                ? readBytes(file, MAX_SNAPSHOT_ENTRY_BYTES)
                : null;
            entries.put(new JSONObject()
                .put("path", path)
                .put(
                    "before",
                    before == null
                        ? JSONObject.NULL
                        : Base64.getEncoder().encodeToString(before)
                ));
        }
        root.put("files", entries);
        byte[] journalBytes = (root.toString() + "\n")
            .getBytes(StandardCharsets.UTF_8);
        if (journalBytes.length > MAX_JOURNAL_BYTES) {
            throw new IOException(
                "Review transaction journal exceeds "
                    + MAX_JOURNAL_BYTES
                    + " bytes"
            );
        }
        File directory = new File(filesRoot, SceneContextStore.DIRECTORY_NAME
            + File.separator + JOURNAL_DIR);
        IoUtils.ensureDirectory(directory);
        File journal = new File(
            directory,
            JOURNAL_PREFIX + UUID.randomUUID().toString() + ".json"
        );
        IoUtils.writeAtomically(
            journal,
            journalBytes
        );
        return new ReviewTransactionJournal(filesRoot, journal, root);
    }

    /** Marks the review committed and removes its recovery marker. */
    public void commit() throws IOException {
        if (finished) {
            return;
        }
        if (journalFile.isFile() && !journalFile.delete()) {
            try {
                rollbackDocument(filesRoot, document, journalFile);
            } catch (Exception rollbackFailure) {
                throw new IOException(
                    "could not delete Review journal or roll back",
                    rollbackFailure
                );
            }
            throw new IOException(
                "could not delete Review transaction journal "
                    + journalFile.getName()
            );
        }
        finished = true;
    }

    /** Rolls all allowed files back to their before-images. */
    public void rollback() throws Exception {
        if (finished) {
            return;
        }
        rollbackDocument(filesRoot, document, journalFile);
        finished = true;
    }

    @Override
    public void close() throws Exception {
        if (!finished) {
            rollback();
        }
    }

    /** Recovers every interrupted Review before API work can open. */
    public static void recover(File filesRoot) throws SceneContextStore.StorageException {
        if (filesRoot == null) {
            return;
        }
        File directory = new File(
            new File(filesRoot, SceneContextStore.DIRECTORY_NAME),
            JOURNAL_DIR
        );
        File[] journals = directory.listFiles();
        if (journals == null) {
            return;
        }
        for (File journal : journals) {
            if (!journal.isFile()
                || !journal.getName().startsWith(JOURNAL_PREFIX)) {
                continue;
            }
            try {
                JSONObject document = new JSONObject(
                    new String(
                        readBytes(journal, MAX_JOURNAL_BYTES),
                        StandardCharsets.UTF_8
                    )
                );
                rollbackDocument(filesRoot, document, journal);
            } catch (Exception e) {
                throw new SceneContextStore.StorageException(
                    SceneContextStore.FailureKind.INVALID_STATE,
                    "could not recover Review transaction journal "
                        + journal.getName(),
                    e
                );
            }
        }
    }

    private static void rollbackDocument(
        File filesRoot,
        JSONObject document,
        File journalFile
    ) throws Exception {
        rollbackDocument(
            filesRoot,
            document,
            journalFile,
            IoUtils::writeAtomically
        );
    }

    static void rollbackDocument(
        File filesRoot,
        JSONObject document,
        File journalFile,
        AtomicWriter writer
    ) throws Exception {
        if (writer == null) {
            throw new IllegalArgumentException("atomic writer is required");
        }
        if (document.optInt("format_version", -1) != FORMAT_VERSION) {
            throw new IOException("unsupported Review transaction journal format");
        }
        JSONArray entries = document.optJSONArray("files");
        JSONArray initial = document.optJSONArray("initial_files");
        JSONArray initialDirectories = document.optJSONArray(
            "initial_directories"
        );
        if (entries == null || initial == null || initialDirectories == null) {
            throw new IOException("Review transaction journal is incomplete");
        }
        // A Review may have deleted an entire initial job directory before
        // crashing.  Recreate only the journal's explicit job directories
        // before restoring request/state before-images; AtomicFile requires
        // the parent directory to exist.
        for (int index = 0; index < initialDirectories.length(); index++) {
            String path = initialDirectories.getString(index);
            IoUtils.ensureDirectory(resolveAllowedDirectory(filesRoot, path));
        }
        for (int index = entries.length() - 1; index >= 0; index--) {
            JSONObject entry = entries.getJSONObject(index);
            String path = entry.getString("path");
            File file = resolveAllowedPath(filesRoot, path);
            Object before = entry.opt("before");
            if (before == null || before == JSONObject.NULL) {
                if (file.isFile() && !file.delete()) {
                    throw new IOException("could not remove " + path);
                }
            } else {
                if (!(before instanceof String)) {
                    throw new IOException("invalid before-image for " + path);
                }
                writer.write(
                    file,
                    Base64.getDecoder().decode((String) before)
                );
            }
        }

        Set<String> initialPaths = new HashSet<>();
        for (int index = 0; index < initial.length(); index++) {
            initialPaths.add(initial.getString(index));
        }
        Set<String> initialDirectoryPaths = new HashSet<>();
        for (int index = 0; index < initialDirectories.length(); index++) {
            initialDirectoryPaths.add(initialDirectories.getString(index));
        }
        for (String current : listAllowedPaths(filesRoot)) {
            if (initialPaths.contains(current)) {
                continue;
            }
            File file = resolveAllowedPath(filesRoot, current);
            if (file.isFile() && !file.delete()) {
                throw new IOException("could not remove new Review file " + current);
            }
        }
        for (String current : listAllowedDirectories(filesRoot)) {
            if (initialDirectoryPaths.contains(current)) {
                continue;
            }
            File directory = resolveAllowedDirectory(filesRoot, current);
            deleteReviewCreatedJobDirectory(directory);
        }
        if (journalFile.isFile() && !journalFile.delete()) {
            throw new IOException("could not remove Review journal");
        }
    }

    private static List<String> listAllowedPaths(File filesRoot) {
        List<String> result = new ArrayList<>();
        collectScenePaths(filesRoot, result);
        collectJobStatePaths(
            filesRoot,
            TranslationJobStore.DIRECTORY_NAME,
            false,
            result
        );
        collectJobStatePaths(
            filesRoot,
            SummaryJobStore.DIRECTORY_NAME,
            true,
            result
        );
        java.util.Collections.sort(result);
        return result;
    }

    private static List<String> listAllowedDirectories(File filesRoot) {
        List<String> result = new ArrayList<>();
        collectJobDirectories(
            filesRoot,
            TranslationJobStore.DIRECTORY_NAME,
            result
        );
        collectJobDirectories(
            filesRoot,
            SummaryJobStore.DIRECTORY_NAME,
            result
        );
        java.util.Collections.sort(result);
        return result;
    }

    private static void collectJobDirectories(
        File filesRoot,
        String directoryName,
        List<String> result
    ) {
        File root = new File(filesRoot, directoryName);
        File[] jobs = root.listFiles();
        if (jobs == null) {
            return;
        }
        for (File job : jobs) {
            if (job.isDirectory()
                && job.getName().matches("[A-Za-z0-9_-]+")) {
                result.add(directoryName + "/" + job.getName());
            }
        }
    }

    private static void collectScenePaths(File filesRoot, List<String> result) {
        addIfFile(
            filesRoot,
            SceneContextStore.DIRECTORY_NAME + "/index.json",
            result
        );
        collectJsonFiles(
            new File(
                new File(filesRoot, SceneContextStore.DIRECTORY_NAME),
                ContextStore.DIRECTORY_NAME
            ),
            filesRoot,
            result
        );
        collectJsonFiles(
            new File(
                new File(filesRoot, SceneContextStore.DIRECTORY_NAME),
                GroupStore.DIRECTORY_NAME
            ),
            filesRoot,
            result
        );
    }

    private static void collectJobStatePaths(
        File filesRoot,
        String directoryName,
        boolean summary,
        List<String> result
    ) {
        File root = new File(filesRoot, directoryName);
        File[] jobs = root.listFiles();
        if (jobs == null) {
            return;
        }
        for (File job : jobs) {
            if (!job.isDirectory()) {
                continue;
            }
            boolean include = true;
            File stateFile = new File(job, "state.json");
            if (stateFile.isFile()) {
                try {
                    JSONObject state = new JSONObject(
                        new String(
                            readBytes(stateFile, MAX_SNAPSHOT_ENTRY_BYTES),
                            StandardCharsets.UTF_8
                        )
                    );
                    String status = state.optString("status", "");
                    // Review reconciliation can mark a running Summary Job
                    // rerun-required. Keep its state before-image so a later
                    // transaction failure restores that marker as well. A
                    // running Translation Job is never rewritten by Review.
                    include = summary
                        || "queued".equals(status);
                } catch (Exception ignored) {
                    include = true;
                }
            }
            if (!include) {
                continue;
            }
            addIfFile(
                filesRoot,
                directoryName + "/" + job.getName() + "/state.json",
                result
            );
            if (summary) {
                addIfFile(
                    filesRoot,
                    directoryName + "/" + job.getName() + "/request.json",
                    result
                );
            }
        }
    }

    private static void collectJsonFiles(
        File directory,
        File filesRoot,
        List<String> result
    ) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                result.add(relativePath(filesRoot, file));
            }
        }
    }

    private static void addIfFile(
        File filesRoot,
        String path,
        List<String> result
    ) {
        if (resolveAllowedPath(filesRoot, path).isFile()) {
            result.add(path);
        }
    }

    private static String relativePath(File root, File file) {
        try {
            return root.getCanonicalFile().toPath()
                .relativize(file.getCanonicalFile().toPath())
                .toString()
                .replace(File.separatorChar, '/');
        } catch (IOException e) {
            return file.getAbsolutePath()
                .substring(root.getAbsolutePath().length() + 1)
                .replace(File.separatorChar, '/');
        }
    }

    private static File resolveAllowedPath(File filesRoot, String path)
        throws IllegalArgumentException {
        if (path == null || path.contains("\\") || path.contains("..")) {
            throw new IllegalArgumentException("invalid Review journal path");
        }
        boolean allowed = path.equals(
                SceneContextStore.DIRECTORY_NAME + "/index.json"
            )
            || path.matches(
                SceneContextStore.DIRECTORY_NAME
                    + "/(contexts|groups)/[A-Za-z0-9._-]+\\.json"
            )
            || path.matches(
                TranslationJobStore.DIRECTORY_NAME
                    + "/[A-Za-z0-9_-]+/state\\.json"
            )
            || path.matches(
                SummaryJobStore.DIRECTORY_NAME
                    + "/[A-Za-z0-9_-]+/(request|state)\\.json"
            );
        if (!allowed) {
            throw new IllegalArgumentException(
                "path outside Review allow-list: " + path
            );
        }
        File candidate = new File(
            filesRoot,
            path.replace('/', File.separatorChar)
        );
        try {
            String rootPath = filesRoot.getCanonicalPath();
            String candidatePath = candidate.getCanonicalPath();
            if (!candidatePath.equals(rootPath)
                && !candidatePath.startsWith(rootPath + File.separator)) {
                throw new IllegalArgumentException(
                    "Review journal path escapes files root"
                );
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "could not resolve Review journal path",
                e
            );
        }
        return candidate;
    }

    private static File resolveAllowedDirectory(File filesRoot, String path) {
        if (path == null || !path.matches(
            "(translation_jobs|summary_jobs)/[A-Za-z0-9_-]+"
        )) {
            throw new IllegalArgumentException(
                "path outside Review directory allow-list: " + path
            );
        }
        return resolveAllowedPath(
            filesRoot,
            path + "/state.json"
        ).getParentFile();
    }

    /**
     * Removes a Job directory that did not exist at the Review boundary.
     * Translation request bytes are intentionally not snapshotted because a
     * legal request may be much larger than the Review journal limit. Since
     * the whole directory is new and admissions share the root lock, every
     * regular file in it belongs to this uncommitted transaction.
     */
    private static void deleteReviewCreatedJobDirectory(File directory)
        throws IOException {
        File[] children = directory.listFiles();
        if (children == null && directory.isDirectory()) {
            throw new IOException(
                "could not enumerate new Review job directory "
                    + directory.getName()
            );
        }
        if (children != null) {
            for (File child : children) {
                if (!child.isFile() || !child.delete()) {
                    throw new IOException(
                        "could not remove new Review job file "
                            + child.getName()
                    );
                }
            }
        }
        if (!directory.exists()) {
            return;
        }
        if (directory.isDirectory() && !directory.delete()) {
            throw new IOException(
                "could not remove Review job directory "
                    + directory.getName()
            );
        }
    }

    private static byte[] readBytes(File file, int maxBytes) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return IoUtils.readAllBytesLimited(input, maxBytes);
        }
    }
}
