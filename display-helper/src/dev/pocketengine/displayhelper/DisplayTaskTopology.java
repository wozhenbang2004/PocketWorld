package dev.pocketengine.displayhelper;

import android.content.ComponentName;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read-only root/leaf task facts. Never moves a task or returns a Binder token. */
final class DisplayTaskTopology {
    private static final int MAX_ROOTS=128,MAX_TASKS=256;
    // Equality is the original native WindowContainerToken equality, not its hash.
    // Keep only current roots (at most MAX_ROOTS); labels carry no Binder authority.
    private Map<Object,String> identities=new LinkedHashMap<>();
    private long sequence,generation;
    synchronized void clear(){identities.clear();generation++;}
    JSONObject read() {
        long expected;synchronized(this){expected=generation;}
        try {
            Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Object roots=Class.forName("android.app.IActivityTaskManager").getMethod("getAllRootTaskInfos").invoke(service);
            if(!(roots instanceof List<?>))return unknown("root_task_api_unsupported");
            return fromRoots((List<?>)roots,expected);
        }catch(Exception unavailable){return unknown("root_task_api_unavailable");}
    }
    /** Separate conversion keeps the actual platform field contract directly testable. */
    synchronized JSONObject fromRoots(List<?> roots) { return fromRoots(roots,generation); }
    private synchronized JSONObject fromRoots(List<?> roots,long expected) {
        if(expected!=generation)return unknown("task_topology_read_expired");
        try {
            if(roots.size()>MAX_ROOTS)return unknown("root_task_limit");
            JSONArray tasks=new JSONArray();Set<Integer> ids=new HashSet<>();Map<Object,String> retained=new LinkedHashMap<>();
            for(Object root:roots) {
                Class<?> type=root.getClass();int id=number(type,root,"taskId"),display=number(type,root,"displayId"),user=number(type,root,"userId");
                Object token=type.getField("token").get(root);if(id<0||display<0||user<0||token==null)return unknown("root_task_identity_unavailable");
                int[] children=(int[])type.getField("childTaskIds").get(root);
                String[] names=(String[])type.getField("childTaskNames").get(root);
                int[] users=(int[])type.getField("childTaskUserIds").get(root);
                if(children==null||names==null||users==null||children.length!=names.length||children.length!=users.length)return unknown("child_task_identity_unavailable");
                String rootIdentity=identities.get(token);
                if(rootIdentity==null){sequence=Math.incrementExact(sequence);rootIdentity="root-"+sequence;}
                retained.put(token,rootIdentity);
                if(children.length==0) {
                    Object base=type.getField("baseActivity").get(root);
                    // Empty organizer roots are not leaf tasks. Nonempty roots need a concrete component.
                    if(base==null){if(number(type,root,"numActivities")!=0)return unknown("root_component_unavailable");continue;}
                    if(!(base instanceof ComponentName))return unknown("root_component_unavailable");
                    add(tasks,ids,id,id,display,user,((ComponentName)base).flattenToString(),rootIdentity);
                } else {
                    if(children.length>MAX_TASKS)return unknown("leaf_task_limit");
                    for(int i=0;i<children.length;i++)add(tasks,ids,children[i],id,display,users[i],names[i],rootIdentity);
                }
                if(tasks.length()>MAX_TASKS)return unknown("leaf_task_limit");
            }
            identities=retained;
            return new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true)
                    .put("reason","all_root_task_infos").put("tasks",tasks).put("readOnly",true);
        }catch(Exception unsupported){return unknown("task_topology_format_unsupported");}
    }
    private static int number(Class<?> type,Object value,String name)throws Exception {Field field=type.getField(name);return field.getInt(value);}
    private static void add(JSONArray tasks,Set<Integer> ids,int task,int root,int display,int user,String name,String rootIdentity)throws Exception {
        ComponentName component=name==null?null:ComponentName.unflattenFromString(name);
        if(task<0||user<0||!ids.add(task)||component==null||!component.getPackageName().matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"))throw new IllegalArgumentException("Unproven leaf task");
        tasks.put(new JSONObject().put("taskId",task).put("rootTaskId",root).put("displayId",display).put("androidUserId",user)
                .put("packageName",component.getPackageName()).put("component",component.flattenToString()).put("rootIdentity",rootIdentity));
    }
    private static JSONObject unknown(String reason) {
        try{return new JSONObject().put("schemaVersion",1).put("status","unknown").put("complete",false).put("reason",reason).put("tasks",new JSONArray()).put("readOnly",true);}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
}
