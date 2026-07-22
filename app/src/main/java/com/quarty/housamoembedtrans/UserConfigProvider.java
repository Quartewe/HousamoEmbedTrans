package com.quarty.housamoembedtrans;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Private-file bridge between the installed module app and the hooked game.
 * Config resources are read-only; schema-valid scene files can be mirrored in
 * either direction. Only the module and target package are accepted callers.
 */
public final class UserConfigProvider extends ContentProvider {

    static final String AUTHORITY = "com.quarty.housamoembedtrans.userfiles";
    static final String METHOD_GET_API_KEY = "get_api_key";
    static final String METHOD_LIST_SCENES = "list_scenes";
    static final String RESULT_API_KEY = "api_key";
    static final String RESULT_SCENES = "scenes";
    static final String RESULT_DELETED_SCENES = "deleted_scenes";

    private static final String TAG = "HET.UserFiles";
    private static final String MODULE_PACKAGE = "com.quarty.housamoembedtrans";
    private static final String TARGET_PACKAGE = "jp.co.lifewonders.housamo";
    private static final int CONFIG = 1;
    private static final int RUNTIME = 2;
    private static final int CHARDICT = 3;
    private static final int GAMETERMS = 4;
    private static final int SCENE = 5;

    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URI_MATCHER.addURI(AUTHORITY, ConfigStore.CONFIG_FILE_NAME, CONFIG);
        URI_MATCHER.addURI(AUTHORITY, ConfigStore.RUNTIME_FILE_NAME, RUNTIME);
        URI_MATCHER.addURI(AUTHORITY, ConfigStore.CHARDICT_FILE_NAME, CHARDICT);
        URI_MATCHER.addURI(AUTHORITY, ConfigStore.GAMETERMS_FILE_NAME, GAMETERMS);
        URI_MATCHER.addURI(AUTHORITY, SceneStore.DIRECTORY_NAME + "/*", SCENE);
    }

    private ConfigStore configStore;
    private SceneStore sceneStore;
    private final ExecutorService sceneWriter = Executors.newSingleThreadExecutor();

    @Override
    public boolean onCreate() {
        Context context = providerContext();
        configStore = new ConfigStore(context);
        sceneStore = new SceneStore(context);
        grantTargetReadAccess(context);
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceReadCaller();

        if (URI_MATCHER.match(uri) == SCENE) {
            return openSceneFile(uri, mode);
        }
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("user resources are read-only");
        }

        String name = resourceName(uri);
        File file = configStore.getValidUserFile(name);
        if (file == null) {
            throw new FileNotFoundException("no valid user override for " + name);
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        if (URI_MATCHER.match(uri) == SCENE) {
            return "application/json";
        }
        resourceName(uri);
        return "application/json";
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceReadCaller();
        Bundle result = new Bundle();
        if (METHOD_GET_API_KEY.equals(method)) {
            result.putString(RESULT_API_KEY, configStore.loadApiKey());
            return result;
        }
        if (METHOD_LIST_SCENES.equals(method)) {
            result.putStringArrayList(
                RESULT_SCENES,
                new ArrayList<>(sceneStore.listValidFileNames())
            );
            result.putStringArrayList(
                RESULT_DELETED_SCENES,
                new ArrayList<>(sceneStore.listDeletedFileNames())
            );
            return result;
        }
        throw new IllegalArgumentException("unsupported provider method: " + method);
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        throw new UnsupportedOperationException("query is not supported");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("insert is not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete is not supported");
    }

    @Override
    public int update(
        Uri uri,
        ContentValues values,
        String selection,
        String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("update is not supported");
    }

    private String resourceName(Uri uri) {
        switch (URI_MATCHER.match(uri)) {
            case CONFIG:
                return ConfigStore.CONFIG_FILE_NAME;
            case RUNTIME:
                return ConfigStore.RUNTIME_FILE_NAME;
            case CHARDICT:
                return ConfigStore.CHARDICT_FILE_NAME;
            case GAMETERMS:
                return ConfigStore.GAMETERMS_FILE_NAME;
            default:
                throw new IllegalArgumentException("unknown user resource URI: " + uri);
        }
    }

    private ParcelFileDescriptor openSceneFile(Uri uri, String mode)
        throws FileNotFoundException {
        String fileName = uri.getLastPathSegment();
        if (!SceneStore.isSimpleSceneFileName(fileName)) {
            throw new FileNotFoundException("invalid scene file name");
        }

        if ("r".equals(mode)) {
            File file = sceneStore.getValidSceneFile(fileName);
            if (file == null) {
                throw new FileNotFoundException("no valid scene named " + fileName);
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        if (!"w".equals(mode) && !"wt".equals(mode) && !"rwt".equals(mode)) {
            throw new FileNotFoundException("unsupported scene mode: " + mode);
        }

        try {
            File temporaryFile = sceneStore.createIncomingFile();
            return ParcelFileDescriptor.open(
                temporaryFile,
                ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE
                    | ParcelFileDescriptor.MODE_WRITE_ONLY,
                new Handler(Looper.getMainLooper()),
                error -> {
                    if (error != null) {
                        if (!temporaryFile.delete()) {
                            Log.w(TAG, "Could not delete failed scene temp " + temporaryFile);
                        }
                        Log.w(TAG, "Scene writer closed with an error", error);
                        return;
                    }
                    sceneWriter.execute(() -> {
                        try {
                            sceneStore.acceptIncoming(temporaryFile, fileName);
                        } catch (Exception e) {
                            Log.w(TAG, "Rejected incoming scene " + fileName, e);
                        }
                    });
                }
            );
        } catch (IOException e) {
            FileNotFoundException wrapped = new FileNotFoundException(
                "could not prepare incoming scene " + fileName
            );
            wrapped.initCause(e);
            throw wrapped;
        }
    }

    private void enforceReadCaller() {
        int callerUid = Binder.getCallingUid();
        if (callerUid == Process.myUid()) {
            return;
        }

        String[] packages = providerContext()
            .getPackageManager()
            .getPackagesForUid(callerUid);

        if (packages != null) {
            for (String packageName : packages) {
                if (TARGET_PACKAGE.equals(packageName)
                    || MODULE_PACKAGE.equals(packageName)) {
                    return;
                }
            }
        }

        throw new SecurityException("caller is not allowed to read module user files");
    }

    private void grantTargetReadAccess(Context context) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        String[] names = {
            ConfigStore.CONFIG_FILE_NAME,
            ConfigStore.CHARDICT_FILE_NAME,
            ConfigStore.GAMETERMS_FILE_NAME
        };

        for (String name : names) {
            Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(name)
                .build();
            try {
                context.grantUriPermission(TARGET_PACKAGE, uri, flags);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not grant target read access to " + name, e);
            }
        }

        Uri scenes = new Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(SceneStore.DIRECTORY_NAME)
            .build();
        try {
            context.grantUriPermission(
                TARGET_PACKAGE,
                scenes,
                flags
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            );
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not grant target scene access", e);
        }
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("provider context is unavailable");
        }
        return context;
    }

}
