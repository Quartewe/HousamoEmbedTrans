package com.quarty.housamoembedtrans.bridge;

public final class HetBridgeContract {
    public static final String MODULE_PACKAGE = "com.quarty.housamoembedtrans";
    public static final String TARGET_PACKAGE = "jp.co.lifewonders.housamo";
    public static final String USER_FILES_AUTHORITY =
        MODULE_PACKAGE + ".userfiles";

    public static final String TRANSLATION_SERVICE_CLASS_NAME =
        MODULE_PACKAGE + ".translation.TranslationService";
    public static final String ACTION_START_TRANSLATION_SERVICE =
        MODULE_PACKAGE + ".translation.action.START_TRANSLATION_SERVICE";
    public static final int ENQUEUE_RESULT_CREATED = 1;
    public static final int ENQUEUE_RESULT_EXISTING = 0;
    public static final int ENQUEUE_RESULT_RETRYABLE_PERSISTENCE = -1;
    public static final int ENQUEUE_RESULT_DUPLICATE_REJECTED = -2;
    public static final int ENQUEUE_RESULT_EXECUTION_NOT_SETTLED = -3;
    public static final int ENQUEUE_RESULT_USER_ACTION_REQUIRED = -4;
    public static final int PROTOCOL_VERSION = 1;

    public static final String METHOD_GET_API_KEY = "get_api_key";
    public static final String METHOD_LIST_SCENES = "list_scenes";
    public static final String METHOD_GET_SCENE_MUTATION_STATUS =
        "get_scene_mutation_status";
    public static final String ARG_SCENE_NAME = "scene_name";
    public static final String RESULT_API_KEY = "api_key";
    public static final String RESULT_SCENES = "scenes";
    public static final String RESULT_DELETED_SCENES = "deleted_scenes";
    public static final String RESULT_MUTATION_STATUS = "mutation_status";
    public static final String MUTATION_STATUS_COMMITTED = "COMMITTED";
    public static final String MUTATION_STATUS_DEFERRED = "DEFERRED";
    public static final String MUTATION_STATUS_UNKNOWN = "UNKNOWN";

    private HetBridgeContract() {
        throw new AssertionError("No instances");
    }
}
