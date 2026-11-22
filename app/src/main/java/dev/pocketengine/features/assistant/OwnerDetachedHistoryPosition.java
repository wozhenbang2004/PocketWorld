package dev.pocketengine.features.assistant;

import java.util.UUID;
import org.json.JSONObject;

/** Body-free, local viewport metadata. It cannot authorize history, a reference, or a send. */
public record OwnerDetachedHistoryPosition(String conversationId,String generation,String messageId,
        long recordVersion,String sha256,int offsetPx,long savedAtMs) {
    private static final long MAX_AGE_MS=30L*24*60*60*1000;
    private static final long MAX_SAFE_INTEGER=9_007_199_254_740_991L;
    public static final int MAX_OFFSET=1_000_000;

    public OwnerDetachedHistoryPosition {
        if(!nativeId(conversationId)||!nativeId(generation)||!nativeId(messageId)||
                recordVersion<1||recordVersion>MAX_SAFE_INTEGER||sha256==null||!sha256.matches("[a-f0-9]{64}")||
                offsetPx<0||offsetPx>MAX_OFFSET||savedAtMs<1||savedAtMs>MAX_SAFE_INTEGER)
            throw new IllegalArgumentException("原历史位置的本机身份不可核对");
    }

    public boolean fresh(long now){return now>=savedAtMs&&now-savedAtMs<MAX_AGE_MS;}
    boolean samePlace(OwnerDetachedHistoryPosition other){
        return other!=null&&conversationId.equals(other.conversationId)&&generation.equals(other.generation)&&
                messageId.equals(other.messageId)&&recordVersion==other.recordVersion&&sha256.equals(other.sha256)&&offsetPx==other.offsetPx;
    }
    public JSONObject locator()throws Exception{
        return new JSONObject().put("kind","owner_native_original").put("conversationId",conversationId).put("generation",generation)
                .put("recordId",messageId).put("recordVersion",recordVersion).put("fingerprint","sha256:"+sha256);
    }
    JSONObject json()throws Exception{
        return new JSONObject().put("conversationId",conversationId).put("generation",generation).put("messageId",messageId)
                .put("recordVersion",recordVersion).put("sha256",sha256).put("offsetPx",offsetPx).put("savedAtMs",savedAtMs);
    }
    static OwnerDetachedHistoryPosition fromJson(JSONObject row)throws Exception{
        if(row.length()!=7)throw new IllegalArgumentException("原历史位置只保留定位");
        return new OwnerDetachedHistoryPosition(string(row,"conversationId"),string(row,"generation"),string(row,"messageId"),
                integer(row,"recordVersion"),string(row,"sha256"),Math.toIntExact(integer(row,"offsetPx")),integer(row,"savedAtMs"));
    }
    private static boolean nativeId(String value){
        if(value==null||value.length()!=36)return false;
        try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException invalid){return false;}
    }
    private static String string(JSONObject row,String field)throws Exception{
        if(row.get(field) instanceof String text)return text;throw new IllegalArgumentException(field);
    }
    private static long integer(JSONObject row,String field)throws Exception{
        Object raw=row.get(field);if(raw instanceof Integer||raw instanceof Long)return ((Number)raw).longValue();
        throw new IllegalArgumentException(field);
    }
}
