package com.quarty.housamoembedtrans.translation;

import android.os.IBinder;
import android.os.RemoteException;
import com.quarty.housamoembedtrans.bridge.ChoiceBackResult;
import java.util.HashSet;
import java.util.Set;

/** Service-owned port lifetime and one-shot result relay, independent of Scene sync. */
final class GameChoiceRequests {
    private IGameChoicePort port;
    private IBinder.DeathRecipient death;
    private final Set<Pending> pending = new HashSet<>();
    private boolean closed;
    private final Runnable positionChanged;
    private PositionReceiver positionReceiver;
    private TranslationService.StoryPosition position;

    GameChoiceRequests(Runnable positionChanged) {
        this.positionChanged = positionChanged;
    }

    synchronized TranslationService.StoryPosition getPosition() { return position; }

    void register(IGameChoicePort next) throws RemoteException {
        Pending[] interrupted;
        synchronized (this) {
            if (closed) return;
            IBinder binder = next.asBinder();
            IBinder.DeathRecipient nextDeath = () -> unregister(next);
            binder.linkToDeath(nextDeath, 0);
            interrupted = clearLocked();
            port = next;
            death = nextDeath;
            if (!binder.isBinderAlive()) {
                clearLocked();
            } else {
                positionReceiver = new PositionReceiver();
                try {
                    // Oneway, serialized with port replacement. Older game ports keep back support.
                    next.subscribePosition(positionReceiver);
                } catch (RemoteException | RuntimeException error) {
                    positionReceiver = null;
                }
            }
        }
        finishInterrupted(interrupted);
        positionChanged.run();
    }

    void unregister(IGameChoicePort expected) {
        Pending[] interrupted;
        synchronized (this) {
            if (port == null || !port.asBinder().equals(expected.asBinder())) return;
            interrupted = clearLocked();
        }
        finishInterrupted(interrupted);
        positionChanged.run();
    }

    void request(IChoiceBackCallback callback, boolean gameConnected) {
        IGameChoicePort target;
        Pending request;
        int unavailable;
        Pending[] interrupted = null;
        synchronized (this) {
            if (port != null && !port.asBinder().isBinderAlive()) {
                interrupted = clearLocked();
                gameConnected = false;
            }
            target = closed ? null : port;
            unavailable = closed || gameConnected
                ? ChoiceBackResult.UNAVAILABLE : ChoiceBackResult.GAME_NOT_CONNECTED;
            request = target == null ? null : new Pending(callback);
            if (request != null) pending.add(request);
        }
        if (interrupted != null) {
            finishInterrupted(interrupted);
            positionChanged.run();
        }
        if (request == null) {
            deliver(callback, unavailable);
            return;
        }
        try {
            // Oneway dispatch only: the final result comes from Unity completion.
            target.requestBack(request);
        } catch (RemoteException | RuntimeException error) {
            request.onCompleted(ChoiceBackResult.UNKNOWN);
        }
    }

    void close() {
        Pending[] interrupted;
        synchronized (this) {
            closed = true;
            interrupted = clearLocked();
        }
        finishInterrupted(interrupted);
        positionChanged.run();
    }

    private Pending[] clearLocked() {
        if (port != null) port.asBinder().unlinkToDeath(death, 0);
        port = null;
        death = null;
        positionReceiver = null;
        position = null;
        Pending[] interrupted = pending.toArray(new Pending[0]);
        pending.clear();
        return interrupted;
    }

    private void finishInterrupted(Pending[] interrupted) {
        for (Pending request : interrupted) {
            request.deliverResult(ChoiceBackResult.UNKNOWN);
        }
    }

    /** Each subscription owns its value; callbacks from a replaced port cannot publish. */
    private final class PositionReceiver extends IStoryPositionListener.Stub {
        @Override
        public void onPositionChanged(String scene, String choiceLabel, int choicePage) {
            synchronized (GameChoiceRequests.this) {
                if (positionReceiver != this) return;
                position = new TranslationService.StoryPosition(scene, choiceLabel, choicePage);
            }
            positionChanged.run();
        }
    }

    private static void deliver(IChoiceBackCallback callback, int result) {
        try {
            callback.onCompleted(result);
        } catch (RemoteException ignored) {
            // The caller has left; there is no retry of this intent.
        }
    }

    private final class Pending extends IChoiceBackCallback.Stub {
        private IChoiceBackCallback callback;

        Pending(IChoiceBackCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onCompleted(int result) {
            synchronized (GameChoiceRequests.this) {
                if (!pending.remove(this)) return;
            }
            deliverResult(result);
        }

        void deliverResult(int result) {
            IChoiceBackCallback target = callback;
            callback = null;
            deliver(target, result);
        }
    }
}
