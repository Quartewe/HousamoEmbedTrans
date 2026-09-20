package com.quarty.housamoembedtrans.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.quarty.housamoembedtrans.translation.IGameObbPort;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/** Runs on game Binder threads, independently of Unity and Scene synchronization. */
final class GameObbPort extends IGameObbPort.Stub {
    private final Context context;
    private volatile boolean closed;

    GameObbPort(Context context) {
        this.context = context;
    }

    void close() {
        closed = true;
    }

    private File directory() throws IOException {
        if (closed) throw new IOException("游戏资源连接已关闭，请重新检查");
        File directory = context.getObbDir();
        if (directory == null) throw new IOException("游戏 OBB 存储目录不可用");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法访问游戏 OBB 目录：" + directory);
        }
        return directory;
    }

    @Override
    public synchronized Bundle inspect() {
        CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), 0
            );
            String version = info.versionName;
            if (version == null || version.trim().isEmpty()) {
                throw new IOException("无法读取游戏版本号");
            }
            File directory = directory();
            File[] entries = directory.listFiles();
            if (entries == null) throw new IOException("无法读取游戏 OBB 目录");
            ArrayList<String> files = new ArrayList<>();
            for (File entry : entries) {
                if (entry.isFile() && entry.getName().endsWith(".obb")) {
                    files.add(entry.getName());
                }
            }
            Bundle result = new Bundle();
            result.putString("version", version);
            result.putStringArrayList("files", files);
            return result;
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage());
        }
    }

    @Override
    public synchronized boolean install(String fileName, ParcelFileDescriptor content) {
        // HET validates the Release and asset name before sending this descriptor.
        // The receiver only copies into its own directory and publishes on success.
        File temporary = null;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(content)) {
            CallerVerifier.enforceAllowedCaller(context, HetBridgeContract.MODULE_PACKAGE);
            File directory = directory();
            File target = new File(directory, fileName);
            if (target.isFile()) return false;
            temporary = new File(directory, fileName + ".het-part");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed) throw new IOException("游戏资源连接已关闭");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (closed) throw new IOException("游戏资源连接已关闭");
            if (target.isFile()) return false;
            if (!temporary.renameTo(target)) {
                throw new IOException("无法将下载资源保存到游戏 OBB 目录");
            }
            return true;
        } catch (Exception error) {
            throw new IllegalStateException(error.getMessage());
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }
}
