package dev.pocketengine.features.memory;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One explicit owner project-only read consent, then one original run. Not a TaskStore,
 * scheduler, model-supplied namespace or permission that can be restored from its metadata. */
public final class ProjectMemoryAccess implements ReadAudience,AutoCloseable {
    public static final class Consent implements AutoCloseable {
        final MemoryBook book;final String project,checkout,records,sources,id;
        private final AtomicBoolean current=new AtomicBoolean(true);
        private volatile ProjectMemoryAccess bound;
        Consent(MemoryBook book,String project,String checkout,JSONArray records,JSONArray sources){this.book=book;this.project=project;this.checkout=checkout;this.records=records.toString();this.sources=sources.toString();id=UUID.randomUUID().toString();}
        /** Actual original SQL task/run/control facts, never a task JSON supplied by a model. */
        public synchronized ProjectMemoryAccess bindForRun(TaskStore originalTasks,String taskId,RunControl original)throws Exception {
            if(!current.get())throw new SecurityException("项目记忆同意已撤回");
            synchronized(originalTasks){
                JSONObject task=originalTasks.get(taskId,false),development=task==null?null:task.optJSONObject("developmentContext");
                if(task==null||!"assistant".equals(task.optString("kind"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||task.has("origin")||development==null||!project.equals(development.optString("projectId"))||!checkout.equals(development.optString("checkoutId")))throw new SecurityException("项目记忆不能借其他项目、成员或渠道任务授权");
                String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");originalTasks.checkExtensionRun(taskId,run,epoch,original);
                TaskMemoryUse.TaskRef ref=new TaskMemoryUse.TaskRef(taskId,run,epoch,"owner","private");
                if(bound!=null){if(bound.original!=original||!bound.task.same(ref))throw new SecurityException("同一记忆同意不能转交另一task/run/epoch");bound.checkCurrent();return bound;}
                ProjectMemoryAccess next=new ProjectMemoryAccess(this,ref,original);book.requireProjectAccessCurrent(next);bound=next;return next;
            }
        }
        public JSONObject metadata()throws Exception{return new JSONObject().put("consentId",id).put("projectId",project).put("checkoutId",checkout).put("memorySpaceId","project:"+project).put("selectedRecords",new JSONArray(records)).put("selectedSourceSpans",new JSONArray(sources)).put("scopeMode","project_only").put("restorablePermission",false).put("bound",bound!=null);}
        /** Closing a pending owner page must not cancel an already admitted original run. */
        public void closeIfUnbound(){if(bound==null)current.set(false);}
        @Override public void close(){current.set(false);}
    }
    final Consent consent;final MemoryBook book;final TaskMemoryUse.TaskRef task;final RunControl original;
    private final AtomicBoolean current=new AtomicBoolean(true);
    private ProjectMemoryAccess(Consent consent,TaskMemoryUse.TaskRef task,RunControl original){this.consent=consent;book=consent.book;this.task=task;this.original=original;}
    @Override public String ownerId(){return "owner";}
    @Override public String spaceId(){return "project:"+consent.project;}
    @Override public String audienceId(){return "project-owner:"+consent.id;}
    @Override public long revision(){return 1;}
    @Override public JSONArray allowedRecords()throws Exception{return new JSONArray(consent.records);}
    @Override public JSONArray allowedSources()throws Exception{return new JSONArray(consent.sources);}
    /** No Book/Tasks monitor, disk or network callback while a native audience is checked. */
    @Override public void checkCurrent()throws Exception{if(!current.get()||!consent.current.get()||original.isCancelled()||!original.matchesHostRun(task.taskId,task.runId,Math.toIntExact(task.coordinatorEpoch)))throw new SecurityException("原项目记忆run同意已撤回或执行已变化");}
    public void requireOriginal(RunControl control,String taskId,String runId,int epoch)throws Exception{checkCurrent();if(control!=original||!task.taskId.equals(taskId)||!task.runId.equals(runId)||task.coordinatorEpoch!=epoch)throw new SecurityException("项目记忆读取不是原task/run/control");}
    public JSONArray currentRecordVersions()throws Exception{return book.projectCurrentRecordVersions(this);}
    public JSONObject metadata()throws Exception{return consent.metadata().put("taskId",task.taskId).put("runId",task.runId).put("epoch",task.coordinatorEpoch).put("taskOwnerId","owner").put("taskSpaceId","private");}
    JSONObject scope()throws Exception{return book.projectScopeForAccess(this);}
    void requireTask(TaskMemoryUse.TaskRef expected)throws Exception{checkCurrent();if(!task.same(expected))throw new SecurityException("项目记忆不能转交其他task/run/epoch");}
    @Override public void close(){current.set(false);}
}
