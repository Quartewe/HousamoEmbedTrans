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
            if (!binder.isBinderAlive()) clearLocked();
        }
        finishInterrupted(interrupted);
    }

    void unregister(IGameChoicePort expected) {
        Pending[] interrupted;
        synchronized (this) {
            if (port == null || !port.asBinder().equals(expected.asBinder())) return;
            interrupted = clearLocked();
        }
        finishInterrupted(interrupted);
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
        if (interrupted != null) finishInterrupted(interrupted);
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
    }

    private Pending[] clearLocked() {
        if (port != null) port.asBinder().unlinkToDeath(death, 0);
        port = null;
        death = null;
        Pending[] interrupted = pending.toArray(new Pending[0]);
        pending.clear();
        return interrupted;
    }

    private void finishInterrupted(Pending[] interrupted) {
        for (Pending request : interrupted) {
            request.deliverResult(ChoiceBackResult.UNKNOWN);
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
