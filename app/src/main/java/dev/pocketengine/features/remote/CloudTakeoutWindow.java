package dev.pocketengine.features.remote;

import org.json.JSONObject;

/** Recognized retention statements only. No byte, host, account or execution authority. */
final class CloudTakeoutWindow {
    private CloudTakeoutWindow() {}
    private static final long CLOUD_TAKEOUT_MS=7L*24*60*60*1000;
    private static boolean whole(Object v){return v instanceof Long||v instanceof Integer;}
    static JSONObject policy(JSONObject observed)throws Exception{
        JSONObject p=observed.optJSONObject("takeoutPolicy");
        return p!=null&&"companion_selected_bytes_takeout_v1".equals(p.opt("protocol"))&&whole(p.opt("durationMs"))&&p.optLong("durationMs")==CLOUD_TAKEOUT_MS
            ?new JSONObject().put("protocol","companion_selected_bytes_takeout_v1").put("durationMs",CLOUD_TAKEOUT_MS):null;
    }
    /** Missing/unknown protocol offers no retention promise. Take the deadline from this exact immutable capture, not the handset clock. */
    static JSONObject receipt(JSONObject actual)throws Exception{
        JSONObject p=actual.optJSONObject("takeoutWindow");if(p==null||!"companion_selected_bytes_takeout_v1".equals(p.opt("protocol")))return null;
        Object when=actual.opt("capturedAt"),until=p.opt("normalPruneProtectedUntil");
        if(!whole(when)||!whole(until)||((Number)when).longValue()<=0||((Number)until).longValue()!=Math.addExact(((Number)when).longValue(),CLOUD_TAKEOUT_MS))
            throw new SecurityException("原冻结回执的取回期限未与本次采集时间匹配，未保存错误期限");
        return new JSONObject().put("protocol","companion_selected_bytes_takeout_v1").put("normalPruneProtectedUntil",until);
    }
}
