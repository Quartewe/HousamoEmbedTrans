package com.quarty.housamoembedtrans.continuation;

import java.util.Arrays;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Checks every branch once; a sequence guarantees dialogue if any of its steps guarantees it. */
final class StorySteps {
    private StorySteps() {}
    static void validate(JSONArray steps,JSONObject settings,JSONObject resources) throws JSONException {
        if (!sequence(steps,settings,resources)) throw new JSONException("每条完整路线至少需要一条对白");
    }
    private static boolean sequence(JSONArray steps,JSONObject settings,JSONObject resources) throws JSONException {
        boolean dialogue = false;
        for (int i=0;i<steps.length();i++) {
            JSONObject step=steps.getJSONObject(i);
            switch (word(step,"type")) {
                case "dialogue":
                    keys(step,"type","text","speaker");
                    word(step,"text");
                    dialogue=true;
                    break;
                default: throw new JSONException("未知 step 类型");
            }
        }
        return dialogue;
    }
    private static String word(JSONObject o,String key) throws JSONException {
        Object raw=o.get(key);
        require(raw instanceof String && ContinuationContent.hasText((String)raw),key+" 需要非空白文本");
        return (String)raw;
    }
    static void keys(JSONObject o,String... allowed) throws JSONException {
        for (Iterator<String> it=o.keys();it.hasNext();) {
            String key=it.next();
            require(Arrays.asList(allowed).contains(key),"未知字段："+key);
        }
    }
    private static void require(boolean condition,String reason) throws JSONException {
        if (!condition) throw new JSONException(reason);
    }
}
