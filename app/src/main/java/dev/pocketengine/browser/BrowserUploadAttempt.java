package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable facts for one owner-picked or exact-artifact handoff; never a replay mechanism. */
public final class BrowserUploadAttempt {
    private BrowserUploadAttempt() { }
    public static JSONObject create(JSONObject identity,JSONArray attachments)throws Exception {
        JSONObject pin=new JSONObject();
        for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId","chooserId","destinationDigest","destinationUrl","destinationOrigin"})pin.put(key,identity.get(key));
        if(identity.has("operationId")){BrowserTaskSpace.uuid(identity.getString("operationId"));pin.put("operationId",identity.getString("operationId")).put("fileInput",new JSONObject(identity.getJSONObject("fileInput").toString()));}
        for(String key:new String[]{"taskId","runId","browserSessionId","browserSpaceId","tabId","documentId","chooserId"})BrowserTaskSpace.uuid(pin.getString(key));
        if(pin.getInt("epoch")<1||pin.getLong("profileRevision")<1||pin.getLong("navigation")<0||!pin.getString("destinationDigest").matches("[a-f0-9]{64}"))throw new SecurityException("上传尝试身份无效");
        if(attachments.length()<1||attachments.length()>10)throw new SecurityException("需要原选择的有界附件版本");
        JSONArray files=new JSONArray();for(int i=0;i<attachments.length();i++){JSONObject a=attachments.getJSONObject(i);BrowserTaskSpace.uuid(a.getString("id"));if(!a.getString("sha256").matches("[a-f0-9]{64}")||a.getLong("size")<0)throw new SecurityException("附件版本不可核对");JSONObject file=new JSONObject().put("artifactId",a.getString("id")).put("store",a.optString("store","attachments")).put("sha256",a.getString("sha256")).put("size",a.getLong("size")).put("name",a.getString("name"));if(file.getString("store").equals("attachments"))file.put("attachmentId",a.getString("id"));else if(file.getString("store").equals("workspace")){BrowserTaskSpace.uuid(a.getString("sourceRevisionId"));if(a.getInt("sourceVersion")<1)throw new SecurityException("工作区成果版本无效");file.put("sourceRevisionId",a.getString("sourceRevisionId")).put("sourceVersion",a.getInt("sourceVersion")).put("projectId",a.getString("projectId"));}else throw new SecurityException("未知上传成果目录");files.put(file);}
        pin.put("attachments",files);return new JSONObject().put("attemptId",pin.getString("chooserId")).put("pin",pin).put("fingerprint",dev.pocketengine.store.TaskStore.capabilityFingerprint(pin)).put("deliveryState","prepared").put("siteState","unknown").put("cancelRequested",false).put("automaticReplayAllowed",false).put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis());
    }
    /** Must run inside the original TaskStore lock immediately before beginBrowserUpload. */
    public static void requireNewArtifactAttempt(JSONArray prior,JSONObject identity,JSONObject artifact)throws Exception {
        BrowserTaskSpace.uuid(identity.getString("operationId"));
        for(int i=0;prior!=null&&i<prior.length();i++){
            JSONObject row=prior.getJSONObject(i),pin=row.getJSONObject("pin");
            if(identity.getString("operationId").equals(pin.optString("operationId")))throw new SecurityException("原上传operation已有记录，请核对原尝试，不会再次派发");
            if(!row.optString("deliveryState").matches("delivery_unknown|local_delivered")||row.optString("siteState").matches("accepted|rejected")
                    ||!identity.getString("profileId").equals(pin.optString("profileId"))||!identity.getString("destinationOrigin").equals(pin.optString("destinationOrigin")))continue;
            JSONArray files=pin.getJSONArray("attachments");
            for(int j=0;j<files.length();j++){JSONObject file=files.getJSONObject(j);
                if(artifact.getString("store").equals(file.optString("store","attachments"))&&artifact.getString("id").equals(file.optString("artifactId",file.optString("attachmentId")))&&artifact.getString("sha256").equals(file.optString("sha256")))
                    throw new SecurityException("这个原件对该站点已有未核实交付，须主人先核对；不会换operation自动重传");
            }
        }
    }
    public static JSONObject transition(JSONObject original,String state,JSONObject evidence)throws Exception {
        if(!Set.of("delivery_entered","local_delivered","cancelled_before_delivery","cancel_requested","site_accepted","site_rejected","site_unknown").contains(state))throw new IllegalArgumentException("未知上传结果");
        JSONObject out=new JSONObject(original.toString());String old=out.getString("deliveryState");
        if(state.equals("delivery_entered")){if(!old.equals("prepared"))throw new SecurityException("同一上传尝试不能再次派发");out.put("deliveryState","delivery_unknown");}
        else if(state.equals("local_delivered")){if(!old.equals("delivery_unknown"))throw new SecurityException("本地交付不是本次已进入的尝试");out.put("deliveryState","local_delivered");}
        else if(state.equals("cancelled_before_delivery")){if(!old.equals("prepared"))throw new SecurityException("已进入的上传不能声明未交付");out.put("deliveryState","not_delivered");}
        else if(state.equals("cancel_requested"))out.put("cancelRequested",true).put("abortConfirmed",false);
        else {if(!state.equals("site_unknown")&&!old.matches("delivery_unknown|local_delivered"))throw new SecurityException("未进入本次文件交付，不能登记为站点已接收或拒绝");if(out.getString("siteState").matches("accepted|rejected"))return out;if(!state.equals("site_unknown")&&(!evidence.optString("source").equals("owner_confirmation")||evidence.optString("note").isBlank()))throw new SecurityException("站点结果需要主人提供明确核对证据");out.put("siteState",state.substring(5));}
        return out.put("lastEvent",state).put("evidence",new JSONObject(evidence.toString())).put("updatedAt",System.currentTimeMillis()).put("automaticReplayAllowed",false);
    }
}
