package com.quarty.housamoembedtrans.storage.config;

import org.json.JSONObject;

/** Version-bound data for the existing arm64 choice tracker, shared with JNI. */
public final class ChoiceBackConfig {
    public final byte[] buildId;
    public final long beginPage;
    public final long savePage;
    public final long waitInput;
    public final long select;
    public final long startScenario;
    public final long endScenario;
    public final long destroy;
    public final long load;
    public final long moveLoad;
    public final long createLoad;
    public final long startGame;
    public final long savePageCaller;
    public final int sendChar;
    public final int waitEffectOnInputInPage;
    public final int waitInputInPage;
    public final int waitInputBrPage;

    // JSON conversion only. The resource owner validates before publication;
    // native receives these typed values without another schema preflight.
    public ChoiceBackConfig(JSONObject json) throws Exception {
        String hex = json.getString("BuildId");
        buildId = new byte[hex.length() / 2];
        for (int i = 0; i < buildId.length; i++) {
            buildId[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        JSONObject rva = json.getJSONObject("RVA");
        beginPage = address(rva, "BeginPage");
        savePage = address(rva, "SavePage");
        waitInput = address(rva, "WaitInput");
        select = address(rva, "Select");
        startScenario = address(rva, "StartScenario");
        endScenario = address(rva, "EndScenario");
        destroy = address(rva, "Destroy");
        load = address(rva, "Load");
        moveLoad = address(rva, "MoveLoad");
        createLoad = address(rva, "CreateLoad");
        startGame = address(rva, "StartGame");
        savePageCaller = address(json, "SavePageCaller");
        JSONObject status = json.getJSONObject("PageStatus");
        sendChar = status.getInt("SendChar");
        waitEffectOnInputInPage = status.getInt("WaitEffectOnInputInPage");
        waitInputInPage = status.getInt("WaitInputInPage");
        waitInputBrPage = status.getInt("WaitInputBrPage");
    }

    private static long address(JSONObject json, String key) throws Exception {
        String value = json.getString(key).trim();
        return value.startsWith("0x") || value.startsWith("0X")
            ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value);
    }

    public static void validate(JSONObject json) throws Exception {
        if (!json.getString("BuildId").matches("(?:[0-9a-fA-F]{2})+")) {
            throw new IllegalArgumentException("ChoiceBack.BuildId must contain whole hexadecimal bytes");
        }
        ChoiceBackConfig config = new ChoiceBackConfig(json);
        long[] addresses = {config.beginPage, config.savePage, config.waitInput,
            config.select, config.startScenario, config.endScenario, config.destroy,
            config.load, config.moveLoad, config.createLoad, config.startGame,
            config.savePageCaller};
        for (long value : addresses) {
            if (value <= 0 || (value & 3) != 0) {
                throw new IllegalArgumentException("ChoiceBack addresses must be positive, aligned arm64 RVAs");
            }
        }
        JSONObject status = json.getJSONObject("PageStatus");
        java.util.HashSet<Integer> values = new java.util.HashSet<>();
        for (String key : new String[]{"SendChar", "WaitEffectOnInputInPage",
                "WaitInputInPage", "WaitInputBrPage"}) {
            Object value = status.get(key);
            if (!(value instanceof Integer) || !values.add((Integer) value)) {
                throw new IllegalArgumentException("ChoiceBack.PageStatus values must be distinct int32 integers");
            }
        }
    }
}
