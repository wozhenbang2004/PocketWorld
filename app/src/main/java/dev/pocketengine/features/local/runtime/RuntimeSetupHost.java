package dev.pocketengine.features.local.runtime;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner setup over the existing TaskStore and managed-runtime installer.
 * No model tool, user project, new process engine or replay loop is introduced. */
public final class RuntimeSetupHost implements AutoCloseable {
    public static final String KEY="ownerRuntimeSetup",COMMAND="runtime.setup.owner",TOOL="native_runtime_prepare",INTENT="native_runtime_setup";
    private static final String PROJECT="native-runtime-setup",STEP="native_runtime_prepare";
    private final TaskStore tasks;private final ManagedProjectRuntime runtime;private final File root;private final Consumer<String> changed;
    private final ConcurrentHashMap<String,RunControl> controls=new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RuntimeSetupHost(TaskStore tasks,ManagedProjectRuntime runtime,File privateDirectory,Consumer<String> changed)throws Exception {
        this.tasks=java.util.Objects.requireNonNull(tasks);this.runtime=java.util.Objects.requireNonNull(runtime);
        root=RuntimeFiles.directory(privateDirectory,"runtime-setup");this.changed=java.util.Objects.requireNonNull(changed);
    }
    public RunControl control(String taskId){return taskId==null?null:controls.get(taskId);}
    public JSONObject read()throws Exception{return read("",0,32768);}
    public synchronized JSONObject read(String taskId,long afterSequence,int maxBytes)throws Exception {
        if(afterSequence<0||maxBytes<8192||maxBytes>262144)throw new IllegalArgumentException("runtime_log_page_invalid");
        JSONObject original=tasks.runtimeSetupForOwner(taskId==null?"":taskId),logs=null;String observationError="";
        if(original!=null){
            String id=original.getJSONObject("task").getString("id");JSONObject row=original.getJSONObject("operation");
            if("prepared".equals(row.optString("state"))&&!controls.containsKey(id)){
                tasks.runtimeSetupPreflightRejected(id,new JSONObject().put("code","runtime_setup_interrupted_before_dispatch").put("message","上次准备在原派发前中断，没有启动安装进程。"));
            }else if(!"prepared".equals(row.optString("state"))&&(row.optJSONObject("receipt")==null||!row.getJSONObject("receipt").optBoolean("notDispatched"))){
                RuntimeExecutionRef ref=reference(original);
                try{tasks.recordRuntimeSetupReceiptForOwner(runtime.query(ref).json());logs=runtime.readLog(ref,afterSequence,maxBytes);}
                catch(Exception unavailable){observationError=errorCode(unavailable);if(!controls.containsKey(id))tasks.runtimeSetupObservationUnavailable(id,observationError);}
            }
            original=tasks.runtimeSetupForOwner(id);releaseSettled(original);
        }
        JSONObject availability=runtime.runtimeSetupAvailabilityForHost();
        try{tasks.requireExecutionEnabled();availability.put("executionEnabled",true);}
        catch(SecurityException disabled){availability.put("executionEnabled",false).put("canPrepare",false).put("blocker","owner_execution_unavailable").put("message",errorCode(disabled));}
        return RuntimeSetupState.view(runtime.describe(),availability,original,
                original!=null&&controls.containsKey(original.getJSONObject("task").getString("id")),logs,observationError);
    }
    public Review review(String mode)throws Exception {
        if(closed)throw new IllegalStateException("runtime_setup_host_closed");
        if(!java.util.Set.of("prepare","repair").contains(mode))throw new IllegalArgumentException("runtime_setup_mode_invalid");
        tasks.requireExecutionEnabled();JSONObject view=read();
        if(!view.getBoolean("canPrepare"))throw new IllegalStateException(view.getString("message"));
        return new Review(this,mode,runtime.describe());
    }
    public synchronized JSONObject start(Confirmed confirmed)throws Exception {
        confirmed.requireIssuer(this);if(closed)throw new IllegalStateException("runtime_setup_host_closed");
        JSONObject existing=tasks.runtimeSetupForOwnerIfPresent(confirmed.requestId());
        if(existing!=null)return read(confirmed.requestId(),0,32768).put("knownOperation",true);
        confirmed.requireCurrent(runtime);JSONObject admitted=tasks.beginRuntimeSetupForOwner(confirmed);
        String id=admitted.getJSONObject("task").getString("id");
        if(admitted.optBoolean("knownOperation"))return read(id,0,32768).put("knownOperation",true);
        RunControl original=new RunControl();original.bindHostRun(id,id,1);
        original.setAuthorityCheck(()->tasks.requireRuntimeSetupCurrent(id,original));controls.put(id,original);
        boolean[] claimed={false};
        try{
            File workspace=RuntimeFiles.directory(root,id);
            RuntimeInvocation invocation=new RuntimeInvocation(confirmed.exact(),binding(id),source(id),original,
                    ()->tasks.requireRuntimeSetupCurrent(id,original),new RuntimeWorkspace(workspace,PROJECT,id));
            runtime.prepareSetupForHost(confirmed,invocation,()->{tasks.claimRuntimeSetupForOwner(id,original);claimed[0]=true;});
            tasks.recordRuntimeSetupReceiptForOwner(runtime.query(invocation.executionRef()).json());
        }catch(Exception|LinkageError failure){
            if(!claimed[0])tasks.runtimeSetupPreflightRejected(id,new JSONObject().put("code",errorCode(failure)).put("message",errorCode(failure)));
            else try{tasks.recordRuntimeSetupReceiptForOwner(runtime.query(reference(admitted)).json());}
            catch(Exception unknown){controls.remove(id,original);original.cancel();tasks.runtimeSetupObservationUnavailable(id,errorCode(unknown));}
            if(!claimed[0]){controls.remove(id,original);original.cancel();}
        }
        notifyChanged(id);return read(id,0,32768).put("knownOperation",false);
    }
    public synchronized JSONObject cancel(String taskId,String runId,int epoch)throws Exception {
        JSONObject original=tasks.runtimeSetupForOwner(taskId),task=original.getJSONObject("task");
        if(!task.getString("runId").equals(runId)||task.getInt("coordinatorEpoch")!=epoch)throw new SecurityException("原安装执行身份已变化，未停止其他任务");
        tasks.cancelRuntimeSetupForOwner(taskId);RunControl control=controls.get(taskId);if(control!=null)control.cancel();
        JSONObject row=original.getJSONObject("operation");
        if("prepared".equals(row.getString("state")))tasks.runtimeSetupPreflightRejected(taskId,new JSONObject().put("code","runtime_setup_cancelled_before_dispatch").put("message","已在派发前取消准备。"));
        else if(row.optJSONObject("receipt")==null||!row.getJSONObject("receipt").optBoolean("notDispatched")){
            try{tasks.recordRuntimeSetupReceiptForOwner(runtime.requestCancel(reference(original)).json());}catch(IllegalArgumentException missing){/* A missing original worker never grants a new launch. */}
        }
        notifyChanged(taskId);return read(taskId,0,32768);
    }
    /** Called by the application's existing managed-job receipt sink, before project routing. */
    public static boolean recognizesReceipt(JSONObject receipt){JSONObject link=receipt.optJSONObject("projectExecution");return link!=null&&INTENT.equals(link.optString("intent"));}
    public void recordReceipt(JSONObject receipt)throws Exception {
        JSONObject link=receipt.getJSONObject("projectExecution");if(!INTENT.equals(link.optString("intent")))throw new SecurityException("不是原本机工具准备回执");
        tasks.recordRuntimeSetupReceiptForOwner(receipt);String id=link.getString("taskId");releaseSettled(tasks.runtimeSetupForOwner(id));notifyChanged(id);
    }
    private void releaseSettled(JSONObject original)throws Exception {
        JSONObject row=original.getJSONObject("operation"),receipt=row.optJSONObject("receipt");
        if("receipt".equals(row.optString("state"))||receipt!=null&&receipt.has("finishedAt")&&!receipt.optBoolean("ownedWorkerLive")){
            RunControl control=controls.remove(original.getJSONObject("task").getString("id"));if(control!=null)control.cancel();
        }
    }
    private void notifyChanged(String id){try{changed.accept(id);}catch(RuntimeException observerUnavailable){/* A UI observer is not execution authority. */}}
    private static String errorCode(Throwable failure){String message=failure.getMessage();return message==null||message.isBlank()?failure.getClass().getSimpleName():message;}
    private static JSONObject binding(String id)throws Exception{return new JSONObject().put("taskId",id).put("runId",id).put("coordinatorEpoch",1).put("operationId",id);}
    private static JSONObject source(String id)throws Exception{return new JSONObject().put("checkout",new JSONObject().put("id",id).put("projectId",PROJECT)).put("files",new JSONArray());}
    private static RuntimeExecutionRef reference(JSONObject original)throws Exception{return RuntimeExecutionRef.fromHost(original.getJSONObject("exact"),original.getJSONObject("binding"));}
    public static boolean exact(JSONObject value){return "local".equals(value.optString("feature"))&&"project.runtime.prepare".equals(value.optString("command"))&&INTENT.equals(value.optString("intent"));}
    @Override public synchronized void close(){closed=true;for(RunControl control:controls.values())control.cancel();controls.clear();}

    public static final class Review implements AutoCloseable {
        private final RuntimeSetupHost issuer;private final String id=UUID.randomUUID().toString(),scope,exact,capability;
        private final long expiresAt=System.currentTimeMillis()+120000;private boolean closed;
        private Review(RuntimeSetupHost issuer,String mode,JSONObject facts)throws Exception {
            this.issuer=issuer;JSONObject effects=facts.getJSONObject("preparationEffects");long bytes=0,space=536870912L;JSONArray archives=effects.getJSONArray("archives");
            for(int i=0;i<archives.length();i++){bytes=Math.addExact(bytes,archives.getJSONObject(i).getLong("archiveBytes"));space=Math.addExact(space,archives.getJSONObject(i).getLong("unpackedBytes"));}
            JSONObject shown=new JSONObject().put("requestId",id).put("mode",mode).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID)
                    .put("runtimeIdentity",facts.getString("identity")).put("catalogIdentity",effects.getString("catalogIdentity"))
                    .put("title",mode.equals("repair")?"检查并修复本机工具":"准备本机工具")
                    .put("summary","下载并校验应用固定的工具包，安装系统工具并验证运行能力；已有可用环境先核对哈希，损坏时准备新版本。")
                    .put("effects",effects).put("downloadBytes",bytes).put("additionalDownloads",true).put("minimumFreeBytes",space)
                    .put("existingGenerationRetained",true).put("expiresAt",expiresAt).put("executionAuthorized",false);
            String planDigest=ProjectSources.digest(shown);shown.put("planDigest",planDigest);scope=shown.toString();
            String sourceDigest=ProjectSources.describe(source(id)).getString("sourceDigest");
            JSONObject payload=new JSONObject().put("checkoutId",id).put("expectedSourceDigest",sourceDigest).put("preparationCatalog",effects).put("setupMode",mode);
            exact=new JSONObject().put("feature","local").put("command","project.runtime.prepare").put("projectId",PROJECT).put("checkoutId",id)
                    .put("intent",INTENT).put("stepId",STEP).put("sourceDigest",sourceDigest).put("planDigest",planDigest)
                    .put("requestDigest",ProjectSources.digest(payload)).put("runtimeId",ManagedProjectRuntime.RUNTIME_ID)
                    .put("runtimeIdentity",facts.getString("identity")).put("payload",payload).toString();
            capability=new JSONObject().put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("runtimeIdentity",facts.getString("identity"))
                    .put("catalogIdentity",effects.getString("catalogIdentity")).put("effects",effects).put("mode",mode)
                    .put("nativeOwnerOnly",true).put("modelDispatchAllowed",false).toString();
        }
        public synchronized JSONObject view()throws Exception{return new JSONObject(scope);}
        private synchronized void current()throws Exception {
            if(closed||issuer.closed||System.currentTimeMillis()>=expiresAt)throw new SecurityException("准备审阅已关闭或过期，请重新核对");
            JSONObject facts=issuer.runtime.describe(),original=new JSONObject(scope);
            if(!original.getString("runtimeIdentity").equals(facts.optString("identity"))||!ProjectSources.canonical(original.getJSONObject("effects")).equals(ProjectSources.canonical(facts.optJSONObject("preparationEffects"))))throw new SecurityException("本机工具目录或已安装版本发生变化，请重新审阅");
        }
        public synchronized Confirmed confirm()throws Exception{current();return new Confirmed(this);}
        @Override public synchronized void close(){closed=true;}
    }
    public static final class Confirmed {
        private final Review review;private Confirmed(Review review){this.review=review;}
        public String requestId(){return review.id;}
        public JSONObject metadata()throws Exception{return new JSONObject(review.scope);}
        public JSONObject exact()throws Exception{return new JSONObject(review.exact);}
        public JSONObject capability()throws Exception{return new JSONObject(review.capability);}
        public void requireUnexpired()throws Exception{synchronized(review){if(review.closed||review.issuer.closed||System.currentTimeMillis()>=review.expiresAt)throw new SecurityException("原本机准备审阅已失效");}}
        private void requireIssuer(RuntimeSetupHost issuer){if(review.issuer!=issuer)throw new SecurityException("不是此原生准备宿主的审阅");}
        void requireCurrent(ManagedProjectRuntime runtime)throws Exception{if(review.issuer.runtime!=runtime)throw new SecurityException("不是审阅的原运行环境");review.current();}
    }
}
