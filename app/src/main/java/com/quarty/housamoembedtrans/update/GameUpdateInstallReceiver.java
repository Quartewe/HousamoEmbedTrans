package com.quarty.housamoembedtrans.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, mutable PackageInstaller callback; not exported to other applications. */
public final class GameUpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        GameUpdateController.get(context).installResult(intent, pending::finish);
    }
}
