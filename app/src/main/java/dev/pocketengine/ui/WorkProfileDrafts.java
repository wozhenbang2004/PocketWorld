package dev.pocketengine.ui;

import dev.pocketengine.features.controlplane.WorkProfile;
import dev.pocketengine.runtime.models.ModelTargetRef;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stateless owner-editor projection only. Nothing here installs, enables, grants, or starts a run. */
public final class WorkProfileDrafts {
    private WorkProfileDrafts() { }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject fields(JSONObject row,String... fields)throws Exception{JSONObject ref=new JSONObject();for(String field:fields)ref.put(field,row.get(field));return ref;}
    public static JSONObject skillReference(JSONObject row)throws Exception{return fields(row,"id","revision","digest").put("optional",false);}
    public static JSONObject extensionReference(JSONObject row)throws Exception{return fields(row,"id","installationId","version","approvalRevision","sourceSha256").put("optional",false);}
    public static JSONObject withModelConcurrency(JSONObject definition,Integer requested)throws Exception{
        if(requested!=null&&(requested<1||requested>2))throw new IllegalArgumentException("本任务模型并发只可请求1或2");JSONObject next=copy(definition);JSONObject resources=next.getJSONObject("resourcePreferences");if(requested==null)resources.remove("maxConcurrentModelCalls");else resources.put("maxConcurrentModelCalls",requested);return WorkProfile.fromJson(next).toJson();
    }
    public static boolean changed(JSONObject definition,JSONObject saved,String name)throws Exception{return saved==null||!WorkProfile.fingerprint(copy(definition).put("name",name)).equals(WorkProfile.fingerprint(saved));}
    public static boolean originalOwnerTask(JSONObject task){return "assistant".equals(task.optString("kind"))&&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))&&(!task.has("origin")||task.isNull("origin"))&&!task.has("sourceLearningJob")&&!task.has("teamAssignment")&&!task.has("teamWriteScope")&&(task.optJSONObject("workProfileRef")!=null||task.optJSONObject("workProfileIntent")!=null);}
    public static JSONObject withModel(JSONObject definition,String purpose,JSONObject reference)throws Exception{
        if(!java.util.Set.of("MAIN","SUMMARY","RELAY").contains(purpose))throw new IllegalArgumentException("本入口仅支持主任务、摘要与明确的网关模型");JSONObject next=copy(definition);next.getJSONObject("modelPolicies").put(purpose,ModelTargetRef.fromJson(reference).toJson());return WorkProfile.fromJson(next).toJson();
    }
    public static JSONObject withoutRelayModel(JSONObject definition)throws Exception {JSONObject next=copy(definition);next.getJSONObject("modelPolicies").remove("RELAY");return WorkProfile.fromJson(next).toJson();}
    /** Owner choices only. Account plans remain app-only; groups are rechecked for EXTERNAL_RELAY at preflight. */
    public static JSONArray modelChoices(JSONObject choices,String purpose)throws Exception {
        if(!java.util.Set.of("MAIN","SUMMARY","RELAY").contains(purpose))throw new IllegalArgumentException("本入口不支持此模型用途");
        JSONArray all=modelChoices(choices);if(!purpose.equals("RELAY"))return all;JSONArray selected=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i),ref=row.getJSONObject("reference");if(!ref.optString("connectionId").startsWith("account:"))selected.put(row);}return selected;
    }
    public static boolean contains(JSONObject definition,String field,String id)throws Exception{JSONArray refs=definition.getJSONArray(field);for(int i=0;i<refs.length();i++)if(id.equals(refs.getJSONObject(i).getString("id")))return true;return false;}
    public static boolean matchesReference(JSONObject definition,String field,JSONObject reference)throws Exception {JSONArray refs=definition.getJSONArray(field);for(int i=0;i<refs.length();i++){JSONObject ref=copy(refs.getJSONObject(i));if(reference.getString("id").equals(ref.getString("id"))){ref.remove("optional");JSONObject expected=copy(reference);expected.remove("optional");return WorkProfile.fingerprint(ref).equals(WorkProfile.fingerprint(expected));}}return false;}
    public static JSONObject withoutReference(JSONObject definition,String field,String id)throws Exception {if(!java.util.Set.of("instructionRefs","skillRefs","extensionRefs","pluginRefs").contains(field))throw new IllegalArgumentException("原引用类型不可编辑");JSONObject next=copy(definition);JSONArray refs=next.getJSONArray(field),kept=new JSONArray();for(int i=0;i<refs.length();i++)if(!id.equals(refs.getJSONObject(i).getString("id")))kept.put(refs.getJSONObject(i));next.put(field,kept);return WorkProfile.fromJson(next).toJson();}
    private static boolean pluginBindingMatches(JSONObject ref,JSONObject row)throws Exception {return WorkProfile.fingerprint(fields(ref,"id","installationId","stateRevision","manifestDigest")).equals(WorkProfile.fingerprint(fields(row,"id","installationId","stateRevision","manifestDigest")));}
    public static boolean hasPluginTool(JSONObject definition,JSONObject row,String tool)throws Exception {JSONArray refs=definition.getJSONArray("pluginRefs");for(int i=0;i<refs.length();i++)if(row.getString("id").equals(refs.getJSONObject(i).getString("id"))&&!pluginBindingMatches(refs.getJSONObject(i),row))return false;return hasPluginTool(definition,row.getString("id"),tool);}
    public static JSONObject withReference(JSONObject definition,String field,JSONObject reference,boolean selected)throws Exception{
        if(!java.util.Set.of("instructionRefs","skillRefs","extensionRefs").contains(field))throw new IllegalArgumentException("原引用类型不可编辑");JSONObject next=copy(definition);JSONArray refs=next.getJSONArray(field),kept=new JSONArray();String id=reference.getString("id");for(int i=0;i<refs.length();i++)if(!id.equals(refs.getJSONObject(i).getString("id")))kept.put(refs.getJSONObject(i));if(selected)kept.put(copy(reference));next.put(field,kept);return WorkProfile.fromJson(next).toJson();
    }
    public static boolean hasPluginTool(JSONObject definition,String id,String tool)throws Exception{JSONArray refs=definition.getJSONArray("pluginRefs");for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(id.equals(ref.getString("id"))){JSONArray tools=ref.getJSONArray("tools");for(int n=0;n<tools.length();n++)if(tool.equals(tools.getString(n)))return true;}}return false;}
    public static JSONObject withPluginTool(JSONObject definition,JSONObject nativeRow,String tool,boolean selected)throws Exception{
        boolean known=false;JSONArray offered=nativeRow.getJSONArray("tools");for(int i=0;i<offered.length();i++){JSONObject row=offered.getJSONObject(i);if(tool.equals(row.getString("name"))&&row.getBoolean("granted"))known=true;}if(selected&&(!nativeRow.getBoolean("enabled")||!known))throw new SecurityException("请先在原插件页启用并审阅此工具权限");
        JSONObject next=copy(definition);JSONArray refs=next.getJSONArray("pluginRefs"),kept=new JSONArray(),tools=new JSONArray();String id=nativeRow.getString("id");boolean optional=false;for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(!id.equals(ref.getString("id")))kept.put(ref);else{optional=ref.getBoolean("optional");if(pluginBindingMatches(ref,nativeRow)){JSONArray old=ref.getJSONArray("tools");for(int n=0;n<old.length();n++)if(!tool.equals(old.getString(n)))tools.put(old.getString(n));}}}if(selected)tools.put(tool);if(tools.length()>0)kept.put(fields(nativeRow,"id","installationId","stateRevision","manifestDigest").put("tools",tools).put("optional",optional));next.put("pluginRefs",kept);return WorkProfile.fromJson(next).toJson();
    }
    public static JSONObject withOptional(JSONObject definition,String field,String id,boolean optional)throws Exception{
        if(!java.util.Set.of("instructionRefs","skillRefs","extensionRefs","pluginRefs").contains(field))throw new IllegalArgumentException("原引用类型不可编辑");JSONObject next=copy(definition);JSONArray refs=next.getJSONArray(field);boolean found=false;for(int i=0;i<refs.length();i++)if(id.equals(refs.getJSONObject(i).getString("id"))){refs.getJSONObject(i).put("optional",optional);found=true;}if(!found)throw new IllegalArgumentException("原选用已变化");return WorkProfile.fromJson(next).toJson();
    }
    public static JSONArray modelChoices(JSONObject choices)throws Exception{
        JSONArray out=new JSONArray();JSONObject sections=choices.optJSONObject("sections");if(sections==null)return out;JSONObject models=sections.optJSONObject("models");if(models!=null&&models.optBoolean("available")){
            JSONObject data=models;JSONArray api=data.getJSONObject("api").getJSONArray("connections");for(int i=0;i<api.length();i++){JSONObject row=api.getJSONObject(i);if(row.getBoolean("enabled")&&!row.getString("model").isBlank())out.put(new JSONObject().put("label",row.getString("name")).put("detail",row.getString("model")).put("reference",ModelTargetRef.exact(row.getString("id"),row.getString("model")).toJson()));}
            JSONArray groups=data.getJSONObject("groups").getJSONArray("groups");for(int i=0;i<groups.length();i++){JSONObject row=groups.getJSONObject(i);out.put(new JSONObject().put("label",row.getString("name")).put("detail","原模型组 · 修订 "+row.getLong("revision")).put("reference",ModelTargetRef.group(row.getString("groupId"),row.getLong("revision")).toJson()));}
        }
        JSONObject accounts=sections.optJSONObject("accounts");if(accounts!=null&&accounts.optBoolean("available")){JSONObject data=accounts,ref=data.optJSONObject("selectedModelRef");if(ref!=null&&data.optBoolean("selectedModelUsable"))out.put(new JSONObject().put("label","原账号页所选模型").put("detail",ref.getString("modelId")).put("reference",ModelTargetRef.exact("account:"+ref.getString("accountId"),ref.getString("modelId")).toJson()));}return out;
    }
    public static boolean runnable(JSONObject review)throws Exception{
        if(!review.optBoolean("ready"))return false;JSONObject policies=review.getJSONObject("modelPolicies");if(!policies.has("MAIN")||!policies.has("SUMMARY"))return false;var names=policies.keys();while(names.hasNext())if(!java.util.Set.of("MAIN","SUMMARY","RELAY").contains(names.next()))return false;
        JSONArray items=review.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);String kind=item.getString("kind");if(item.getBoolean("included")&&(kind.equals("team")||kind.startsWith("target:")))return false;}return true;
    }
}
