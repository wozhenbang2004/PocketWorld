package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.memory.ToolOutputCapture;
import dev.pocketengine.features.plugins.mcp.McpStdioFrames;
import dev.pocketengine.store.TaskStore;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Synchronous per-original-plugin-operation protocol adapter on the existing Runner worker.
 * No ProcessBuilder, task, thread pool, pipe handle restoration or server restart is introduced. */
public final class McpLocalTaskPipe {
    private McpLocalTaskPipe() {}
    public static final class NotDispatched extends Exception {
        private final TaskStore.NativeMcpInvocation original;
        private NotDispatched(TaskStore.NativeMcpInvocation original,Exception cause){super("原本机MCP在进入原runtime派发前拒绝；没有启动程序或发送RPC",cause);this.original=original;}
        public boolean matches(TaskStore.NativeMcpInvocation actual){return actual==original;}
    }
    private static final class Channel implements McpStdioClient.Channel,NativePipeAdmission.Output {
        final TaskStore.NativeMcpInvocation authorization;final TaskStore tasks;final RuntimeInvocation invocation;final JSONObject scope;final RunControl original;
        final McpStdioFrames.Decoder decoder=new McpStdioFrames.Decoder();final ArrayBlockingQueue<JSONObject> frames=new ArrayBlockingQueue<>(McpStdioClient.MAX_RPC_MESSAGES);final AtomicReference<Exception> failure=new AtomicReference<>();final AtomicBoolean invalid=new AtomicBoolean(),ended=new AtomicBoolean(),cancelRequested=new AtomicBoolean();
        volatile ManagedProcessJobs.PipeHandle handle;volatile RuntimeReceipt latest;volatile boolean protocolFinished,rpcMayHaveDispatched,outputUnavailable;volatile JSONObject result;
        Channel(TaskStore.NativeMcpInvocation authorization,TaskStore tasks,RuntimeInvocation invocation,JSONObject scope){this.authorization=authorization;this.tasks=tasks;this.invocation=invocation;this.scope=scope;original=authorization.originalControl();}
        void current()throws Exception{if(failure.get()!=null)throw failure.get();if(invalid.get())throw new SecurityException("原本机MCP管道已失效，不恢复、重连或重发");authorization.requireCurrentForHost();}
        void attach(ManagedProcessJobs.PipeHandle actual)throws Exception{if(handle!=null||!invocation.executionRef().operationId().equals(actual.reference().operationId())||!invocation.exact().getJSONObject("payload").getString("nativePipeBindingDigest").equals(actual.identity().getString("nativePipeBindingDigest")))throw new SecurityException("不是此原插件调用准入的opaque Job");handle=actual;if(invalid.get())cancel();}
        @Override public boolean bytes(int stream,byte[] bytes){if(stream==2)return true;if(stream!=1||failure.get()!=null)return false;try{decoder.acceptStdout(bytes,0,bytes.length,frame->{if(!frames.offer(frame))throw new IOException("本机MCP协议帧超过原128项预算");});return true;}catch(Exception malformed){failure.compareAndSet(null,malformed);return false;}}
        void receipt(RuntimeReceipt actual)throws Exception{invocation.executionRef().requireReceipt(actual.json());latest=actual;if((actual.json().optBoolean("receiptReceived")||actual.json().has("finishedAt"))&&ended.compareAndSet(false,true))try{decoder.endOfStream();}catch(Exception malformed){failure.compareAndSet(null,malformed);}tasks.recordLocalMcpInvocationForHost(authorization.recordForHost(),McpLocalInvocation.afterProtocolForHost(scope,actual,protocolFinished,rpcMayHaveDispatched,result,outputUnavailable));}
        @Override public JSONObject write(String operation,byte[] frame,RunControl control,long waitMillis)throws Exception{if(control!=original)throw new SecurityException("MCP输入不能借另一控制");current();if(handle==null)throw new SecurityException("原MCP尚无管道");return handle.write(original,operation,frame,this::current,waitMillis);}
        @Override public JSONObject read(RunControl control,long waitMillis)throws Exception{if(control!=original)throw new SecurityException("MCP读取不能借另一控制");long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);while(true){current();JSONObject frame=frames.poll(Math.max(1,Math.min(25,TimeUnit.NANOSECONDS.toMillis(Math.max(0,until-System.nanoTime())))),TimeUnit.MILLISECONDS);current();if(frame!=null)return frame;if(ended.get()||System.nanoTime()>=until)return null;}}
        @Override public void invalidate(){invalid.set(true);cancel();}
        void cancel(){var actual=handle;if(actual!=null&&cancelRequested.compareAndSet(false,true))try{if(latest==null||!latest.json().optBoolean("resourcesReleased"))actual.requestCancel(original);}catch(Exception unavailable){failure.compareAndSet(null,unavailable);}}
        RuntimeReceipt query()throws Exception{if(handle==null)throw new SecurityException("原MCP没有已准入可查询句柄");return RuntimeReceipt.fromBackend(invocation.executionRef(),handle.query());}
    }
    public static JSONObject run(TaskStore.NativeMcpInvocation original,TaskStore tasks,LocalModule local,ToolOutputCapture.Factory outputFactory)throws Exception {
        Channel channel=null;McpStdioClient protocol=null;McpResultCapture capture=null;boolean entered=false;Exception originalFailure=null;
        try {
            original.requireCurrentForHost();var program=original.programForHost();JSONObject p=program.scopeForHost();
            if(outputFactory!=null)capture=McpResultCapture.open(outputFactory,program.hostTool(),original.operationId(),original.originalControl(),new JSONObject().put("transport","stdio").put("pluginId",p.getString("pluginId")).put("installationId",p.getString("installationId")).put("version",p.getString("version")).put("hostTool",program.hostTool()).put("remoteTool",program.remoteToolName()).put("configurationDigest",p.getString("configurationDigest")).put("sourceDigest",p.getString("sourceDigest")).put("runtimeIdentity",p.getString("runtimeIdentity")).put("serverDigest",p.getString("serverDigest")).put("descriptorDigest",p.getString("descriptorDigest")));
            var prepared=local.prepareMcpForTask(original);RuntimeInvocation invocation=prepared.invocationForHost();channel=new Channel(original,tasks,invocation,prepared.scopeForHost());Channel retained=channel;ManagedProjectRuntime runtime=prepared.runtimeForHost();
            NativePipeAdmission admission=runtime.admitPipesForHost(invocation,McpLocalInvocation.pipeIdentityForHost(prepared.scopeForHost()),new NativePipeAdmission.Authorization(){
                public void requireCurrent(RuntimeInvocation actual,JSONObject binding)throws Exception{if(actual!=invocation)throw new SecurityException("MCP原invocation改变");original.requireCurrentForHost();}
                public void claimBeforeDispatch(RuntimeInvocation actual,JSONObject binding)throws Exception{tasks.claimLocalMcpInvocationForHost(original,actual,binding);}
                public void recordOriginalReceipt(RuntimeReceipt receipt)throws Exception{retained.receipt(receipt);}
            },channel);
            // No absence of a disk row/ACK after this point can attest no server startup effects.
            entered=true;channel.attach(runtime.dispatchPipesForHost(invocation,admission));protocol=McpStdioClient.forOriginalProgram(channel,original.originalControl(),prepared.scopeForHost());protocol.discover();McpResultCapture output=capture;
            try{channel.result=output==null?protocol.call(program.toolForHost(),program.arguments()):protocol.call(program.toolForHost(),program.arguments(),(body,token)->{retained.rpcMayHaveDispatched=true;return output.result(body,token);});channel.rpcMayHaveDispatched=true;}
            catch(McpClient.CallFailure failure){channel.rpcMayHaveDispatched=failure.dispatched;throw failure;}
            if(!Boolean.TRUE.equals(channel.handle.eof(channel.original,UUID.randomUUID().toString(),channel::current,2000).opt("stdinClosed")))throw new IOException("原MCP EOF票尚未确认，不重发；只核对原进程"); // EOF is cleanup, never an RPC business result.
        }catch(Exception failed){
            originalFailure=failed;if(!entered){if(capture!=null)capture.notDispatched();throw new NotDispatched(original,failed);}if(channel!=null){channel.outputUnavailable=failed instanceof ToolOutputCapture.OutputSourceUnavailable;channel.failure.compareAndSet(null,failed);channel.cancel();}
        }finally{
            if(entered&&channel!=null){long grace=System.nanoTime()+TimeUnit.SECONDS.toNanos(5),end=grace+TimeUnit.SECONDS.toNanos(5);RuntimeReceipt actual=null;
                while(System.nanoTime()<end){try{actual=channel.query();if(actual.json().optBoolean("resourcesReleased")&&actual.json().optBoolean("receiptReceived"))break;}catch(Exception unavailable){channel.failure.compareAndSet(null,unavailable);}if(System.nanoTime()>=grace||channel.original.isCancelled())channel.cancel();try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();channel.cancel();break;}}
                channel.protocolFinished=true;try{if(actual==null)actual=channel.query();channel.receipt(actual);}catch(Exception unknown){/* Original SQL unknown/ref and native lease remain; no synthetic receipt. */}
            }
            if(protocol!=null)protocol.close();
        }
        if(originalFailure instanceof ToolOutputCapture.OutputSourceUnavailable blocked)throw blocked;
        JSONObject result=original.recordForHost().publicReceiptForHost();if(result==null)throw new IllegalStateException("原MCP未取得真实回执，只查询原操作");if(capture!=null)result.put("outputCapture",capture.metadata());return result;
    }
}
