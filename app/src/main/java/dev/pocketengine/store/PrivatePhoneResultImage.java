package dev.pocketengine.store;

import java.util.regex.Pattern;
import org.json.JSONObject;

/** Identity of an already decrypted result held by the PRIVATE task reader. It never reads a file,
 * captures a screen or authorizes execution. No image data is put in this key or its descriptor. */
public record PrivatePhoneResultImage(String taskId,String runId,int epoch,long revision,String digest,
        String imageId,String sha,String file,String mime,int bytes,long expires) {
    private static final Pattern HASH=Pattern.compile("[0-9a-f]{64}"), UUID=Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static boolean hash(Object s){return s instanceof String value && HASH.matcher(value).matches();}
    private static long number(Object n){return n instanceof Integer||n instanceof Long?((Number)n).longValue():-1;}

    public static PrivatePhoneResultImage cached(JSONObject raw,long now) {
        if(raw==null||!PhoneActionRecovery.local(raw)||!hash(raw.opt("resultImageDigest"))||number(raw.opt("resultRevision"))<1)return null;
        JSONObject ev=raw.optJSONObject("evidence"), record=ev==null?null:ev.optJSONObject("screenshot");
        if(record==null||!"available".equals(record.opt("readState"))||!hash(ev.opt("imageSha256"))||!(ev.opt("imageDataUrl") instanceof String data))return null;
        long length=number(record.opt("bytes")),epoch=number(raw.opt("coordinatorEpoch"));
        if(length<1||length>TaskScreenshotStorage.MAX_IMAGE_BYTES||epoch<1||epoch>Integer.MAX_VALUE)return null;
        if(!(raw.opt("id") instanceof String task)||task.isBlank()||task.length()>200||!(raw.opt("runId") instanceof String run)||run.isBlank()||run.length()>200||
                !(record.opt("imageId") instanceof String id)||!UUID.matcher(id).matches()||!(record.opt("mimeType") instanceof String mime)||!mime.matches("image/(jpeg|png|webp)")||
                !(ev.opt("imageFile") instanceof String file)||file.isBlank()||file.length()>300)return null;
        String prefix="data:"+mime+";base64,";
        if(data.length()!=prefix.length()+4*((length+2)/3)||!data.startsWith(prefix))return null; // Android Base64.NO_WRAP from TaskStore.copy
        var key=new PrivatePhoneResultImage(task,run,(int)epoch,number(raw.opt("resultRevision")),
                raw.optString("resultImageDigest"),id,ev.optString("imageSha256"),file,mime,(int)length,number(record.opt("expiresAt")));
        return key.matchesSource(raw,now)?key:null;
    }

    private boolean matchesSource(JSONObject raw,long now) {
        if(raw==null||expires<=now||!PhoneActionRecovery.local(raw)||!taskId.equals(raw.opt("id"))||!runId.equals(raw.opt("runId"))||
                epoch!=number(raw.opt("coordinatorEpoch"))||revision!=number(raw.opt("resultRevision")))return false;
        JSONObject ev=raw.optJSONObject("evidence"),r=ev==null?null:ev.optJSONObject("screenshot");
        return r!=null&&"pocketworld.task-screenshot.v1".equals(r.opt("format"))&&taskId.equals(r.opt("taskId"))&&runId.equals(r.opt("runId"))&&
                epoch==number(r.opt("epoch"))&&imageId.equals(r.opt("imageId"))&&"stored".equals(r.opt("state"))&&Boolean.TRUE.equals(r.opt("encrypted"))&&
                Boolean.FALSE.equals(r.opt("legacyRetained"))&&!r.optBoolean("retainedFromPreviousResult")&&!r.optBoolean("deletionPending")&&r.isNull("remotePhoneSnapshot")&&
                bytes==number(r.opt("bytes"))&&mime.equals(r.opt("mimeType"))&&expires==number(r.opt("expiresAt"))&&
                sha.equals(ev.opt("imageSha256"))&&file.equals(ev.opt("imageFile"));
    }

    /** Check the original database row and the independently published result identity, not a UI's claim. */
    boolean stillSaved(JSONObject raw,JSONObject header,long now) {
        JSONObject ev=header.optJSONObject("evidence");
        return matchesSource(raw,now)&&taskId.equals(header.opt("id"))&&runId.equals(header.opt("runId"))&&epoch==number(header.opt("coordinatorEpoch"))&&
                revision==number(header.opt("resultRevision"))&&digest.equals(header.opt("resultImageDigest"))&&ev!=null&&
                sha.equals(ev.opt("imageSha256"))&&file.equals(ev.opt("imageFile"));
    }
    public JSONObject descriptor() throws Exception {
        return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("resultRevision",revision)
                .put("resultImageDigest",digest).put("imageId",imageId).put("imageSha256",sha).put("expiresAt",expires)
                .put("mimeType",mime).put("bytes",bytes);
    }
    public boolean requested(JSONObject input) {
        return taskId.equals(input.opt("taskId"))&&runId.equals(input.opt("runId"))&&epoch==number(input.opt("epoch"))&&
                revision==number(input.opt("resultRevision"))&&digest.equals(input.opt("resultImageDigest"))&&
                imageId.equals(input.opt("imageId"))&&sha.equals(input.opt("imageSha256"));
    }
    public JSONObject resultFrom(JSONObject stillHeld,long now) throws Exception {
        if(!equals(cached(stillHeld,now)))throw new SecurityException("原任务这份已读取的截图已变化，没有显示替代图。");
        return descriptor().put("readOnly",true).put("executionAuthorized",false).put("liveScreen",false)
                .put("imageDataUrl",stillHeld.getJSONObject("evidence").getString("imageDataUrl"));
    }
}
