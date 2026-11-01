package dev.pocketengine.ui.memory;

import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONObject;

/** Ephemeral display/navigation only. Original reads, privacy, audit and all authority stay in NativeHost/TaskStore. */
public final class TaskMemoryTraceViewState {
    private TaskMemoryTraceViewState(){}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}

    /** A still-running native read may deduplicate to its old ID; explicit UI cancellation cannot reopen it. */
    public static final class Requests {
        private final LinkedHashSet<String> abandoned=new LinkedHashSet<>();
        public void abandon(String requestId){
            if(requestId==null||requestId.isBlank())return;abandoned.add(requestId);
            while(abandoned.size()>64)abandoned.remove(abandoned.iterator().next());
        }
        public String begin(String requestId){
            if(requestId==null||requestId.isBlank())throw new IllegalArgumentException("缺少本次读取身份");
            if(abandoned.contains(requestId))throw new IllegalStateException("上一条读取仍在收尾，请稍后明确重读。旧回复不会重新显示。");return requestId;
        }
    }

    /** History retains query/location metadata, never another cache of original bodies. */
    public static final class Navigation {
        private final ArrayDeque<JSONObject> trail=new ArrayDeque<>();
        private JSONObject current;
        public Navigation(String taskId,String runId)throws Exception{current=location(taskId,runId);}
        private static JSONObject location(String taskId,String runId)throws Exception{
            if(taskId==null||taskId.isBlank()||runId==null)throw new IllegalArgumentException("缺少原任务位置");
            return new JSONObject().put("taskId",taskId).put("runId",runId).put("section","messages").put("query","").put("sourceId","").put("cursor","").put("snapshot","");
        }
        public JSONObject location()throws Exception{return copy(current);}
        public boolean canBack(){return !trail.isEmpty();}
        public void section(String section)throws Exception{
            if(!Set.of("messages","operations","events","parents").contains(section))throw new IllegalArgumentException("未知执行记录范围");
            current.put("section",section).put("query","").put("sourceId","").put("cursor","");
        }
        public void query(String query)throws Exception{
            if(query==null||query.length()>500)throw new IllegalArgumentException("检索词超过本页范围");
            current.put("query",query).put("sourceId","").put("cursor","").put("snapshot","");
        }
        public JSONObject request(String cursor,boolean pinSnapshot)throws Exception{
            current.put("cursor",cursor);
            JSONObject request=new JSONObject();for(String key:new String[]{"taskId","runId","section","query","sourceId","cursor"})request.put(key,current.getString(key));
            request.put("limit",10).put("maxChars",4000);
            if(pinSnapshot&&!current.getString("snapshot").isEmpty())request.put("expectedSnapshot",current.getString("snapshot"));return request;
        }
        public boolean matches(JSONObject request)throws Exception{
            for(String key:new String[]{"taskId","runId","section","query","sourceId","cursor"})if(!current.getString(key).equals(request.optString(key)))return false;return true;
        }
        /** The UI calls this only after the original TaskMemoryTrace.checkedPage succeeds. */
        public void accepted(JSONObject checked)throws Exception{
            String run=checked.getString("runId"),snapshot=checked.getString("snapshot");
            if(!current.getString("taskId").equals(checked.optString("taskId"))||!current.getString("runId").isEmpty()&&!current.getString("runId").equals(run)||!snapshot.matches("[0-9a-f]{64}"))throw new SecurityException("执行记录位置已变化");
            current.put("runId",run).put("snapshot",snapshot);
        }
        private void push(String snapshot)throws Exception{
            if(trail.size()>=8)throw new IllegalStateException("本次已进入8级来源，请先返回核对");
            JSONObject saved=copy(current);saved.put("snapshot",snapshot);trail.addLast(saved);
        }
        public void focus(String source,String snapshot)throws Exception{
            if(source==null||!source.startsWith("task:"+current.getString("taskId")+":"))throw new IllegalArgumentException("父调用不属于这一原任务");
            push(snapshot);current.put("query","").put("sourceId",source).put("cursor","").put("snapshot",snapshot);
        }
        public void parent(String taskId,String runId,String snapshot)throws Exception{JSONObject next=location(taskId,runId);push(snapshot);current=next;}
        public void run(String runId,String snapshot)throws Exception{
            if(runId==null||runId.isBlank())throw new IllegalArgumentException("缺少原执行轮次");push(snapshot);
            current.put("runId",runId).put("sourceId","").put("cursor","").put("snapshot","");
        }
        public boolean back(){if(trail.isEmpty())return false;current=trail.removeLast();return true;}
    }

    /** One fixed original operation version. Cancel stops observation, never an original task or submitted mutation. */
    public static final class Operation {
        private final String taskId,runId,sourceId,revision;
        private JSONObject submitted,page;
        private String pending,status="idle",error="";
        public Operation(String taskId,String runId,JSONObject entry)throws Exception{
            if(!"operation_receipt".equals(entry.optString("kind")))throw new IllegalArgumentException("只能重开原操作回执");
            this.taskId=taskId;this.runId=runId;sourceId=entry.getString("sourceId");revision=entry.getString("revision");request(0,4000);
        }
        public JSONObject reference()throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("sourceId",sourceId).put("revision",revision);}
        public JSONObject request(int offset,int maxChars)throws Exception{
            JSONObject request=reference().put("offset",offset).put("maxChars",maxChars);TaskMemoryTrace.operationId(request);return request;
        }
        public void begin(String requestId,JSONObject request)throws Exception{
            if(requestId==null||requestId.isBlank())throw new IllegalArgumentException("缺少本次读取身份");
            TaskMemoryTrace.operationId(request);for(String key:new String[]{"taskId","runId","sourceId","revision"})if(!reference().getString(key).equals(request.optString(key)))throw new SecurityException("读取不能替换所选来源版本");
            submitted=copy(request);pending=requestId;page=null;status="reading";error="";
        }
        /** The Compose caller checks exactEnvelope first; this is the shared real B1 range/version checker. */
        public boolean accept(String requestId,JSONObject supplied)throws Exception{
            if(pending==null||!pending.equals(requestId))return false;
            JSONObject checked=TaskMemoryTrace.checkedOperationPage(supplied,submitted);
            status=checked.getString("status");page="current".equals(status)?copy(checked):null;pending=null;error="";return true;
        }
        public boolean fail(String requestId,String message){
            if(pending==null||!pending.equals(requestId))return false;pending=null;page=null;status="error";error=message==null?"原来源读取未完成":message;return true;
        }
        public void cancel(){pending=null;submitted=null;page=null;status="cancelled";error="";}
        public void invalidate(){pending=null;submitted=null;page=null;status="stale";error="";}
        public String pendingId(){return pending;}
        public String status(){return status;}
        public String error(){return error;}
        public JSONObject submitted()throws Exception{return submitted==null?null:copy(submitted);}
        public JSONObject page()throws Exception{return page==null?null:copy(page);}
        public int nextOffset()throws Exception{return page==null?-1:page.getInt("nextOffset");}
        public String citation()throws Exception{
            if(page==null||!"current".equals(status))throw new IllegalStateException("当前没有可复制的已核对正文范围");
            JSONObject ref=reference().put("readRange",copy(page.getJSONObject("readRange")));
            return "原执行记录引用（仅本页范围，不证明任务已完成）\n"+ref.toString(2)+"\n\n"+page.getString("body");
        }
    }
}
