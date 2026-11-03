package dev.pocketengine.features.local.runtime;

import dev.pocketengine.features.local.project.ProjectSources;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owned OS jobs and an executor receipt cache, subordinate to the original TaskStore launch CAS. */
public final class ManagedProcessJobs implements AutoCloseable {
    private final File root;
    private final NativeProcessSupervisor supervisor;
    private final Map<String,Job> live=new HashMap<>();
    private volatile java.util.function.Consumer<JSONObject> receiptSink=receipt->{};
    private volatile java.util.function.Consumer<ProcessInputReceipt> inputSink=receipt->{throw new IllegalStateException("runtime_pty_native_receipt_sink_missing");};
    private volatile boolean inputSinkReady;
    private final Map<String,Job> unresolvedPtyLeases=new HashMap<>();
    private boolean closing;
    private static final int MAX_LOG_BYTES=8*1024*1024;
    private static final class Job {
        final RuntimeInvocation invocation;final File directory;final AutoCloseable lease;final String id;
        JSONObject receipt;Thread thread;volatile boolean cancel;
        final java.util.concurrent.CopyOnWriteArrayList<PreparationIo> preparationIo=new java.util.concurrent.CopyOnWriteArrayList<>();
        long sequence,storedBytes,receivedBytes;
        long startedNanos,lastOutputNanos; // Live-owner observation only, never restored as process authority.
        final java.util.concurrent.locks.ReentrantReadWriteLock sourceWrites=new java.util.concurrent.locks.ReentrantReadWriteLock();
        volatile ProjectDevSession devSession;
        final RuntimePtyInput terminalInput;
        final NativePipeAdmission pipeAdmission;final RuntimePipeInput pipeInput;
        final java.util.Set<String> publishedInputs=new java.util.HashSet<>();
        Job(RuntimeInvocation invocation,File directory,JSONObject receipt,AutoCloseable lease) throws Exception {
            this(invocation,directory,receipt,lease,null);
        }
        Job(RuntimeInvocation invocation,File directory,JSONObject receipt,AutoCloseable lease,NativePipeAdmission pipeAdmission) throws Exception {
            this.invocation=invocation;this.directory=directory;this.receipt=receipt;this.lease=lease;this.pipeAdmission=pipeAdmission;
            pipeInput=pipeAdmission==null?null:new RuntimePipeInput(pipeAdmission.byteBudget);
            id=invocation.executionRef().operationId();
            JSONObject exact=invocation.exact(),payload=exact.getJSONObject("payload");
            boolean pty="pty".equals(payload.optString("terminalMode"));
            if(pty&&pipeInput!=null)throw new SecurityException("runtime_pipe_cannot_use_pty");
            if(pty){if(!"command".equals(exact.optString("intent")))throw new SecurityException("runtime_pty_requires_explicit_command_plan");dev.pocketengine.features.local.project.ProjectCommandSpec.fromPayload(payload);terminalInput=new RuntimePtyInput(payload.getLong("inputByteLimit"));}
            else terminalInput=null;
        }
    }
    /** A scoped cancellation handle of this exact live Job, not restorable receipt authority.
     * Invoke only outside the jobs/Job/lease monitors; the action must be non-blocking. */
    private static final class PreparationIo implements Runnable,AutoCloseable {
        final Job job;final Runnable action;final java.util.concurrent.atomic.AtomicBoolean invoked=new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean closed;
        PreparationIo(Job job,Runnable action){this.job=job;this.action=java.util.Objects.requireNonNull(action);}
        @Override public void run(){if(!closed&&invoked.compareAndSet(false,true))try{action.run();}catch(RuntimeException ignored){/* Original worker still owns its receipt and lease. */}}
        @Override public void close(){closed=true;job.preparationIo.remove(this);job.invocation.originalControl().removeCancelAction(this);}
    }
    private static void cancelPreparationIo(Job job){for(PreparationIo io:job.preparationIo)io.run();}
    /** Only the actual live queue/native writer creates this typed evidence. Not restorable JSON. */
    public static final class ProcessInputReceipt {
        private final RuntimeExecutionRef execution;private final dev.pocketengine.core.RunControl original;private final JSONObject result;
        private ProcessInputReceipt(Job job,JSONObject actual)throws Exception {this(job.invocation.executionRef(),job.invocation.originalControl(),actual);}
        private ProcessInputReceipt(RuntimeExecutionRef execution,dev.pocketengine.core.RunControl original,JSONObject actual)throws Exception {this.execution=execution;this.original=original;result=ProjectSources.copy(actual).put("projectExecution",execution.executionLink()).put("terminalOutputMerged",true).put("nativeInputReceiptContract",NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);}
        public RuntimeExecutionRef execution(){return execution;}
        public dev.pocketengine.core.RunControl originalControl(){return original;}
        public JSONObject json()throws Exception{return ProjectSources.copy(result);}
    }
    public ManagedProcessJobs(File privateRuntimeDirectory,NativeProcessSupervisor supervisor) throws Exception {
        root=RuntimeFiles.directory(privateRuntimeDirectory,"jobs");this.supervisor=java.util.Objects.requireNonNull(supervisor);
    }
    /** Monotone ordering in this original executor receipt cache, not task authority or a
     * timestamp/hash invented as a source revision. Retained when individual logs are cleared. */
    public synchronized long currentCheckoutSequence(String checkout)throws Exception {
        dev.pocketengine.features.local.project.ProjectBuildPlan.identity(checkout);File orders=RuntimeFiles.directory(root,"checkout-orders"),file=new File(orders,checkout+".json");
        if(java.nio.file.Files.isSymbolicLink(file.toPath())||java.nio.file.Files.isSymbolicLink(new File(orders,checkout+".json.pending").toPath()))throw new SecurityException("runtime_job_order_symlink_rejected");
        if(file.exists()){JSONObject row=RuntimeFiles.read(file);Object value=row.opt("sequence");if(!"pocketworld.managed-checkout-order.v1".equals(row.optString("format"))||!checkout.equals(row.optString("checkoutId"))||!(value instanceof Long||value instanceof Integer)||((Number)value).longValue()<0)throw new SecurityException("runtime_job_order_unknown");return ((Number)value).longValue();}
        long previous=0;File[] directories=root.listFiles();if(directories==null)throw new java.io.IOException("runtime_job_cache_unreadable");
        for(File directory:directories){File receipt=new File(directory,"receipt.json");if(!receipt.isFile())continue;JSONObject actual=RuntimeFiles.read(receipt);if(checkout.equals(actual.optString("checkoutId"))&&actual.has("checkoutSequence")){Object value=actual.get("checkoutSequence");if(!(value instanceof Long||value instanceof Integer)||((Number)value).longValue()<1)throw new SecurityException("runtime_original_job_order_invalid");previous=Math.max(previous,((Number)value).longValue());}}
        return previous;
    }
    private long nextCheckoutSequence(String checkout)throws Exception {
        long old=currentCheckoutSequence(checkout);if(old==Long.MAX_VALUE)throw new IllegalStateException("runtime_job_order_exhausted");long next=old+1;File orders=RuntimeFiles.directory(root,"checkout-orders");RuntimeFiles.write(new File(orders,checkout+".json"),new JSONObject().put("format","pocketworld.managed-checkout-order.v1").put("checkoutId",checkout).put("sequence",next));return next;
    }
    public boolean available() {return supervisor.available();}
    /** Metadata-only accounting over the original logs/receipts; never a generic cache-delete entry. */
    public JSONObject ownerStorageUsage(dev.pocketengine.core.RunControl original)throws Exception{return dev.pocketengine.features.local.project.NativeStorageTree.measure(root,original);}
    public boolean ptyAvailable(){return supervisor.ptyAvailable()&&inputSinkReady;}
    public boolean pipesAvailable(){return supervisor.pipesAvailable();}
    public void setProcessInputSink(java.util.function.Consumer<ProcessInputReceipt> nativeSink){inputSink=java.util.Objects.requireNonNull(nativeSink);inputSinkReady=true;}
    public void setReceiptSink(java.util.function.Consumer<JSONObject> nativeSink) {receiptSink=java.util.Objects.requireNonNull(nativeSink);}
    public synchronized void requireEnvironmentIdle(String identity) throws Exception {
        File[] directories=root.listFiles();if(directories==null)throw new java.io.IOException("runtime_job_cache_unreadable");
        for(File directory:directories) {
            File record=new File(directory,"receipt.json");if(!record.isFile())continue;
            JSONObject receipt=RuntimeFiles.read(record);
            if((identity==null||identity.equals(receipt.optString("environmentIdentity")))&&!Boolean.TRUE.equals(receipt.opt("resourcesReleased")))
                throw new IllegalStateException("runtime_environment_has_live_or_unresolved_job");
        }
    }
    public RuntimeReceipt start(RuntimeInvocation invocation,List<String> command,
            Map<String,String> environment,File directory,long timeoutMillis,AutoCloseable lease) throws Exception {
        return start(invocation,command,environment,directory,timeoutMillis,lease,null);
    }
    RuntimeReceipt startPipes(RuntimeInvocation invocation,List<String> command,Map<String,String> environment,File directory,long timeoutMillis,AutoCloseable lease,NativePipeAdmission admission)throws Exception {
        if(admission.invocation!=invocation)throw new SecurityException("runtime_pipe_original_invocation_required");
        return start(invocation,command,environment,directory,timeoutMillis,lease,admission);
    }
    private RuntimeReceipt start(RuntimeInvocation invocation,List<String> command,Map<String,String> environment,File directory,long timeoutMillis,AutoCloseable lease,NativePipeAdmission pipeAdmission)throws Exception {
        AutoCloseable ownedLease=java.util.Objects.requireNonNull(lease);
        boolean workerOwnsLease=false;Throwable primaryFailure=null;
        try {
            RuntimeExecutionRef ref=java.util.Objects.requireNonNull(invocation).executionRef();String id=ref.operationId();
            File jobDirectory=RuntimeFiles.directory(root,id),record=new File(jobDirectory,"receipt.json");
            synchronized(this) {
                if(closing)throw new IllegalStateException("runtime_jobs_closing");
                if(record.isFile())return query(ref);
            }
            // Native host gates may inspect environment/task state. Never call them or release
            // an environment lease while holding the jobs monitor (environment -> jobs is valid).
            invocation.checkCurrent();invocation.workspace().verify(invocation);
            JSONObject receipt=initial(ref).put("status","awaiting_receipt").put("createdAt",System.currentTimeMillis())
                .put("executable",command.get(0)).put("argv",new JSONArray(command.subList(1,command.size())))
                .put("environmentIdentity",environment.getOrDefault("POCKETWORLD_ENVIRONMENT_IDENTITY",""))
                .put("sourceCheckStatus","pre_exec_manifest_verified").put("receiptReceived",false)
                .put("processExited",false).put("resourcesReleased",false).put("cancelRequested",false)
                .put("stdout","").put("stderr","").put("outputTruncated",false).put("logCursor",0)
                .put("timeoutMillis",timeoutMillis).put("runMode",ref.exact().getJSONObject("payload").optString("runMode","foreground"));
            Job job=new Job(invocation,jobDirectory,receipt,ownedLease,pipeAdmission);
            JSONObject reviewed=invocation.exact().getJSONObject("payload");
            if("command".equals(invocation.exact().optString("intent"))||NativePipeAdmission.exact(invocation.exact())||dev.pocketengine.features.local.project.ProjectGit.exact(invocation.exact())||dev.pocketengine.features.skills.ManagedSkillBackend.exact(invocation.exact())||dev.pocketengine.features.local.project.ProjectGitImport.exact(invocation.exact())){JSONArray requested=dev.pocketengine.features.local.project.ProjectGit.exact(invocation.exact())?dev.pocketengine.features.local.project.ProjectGit.arguments(reviewed):dev.pocketengine.features.local.project.ProjectGitImport.exact(invocation.exact())?dev.pocketengine.features.local.project.ProjectGitImport.arguments(reviewed):reviewed.getJSONArray("argv");receipt.remove("argv");receipt.remove("requestedArgv");receipt.put("argvCount",requested.length()).put("argvDigest",ProjectSources.digest(new JSONObject().put("argv",requested))).put("argumentValuesIncluded",false).put("workingDirectory",reviewed.getString("workingDirectory")).put("terminalMode",reviewed.getString("terminalMode"));}
            if(job.terminalInput!=null)receipt.put("terminalNativeContract",reviewed.getString("terminalNativeContract")).put("terminalOutputMerged",true).put("inputByteLimit",reviewed.getLong("inputByteLimit")).put("terminalRows",reviewed.getInt("terminalRows")).put("terminalColumns",reviewed.getInt("terminalColumns")).put("cleanupScope","owned_session_and_observed_descendants").put("allDescendantsTerminated",false).put("unobservedDetachedDescendantsPossible",true);
            if(pipeAdmission!=null)receipt.put("nativeDuplexPipes",true).put("pipeNativeContract",NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT).put("nativePipeBindingDigest",ProjectSources.digest(pipeAdmission.binding())).put("inputByteLimit",pipeAdmission.byteBudget).put("cleanupScope","owned_session_and_observed_descendants").put("allDescendantsTerminated",false).put("unobservedDetachedDescendantsPossible",true);
            List<String> frozenCommand=java.util.Collections.unmodifiableList(new ArrayList<>(command));
            Map<String,String> frozenEnvironment=java.util.Collections.unmodifiableMap(new HashMap<>(environment));
            job.thread=new Thread(()->execute(job,frozenCommand,frozenEnvironment,directory,timeoutMillis),"project-process-"+id);
            synchronized(this) {
                if(closing)throw new IllegalStateException("runtime_jobs_closing");
                // The monitor was released during validation: another invocation may now own it.
                if(record.isFile())return query(ref);
                receipt.put("checkoutSequence",nextCheckoutSequence(ref.checkoutId()));
                RuntimeReceipt admitted=RuntimeReceipt.fromBackend(ref,receipt);
                RuntimeFiles.write(record,receipt);live.put(id,job);
                try {if(pipeAdmission!=null)pipeAdmission.attach(new PipeHandle(job,pipeAdmission));job.thread.start();} catch(RuntimeException|Error error) {
                    live.remove(id);receipt.put("status","not_launched").put("error","runtime_worker_start_failed")
                            .put("resourcesReleased",true);
                    RuntimeFiles.write(record,receipt);throw error;
                }
                workerOwnsLease=true;
                return admitted;
            }
        } catch(Exception|Error failure) {primaryFailure=failure;throw failure;}
        finally {
            // Every failed/duplicate/closed path releases exactly once, after leaving all jobs locks.
            // A started worker has the sole lease ownership and releases it after native drain.
            if(!workerOwnsLease)try {ownedLease.close();} catch(Exception|Error release) {
                if(primaryFailure==null)throw release;
                if(primaryFailure!=release)primaryFailure.addSuppressed(release);
            }
        }
    }
    /** Installation is work of the same original operation, not another task runner. */
    public interface Preparation { JSONObject run(PreparationContext context) throws Exception; }
    public final class PreparationContext {
        private final Job job;
        private NativeProcessSupervisor.Exit lastExit;
        private boolean nativeObservationUnknown;
        private String lastStdout="";private boolean captureTruncated;
        private String lastPhase="";private long lastProgressAt,lastProgressBytes;
        private PreparationContext(Job job) {this.job=job;}
        public void check() throws Exception {if(job.cancel)throw new InterruptedException("runtime_preparation_cancelled");job.invocation.checkCurrent();}
        AutoCloseable cancelIoOnStop(Runnable nonBlockingCancel)throws Exception {
            check();PreparationIo scope=new PreparationIo(job,nonBlockingCancel);job.preparationIo.add(scope);job.invocation.originalControl().onCancel(scope);
            try{check();return scope;}catch(Exception|Error stopped){scope.close();throw stopped;}
        }
        public void output(int stream,byte[] bytes) throws Exception {append(job,stream,bytes);}
        public void progress(String phase,long completed,long total) throws Exception {
            check();long now=System.currentTimeMillis();boolean persist=!phase.equals(lastPhase)||completed==total||now-lastProgressAt>500||completed-lastProgressBytes>1048576;
            synchronized(job) {job.receipt.put("preparationPhase",phase).put("completedBytes",completed).put("totalBytes",total);if(persist)RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);}
            if(persist){lastPhase=phase;lastProgressAt=now;lastProgressBytes=completed;}
        }
        public String stdout(){return lastStdout;}
        public boolean outputTruncated(){return captureTruncated;}
        public NativeProcessSupervisor.Exit execute(List<String> command,Map<String,String> environment,File directory,long timeoutMillis) throws Exception {
            check();
            synchronized(job) {job.receipt.put("processDispatchAttempted",true);RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);}
            nativeObservationUnknown=true;
            ByteArrayOutputStream captured=new ByteArrayOutputStream();captureTruncated=false;NativeProcessSupervisor.Observer delegate=observer(job);
            lastExit=supervisor.execute(command,environment,directory,timeoutMillis,500,new NativeProcessSupervisor.Observer(){
                @Override public boolean cancelled(){return delegate.cancelled();}
                @Override public boolean output(int stream,byte[] bytes){if(stream==1){int count=Math.min(bytes.length,262144-captured.size());if(count<bytes.length)captureTruncated=true;captured.write(bytes,0,count);}return delegate.output(stream,bytes);}
            });
            lastStdout=captured.toString(StandardCharsets.UTF_8.name());
            nativeObservationUnknown=!lastExit.groupAbsent;
            synchronized(job) {job.receipt.put("processLaunched",lastExit.launchErrno==0).put("processExited",lastExit.exitCode>=0||lastExit.signal>0).put("processGroupAbsent",lastExit.groupAbsent).put("launchErrno",lastExit.launchErrno).put("terminationSignal",lastExit.signal).put("outputComplete",lastExit.outputComplete)
                    .put("nativeResultContract",lastExit.resultContract).put("noChildCreated",lastExit.noChildCreated).put("launchPhase",lastExit.noChildCreated?"no_child_created":lastExit.exitCode>=0||lastExit.signal>0?"exit_observed":"unknown").put("timedOut",lastExit.timedOut);}
            return lastExit;
        }
    }
    public synchronized RuntimeReceipt existing(RuntimeExecutionRef ref) throws Exception {
        return new File(new File(root,ref.operationId()),"receipt.json").isFile()?query(ref):null;
    }
    public RuntimeReceipt startPreparation(RuntimeInvocation invocation,String catalogIdentity,Preparation work,AutoCloseable preparationLease) throws Exception {
        boolean transferred=false;
        try {
            RuntimeExecutionRef ref=invocation.executionRef();String id=ref.operationId();
            File directory=RuntimeFiles.directory(root,id),record=new File(directory,"receipt.json");
            synchronized(this) {if(closing)throw new IllegalStateException("runtime_jobs_closing");if(record.isFile())return query(ref);}
            invocation.checkCurrent();invocation.workspace().verify(invocation);
            JSONObject receipt=initial(ref).put("status","awaiting_receipt").put("createdAt",System.currentTimeMillis())
                    .put("environmentIdentity",catalogIdentity).put("sourceCheckStatus","pre_exec_manifest_verified")
                    .put("receiptReceived",false).put("processDispatchAttempted",false).put("processLaunched",false).put("processExited",false)
                    .put("resourcesReleased",false).put("cancelRequested",false).put("stdout","").put("stderr","")
                    .put("outputTruncated",false).put("logCursor",0).put("preparationPhase","queued");
            Job job=new Job(invocation,directory,receipt,preparationLease);
            job.thread=new Thread(()->executePreparation(job,work),"project-prepare-"+id);
            synchronized(this) {
                if(closing)throw new IllegalStateException("runtime_jobs_closing");if(record.isFile())return query(ref);
                receipt.put("checkoutSequence",nextCheckoutSequence(ref.checkoutId()));
                RuntimeReceipt admitted=RuntimeReceipt.fromBackend(ref,receipt);
                RuntimeFiles.write(record,receipt);live.put(id,job);
                try {job.thread.start();}catch(RuntimeException|Error failure) {live.remove(id);receipt.put("status","not_launched").put("resourcesReleased",true);RuntimeFiles.write(record,receipt);throw failure;}
                transferred=true;return admitted;
            }
        } finally {if(!transferred)preparationLease.close();}
    }
    private NativeProcessSupervisor.Observer observer(Job job) {
        return new NativeProcessSupervisor.Observer() {
            @Override public boolean cancelled() {if(job.cancel)return true;try{job.invocation.checkCurrent();return false;}catch(Exception stopped){job.cancel=true;return true;}}
            @Override public boolean output(int stream,byte[] bytes) {try{append(job,stream,bytes);return true;}catch(Exception failed){return false;}}
        };
    }
    private boolean started(Job job) {
        try {
            job.invocation.checkCurrent();JSONObject observed;
            synchronized(job){
                if(job.cancel)return false;
                if(job.receipt.optBoolean("nativeStartObserved"))return true;
                long now=System.currentTimeMillis();String ack=ProjectSources.digest(new JSONObject().put("execution",job.invocation.executionRef().executionLink()).put("observedAt",now).put("nonce",java.util.UUID.randomUUID().toString()));
                job.startedNanos=System.nanoTime();
                job.receipt.put("nativeStartObserved",true).put("nativeStartAt",now).put("launchAckIdentity",ack).put("processLaunched",true).put("ownedWorkerLive",true).put("status","running");
                RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);observed=ProjectSources.copy(job.receipt);
            }
            // A native observation only; no job/lease/map lock crosses the original host callback.
            if("owned_service".equals(observed.optString("runMode")))deliverReceipt(job,summary(job.invocation.executionRef(),observed));
            return true;
        }catch(Exception failed){return false;}
    }
    private void executePreparation(Job job,Preparation work) {
        PreparationContext context=new PreparationContext(job);
        String finalStatus="unknown";boolean releaseProven=false;
        try {
            JSONObject result=work.run(context);
            finalStatus="completed";releaseProven=!context.nativeObservationUnknown;
            synchronized(job) {
                for(java.util.Iterator<String> keys=result.keys();keys.hasNext();) {String key=keys.next();job.receipt.put(key,result.get(key));}
                job.receipt.put("status","awaiting_resource_release").put("exitCode",0).put("receiptReceived",true).put("resourcesReleased",false).put("preparationPhase","releasing_resources");
            }
        } catch(Exception|LinkageError failure) {
            boolean unknown=context.nativeObservationUnknown;finalStatus=unknown?"unknown":"failed";releaseProven=!unknown;
            synchronized(job) {try {
                job.receipt.put("status",unknown?"unknown":"awaiting_resource_release").put("receiptReceived",!unknown).put("resourcesReleased",false)
                        .put("cancelRequested",job.cancel||failure instanceof InterruptedException)
                        .put("exitCode",context.lastExit==null?-1:context.lastExit.exitCode)
                        .put("effectStatus",job.receipt.optBoolean("processDispatchAttempted")?"preparation_failed":"not_dispatched")
                        .put("error",failure.getMessage()==null?"runtime_preparation_failed":failure.getMessage())
                        .put("preparationPhase",unknown?"observation_unknown":"interrupted");
            }catch(Exception ignored){}}
        } finally {
            boolean leaseClosed=false;
            try {job.lease.close();leaseClosed=true;}catch(Exception failed){synchronized(job){try{job.receipt.put("leaseError",true).put("error","runtime_resource_release_unconfirmed");}catch(Exception ignored){}}}
            synchronized(job) {try {
                job.receipt.put("ownedWorkerLive",false).put("resourcesReleased",leaseClosed&&releaseProven).put("status",leaseClosed?finalStatus:"unknown");
                if(leaseClosed&&"completed".equals(finalStatus))job.receipt.put("preparationPhase","ready");
                job.receipt.put("finishedAt",System.currentTimeMillis());RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);
            }catch(Exception ignored){}}
            synchronized(this) {live.remove(job.id);notifyAll();}
            try {JSONObject actual;synchronized(job){actual=ProjectSources.copy(job.receipt);}deliverReceipt(job,summary(job.invocation.executionRef(),actual));}catch(Exception ignored){}
        }
    }
    private static JSONObject initial(RuntimeExecutionRef ref) throws Exception {
        String kind;
        switch(ref.phase()) {
            case PROBE:kind="tool_probe";break;case STAGE:kind="project_stage";break;
            case COLLECT:kind="artifact_collection";break;default:kind="command";
        }
        JSONObject receipt=new JSONObject().put("id",ref.operationId()).put("operationId",ref.operationId())
                .put("checkoutId",ref.checkoutId()).put("sourceDigest",ref.sourceDigest())
                .put("kind",kind).put("provider","managed_process").put("projectExecution",ref.executionLink());
        if(ref.phase()==RuntimeExecutionRef.Phase.COMMAND) {
            JSONObject payload=ref.exact().getJSONObject("payload");
            JSONArray arguments=dev.pocketengine.features.local.project.ProjectGit.exact(ref.exact())?dev.pocketengine.features.local.project.ProjectGit.arguments(payload):dev.pocketengine.features.local.project.ProjectGitImport.exact(ref.exact())?dev.pocketengine.features.local.project.ProjectGitImport.arguments(payload):payload.getJSONArray("argv");
            receipt.put("requestedExecutable",payload.getString("executable"))
                    .put("requestedArgv",new JSONArray(arguments.toString()));
        }
        return receipt;
    }
    private void execute(Job job,List<String> command,Map<String,String> environment,File directory,long timeoutMillis) {
        String finalStatus="unknown";boolean groupAbsent=false;java.util.List<JSONObject> pendingInputs=new ArrayList<>();
        try {
            if(job.invocation.exact().getJSONObject("payload").has("devWatch")){
                try{
                    ProjectDevSession session=ProjectDevSession.create(job.invocation);job.devSession=session;command=new ArrayList<>(command);command.add(session.markerArgument());
                    synchronized(job){job.receipt.put("devWatch",session.startupMetadata()).put("activeSourceDigest",job.invocation.executionRef().sourceDigest()).put("sourceGeneration",0);RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);}
                }catch(Exception setup){
                    // The actual native entry-cancel result below proves no child, rather than inventing a JSON no-child flag.
                    job.cancel=true;synchronized(job){job.receipt.put("devSessionError",setup.getClass().getSimpleName());}
                    append(job,2,("开发会话准备失败（"+setup.getClass().getSimpleName()+"）；未启动开发入口。\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            NativeProcessSupervisor.PtyObserver observer=new NativeProcessSupervisor.PtyObserver() {
                        @Override public void ptyIdentity(long pid,long born,long session){synchronized(job){try{job.receipt.put("ptyLeaderPid",pid).put("ptyLeaderStartTicks",born).put("ptySessionId",session);}catch(Exception invalid){throw new IllegalStateException("runtime_pty_identity_record_failed");}}}
                        @Override public byte[] nextTerminalCommand(){return job.terminalInput==null?null:job.terminalInput.next();}
                        @Override public boolean terminalCommandCurrent(long sequence){try{job.invocation.checkCurrent();job.terminalInput.checkCurrent(sequence);return !job.cancel;}catch(Exception stale){job.cancel=true;return false;}}
                        @Override public void terminalCommandResult(long sequence,int bytes,int error,int rows,int columns,boolean effects){
                            try{JSONObject result=job.terminalInput.observed(sequence,bytes,error,rows,columns,effects);publishInput(job,result);}
                            catch(Exception failed){throw new IllegalStateException("runtime_pty_native_input_receipt_failed",failed);}
                        }
                        @Override public boolean started(){return ManagedProcessJobs.this.started(job);}
                        @Override public boolean cancelled() {
                            if(job.cancel) return true;
                            try {job.invocation.checkCurrent();return false;}
                            catch(Exception error) {job.cancel=true;return true;}
                        }
                        @Override public boolean output(int stream,byte[] data) {
                            try {append(job,stream,data);return true;}
                            catch(Exception error) {synchronized(job){try{job.receipt.put("logError","runtime_log_write_failed");}catch(Exception ignored){}}return false;}
                        }
                    };
            JSONObject terminal=job.invocation.exact().getJSONObject("payload");
            NativeProcessSupervisor.Exit exit;
            if(job.pipeInput!=null)exit=supervisor.executePipes(command,environment,directory,timeoutMillis,500,new NativeProcessSupervisor.PipeObserver(){
                public boolean started(){return observer.started();}
                public boolean cancelled(){try{job.pipeAdmission.checkCurrent();return observer.cancelled();}catch(Exception stale){job.cancel=true;return true;}}
                public byte[] nextStdinCommand(){return job.pipeInput.next();}
                public boolean stdinCommandCurrent(long sequence){try{job.pipeAdmission.checkCurrent();return !job.cancel&&job.pipeInput.current(sequence);}catch(Exception stale){job.cancel=true;return false;}}
                public void stdinCommandResult(long sequence,int written,int error,boolean effects){try{job.pipeInput.observed(sequence,written,error,effects);}catch(Exception invalid){throw new IllegalStateException("runtime_pipe_native_ack_invalid",invalid);}}
                public boolean output(int stream,byte[] bytes){try{return observer.output(stream,bytes)&&job.pipeAdmission.bytes(stream,bytes);}catch(Exception stale){job.cancel=true;return false;}}
            });
            else exit=job.terminalInput==null?supervisor.execute(command,environment,directory,timeoutMillis,500,observer):supervisor.executePty(command,environment,directory,timeoutMillis,500,terminal.getInt("terminalRows"),terminal.getInt("terminalColumns"),observer);
            groupAbsent=exit.groupAbsent&&((job.terminalInput==null&&job.pipeInput==null)||exit.sessionAbsent);finalStatus=!groupAbsent?"unknown":exit.exitCode==0&&exit.launchErrno==0&&!exit.cancelled&&!exit.timedOut?"completed":"failed";
            synchronized(job) {
                job.receipt.put("processLaunched",exit.launchErrno==0).put("processExited",exit.exitCode>=0||exit.signal>0)
                        .put("exitCode",exit.exitCode).put("terminationSignal",exit.signal)
                        .put("launchErrno",exit.launchErrno).put("cancelRequested",job.cancel||exit.cancelled)
                        .put("timedOut",exit.timedOut).put("processGroupAbsent",exit.groupAbsent)
                        .put("outputComplete",exit.outputComplete).put("receiptReceived",true)
                        .put("nativeResultContract",exit.resultContract).put("noChildCreated",exit.noChildCreated).put("launchPhase",exit.noChildCreated?"no_child_created":exit.exitCode>=0||exit.signal>0?"exit_observed":"unknown")
                        .put("resourcesReleased",false).put("status","awaiting_resource_release");
                if(job.terminalInput!=null||job.pipeInput!=null)job.receipt.put("processSessionAbsent",exit.sessionAbsent);
                if(job.terminalInput!=null)job.receipt.put("terminalNativeContract",exit.resultContract);
                if(exit.launchErrno>0)job.receipt.put("error",exit.noChildCreated?"runtime_process_not_created":"runtime_process_launch_failed").put("message",exit.noChildCreated?"原生执行已确认未创建子进程；请核对启动错误，资源释放后才能明确修改或继续":"原子进程启动工具失败；请核对原生错误码和真实退出回执，不自动重试");
            }
        } catch(Exception|LinkageError error) {
            synchronized(job) {try {job.receipt.put("status","unknown").put("error","runtime_execution_observation_failed");}catch(Exception ignored){}}
        } finally {
            // A native file CAS already admitted by this service must finish before its checkout lease is released.
            // Process cancellation remains independent; it never needs this source-write lock.
            job.sourceWrites.writeLock().lock();
            try{
                boolean markerClean=job.devSession==null||job.devSession.cleanupMarker();
                boolean leaseClosed=false;
                // An unconfirmed PTY session may retain foreground/background groups or an observed
                // escaped descendant. Keep its real environment lease, not merely a JSON flag.
                if((job.terminalInput==null&&job.pipeInput==null)||groupAbsent)try {job.lease.close();leaseClosed=true;} catch(Exception error) {synchronized(job){try{job.receipt.put("leaseError",true).put("error","runtime_resource_release_unconfirmed");}catch(Exception ignored){}}}
                if((job.terminalInput!=null||job.pipeInput!=null)&&!leaseClosed)synchronized(this){unresolvedPtyLeases.put(job.id,job);}
                if(job.terminalInput!=null)try{pendingInputs=job.terminalInput.closeAndObserveNeverDequeued();}catch(Exception failed){synchronized(job){try{job.receipt.put("terminalInputCloseUnknown",true);}catch(Exception ignored){}}}
                if(job.pipeInput!=null)job.pipeInput.close();
                synchronized(job) {
                    try {job.receipt.put("ownedWorkerLive",false).put("resourcesReleased",leaseClosed&&groupAbsent).put("status",leaseClosed?finalStatus:"unknown").put("finishedAt",System.currentTimeMillis());if(job.devSession!=null)job.receipt.put("devMarkerCacheCleanupComplete",markerClean);RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);}
                    catch(Exception error) {try{job.receipt.put("receiptPersistence","failed");}catch(Exception ignored){}}
                }
            }finally{job.sourceWrites.writeLock().unlock();}
            synchronized(this) {live.remove(job.id);notifyAll();}
            for(JSONObject neverDequeued:pendingInputs)try{publishInput(job,neverDequeued);}catch(Exception failed){synchronized(job){try{job.receipt.put("terminalInputReceiptDelivery","failed");}catch(Exception ignored){}}}
            // No process/lease/job-map lock is held across host callbacks. This is one observed final
            // receipt for the original operation, not a new Task/Run or a retry dispatch.
            try {
                JSONObject actual;synchronized(job){actual=ProjectSources.copy(job.receipt);}
                deliverReceipt(job,summary(job.invocation.executionRef(),actual));
            } catch(Exception error) {
                synchronized(job) {try {
                    job.receipt.put("hostReceiptDelivery","failed");
                    RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);
                }catch(Exception ignored){}}
            }
        }
    }
    private void deliverReceipt(Job job,JSONObject actual)throws Exception {
        if(job.pipeAdmission==null)receiptSink.accept(actual);
        else job.pipeAdmission.receipt(RuntimeReceipt.fromBackend(job.invocation.executionRef(),actual));
    }
    /** Original object identity + private generation, never a PID/FD reconstructed from JSON. */
    public final class PipeHandle {
        private final Job job;private final NativePipeAdmission admission;private final String generation=java.util.UUID.randomUUID().toString();
        private PipeHandle(Job job,NativePipeAdmission admission){this.job=job;this.admission=admission;}
        public RuntimeExecutionRef reference(){return job.invocation.executionRef();}
        public JSONObject identity()throws Exception{return admission.binding().put("pipeInstanceId",generation).put("nativePipeBindingDigest",ProjectSources.digest(admission.binding()));}
        private void current(dev.pocketengine.core.RunControl original)throws Exception {
            if(original!=job.invocation.originalControl())throw new SecurityException("runtime_pipe_original_control_required");
            synchronized(ManagedProcessJobs.this){if(live.get(job.id)!=job)throw new SecurityException("runtime_pipe_original_handle_not_live");}
            original.check();admission.checkCurrent();if(job.cancel)throw new SecurityException("runtime_pipe_original_job_stopping");
        }
        private JSONObject send(dev.pocketengine.core.RunControl original,String operation,byte[] bytes,boolean eof,ProjectRuntime.LiveGate gate,long waitMillis)throws Exception {
            if(waitMillis<1||waitMillis>20000)throw new IllegalArgumentException("runtime_pipe_wait_budget_invalid");
            current(original);java.util.Objects.requireNonNull(gate).check();
            RuntimePipeInput.Ticket ticket=job.pipeInput.offer(operation,bytes,eof,()->{current(original);gate.check();});
            return job.pipeInput.await(ticket,original,waitMillis).put("executionId",job.id).put("pipeInstanceId",generation);
        }
        public JSONObject write(dev.pocketengine.core.RunControl original,String operation,byte[] bytes,ProjectRuntime.LiveGate gate,long waitMillis)throws Exception{return send(original,operation,bytes,false,gate,waitMillis);}
        public JSONObject eof(dev.pocketengine.core.RunControl original,String operation,ProjectRuntime.LiveGate gate,long waitMillis)throws Exception{return send(original,operation,new byte[0],true,gate,waitMillis);}
        public JSONObject originalInput(String operation)throws Exception{return job.pipeInput.query(operation).put("executionId",job.id).put("pipeInstanceId",generation);}
        public JSONObject query()throws Exception{return ManagedProcessJobs.this.query(reference()).json();}
        public void requestCancel(dev.pocketengine.core.RunControl original)throws Exception {
            if(original!=job.invocation.originalControl())throw new SecurityException("runtime_pipe_original_control_required");
            JSONObject bound=reference().binding();if(!original.matchesHostRun(bound.getString("taskId"),bound.getString("runId"),bound.getInt("coordinatorEpoch")))throw new SecurityException("runtime_pipe_original_run_changed");
            // Mutate only the held original Job object, never a fresh lookup by operation/PID/FD.
            job.cancel=true;
        }
    }
    private void publishInput(Job job,JSONObject actual)throws Exception {
        ProcessInputReceipt proof=new ProcessInputReceipt(job,actual);
        // The original TaskStore is the durable authority; this executor cache must not precede
        // or replace its typed native acknowledgement. No queue/job lock crosses this callback.
        inputSink.accept(proof);
        synchronized(job){job.publishedInputs.add(actual.getString("inputOperationId"));try{File records=RuntimeFiles.directory(job.directory,"terminal-inputs");RuntimeFiles.write(new File(records,actual.getString("inputOperationId")+".json"),actual);job.receipt.put("lastTerminalInput",actual);RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);}catch(Exception cache){job.receipt.put("terminalInputCacheIncomplete",true);}job.notifyAll();}
    }
    public JSONObject processInput(RuntimeExecutionRef ref,dev.pocketengine.core.RunControl original,String inputOperation,JSONObject request,ProjectRuntime.LiveGate inputGate)throws Exception {
        JSONObject bound=ref.binding();if(original==null||!original.matchesHostRun(bound.getString("taskId"),bound.getString("runId"),bound.getInt("coordinatorEpoch")))throw new SecurityException("runtime_pty_input_original_control_required");
        Job job;synchronized(this){job=live.get(ref.operationId());}RuntimePtyInput.Ticket ticket=null;
        try{
            ref.checkControl(original);
            if(job==null||job.invocation.originalControl()!=original)throw new SecurityException("runtime_pty_original_owned_job_required");
            job.invocation.checkCurrent();java.util.Objects.requireNonNull(inputGate).check();
            synchronized(job){ref.requireReceipt(job.receipt);if(job.terminalInput==null||job.cancel||!"running".equals(job.receipt.optString("status"))||!Boolean.TRUE.equals(job.receipt.opt("nativeStartObserved"))||Boolean.TRUE.equals(job.receipt.opt("resourcesReleased")))throw new SecurityException("runtime_pty_input_session_not_live");}
            ticket=job.terminalInput.offer(inputOperation,request,inputGate);
        }catch(SecurityException|IllegalArgumentException notQueued){
            if(job!=null&&job.terminalInput!=null&&job.terminalInput.hasRequest(inputOperation))throw notQueued;
            // This invocation has not entered any native IO queue. This proof is private typed
            // host evidence, not inferred from an external accepted=false or missing PID.
            JSONObject actual=new JSONObject().put("inputOperationId",inputOperation).put("inputDigest",ProjectSources.digest(request)).put("inputSequence",0).put("action",request.getString("action")).put("status","not_dispatched").put("requestedBytes",request.has("text")?dev.pocketengine.features.local.project.ProjectCommandSpec.encode(request.getString("text")).length:request.optString("action").equals("eof")?1:0).put("writtenBytes",0).put("nativeErrno",0).put("rows",0).put("columns",0).put("nativeAcknowledged",false).put("nativeQueueNeverEntered",true).put("inputTextIncluded",false).put("processTerminal",false).put("replayAllowed",false).put("reason",notQueued.getMessage()==null?"runtime_pty_input_rejected_before_queue":notQueued.getMessage());
            inputSink.accept(new ProcessInputReceipt(ref,original,actual));return actual.put("executionId",ref.operationId()).put("queryComplete",true);
        }
        JSONObject result=job.terminalInput.await(ticket,original,5000);
        if(!result.optBoolean("nativeAcknowledged")&&"not_dispatched".equals(result.optString("status")))publishInput(job,result);
        // The byte ACK precedes the host callback. Do not race a real successful input back to
        // the Runner as unknown merely because its SQLite audit callback is still committing.
        long until=System.nanoTime()+5_000_000_000L;
        while(result.optBoolean("nativeAcknowledged")&&!"unknown".equals(result.optString("status"))){
            original.check();synchronized(job){if(job.publishedInputs.contains(inputOperation))break;long remaining=until-System.nanoTime();if(remaining<=0){result.put("status","unknown").put("nativeReceiptUnconfirmed",true);break;}job.wait(Math.max(1,Math.min(25,remaining/1_000_000L)));}
        }
        return result.put("executionId",ref.operationId()).put("queryComplete",true);
    }
    private static void append(Job job,int stream,byte[] data) throws Exception {
        synchronized(job) {
            if(data.length>0)job.lastOutputNanos=System.nanoTime();
            job.receivedBytes+=data.length;
            int count=(int)Math.min(data.length,MAX_LOG_BYTES-job.storedBytes);
            if(count<data.length) job.receipt.put("outputTruncated",true);
            if(count>0) {
                try(FileOutputStream file=new FileOutputStream(new File(job.directory,"output.bin"),true)) {
                    DataOutputStream output=new DataOutputStream(file);
                    output.writeLong(++job.sequence);output.writeByte(stream);output.writeInt(count);output.write(data,0,count);
                    output.flush();file.getFD().sync();
                }
                job.storedBytes+=count;
            }
            job.receipt.put("logCursor",job.sequence).put("outputBytes",job.receivedBytes).put("storedOutputBytes",job.storedBytes);
        }
    }
    public synchronized RuntimeReceipt query(RuntimeExecutionRef ref) throws Exception {
        Job job=live.get(ref.operationId());JSONObject receipt;
        if(job!=null) {synchronized(job) {receipt=ProjectSources.copy(job.receipt).put("ownedWorkerLive",!Boolean.TRUE.equals(job.receipt.opt("resourcesReleased"))).put("observation","owned_worker_live");}}
        else {
            File file=new File(new File(root,ref.operationId()),"receipt.json");
            if(!file.isFile()) throw new IllegalArgumentException("runtime_original_job_not_found");
            receipt=RuntimeFiles.read(file).put("ownedWorkerLive",false);ref.requireReceipt(receipt);
            if(!receipt.optBoolean("receiptReceived") && !receipt.optString("status").equals("not_launched"))
                receipt.put("status","unknown").put("observation","owner_process_restarted_no_replay");
        }
        ref.requireReceipt(receipt);
        Long silence=null;boolean outputObserved=false;
        if(job!=null)synchronized(job){
            outputObserved=job.receivedBytes>0;
            if(Boolean.TRUE.equals(job.receipt.opt("nativeStartObserved"))){
                long elapsed=System.nanoTime()-(outputObserved?job.lastOutputNanos:job.startedNanos);
                if(elapsed>=0)silence=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsed);
            }
        }
        receipt.put("activity",RuntimeActivity.describe(ref.operationId(),receipt,silence,outputObserved));
        return RuntimeReceipt.fromBackend(ref,summary(ref,receipt));
    }
    /** Retained native currentness only: no Control/TaskStore/LocalModule callback or lease lock. */
    public synchronized void requireOwnedOrReleased(RuntimeExecutionRef ref)throws Exception {
        Job job=live.get(ref.operationId());JSONObject receipt;
        if(job!=null){synchronized(job){receipt=ProjectSources.copy(job.receipt);}}
        else receipt=RuntimeFiles.read(new File(new File(root,ref.operationId()),"receipt.json"));
        ref.requireReceipt(receipt);
        boolean released=receipt.optBoolean("receiptReceived")&&receipt.optString("status").matches("completed|failed")&&Boolean.TRUE.equals(receipt.opt("resourcesReleased"));
        if(released)return;
        if(job==null||!Boolean.TRUE.equals(receipt.opt("nativeStartObserved"))||!receipt.optString("launchAckIdentity").matches("[a-f0-9]{64}")||receipt.optString("status").equals("unknown"))throw new SecurityException("runtime_owned_service_currentness_unknown");
    }
    /** A lease borrowed from this actual native job, not reconstructed from a receipt/accepted JSON. */
    public final class LiveEditLease implements AutoCloseable {
        private final Job job;private boolean closed;
        private LiveEditLease(Job job){this.job=job;}
        public JSONObject policy()throws Exception{return job.devSession.policy();}
        public void check()throws Exception {if(closed)throw new SecurityException("原热改写租约已结束");job.invocation.checkCurrent();synchronized(job){requireDevLive(job);}}
        public JSONObject record(JSONObject actualSnapshot,String path,String writtenSha)throws Exception {
            dev.pocketengine.features.local.project.ProjectLiveEditPolicy.requireCurrent(policy(),actualSnapshot);JSONObject source=ProjectSources.describe(actualSnapshot);byte[] bytes=ProjectSources.byteFiles(actualSnapshot).get(path);if(bytes==null||!writtenSha.equals(ProjectSources.sha256(bytes)))throw new SecurityException("热改后没有观察到所写原字节，不能确认保存");
            synchronized(job){long generation=Math.addExact(job.receipt.optLong("sourceGeneration"),1);job.receipt.put("sourceGeneration",generation).put("activeSourceDigest",source.getString("sourceDigest")).put("lastLiveEditPath",path).put("lastLiveEditSha256",writtenSha);RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);return new JSONObject().put("executionId",job.id).put("sourceGeneration",generation).put("activeSourceDigest",source.getString("sourceDigest")).put("startupSourceDigest",job.invocation.executionRef().sourceDigest()).put("policyDigest",policy().getString("policyDigest"));}
        }
        @Override public void close(){if(closed)return;closed=true;job.sourceWrites.readLock().unlock();}
    }
    private static void requireDevLive(Job job)throws Exception {
        if(job.devSession==null||job.cancel||!"running".equals(job.receipt.optString("status"))||!Boolean.TRUE.equals(job.receipt.opt("nativeStartObserved"))||!Boolean.TRUE.equals(job.receipt.opt("processLaunched"))||Boolean.TRUE.equals(job.receipt.opt("resourcesReleased"))||Boolean.TRUE.equals(job.receipt.opt("processExited")))throw new SecurityException("原开发服务不再是已知存活状态，不能热改或打开活预览");
    }
    public LiveEditLease beginLiveEdit(RuntimeExecutionRef ref,dev.pocketengine.core.RunControl original)throws Exception {
        Job job;synchronized(this){job=live.get(ref.operationId());}if(job==null||job.invocation.originalControl()!=original)throw new SecurityException("热改没有原活动进程与控制器");ref.checkControl(original);synchronized(job){ref.requireReceipt(job.receipt);requireDevLive(job);}job.invocation.checkCurrent();job.sourceWrites.readLock().lockInterruptibly();boolean admitted=false;
        try{synchronized(job){requireDevLive(job);}LiveEditLease lease=new LiveEditLease(job);admitted=true;return lease;}finally{if(!admitted)job.sourceWrites.readLock().unlock();}
    }
    public ProjectDevSession.Observed probeLivePreview(RuntimeExecutionRef ref,dev.pocketengine.core.RunControl original,dev.pocketengine.core.RunControl observation)throws Exception {
        Job job;synchronized(this){job=live.get(ref.operationId());}if(job==null||job.invocation.originalControl()!=original)throw new SecurityException("活预览没有原活动进程与控制器");ref.checkControl(original);ProjectDevSession session;
        synchronized(job){ref.requireReceipt(job.receipt);requireDevLive(job);session=job.devSession;}
        return session.probe(observation,()->{job.invocation.checkCurrent();synchronized(job){requireDevLive(job);}});
    }
    public void requireLivePreview(RuntimeExecutionRef ref,dev.pocketengine.core.RunControl original)throws Exception {
        Job job;synchronized(this){job=live.get(ref.operationId());}if(job==null||job.invocation.originalControl()!=original)throw new SecurityException("原活预览进程已不可核对");ref.checkControl(original);job.invocation.checkCurrent();synchronized(job){ref.requireReceipt(job.receipt);requireDevLive(job);}
    }
    public void requirePtyServiceLive(RuntimeExecutionRef ref,dev.pocketengine.core.RunControl original)throws Exception {
        Job job;synchronized(this){job=live.get(ref.operationId());}if(job==null||job.invocation.originalControl()!=original)throw new SecurityException("runtime_pty_original_session_not_live");ref.checkControl(original);job.invocation.checkCurrent();
        synchronized(job){ref.requireReceipt(job.receipt);if(job.terminalInput==null||job.cancel||!"running".equals(job.receipt.optString("status"))||!Boolean.TRUE.equals(job.receipt.opt("nativeStartObserved"))||Boolean.TRUE.equals(job.receipt.opt("processExited"))||Boolean.TRUE.equals(job.receipt.opt("resourcesReleased")))throw new SecurityException("runtime_pty_original_session_not_live");}
    }
    public synchronized void requireCheckoutWritable(String checkout)throws Exception {
        File[] directories=root.listFiles();if(directories==null)throw new java.io.IOException("runtime_job_cache_unreadable");
        for(File directory:directories){File record=new File(directory,"receipt.json");if(!record.isFile())continue;Job job=live.get(directory.getName());JSONObject receipt;
            if(job!=null){synchronized(job){receipt=ProjectSources.copy(job.receipt);}}else receipt=RuntimeFiles.read(record);
            if(checkout.equals(receipt.optString("checkoutId"))&&"owned_service".equals(receipt.optString("runMode"))&&!Boolean.TRUE.equals(receipt.opt("resourcesReleased")))throw new IllegalStateException("project_owned_service_holds_checkout_stop_original_first");
            JSONObject link=receipt.optJSONObject("projectExecution");
            if(checkout.equals(receipt.optString("checkoutId"))&&link!=null&&link.optString("intent").matches("git_(status|history|diff|init|stage|unstage|resolve|commit)")&&!Boolean.TRUE.equals(receipt.opt("resourcesReleased")))throw new IllegalStateException("project_git_observation_holds_checkout_wait_original_receipt");
        }
    }
    private JSONObject summary(RuntimeExecutionRef ref,JSONObject receipt) throws Exception {
        JSONObject page=readLog(ref,0,65536);JSONArray chunks=page.getJSONArray("chunks");
        ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        for(int i=0;i<chunks.length();i++) {
            JSONObject chunk=chunks.getJSONObject(i);byte[] bytes=Base64.getDecoder().decode(chunk.getString("dataBase64"));
            (chunk.getInt("stream")==1?stdout:stderr).write(bytes);
        }
        receipt.put("stdout",stdout.toString(StandardCharsets.UTF_8.name())).put("stderr",stderr.toString(StandardCharsets.UTF_8.name()))
                .put("summaryOutputOnly",true).put("summaryOutputTruncated",page.getBoolean("hasMore")||receipt.optBoolean("outputTruncated"));
        return receipt;
    }
    public RuntimeReceipt requestCancel(RuntimeExecutionRef ref) throws Exception {
        RuntimeReceipt observed;Job job;
        synchronized(this){observed=query(ref);job=live.get(ref.operationId());
        if(job==null) {
            // A stale/unknown transport record is not a PID or authority to kill.
            if(!observed.terminal()) {
                JSONObject receipt=observed.json().put("cancelRequested",true).put("cancelDelivery","no_owned_live_process")
                        .put("resourcesReleased",false);
                RuntimeFiles.write(new File(new File(root,ref.operationId()),"receipt.json"),receipt);
                return RuntimeReceipt.fromBackend(ref,receipt);
            }
            return observed;
        }}
        job.cancel=true;
        try{synchronized(job) {job.receipt.put("cancelRequested",true);RuntimeFiles.write(new File(job.directory,"receipt.json"),job.receipt);}}
        finally{cancelPreparationIo(job);}
        return query(ref);
    }
    /** Bounded raw-byte pages; sequence cursor avoids UTF-8 corruption at pipe chunk boundaries. */
    public synchronized JSONObject readLog(RuntimeExecutionRef ref,long afterSequence,int maxBytes) throws Exception {
        if(afterSequence<0 || maxBytes<8192 || maxBytes>262144) throw new IllegalArgumentException("runtime_log_page_invalid");
        Job job=live.get(ref.operationId());Object lock=job==null?this:job;
        synchronized(lock) {
            JSONObject receipt=job==null?RuntimeFiles.read(new File(new File(root,ref.operationId()),"receipt.json")):job.receipt;
            ref.requireReceipt(receipt);JSONArray chunks=new JSONArray();long next=afterSequence;int total=0;boolean more=false;
            File log=new File(new File(root,ref.operationId()),"output.bin");
            if(log.isFile()) try(DataInputStream input=new DataInputStream(new FileInputStream(log))) {
                while(true) {
                    long sequence;try {sequence=input.readLong();}catch(EOFException done){break;}
                    int stream=input.readUnsignedByte(),length=input.readInt();
                    if((stream!=1 && stream!=2)||length<1||length>8192)throw new java.io.IOException("runtime_log_record_invalid");
                    byte[] bytes=new byte[length];input.readFully(bytes);
                    if(sequence<=afterSequence)continue;
                    if(total+length>maxBytes){more=true;break;}
                    chunks.put(new JSONObject().put("sequence",sequence).put("stream",stream).put("bytes",length)
                            .put("dataBase64",Base64.getEncoder().encodeToString(bytes)));
                    total+=length;next=sequence;
                }
            }
            return new JSONObject().put("operationId",ref.operationId()).put("chunks",chunks).put("nextCursor",next)
                    .put("hasMore",more).put("outputTruncated",receipt.optBoolean("outputTruncated"));
        }
    }
    @Override public void close() throws Exception {
        List<Job> jobs;
        synchronized(this) {closing=true;jobs=new ArrayList<>(live.values());for(Job job:jobs)job.cancel=true;}
        for(Job job:jobs)cancelPreparationIo(job);
        long until=System.currentTimeMillis()+5000;
        for(Job job:jobs) {long remaining=until-System.currentTimeMillis();if(remaining>0)job.thread.join(remaining);}
        synchronized(this) {if(!live.isEmpty()||!unresolvedPtyLeases.isEmpty())throw new IllegalStateException("runtime_process_drain_unconfirmed");}
    }
}
