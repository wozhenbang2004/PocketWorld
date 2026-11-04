package dev.pocketengine.store;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Result-image retention metadata. TaskStore remains the sole owner of policy and image references. */
public final class TaskScreenshotPolicy {
    public static final long DAY=86_400_000L;
    private TaskScreenshotPolicy() { }
    public static JSONObject initial()throws Exception {
        return new JSONObject().put("format","pocketworld.task-screenshot-policy.v1").put("revision",1L)
                .put("retentionMillis",DAY).put("changedAt",0L).put("appliesTo","future_results")
                .put("legacyPolicy","keep_current_until_owner_deletes_or_replaces").put("encryption","android_keystore_aes_gcm");
    }
    public static JSONObject validate(JSONObject input)throws Exception {
        if(input==null||!"pocketworld.task-screenshot-policy.v1".equals(input.optString("format")))throw new SecurityException("截图保存策略不可核对");
        exact(input,"revision",1);exact(input,"changedAt",0);long duration=exact(input,"retentionMillis",0);
        if(duration!=0&&duration!=DAY&&duration!=7*DAY&&duration!=30*DAY)throw new SecurityException("截图留存时限不受支持");
        if(!"future_results".equals(input.optString("appliesTo"))||!"keep_current_until_owner_deletes_or_replaces".equals(input.optString("legacyPolicy"))||!"android_keystore_aes_gcm".equals(input.optString("encryption")))throw new SecurityException("截图策略不能改变原成果或取消加密");
        return new JSONObject(input.toString());
    }
    public static JSONObject change(JSONObject current,long revision,long duration,long now)throws Exception {
        JSONObject value=validate(current);if(value.getLong("revision")!=revision)throw new SecurityException("截图设置已变化，请重新查看");
        if(now<0)throw new IllegalArgumentException("Invalid screenshot clock");
        if(value.getLong("retentionMillis")==duration)return value;
        value.put("retentionMillis",duration).put("revision",Math.addExact(revision,1)).put("changedAt",Math.max(now,value.getLong("changedAt")));
        return validate(value);
    }
    public static JSONObject publicView(JSONObject value)throws Exception {
        JSONObject result=validate(value);return result.put("newCapturesEnabled",result.getLong("retentionMillis")>0)
                .put("retentionOptionsMillis",new JSONArray().put(0L).put(DAY).put(7*DAY).put(30*DAY));
    }
    public static JSONObject record(String task,String run,int epoch,String image,long now,long expiry,int bytes,String mime,boolean legacy)throws Exception {
        JSONObject value=new JSONObject().put("format","pocketworld.task-screenshot.v1").put("taskId",task).put("runId",run).put("epoch",epoch)
                .put("imageId",image).put("createdAt",now).put("expiresAt",expiry).put("bytes",bytes).put("mimeType",mime)
                .put("encrypted",true).put("legacyRetained",legacy).put("state","stored");
        aad(value);return value;
    }
    public static byte[] aad(JSONObject value)throws Exception {
        String id=value.getString("imageId");if(!UUID.fromString(id).toString().equals(id)||!"pocketworld.task-screenshot.v1".equals(value.optString("format")))throw new SecurityException("原截图身份无效");
        String task=value.getString("taskId"),run=value.getString("runId"),mime=value.getString("mimeType");
        if(task.isEmpty()||task.length()>200||run.length()>200||!mime.matches("image/(jpeg|png|webp)"))throw new SecurityException("原截图范围无效");
        long epoch=exact(value,"epoch",0),created=exact(value,"createdAt",0),expires=exact(value,"expiresAt",0),bytes=exact(value,"bytes",1);
        if(bytes>TaskScreenshotStorage.MAX_IMAGE_BYTES||expires>0&&expires<created||!Boolean.TRUE.equals(value.opt("encrypted")))throw new SecurityException("原截图密文元数据无效");
        // Stable order and immutable source/expiry fields authenticate the exact original image.
        return new JSONArray().put("pocketworld.task-screenshot.v1").put(task).put(run).put(epoch).put(id).put(created).put(expires).put(bytes).put(mime).toString().getBytes(StandardCharsets.UTF_8);
    }
    public static boolean expired(JSONObject record,long now)throws Exception {aad(record);long expiry=record.getLong("expiresAt");return expiry>0&&now>=expiry;}
    /** Structural projection only. A storage caller must authenticate these bytes before deletion. */
    static JSONObject metadataFromAad(byte[] aad)throws Exception {
        JSONArray fields=new JSONArray(new String(aad,StandardCharsets.UTF_8));if(fields.length()!=9)throw new SecurityException("截图原头信息不完整");
        for(int at:new int[]{0,1,2,4,8})if(!(fields.get(at) instanceof String))throw new SecurityException("截图原头身份格式无效");
        for(int at:new int[]{3,5,6,7})if(!(fields.get(at) instanceof Integer||fields.get(at) instanceof Long))throw new SecurityException("截图原头数字格式无效");
        if(!"pocketworld.task-screenshot.v1".equals(fields.getString(0))||fields.getLong(3)>Integer.MAX_VALUE||fields.getLong(7)>Integer.MAX_VALUE)throw new SecurityException("截图原头范围无效");
        JSONObject value=record(fields.getString(1),fields.getString(2),fields.getInt(3),fields.getString(4),fields.getLong(5),fields.getLong(6),fields.getInt(7),fields.getString(8),fields.getLong(6)==0);
        if(!java.util.Arrays.equals(aad,aad(value)))throw new SecurityException("截图原头编码不可核对");return value;
    }
    public static String mimeType(byte[] bytes) {
        if(bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return "image/jpeg";
        if(bytes.length>=8&&java.util.Arrays.equals(java.util.Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return "image/png";
        if(bytes.length>=12&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        throw new SecurityException("结果截图不是受支持的图片字节，未保存");
    }
    private static long exact(JSONObject value,String key,long minimum)throws Exception {
        Object raw=value.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<minimum)throw new SecurityException("Invalid screenshot "+key);return ((Number)raw).longValue();
    }
}
