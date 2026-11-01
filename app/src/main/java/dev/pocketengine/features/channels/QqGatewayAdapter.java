package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Outbound official-bot receiver. The existing core/host/outbox remain the sole authorities. */
final class QqGatewayAdapter implements AutoCloseable {
    interface Credential {String get(String id)throws Exception;}
    private final ChannelCore core;
    private final ChannelHttp delivery;
    private final Credential credentials;
    private final WeixinAdapter.Accepted accepted;
    private final java.util.function.Consumer<String> changed;
    private final QqGatewayClient client;
    private final Map<String,RunControl> monitors=new ConcurrentHashMap<>();
    private final Map<String,JSONObject> states=new ConcurrentHashMap<>();
    private volatile boolean closed;
    QqGatewayAdapter(FeatureContext context,ChannelCore core,ChannelHttp delivery,WeixinAdapter.Accepted accepted){
        this(context,core,delivery,accepted,new QqGatewayClient());
    }
    QqGatewayAdapter(FeatureContext context,ChannelCore core,ChannelHttp delivery,WeixinAdapter.Accepted accepted,QqGatewayClient client){
        this(core,delivery,id->context.secrets.get("channels."+id+".secret"),accepted,id->{
            try{context.emit("channels",new JSONObject().put("type","qq_gateway_state").put("profileId",id));}catch(Exception ignored){}
        },client);
    }
    QqGatewayAdapter(ChannelCore core,ChannelHttp delivery,Credential credentials,WeixinAdapter.Accepted accepted,java.util.function.Consumer<String> changed,QqGatewayClient client){
        this.core=core;this.delivery=delivery;this.credentials=credentials;this.accepted=accepted;this.changed=changed;this.client=client;
    }
    JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new IllegalStateException("QQ接收已关闭");String id=ChannelCore.identifier(input,"id");
        switch(command){
            case "qq.start":return start(id);
            case "qq.stop":cancel(id);return new JSONObject().put("profileId",id).put("requested",true).put("message","已请求停止QQ接收；已有任务请在原任务中停止");
            default:throw new IllegalArgumentException("未知QQ接入操作");
        }
    }
    JSONObject snapshot()throws Exception {
        JSONArray rows=new JSONArray();for(Map.Entry<String,JSONObject> entry:states.entrySet())rows.put(new JSONObject(entry.getValue().toString()).put("profileId",entry.getKey()).put("monitorRunning",monitors.containsKey(entry.getKey())));
        return new JSONObject().put("officialBot",true).put("personalAccount",false).put("transport","outbound_wss").put("requiresPublicCallback",false)
                .put("backgroundPersistence","app_process_only").put("connections",rows);
    }
    private synchronized JSONObject start(String id)throws Exception {
        JSONObject profile=requireProfile(id);String secret=credentials.get(id);if(secret==null||secret.isEmpty())throw new SecurityException("请先配置QQ官方机器人AppID和AppSecret");
        if(monitors.size()>=3&&!monitors.containsKey(id))throw new IllegalStateException("当前最多同时接收3个QQ机器人");
        RunControl control=new RunControl();if(monitors.putIfAbsent(id,control)!=null)throw new IllegalStateException("此QQ连接正在接收或停止中");
        String fingerprint=configurationFingerprint(profile),credentialFingerprint=ChannelCore.digest(secret);
        ChannelHttp.DispatchGuard guard=()->{
            control.check();if(closed||!fingerprint.equals(configurationFingerprint(requireProfile(id)))||!credentialFingerprint.equals(ChannelCore.digest(credentials.get(id))))throw new SecurityException("QQ接入配置已变化");
        };
        state(id,"starting","正在连接QQ官方网关",0);
        Thread thread=new Thread(()->run(id,profile,secret,control,guard),"pocket-qq-gateway");thread.setDaemon(true);thread.start();
        return new JSONObject().put("profileId",id).put("status","starting").put("transport","outbound_wss").put("persistentAfterProcessDeath",false);
    }
    private void run(String id,JSONObject profile,String secret,RunControl control,ChannelHttp.DispatchGuard guard){
        QqGatewaySession.Resume resume=new QqGatewaySession.Resume();int failures=0,authenticationFailures=0;boolean blocked=false;
        try{while(!closed&&!control.isCancelled()){
            long wait=3000;
            try{
                guard.check();ChannelHttp.GatewayAccess access=delivery.qqGateway(profile,secret,control,guard);guard.check();
                QqGatewayClient.Exit exit=client.run(access,resume,control,guard,new QqGatewaySession.Events(){
                    public void ready(boolean resumed)throws Exception{guard.check();state(id,"listening",resumed?"QQ官方会话已恢复":"QQ官方会话已认证，正在接收文字消息",System.currentTimeMillis());}
                    public void message(JSONObject payload)throws Exception{receive(id,profile,payload,control,guard);}
                });
                guard.check();if(exit.clearSession)resume.clear();
                if(exit.refreshToken){delivery.forgetQqToken(id);if(++authenticationFailures>=2){blocked=true;state(id,"authorization_required","QQ重复拒绝更新后的凭据，请核对机器人授权与平台权限",0);break;}}
                else authenticationFailures=0;
                if(exit.fatal){blocked=true;state(id,"platform_blocked","QQ平台拒绝接收，请检查机器人状态、沙箱成员与事件权限；未改用其他账号或公网桥接",0);break;}
                wait=exit.minimumDelay;
            }catch(InterruptedException stopped){break;}
            catch(SecurityException revoked){blocked=true;state(id,"configuration_changed","QQ配置已变更，接收已停止，请由本机重新启动",0);break;}
            catch(ChannelHttp.Rejected rejected){blocked=true;state(id,"authorization_required","QQ拒绝访问凭据或网关请求，请核对AppID、凭据及平台权限",0);break;}
            catch(Exception unavailable){/* The durable core and original task are retained, never replayed as a new task. */}
            guard.check();wait=Math.max(wait,Math.min(60_000,3000L<<Math.min(failures++,4)));
            state(id,"retry_wait","QQ接收连接中断，将恢复原会话；未重试任务或消息发送",0);control.sleep(wait);
        }}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Exception stopped){blocked=true;state(id,"configuration_changed","QQ接收授权已失效，请由本机重新启动",0);}
        finally{resume.clear();monitors.remove(id,control);if(!blocked)state(id,"stopped","QQ接收已停止",0);}
    }
    private void receive(String id,JSONObject profile,JSONObject payload,RunControl control,ChannelHttp.DispatchGuard guard)throws Exception {
        guard.check();JSONObject canonical;
        try{canonical=ChannelProtocol.canonicalQq(payload);}catch(IllegalArgumentException unsupported){return;}
        long age=System.currentTimeMillis()-canonical.getLong("sentAt");if(age< -300_000)return;
        control.check();guard.check();JSONObject incoming=core.accept(id,canonical,"qq_gateway");
        if(incoming.getBoolean("duplicate"))return;
        String eventId=incoming.getJSONObject("event").getString("id");
        if(age>3_600_000){core.eventStatus(eventId,"awaiting_local","已保留QQ旧消息，未自动回答或执行，请由本机选择处理");return;}
        guard.check();accepted.handle(profile,incoming);
    }
    private JSONObject requireProfile(String id)throws Exception {JSONObject profile=core.profile(id);if(!profile.optString("provider").equals("qq")||!profile.optBoolean("enabled"))throw new SecurityException("QQ官方机器人连接不存在或已暂停");return profile;}
    private static String configurationFingerprint(JSONObject profile)throws Exception {
        // Only native setup fields fence a receiver. beginAnswer updates quotaWindowStart/
        // quotaUsed on the same profile; normal runtime accounting must not revoke its socket.
        JSONArray configuration=new JSONArray();
        for(String key:new String[]{"id","name","provider","appId","enabled","apiBase","autoReply","replyUrl","maxRepliesPerHour","updatedAt","transportEpoch"})configuration.put(profile.get(key));
        return ChannelCore.digest(configuration.toString());
    }
    boolean isRunning(String id){return monitors.containsKey(id);}
    // Cancellation must not wait for another profile's synchronized token HTTP request.
    // Cache entries remain credential-fingerprint bound and refresh on actual auth rejection.
    void cancel(String id){RunControl control=monitors.get(id);if(control!=null)control.cancel();}
    void forget(String id){states.remove(id);delivery.forgetQqToken(id);}
    private void state(String id,String status,String message,long success){
        try{core.profile(id);JSONObject prior=states.get(id);states.put(id,new JSONObject().put("status",status).put("message",message).put("lastSyncAt",success>0?success:prior==null?0:prior.optLong("lastSyncAt")));changed.accept(id);}catch(IllegalArgumentException removed){states.remove(id);}catch(Exception ignored){}
    }
    @Override public void close(){closed=true;for(RunControl control:monitors.values())control.cancel();client.close();}
}
