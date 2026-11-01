package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native iLink lifecycle: login, private text polling, cursor and encrypted reply context. */
final class WeixinAdapter implements AutoCloseable {
    interface Accepted {void handle(JSONObject profile,JSONObject accepted)throws Exception;}
    private final FeatureContext context;
    private final ChannelCore core;
    private final Accepted accepted;
    private final java.util.function.Consumer<String> profileChanging;
    private final WeixinClient client;
    private final WeixinOwnerImages ownerImages;
    private final OwnerDmMedia ownerMedia;
    private final Map<String,Login> logins=new ConcurrentHashMap<>();
    private final Map<String,RunControl> monitors=new ConcurrentHashMap<>();
    private final Map<String,JSONObject> states=new ConcurrentHashMap<>();
    private final Map<String,AtomicBoolean> pulls=new ConcurrentHashMap<>();
    private final Map<String,RunControl> activePulls=new ConcurrentHashMap<>();
    private volatile boolean closed;
    WeixinAdapter(FeatureContext context,ChannelCore core,Accepted accepted,java.util.function.Consumer<String> profileChanging){this(context,core,accepted,profileChanging,new WeixinClient());}
    WeixinAdapter(FeatureContext context,ChannelCore core,Accepted accepted,java.util.function.Consumer<String> profileChanging,WeixinClient client){this(context,core,accepted,profileChanging,client,new GroupInboundFiles());}
    WeixinAdapter(FeatureContext context,ChannelCore core,Accepted accepted,java.util.function.Consumer<String> profileChanging,WeixinClient client,GroupInboundFiles download){this(context,core,accepted,profileChanging,client,download,new OwnerDmMedia(context,core,download));}
    WeixinAdapter(FeatureContext context,ChannelCore core,Accepted accepted,java.util.function.Consumer<String> profileChanging,WeixinClient client,GroupInboundFiles download,OwnerDmMedia ownerMedia){this.context=context;this.core=core;this.accepted=accepted;this.profileChanging=profileChanging;this.client=client;this.ownerMedia=ownerMedia;ownerImages=new WeixinOwnerImages(context,core,download);}

    JSONObject handle(String command,JSONObject input)throws Exception {
        if(closed)throw new IllegalStateException("微信接入已关闭");
        switch(command){
            case "weixin.login.start":return startLogin(input);
            case "weixin.login.inspect":return inspectLogin(input);
            case "weixin.login.poll":return pollLogin(input);
            case "weixin.login.cancel":return cancelLogin(input);
            case "weixin.poll":return pull(ChannelCore.identifier(input,"id"),new RunControl());
            case "weixin.start":return start(ChannelCore.identifier(input,"id"));
            case "weixin.stop":{String id=ChannelCore.identifier(input,"id");cancel(id);return new JSONObject().put("requested",true).put("message","已停止接收；已投递消息不会撤回");}
            default:throw new IllegalArgumentException("未知微信接入操作");
        }
    }
    JSONObject snapshot()throws Exception {
        JSONArray rows=new JSONArray();for(Map.Entry<String,JSONObject> row:states.entrySet())rows.put(new JSONObject(row.getValue().toString()).put("profileId",row.getKey()).put("monitorRunning",monitors.containsKey(row.getKey())));
        return new JSONObject().put("available",true).put("accountType","weixin_ilink_bot").put("personalChatHistory",false).put("wecom",false).put("officialAccount",false).put("requiresPublicCallback",false).put("privateText",true).put("groupMessages",false).put("media",false).put("ownerDmSelectedMedia",true).put("ownerDmSelectedMediaConsentDefault",false).put("ownerDmSelectedMediaMaxBytes",OwnerDmMedia.MAX_BYTES).put("automaticMediaUnderstanding",false).put("ownerDmImages",true).put("ownerDmImageConsentDefault",false).put("ownerDmImageFormats",new JSONArray().put("png").put("jpeg").put("webp")).put("ownerDmImageLimits","4images_4MiB_each_8MiB_total_12MP_each").put("mixedItemText",true).put("nativeQuotes",true).put("idOnlyQuoteRestore",true).put("quoteSource","original_inbox_same_account_peer_scope_and_transport").put("quoteBounds","max200_original_messages_30days").put("unsupportedItemsExplained",true)
                .put("backgroundPersistence","app_process_only").put("connections",rows);
    }
    private JSONObject startLogin(JSONObject input)throws Exception {
        String name=input.optString("name","我的微信").trim();if(name.isEmpty()||name.length()>80)throw new IllegalArgumentException("连接名称需为1至80字");
        String id=input.has("loginId")?loginId(input):UUID.randomUUID().toString();Login login;
        // Register before HTTP. A close overtaking queued start fences this same transient row.
        synchronized(logins){if(closed)throw new SecurityException("原登录宿主已关闭");pruneLogins();Login previous=logins.get(id);if(previous!=null){if(!previous.name.isEmpty()&&!previous.name.equals(name))throw new SecurityException("原登录编号不能改用于另一配置");return loginView(id,previous);}
            int active=0;for(Login row:logins.values())if(!row.control.isCancelled()&&!row.status.matches("confirmed|failed|cancelled|expired|verify_code_blocked"))active++;
            if(active>=3||logins.size()>=32)throw new IllegalStateException("请先完成或取消已有扫码登录");login=new Login(name,new RunControl());logins.put(id,login);}
        final Login original=login;login.control.setAuthorityCheck(()->{if(closed||logins.get(id)!=original||System.currentTimeMillis()>original.expiresAt)throw new SecurityException("原登录已关闭或到期");});
        try{JSONObject qr=client.startLogin(login.control);login.control.check();String code=qr.optString("qrcode"),content=qr.optString("qrcode_img_content");
            if(code.isEmpty()||code.length()>4096||content.isEmpty()||content.length()>16000)throw new IllegalStateException("微信未返回有效二维码");
            synchronized(login){login.control.check();if(logins.get(id)!=login||closed)throw new SecurityException("原登录页面已关闭");login.code=code;login.content=content;login.status="awaiting_scan";}
            return loginView(id,login);
        }catch(Exception failure){synchronized(login){if(!login.status.equals("cancelled"))login.status="failed";login.content="";login.code="";}throw failure;}
    }
    private void pruneLogins(){for(Map.Entry<String,Login> entry:logins.entrySet()){Login row=entry.getValue();synchronized(row){if(row.expiresAt<System.currentTimeMillis()){row.control.cancel();logins.remove(entry.getKey(),row);}}}}
    private static String loginId(JSONObject input)throws Exception {String id=ChannelCore.identifier(input,"loginId");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("原微信登录编号无效");return id;}
    private JSONObject inspectLogin(JSONObject input)throws Exception {String id=loginId(input);Login login=logins.get(id);if(login==null)return new JSONObject().put("loginId",id).put("status","unknown").put("ownerBound",false).put("message","原登录会话不在此进程中；先核对已保存账号，再明确重新开始。未自动创建新登录。");return loginView(id,login);}
    private JSONObject loginView(String id,Login login)throws Exception {
        synchronized(login){String status=System.currentTimeMillis()>login.expiresAt?"expired":login.status;JSONObject view=new JSONObject().put("loginId",id).put("status",status).put("expiresAt",login.expiresAt).put("ownerBound",false);
            if(!login.content.isEmpty()&&!status.matches("expired|cancelled|failed|confirmed"))view.put("qrcodeContent",login.content);
            if(status.equals("confirmed")&&!login.profileId.isEmpty())view.put("profile",core.profile(login.profileId)).put("authenticated",true);
            if(status.equals("cancelled"))view.put("message","本机已取消这次登录检查；未删除已保存账号，也不声称已撤回微信端已发生的登录。");return view;}
    }
    private JSONObject cancelLogin(JSONObject input)throws Exception {
        String id=loginId(input);Login login;
        synchronized(logins){pruneLogins();login=logins.get(id);if(login==null){if(logins.size()>=32)throw new IllegalStateException("原登录清理槽已满，请等待原期限后核对");login=new Login("",new RunControl());logins.put(id,login);}}
        synchronized(login){if(login.status.equals("confirmed"))return loginView(id,login).put("cancelled",false);login.status="cancelled";login.content="";login.code="";login.control.cancel();}
        return loginView(id,login).put("cancelled",true).put("localStartFence",true);
    }
    private JSONObject pollLogin(JSONObject input)throws Exception {
        String id=loginId(input);Login login=logins.get(id);
        if(login==null)return inspectLogin(input);
        if(login.expiresAt<System.currentTimeMillis()){login.control.cancel();return loginView(id,login);}
        if(login.code.isEmpty()||login.status.matches("cancelled|failed|confirmed"))return loginView(id,login);
        if(!login.polling.compareAndSet(false,true))throw new IllegalStateException("正在检查此二维码，请稍候");
        try{
            JSONObject raw=client.pollLogin(login.base,login.code,input.optString("verifyCode"),login.control);login.control.check();String status=raw.optString("status");
            synchronized(login){
            login.control.check();if(closed||logins.get(id)!=login)throw new SecurityException("原登录宿主已关闭或身份变化");
            JSONObject safe=new JSONObject().put("loginId",id).put("status",status).put("ownerBound",false);
            if(status.equals("scaned_but_redirect")){
                String host=raw.optString("redirect_host");if(host.isEmpty())throw new IllegalStateException("微信未返回继续登录的地址");
                login.base=WeixinClient.validateApiBase(host.contains("://")?host:"https://"+host);login.status=status;return safe;
            }
            if(status.equals("confirmed")){synchronized(login){
                login.control.check();
                String token=raw.optString("bot_token"),bot=ChannelCore.identifier(raw,"ilink_bot_id"),base=WeixinClient.validateApiBase(raw.optString("baseurl",WeixinClient.DEFAULT_BASE));
                if(token.isEmpty()||token.length()>8192||token.indexOf('\r')>=0||token.indexOf('\n')>=0)throw new IllegalStateException("微信未返回有效登录凭据");
                String profileId=UUID.randomUUID().toString();boolean reconnect=false;JSONArray profiles=core.snapshot().getJSONArray("profiles");
                for(int i=0;i<profiles.length();i++){JSONObject existing=profiles.getJSONObject(i);if(existing.optString("provider").equals("weixin")&&bot.equals(existing.optString("appId"))){profileId=existing.getString("id");reconnect=true;break;}}
                profileChanging.accept(profileId);if(reconnect)core.prepareCredentialChange(profileId);context.secrets.put(secretSlot(profileId),token);resetContexts(profileId);
                JSONObject profile;
                try{profile=core.saveProfile(new JSONObject().put("id",profileId).put("name",login.name).put("provider","weixin").put("appId",bot).put("apiBase",base).put("enabled",true).put("autoReply",false));}
                catch(Exception failure){if(!reconnect)context.secrets.remove(secretSlot(profileId));throw failure;}
                login.profileId=profileId;login.status="confirmed";login.content="";login.code="";
                safe.put("profile",profile).put("authenticated",true).put("message","已登录；主人绑定仍需收到私聊后由本机确认，尚未自动开始回复");emit("weixin_logged_in",profileId);return safe;
            }}
            if(status.equals("expired")||status.equals("verify_code_blocked")){login.status=status;login.content="";login.code="";login.control.cancel();return safe;}
            if(!status.matches("wait|scaned|expired|need_verifycode|verify_code_blocked|binded_redirect"))throw new IllegalStateException("微信返回未知登录状态，未保存凭据");
            login.status=status;
            return safe;
            }
        }finally{login.polling.set(false);}
    }
    private JSONObject start(String id)throws Exception {
        requireProfile(id);if(monitors.size()>=3&&!monitors.containsKey(id))throw new IllegalStateException("当前最多同时接收3个微信账号");
        RunControl control=new RunControl();if(monitors.putIfAbsent(id,control)!=null)throw new IllegalStateException("此微信连接已在接收");
        state(id,"starting","正在连接微信iLink，尚未确认同步成功",0);
        Thread thread=new Thread(()->{
            try{while(!closed&&!control.isCancelled()){
                try{pull(id,control);control.sleep(300);}
                catch(WeixinClient.SessionExpired expired){state(id,"login_required","微信授权已失效，请重新扫码",0);break;}
                catch(InterruptedException cancelled){break;}
                catch(Exception failed){state(id,"retry_wait","接收未完成，保留原同步位置后重连",0);control.sleep(5000);}
            }}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            finally{monitors.remove(id,control);try{JSONObject previous=states.get(id);if(previous==null||!previous.optString("status").equals("login_required"))state(id,"stopped","接收已停止",0);}catch(Exception ignored){}}
        },"pocket-weixin-poll");thread.setDaemon(true);thread.start();
        return new JSONObject().put("profileId",id).put("status","starting").put("persistentAfterProcessDeath",false);
    }
    private JSONObject pull(String id,RunControl control)throws Exception {
        AtomicBoolean lock=pulls.computeIfAbsent(id,key->new AtomicBoolean());if(!lock.compareAndSet(false,true))throw new IllegalStateException("此微信账号已有接收请求");
        activePulls.put(id,control);
        try{
            JSONObject profile=requireProfile(id);String credential=context.secrets.get(secretSlot(id));if(credential.isEmpty())throw new SecurityException("请先扫码登录微信");
            String scope=cursorScope(profile,credential),stored=context.secrets.get(cursorSlot(id)),cursor="";
            // Older unscoped cursors are not silently promoted to a newly authenticated account.
            if(stored.startsWith("{")){JSONObject saved=new JSONObject(stored);if(scope.equals(saved.optString("scope")))cursor=saved.optString("cursor");}
            ChannelHttp.DispatchGuard guard=()->{control.check();if(!scope.equals(cursorScope(requireProfile(id),context.secrets.get(secretSlot(id)))))throw new SecurityException("微信原账号/凭据/接收代次已变化");};
            JSONObject response=client.poll(profile.getString("apiBase"),credential,cursor,control,guard);guard.check();JSONArray messages=response.getJSONArray("msgs");int received=0,skipped=0;
            for(int i=0;i<messages.length();i++){
                control.check();JSONObject raw=messages.optJSONObject(i);if(raw==null){skipped++;continue;}
                JSONObject canonical;try{canonical=WeixinClient.canonical(raw);}catch(IllegalArgumentException malformed){skipped++;continue;}
                if(canonical==null){skipped++;continue;}
                if(canonical.getLong("sentAt")>System.currentTimeMillis()+300_000){skipped++;continue;}
                guard.check();JSONObject incoming=core.accept(id,canonical,"weixin_session");String eventId=incoming.optBoolean("duplicate")?incoming.getString("eventId"):incoming.getJSONObject("event").getString("id");
                String token=raw.optString("context_token");if(!token.isEmpty()&&token.length()<=4096)saveContext(id,eventId,token);
                if(!incoming.optBoolean("duplicate")){
                    try{ownerImages.capture(incoming.getJSONObject("event"),raw,control);}catch(Exception unavailable){control.check();core.eventStatus(eventId,"awaiting_local","原图片下载引用未能保留；文字仍在原消息中，未下载或假称看图");}
                    try{ownerMedia.captureWeixin(incoming.getJSONObject("event"),raw,control);}catch(Exception unavailable){control.check();core.eventStatus(eventId,"awaiting_local","原文件/音视频下载引用未能保留；没有下载或假称已读媒体");}
                    received++;
                    if(System.currentTimeMillis()-canonical.getLong("sentAt")>3_600_000)core.eventStatus(eventId,"awaiting_local","已保留旧消息，未自动回答或执行；请由本机选择处理");
                    else{guard.check();accepted.handle(profile,incoming);}
                }
            }
            guard.check();String next=response.optString("get_updates_buf");if(!next.isEmpty())context.secrets.put(cursorSlot(id),new JSONObject().put("version",2).put("scope",scope).put("cursor",next).put("savedAt",System.currentTimeMillis()).toString());guard.check();
            state(id,monitors.containsKey(id)?"listening":"synchronized","已完成一次官方消息同步",System.currentTimeMillis());
            return new JSONObject().put("profileId",id).put("received",received).put("skippedUnsupported",skipped).put("authenticated",true).put("cursorSaved",!next.isEmpty()).put("status","synchronized");
        }finally{activePulls.remove(id,control);lock.set(false);}
    }
    boolean ownerImagesEligible(JSONObject event)throws Exception {return ownerImages.eligible(event);}
    JSONObject frozenRemoteInputSource(String id,String hash)throws Exception{return ownerImages.frozenRemoteInputSource(id,hash);}
    WeixinOwnerImages.Projection ownerImages(JSONObject event,dev.pocketengine.core.ModelConfig model,RunControl control)throws Exception{return ownerImages.project(event,model,control);}
    void checkOwnerImages(JSONObject event,WeixinOwnerImages.Projection projection,RunControl control)throws Exception{ownerImages.checkProjection(event,projection,control);}
    JSONObject send(JSONObject profile,JSONObject event,JSONObject draft,RunControl control,ChannelHttp.DispatchGuard guard)throws Exception {
        return client.send(profile.getString("apiBase"),context.secrets.get(secretSlot(profile.getString("id"))),event,draft,getContext(profile.getString("id"),event.getString("id")),control,guard);
    }
    JSONObject sendMedia(JSONObject profile,JSONObject event,JSONObject row,JSONObject format,byte[] bytes,RunControl control,ChannelHttp.DispatchGuard guard,WeixinClient.MediaSteps steps)throws Exception {
        return client.sendMedia(profile.getString("apiBase"),context.secrets.get(secretSlot(profile.getString("id"))),event,row,getContext(profile.getString("id"),event.getString("id")),format,bytes,control,guard,steps);
    }
    private JSONObject requireProfile(String id)throws Exception {JSONObject profile=core.profile(id);if(!profile.optString("provider").equals("weixin")||!profile.optBoolean("enabled"))throw new SecurityException("微信连接不存在或已暂停");WeixinClient.validateApiBase(profile.getString("apiBase"));return profile;}
    synchronized void resetContexts(String id)throws Exception {context.secrets.remove(cursorSlot(id));context.secrets.remove(contextSlot(id));ownerImages.reset(id);ownerMedia.reset(id);states.remove(id);}
    private synchronized void saveContext(String id,String eventId,String token)throws Exception {
        String stored=context.secrets.get(contextSlot(id));JSONObject all=stored.isEmpty()?new JSONObject():new JSONObject(stored);all.remove(eventId);all.put(eventId,token);
        while(all.length()>100||all.toString().length()>48000){String first=all.keys().next();if(first.equals(eventId)&&all.length()>1){java.util.Iterator<String> keys=all.keys();keys.next();first=keys.next();}all.remove(first);}
        context.secrets.put(contextSlot(id),all.toString());
    }
    private synchronized String getContext(String id,String eventId)throws Exception {String stored=context.secrets.get(contextSlot(id));return stored.isEmpty()?"":new JSONObject(stored).optString(eventId);}
    void cancel(String id){RunControl control=monitors.get(id);if(control!=null)control.cancel();RunControl pull=activePulls.get(id);if(pull!=null)pull.cancel();}
    boolean isRunning(String id){return monitors.containsKey(id);}
    private void state(String id,String status,String message,long success) {try{JSONObject previous=states.get(id);long last=success>0?success:previous==null?0:previous.optLong("lastSyncAt");states.put(id,new JSONObject().put("status",status).put("message",message).put("lastSyncAt",last));emit("weixin_state",id);}catch(Exception ignored){/* Status telemetry never changes an authenticated event or its durable cursor. */}}
    private void emit(String type,String id)throws Exception{context.emit("channels",new JSONObject().put("type",type).put("profileId",id));}
    private static String secretSlot(String id){return "channels."+id+".secret";}
    private static String cursorScope(JSONObject profile,String credential)throws Exception{return ChannelCore.digest(new JSONArray().put("weixin_cursor_v2").put(profile.getString("id")).put(profile.getString("appId")).put(profile.getString("apiBase")).put(profile.getString("transportEpoch")).put(ChannelCore.digest(credential)).toString());}
    private static String cursorSlot(String id){return "channels."+id+".weixin-cursor";}
    private static String contextSlot(String id){return "channels."+id+".weixin-context";}
    @Override public void close(){closed=true;for(RunControl control:monitors.values())control.cancel();for(RunControl control:activePulls.values())control.cancel();for(Login login:logins.values())synchronized(login){login.control.cancel();}logins.clear();}
    private static final class Login {final String name;final RunControl control;final AtomicBoolean polling=new AtomicBoolean();final long expiresAt=System.currentTimeMillis()+300_000;volatile String code="",content="",status="starting",profileId="",base=WeixinClient.DEFAULT_BASE;Login(String name,RunControl control){this.name=name;this.control=control;}}
}
