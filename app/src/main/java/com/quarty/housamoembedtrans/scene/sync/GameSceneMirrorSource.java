package com.quarty.housamoembedtrans.scene.sync;

import com.quarty.housamoembedtrans.bridge.SceneSyncWireCodec;
import com.quarty.housamoembedtrans.scene.store.SceneStore;

/** Adapts one injected SceneStore snapshot enumeration to the export seam. */
public final class GameSceneMirrorSource
    implements SceneMirrorExportCoordinator.SceneSource {

    private final SceneStore sceneStore;

    public GameSceneMirrorSource(SceneStore sceneStore) {
        if (sceneStore == null) {
            throw new IllegalArgumentException("sceneStore cannot be null");
        }
        this.sceneStore = sceneStore;
    }

    @Override
    public void stream(SceneMirrorExportCoordinator.SceneConsumer consumer)
        throws Exception {
        if (consumer == null) {
            throw new IllegalArgumentException("consumer cannot be null");
        }
        sceneStore.forEachRawSceneCandidate((sceneName, snapshot, error) -> {
            if (snapshot != null) {
                consumer.scene(sceneName, snapshot.bytes);
            } else {
                consumer.rejected(sceneName, mapError(error));
            }
        });
    }

    private static int mapError(Exception error) {
        if (!(error instanceof SceneStore.RawSceneFailure)) {
            return SceneSyncWireCodec.EXPORT_INTERNAL_VALIDATION_FAILURE;
        }
        SceneStore.RawSceneFailureKind kind =
            ((SceneStore.RawSceneFailure) error).kind;
        switch (kind) {
            case READ:
                return SceneSyncWireCodec.EXPORT_READ_FAILED;
            case EMPTY:
                return SceneSyncWireCodec.EXPORT_EMPTY_FILE;
            case TOO_LARGE:
                return SceneSyncWireCodec.EXPORT_FILE_TOO_LARGE;
            case INVALID_UTF8:
                return SceneSyncWireCodec.EXPORT_INVALID_UTF8;
            case INVALID_JSON:
                return SceneSyncWireCodec.EXPORT_INVALID_JSON;
            case SCHEMA_INVALID:
                return SceneSyncWireCodec.EXPORT_SCHEMA_INVALID;
            case IDENTITY_MISMATCH:
                return SceneSyncWireCodec.EXPORT_SCENE_IDENTITY_MISMATCH;
            case INTERNAL:
            default:
                return SceneSyncWireCodec.EXPORT_INTERNAL_VALIDATION_FAILURE;
        }
    }
}
