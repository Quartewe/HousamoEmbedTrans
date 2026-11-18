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
                case "stage":
                    action(step,settings,resources);
                    break;
                default: throw new JSONException("未知 step 类型");
            }
        }
        return dialogue;
    }
    private static void action(JSONObject s,JSONObject settings,JSONObject res) throws JSONException {
        boolean bg=settings.getBoolean("allow_background");
        switch (word(s,"action")) {
            case "bg":
                require(bg,"未允许修改背景");
                keys(s,"type","action","bg","fade");
                require(contains(res.optJSONArray("backgrounds"),word(s,"bg")),"背景未在本 Scene 出现");
                break;
            case "wait_fade":
                keys(s,"type","action");
                require(bg,"纯对白模式不能编排过渡");
                return;
            default: throw new JSONException("未知演出动作");
        }
        if (s.has("fade")) require(number(s,"fade")>=0,"fade 需要非负秒数");
    }
    private static boolean contains(JSONArray a,String value) {
        if (a!=null) for (int i=0;i<a.length();i++) if (value.equals(a.opt(i))) return true;
        return false;
    }
    private static double number(JSONObject o,String key) throws JSONException {
        Object n=o.get(key);
        require(n instanceof Number && Double.isFinite(((Number)n).doubleValue()),key+" 需要数字");
        return ((Number)n).doubleValue();
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
