package com.quarty.housamoembedtrans.translation;

import android.os.ParcelFileDescriptor;

/** Independent of Scene generations. Stream: count, then UTF name, long length, bytes. */
interface IGameLogPort {
    ParcelFileDescriptor exportRecentLogs();
}
