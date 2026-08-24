package com.quarty.housamoembedtrans.ui;

import android.content.Context;

import com.quarty.housamoembedtrans.R;
import com.quarty.housamoembedtrans.runtime.SceneSyncRuntimeState;

/** Shared labels for the complete Scene Sync runtime phase contract. */
final class SceneSyncUiText {
    private SceneSyncUiText() {}

    static String phaseLabel(
        Context context,
        SceneSyncRuntimeState.Phase phase
    ) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }
        return context.getString(phaseResource(phase));
    }

    /** Package-private pure mapping seam for host/static four-state checks. */
    static int phaseResource(SceneSyncRuntimeState.Phase phase) {
        if (phase == null) {
            return R.string.scene_sync_phase_idle;
        }
        switch (phase) {
            case IDLE:
                return R.string.scene_sync_phase_idle;
            case FULL_SYNC:
                return R.string.scene_sync_phase_full_sync;
            case MANUAL_REFRESH:
                return R.string.scene_sync_phase_refresh;
            case MANUAL_APPLY:
                return R.string.scene_sync_phase_manual_apply;
            default:
                // Do not silently map a newly-added phase to IDLE.
                throw new IllegalStateException(
                    "Scene Sync phase label mapping is incomplete: " + phase
                );
        }
    }
}
