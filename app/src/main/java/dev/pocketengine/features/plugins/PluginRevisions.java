package dev.pocketengine.features.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

/** Declaration-only revision transforms. The module's manifest file remains the sole authority. */
public final class PluginRevisions {
    static final int HISTORY_LIMIT=3;
    private PluginRevisions() { }

    public static String installationId(JSONObject plugin){return plugin.optString("installationId","legacy-"+plugin.optLong("installedAt"));}

    /** Legacy records start at zero; owner writes advance this even when a value returns to its old state. */
    public static long stateRevision(JSONObject plugin){
        if(plugin==null||!plugin.has("stateRevision"))return 0;
        return revision(plugin.opt("stateRevision"));
    }
    private static long revision(Object value){
        if(value instanceof Number)try{
            long result=new java.math.BigDecimal(value.toString()).longValueExact();
            if(result>=0)return result;
        }catch(NumberFormatException|ArithmeticException invalid){/* Reject lossy or malformed revision values. */}
        throw new IllegalArgumentException("插件状态序号无效，请重新读取当前状态");
    }
    static void advanceState(JSONObject plugin)throws Exception {plugin.put("stateRevision",Math.addExact(stateRevision(plugin),1));}

    static void checkExpected(JSONObject current,JSONObject input,boolean required)throws Exception {
        if(required&&!input.has("expectedInstallationId"))throw new IllegalArgumentException("请先读取当前插件，再确认恢复版本");
        if(input.has("expectedInstallationId")){
            String expected=input.getString("expectedInstallationId"),actual=current==null?"":installationId(current);
            if(!expected.equals(actual))throw new IllegalStateException("插件安装已变化，请刷新预览后再操作");
        }
        if(input.has("expectedStateRevision")&&revision(input.opt("expectedStateRevision"))!=stateRevision(current))
            throw new IllegalStateException("插件启停或工具授权已变化，请重新读取并审阅");
    }

    static void checkRestoreExpected(JSONObject current,JSONObject input)throws Exception {
        checkExpected(current,input,true);
        if(!input.has("expectedStateRevision"))throw new IllegalArgumentException("请先读取当前插件状态，再确认恢复版本");
    }

    static JSONArray archive(JSONObject current,long now)throws Exception {
        JSONArray result=new JSONArray();
        if(current==null)return result;
        result.put(record(current,now));
        JSONArray previous=current.optJSONArray("history");
        if(previous!=null)for(int i=0;i<previous.length()&&result.length()<HISTORY_LIMIT;i++){
            JSONObject revision=previous.getJSONObject(i);
            result.put(record(revision,revision.optLong("archivedAt")));
        }
        return result;
    }

    private static JSONObject record(JSONObject source,long archivedAt)throws Exception {
        // Whitelist declaration metadata; never preserve enabled state, grants or credential references.
        return new JSONObject().put("manifest",PluginRules.manifest(source.getJSONObject("manifest")))
                .put("installationId",installationId(source)).put("installedAt",source.optLong("installedAt"))
                .put("archivedAt",archivedAt);
    }

    static JSONObject history(JSONObject current)throws Exception {
        JSONArray versions=new JSONArray(),stored=current.optJSONArray("history");
        if(stored!=null)for(int i=0;i<stored.length()&&i<HISTORY_LIMIT;i++){
            JSONObject revision=stored.getJSONObject(i);
            versions.put(record(revision,revision.optLong("archivedAt")));
        }
        return new JSONObject().put("id",current.getString("id")).put("currentInstallationId",installationId(current))
                .put("currentStateRevision",stateRevision(current))
                .put("currentVersion",current.getJSONObject("manifest").getString("version"))
                .put("versions",versions).put("historyLimit",HISTORY_LIMIT).put("credentialsRetained",false)
                .put("message","恢复仅恢复工具声明；将生成新安装身份、停用插件并清空授权，旧凭据不会恢复");
    }

    static JSONObject restoreManifest(JSONObject current,String targetInstallationId)throws Exception {
        JSONArray versions=history(current).getJSONArray("versions");
        for(int i=0;i<versions.length();i++){
            JSONObject version=versions.getJSONObject(i);
            if(targetInstallationId.equals(version.getString("installationId"))){
                JSONObject manifest=version.getJSONObject("manifest");
                if(!current.getString("id").equals(manifest.getString("id")))throw new IllegalStateException("历史版本与插件身份不符");
                return manifest;
            }
        }
        throw new IllegalArgumentException("此历史版本已不存在，请刷新版本列表");
    }

    public static JSONObject preview(JSONObject current,JSONObject manifest)throws Exception {
        JSONArray added=new JSONArray(),removed=new JSONArray(),changed=new JSONArray();
        JSONObject oldManifest=current==null?new JSONObject():current.getJSONObject("manifest");
        JSONArray oldTools=oldManifest.optJSONArray("tools"),tools=manifest.getJSONArray("tools");
        if(oldTools==null)oldTools=new JSONArray();
        for(int i=0;i<tools.length();i++){
            JSONObject next=tools.getJSONObject(i),old=tool(oldTools,next.getString("name"));
            if(old==null){added.put(copy(next));continue;}
            JSONArray fields=new JSONArray();
            for(String field:new String[]{"kind","transport","endpoint","configurationDigest","effect","schema","description","protocolVersion","descriptorDigest","serverDigest","annotations","outputSchema"})
                if(!same(old.opt(field),next.opt(field)))fields.put(field);
            if(fields.length()>0)changed.put(new JSONObject().put("name",next.getString("name"))
                    .put("fields",fields).put("before",copy(old)).put("after",copy(next)));
        }
        for(int i=0;i<oldTools.length();i++){
            JSONObject old=oldTools.getJSONObject(i);
            if(tool(tools,old.getString("name"))==null)removed.put(copy(old));
        }
        return new JSONObject().put("id",manifest.getString("id")).put("isUpdate",current!=null)
                .put("expectedInstallationId",current==null?"":installationId(current))
                .put("expectedStateRevision",stateRevision(current))
                .put("currentVersion",oldManifest.optString("version")).put("targetVersion",manifest.getString("version"))
                .put("nameChanged",current!=null&&!oldManifest.getString("name").equals(manifest.getString("name")))
                .put("sameManifest",current!=null&&same(oldManifest,manifest)).put("manifest",copy(manifest))
                .put("addedTools",added).put("removedTools",removed).put("changedTools",changed)
                .put("enabledAfterInstall",false).put("grantsAfterInstall",new JSONArray()).put("credentialsRetained",false)
                .put("message","安装或恢复后需重新启用和授予工具权限；旧凭据不会沿用，已发出的远端操作不会撤回");
    }

    private static JSONObject tool(JSONArray tools,String name)throws Exception {
        for(int i=0;i<tools.length();i++)if(name.equals(tools.getJSONObject(i).getString("name")))return tools.getJSONObject(i);
        return null;
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static boolean same(Object left,Object right)throws Exception {
        if(left instanceof JSONObject&&right instanceof JSONObject){
            JSONObject a=(JSONObject)left,b=(JSONObject)right;if(a.length()!=b.length())return false;
            java.util.Iterator<String> keys=a.keys();while(keys.hasNext()){String key=keys.next();if(!b.has(key)||!same(a.get(key),b.get(key)))return false;}
            return true;
        }
        if(left instanceof JSONArray&&right instanceof JSONArray){
            JSONArray a=(JSONArray)left,b=(JSONArray)right;if(a.length()!=b.length())return false;
            for(int i=0;i<a.length();i++)if(!same(a.get(i),b.get(i)))return false;return true;
        }
        // Compare the JSON number values without losing integer/decimal precision through a double.
        if(left instanceof Number&&right instanceof Number)return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString()))==0;
        return java.util.Objects.equals(left,right);
    }
}
