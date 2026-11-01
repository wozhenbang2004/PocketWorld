package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import org.json.JSONArray;
import org.json.JSONObject;

/** Role, model, protocol runner and execution device are separate identities. */
public final class AgentSlot {
    private AgentSlot(){}
    public static JSONObject normalize(JSONObject input,int index)throws Exception {
        String id=input.optString("id","member-"+index);if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("成员ID无效");
        String runner=input.optString("runner","model");if(!runner.matches("model|codex_app_server|claude|kimi"))throw new IllegalArgumentException("成员runner未识别");
        JSONObject slot=new JSONObject().put("id",id).put("name",JsonFiles.text(input,"name",48)).put("role",JsonFiles.text(input,"role",1200)).put("runner",runner);
        if(input.has("model")&&!input.optString("model").isEmpty())slot.put("model",JsonFiles.text(input,"model",200));
        slot.put("assignment",input.has("assignment")?JsonFiles.text(input,"assignment",2000):slot.getString("role"));
        if(input.has("acceptance"))slot.put("acceptance",JsonFiles.text(input,"acceptance",1200));
        JSONArray dependencies=new JSONArray();
        if(input.has("dependsOn")){
            Object value=input.opt("dependsOn");if(!(value instanceof JSONArray)||((JSONArray)value).length()>3)throw new IllegalArgumentException("分工依赖需为至多3名本队成员");
            java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<((JSONArray)value).length();i++){Object dependency=((JSONArray)value).get(i);if(!(dependency instanceof String)||!((String)dependency).matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!seen.add((String)dependency)||id.equals(dependency))throw new IllegalArgumentException("分工依赖身份无效或重复");dependencies.put(dependency);}
        }
        slot.put("dependsOn",dependencies);
        if(input.has("writeFiles")){JSONArray selected=input.getJSONArray("writeFiles");if(selected.length()<1||selected.length()>8)throw new IllegalArgumentException("成员文件范围需为1至8个项目相对路径");JSONArray files=new JSONArray();java.util.Set<String> unique=new java.util.HashSet<>();for(int i=0;i<selected.length();i++){String path=dev.pocketengine.features.workspace.VersionedWorkspace.path(selected.getString(i));if(!unique.add(path))throw new IllegalArgumentException("成员文件不能重复");files.put(path);}slot.put("writeFiles",files);}
        Object device=input.opt("device");if(device instanceof JSONObject){JSONObject source=(JSONObject)device;String profile=JsonFiles.text(source,"profileId",80),environment=JsonFiles.text(source,"environmentId",80);if(!profile.matches("[A-Za-z0-9_-]{1,80}")||!environment.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("执行设备身份无效");slot.put("device",new JSONObject().put("profileId",profile).put("environmentId",environment));}
        else if(device==null||device==JSONObject.NULL||device.equals("phone"))slot.put("device","phone");else throw new IllegalArgumentException("执行设备需为phone或已配置电脑环境");
        if(input.has("remoteProduction")){
            if(!runner.equals("codex_app_server")||!(slot.opt("device") instanceof JSONObject))throw new SecurityException("远端制作选择仅属于已选Codex电脑成员");JSONObject selected=input.getJSONObject("remoteProduction");
            java.util.Set<String> allowed=java.util.Set.of("sourceTaskId","snapshotId","limits","internalInferenceBillingUnboundedAcknowledged");for(java.util.Iterator<String> keys=selected.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("远端制作配置不能指定原生授权、job或凭据");
            for(String key:new String[]{"sourceTaskId","snapshotId"})if(!selected.getString(key).matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("请选择原任务已冻结的真实文件快照");
            dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.validateLimits(selected.getJSONObject("limits"));if(!Boolean.TRUE.equals(selected.opt("internalInferenceBillingUnboundedAcknowledged")))throw new SecurityException("需明确确认独立远端job授权与内部模型计费不能硬限");slot.put("remoteProduction",JsonFiles.copy(selected));
        }
        return slot;
    }
    public static String availability(JSONObject slot,boolean externalFactory){String runner=slot.optString("runner","model");if(runner.equals("model"))return "available";if(!runner.equals("codex_app_server"))return "unsupported";if(!(slot.opt("device") instanceof JSONObject))return "device_required";return externalFactory?"probe_required":"adapter_required";}
}
