package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import org.json.JSONArray;
import org.json.JSONObject;

/** Role, model, protocol runner and execution device are separate identities. */
public final class AgentSlot {
    private AgentSlot(){}
    public static JSONObject normalize(JSONObject input,int index)throws Exception {
        String id=input.optString("id","member-"+index);if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("成员ID无效");
        String runner=input.optString("runner","model");if(!runner.matches("model|phone_agent|codex_app_server|claude|kimi"))throw new IllegalArgumentException("成员runner未识别");
        JSONObject slot=new JSONObject().put("id",id).put("name",JsonFiles.text(input,"name",48)).put("role",JsonFiles.text(input,"role",1200)).put("runner",runner);
        if(runner.equals("claude")){if(!Boolean.TRUE.equals(input.opt("externalExecutionRiskAcknowledged")))throw new SecurityException("Claude成员需明确确认工具限制不是OS沙箱、账号和内部费用待真实执行核验");slot.put("externalExecutionRiskAcknowledged",true);}
        if(input.has("model")&&!input.optString("model").isEmpty())slot.put("model",JsonFiles.text(input,"model",200));
        if(input.has("modelTarget"))slot.put("modelTarget",dev.pocketengine.runtime.models.ModelTargetRef.fromJson(input.getJSONObject("modelTarget")).toJson());
        slot.put("assignment",input.has("assignment")?JsonFiles.text(input,"assignment",2000):slot.getString("role"));
        if(input.has("acceptance"))slot.put("acceptance",JsonFiles.text(input,"acceptance",1200));
        JSONArray dependencies=new JSONArray();
        if(input.has("dependsOn")){
            Object value=input.opt("dependsOn");if(!(value instanceof JSONArray)||((JSONArray)value).length()>3)throw new IllegalArgumentException("分工依赖需为至多3名本队成员");
            java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<((JSONArray)value).length();i++){Object dependency=((JSONArray)value).get(i);if(!(dependency instanceof String)||!((String)dependency).matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!seen.add((String)dependency)||id.equals(dependency))throw new IllegalArgumentException("分工依赖身份无效或重复");dependencies.put(dependency);}
        }
        slot.put("dependsOn",dependencies);
        if(input.has("writeFiles")){JSONArray selected=input.getJSONArray("writeFiles");if(selected.length()<1||selected.length()>8)throw new IllegalArgumentException("成员文件范围需为1至8个项目相对路径");JSONArray files=new JSONArray();java.util.Set<String> unique=new java.util.HashSet<>();for(int i=0;i<selected.length();i++){String path=dev.pocketengine.features.workspace.VersionedWorkspace.path(selected.getString(i));if(!unique.add(path))throw new IllegalArgumentException("成员文件不能重复");files.put(path);}slot.put("writeFiles",files);}
        Object device=input.opt("device");if(runner.equals("phone_agent")){
            if(!(device instanceof JSONObject))throw new IllegalArgumentException("手机成员需明确选择本机后台屏或已配对 Android");
            JSONObject selected=(JSONObject)device;String kind=selected.optString("kind");
            if(kind.equals("local_phone")){if(!"background".equals(selected.optString("screenMode"))||selected.length()!=2)throw new SecurityException("本机团队手机成员仅使用明确后台独立屏");slot.put("device",new JSONObject().put("kind",kind).put("screenMode","background"));}
            else if(kind.equals("paired_android")){if(selected.length()!=2)throw new SecurityException("远端成员仅保存目标选择，不接收权限对象");slot.put("device",new JSONObject().put("kind",kind).put("targetDeviceId",dev.pocketengine.features.devices.DeviceProtocol.deviceId(selected.opt("targetDeviceId"),"targetDeviceId")));}
            else throw new IllegalArgumentException("手机成员目标不支持");
            if(input.has("modelTarget")||input.has("remoteProduction")||input.has("writeFiles"))throw new SecurityException("手机成员使用原生确认的本机或目标机模型，不继承研究、电脑和文件权限");
            slot.remove("model");
        }else if(device instanceof JSONObject){JSONObject source=(JSONObject)device;String profile=JsonFiles.text(source,"profileId",80),environment=JsonFiles.text(source,"environmentId",80);if(!profile.matches("[A-Za-z0-9_-]{1,80}")||!environment.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("执行设备身份无效");slot.put("device",new JSONObject().put("profileId",profile).put("environmentId",environment));}
        else if(device==null||device==JSONObject.NULL||device.equals("phone"))slot.put("device","phone");else throw new IllegalArgumentException("执行设备需为phone或已配置电脑环境");
        if(input.has("remoteProduction")){
            if(!runner.equals("codex_app_server")||!(slot.opt("device") instanceof JSONObject))throw new SecurityException("远端制作选择仅属于已选Codex电脑成员");JSONObject selected=input.getJSONObject("remoteProduction");
            boolean full=dev.pocketengine.features.remote.RemoteProjectAssignments.MODE.equals(selected.optString("mode"));java.util.Set<String> allowed=full?java.util.Set.of("mode","packageReference","limits","allowCommands","sameUserExecutionRiskAcknowledged","privateSourceTransferAcknowledged","internalInferenceBillingUnboundedAcknowledged"):java.util.Set.of("sourceTaskId","snapshotId","limits","internalInferenceBillingUnboundedAcknowledged");for(java.util.Iterator<String> keys=selected.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("远端制作配置不能指定原生授权、job或凭据");
            if(full){JSONObject pin=selected.getJSONObject("packageReference");if(pin.length()!=3||!pin.getString("id").matches("[a-f0-9-]{36}")||!pin.getString("sha256").matches("[a-f0-9]{64}")||!pin.getString("manifestIdentity").matches("[a-f0-9]{64}"))throw new SecurityException("请选择原catalog准确完整源项目包，不能提供任意ZIP或JSONmanifest");dev.pocketengine.features.remote.RemoteProjectAssignments.limits(selected.getJSONObject("limits"));for(String key:new String[]{"allowCommands","sameUserExecutionRiskAcknowledged","privateSourceTransferAcknowledged","internalInferenceBillingUnboundedAcknowledged"})if(!Boolean.TRUE.equals(selected.opt(key)))throw new SecurityException("须明确完整源码外传、同用户命令风险及内部费用未知");}
            else{for(String key:new String[]{"sourceTaskId","snapshotId"})if(!selected.getString(key).matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("请选择原任务已冻结的真实文件快照");dev.pocketengine.features.remote.RemoteAssignmentWorkspaces.validateLimits(selected.getJSONObject("limits"));}
            if(!Boolean.TRUE.equals(selected.opt("internalInferenceBillingUnboundedAcknowledged")))throw new SecurityException("需明确确认独立远端job授权与内部模型计费不能硬限");slot.put("remoteProduction",JsonFiles.copy(selected));
        }
        return slot;
    }
    public static String availability(JSONObject slot,boolean externalFactory){String runner=slot.optString("runner","model");if(runner.equals("model"))return "available";if(runner.equals("phone_agent"))return "native_scope_required";if(!runner.matches("codex_app_server|claude"))return "unsupported";if(!(slot.opt("device") instanceof JSONObject))return "device_required";return externalFactory?"probe_required":"adapter_required";}
}
