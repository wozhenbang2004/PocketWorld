package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.plugins.mcp.McpStdioFrames;
import dev.pocketengine.store.TaskStore;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-original-operation protocol adapter, not a process runner or a second approval/receipt store.
 * The caller is the original finite FeatureHost worker; every child/lease/input lives in its one
 * original ManagedProcessJobs.Job. This first purpose never calls an advertised remote tool. */
public final class McpLocalDiscoveryPipe {
    private McpLocalDiscoveryPipe() {}
    public static final class NotDispatched extends Exception {
        private final McpLocalDiscovery.Confirmed selected;
        private NotDispatched(McpLocalDiscovery.Confirmed selected,Exception cause){super("本机MCP在进入原runtime派发前已拒绝；没有启动程序或发送协议输入",cause);this.selected=selected;}
        public boolean matches(McpLocalDiscovery.Confirmed actual){return actual==selected;}
    }
    private static final class Channel implements McpStdioClient.Channel,NativePipeAdmission.Output {
        final McpLocalDiscovery.Confirmed confirmed;final TaskStore tasks;final RunControl original;final RuntimeInvocation invocation;
        final ArrayBlockingQueue<JSONObject> frames=new ArrayBlockingQueue<>(McpStdioClient.MAX_RPC_MESSAGES);final McpStdioFrames.Decoder decoder=new McpStdioFrames.Decoder();
        final AtomicReference<Exception> failure=new AtomicReference<>();final AtomicReference<McpLocalDiscovery.Metadata> metadata=new AtomicReference<>();final AtomicReference<RuntimeReceipt> finalReceipt=new AtomicReference<>();final AtomicBoolean protocolFinished=new AtomicBoolean(),invalid=new AtomicBoolean(),cancelRequested=new AtomicBoolean(),ended=new AtomicBoolean();
        volatile ManagedProcessJobs.PipeHandle handle;
        Channel(McpLocalDiscovery.Confirmed confirmed,TaskStore tasks,RunControl original,RuntimeInvocation invocation){this.confirmed=confirmed;this.tasks=tasks;this.original=original;this.invocation=invocation;}
        void current()throws Exception{if(failure.get()!=null)throw failure.get();if(invalid.get())throw new SecurityException("原本机MCP协议管道已失效，不恢复或重发");tasks.requireOwnerMcpDiscoveryCurrentForOwner(confirmed.actionId(),original);confirmed.requireCurrentForHost(tasks,original);}
        void attach(ManagedProcessJobs.PipeHandle actual)throws Exception {
            if(handle!=null)throw new SecurityException("原MCP管道已绑定");JSONObject identity=actual.identity();if(!invocation.executionRef().operationId().equals(actual.reference().operationId())||!invocation.exact().getJSONObject("payload").getString("nativePipeBindingDigest").equals(identity.getString("nativePipeBindingDigest")))throw new SecurityException("不是此原本机MCP派发的opaque管道");handle=actual;if(invalid.get())cancelOriginal();
        }
        @Override public boolean bytes(int stream,byte[] bytes){
            if(stream==2)return true; // The ONE original job retains raw stderr diagnostics. Never parse them.
            if(stream!=1||failure.get()!=null)return false;
            try{decoder.acceptStdout(bytes,0,bytes.length,value->{if(!frames.offer(value))throw new IOException("本机MCP待处理协议帧超过128项");});return true;}
            catch(Exception malformed){failure.compareAndSet(null,malformed);return false;}
        }
        void receipt(RuntimeReceipt actual)throws Exception {
            invocation.executionRef().requireReceipt(actual.json());finalReceipt.set(actual);
            if((actual.json().optBoolean("receiptReceived")||actual.json().has("finishedAt"))&&ended.compareAndSet(false,true))try{decoder.endOfStream();}catch(Exception malformed){failure.compareAndSet(null,malformed);}
            tasks.recordOwnerMcpDiscoveryForHost(McpLocalDiscovery.observeForHost(confirmed,actual,failure.get()==null?metadata.get():null,protocolFinished.get()));
        }
        @Override public JSONObject write(String inputOperationId,byte[] frame,RunControl control,long waitMillis)throws Exception {
            if(control!=original)throw new SecurityException("本机MCP协议不能借用另一运行控制");current();ManagedProcessJobs.PipeHandle actual=handle;if(actual==null)throw new SecurityException("原本机MCP尚无已准入管道");return actual.write(original,inputOperationId,frame,this::current,waitMillis);
        }
        @Override public JSONObject read(RunControl control,long waitMillis)throws Exception {
            if(control!=original)throw new SecurityException("本机MCP协议不能借用另一运行控制");long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
            while(true){current();JSONObject value=frames.poll(Math.max(1,Math.min(25,TimeUnit.NANOSECONDS.toMillis(Math.max(0,until-System.nanoTime())))),TimeUnit.MILLISECONDS);current();if(value!=null)return value;if(ended.get()||System.nanoTime()>=until)return null;}
        }
        @Override public void invalidate(){invalid.set(true);cancelOriginal();}
        void cancelOriginal(){ManagedProcessJobs.PipeHandle actual=handle;if(actual!=null&&cancelRequested.compareAndSet(false,true))try{RuntimeReceipt receipt=finalReceipt.get();if(receipt==null||!receipt.json().optBoolean("resourcesReleased"))actual.requestCancel(original);}catch(Exception unavailable){failure.compareAndSet(null,unavailable);}}
        RuntimeReceipt observed()throws Exception{ManagedProcessJobs.PipeHandle actual=handle;if(actual==null)throw new SecurityException("原本机MCP未取得可查询的opaque句柄");return RuntimeReceipt.fromBackend(invocation.executionRef(),actual.query());}
        boolean cleanlyClosed(RuntimeReceipt observed)throws Exception{JSONObject value=observed.json();return value.optBoolean("receiptReceived")&&value.optBoolean("processExited")&&value.optBoolean("resourcesReleased")&&value.optBoolean("processGroupAbsent")&&value.optBoolean("processSessionAbsent")&&value.optBoolean("outputComplete")&&value.optInt("exitCode",-1)==0&&!value.optBoolean("timedOut")&&!value.optBoolean("cancelRequested");}
    }
    /** Requires the already persisted original owner task/run/op and same retained control. */
    public static JSONObject run(McpLocalDiscovery.Confirmed confirmed,TaskStore tasks,RunControl original)throws Exception {
        Channel channel=null;McpStdioClient protocol=null;boolean runtimeEntered=false,protocolSucceeded=false;
        try {
            confirmed.requireCurrentForHost(tasks,original);RuntimeInvocation invocation=confirmed.invocationForHost(tasks,original,()->{tasks.requireOwnerMcpDiscoveryCurrentForOwner(confirmed.actionId(),original);confirmed.requireCurrentForHost(tasks,original);});
            channel=new Channel(confirmed,tasks,original,invocation);Channel retained=channel;ManagedProjectRuntime runtime=confirmed.runtimeForHost(tasks,original);
            NativePipeAdmission.Authorization authorization=new NativePipeAdmission.Authorization(){
                public void requireCurrent(RuntimeInvocation actual,JSONObject identity)throws Exception{if(actual!=invocation)throw new SecurityException("原MCP管道invocation不同");tasks.requireOwnerMcpDiscoveryCurrentForOwner(confirmed.actionId(),original);confirmed.requireCurrentForHost(tasks,original);}
                public void claimBeforeDispatch(RuntimeInvocation actual,JSONObject identity)throws Exception{tasks.bindOwnerMcpDiscoveryJobForHost(confirmed,actual,identity,original);}
                public void recordOriginalReceipt(RuntimeReceipt actual)throws Exception{retained.receipt(actual);}
            };
            NativePipeAdmission admission=runtime.admitPipesForHost(invocation,McpLocalDiscovery.pipeIdentityForHost(confirmed.scope()),authorization,channel);
            // Beyond this boundary a failure is uncertain even if no disk receipt is yet readable.
            runtimeEntered=true;channel.attach(runtime.dispatchPipesForHost(invocation,admission));protocol=new McpStdioClient(channel,original);JSONArray tools=protocol.discover();channel.metadata.set(McpLocalDiscovery.metadataForHost(confirmed,tasks,original,protocol,tools));protocolSucceeded=true;
            RuntimeReceipt observed=channel.observed();if(!channel.cleanlyClosed(observed)){
                JSONObject eof=channel.handle.eof(original,UUID.randomUUID().toString(),channel::current,2000);
                if(!Boolean.TRUE.equals(eof.opt("stdinClosed")))throw new IOException("原MCP EOF字节票未确认；没有重发");
            }
        }catch(Exception failed){
            if(!runtimeEntered)throw new NotDispatched(confirmed,failed);
            protocolSucceeded=false;if(channel!=null){channel.failure.compareAndSet(null,failed);channel.cancelOriginal();}
        }finally{
            if(runtimeEntered&&channel!=null){
                long grace=System.nanoTime()+TimeUnit.SECONDS.toNanos(5),end=grace+TimeUnit.SECONDS.toNanos(5);RuntimeReceipt actual=null;
                while(System.nanoTime()<end){try{actual=channel.observed();if(actual.json().optBoolean("resourcesReleased")&&actual.json().optBoolean("receiptReceived"))break;}catch(Exception unavailable){channel.failure.compareAndSet(null,unavailable);}if(System.nanoTime()>=grace||original.isCancelled())channel.cancelOriginal();try{Thread.sleep(20);}catch(InterruptedException stopped){Thread.currentThread().interrupt();channel.cancelOriginal();break;}}
                channel.protocolFinished.set(true);
                try{if(actual==null)actual=channel.observed();McpLocalDiscovery.Metadata metadata=protocolSucceeded&&channel.failure.get()==null?channel.metadata.get():null;tasks.recordOwnerMcpDiscoveryForHost(McpLocalDiscovery.observeForHost(confirmed,actual,metadata,true));}
                catch(Exception uncertain){try{tasks.status(confirmed.actionId(),"reconciling","原本机MCP发现/进程清场尚未完整确认；保留原操作与租约，只查询不重发");}catch(Exception unavailable){}}
            }
            if(protocol!=null)protocol.close();confirmed.close();
        }
        return publicView(tasks.ownerMcpDiscoveryForOwner(confirmed.actionId()));
    }
    /** Public owner result excludes the private exact argv, workspace body and raw process logs. */
    public static JSONObject publicView(JSONObject originalRow)throws Exception {
        JSONObject result=new JSONObject().put("taskId",originalRow.getString("taskId")).put("status",originalRow.getString("status")).put("scope",originalRow.getJSONObject("scope")).put("cancelRequested",originalRow.getBoolean("cancelRequested")).put("replayAllowed",false).put("agentRunnerCreated",false);JSONObject receipt=originalRow.optJSONObject("receipt");if(receipt!=null)result.put("receipt",new JSONObject(receipt.toString()));return result;
    }
}
