package dev.pocketengine.features.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** One exact retained Work-output range. This locator never contains quoted text or grants. */
public record OwnerRetainedWorkSpan(String conversationId,String messageId,long sourceRevision,
        String taskId,String runId,int epoch,String deliveryId,String resultDigest,
        int summaryChars,int offset,int chars,String spanDigest) {
    public static final String FIELD="ownerRetainedWorkSpan";
    public static final String DISPLAY_FIELD="hasRetainedWorkSpan";
    private static final String FORMAT="pocketworld.owner-retained-work-span.v1";
    public static final int MAX_CHARS=4000;

    /** In-process owner visit only. The mailbox transaction and navigation race for
     * one decision. CLAIMED is never proof that the SQLite commit succeeded. */
    public static final class SendVisit {
        private static final int OPEN=0, RETIRED=1, CLAIMED=2;
        public final String id;
        private final Object store;
        private final java.util.function.BooleanSupplier nativeEpoch;
        private final java.util.concurrent.atomic.AtomicInteger decision=new java.util.concurrent.atomic.AtomicInteger();
        public SendVisit(String id,Object store,java.util.function.BooleanSupplier nativeEpoch){
            if(!OwnerRetainedWorkSpan.id(id))throw new SecurityException("原选段发送窗口无效");
            this.id=id;this.store=java.util.Objects.requireNonNull(store);this.nativeEpoch=java.util.Objects.requireNonNull(nativeEpoch);
        }
        public boolean pending(Object currentStore){
            if(currentStore!=store||!nativeEpoch.getAsBoolean())retireWithoutClaim();
            return decision.get()==OPEN;
        }
        public void requirePending(Object currentStore){
            if(!pending(currentStore))throw new SecurityException("原选段发送窗口已关闭");
        }
        /** True only when native mailbox acceptance cannot have committed. */
        public boolean retireWithoutClaim(){
            return decision.compareAndSet(OPEN,RETIRED)||decision.get()==RETIRED;
        }
        public void claimMailbox(Object currentStore){
            requirePending(currentStore);
            if(!decision.compareAndSet(OPEN,CLAIMED))throw new SecurityException("原选段发送窗口已关闭");
        }
        public void requireQueryOnly(){decision.set(CLAIMED);}
    }
    /** The caller supplies a detached UI projection, never the original stored/executor row. */
    public static JSONObject hideInternal(JSONObject result){
        if(result==null)return null;
        java.util.ArrayDeque<Object> todo=new java.util.ArrayDeque<>();todo.add(result);
        while(!todo.isEmpty()){
            Object current=todo.removeLast();
            if(current instanceof JSONObject row){
                row.remove(FIELD);
                for(var keys=row.keys();keys.hasNext();){
                    Object value=row.opt(keys.next());if(value instanceof JSONObject||value instanceof org.json.JSONArray)todo.add(value);
                }
            }else if(current instanceof org.json.JSONArray rows){
                for(int i=0;i<rows.length();i++){
                    Object value=rows.opt(i);if(value instanceof JSONObject||value instanceof org.json.JSONArray)todo.add(value);
                }
            }
        }
        return result;
    }

    public OwnerRetainedWorkSpan {
        if(conversationId==null||conversationId.isBlank()||conversationId.length()>240
                ||!id(messageId)||sourceRevision<1||!id(taskId)||!id(runId)||epoch<1||!id(deliveryId)
                ||!digest(resultDigest)||summaryChars<=24000||offset<0||chars<1||chars>MAX_CHARS
                ||(long)offset+chars>240000||(long)offset+chars>summaryChars||!digest(spanDigest))
            throw new IllegalArgumentException("原交付选段定位无效");
    }

    public JSONObject json()throws Exception {
        return new JSONObject().put("format",FORMAT).put("conversationId",conversationId).put("messageId",messageId)
                .put("sourceRevision",sourceRevision).put("taskId",taskId).put("runId",runId).put("epoch",epoch)
                .put("deliveryId",deliveryId).put("resultDigest",resultDigest).put("summaryChars",summaryChars)
                .put("offset",offset).put("chars",chars).put("spanDigest",spanDigest);
    }

    public static OwnerRetainedWorkSpan from(JSONObject row)throws Exception {
        if(row==null||row.length()!=13||!FORMAT.equals(row.opt("format")))throw new IllegalArgumentException("不是已核对的原交付选段");
        return new OwnerRetainedWorkSpan(string(row,"conversationId"),string(row,"messageId"),integer(row,"sourceRevision",Long.MAX_VALUE),
                string(row,"taskId"),string(row,"runId"),(int)integer(row,"epoch",Integer.MAX_VALUE),string(row,"deliveryId"),
                string(row,"resultDigest"),(int)integer(row,"summaryChars",Integer.MAX_VALUE),(int)integer(row,"offset",239999),
                (int)integer(row,"chars",MAX_CHARS),string(row,"spanDigest"));
    }

    public static OwnerRetainedWorkSpan in(JSONObject parent)throws Exception {
        return parent.has(FIELD)?from(parent.getJSONObject(FIELD)):null;
    }

    public static String textDigest(String value)throws Exception {
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw new IllegalArgumentException("原交付选段字符不完整");
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder valueHex=new StringBuilder(64);for(byte b:bytes)valueHex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return valueHex.toString();
    }

    private static boolean id(String value){return value!=null&&value.matches("[a-f0-9-]{36}");}
    private static boolean digest(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static String string(JSONObject row,String name){
        Object value=row.opt(name);if(!(value instanceof String text))throw new IllegalArgumentException("原交付选段标识无效");return text;
    }
    private static long integer(JSONObject row,String name,long maximum){
        Object value=row.opt(name);if(!(value instanceof Integer||value instanceof Long))throw new IllegalArgumentException("原交付选段范围无效");
        long result=((Number)value).longValue();if(result<0||result>maximum)throw new IllegalArgumentException("原交付选段范围无效");return result;
    }
}
