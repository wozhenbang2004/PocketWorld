package dev.pocketengine.media.voice;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.*;
import dev.pocketengine.store.TaskStore;
import java.util.*;
import org.json.JSONObject;

/** Original receipt sink. Reply/ASR are separate observed calls, never inferred from audio or text. */
public final class RealtimeInferenceAccounting implements AutoCloseable {
    public interface Factory {RealtimeInferenceAccounting open(RealtimeVoiceProfile profile,String capturedKey,String nativeSessionId)throws Exception;}
    private final NativeInferenceWork work;private final RunControl original;private final RealtimeVoiceProfile profile;private final ModelConfig captured;
    private final Map<String,InferenceCapture> calls=new LinkedHashMap<>(),transcriptions=new LinkedHashMap<>();
    private final Set<String> terminal=new HashSet<>(),transcriptionTerminal=new HashSet<>();
    private final Map<String,String> terminalFingerprints=new HashMap<>(),transcriptionFingerprints=new HashMap<>();private boolean closed,failed;
    private RealtimeInferenceAccounting(NativeInferenceWork work,RunControl original,RealtimeVoiceProfile profile,String key){this.work=work;this.original=original;this.profile=profile;captured=new ModelConfig("",key,profile.model,false,1,30,"openai_responses",4096);}
    public static RealtimeInferenceAccounting open(TaskStore store,RealtimeVoiceProfile profile,String key,String nativeSessionId)throws Exception{
        RunControl original=new RunControl();NativeInferenceWork work=NativeInferenceWork.open(store,TaskStore.NativeInferenceKind.REALTIME,nativeSessionId,original);return new RealtimeInferenceAccounting(work,original,profile,key);
    }
    public synchronized JSONObject reference()throws Exception{return work.reference().put("requestOrigin","server_response_observed").put("transcriptionOrigin","server_transcription_observed").put("onlyActuallyObservedEvents",true);}
    private static String identifier(JSONObject actual,String field)throws Exception{String id=actual.getString(field);if(!id.matches("[A-Za-z0-9_.:-]{1,200}"))throw new SecurityException("实际语音服务事件身份无效");return id;}
    public synchronized void created(JSONObject actual)throws Exception{
        if(closed)throw new IllegalStateException("原实时用量会话已结束");String id=identifier(actual,"id");if(calls.containsKey(id))return;if(calls.size()>=257)throw new IllegalStateException("本语音会话已停止接收更多回复事件");
        InferenceCapture capture=InferenceCapture.realtime(captured,profile.revision,original,work.target());capture.observedServerResponse(id);calls.put(id,capture);
        // One actually observed excess event is retained as unknown before the audio owner closes.
        // This local bound is not a promise that the service cannot process another request.
        if(calls.size()>256){failed=true;throw new IllegalStateException("语音回复观察已到本地上限，服务端可能已处理；已停止采集且没有重连");}
    }
    public synchronized void done(JSONObject actual)throws Exception{
        String id=identifier(actual,"id");InferenceCapture capture=calls.get(id);if(capture==null)throw new SecurityException("回复终态没有本会话实际开始记录");String fingerprint=TaskStore.capabilityFingerprint(actual);
        if(terminal.contains(id)){if(!fingerprint.equals(terminalFingerprints.get(id)))throw new SecurityException("原实时回复出现冲突终态，未累加新用量或改写旧回执");return;}
        capture.usage.observe(actual,ProviderUsage.Stage.FINAL);String status=actual.getString("status");capture.usage.terminal(status);
        InferenceReceipt.Outcome outcome;
        if(status.equals("completed")){capture.usage.completed();outcome=InferenceReceipt.Outcome.COMPLETED;}
        else if(status.equals("cancelled"))outcome=InferenceReceipt.Outcome.CANCELLED;
        else{failed=true;outcome=InferenceReceipt.Outcome.FAILED;capture.failure(InferenceFailure.local(InferenceFailure.Kind.PROTOCOL,System.currentTimeMillis()));}
        capture.finish(outcome);terminal.add(id);terminalFingerprints.put(id,fingerprint);
    }
    public synchronized void transcriptionCommitted(JSONObject actual)throws Exception{
        if(closed||profile.transcriptionModel.isEmpty())throw new SecurityException("没有本会话已配置的ASR来源");String item=identifier(actual,"item_id");if(transcriptions.containsKey(item))return;if(transcriptions.size()>=65)throw new IllegalStateException("本会话已停止接收更多转录事件");
        InferenceCapture capture=InferenceCapture.realtimeTranscription(captured.withModel(profile.transcriptionModel),profile.revision,original,work.transcriptionTarget());capture.observedServerTranscription(item);transcriptions.put(item,capture);
        if(transcriptions.size()>64){failed=true;throw new IllegalStateException("输入转录观察已到本地上限，未推测后续服务用量或重发声音");}
    }
    public synchronized void transcriptionFinished(JSONObject actual)throws Exception{
        String item=identifier(actual,"item_id"),type=actual.getString("type");Object part=actual.opt("content_index");if(!(part instanceof Integer||part instanceof Long)||((Number)part).longValue()!=0||!Set.of("conversation.item.input_audio_transcription.completed","conversation.item.input_audio_transcription.failed").contains(type))throw new SecurityException("转录终态不是本会话支持的实际音频项");
        InferenceCapture capture=transcriptions.get(item);if(capture==null)throw new SecurityException("转录终态没有本会话实际committed来源");String fingerprint=TaskStore.capabilityFingerprint(actual);
        if(transcriptionTerminal.contains(item)){if(!fingerprint.equals(transcriptionFingerprints.get(item)))throw new SecurityException("同一输入项出现冲突转录终态，未重复计量");return;}
        capture.usage.observe(actual,ProviderUsage.Stage.FINAL);capture.usage.terminal(type);boolean complete=type.endsWith(".completed");
        if(!complete){failed=true;capture.failure(InferenceFailure.local(InferenceFailure.Kind.PROTOCOL,System.currentTimeMillis()));}
        capture.finish(complete?InferenceReceipt.Outcome.COMPLETED:InferenceReceipt.Outcome.FAILED);transcriptionTerminal.add(item);transcriptionFingerprints.put(item,fingerprint);
    }
    public synchronized void close(String reason)throws Exception{
        if(closed)return;Exception error=null;boolean cancelled=Set.of("owner_hangup","owner_backgrounded","owner_replaced","host_closed","connection_cancelled").contains(reason);
        try{finishUnconfirmed(calls,terminal,cancelled);}catch(Exception storage){error=storage;}
        try{finishUnconfirmed(transcriptions,transcriptionTerminal,cancelled);}catch(Exception storage){if(error==null)error=storage;else error.addSuppressed(storage);}
        try{if(failed||error!=null)work.failed(error==null?new IllegalStateException("realtime_unconfirmed"):error);else work.completed();}catch(Exception storage){if(error==null)error=storage;else error.addSuppressed(storage);}
        if(error!=null)throw error;closed=true;calls.clear();transcriptions.clear();terminal.clear();transcriptionTerminal.clear();terminalFingerprints.clear();transcriptionFingerprints.clear();
    }
    private void finishUnconfirmed(Map<String,InferenceCapture> captures,Set<String> finished,boolean cancelled)throws Exception{
        Exception error=null;for(var entry:captures.entrySet())if(!finished.contains(entry.getKey()))try{
            entry.getValue().failure(InferenceFailure.local(cancelled?InferenceFailure.Kind.CANCELLED:InferenceFailure.Kind.NETWORK,System.currentTimeMillis()));entry.getValue().finish(cancelled?InferenceReceipt.Outcome.CANCELLED:InferenceReceipt.Outcome.FAILED);finished.add(entry.getKey());failed|=!cancelled;
        }catch(Exception storage){if(error==null)error=storage;else error.addSuppressed(storage);}if(error!=null)throw error;
    }
    @Override public void close()throws Exception{close("host_closed");}
}
