package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import org.json.JSONObject;

/** One actual model presentation stream into the original QQ C2C outbox.
 * No scheduler, retry, model invocation or alternate delivery ledger. */
final class QqReplyStream implements ModelGateway.StreamSink {
    private final ChannelCore core;
    private final ChannelHttp http;
    private final JSONObject profile,event;
    private final String credential,prefix;
    private final Runnable changed;
    private int acknowledgedFrames;
    private final RunControl control;
    private final ChannelHttp.DispatchGuard origin;
    private final String id=java.util.UUID.randomUUID().toString();
    private final StringBuilder pending=new StringBuilder(),visible=new StringBuilder();
    private String requestId;
    private long lastEventSequence,lastPostNanos;
    private boolean registered,stopped,completed,receivedDeltas;

    QqReplyStream(ChannelCore core,ChannelHttp http,JSONObject profile,JSONObject event,String credential,
                  String prefix,RunControl control,ChannelHttp.DispatchGuard origin,Runnable changed)throws Exception {
        if(!QqConversation.scene(event).equals(QqConversation.C2C)||!profile.optBoolean("qqStreamRepliesEnabled"))throw new SecurityException("QQ流式仅限明确启用的原C2C会话");
        this.core=core;this.http=http;this.profile=new JSONObject(profile.toString());this.event=new JSONObject(event.toString());
        this.credential=credential;this.prefix=prefix;this.control=control;this.origin=origin;this.changed=changed;
    }

    @Override public void event(ModelGateway.StreamEvent current)throws Exception {
        if(current.kind==ModelGateway.StreamKind.FAILED||current.kind==ModelGateway.StreamKind.CANCELLED){failed("原模型流未取得完整终态；已显示的只是生成中草稿");return;}
        if(stopped||completed)throw new SecurityException("原QQ展示流已停止，不继续自动发送");
        origin.check();control.check();
        if(requestId==null)requestId=current.requestId;
        if(!requestId.equals(current.requestId)||current.sequence<=lastEventSequence)throw new SecurityException("展示分片不是同一实际模型请求/顺序");
        lastEventSequence=current.sequence;
        if(current.kind!=ModelGateway.StreamKind.TEXT_DELTA)return;receivedDeltas=true;
        pending.append(current.text);drain(false);
        if(visible.length()+pending.length()+prefix.length()>4000)throw new IllegalArgumentException("QQ生成内容超过4000字；已显示部分不冒完整回答，不截断发送DONE");
        if(acknowledgedFrames<63&&!(prefix+visible).trim().isEmpty()&&(lastPostNanos==0||System.nanoTime()-lastPostNanos>=500_000_000L))post(prefix+visible,1);
    }

    /** Complete only after the original Gateway return has passed the caller's terminal/tool checks. */
    void complete(String finalText)throws Exception {
        if(stopped||completed)throw new SecurityException("原QQ流已结束，不能重放最终帧");origin.check();control.check();drain(true);
        if(finalText==null||finalText.trim().isEmpty()||finalText.length()>4000)throw new IllegalArgumentException("QQ最终完整文字超出此原会话范围");
        post(finalText,10);completed=true;
    }

    private void drain(boolean end){
        String value=pending.toString();if(!credential.isEmpty())value=value.replace(credential,"[凭据已隐藏]");int keep=0;
        if(!end&&!credential.isEmpty())for(int n=Math.min(credential.length()-1,value.length());n>0;n--)if(value.regionMatches(value.length()-n,credential,0,n)){keep=n;break;}
        int take=value.length()-keep;if(take>0&&Character.isHighSurrogate(value.charAt(take-1)))take--;
        visible.append(value,0,take);pending.setLength(0);pending.append(value.substring(take));
    }

    private void post(String text,int state)throws Exception {
        origin.check();control.check();
        if(lastPostNanos!=0){long wait=300_000_000L-(System.nanoTime()-lastPostNanos);if(wait>0)control.sleep((wait+999_999L)/1_000_000L);origin.check();control.check();}
        if(!registered){core.queueQqStream(id,event,profile.getString("transportEpoch"),requestId==null?"final_only_no_provider_deltas":requestId);registered=true;}
        JSONObject frame=core.reserveQqStreamFrame(id,text,state,receivedDeltas);
        try{
            ChannelHttp.DispatchGuard guard=()->{origin.check();control.check();core.checkQqStreamFrame(id,frame);};
            JSONObject receipt=http.sendQqStream(profile,credential,event,frame,control,guard);guard.check();core.ackQqStreamFrame(id,frame,receipt);acknowledgedFrames++;lastPostNanos=System.nanoTime();changed.run();
        }catch(Exception unconfirmed){failed("原QQ流分片发送结果未知；保留原index与消息编号，不重放或改发普通消息");throw unconfirmed;}
    }

    void failed(String reason){
        if(stopped||completed)return;stopped=true;pending.setLength(0);
        if(registered)try{core.stopQqStream(id,reason);changed.run();}catch(Exception ignored){/* In-flight/persisted sending reopens as unknown, never as queued. */}
    }
}
