package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Small bounded HTTPS client for explicitly configured reply destinations. */
final class ChannelHttp {
    interface DispatchGuard {void check()throws Exception;}
    private final Map<String,Token> tokens=new HashMap<>();
    static final String QQ_BASE="https://api.bot.qq.com";
    private final String qqBase;
    ChannelHttp(){qqBase=QQ_BASE;}
    /** Package-private loopback fixture, never a configurable production credential destination. */
    ChannelHttp(String testBase)throws Exception {
        URI uri=new URI(testBase);
        if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1||!uri.getPath().isEmpty()||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new SecurityException("测试接口必须是本机回环根地址");
        qqBase=testBase;
    }
    static void validateReplyUrl(String address)throws Exception {
        if(address.isEmpty())return;
        URI url=new URI(address);String host=url.getHost();
        boolean loopback="127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host)||"[::1]".equals(host)||"::1".equals(host);
        if(host==null||url.getUserInfo()!=null||url.getFragment()!=null||url.getQuery()!=null
                ||(!"https".equalsIgnoreCase(url.getScheme())&&!(loopback&&"http".equalsIgnoreCase(url.getScheme()))))
            throw new IllegalArgumentException("回复地址需为HTTPS；本机调试可使用回环HTTP");
    }
    static boolean sameOrigin(String first,String second) {
        if(first.isEmpty()||second.isEmpty())return first.equals(second);
        try {
            URI a=new URI(first),b=new URI(second);
            return a.getHost()!=null&&b.getHost()!=null&&a.getScheme().equalsIgnoreCase(b.getScheme())
                    &&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);
        }catch(Exception invalid){return false;}
    }
    private static int port(URI uri){return uri.getPort()>=0?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;}
    JSONObject sendRelay(String url,String secret,JSONObject payload,RunControl control,DispatchGuard guard)throws Exception {
        if(url.isEmpty())throw new IllegalStateException("请先配置消息桥接回复地址");validateReplyUrl(url);
        return post(url,"Bearer "+secret,payload,control,guard);
    }
    JSONObject probeQq(JSONObject profile,String secret)throws Exception {
        String token=qqToken(profile,secret,new RunControl(),()->{});
        return new JSONObject().put("authenticated",!token.isEmpty()).put("message","QQ访问凭证获取成功；消息接收和发送尚待平台联调");
    }
    JSONObject sendQq(JSONObject profile,String secret,JSONObject event,JSONObject outbox,RunControl control,DispatchGuard guard)throws Exception {
        String path=QqConversation.endpoint(event);
        JSONObject payload=new JSONObject().put("content",outbox.getString("text")).put("msg_id",event.getString("messageId"));
        // Official guild channel/DM methods have their own schema, not the v2 group/C2C body.
        if(!QqConversation.guild(event))payload.put("msg_type",0).put("msg_seq",outbox.getInt("sequence"));
        String token=qqToken(profile,secret,control,guard);
        guard.check();control.check();
        return post(qqBase+path,"QQBot "+token,payload,control,guard);
    }
    /** Native group media only: upload does not post a message, and an unknown upload is never retried. */
    JSONObject uploadGroupMedia(JSONObject profile,String secret,JSONObject event,String mime,byte[] bytes,RunControl control,DispatchGuard guard)throws Exception {
        if(!QqConversation.scene(event).equals(QqConversation.GROUP)||bytes==null||bytes.length>20*1024*1024L)throw new SecurityException("群媒体来源或大小无效");
        String room=ChannelCore.identifier(event,"roomId");int type=mime.startsWith("image/")?1:mime.startsWith("video/")?2:mime.startsWith("audio/")?3:4;
        JSONObject payload=new JSONObject().put("file_type",type).put("srv_send_msg",false).put("file_name","group-artifact")
                .put("file_data",java.util.Base64.getEncoder().encodeToString(bytes));
        String token=qqToken(profile,secret,control,guard);guard.check();control.check();return request(qqBase+"/v2/groups/"+room+"/files","QQBot "+token,payload,control,guard,30*1024*1024);
    }
    JSONObject sendGroupMedia(JSONObject profile,String secret,JSONObject event,JSONObject outbox,String fileInfo,RunControl control,DispatchGuard guard)throws Exception {
        if(!QqConversation.scene(event).equals(QqConversation.GROUP)||fileInfo==null||fileInfo.isEmpty()||fileInfo.length()>8192)throw new SecurityException("原QQ群媒体上传回执无效");
        String token=qqToken(profile,secret,control,guard);JSONObject payload=new JSONObject().put("content"," ").put("msg_type",7).put("msg_id",event.getString("messageId")).put("msg_seq",outbox.getInt("sequence")).put("media",new JSONObject().put("file_info",fileInfo));
        guard.check();control.check();return request(qqBase+"/v2/groups/"+ChannelCore.identifier(event,"roomId")+"/messages","QQBot "+token,payload,control,guard);
    }
    JSONObject sendQqStream(JSONObject profile,String secret,JSONObject event,JSONObject frame,RunControl control,DispatchGuard guard)throws Exception {
        if(!QqConversation.scene(event).equals(QqConversation.C2C)||!frame.getString("msg_id").equals(event.getString("messageId"))||!frame.getString("input_mode").equals("replace")||!frame.getString("content_type").equals("markdown"))throw new SecurityException("QQ流帧不是原C2C消息");String token=qqToken(profile,secret,control,guard);guard.check();control.check();return request(qqBase+"/v2/users/"+ChannelCore.identifier(event,"senderId")+"/stream_messages","QQBot "+token,frame,control,guard);
    }
    /** Native reviewed original C2C bytes; upload and message remain two separately recorded phases. */
    JSONObject uploadOwnerMedia(JSONObject profile,String secret,JSONObject event,JSONObject media,byte[] bytes,RunControl control,DispatchGuard guard)throws Exception {
        if(!QqConversation.scene(event).equals(QqConversation.C2C)||bytes==null||bytes.length>ChannelOutboundMediaFormats.MAX_BYTES||media.getLong("bytes")!=bytes.length)throw new SecurityException("QQ附件必须是准确原C2C目标/固定字节");
        int type=media.getInt("qqFileType");if(type<1||type>4)throw new SecurityException("QQ媒体类型不支持");String token=qqToken(profile,secret,control,guard);
        JSONObject body=new JSONObject().put("file_type",type).put("srv_send_msg",false).put("file_name",media.getString("name")).put("file_data",java.util.Base64.getEncoder().encodeToString(bytes));
        guard.check();control.check();return request(qqBase+"/v2/users/"+ChannelCore.identifier(event,"senderId")+"/files","QQBot "+token,body,control,guard,12*1024*1024);
    }
    JSONObject sendOwnerMedia(JSONObject profile,String secret,JSONObject event,JSONObject row,String fileInfo,RunControl control,DispatchGuard guard)throws Exception {
        if(!QqConversation.scene(event).equals(QqConversation.C2C)||fileInfo==null||fileInfo.isEmpty()||fileInfo.length()>8192)throw new SecurityException("QQ原C2C媒体回执不完整");String token=qqToken(profile,secret,control,guard);
        JSONObject body=new JSONObject().put("content",row.getString("text")).put("msg_type",7).put("msg_id",event.getString("messageId")).put("msg_seq",row.getInt("sequence")).put("media",new JSONObject().put("file_info",fileInfo));guard.check();control.check();return request(qqBase+QqConversation.endpoint(event),"QQBot "+token,body,control,guard);
    }
    /** Token and gateway stay native-only; no secret-bearing object enters a feature response. */
    GatewayAccess qqGateway(JSONObject profile,String secret,RunControl control,DispatchGuard guard)throws Exception {
        String token=qqToken(profile,secret,control,guard);
        JSONObject response=request(qqBase+"/gateway","QQBot "+token,null,control,guard);
        guard.check();control.check();return new GatewayAccess(response.getString("url"),token,(1<<25)|(profile.optBoolean("qqGuildEnabled")?((1<<30)|(1<<12)):0));
    }
    synchronized void forgetQqToken(String profileId){tokens.remove(profileId);}
    static final class GatewayAccess {
        final String url,token;final int intents;final String botUserId;
        GatewayAccess(String url,String token){this(url,token,1<<25);}
        GatewayAccess(String url,String token,int intents){this(url,token,intents,"");}
        GatewayAccess(String url,String token,int intents,String botUserId){this.url=url;this.token=token;this.intents=intents;this.botUserId=botUserId;}
    }
    private synchronized String qqToken(JSONObject profile,String secret,RunControl control,DispatchGuard guard)throws Exception {
        String id=profile.getString("id"),fingerprint=ChannelCore.digest(profile.getString("appId")+"\u0000"+secret);Token previous=tokens.get(id);
        long now=System.currentTimeMillis();
        if(previous!=null&&previous.secretFingerprint.equals(fingerprint)&&previous.expiresAt>now+60_000)return previous.value;
        JSONObject result=post(qqBase+"/app/getAppAccessToken","",new JSONObject().put("appId",profile.getString("appId")).put("clientSecret",secret),control,guard);
        String value=result.optString("access_token");long ttl=result.optLong("expires_in",0);
        if(value.isEmpty()||value.length()>8192||value.indexOf('\r')>=0||value.indexOf('\n')>=0||ttl<=0)throw new IllegalStateException("QQ未返回有效访问凭证");
        tokens.put(id,new Token(value,now+Math.min(ttl,7200)*1000,fingerprint));return value;
    }
    static JSONObject post(String address,String authorization,JSONObject payload)throws Exception {
        return post(address,authorization,payload,new RunControl(),()->{});
    }
    static JSONObject post(String address,String authorization,JSONObject payload,RunControl control,DispatchGuard guard)throws Exception {
        return request(address,authorization,payload,control,guard);
    }
    private static JSONObject request(String address,String authorization,JSONObject payload,RunControl control,DispatchGuard guard)throws Exception {return request(address,authorization,payload,control,guard,64_000);}
    private static JSONObject request(String address,String authorization,JSONObject payload,RunControl control,DispatchGuard guard,int maximumBytes)throws Exception {
        control.check();guard.check();
        HttpURLConnection connection=(HttpURLConnection)new URI(address).toURL().openConnection();
        connection.setConnectTimeout(10_000);connection.setReadTimeout(15_000);connection.setRequestMethod(payload==null?"GET":"POST");
        connection.setDoOutput(payload!=null);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        if(!authorization.isEmpty())connection.setRequestProperty("Authorization",authorization);
        byte[] bytes=payload==null?null:payload.toString().getBytes(StandardCharsets.UTF_8);if(bytes!=null&&bytes.length>maximumBytes)throw new IllegalArgumentException("待投递数据过大");
        if(bytes!=null)connection.setFixedLengthStreamingMode(bytes.length);
        AtomicBoolean finished=new AtomicBoolean();Runnable disconnect=connection::disconnect;control.onCancel(disconnect);
        Thread deadline=new Thread(()->{try{Thread.sleep(30_000);if(!finished.get())connection.disconnect();}catch(InterruptedException ignored){}},"pocket-channel-deadline");deadline.setDaemon(true);deadline.start();
        try {
            guard.check();control.check();
            if(bytes!=null)try(OutputStream out=connection.getOutputStream()){guard.check();control.check();out.write(bytes);}
            control.check();
            int status=connection.getResponseCode();
            if(status<200||status>=300)throw new Rejected(status);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()) {
                byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1){control.check();if(buffer.size()+n>64_000)throw new IllegalStateException("平台响应过大，投递状态需核对");buffer.write(chunk,0,n);}
            }
            JSONObject result=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
            if(result.optLong("err_code",0)!=0||result.optLong("code",0)!=0)throw new Rejected(status);
            return result;
        } finally {finished.set(true);control.removeCancelAction(disconnect);deadline.interrupt();connection.disconnect();}
    }
    static final class Rejected extends Exception {
        Rejected(int status){super("平台拒绝请求（HTTP "+status+"），请检查连接、权限和回复窗口");}
    }
    private static final class Token {
        final String value,secretFingerprint;final long expiresAt;
        Token(String value,long expiresAt,String fingerprint){this.value=value;this.expiresAt=expiresAt;this.secretFingerprint=fingerprint;}
    }
}
