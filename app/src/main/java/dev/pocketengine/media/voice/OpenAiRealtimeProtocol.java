package dev.pocketengine.media.voice;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** OpenAI Realtime GA event adapter. Independently authored; no tasks, tools or text-model fallback. */
public final class OpenAiRealtimeProtocol {
    public interface Output {
        void send(JSONObject event)throws Exception;
        void ready();
        void changed(String status);
        default void transcription(JSONObject event)throws Exception{}
        /** Native accounting facts only; never a tool grant or an audio/text callback. */
        default void responseCreated(JSONObject actualResponse)throws Exception{}
        default void responseDone(JSONObject actualResponse)throws Exception{}
        default void transcriptionCommitted(JSONObject actualEvent)throws Exception{}
        default void transcriptionFinished(JSONObject actualEvent)throws Exception{}
    }
    private final RealtimeVoiceProfile profile;
    private final PcmPlaybackQueue playback;
    private final Output output;
    private final Set<String> retired=new HashSet<>(),eventIds=new HashSet<>();
    private String responseId="",itemId="",cancelPending="",truncatePending="";
    private long outputGeneration,ackDeadline,clientSequence;
    private int truncatedMilliseconds;
    private boolean configured,generating,closed;
    private String transcript="";
    private static final class InputTurn {final long sequence;String text="";boolean terminal;InputTurn(long sequence){this.sequence=sequence;}}
    private final Map<String,InputTurn> inputTurns=new LinkedHashMap<>();
    private long inputSequence;
    private boolean inputSinceClear;
    public OpenAiRealtimeProtocol(RealtimeVoiceProfile profile,PcmPlaybackQueue playback,Output output){this.profile=profile;this.playback=playback;this.output=output;}
    public void opened()throws Exception{
        JSONObject format=new JSONObject().put("type","audio/pcm").put("rate",24_000);
        JSONObject vad=new JSONObject().put("type","server_vad").put("create_response",true).put("interrupt_response",true)
            .put("threshold",0.6).put("prefix_padding_ms",300).put("silence_duration_ms",600);
        JSONObject audio=new JSONObject().put("input",new JSONObject().put("format",format).put("turn_detection",vad)
            .put("noise_reduction",new JSONObject().put("type","near_field")))
            .put("output",new JSONObject().put("format",format).put("voice",profile.voice));
        if(!profile.transcriptionModel.isEmpty())audio.getJSONObject("input").put("transcription",new JSONObject().put("model",profile.transcriptionModel));
        send(new JSONObject().put("type","session.update").put("session",new JSONObject().put("type","realtime")
            .put("output_modalities",new JSONArray().put("audio")).put("audio",audio).put("tools",new JSONArray()).put("tool_choice","none")
            .put("instructions","你是口袋世界的语音对话伙伴。自然、简短地用用户的语言交流。此语音连接不执行工具，不要声称已执行操作。用户需要实际做事时，请其在本机复核最终转录并明确开始原任务；之后以原任务记录、敏感审批和真实产物为准。")));
    }
    public void receive(String text)throws Exception{
        if(closed)return;
        if(text==null||text.length()>96_000)throw new IllegalStateException("语音服务消息过大");
        JSONObject event=new JSONObject(text);String type=event.getString("type");
        String id=event.optString("event_id");
        if(id.length()>512)throw new IllegalStateException("语音服务事件标识无效");
        if(!id.isEmpty()&&!eventIds.add(id))return;
        if(eventIds.size()>8192)throw new IllegalStateException("本次语音会话已达到事件预算，请重新发起");
        if(type.equals("error"))throw new IllegalStateException("语音服务拒绝了请求；未自动重试");
        if(type.equals("session.created"))return;
        if(type.equals("session.updated")){
            validateSession(event.getJSONObject("session"));
            if(!configured){configured=true;output.ready();}return;
        }
        if(!configured)throw new IllegalStateException("语音服务尚未确认音频格式");
        switch(type){
            case "input_audio_buffer.committed":{
                if(profile.transcriptionModel.isEmpty()||!inputSinceClear)break;
                String item=identifier(event,"item_id");if(inputTurns.containsKey(item))break;
                output.transcriptionCommitted(event);
                if(inputTurns.size()>=64)throw new IllegalStateException("本次语音目标转录已达到64段上限，请结束后重新发起");
                InputTurn turn=new InputTurn(++inputSequence);inputTurns.put(item,turn);caption(item,turn,"begin",false);break;
            }
            case "conversation.item.input_audio_transcription.delta":
            case "conversation.item.input_audio_transcription.completed":
            case "conversation.item.input_audio_transcription.failed":{
                String item=identifier(event,"item_id");InputTurn turn=inputTurns.get(item);
                if(turn==null||event.optInt("content_index",0)!=0)break;
                if(type.endsWith(".completed")||type.endsWith(".failed"))output.transcriptionFinished(event);
                if(turn.terminal)break;
                if(type.endsWith(".failed")){turn.terminal=true;turn.text="";caption(item,turn,"failed",false);break;}
                String next=type.endsWith(".completed")?event.getString("transcript"):turn.text+event.optString("delta");
                if(next.length()>4000){turn.terminal=true;turn.text="";caption(item,turn,"failed",false);break;}
                turn.text=next;turn.terminal=type.endsWith(".completed");caption(item,turn,turn.terminal?"complete":"partial",turn.terminal);break;
            }
            case "input_audio_buffer.speech_started":interrupt(true);output.changed("listening");break;
            case "input_audio_buffer.speech_stopped":output.changed("thinking");break;
            case "response.created":{
                String next=identifier(event.getJSONObject("response"),"id");
                output.responseCreated(event.getJSONObject("response"));
                if(retired.contains(next))return;
                if(generating||!truncatePending.isEmpty()||!cancelPending.isEmpty())throw new IllegalStateException("上一回复的打断尚未确认，已停止会话");
                if(playback.active())throw new IllegalStateException("上一回复尚未播放完，会话已停止以避免混合音轨");
                responseId=next;itemId="";generating=true;transcript="";output.changed("thinking");break;
            }
            case "response.output_audio.delta":{
                if(!current(event))return;
                if(event.getInt("content_index")!=0||event.getInt("output_index")!=0)throw new IllegalStateException("此客户端仅支持单音频回复");
                String item=identifier(event,"item_id");
                if(itemId.isEmpty()){itemId=item;outputGeneration=playback.begin(responseId+":"+item);}
                else if(!itemId.equals(item))throw new IllegalStateException("语音回复音轨发生未确认变化");
                String encoded=event.getString("delta");
                if(encoded.length()>64_000)throw new IllegalStateException("语音帧过大");
                byte[] pcm=Base64.getDecoder().decode(encoded);
                if(!playback.enqueue(outputGeneration,pcm))throw new IllegalStateException("语音回复已失效");
                output.changed("buffering");break;
            }
            case "response.output_audio_transcript.delta":{
                if(!current(event))return;
                String delta=event.getString("delta");if(transcript.length()+delta.length()>4000)throw new IllegalStateException("语音回复文字过长");
                transcript+=delta;break;
            }
            case "response.done":{
                JSONObject response=event.getJSONObject("response");String doneId=identifier(response,"id");
                output.responseDone(response);
                if(doneId.equals(cancelPending)){cancelPending="";clearDeadline();output.changed("interrupted");return;}
                if(retired.contains(doneId)||!doneId.equals(responseId))return;
                generating=false;
                String status=response.getString("status");
                if(!status.equals("completed")){interrupt(true);if(!status.equals("cancelled"))throw new IllegalStateException("语音服务未完成回复");}
                else{
                    JSONArray items=response.optJSONArray("output");
                    if(items!=null)for(int i=0;i<items.length();i++)if(!"message".equals(items.getJSONObject(i).optString("type")))throw new IllegalStateException("语音服务返回了未授权工具内容");
                    if(retired.size()>=256)throw new IllegalStateException("本次会话已达到轮次预算");
                    retired.add(doneId);playback.finish(outputGeneration);output.changed(playback.active()?"draining":"listening");
                }
                break;
            }
            case "conversation.item.truncated":{
                if(!truncatePending.isEmpty()&&truncatePending.equals(event.optString("item_id"))&&event.optInt("content_index",-1)==0&&event.optInt("audio_end_ms",-1)==truncatedMilliseconds){
                    truncatePending="";clearDeadline();output.changed("interrupted");
                }break;
            }
            case "response.function_call_arguments.delta":case "response.function_call_arguments.done":
                throw new IllegalStateException("语音连接没有工具执行权限");
            default:break; // Captions, rate-limit notices and audio-done are not playback completion.
        }
    }
    private boolean current(JSONObject event)throws Exception{
        String id=identifier(event,"response_id");
        if(retired.contains(id))return false;
        if(!id.equals(responseId)||!generating)throw new IllegalStateException("语音音频不属于当前回复");
        return true;
    }
    public void appendInput(byte[] pcm)throws Exception{
        if(closed||!configured)return;
        if(pcm==null||pcm.length==0||pcm.length%2!=0||pcm.length>4800)throw new IllegalArgumentException("采集音频格式无效");
        send(new JSONObject().put("type","input_audio_buffer.append").put("audio",Base64.getEncoder().encodeToString(pcm)));
        inputSinceClear=true;
    }
    /** Stops output only. The microphone remains available; no TaskStore cancellation is invoked. */
    public void interrupt(boolean serverAlreadyCancelled)throws Exception{
        if(closed)return;
        boolean buffered=playback.active();long heard=playback.interrupt();
        if(!generating&&!buffered)return;
        if(retired.size()>=256)throw new IllegalStateException("本次会话已达到轮次预算");
        retired.add(responseId);
        if(generating&&!responseId.isEmpty()){
            cancelPending=responseId;
            if(!serverAlreadyCancelled)send(new JSONObject().put("type","response.cancel").put("response_id",responseId));
        }
        generating=false;
        if(buffered&&!itemId.isEmpty()){
            if(heard<0)throw new IllegalStateException("无法确认已播放位置，会话已结束以避免保留未听到的上下文");
            truncatedMilliseconds=(int)(heard*1000/PcmPlaybackQueue.SAMPLE_RATE);truncatePending=itemId;
            send(new JSONObject().put("type","conversation.item.truncate").put("item_id",itemId).put("content_index",0).put("audio_end_ms",truncatedMilliseconds));
        }
        if(!cancelPending.isEmpty()||!truncatePending.isEmpty())ackDeadline=System.nanoTime()+5_000_000_000L;
        output.changed("interrupted");
    }
    public void clearInput()throws Exception{if(configured&&!closed){inputSinceClear=false;for(Map.Entry<String,InputTurn> entry:inputTurns.entrySet())if(!entry.getValue().terminal){entry.getValue().terminal=true;entry.getValue().text="";caption(entry.getKey(),entry.getValue(),"failed",false);}send(new JSONObject().put("type","input_audio_buffer.clear"));}}
    private void caption(String item,InputTurn turn,String phase,boolean isFinal)throws Exception{
        output.transcription(new JSONObject().put("kind","transcription").put("segmentId",item).put("protocol","openai_realtime_ga").put("sequence",turn.sequence).put("phase",phase).put("text",turn.text).put("finalStream",isFinal).put("isFinal",isFinal));
    }
    public void checkDeadline(long now){if(ackDeadline!=0&&now>ackDeadline)throw new IllegalStateException("远端打断或上下文截断未确认，会话已停止");}
    public boolean configured(){return configured&&!closed;}
    public boolean interruptionPending(){return !cancelPending.isEmpty()||!truncatePending.isEmpty();}
    public String transcript(){return transcript;}
    public void close(){closed=true;generating=false;}
    private void clearDeadline(){if(cancelPending.isEmpty()&&truncatePending.isEmpty())ackDeadline=0;}
    private void send(JSONObject event)throws Exception{event.put("event_id","pw-"+(++clientSequence));output.send(event);}
    private static String identifier(JSONObject event,String key)throws Exception{
        String id=event.getString(key);if(id.isBlank()||id.length()>200)throw new IllegalStateException("语音服务标识无效");return id;
    }
    private void validateSession(JSONObject session)throws Exception{
        if(!"realtime".equals(session.optString("type")))throw new IllegalStateException("服务没有确认实时语音协议");
        JSONObject audio=session.getJSONObject("audio"),input=audio.getJSONObject("input"),outputAudio=audio.getJSONObject("output");
        for(JSONObject format:new JSONObject[]{input.getJSONObject("format"),outputAudio.getJSONObject("format")})
            if(!"audio/pcm".equals(format.optString("type"))||format.optInt("rate")!=24_000)throw new IllegalStateException("语音服务音频格式不受支持");
        JSONObject vad=input.getJSONObject("turn_detection");
        if(!profile.transcriptionModel.isEmpty()&&(input.optJSONObject("transcription")==null||!profile.transcriptionModel.equals(input.getJSONObject("transcription").optString("model"))))throw new IllegalStateException("服务未确认所选输入转录模型，不能把会话当作任务目标转录");
        if(!"server_vad".equals(vad.optString("type"))||!vad.optBoolean("interrupt_response")||!vad.optBoolean("create_response"))throw new IllegalStateException("服务未确认自动轮流与打断");
        if(!profile.voice.equals(outputAudio.optString("voice")))throw new IllegalStateException("服务没有确认选定声音");
        JSONArray tools=session.optJSONArray("tools"),modalities=session.getJSONArray("output_modalities");
        if(tools==null||tools.length()!=0||!"none".equals(session.optString("tool_choice"))||modalities.length()!=1||!"audio".equals(modalities.getString(0)))throw new IllegalStateException("服务没有确认纯语音对话权限");
    }
}
