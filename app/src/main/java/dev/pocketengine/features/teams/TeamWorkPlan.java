package dev.pocketengine.features.teams;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-authored ordering within the existing bounded team run, never a product task queue. */
public final class TeamWorkPlan {
    private TeamWorkPlan(){}
    static void validate(JSONArray members)throws Exception {
        Map<String,JSONObject> slots=new HashMap<>();for(int i=0;i<members.length();i++){JSONObject slot=members.getJSONObject(i);if(slots.put(slot.getString("id"),slot)!=null)throw new IllegalArgumentException("成员ID重复");}
        Set<String> visited=new HashSet<>(),visiting=new HashSet<>();for(String id:slots.keySet())visit(id,slots,visited,visiting);
    }
    static void validateWritable(JSONArray members)throws Exception {
        validateWritable(members,false);
    }
    /** This native selected-file origin does not change the older external read-only driver. */
    static void validateWritable(JSONArray members,boolean nativeRemoteSelections)throws Exception {
        Set<String> selected=new HashSet<>();for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);boolean local=member.optString("runner","model").equals("model")&&"phone".equals(member.opt("device")),remote=nativeRemoteSelections&&member.optString("runner").equals("codex_app_server")&&member.opt("device") instanceof JSONObject&&member.optJSONObject("remoteProduction")!=null;
            if(!local&&!remote)throw new SecurityException("制作只支持本机原AgentRunner，或主人明确选定且已核实原快照的Codex所选文件副本；其他外部成员保持只读");JSONArray files=member.getJSONArray("writeFiles");if(files.length()<1||files.length()>8)throw new IllegalArgumentException("每名成员需选择1至8个文件");for(int f=0;f<files.length();f++){String path=dev.pocketengine.features.workspace.VersionedWorkspace.path(files.getString(f));if(!selected.add(path))throw new IllegalArgumentException("成员文件范围必须互斥："+path);}}
    }
    private static void visit(String id,Map<String,JSONObject> slots,Set<String> visited,Set<String> visiting)throws Exception {
        if(visited.contains(id))return;if(!slots.containsKey(id))throw new IllegalArgumentException("分工依赖不属于本次团队");if(!visiting.add(id))throw new IllegalArgumentException("分工依赖形成循环");
        JSONArray dependencies=slots.get(id).getJSONArray("dependsOn");for(int i=0;i<dependencies.length();i++)visit(dependencies.getString(i),slots,visited,visiting);visiting.remove(id);visited.add(id);
    }
    static JSONObject state(JSONObject member,int round)throws Exception {
        JSONArray states=member.getJSONArray("roundStates");for(int i=0;i<states.length();i++)if(states.getJSONObject(i).getInt("round")==round)return states.getJSONObject(i);throw new IllegalArgumentException("成员轮次不存在");
    }
    static JSONArray blockers(JSONArray members,JSONObject member,int round)throws Exception {
        JSONArray result=new JSONArray(),dependencies=member.getJSONArray("dependsOn");
        if(round>1&&!state(member,round-1).optString("status").equals("completed"))result.put(member.getString("id"));
        for(int i=0;i<dependencies.length();i++){String id=dependencies.getString(i);if(!state(find(members,id),round).optString("status").equals("completed"))result.put(id);}
        return result;
    }
    static boolean permanentlyBlocked(JSONArray members,JSONObject member,int round)throws Exception {
        if(round>1&&failed(state(member,round-1).optString("status")))return true;
        JSONArray dependencies=member.getJSONArray("dependsOn");for(int i=0;i<dependencies.length();i++)if(failed(state(find(members,dependencies.getString(i)),round).optString("status")))return true;return false;
    }
    static boolean failed(String status){return status.matches("failed|unsupported|blocked_dependency|exited");}
    public static JSONObject find(JSONArray members,String id)throws Exception {for(int i=0;i<members.length();i++)if(id.equals(members.getJSONObject(i).getString("id")))return members.getJSONObject(i);throw new IllegalArgumentException("团队成员不存在");}
}
