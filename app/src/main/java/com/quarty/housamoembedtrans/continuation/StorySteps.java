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
                case "choice":
                    require(settings.getBoolean("allow_choices"),"未开启额外选项");
                    keys(step,"type","options");
                    JSONArray options=step.getJSONArray("options");
                    require(options.length()>0,"选项列表为空");
                    boolean all=true;
                    for (int j=0;j<options.length();j++) {
                        JSONObject item=options.getJSONObject(j);
                        keys(item,"text","steps");
                        word(item,"text");
                        all &= sequence(item.getJSONArray("steps"),settings,resources);
                    }
                    dialogue |= all;
                    break;
                default: throw new JSONException("未知 step 类型");
            }
        }
        return dialogue;
    }
    private static void action(JSONObject s,JSONObject settings,JSONObject res) throws JSONException {
        JSONObject chars=res.optJSONObject("characters"), layers=res.optJSONObject("layers");
        boolean character=settings.getBoolean("allow_character"),bg=settings.getBoolean("allow_background");
        switch (word(s,"action")) {
            case "character":
                require(character,"未允许修改角色演出");
                keys(s,"type","action","character","pattern","layer","x","y","fade");
                String key=word(s,"character");
                require(chars!=null && chars.has(key),"不可用立绘："+key);
                if (s.has("pattern")) require(contains(chars.getJSONObject(key).optJSONArray("patterns"),
                    word(s,"pattern")),"此立绘没有指定表情");
                if (s.has("layer")) require(layers!=null && layers.has(word(s,"layer")),"不可用角色图层");
                if (s.has("x")) number(s,"x");
                if (s.has("y")) number(s,"y");
                break;
            case "character_off":
                require(character,"未允许角色退场");
                keys(s,"type","action","target","fade");
                String target=word(s,"target");
                require(target.equals("all") || (chars!=null && chars.has(target)) || (layers!=null && layers.has(target)),
                    "不可用退场对象："+target);
                break;
            case "bg":
                require(bg,"未允许修改背景");
                keys(s,"type","action","bg","fade");
                require(contains(res.optJSONArray("backgrounds"),word(s,"bg")),"背景未在本 Scene 出现");
                break;
            case "wait_fade":
                keys(s,"type","action");
                require(character || bg,"纯对白模式不能编排过渡");
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
    static String word(JSONObject o,String key) throws JSONException {
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
