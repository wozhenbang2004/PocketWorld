package dev.pocketengine.features.channels;

import org.json.JSONArray;
import org.json.JSONObject;

/** Independently authored QQ protocol state. It does not own tasks, messages or permissions. */
final class QqGatewaySession {
    interface Sender {void send(JSONObject payload)throws Exception;}
    interface Events {void message(JSONObject payload)throws Exception;void ready(boolean resumed)throws Exception;}
    static final class Resume {
        private String id="";private long sequence=-1;
        void clear(){id="";sequence=-1;}
    }
    private final Resume resume;
    private final String token;
    private final Sender sender;
    private final Events events;
    private final ChannelHttp.DispatchGuard guard;
    private final long openedAt;
    private boolean hello,ready,resuming,awaitingAck,retired;
    private long interval,nextHeartbeat;

    QqGatewaySession(Resume resume,String token,Sender sender,Events events,ChannelHttp.DispatchGuard guard,long now){
        this.resume=resume;this.token=token;this.sender=sender;this.events=events;this.guard=guard;openedAt=now;
    }
    synchronized void receive(String text,long now)throws Exception {
        check();if(text.length()>65_536)throw new IllegalArgumentException("QQ网关消息过大");
        JSONObject payload=new JSONObject(text);long op=integer(payload,"op");
        if(op==10){
            if(hello)throw new IllegalArgumentException("QQ网关重复握手");
            interval=integer(payload.getJSONObject("d"),"heartbeat_interval");
            if(interval<1000||interval>300_000)throw new IllegalArgumentException("QQ网关心跳周期无效");
            hello=true;nextHeartbeat=now+interval;resuming=!resume.id.isEmpty()&&resume.sequence>=0;
            JSONObject data=new JSONObject().put("token","QQBot "+token);
            if(resuming){data.put("session_id",resume.id).put("seq",resume.sequence);send(new JSONObject().put("op",6).put("d",data));}
            else{resume.clear();data.put("intents",1<<25).put("shard",new JSONArray().put(0).put(1));send(new JSONObject().put("op",2).put("d",data));}
            return;
        }
        if(!hello)throw new IllegalArgumentException("QQ网关尚未握手");
        if(op==11){awaitingAck=false;return;}
        if(op==1){heartbeat();return;}
        if(op==7)throw new Reconnect(false);
        if(op==9){resume.clear();throw new Reconnect(true);}
        if(op!=0)throw new IllegalArgumentException("不支持的QQ网关操作");
        long sequence=integer(payload,"s");String type=payload.optString("t");
        if(type.equals("READY")){
            if(ready||resuming)throw new IllegalArgumentException("QQ网关登录状态无效");
            String id=ChannelCore.identifier(payload.getJSONObject("d"),"session_id");
            resume.id=id;resume.sequence=sequence;ready=true;events.ready(false);return;
        }
        if(type.equals("RESUMED")){
            if(!resuming||ready)throw new IllegalArgumentException("QQ网关恢复状态无效");
            resume.sequence=Math.max(resume.sequence,sequence);ready=true;events.ready(true);return;
        }
        // QQ replays missed events before RESUMED. Only a previously authenticated session
        // may take this path; a fresh IDENTIFY must receive READY before any message.
        if(!ready&&!resuming)throw new SecurityException("QQ网关尚未完成账号认证");
        if(sequence<=resume.sequence)return;
        if(type.equals("C2C_MESSAGE_CREATE")||type.equals("GROUP_AT_MESSAGE_CREATE")||type.equals("GROUP_MESSAGE_CREATE")){
            check();events.message(payload);check();
        }
        // Advance only after the actual consumer has persisted/handled this event.
        resume.sequence=sequence;
    }
    synchronized void tick(long now)throws Exception {
        check();if(!ready&&now-openedAt>=30_000)throw new IllegalStateException("QQ网关登录未在时限内完成");
        if(hello&&now>=nextHeartbeat){if(awaitingAck)throw new Reconnect(false);heartbeat();nextHeartbeat=now+interval;}
    }
    private void heartbeat()throws Exception{send(new JSONObject().put("op",1).put("d",resume.sequence<0?JSONObject.NULL:resume.sequence));awaitingAck=true;}
    private void send(JSONObject payload)throws Exception{check();sender.send(payload);}
    private void check()throws Exception{if(retired)throw new InterruptedException("QQ连接已结束");guard.check();}
    synchronized void retire(){retired=true;}
    private static long integer(JSONObject object,String key)throws Exception {
        Object value=object.opt(key);if(!(value instanceof Integer)&&!(value instanceof Long))throw new IllegalArgumentException("QQ网关整数无效");
        long result=((Number)value).longValue();if(result<0)throw new IllegalArgumentException("QQ网关整数无效");return result;
    }
    static final class Reconnect extends Exception {final boolean reset;Reconnect(boolean reset){super("QQ网关要求重连");this.reset=reset;}}
}
