package com.quarty.housamoembedtrans.scene.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Private transaction-slot kernel shared by the two Scene stores.
 *
 * <p>The kernel owns only filesystem protocol invariants: exact slot-name
 * identity, residue enumeration, recursive cleanup and strict byte writes.
 * Store adapters retain their state schema and map {@link SlotFailure} to
 * their own typed domain failures.</p>
 */
final class TransactionalSceneSlots {
    enum FailureKind {
        INVALID_STATE,
        IO
    }

    static final class SlotFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final FailureKind kind;

        SlotFailure(FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        SlotFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    @FunctionalInterface
    interface StateNameReader {
        String read(File stateFile) throws SlotFailure;
    }

    @FunctionalInterface
    interface FailureFactory {
        IOException create(String message, Throwable cause);
    }

    @FunctionalInterface
    interface SlotValidator {
        void validate(File directory, String sceneName) throws IOException;
    }

    @FunctionalInterface
    interface IncomingWriter {
        void write(File incomingDirectory) throws IOException;
    }

    @FunctionalInterface
    interface TransientFailureClassifier {
        boolean isTransient(IOException failure);
    }

    /**
     * Package-private filesystem seam used by host fixtures to make write,
     * rename and cleanup failures deterministic.  Stores keep their public
     * APIs unchanged; production calls use the adapter below by default.
     */
    interface FileOps {
        boolean exists(File file);

        boolean isDirectory(File file);

        boolean isFile(File file);

        File[] listFiles(File file);

        boolean mkdir(File file);

        boolean mkdirs(File file);

        boolean rename(File source, File target);

        boolean delete(File file);

        FileOutput openOutput(File file) throws IOException;
    }

    interface FileOutput extends AutoCloseable {
        void write(byte[] bytes) throws IOException;

        void flush() throws IOException;

        void sync() throws IOException;

        @Override
        void close() throws IOException;
    }

    /** The real filesystem adapter used by Android stores. */
    static final class ProductionFileOps implements FileOps {
        static final ProductionFileOps INSTANCE = new ProductionFileOps();

        private ProductionFileOps() {}

        @Override
        public boolean exists(File file) {
            return file.exists();
        }

        @Override
        public boolean isDirectory(File file) {
            return file.isDirectory();
        }

        @Override
        public boolean isFile(File file) {
            return file.isFile();
        }

        @Override
        public File[] listFiles(File file) {
            return file.listFiles();
        }

        @Override
        public boolean mkdir(File file) {
            return file.mkdir();
        }

        @Override
        public boolean mkdirs(File file) {
            return file.mkdirs();
        }

        @Override
        public boolean rename(File source, File target) {
            return source.renameTo(target);
        }

        @Override
        public boolean delete(File file) {
            return file.delete();
        }

        @Override
        public FileOutput openOutput(File file) throws IOException {
            return new ProductionFileOutput(file);
        }
    }

    private static final class ProductionFileOutput implements FileOutput {
        private final FileOutputStream output;

        private ProductionFileOutput(File file) throws IOException {
            output = new FileOutputStream(file);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            output.write(bytes);
        }

        @Override
        public void flush() throws IOException {
            output.flush();
        }

        @Override
        public void sync() throws IOException {
            output.getFD().sync();
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    private TransactionalSceneSlots() {}

    /** Publishes a new formal slot, preserving a concurrent formal winner. */
    static boolean publishNew(
        File root,
        String sceneName,
        String incomingPrefix,
        FailureFactory failureFactory,
        IncomingWriter writer
    ) throws IOException {
        return publishNew(
            root,
            sceneName,
            incomingPrefix,
            failureFactory,
            ProductionFileOps.INSTANCE,
            writer
        );
    }

    static boolean publishNew(
        File root,
        String sceneName,
        String incomingPrefix,
        FailureFactory failureFactory,
        FileOps fileOps,
        IncomingWriter writer
    ) throws IOException {
        File incoming = new File(root, incomingPrefix + sceneName);
        File formal = new File(root, sceneName);
        if (fileOps.exists(incoming) || !fileOps.mkdir(incoming)) {
            throw failureFactory.create(
                "could not create incoming slot",
                null
            );
        }
        try {
            writer.write(incoming);
            if (fileOps.exists(formal)) {
                cleanupOrThrow(
                    incoming,
                    "could not remove losing incoming slot",
                    failureFactory,
                    fileOps
                );
                return false;
            }
            if (!fileOps.rename(incoming, formal)) {
                if (fileOps.isDirectory(formal)) {
                    cleanupOrThrow(
                        incoming,
                        "could not remove losing incoming slot",
                        failureFactory,
                        fileOps
                    );
                    return false;
                }
                throw failureFactory.create(
                    "could not publish formal slot",
                    null
                );
            }
            return true;
        } catch (IOException e) {
            attachSecondary(
                e,
                incoming,
                failureFactory,
                "could not remove incomplete incoming slot",
                fileOps
            );
            throw e;
        } catch (RuntimeException e) {
            attachSecondary(
                e,
                incoming,
                failureFactory,
                "could not remove incomplete incoming slot",
                fileOps
            );
            throw e;
        }
    }

    /** Publishes a replacement through formal -> backup -> formal order. */
    static void publishReplacement(
        File root,
        String sceneName,
        String incomingPrefix,
        String backupPrefix,
        FailureFactory failureFactory,
        IncomingWriter writer
    ) throws IOException {
        publishReplacement(
            root,
            sceneName,
            incomingPrefix,
            backupPrefix,
            failureFactory,
            ProductionFileOps.INSTANCE,
            writer
        );
    }

    static void publishReplacement(
        File root,
        String sceneName,
        String incomingPrefix,
        String backupPrefix,
        FailureFactory failureFactory,
        FileOps fileOps,
        IncomingWriter writer
    ) throws IOException {
        File incoming = new File(root, incomingPrefix + sceneName);
        File formal = new File(root, sceneName);
        File backup = new File(root, backupPrefix + sceneName);
        if (fileOps.exists(incoming) || !fileOps.mkdir(incoming)) {
            throw failureFactory.create(
                "could not create incoming slot",
                null
            );
        }
        boolean oldMoved = false;
        try {
            writer.write(incoming);
            if (fileOps.exists(formal)) {
                if (fileOps.exists(backup)) {
                    throw failureFactory.create(
                        "fixed backup slot is not empty",
                        null
                    );
                }
                if (!fileOps.rename(formal, backup)) {
                    throw failureFactory.create(
                        "could not stage formal slot",
                        null
                    );
                }
                oldMoved = true;
            }
            if (!fileOps.rename(incoming, formal)) {
                throw failureFactory.create(
                    "could not publish replacement slot",
                    null
                );
            }
            cleanupOrThrow(
                backup,
                "could not remove published backup slot",
                failureFactory,
                fileOps
            );
        } catch (IOException e) {
            restoreAndCleanup(
                e,
                formal,
                backup,
                incoming,
                oldMoved,
                failureFactory,
                fileOps
            );
            throw e;
        } catch (RuntimeException e) {
            restoreAndCleanup(
                e,
                formal,
                backup,
                incoming,
                oldMoved,
                failureFactory,
                fileOps
            );
            throw e;
        }
    }

    private static void restoreAndCleanup(
        Throwable primary,
        File formal,
        File backup,
        File incoming,
        boolean oldMoved,
        FailureFactory failureFactory,
        FileOps fileOps
    ) {
        try {
            if (oldMoved
                && !fileOps.exists(formal)
                && !fileOps.rename(backup, formal)) {
                attach(
                    primary,
                    failureFactory.create(
                        "could not restore previous formal slot",
                        null
                    )
                );
            }
        } catch (RuntimeException restoreFailure) {
            attach(
                primary,
                failureFactory.create(
                    "could not restore previous formal slot",
                    restoreFailure
                )
            );
        }
        attachSecondary(
            primary,
            incoming,
            failureFactory,
            "could not remove incomplete incoming slot",
            fileOps
        );
    }

    private static void attachSecondary(
        Throwable primary,
        File file,
        FailureFactory failureFactory,
        String message,
        FileOps fileOps
    ) {
        if (primary == null || file == null) {
            return;
        }
        try {
            if (fileOps.exists(file) && !deleteRecursively(file, fileOps)) {
                throw failureFactory.create(message, null);
            }
        } catch (IOException cleanup) {
            attach(primary, cleanup);
        } catch (RuntimeException cleanup) {
            attach(primary, failureFactory.create(message, cleanup));
        }
    }

    private static void attach(Throwable primary, Throwable secondary) {
        if (primary != null && secondary != null) {
            primary.addSuppressed(secondary);
        }
    }

    static List<File> slotDirectories(
        File root,
        String sceneName,
        String prefix,
        String stateFileName,
        StateNameReader stateReader
    ) throws SlotFailure {
        return slotDirectories(
            root,
            sceneName,
            prefix,
            stateFileName,
            stateReader,
            ProductionFileOps.INSTANCE
        );
    }

    static List<File> slotDirectories(
        File root,
        String sceneName,
        String prefix,
        String stateFileName,
        StateNameReader stateReader,
        FileOps fileOps
    ) throws SlotFailure {
        List<File> matches = new ArrayList<>();
        if (!fileOps.isDirectory(root)) {
            return matches;
        }
        File[] entries = fileOps.listFiles(root);
        if (entries == null) {
            throw new SlotFailure(FailureKind.IO, "could not enumerate slot root");
        }
        for (File entry : entries) {
            if (sceneName.equals(slotSceneName(
                entry,
                prefix,
                stateFileName,
                stateReader,
                fileOps
            ))) {
                matches.add(entry);
            }
        }
        return matches;
    }

    static List<File> backupDirectoriesForCleanup(
        File root,
        String sceneName,
        String backupPrefix,
        String stateFileName,
        StateNameReader stateReader
    ) throws SlotFailure {
        return slotDirectories(
            root,
            sceneName,
            backupPrefix,
            stateFileName,
            stateReader
        );
    }

    static List<File> backupDirectoriesForCleanup(
        File root,
        String sceneName,
        String backupPrefix,
        String stateFileName,
        StateNameReader stateReader,
        FileOps fileOps
    ) throws SlotFailure {
        return slotDirectories(
            root,
            sceneName,
            backupPrefix,
            stateFileName,
            stateReader,
            fileOps
        );
    }

    /**
     * Resolves a slot only when its complete suffix is the Scene identity.
     * Existing state is authoritative and invalid state is never treated as
     * absent state.
     */
    static String slotSceneName(
        File entry,
        String prefix,
        String stateFileName,
        StateNameReader stateReader
    ) throws SlotFailure {
        return slotSceneName(
            entry,
            prefix,
            stateFileName,
            stateReader,
            ProductionFileOps.INSTANCE
        );
    }

    static String slotSceneName(
        File entry,
        String prefix,
        String stateFileName,
        StateNameReader stateReader,
        FileOps fileOps
    ) throws SlotFailure {
        if (entry == null || !entry.getName().startsWith(prefix)) {
            return null;
        }
        String suffix = entry.getName().substring(prefix.length());
        File stateFile = new File(entry, stateFileName);
        if (fileOps.exists(stateFile)) {
            if (!fileOps.isFile(stateFile)) {
                throw new SlotFailure(
                    FailureKind.INVALID_STATE,
                    "slot state path is not a regular file"
                );
            }
            if (stateReader == null) {
                throw new SlotFailure(
                    FailureKind.INVALID_STATE,
                    "slot state reader is required"
                );
            }
            String stateName = stateReader.read(stateFile);
            if (!SceneStore.isValidSceneName(suffix)
                || !suffix.equals(stateName)) {
                throw new SlotFailure(
                    FailureKind.INVALID_STATE,
                    "slot state SceneName does not match its complete suffix"
                );
            }
            return suffix;
        }
        return SceneStore.isValidSceneName(suffix) ? suffix : null;
    }

    static void cleanupAllIncoming(
        File root,
        String incomingPrefix,
        FailureFactory failureFactory
    ) throws IOException {
        cleanupAllIncoming(
            root,
            incomingPrefix,
            failureFactory,
            ProductionFileOps.INSTANCE
        );
    }

    static void cleanupAllIncoming(
        File root,
        String incomingPrefix,
        FailureFactory failureFactory,
        FileOps fileOps
    ) throws IOException {
        if (!fileOps.isDirectory(root)) {
            return;
        }
        File[] entries = fileOps.listFiles(root);
        if (entries == null) {
            throw failureFactory.create(
                "could not enumerate slot root",
                null
            );
        }
        for (File entry : entries) {
            if (entry.getName().startsWith(incomingPrefix)) {
                cleanupOrThrow(
                    entry,
                    "could not remove uncommitted incoming slot",
                    failureFactory,
                    fileOps
                );
            }
        }
    }

    /**
     * Normalizes one Scene's formal/incoming/backup slots.  The Store adapter
     * supplies schema validation and failure classification; this method owns
     * the winner-selection and rename order for both stores.
     */
    static boolean normalize(
        File root,
        String sceneName,
        String incomingPrefix,
        String backupPrefix,
        String stateFileName,
        StateNameReader stateReader,
        SlotValidator validator,
        TransientFailureClassifier transientFailure,
        FailureFactory failureFactory,
        boolean discardInvalidFormal
    ) throws IOException {
        return normalize(
            root,
            sceneName,
            incomingPrefix,
            backupPrefix,
            stateFileName,
            stateReader,
            validator,
            transientFailure,
            failureFactory,
            discardInvalidFormal,
            ProductionFileOps.INSTANCE
        );
    }

    static boolean normalize(
        File root,
        String sceneName,
        String incomingPrefix,
        String backupPrefix,
        String stateFileName,
        StateNameReader stateReader,
        SlotValidator validator,
        TransientFailureClassifier transientFailure,
        FailureFactory failureFactory,
        boolean discardInvalidFormal,
        FileOps fileOps
    ) throws IOException {
        if (!fileOps.isDirectory(root)) {
            if (fileOps.exists(root)) {
                throw failureFactory.create("slot root is not a directory", null);
            }
            if (!fileOps.mkdirs(root) && !fileOps.isDirectory(root)) {
                throw failureFactory.create("could not create slot root", null);
            }
        }
        File formal = new File(root, sceneName);
        boolean formalValid = false;
        IOException formalFailure = null;
        if (fileOps.exists(formal)) {
            try {
                validator.validate(formal, sceneName);
                formalValid = true;
            } catch (IOException e) {
                if (transientFailure.isTransient(e)) {
                    throw e;
                }
                formalFailure = e;
            }
        }

        cleanupAllIncoming(root, incomingPrefix, failureFactory, fileOps);

        if (formalValid) {
            for (File backup : backupDirectoriesForCleanup(
                root,
                sceneName,
                backupPrefix,
                stateFileName,
                stateReader,
                fileOps
            )) {
                cleanupOrThrow(
                    backup,
                    "could not remove stale backup slot",
                    failureFactory,
                    fileOps
                );
            }
            return false;
        }

        List<File> validBackups = new ArrayList<>();
        for (File backup : slotDirectories(
            root,
            sceneName,
            backupPrefix,
            stateFileName,
            stateReader,
            fileOps
        )) {
            try {
                validator.validate(backup, sceneName);
                validBackups.add(backup);
            } catch (IOException e) {
                if (transientFailure.isTransient(e)) {
                    throw e;
                }
                cleanupOrThrow(
                    backup,
                    "could not remove invalid backup slot",
                    failureFactory,
                    fileOps
                );
            }
        }
        if (validBackups.size() > 1) {
            throw new SlotFailure(
                FailureKind.IO,
                "multiple valid backups have no deterministic winner"
            );
        }
        if (validBackups.size() == 1) {
            if (fileOps.exists(formal)) {
                cleanupOrThrow(
                    formal,
                    "could not remove damaged formal slot",
                    failureFactory,
                    fileOps
                );
            }
            if (!fileOps.rename(validBackups.get(0), formal)) {
                throw failureFactory.create(
                    "could not restore backup slot",
                    null
                );
            }
            return false;
        }
        if (formalFailure != null) {
            if (discardInvalidFormal) {
                cleanupOrThrow(
                    formal,
                    "could not discard damaged formal slot",
                    failureFactory,
                    fileOps
                );
                return true;
            }
            throw formalFailure;
        }
        return false;
    }

    static void cleanupOrThrow(
        File file,
        String message,
        FailureFactory failureFactory
    ) throws IOException {
        cleanupOrThrow(
            file,
            message,
            failureFactory,
            ProductionFileOps.INSTANCE
        );
    }

    static void cleanupOrThrow(
        File file,
        String message,
        FailureFactory failureFactory,
        FileOps fileOps
    ) throws IOException {
        try {
            if (fileOps.exists(file) && !deleteRecursively(file, fileOps)) {
                throw failureFactory.create(message, null);
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw failureFactory.create(message, e);
        }
    }

    static void writeExact(
        File file,
        byte[] bytes,
        FailureFactory failureFactory
    ) throws IOException {
        writeExact(file, bytes, failureFactory, ProductionFileOps.INSTANCE);
    }

    static void writeExact(
        File file,
        byte[] bytes,
        FailureFactory failureFactory,
        FileOps fileOps
    ) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || !fileOps.isDirectory(parent)) {
            throw failureFactory.create("slot file parent is missing", null);
        }
        try (FileOutput output = fileOps.openOutput(file)) {
            output.write(bytes);
            output.flush();
            output.sync();
        } catch (IOException e) {
            throw failureFactory.create("could not write slot file", e);
        } catch (RuntimeException e) {
            throw failureFactory.create("could not write slot file", e);
        }
    }

    static boolean deleteRecursively(File file) {
        return deleteRecursively(file, ProductionFileOps.INSTANCE);
    }

    static boolean deleteRecursively(File file, FileOps fileOps) {
        if (file == null || !fileOps.exists(file)) {
            return true;
        }
        if (fileOps.isDirectory(file)) {
            File[] children = fileOps.listFiles(file);
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteRecursively(child, fileOps)) {
                    return false;
                }
            }
        }
        return fileOps.delete(file);
    }

    static String decodeStrictUtf8(byte[] bytes)
        throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

}
