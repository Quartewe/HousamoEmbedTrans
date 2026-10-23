package com.quarty.housamoembedtrans.bridge;

import android.content.Context;
import android.os.RemoteException;
import com.quarty.housamoembedtrans.translation.IChoiceBackCallback;
import com.quarty.housamoembedtrans.translation.IGameChoicePort;
import java.util.HashSet;
import java.util.Set;

/** Connection lifetime only. Native owns the story, history and restore state. */
final class GameChoicePort extends IGameChoicePort.Stub {
    private final Context context;
    private final Set<Completion> pending = new HashSet<>();
    private boolean connected;

    GameChoicePort(Context context) {
        this.context = context;
    }

    synchronized void connect() {
        connected = true;
    }

    void disconnect() {
        Completion[] interrupted;
        synchronized (this) {
            connected = false;
            interrupted = pending.toArray(new Completion[0]);
            pending.clear();
        }
        for (Completion completion : interrupted) {
            completion.deliver(ChoiceBackResult.UNKNOWN);
        }
    }

    @Override
    public void requestBack(IChoiceBackCallback callback) {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        Completion completion = new Completion(this, callback);
        boolean admitted;
        synchronized (this) {
            admitted = connected;
            if (admitted) pending.add(completion);
        }
        if (!admitted) {
            completion.deliver(ChoiceBackResult.UNAVAILABLE);
            return;
        }
        try {
            // This queues work for Unity; it must not wait for loading to finish.
            nativeRequestBack(completion);
        } catch (UnsatisfiedLinkError error) {
            completion.onCompleted(ChoiceBackResult.UNAVAILABLE);
        } catch (RuntimeException error) {
            completion.onCompleted(ChoiceBackResult.FAILED);
        }
    }

    private static native void nativeRequestBack(Completion completion);

    /** JNI retains this object until native cleanup, even after disconnection. */
    private static final class Completion {
        private final GameChoicePort owner;
        private IChoiceBackCallback callback;

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
