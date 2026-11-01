package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import org.json.JSONObject;

/** Use an existing original task first; standalone native calls get only an accounting record. */
public final class NativeInferenceWork implements AutoCloseable {
    private final TaskStore.NativeInferenceSession owned;private final RunControl original;private final InferenceCapture.Target target;private boolean finished;
    private NativeInferenceWork(TaskStore.NativeInferenceSession owned,RunControl original,InferenceCapture.Target target){this.owned=owned;this.original=original;this.target=target;}
    public static NativeInferenceWork open(TaskStore tasks,TaskStore.NativeInferenceKind kind,String source,RunControl original)throws Exception{
        original.check();RunControl.HostRunIdentity existing=original.hostRunIdentity();
        if(existing!=null)return new NativeInferenceWork(null,original,tasks.journal(existing.taskId(),existing.runId(),existing.epoch(),original).inferenceTarget(InferenceReceipt.Purpose.valueOf(kind.name()),""));
        TaskStore.NativeInferenceSession session=tasks.beginNativeInference(kind,source,original);
        try{return new NativeInferenceWork(session,original,session.target());}catch(Exception failure){try{session.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public InferenceCapture.Target target(){return target;}
    public InferenceCapture.Target transcriptionTarget()throws Exception{if(owned==null)throw new SecurityException("转录来源必须属于同原实时用量会话");return owned.transcriptionTarget();}
    public JSONObject reference()throws Exception{return new JSONObject().put("taskId",target.context.taskId).put("runId",target.context.runId).put("epoch",target.context.epoch).put("purpose",target.context.purpose.name()).put("accountingOnly",owned!=null).put("executionAuthorized",false);}
    public void completed()throws Exception{if(!finished){if(owned!=null)owned.finish("completed");finished=true;}}
    public void failed(Exception cause)throws Exception{if(!finished){if(owned!=null)owned.finish(original.isCancelled()||cause instanceof InterruptedException?"cancelled":"failed");finished=true;}}
    @Override public void close()throws Exception{if(!finished){if(owned!=null)owned.close();finished=true;}}
}
