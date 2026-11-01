package dev.pocketengine.ui.memory;

import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.memory.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Controlled native journal/source ports around the REAL B1 module. This is not the root TaskStore bridge. */
public final class TaskMemoryNativeFixture implements AutoCloseable {
    public final JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
    public final TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef("task-current","run-current",4,"owner","private");
    public final TaskMemoryUse.ReceiptRef reference=new TaskMemoryUse.ReceiptRef(task,"request-current","answer-current");
    public final List<String> commands=new ArrayList<>(),openedTasks=new ArrayList<>();
    public final JSONArray revocationRefs=new JSONArray();
    public MemoryModule module;
    public TaskMemoryUse uses;
    public JSONObject original;
    public TaskMemoryUse.Provided provided;
    public boolean ownerAllowed=true,failCorrection,sourceBodyAvailable=true;
    public String taskStatus="running";
    private dev.pocketengine.store.TaskStore searchableTasks;
    public String searchedTaskId;
    public SourceRef source=new SourceRef("task_evidence","task:task-original:verified_result",2,"owner","private");
    public final File directory;
    private final Context context;
    private JSONObject originalMetadata;
    private final TaskMemoryUse.HostReader host=new TaskMemoryUse.HostReader(){
        public TaskMemoryUse.TaskState readTask(MemoryBook.Scope scope,String id){return new TaskMemoryUse.TaskState(task,taskStatus,false);}
        public TaskMemoryUse.Provided readProvided(MemoryBook.Scope scope,TaskMemoryUse.ReceiptRef ref){return provided;}
    };
    public final MemoryHostFence fence=new MemoryHostFence(){
        public void check(){if(!ownerAllowed)throw new SecurityException("当前空间访问已撤销，请返回任务重新核对");}
        public JSONObject admit(Admission action)throws Exception{check();return action.run();}
    };
    public TaskMemoryNativeFixture(Context base,boolean populate,String text)throws Exception {
        directory=Files.createTempDirectory("task-memory-native-").toFile();
        context=new ContextWrapper(base){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return directory;}
        };
        createModule();
        if(populate)original=module.importFromSources(scope,new JSONObject().put("text",text).put("layer","fact")
            .put("sourceRefs",new JSONArray().put(source.toJson()))).getJSONObject("record");
        TaskMemoryUse.Selection selected=uses.prepareTaskRecall(scope,task,"地点",16,12000,fence);
        JSONObject metadata=selected.metadata();originalMetadata=metadata;
        provided=new TaskMemoryUse.Provided(reference,metadata.getString("selectionId"),metadata.getString("contextDigest"),System.currentTimeMillis(),false,metadata.getJSONArray("records"));
        // A typed controlled journal confirmation, rather than fabricating a display receipt JSON.
        uses.confirmTaskMemoryUse(scope,selected,reference,fence);
    }
    private void createModule()throws Exception {
        module=new MemoryModule(new FeatureContext(context,null,null,event->{}),(scope,kind,id)->new SourceRef.State(source,false));
        uses=module.taskMemoryUse(host,expected->sourceBodyAvailable?new TaskMemoryUse.Evidence(expected,"原任务确认的地点证据","原任务中由用户确认的地点资料：杭州。此为测试夹具，不是任务成功证明。"):null);
    }
    public JSONObject receipt()throws Exception{return uses.readTaskMemoryReceipt(scope,reference,fence);}
    public JSONObject request(String command,JSONObject payload)throws Exception {
        commands.add(command);fence.check();
        if(!payload.optString("taskId").equals(task.taskId)||!payload.optString("runId").equals(task.runId)
                ||!payload.optString("requestId").equals(reference.requestId)||!payload.optString("answerId").equals(reference.answerId))throw new SecurityException("任务请求身份不匹配");
        switch(command){
            case "searchTaskMemoryTasks":{
                if(searchableTasks==null){searchableTasks=new dev.pocketengine.store.TaskStore(context);
                    JSONObject task=searchableTasks.create("原任务地点资料：真实失败任务仍可核对");searchedTaskId=task.getString("id");
                    searchableTasks.finish(searchedTaskId,"failed","未完成外部操作；这条任务没有主人确认结果",new JSONObject());}
                return new TaskStoreMemoryReader(searchableTasks,null).searchTasks(scope,payload.getString("query"),payload.optString("cursor"),payload.getInt("limit"));
            }
            case "readTaskMemoryReceipt":return receipt();
            case "readTaskMemorySource":return uses.readTaskMemorySource(scope,reference,payload.getString("id"),payload.getLong("expectedRevision"),SourceRef.fromJson(payload.getJSONObject("sourceRef")),fence);
            case "listTaskMemoryReplacements":return module.handle("list",new JSONObject().put("scope",scope));
            case "correctTaskMemoryReviewed":{
                if(failCorrection)throw new IllegalStateException("此版本已变化，请重新核对后再保存");
                JSONObject result=module.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject()
                    .put("id",payload.getString("id")).put("revision",payload.getLong("expectedRevision")).put("text",payload.getString("text"))));
                return feedback(result);
            }
            case "forgetTaskMemoryReviewed":return feedback(module.forgetReviewed(scope,payload.getString("id"),payload.getLong("expectedRevision"),this::recordRevocation));
            case "supersedeTaskMemoryReviewed":return feedback(module.supersedeReviewed(scope,payload.getString("keptId"),payload.getLong("keptRevision"),payload.getString("replacedId"),payload.getLong("replacedRevision"),this::recordRevocation));
            default:throw new IllegalArgumentException("不是本叶的原生回调");
        }
    }
    private void recordRevocation(JSONArray removed)throws Exception{for(int i=0;i<removed.length();i++)revocationRefs.put(new JSONObject(removed.getJSONObject(i).toString()));}
    private JSONObject feedback(JSONObject result)throws Exception {
        // The root context-clearing adapter is deliberately absent from this fixture.
        return result.put("receiptId",provided.selectionId).put("taskId",task.taskId).put("runId",task.runId)
            .put("requestId",reference.requestId).put("answerId",reference.answerId)
            .put("contextInvalidated",false).put("futureRecallBlocked",false).put("revocationPending",true);
    }
    public JSONObject keepCorrectFact(String text)throws Exception{return module.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text",text).put("layer","fact"))).getJSONObject("record");}
    public void reopenWithoutOriginalJournal()throws Exception{module.close();provided=null;createModule();}
    public JSONObject revokeProvided()throws Exception{
        provided=new TaskMemoryUse.Provided(reference,originalMetadata.getString("selectionId"),originalMetadata.getString("contextDigest"),System.currentTimeMillis(),true,originalMetadata.getJSONArray("records"));return receipt();
    }
    @Override public void close(){module.close();}
}
