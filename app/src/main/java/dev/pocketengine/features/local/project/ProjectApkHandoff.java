package dev.pocketengine.features.local.project;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import org.json.JSONObject;

/** Only the original owner system-handoff reference and its preceding receipt, not an install
 * queue or TaskStore. A durable unknown reference can never authorize another launch. */
final class ProjectApkHandoff {
    private static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    ProjectApkHandoff(Context context){prefs=context.getSharedPreferences("owner_project_apk_handoff",Context.MODE_PRIVATE);}
    JSONObject current() throws Exception {synchronized(LOCK){return read("current");}}
    JSONObject find(String id) throws Exception {synchronized(LOCK){JSONObject value=read("current");if(value!=null&&id.equals(value.optString("requestId")))return value;value=read("previous");return value!=null&&id.equals(value.optString("requestId"))?value:null;}}
    JSONObject begin(JSONObject review,String uri) throws Exception {synchronized(LOCK){
        JSONObject old=read("current");
        if(old!=null&&"awaiting_system".equals(old.optString("state")))throw new IllegalStateException("原安装交接结果未确认，请先核对或明确结束等待；没有再次提交");
        int requestCode=prefs.getInt("lastRequestCode",19999)+1;
        if(requestCode>65534)throw new IllegalStateException("本机安装交接编号已用尽；未复用旧结果编号或重投");
        JSONObject value=new JSONObject().put("requestId",UUID.randomUUID().toString()).put("requestCode",requestCode).put("review",new JSONObject(review.toString()))
                .put("viewUri",uri).put("state","awaiting_system").put("createdAt",System.currentTimeMillis());
        SharedPreferences.Editor edit=prefs.edit().putInt("lastRequestCode",requestCode).putString("current",value.toString());
        if(old!=null)edit.putString("previous",old.toString());
        if(!edit.commit())throw new IllegalStateException("未能持久保存原交接引用，未请求系统安装");
        return value;
    }}
    JSONObject update(String id,String state,JSONObject observation,Integer result) throws Exception {synchronized(LOCK){
        String key="current";JSONObject value=read(key);
        if(value==null||!id.equals(value.optString("requestId"))){key="previous";value=read(key);}
        if(value==null||!id.equals(value.optString("requestId")))throw new IllegalStateException("原安装引用已不在当前保留窗口；未写入其他安装回执");
        if("owner_closed_unknown".equals(state)&&!"awaiting_system".equals(value.optString("state")))return value;
        // A late read cannot erase an already-returned system result or reopen a closed wait.
        if(state!=null&&(!"awaiting_system".equals(state)||"awaiting_system".equals(value.optString("state"))))value.put("state",state);
        if(observation!=null)value.put("observation",observation).put("observedAt",System.currentTimeMillis());
        if(result!=null){value.put("systemResultCode",result);value.remove("observation");value.remove("observedAt");}
        if(!prefs.edit().putString(key,value.toString()).commit())throw new IllegalStateException("原安装回执未能保存，请核对系统安装状态；不会再次提交");
        return value;
    }}
    JSONObject observe(JSONObject original,JSONObject observation) throws Exception {synchronized(LOCK){
        JSONObject latest=find(original.getString("requestId"));
        if(latest==null)throw new IllegalStateException("原交接引用已不在当前保留窗口；没有核对其他安装");
        // The package read may have raced a system result or an explicit close in another window.
        // Do not attach a pre-result observation to that newer receipt.
        if(!latest.getString("state").equals(original.getString("state"))||
                !java.util.Objects.equals(latest.opt("systemResultCode"),original.opt("systemResultCode")))return latest;
        return update(original.getString("requestId"),null,observation,null);
    }}
    private JSONObject read(String key) throws Exception {
        String text=prefs.getString(key,null);if(text==null)return null;
        if(text.length()>32768)throw new IllegalStateException("原安装引用超出可恢复范围；未清空或重投");
        JSONObject value=new JSONObject(text),review=value.getJSONObject("review");
        if(!value.getString("requestId").matches("[a-f0-9-]{36}")||!review.getString("artifactSha256").matches("[a-f0-9]{64}")||
                !review.getString("packageName").matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")||review.getLong("versionCode")<0)
            throw new IllegalStateException("原安装引用不可核对；未清空或重新提交");
        return value;
    }
}
