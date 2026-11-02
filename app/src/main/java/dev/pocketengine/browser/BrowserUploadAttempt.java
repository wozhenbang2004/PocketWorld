package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable facts for one owner-picked handoff, not an uploader or replay mechanism. */
public final class BrowserUploadAttempt {
    private BrowserUploadAttempt() { }
    public static JSONObject create(JSONObject identity,JSONArray attachments)throws Exception {
        JSONObject pin=new JSONObject();
        for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId","chooserId","destinationDigest","destinationUrl","destinationOrigin"})pin.put(key,identity.get(key));
        if(identity.has("operationId")){BrowserTaskSpace.uuid(identity.getString("operationId"));pin.put("operationId",identity.getString("operationId"));JSONObject input=identity.getJSONObject("fileInput");if(input.length()!=3||!input.getString("ref").matches("n[0-9]{1,3}")||!input.getString("nodeKey").matches("k[1-9][0-9]{0,6}"))throw new SecurityException("上传的原file input身份无效");BrowserTaskSpace.uuid(input.getString("observationId"));pin.put("fileInput",new JSONObject(input.toString()));}
        for(String key:new String[]{"taskId","runId","browserSessionId","browserSpaceId","tabId","documentId","chooserId"})BrowserTaskSpace.uuid(pin.getString(key));
        if(pin.getInt("epoch")<1||pin.getLong("profileRevision")<1||pin.getLong("navigation")<0||!pin.getString("destinationDigest").matches("[a-f0-9]{64}"))throw new SecurityException("上传尝试身份无效");
        if(attachments.length()<1||attachments.length()>10)throw new SecurityException("需要原选择的有界附件版本");
        JSONArray files=new JSONArray();for(int i=0;i<attachments.length();i++){JSONObject a=attachments.getJSONObject(i);BrowserTaskSpace.uuid(a.getString("id"));if(!a.getString("sha256").matches("[a-f0-9]{64}")||a.getLong("size")<0)throw new SecurityException("附件版本不可核对");files.put(new JSONObject().put("attachmentId",a.getString("id")).put("sha256",a.getString("sha256")).put("size",a.getLong("size")).put("name",a.getString("name")));}
        pin.put("attachments",files);return new JSONObject().put("attemptId",pin.getString("chooserId")).put("pin",pin).put("fingerprint",dev.pocketengine.store.TaskStore.capabilityFingerprint(pin)).put("deliveryState","prepared").put("siteState","unknown").put("cancelRequested",false).put("automaticReplayAllowed",false).put("createdAt",System.currentTimeMillis()).put("updatedAt",System.currentTimeMillis());
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
