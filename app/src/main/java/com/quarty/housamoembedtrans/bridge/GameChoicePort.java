package com.quarty.housamoembedtrans.bridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;
import com.quarty.housamoembedtrans.translation.IGameChoicePort;
import com.quarty.housamoembedtrans.translation.IStoryPositionListener;
import java.util.HashSet;
import java.util.Set;

/** Connection lifetime only. Native owns the story, history and restore state. */
final class GameChoicePort extends IGameChoicePort.Stub {
    private final Context context;
    private final Set<Completion> pending = new HashSet<>();
    private boolean connected;
    private long connectionEpoch;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Latest display value survives a service disconnect, just like native history.
    private String scene;
    private boolean positionReceived;
    private String choiceLabel = "";
    private int choicePage = -1;
    private IStoryPositionListener positionListener;

    GameChoicePort(Context context) {
        this.context = context;
    }

    synchronized void connect() {
        connected = true;
        ++connectionEpoch;
    }

    void disconnect() {
        Completion[] interrupted;
        synchronized (this) {
            connected = false;
            ++connectionEpoch;
            positionListener = null;
            interrupted = pending.toArray(new Completion[0]);
            pending.clear();
        }
        for (Completion completion : interrupted) {
            try { nativeCancelOpenStory(completion); } catch (UnsatisfiedLinkError ignored) { }
            completion.deliver(ChoiceBackResult.UNKNOWN);
        }
    }

    @Override
    public void subscribePosition(IStoryPositionListener listener) {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        final long epoch;
        synchronized (this) { epoch = connectionEpoch; }
        main.post(() -> {
            synchronized (this) {
                if (!connected || epoch != connectionEpoch || !listener.asBinder().isBinderAlive()) return;
                positionListener = listener;
                sendPositionLocked();
            }
        });
    }

    /** Native calls in Unity order; this handler also serializes initial subscription delivery. */
    void positionChanged(String scene, String choiceLabel, int choicePage) {
        main.post(() -> {
            synchronized (this) {
                this.scene = scene;
                positionReceived = true;
                this.choiceLabel = choiceLabel;
                this.choicePage = choicePage;
                sendPositionLocked();
            }
        });
    }

    private void sendPositionLocked() {
        if (!connected || positionListener == null || !positionReceived) return;
        try {
            positionListener.onPositionChanged(scene, choiceLabel, choicePage);
        } catch (RemoteException error) {
            positionListener = null;
        }
    }

    @Override
    public void requestBack(IChoiceBackCallback callback) {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        request(null, callback);
    }

    @Override
    public int getStoryOpenProtocolVersion() {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        return 1;
    }

    @Override
    public void requestOpenStory(String questLabel, IChoiceBackCallback callback) {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        request(questLabel, callback);
    }

    private void request(String questLabel, IChoiceBackCallback callback) {
        Completion completion = new Completion(this, callback);
        synchronized (this) {
            if (!connected) {
                completion.deliver(ChoiceBackResult.UNAVAILABLE);
                return;
            }
            pending.add(completion);
            try {
                // Mailbox publication and disconnect share this lock. Native only
                // queues here; Unity claims the operation after this call returns.
                if (questLabel == null) nativeRequestBack(completion);
                else nativeRequestOpenStory(questLabel, completion);
            } catch (UnsatisfiedLinkError error) {
                completion.onCompleted(ChoiceBackResult.UNAVAILABLE);
            } catch (RuntimeException error) {
                completion.onCompleted(ChoiceBackResult.FAILED);
            }
        }
    }

    private static native void nativeRequestBack(Completion completion);
    private static native void nativeRequestOpenStory(String questLabel, Completion completion);
    private static native void nativeCancelOpenStory(Completion completion);

    /** JNI retains this object until native cleanup, even after disconnection. */
    private static final class Completion {
        private final GameChoicePort owner;
        private IChoiceBackCallback callback;
        private boolean started;

        Completion(GameChoicePort owner, IChoiceBackCallback callback) {
            this.owner = owner;
            this.callback = callback;
        }

        void onCompleted(int result) {
            synchronized (owner) {
                if (!owner.pending.remove(this)) return;
            }
            deliver(result);
        }

        /** Unity claims the operation before changing any game state. */
        boolean tryStart() {
            synchronized (owner) {
                if (started || !owner.connected || !owner.pending.contains(this)) return false;
                started = true;
                return true;
            }
        }

        void deliver(int result) {
            IChoiceBackCallback target = callback;
            callback = null;
            try {
                target.onCompleted(result);
            } catch (RemoteException ignored) {
                // Native still owns and finishes the restore when HET has left.
            }
        }
    }
}
