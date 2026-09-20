package com.quarty.housamoembedtrans.translation;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Small metadata and file operations owned by the connected game process. */
interface IGameObbPort {
    Bundle inspect();
    boolean install(String fileName, in ParcelFileDescriptor content);
}
