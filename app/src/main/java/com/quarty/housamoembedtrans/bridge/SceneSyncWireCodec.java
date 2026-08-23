package com.quarty.housamoembedtrans.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared bounded codec for the Game Scene Port's HETS streams.
 *
 * <p>The class deliberately has no Android dependency so both sides of the
 * Binder connection can use exactly the same byte contract.  Scene bodies are
 * read only after their declared length has passed the hard limit check; the
 * decoder never allocates a complete record payload first.</p>
 */
public final class SceneSyncWireCodec {
    public static final int WIRE_VERSION = 1;
    public static final int STREAM_EXPORT = 1;
    public static final int STREAM_APPLY_REQUEST = 2;
    public static final int STREAM_APPLY_RESULT = 3;

    public static final int MAX_SCENE_NAME_BYTES = 235;
    public static final int MAX_SCENE_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SCENES = 65_536;

    public static final int EXPORT_READ_FAILED = 1;
    public static final int EXPORT_EMPTY_FILE = 2;
    public static final int EXPORT_FILE_TOO_LARGE = 3;
    public static final int EXPORT_INVALID_UTF8 = 4;
    public static final int EXPORT_INVALID_JSON = 5;
    public static final int EXPORT_SCHEMA_INVALID = 6;
    public static final int EXPORT_SCENE_IDENTITY_MISMATCH = 7;
    public static final int EXPORT_INTERNAL_VALIDATION_FAILURE = 8;

    public static final int APPLY_NONE = 0;
    public static final int APPLY_REQUEST_STREAM_FAILED = 1;
    public static final int APPLY_REQUEST_PROTOCOL_INVALID = 2;
    public static final int APPLY_WRITE_FAILED = 3;
    public static final int APPLY_DELETE_FAILED = 4;
    public static final int APPLY_POLICY_UPDATE_FAILED = 5;
    public static final int APPLY_OPERATION_CANCELED = 6;
    public static final int APPLY_INTERNAL_FAILURE = 7;
    /** The intent is durable in the Scene mutation pool, not yet formal. */
    public static final int APPLY_DEFERRED = 8;

    private static final byte[] MAGIC = new byte[] {'H', 'E', 'T', 'S'};
    private static final long MAX_RECORD_PAYLOAD =
        (long) MAX_SCENE_BYTES + MAX_SCENE_NAME_BYTES + 6L;

    private SceneSyncWireCodec() {}

    public enum RecordType {
        SCENE(1),
        REJECTED(2),
        WRITE_SCENE(16),
        DELETE_SCENE(17),
        REPLACE_BLOCKED_SCENES(18),
        APPLY_RESULT(32),
        END(255);

        public final int wireValue;

        RecordType(int wireValue) {
            this.wireValue = wireValue;
        }

        private static RecordType fromWireValue(int value) {
            for (RecordType type : values()) {
                if (type.wireValue == value) {
                    return type;
                }
            }
            return null;
        }
    }

    public static final class ExportRecord {
        public final RecordType type;
        public final String sceneName;
        public final byte[] sceneBytes;
        public final int errorCode;

        private ExportRecord(
            RecordType type,
            String sceneName,
            byte[] sceneBytes,
            int errorCode
        ) {
            this.type = type;
            this.sceneName = sceneName;
            this.sceneBytes = sceneBytes;
            this.errorCode = errorCode;
        }
    }

    public static final class ExportStream {
        public final List<ExportRecord> records;

        private ExportStream(List<ExportRecord> records) {
            this.records = Collections.unmodifiableList(records);
        }
    }

    public static final class ApplyCommand {
        public final RecordType type;
        public final String sceneName;
        public final byte[] sceneBytes;
        public final List<String> blockedScenes;

        private ApplyCommand(
            RecordType type,
            String sceneName,
            byte[] sceneBytes,
            List<String> blockedScenes
        ) {
            this.type = type;
            this.sceneName = sceneName;
            this.sceneBytes = sceneBytes;
            this.blockedScenes = blockedScenes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(blockedScenes);
        }
    }

    public static final class ApplyRequest {
        public final ApplyCommand command;

        private ApplyRequest(ApplyCommand command) {
            this.command = command;
        }
    }

    public static final class ApplyResult {
        public final boolean success;
        public final int errorCode;

        public ApplyResult(boolean success, int errorCode) {
            this.success = success;
            this.errorCode = errorCode;
        }
    }

    /**
     * Receives a WRITE_SCENE body as a bounded stream.  The callback must
     * consume the stream to EOF; the codec verifies the exact declared length
     * and strict UTF-8 while the bytes are being consumed.
     */
    @FunctionalInterface
    public interface SceneBodyConsumer {
        void accept(String sceneName, int bodyLength, InputStream body)
            throws IOException;
    }

    /**
     * Streaming export visitor.  A scene body is available only for the
     * duration of the callback and is already bounded to its declared length.
     */
    public interface ExportRecordConsumer {
        void onScene(String sceneName, int bodyLength, InputStream body)
            throws IOException;

        void onRejected(String sceneName, int errorCode) throws IOException;
    }

    /** Checked protocol failure with direction, record and offset diagnostics. */
    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static ExportRecord scene(String sceneName, byte[] sceneBytes) {
        return new ExportRecord(RecordType.SCENE, sceneName, sceneBytes, 0);
    }

    public static ExportRecord rejected(String sceneName, int errorCode) {
        return new ExportRecord(RecordType.REJECTED, sceneName, null, errorCode);
    }

    public static ApplyCommand writeScene(String sceneName, byte[] sceneBytes) {
        return new ApplyCommand(RecordType.WRITE_SCENE, sceneName, sceneBytes, null);
    }

    public static ApplyCommand deleteScene(String sceneName) {
        return new ApplyCommand(RecordType.DELETE_SCENE, sceneName, null, null);
    }

    public static ApplyCommand replaceBlockedScenes(Collection<String> sceneNames) {
        return new ApplyCommand(
            RecordType.REPLACE_BLOCKED_SCENES,
            null,
            null,
            sceneNames == null ? null : new ArrayList<>(sceneNames)
        );
    }

    public static byte[] encodeExport(List<ExportRecord> records)
        throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writeExport(output, records);
        } catch (IOException e) {
            if (e instanceof ProtocolException) {
                throw (ProtocolException) e;
            }
            throw new ProtocolException("could not encode export stream", e);
        }
        return output.toByteArray();
    }

    public static void writeExport(
        OutputStream output,
        Iterable<ExportRecord> records
    ) throws IOException {
        if (output == null || records == null) {
            throw new ProtocolException(
                "direction=game_to_het/export record=header type=unknown "
                    + "scene=unknown offset=0: output and records are required"
            );
        }
        writeHeader(output, STREAM_EXPORT);
        Set<String> names = new HashSet<>();
        int index = 0;
        for (ExportRecord record : records) {
            if (index >= MAX_SCENES) {
                throw protocol(
                    "game_to_het/export", index, "unknown", "unknown", -1,
                    "record count exceeds " + MAX_SCENES
                );
            }
            if (record == null || record.type == null) {
                throw protocol(
                    "game_to_het/export", index, "unknown", "unknown", -1,
                    "null export record"
                );
            }
            if (!names.add(requireSceneName(
                record.sceneName,
                "game_to_het/export",
                index,
                record.type.name()
            ))) {
                throw protocol(
                    "game_to_het/export", index, record.type.name(),
                    record.sceneName, -1, "duplicate SceneName"
                );
            }
            switch (record.type) {
                case SCENE:
                    writeSceneRecord(
                        output,
                        RecordType.SCENE,
                        record.sceneName,
                        record.sceneBytes,
                        "game_to_het/export",
                        index
                    );
                    break;
                case REJECTED:
                    validateExportError(record.errorCode,
                        "game_to_het/export", index, record.sceneName);
                    writeRecordHeader(output, RecordType.REJECTED, 2L
                        + encodedNameLength(record.sceneName));
                    writeName(output, record.sceneName);
                    writeU16(output, record.errorCode);
                    break;
                default:
                    throw protocol(
                        "game_to_het/export", index, record.type.name(),
                        record.sceneName, -1,
                        "record is not allowed on EXPORT stream"
                    );
            }
            index++;
        }
        writeRecordHeader(output, RecordType.END, 0L);
    }

    /**
     * Incremental export writer.  The caller supplies one already-validated
     * Scene body at a time; the writer owns only the fixed copy buffer and the
     * set of emitted names, never a second copy of the whole export.
     */
    public static StreamingExportWriter beginStreamingExport(
        OutputStream output
    ) throws IOException {
        return new StreamingExportWriter(output);
    }

    public static final class StreamingExportWriter implements AutoCloseable {
        private final OutputStream output;
        private final Set<String> names = new HashSet<>();
        private int recordIndex;
        private boolean finished;

        private StreamingExportWriter(OutputStream output) throws IOException {
            if (output == null) {
                throw new ProtocolException(
                    "direction=game_to_het/export record=header type=unknown "
                        + "scene=unknown offset=0: output is required"
                );
            }
            this.output = output;
            writeHeader(output, STREAM_EXPORT);
        }

        public void writeScene(
            String sceneName,
            InputStream body,
            int bodyLength
        ) throws IOException {
            ensureOpen();
            String validName = registerName(sceneName, RecordType.SCENE);
            if (body == null || bodyLength < 1 || bodyLength > MAX_SCENE_BYTES) {
                throw protocol(
                    "game_to_het/export",
                    recordIndex,
                    RecordType.SCENE.name(),
                    validName,
                    -1,
                    "Scene body length must be 1.."
                        + MAX_SCENE_BYTES
                        + ", got "
                        + bodyLength
                );
            }

            writeRecordHeader(
                output,
                RecordType.SCENE,
                encodedNameLength(validName) + 4L + bodyLength
            );
            writeName(output, validName);
            writeU32(output, bodyLength);
            Utf8StreamValidator validator = new Utf8StreamValidator(
                "game_to_het/export",
                recordIndex,
                RecordType.SCENE.name(),
                validName
            );
            byte[] buffer = new byte[8192];
            int remaining = bodyLength;
            while (remaining > 0) {
                int read = body.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw protocol(
                        "game_to_het/export",
                        recordIndex,
                        RecordType.SCENE.name(),
                        validName,
                        -1,
                        "early EOF while reading Scene body"
                    );
                }
                if (read == 0) {
                    continue;
                }
                validator.accept(buffer, read);
                output.write(buffer, 0, read);
                remaining -= read;
            }
            validator.finish();
            recordIndex++;
        }

        public void writeRejected(String sceneName, int errorCode)
            throws IOException {
            ensureOpen();
            String validName = registerName(sceneName, RecordType.REJECTED);
            validateExportError(
                errorCode,
                "game_to_het/export",
                recordIndex,
                validName
            );
            writeRecordHeader(
                output,
                RecordType.REJECTED,
                encodedNameLength(validName) + 2L
            );
            writeName(output, validName);
            writeU16(output, errorCode);
            recordIndex++;
        }

        public void finish() throws IOException {
            ensureOpen();
            writeRecordHeader(output, RecordType.END, 0L);
            finished = true;
        }

        @Override
        public void close() {
            // The owner of the PFD closes the underlying stream.  Deliberately
            // do not synthesize END after a failed write.
            finished = true;
        }

        private String registerName(String sceneName, RecordType type)
            throws ProtocolException {
            if (recordIndex >= MAX_SCENES) {
                throw protocol(
                    "game_to_het/export",
                    recordIndex,
                    type.name(),
                    sceneName,
                    -1,
                    "record count exceeds " + MAX_SCENES
                );
            }
            String validName = requireSceneName(
                sceneName,
                "game_to_het/export",
                recordIndex,
                type.name()
            );
            if (!names.add(validName)) {
                throw protocol(
                    "game_to_het/export",
                    recordIndex,
                    type.name(),
                    validName,
                    -1,
                    "duplicate SceneName"
                );
            }
            return validName;
        }

        private void ensureOpen() throws ProtocolException {
            if (finished) {
                throw protocol(
                    "game_to_het/export",
                    recordIndex,
                    "unknown",
                    "unknown",
                    -1,
                    "export writer is already finished"
                );
            }
        }
    }

    public static byte[] encodeWriteScene(String sceneName, byte[] sceneBytes)
        throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writeWriteScene(output, sceneName, sceneBytes);
        } catch (IOException e) {
            if (e instanceof ProtocolException) {
                throw (ProtocolException) e;
            }
            throw new ProtocolException("could not encode WRITE_SCENE", e);
        }
        return output.toByteArray();
    }

    /** Streaming-friendly WRITE_SCENE encoder; does not build a payload copy. */
    public static void writeWriteScene(
        OutputStream output,
        String sceneName,
        byte[] sceneBytes
    ) throws IOException {
        writeApply(output, writeScene(sceneName, sceneBytes));
    }

    public static byte[] encodeDeleteScene(String sceneName)
        throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writeDeleteScene(output, sceneName);
        } catch (IOException e) {
            if (e instanceof ProtocolException) {
                throw (ProtocolException) e;
            }
            throw new ProtocolException("could not encode DELETE_SCENE", e);
        }
        return output.toByteArray();
    }

    public static void writeDeleteScene(OutputStream output, String sceneName)
        throws IOException {
        writeApply(output, deleteScene(sceneName));
    }

    public static byte[] encodeReplaceBlockedScenes(Collection<String> sceneNames)
        throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writeReplaceBlockedScenes(output, sceneNames);
        } catch (IOException e) {
            if (e instanceof ProtocolException) {
                throw (ProtocolException) e;
            }
            throw new ProtocolException(
                "could not encode REPLACE_BLOCKED_SCENES",
                e
            );
        }
        return output.toByteArray();
    }

    public static void writeReplaceBlockedScenes(
        OutputStream output,
        Collection<String> sceneNames
    ) throws IOException {
        writeApply(output, replaceBlockedScenes(sceneNames));
    }

    public static byte[] encodeApply(ApplyCommand command)
        throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            writeApply(output, command);
        } catch (IOException e) {
            if (e instanceof ProtocolException) {
                throw (ProtocolException) e;
            }
            throw new ProtocolException("could not encode apply request", e);
        }
        return output.toByteArray();
    }

    public static void writeApply(OutputStream output, ApplyCommand command)
        throws IOException {
        if (output == null || command == null || command.type == null) {
            throw new ProtocolException(
                "direction=het_to_game/apply_request record=0 type=unknown "
                    + "scene=unknown offset=0: command is required"
            );
        }
        writeHeader(output, STREAM_APPLY_REQUEST);
        switch (command.type) {
            case WRITE_SCENE:
                writeSceneRecord(
                    output,
                    RecordType.WRITE_SCENE,
                    command.sceneName,
                    command.sceneBytes,
                    "het_to_game/apply_request",
                    0
                );
                break;
            case DELETE_SCENE:
                requireSceneName(
                    command.sceneName,
                    "het_to_game/apply_request",
                    0,
                    command.type.name()
                );
                writeRecordHeader(output, RecordType.DELETE_SCENE,
                    encodedNameLength(command.sceneName));
                writeName(output, command.sceneName);
                break;
            case REPLACE_BLOCKED_SCENES:
                writeReplaceBlockedScenes(output, command.blockedScenes,
                    "het_to_game/apply_request", 0);
                break;
            default:
                throw protocol(
                    "het_to_game/apply_request", 0, command.type.name(),
                    command.sceneName, -1,
                    "record is not allowed on APPLY_REQUEST stream"
                );
        }
        writeRecordHeader(output, RecordType.END, 0L);
    }

    public static byte[] encodeApplyResult(boolean success, int errorCode)
        throws ProtocolException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8);
        try {
            writeApplyResult(output, success, errorCode);
        } catch (IOException e) {
            if (e instanceof ProtocolException) {
                throw (ProtocolException) e;
            }
            throw new ProtocolException("could not encode apply result", e);
        }
        return output.toByteArray();
    }

    public static void writeApplyResult(
        OutputStream output,
        boolean success,
        int errorCode
    ) throws IOException {
        if (output == null) {
            throw protocol(
                "game_to_het/apply_result", 0, "APPLY_RESULT", "unknown", 0,
                "output is required"
            );
        }
        validateApplyResult(success, errorCode,
            "game_to_het/apply_result", 0, -1);
        writeHeader(output, STREAM_APPLY_RESULT);
        writeRecordHeader(output, RecordType.APPLY_RESULT, 3L);
        output.write(success ? 1 : 0);
        writeU16(output, errorCode);
        writeRecordHeader(output, RecordType.END, 0L);
    }

    public static ExportStream decodeExport(InputStream input)
        throws IOException {
        final List<ExportRecord> records = new ArrayList<>();
        decodeExport(input, new ExportRecordConsumer() {
            @Override
            public void onScene(
                String sceneName,
                int bodyLength,
                InputStream body
            ) throws IOException {
                records.add(new ExportRecord(
                    RecordType.SCENE,
                    sceneName,
                    readBodyBytes(body, bodyLength),
                    0
                ));
            }

            @Override
            public void onRejected(String sceneName, int errorCode) {
                records.add(new ExportRecord(
                    RecordType.REJECTED,
                    sceneName,
                    null,
                    errorCode
                ));
            }
        });
        return new ExportStream(records);
    }

    /** Decodes an EXPORT stream without retaining a batch of Scene bodies. */
    public static void decodeExport(
        InputStream input,
        ExportRecordConsumer consumer
    ) throws IOException {
        if (consumer == null) {
            throw new ProtocolException(
                "direction=game_to_het/export record=header type=unknown "
                    + "scene=unknown offset=0: consumer is required"
            );
        }
        WireReader reader = new WireReader(input, "game_to_het/export");
        reader.readHeader(STREAM_EXPORT);
        Set<String> names = new HashSet<>();
        boolean ended = false;
        int index = 0;
        while (!ended) {
            int rawType = reader.readUnsignedByte(index, "unknown", "unknown");
            long payloadLength = reader.readUnsignedInt(index, rawTypeName(rawType),
                "unknown");
            RecordType type = RecordType.fromWireValue(rawType);
            if (type == null) {
                throw reader.protocol(index, "unknown", "unknown",
                    "unknown record_type=" + rawType);
            }
            if (payloadLength > MAX_RECORD_PAYLOAD) {
                throw reader.protocol(index, type.name(), "unknown",
                    "declared payload_length=" + payloadLength
                        + " exceeds " + MAX_RECORD_PAYLOAD);
            }
            if (type == RecordType.END) {
                if (payloadLength != 0L) {
                    throw reader.protocol(index, type.name(), "unknown",
                        "END payload_length must be 0, got " + payloadLength);
                }
                ended = true;
                index++;
                break;
            }
            if (type != RecordType.SCENE && type != RecordType.REJECTED) {
                throw reader.protocol(index, type.name(), "unknown",
                    "record is not allowed on EXPORT stream");
            }
            if (index >= MAX_SCENES) {
                throw reader.protocol(index, type.name(), "unknown",
                    "record count exceeds " + MAX_SCENES);
            }
            PayloadReader payload = reader.payload(payloadLength, index, type.name());
            String sceneName = payload.readName();
            if (!names.add(sceneName)) {
                throw payload.protocol(sceneName, "duplicate SceneName");
            }
            if (type == RecordType.SCENE) {
                payload.streamSceneBody(sceneName, consumer::onScene);
                payload.finish(sceneName);
            } else {
                int errorCode = payload.readUnsignedShort(sceneName);
                validateExportError(errorCode, reader.direction, index, sceneName);
                payload.finish(sceneName);
                consumer.onRejected(sceneName, errorCode);
            }
            index++;
        }
        if (!ended) {
            throw reader.protocol(index, "unknown", "unknown", "missing END");
        }
        reader.ensureEof(index, "END", "unknown");
    }

    private static byte[] readBodyBytes(InputStream body, int bodyLength)
        throws IOException {
        byte[] bytes = new byte[bodyLength];
        int position = 0;
        while (position < bodyLength) {
            int read = body.read(bytes, position, bodyLength - position);
            if (read < 0) {
                throw new ProtocolException(
                    "streamed Scene body ended before declared length "
                        + bodyLength
                );
            }
            if (read == 0) {
                continue;
            }
            position += read;
        }
        if (body.read() != -1) {
            throw new ProtocolException(
                "streamed Scene body exceeded declared length " + bodyLength
            );
        }
        return bytes;
    }

    public static ExportStream decodeExport(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new ProtocolException("direction=game_to_het/export record=header "
                + "type=unknown scene=unknown offset=0: null input");
        }
        return decodeExport(new ByteArrayInputStream(bytes));
    }

    public static ApplyRequest decodeApplyRequest(InputStream input)
        throws IOException {
        return decodeApplyRequest(input, null);
    }

    /**
     * Decodes an APPLY_REQUEST while optionally streaming WRITE_SCENE bodies.
     * Passing a consumer keeps the body out of the returned command and out of
     * the game process heap; passing null retains the byte[] convenience path.
     */
    public static ApplyRequest decodeApplyRequest(
        InputStream input,
        SceneBodyConsumer bodyConsumer
    ) throws IOException {
        WireReader reader = new WireReader(input, "het_to_game/apply_request");
        reader.readHeader(STREAM_APPLY_REQUEST);
        int index = 0;
        int rawType = reader.readUnsignedByte(index, "unknown", "unknown");
        long payloadLength = reader.readUnsignedInt(index, rawTypeName(rawType),
            "unknown");
        RecordType type = RecordType.fromWireValue(rawType);
        if (type == null) {
            throw reader.protocol(index, "unknown", "unknown",
                "unknown record_type=" + rawType);
        }
        if (type == RecordType.END) {
            throw reader.protocol(index, type.name(), "unknown",
                "APPLY_REQUEST requires exactly one command before END");
        }
        if (payloadLength > MAX_RECORD_PAYLOAD) {
            throw reader.protocol(index, type.name(), "unknown",
                "declared payload_length=" + payloadLength
                    + " exceeds " + MAX_RECORD_PAYLOAD);
        }
        if (type != RecordType.WRITE_SCENE
            && type != RecordType.DELETE_SCENE
            && type != RecordType.REPLACE_BLOCKED_SCENES) {
            throw reader.protocol(index, type.name(), "unknown",
                "record is not allowed on APPLY_REQUEST stream");
        }
        ApplyCommand command;
        PayloadReader payload = reader.payload(payloadLength, index, type.name());
        if (type == RecordType.WRITE_SCENE) {
            String sceneName = payload.readName();
            byte[] bytes = bodyConsumer == null
                ? payload.readSceneBody(sceneName)
                : payload.streamSceneBody(sceneName, bodyConsumer);
            payload.finish(sceneName);
            command = writeScene(sceneName, bytes);
        } else if (type == RecordType.DELETE_SCENE) {
            String sceneName = payload.readName();
            payload.finish(sceneName);
            command = deleteScene(sceneName);
        } else {
            List<String> names = payload.readBlockedScenes();
            payload.finish("unknown");
            command = replaceBlockedScenes(names);
        }

        index++;
        int endType = reader.readUnsignedByte(index, "unknown", "unknown");
        long endLength = reader.readUnsignedInt(index, rawTypeName(endType), "unknown");
        RecordType end = RecordType.fromWireValue(endType);
        if (end != RecordType.END || endLength != 0L) {
            throw reader.protocol(index, end == null ? "unknown" : end.name(),
                "unknown", end == RecordType.END
                    ? "END payload_length must be 0, got " + endLength
                    : "APPLY_REQUEST must contain exactly one command and END");
        }
        reader.ensureEof(index + 1, "END", "unknown");
        return new ApplyRequest(command);
    }

    public static ApplyRequest decodeApplyRequest(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new ProtocolException("direction=het_to_game/apply_request record=header "
                + "type=unknown scene=unknown offset=0: null input");
        }
        return decodeApplyRequest(new ByteArrayInputStream(bytes));
    }

    public static ApplyResult decodeApplyResult(InputStream input)
        throws IOException {
        WireReader reader = new WireReader(input, "game_to_het/apply_result");
        reader.readHeader(STREAM_APPLY_RESULT);
        int index = 0;
        int rawType = reader.readUnsignedByte(index, "unknown", "unknown");
        long payloadLength = reader.readUnsignedInt(index, rawTypeName(rawType),
            "unknown");
        RecordType type = RecordType.fromWireValue(rawType);
        if (type != RecordType.APPLY_RESULT) {
            throw reader.protocol(index, type == null ? "unknown" : type.name(),
                "unknown", type == null
                    ? "unknown record_type=" + rawType
                    : "only APPLY_RESULT is allowed on APPLY_RESULT stream");
        }
        if (payloadLength != 3L) {
            throw reader.protocol(index, type.name(), "unknown",
                "APPLY_RESULT payload_length must be 3, got " + payloadLength);
        }
        PayloadReader payload = reader.payload(payloadLength, index, type.name());
        int successValue = payload.readUnsignedByte("unknown");
        int errorCode = payload.readUnsignedShort("unknown");
        payload.finish("unknown");
        if (successValue != 0 && successValue != 1) {
            throw reader.protocol(index, type.name(), "unknown",
                "success must be 0 or 1, got " + successValue);
        }
        validateApplyResult(successValue == 1, errorCode,
            reader.direction, index, -1);

        index++;
        int endType = reader.readUnsignedByte(index, "unknown", "unknown");
        long endLength = reader.readUnsignedInt(index, rawTypeName(endType), "unknown");
        RecordType end = RecordType.fromWireValue(endType);
        if (end != RecordType.END || endLength != 0L) {
            throw reader.protocol(index, end == null ? "unknown" : end.name(),
                "unknown", end == RecordType.END
                    ? "END payload_length must be 0, got " + endLength
                    : "APPLY_RESULT must be followed by END");
        }
        reader.ensureEof(index + 1, "END", "unknown");
        return new ApplyResult(successValue == 1, errorCode);
    }

    public static ApplyResult decodeApplyResult(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new ProtocolException("direction=game_to_het/apply_result record=header "
                + "type=unknown scene=unknown offset=0: null input");
        }
        return decodeApplyResult(new ByteArrayInputStream(bytes));
    }

    private static void writeSceneRecord(
        OutputStream output,
        RecordType type,
        String sceneName,
        byte[] sceneBytes,
        String direction,
        int index
    ) throws IOException {
        String validName = requireSceneName(sceneName, direction, index, type.name());
        if (sceneBytes == null || sceneBytes.length < 1
            || sceneBytes.length > MAX_SCENE_BYTES) {
            throw protocol(direction, index, type.name(), validName, -1,
                "Scene body length must be 1.." + MAX_SCENE_BYTES + ", got "
                    + (sceneBytes == null ? "null" : sceneBytes.length));
        }
        validateUtf8(sceneBytes, direction, index, type.name(), validName,
            "Scene body");
        long payloadLength = encodedNameLength(validName) + 4L + sceneBytes.length;
        writeRecordHeader(output, type, payloadLength);
        writeName(output, validName);
        writeU32(output, sceneBytes.length);
        output.write(sceneBytes);
    }

    private static void writeReplaceBlockedScenes(
        OutputStream output,
        Collection<String> inputNames,
        String direction,
        int index
    ) throws IOException {
        if (inputNames == null || inputNames.size() > MAX_SCENES) {
            throw protocol(direction, index, RecordType.REPLACE_BLOCKED_SCENES.name(),
                "unknown", -1,
                "blocked SceneName count must be 0.." + MAX_SCENES + ", got "
                    + (inputNames == null ? "null" : inputNames.size()));
        }
        List<String> names = new ArrayList<>(inputNames.size());
        Set<String> unique = new HashSet<>();
        for (String name : inputNames) {
            String validName = requireSceneName(name, direction, index,
                RecordType.REPLACE_BLOCKED_SCENES.name());
            if (!unique.add(validName)) {
                throw protocol(direction, index,
                    RecordType.REPLACE_BLOCKED_SCENES.name(), validName, -1,
                    "duplicate SceneName");
            }
            names.add(validName);
        }
        Collections.sort(names);
        long payloadLength = 4L;
        for (String name : names) {
            payloadLength += encodedNameLength(name);
        }
        writeRecordHeader(output, RecordType.REPLACE_BLOCKED_SCENES, payloadLength);
        writeU32(output, names.size());
        for (String name : names) {
            writeName(output, name);
        }
    }

    private static String requireSceneName(
        String sceneName,
        String direction,
        int index,
        String type
    ) throws ProtocolException {
        if (sceneName == null || sceneName.isEmpty()) {
            throw protocol(direction, index, type, "unknown", -1,
                "SceneName is empty");
        }
        if (sceneName.endsWith(".json")) {
            throw protocol(direction, index, type, sceneName, -1,
                "wire SceneName must be bare and must not carry .json suffix");
        }
        byte[] bytes = sceneName.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_SCENE_NAME_BYTES) {
            throw protocol(direction, index, type, sceneName, -1,
                "SceneName length must be 1.." + MAX_SCENE_NAME_BYTES
                    + " bytes, got " + bytes.length);
        }
        for (int i = 0; i < sceneName.length(); i++) {
            char value = sceneName.charAt(i);
            boolean allowed = (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_' || value == '-';
            if (!allowed) {
                throw protocol(direction, index, type, sceneName, -1,
                    "SceneName is not ASCII-safe");
            }
        }
        return sceneName;
    }

    private static int encodedNameLength(String sceneName) {
        return 2 + sceneName.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void validateExportError(
        int errorCode,
        String direction,
        int index,
        String sceneName
    ) throws ProtocolException {
        if (errorCode < EXPORT_READ_FAILED
            || errorCode > EXPORT_INTERNAL_VALIDATION_FAILURE) {
            throw protocol(direction, index, RecordType.REJECTED.name(),
                sceneName, -1,
                "REJECTED error code must be 1..8, got " + errorCode);
        }
    }

    private static void validateApplyResult(
        boolean success,
        int errorCode,
        String direction,
        int index,
        long offset
    ) throws ProtocolException {
        boolean valid = success
            ? (errorCode == APPLY_NONE || errorCode == APPLY_DEFERRED)
            : errorCode >= APPLY_REQUEST_STREAM_FAILED
                && errorCode <= APPLY_INTERNAL_FAILURE;
        if (!valid) {
            throw protocol(direction, index, RecordType.APPLY_RESULT.name(),
                "unknown", offset,
                success
                    ? "success=1 requires error_code=0, got " + errorCode
                    : "success=0 requires error_code=1..7, got " + errorCode);
        }
    }

    private static void validateUtf8(
        byte[] bytes,
        String direction,
        int index,
        String type,
        String sceneName,
        String label
    ) throws ProtocolException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            throw protocol(direction, index, type, sceneName, -1,
                label + " is not strict UTF-8");
        }
    }

    private static void writeHeader(OutputStream output, int streamType)
        throws IOException {
        output.write(MAGIC);
        writeU16(output, WIRE_VERSION);
        writeU16(output, streamType);
    }

    private static void writeRecordHeader(
        OutputStream output,
        RecordType type,
        long payloadLength
    ) throws IOException {
        if (payloadLength < 0L || payloadLength > 0xffff_ffffL) {
            throw new ProtocolException(
                "record payload length is outside uint32: " + payloadLength
            );
        }
        output.write(type.wireValue);
        writeU32(output, payloadLength);
    }

    private static void writeName(OutputStream output, String sceneName)
        throws IOException {
        byte[] bytes = sceneName.getBytes(StandardCharsets.UTF_8);
        writeU16(output, bytes.length);
        output.write(bytes);
    }

    private static void writeU16(OutputStream output, int value)
        throws IOException {
        if (value < 0 || value > 0xffff) {
            throw new ProtocolException("value is outside uint16: " + value);
        }
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeU32(OutputStream output, long value)
        throws IOException {
        if (value < 0L || value > 0xffff_ffffL) {
            throw new ProtocolException("value is outside uint32: " + value);
        }
        output.write((int) ((value >>> 24) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) (value & 0xff));
    }

    private static String rawTypeName(int rawType) {
        RecordType type = RecordType.fromWireValue(rawType);
        return type == null ? "unknown" : type.name();
    }

    private static ProtocolException protocol(
        String direction,
        int index,
        String type,
        String sceneName,
        long offset,
        String reason
    ) {
        return new ProtocolException(
            "direction=" + direction
                + " record=" + index
                + " type=" + (type == null ? "unknown" : type)
                + " scene=" + (sceneName == null ? "unknown" : sceneName)
                + " offset=" + offset
                + ": " + reason
        );
    }

    private static final class WireReader {
        private final InputStream input;
        private final String direction;
        private long offset;

        private WireReader(InputStream input, String direction)
            throws ProtocolException {
            if (input == null) {
                throw new ProtocolException(
                    "direction=" + direction + " record=header type=unknown "
                        + "scene=unknown offset=0: null input"
                );
            }
            this.input = input;
            this.direction = direction;
        }

        private void readHeader(int expectedStream)
            throws IOException {
            long start = offset;
            for (byte expected : MAGIC) {
                int actual = readByteOrEof(0, "header", "unknown");
                if (actual != (expected & 0xff)) {
                    throw SceneSyncWireCodec.protocol(direction, 0, "header", "unknown", offset - 1,
                        "magic mismatch: expected HETS");
                }
            }
            int version = readU16(0, "header", "unknown");
            if (version != WIRE_VERSION) {
                throw SceneSyncWireCodec.protocol(direction, 0, "header", "unknown", start + 4,
                    "wire version mismatch: expected " + WIRE_VERSION
                        + ", got " + version);
            }
            int streamType = readU16(0, "header", "unknown");
            if (streamType != STREAM_EXPORT
                && streamType != STREAM_APPLY_REQUEST
                && streamType != STREAM_APPLY_RESULT) {
                throw SceneSyncWireCodec.protocol(direction, 0, "header", "unknown", start + 6,
                    "unknown stream type=" + streamType);
            }
            if (streamType != expectedStream) {
                throw SceneSyncWireCodec.protocol(direction, 0, "header", "unknown", start + 6,
                    "wrong stream type: expected " + expectedStream
                        + ", got " + streamType);
            }
        }

        private int readUnsignedByte(int index, String type, String sceneName)
            throws IOException {
            return readByteOrEof(index, type, sceneName);
        }

        private int readU16(int index, String type, String sceneName)
            throws IOException {
            int high = readByteOrEof(index, type, sceneName);
            int low = readByteOrEof(index, type, sceneName);
            return (high << 8) | low;
        }

        private long readUnsignedInt(int index, String type, String sceneName)
            throws IOException {
            long b1 = readByteOrEof(index, type, sceneName);
            long b2 = readByteOrEof(index, type, sceneName);
            long b3 = readByteOrEof(index, type, sceneName);
            long b4 = readByteOrEof(index, type, sceneName);
            return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        }

        private int readByteOrEof(int index, String type, String sceneName)
            throws IOException {
            int value = input.read();
            if (value < 0) {
                throw SceneSyncWireCodec.protocol(direction, index, type, sceneName, offset,
                    "early EOF");
            }
            offset++;
            return value;
        }

        private PayloadReader payload(long length, int index, String type) {
            return new PayloadReader(this, length, index, type);
        }

        private void ensureEof(int index, String type, String sceneName)
            throws IOException {
            int value = input.read();
            if (value >= 0) {
                throw SceneSyncWireCodec.protocol(direction, index, type, sceneName, offset,
                    "trailing data after END");
            }
        }

        private ProtocolException protocol(
            int index,
            String type,
            String sceneName,
            String reason
        ) {
            return SceneSyncWireCodec.protocol(
                direction, index, type, sceneName, offset, reason
            );
        }
    }

    private static final class PayloadReader {
        private final WireReader parent;
        private long remaining;
        private final int index;
        private final String type;

        private PayloadReader(
            WireReader parent,
            long length,
            int index,
            String type
        ) {
            this.parent = parent;
            this.remaining = length;
            this.index = index;
            this.type = type;
        }

        private String readName() throws IOException {
            int nameLength = readUnsignedShort("unknown");
            if (nameLength < 1 || nameLength > MAX_SCENE_NAME_BYTES) {
                throw protocol("unknown", "SceneName length must be 1.."
                    + MAX_SCENE_NAME_BYTES + " bytes, got " + nameLength);
            }
            byte[] bytes = readBytes(nameLength, "unknown");
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            } catch (CharacterCodingException e) {
                throw protocol("unknown", "SceneName is not strict UTF-8");
            }
            return requireSceneName(name, parent.direction, index, type);
        }

        private byte[] readSceneBody(String sceneName) throws IOException {
            long length = readUnsignedInt(sceneName);
            if (length < 1L || length > MAX_SCENE_BYTES) {
                throw protocol(sceneName, "Scene body length must be 1.."
                    + MAX_SCENE_BYTES + " bytes, got " + length);
            }
            if (length > remaining) {
                throw protocol(sceneName, "Scene body length " + length
                    + " exceeds payload bytes remaining " + remaining);
            }
            byte[] bytes = readBytes((int) length, sceneName);
            validateUtf8(bytes, parent.direction, index, type, sceneName,
                "Scene body");
            return bytes;
        }

        private byte[] streamSceneBody(
            String sceneName,
            SceneBodyConsumer consumer
        ) throws IOException {
            long length = readUnsignedInt(sceneName);
            if (length < 1L || length > MAX_SCENE_BYTES) {
                throw protocol(sceneName, "Scene body length must be 1.."
                    + MAX_SCENE_BYTES + " bytes, got " + length);
            }
            if (length > remaining) {
                throw protocol(sceneName, "Scene body length " + length
                    + " exceeds payload bytes remaining " + remaining);
            }
            SceneBodyInputStream body = new SceneBodyInputStream(
                this, (int) length, sceneName
            );
            consumer.accept(sceneName, (int) length, body);
            body.finish();
            return null;
        }

        private List<String> readBlockedScenes() throws IOException {
            long count = readUnsignedInt("unknown");
            if (count > MAX_SCENES) {
                throw protocol("unknown", "blocked SceneName count must be 0.."
                    + MAX_SCENES + ", got " + count);
            }
            List<String> names = new ArrayList<>((int) count);
            Set<String> unique = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String name = readName();
                if (!unique.add(name)) {
                    throw protocol(name, "duplicate SceneName in blocked list");
                }
                names.add(name);
            }
            return names;
        }

        private int readUnsignedByte(String sceneName) throws IOException {
            return readBytesAsInt(1, sceneName);
        }

        private int readUnsignedShort(String sceneName) throws IOException {
            return readBytesAsInt(2, sceneName);
        }

        private long readUnsignedInt(String sceneName) throws IOException {
            if (remaining < 4L) {
                throw protocol(sceneName, "expected 4 bytes, payload has "
                    + remaining + " remaining");
            }
            int b1 = readOne(sceneName);
            int b2 = readOne(sceneName);
            int b3 = readOne(sceneName);
            int b4 = readOne(sceneName);
            return ((long) b1 << 24) | ((long) b2 << 16)
                | ((long) b3 << 8) | b4;
        }

        private int readBytesAsInt(int count, String sceneName)
            throws IOException {
            if (remaining < count) {
                throw protocol(sceneName, "expected " + count
                    + " bytes, payload has " + remaining + " remaining");
            }
            if (count == 1) {
                return readOne(sceneName);
            }
            return (readOne(sceneName) << 8) | readOne(sceneName);
        }

        private int readOne(String sceneName) throws IOException {
            int value = parent.readByteOrEof(index, type, sceneName);
            remaining--;
            return value;
        }

        private byte[] readBytes(int length, String sceneName) throws IOException {
            if (length < 0 || remaining < length) {
                throw protocol(sceneName, "expected " + length
                    + " bytes, payload has " + remaining + " remaining");
            }
            byte[] bytes = new byte[length];
            int position = 0;
            while (position < length) {
                int read = parent.input.read(bytes, position, length - position);
                if (read < 0) {
                    throw protocol(sceneName, "early EOF while reading "
                        + length + " bytes");
                }
                if (read == 0) {
                    continue;
                }
                position += read;
                parent.offset += read;
                remaining -= read;
            }
            return bytes;
        }

        private void finish(String sceneName) throws ProtocolException {
            if (remaining != 0L) {
                throw protocol(sceneName, "payload has " + remaining
                    + " trailing bytes");
            }
        }

        private ProtocolException protocol(String sceneName, String reason) {
            return SceneSyncWireCodec.protocol(
                parent.direction, index, type, sceneName, parent.offset, reason
            );
        }
    }

    /** InputStream limited to one declared Scene body and validated as UTF-8. */
    private static final class SceneBodyInputStream extends InputStream {
        private final PayloadReader owner;
        private final String sceneName;
        private final Utf8Validator validator;
        private int remaining;
        private boolean finished;

        private SceneBodyInputStream(
            PayloadReader owner,
            int length,
            String sceneName
        ) {
            this.owner = owner;
            this.remaining = length;
            this.sceneName = sceneName;
            this.validator = new Utf8Validator(owner, sceneName);
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = owner.parent.input.read();
            if (value < 0) {
                throw owner.protocol(sceneName, "early EOF while reading Scene body");
            }
            owner.parent.offset++;
            owner.remaining--;
            remaining--;
            validator.accept(value);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length)
            throws IOException {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (remaining == 0) {
                return -1;
            }
            int wanted = Math.min(length, remaining);
            if (wanted == 0) {
                return 0;
            }
            int read = owner.parent.input.read(bytes, offset, wanted);
            if (read < 0) {
                throw owner.protocol(sceneName, "early EOF while reading Scene body");
            }
            if (read == 0) {
                return 0;
            }
            owner.parent.offset += read;
            owner.remaining -= read;
            remaining -= read;
            for (int i = 0; i < read; i++) {
                validator.accept(bytes[offset + i] & 0xff);
            }
            return read;
        }

        private void finish() throws ProtocolException {
            if (finished) {
                return;
            }
            finished = true;
            if (remaining != 0) {
                throw owner.protocol(sceneName, "Scene body consumer stopped with "
                    + remaining + " bytes remaining");
            }
            validator.finish();
        }
    }

    /** Incremental UTF-8 validator that never buffers the Scene body. */
    private static final class Utf8Validator {
        private final PayloadReader owner;
        private final String sceneName;
        private int continuationCount;
        private int codePoint;
        private int firstContinuationMin = 0x80;
        private int firstContinuationMax = 0xbf;

        private Utf8Validator(PayloadReader owner, String sceneName) {
            this.owner = owner;
            this.sceneName = sceneName;
        }

        private void accept(int value) throws ProtocolException {
            if (continuationCount > 0) {
                if (value < firstContinuationMin
                    || value > firstContinuationMax) {
                    throw owner.protocol(sceneName,
                        "Scene body is not strict UTF-8");
                }
                codePoint = (codePoint << 6) | (value & 0x3f);
                continuationCount--;
                firstContinuationMin = 0x80;
                firstContinuationMax = 0xbf;
                if (continuationCount == 0
                    && (codePoint > 0x10ffff
                        || (codePoint >= 0xd800 && codePoint <= 0xdfff))) {
                    throw owner.protocol(sceneName,
                        "Scene body is not strict UTF-8");
                }
                return;
            }
            if (value <= 0x7f) {
                return;
            }
            if (value >= 0xc2 && value <= 0xdf) {
                continuationCount = 1;
                codePoint = value & 0x1f;
                return;
            }
            if (value >= 0xe0 && value <= 0xef) {
                continuationCount = 2;
                codePoint = value & 0x0f;
                if (value == 0xe0) {
                    firstContinuationMin = 0xa0;
                } else if (value == 0xed) {
                    firstContinuationMax = 0x9f;
                }
                return;
            }
            if (value >= 0xf0 && value <= 0xf4) {
                continuationCount = 3;
                codePoint = value & 0x07;
                if (value == 0xf0) {
                    firstContinuationMin = 0x90;
                } else if (value == 0xf4) {
                    firstContinuationMax = 0x8f;
                }
                return;
            }
            throw owner.protocol(sceneName, "Scene body is not strict UTF-8");
        }

        private void finish() throws ProtocolException {
            if (continuationCount != 0) {
                throw owner.protocol(sceneName,
                    "Scene body ends in an incomplete UTF-8 sequence");
            }
        }
    }

    /** Strict UTF-8 validator for the producer-side streaming writer. */
    private static final class Utf8StreamValidator {
        private final String direction;
        private final int index;
        private final String type;
        private final String sceneName;
        private int continuationCount;
        private int codePoint;
        private int firstContinuationMin = 0x80;
        private int firstContinuationMax = 0xbf;

        private Utf8StreamValidator(
            String direction,
            int index,
            String type,
            String sceneName
        ) {
            this.direction = direction;
            this.index = index;
            this.type = type;
            this.sceneName = sceneName;
        }

        private void accept(byte[] bytes, int length) throws ProtocolException {
            for (int offset = 0; offset < length; offset++) {
                acceptByte(bytes[offset] & 0xff);
            }
        }

        private void acceptByte(int value) throws ProtocolException {
            if (continuationCount > 0) {
                if (value < firstContinuationMin
                    || value > firstContinuationMax) {
                    throw invalid();
                }
                codePoint = (codePoint << 6) | (value & 0x3f);
                continuationCount--;
                firstContinuationMin = 0x80;
                firstContinuationMax = 0xbf;
                if (continuationCount == 0
                    && (codePoint > 0x10ffff
                        || (codePoint >= 0xd800 && codePoint <= 0xdfff))) {
                    throw invalid();
                }
                return;
            }
            if (value <= 0x7f) {
                return;
            }
            if (value >= 0xc2 && value <= 0xdf) {
                continuationCount = 1;
                codePoint = value & 0x1f;
                return;
            }
            if (value >= 0xe0 && value <= 0xef) {
                continuationCount = 2;
                codePoint = value & 0x0f;
                if (value == 0xe0) {
                    firstContinuationMin = 0xa0;
                } else if (value == 0xed) {
                    firstContinuationMax = 0x9f;
                }
                return;
            }
            if (value >= 0xf0 && value <= 0xf4) {
                continuationCount = 3;
                codePoint = value & 0x07;
                if (value == 0xf0) {
                    firstContinuationMin = 0x90;
                } else if (value == 0xf4) {
                    firstContinuationMax = 0x8f;
                }
                return;
            }
            throw invalid();
        }

        private void finish() throws ProtocolException {
            if (continuationCount != 0) {
                throw invalid();
            }
        }

        private ProtocolException invalid() {
            return protocol(
                direction,
                index,
                type,
                sceneName,
                -1,
                "Scene body is not strict UTF-8"
            );
        }
    }
}
