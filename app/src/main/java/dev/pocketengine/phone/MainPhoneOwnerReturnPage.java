package dev.pocketengine.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** One already-required post-return read. Only a complete structural page from the original
 * active application/window and session may leave the phone; never guess from a requested app. */
public final class MainPhoneOwnerReturnPage {
    private final String id,run,scope,operation;private final int epoch;private final JSONObject proof,refusal;
    private MainPhoneOwnerReturnPage(String id,String run,int epoch,JSONObject proof,JSONObject receipt)throws Exception {
        this.id=id;this.run=run;this.epoch=epoch;this.proof=new JSONObject(proof.toString());this.refusal=new JSONObject(receipt.toString());
        scope=proof.getString("observedSurfaceKey");operation=proof.getString("operationId");
    }
    public static MainPhoneOwnerReturnPage capture(String id,String run,int epoch,JSONObject evidence)throws Exception {
        JSONObject p=evidence.optJSONObject(MainPhoneOwnerStep.KEY),r=evidence.optJSONObject("nativeSensitiveStep");
        if(p==null||!id.equals(p.opt("taskId"))||!run.equals(p.opt("runId"))||epoch!=p.optInt("epoch",-1)||!p.optString("operationId").matches("[a-fA-F0-9-]{36}")||
                !p.optString("observedSurfaceKey").matches("[0-9a-f]{64}")||MainPhoneOwnerStep.reason(r).isEmpty()||!p.getString("reason").equals(MainPhoneOwnerStep.reason(r)))return null;
        return new MainPhoneOwnerReturnPage(id,run,epoch,p,r);
    }
    /** Derived only from the previous accepted native observation, not a launch or operation argument. */
    public static String surfaceKey(JSONObject o,String task,String run,int epoch,long pause)throws Exception {
        if(o==null||!Boolean.TRUE.equals(o.opt("ok"))||o.optBoolean("changedDuringRead")||o.optInt("displayId",-1)!=0)return "";
        JSONObject binding=o.optJSONObject("observationBinding"),installed=o.optJSONObject("installedApp");String pkg=o.optString("packageName"),session=o.optString("sessionId"),obs=o.optString("observationId");
        if(binding==null||!task.equals(binding.opt("taskId"))||!run.equals(binding.opt("runId"))||epoch!=binding.optInt("epoch",-1)||binding.optInt("displayId",-1)!=0||
                session.isEmpty()||!session.equals(binding.opt("sessionId"))||obs.isEmpty()||!obs.equals(binding.opt("observationId"))||pause!=binding.optLong("pauseRevision",-1)||
                installed==null||!"android_package_manager".equals(installed.opt("source"))||!pkg.equals(installed.opt("packageName"))||pkg.length()>255||
                !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||pkg.equals("dev.pocketengine")||pkg.equals("com.android.systemui"))return "";
        boolean actual=false;JSONArray windows=o.optJSONArray("windows");
        for(int i=0;windows!=null&&i<windows.length();i++){
            JSONObject w=windows.optJSONObject(i);if(w==null)return "";
            if(!w.optBoolean("active")&&!w.optBoolean("focused"))continue;
            if(w.optInt("displayId",-1)!=0||w.optInt("type",-1)!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION||!pkg.equals(w.opt("packageName")))return "";
            actual=true;
        }
        if(!actual)return "";byte[] hash=MessageDigest.getInstance("SHA-256").digest((session+"\n"+pkg).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder(64);for(byte b:hash)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
    }
    public boolean accepts(JSONObject o,long pause)throws Exception {
        if(!scope.equals(surfaceKey(o,id,run,epoch,pause))||o.optBoolean("protected")||o.optBoolean("secure")||o.optBoolean("privateInput")||
                o.optBoolean("truncated")||Boolean.FALSE.equals(o.opt("screenShareAllowed"))||o.optJSONArray("nodes")==null||o.getJSONArray("nodes").length()==0)return false;
        String pkg=o.getString("packageName");JSONArray nodes=o.getJSONArray("nodes"),windows=o.getJSONArray("windows");
        java.util.Set<Integer> originalWindows=new java.util.HashSet<>();
        for(int i=0;i<windows.length();i++){JSONObject w=windows.getJSONObject(i);
            if(pkg.equals(w.opt("packageName"))&&w.optInt("type",-1)==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)originalWindows.add(w.optInt("windowId",-1));}
        for(int i=0;i<nodes.length();i++){JSONObject node=nodes.optJSONObject(i);
            if(node==null||!pkg.equals(node.opt("packageName"))||!originalWindows.contains(node.optInt("windowId",-2)))return false;}
        JSONObject sources=o.optJSONObject("observationSources"),coverage=o.optJSONObject("treeCoverage");
        return sources!=null&&Boolean.TRUE.equals(sources.opt("treeAdequate"))&&Boolean.FALSE.equals(sources.opt("imageIncluded"))&&
                (coverage==null||!coverage.optBoolean("bodyTruncated"));
    }
    public JSONObject blocked(long pause)throws Exception {
        return new JSONObject().put("ok",false).put("code","owner_return_page_not_verified").put("message","交还后未核对到原应用的非敏感页面。没有把这次页面交给模型，也未派发新操作。")
                .put(MainPhoneOwnerStep.KEY,new JSONObject(proof.toString())).put("nativeSensitiveStep",new JSONObject(refusal.toString()))
                .put("originalEffectsRetained",true).put("automaticReplayAllowed",false).put("mainOwnerPageCheck",new JSONObject()
                        .put("operationId",operation).put("pauseRevision",pause).put("state","not_verified").put("sharedWithModel",false));
    }
}
