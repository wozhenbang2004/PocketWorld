package dev.pocketengine.store;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure retained-result shape rules. This class grants no execution or storage authority. */
public final class OwnerWorkResultProtocol {
    public static final int ORDINARY_RESULT_LIMIT=128;
    public static final String CAPACITY_RECOVERY="capacity_recovery";
    public static final String CAPACITY_CANCEL="capacity_cancel";

    private OwnerWorkResultProtocol() { }

    public static boolean isCapacityNoticeKind(String kind){return CAPACITY_RECOVERY.equals(kind)||CAPACITY_CANCEL.equals(kind);}

    /** Two fixed lifecycle slots, never another ordinary-result quota.
     * Reads only; callers retain ownership and synchronization of the supplied array. */
    public static int countOrdinaryResults(JSONArray entries)throws Exception {
        int ordinary=0,recovery=0,cancel=0;
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);String kind=entry.optString("noticeKind");
            if(CAPACITY_RECOVERY.equals(kind))recovery++;
            else if(CAPACITY_CANCEL.equals(kind))cancel++;
            else {if(entry.has("noticeKind"))throw new IllegalStateException("原工作通知类型不可核对，未覆盖已有结果");ordinary++;}
        }
        if(ordinary>ORDINARY_RESULT_LIMIT||recovery>1||cancel>1)throw new IllegalStateException("原工作结果容量不可核对，未覆盖已有结果");
        return ordinary;
    }
}
