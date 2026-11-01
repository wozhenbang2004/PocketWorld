package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independently authored iLink wire adapter. It owns no identity grants or task scheduling. */
public final class WeixinClient {
    public static final String DEFAULT_BASE="https://ilinkai.weixin.qq.com";
    private static final String VERSION="0.3.0";
    private final String loginBase;
    private final boolean loopbackTest;
    public WeixinClient(){loginBase=DEFAULT_BASE;loopbackTest=false;}
    WeixinClient(String testBase){loginBase=testBase;loopbackTest=true;}
    public static String validateApiBase(String address)throws Exception{return validateBase(address,false);}
    private static String validateBase(String address,boolean test)throws Exception {
        URI uri=new URI(address);String host=uri.getHost();
        boolean loopback=test&&("127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host));
        boolean official=host!=null&&(host.equalsIgnoreCase("ilinkai.weixin.qq.com")||host.toLowerCase(java.util.Locale.ROOT).endsWith(".weixin.qq.com"));
        if(uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&!loopback
                ||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||!(loopback&&"http".equals(uri.getScheme())||official&&"https".equals(uri.getScheme())))
            throw new SecurityException("微信接入地址必须是官方weixin.qq.com的HTTPS根地址");
        return uri.getScheme()+"://"+uri.getRawAuthority();
    }
    public JSONObject startLogin(RunControl control)throws Exception {
        return request(loginBase,"/ilink/bot/get_bot_qrcode?bot_type=3","POST","",new JSONObject().put("local_token_list",new JSONArray()),false,control,()->{});
    }
    public JSONObject pollLogin(String base,String qrcode,String verification,RunControl control)throws Exception {
        if(qrcode==null||qrcode.isEmpty()||qrcode.length()>4096)throw new IllegalArgumentException("登录二维码无效");
        if(verification!=null&&!verification.isEmpty()&&!verification.matches("[A-Za-z0-9-]{1,32}"))throw new IllegalArgumentException("验证码格式无效");
        String path="/ilink/bot/get_qrcode_status?qrcode="+URLEncoder.encode(qrcode,StandardCharsets.UTF_8.name());
        if(verification!=null&&!verification.isEmpty())path+="&verify_code="+URLEncoder.encode(verification,StandardCharsets.UTF_8.name());
        return request(base,path,"GET","",null,false,control,()->{});
    }
    public JSONObject poll(String base,String token,String cursor,RunControl control,ChannelHttp.DispatchGuard guard)throws Exception {
        if(cursor==null||cursor.length()>32768)throw new IllegalArgumentException("微信同步游标无效");
        JSONObject result=request(base,"/ilink/bot/getupdates","POST",token,new JSONObject().put("get_updates_buf",cursor).put("base_info",baseInfo()),true,control,guard);
        requireSuccess(result);if(result.optJSONArray("msgs")==null)throw new IllegalStateException("微信未返回消息列表");
        if(result.getJSONArray("msgs").length()>200)throw new IllegalStateException("微信消息批次超出支持范围");
        if(result.has("get_updates_buf")&&(!(result.opt("get_updates_buf") instanceof String)||result.optString("get_updates_buf").length()>32768))throw new IllegalStateException("微信返回无效同步游标");
        return result;
    }
    public JSONObject send(String base,String token,JSONObject event,JSONObject draft,String contextToken,RunControl control,ChannelHttp.DispatchGuard guard)throws Exception {
        if(contextToken==null||contextToken.isEmpty()||contextToken.length()>4096)throw new IllegalStateException("此消息缺少可用的微信会话上下文，请等待对方发来新消息");
        JSONObject message=new JSONObject().put("from_user_id","").put("to_user_id",event.getString("senderId"))
                .put("client_id",draft.getString("id")).put("message_type",2).put("message_state",2).put("context_token",contextToken)
                .put("item_list",new JSONArray().put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text",draft.getString("text")))));
        JSONObject response=request(base,"/ilink/bot/sendmessage","POST",token,new JSONObject().put("msg",message).put("base_info",baseInfo()),true,control,guard);
        requireSuccess(response);
        // iLink acknowledges a request with ret=0, not a provider-generated message id.
        // The stable client_id identifies this exact outbox attempt; this is transport acceptance.
        return new JSONObject().put("id",draft.getString("id")).put("accepted",true).put("providerReceipt",new JSONObject().put("ret",0));
    }
    interface MediaSteps {void phase(String next,long bytes)throws Exception;void uploaded(JSONObject privateMediaItem)throws Exception;}
    /** Native selected bytes only. CDN never receives the Bot token, cookies or ambient credentials. */
    JSONObject sendMedia(String base,String token,JSONObject event,JSONObject row,String contextToken,JSONObject format,byte[] plain,RunControl control,ChannelHttp.DispatchGuard guard,MediaSteps steps)throws Exception {
        if(contextToken==null||contextToken.isEmpty()||contextToken.length()>4096||plain==null||plain.length>ChannelOutboundMediaFormats.MAX_BYTES||format.getLong("bytes")!=plain.length)throw new SecurityException("原微信附件范围/会话上下文不完整");
        byte[] key=new byte[16],encrypted=null;new SecureRandom().nextBytes(key);String hex=hex(key),fileKey=java.util.UUID.randomUUID().toString().replace("-","");
        try {
            javax.crypto.Cipher aes=javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");aes.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"));encrypted=aes.doFinal(plain);String md5=hex(java.security.MessageDigest.getInstance("MD5").digest(plain));
            steps.phase("upload_requesting",0);guard.check();control.check();
            JSONObject upload=request(base,"/ilink/bot/getuploadurl","POST",token,new JSONObject().put("filekey",fileKey).put("media_type",format.getInt("weixinUploadMediaType")).put("to_user_id",event.getString("senderId")).put("rawsize",plain.length).put("rawfilemd5",md5).put("filesize",encrypted.length).put("no_need_thumb",true).put("aeskey",hex).put("base_info",baseInfo()),true,control,guard);
            if(upload.has("ret")&&!numberEquals(upload.opt("ret"),0)||upload.has("errcode")&&!numberEquals(upload.opt("errcode"),0))throw new IllegalStateException("微信未确认媒体上传准备");
            String full=boundedWire(upload,"upload_full_url",8192),param=boundedWire(upload,"upload_param",4096);if(full.isEmpty()&&param.isEmpty())throw new IllegalStateException("微信没有返回准确上传目标");
            String address=full.isEmpty()?"https://novac2c.cdn.weixin.qq.com/c2c/upload?encrypted_query_param="+URLEncoder.encode(param,StandardCharsets.UTF_8.name())+"&filekey="+URLEncoder.encode(fileKey,StandardCharsets.UTF_8.name()):full;
            URI checked=checkedUploadAddress(address);String actual=checked.toASCIIString();if(loopbackTest&&checked.getHost().equalsIgnoreCase("novac2c.cdn.weixin.qq.com"))actual=validateBase(loginBase,true)+checked.getRawPath()+"?"+checked.getRawQuery();
            steps.phase("uploading",0);String downloadParam=uploadBinary(actual,encrypted,control,guard);guard.check();control.check();
            JSONObject media=new JSONObject().put("encrypt_query_param",downloadParam).put("aes_key",Base64.getEncoder().encodeToString(hex.getBytes(StandardCharsets.US_ASCII))).put("encrypt_type",1);
            int type=format.getInt("weixinItemType");JSONObject item=new JSONObject().put("type",type),body=new JSONObject().put("media",media);
            if(type==2)body.put("mid_size",encrypted.length);else if(type==5)body.put("video_size",encrypted.length);else if(type==3)body.put("encode_type",format.getInt("weixinCodec"));else if(type==4)body.put("file_name",format.getString("name")).put("len",String.valueOf(plain.length));else throw new SecurityException("微信媒体类型不支持");
            item.put(type==2?"image_item":type==5?"video_item":type==3?"voice_item":"file_item",body);steps.uploaded(item);steps.phase("uploaded",plain.length);
            JSONObject message=new JSONObject().put("from_user_id","").put("to_user_id",event.getString("senderId")).put("client_id",row.getString("id")).put("message_type",2).put("message_state",2).put("context_token",contextToken).put("item_list",new JSONArray().put(item));
            steps.phase("message_sending",plain.length);guard.check();control.check();JSONObject response=request(base,"/ilink/bot/sendmessage","POST",token,new JSONObject().put("msg",message).put("base_info",baseInfo()),true,control,guard);requireSuccess(response);
            return new JSONObject().put("id",row.getString("id")).put("accepted",true).put("providerReceipt",new JSONObject().put("ret",0)).put("receiptKind","transport_request_accepted_not_recipient_read");
        }finally{java.util.Arrays.fill(key,(byte)0);if(encrypted!=null)java.util.Arrays.fill(encrypted,(byte)0);}
    }
    private URI checkedUploadAddress(String address)throws Exception {
        URI uri=new URI(address);boolean official="https".equals(uri.getScheme())&&"novac2c.cdn.weixin.qq.com".equalsIgnoreCase(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443)&&"/c2c/upload".equals(uri.getRawPath());boolean fixture=loopbackTest&&ChannelHttp.sameOrigin(loginBase,address)&&"http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost());
        if(address.length()>8192||uri.getUserInfo()!=null||uri.getFragment()!=null||!official&&!fixture)throw new SecurityException("只允许官方微信精确上传路径；不跟随重定向或转发凭据");return uri;
    }
    private String uploadBinary(String address,byte[] encrypted,RunControl control,ChannelHttp.DispatchGuard guard)throws Exception {
        checkedUploadAddress(address);control.check();guard.check();HttpURLConnection connection=(HttpURLConnection)new URI(address).toURL().openConnection();connection.setRequestMethod("POST");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10_000);connection.setReadTimeout(20_000);connection.setRequestProperty("Content-Type","application/octet-stream");connection.setRequestProperty("Cookie","");connection.setRequestProperty("Authorization","");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(encrypted.length);Runnable disconnect=connection::disconnect;control.onCancel(disconnect);AtomicBoolean done=new AtomicBoolean();Thread deadline=new Thread(()->{try{Thread.sleep(40_000);if(!done.get())connection.disconnect();}catch(InterruptedException ignored){}},"pocket-weixin-media-deadline");deadline.setDaemon(true);deadline.start();
        try{guard.check();control.check();try(OutputStream out=connection.getOutputStream()){for(int offset=0;offset<encrypted.length;){guard.check();control.check();int count=Math.min(32768,encrypted.length-offset);out.write(encrypted,offset,count);offset+=count;}}guard.check();control.check();if(connection.getResponseCode()!=200)throw new IllegalStateException("微信CDN上传未确认；不重传原body");String result=connection.getHeaderField("x-encrypted-param");if(result==null||result.isEmpty()||result.length()>4096||result.indexOf('\r')>=0||result.indexOf('\n')>=0)throw new IllegalStateException("微信CDN缺少原上传回执，状态未知");return result;}
        finally{done.set(true);deadline.interrupt();control.removeCancelAction(disconnect);connection.disconnect();}
    }
    private static String boundedWire(JSONObject value,String key,int max)throws Exception {Object raw=value.opt(key);if(raw==null||raw==JSONObject.NULL)return "";if(!(raw instanceof String)||((String)raw).length()>max)throw new IllegalStateException("微信媒体回执字段无效");return(String)raw;}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    public static JSONObject canonical(JSONObject message)throws Exception {
        if(!numberEquals(message.opt("message_type"),1)||!numberEquals(message.opt("message_state"),2))return null;
        if(!message.optString("group_id").isEmpty())return null;
        String id=String.valueOf(message.opt("message_id"));
        if(!id.matches("[0-9]{1,30}")||id.matches("0+"))throw new IllegalArgumentException("微信消息缺少稳定ID");
        String sender=ChannelCore.identifier(message,"from_user_id");JSONArray items=message.optJSONArray("item_list");if(items==null||items.length()>30)return null;
        JSONObject parts=ChannelMessageParts.weixin(message,"wx-"+id);String text=ChannelMessageParts.currentText(parts);
        if(text.trim().isEmpty()&&ChannelMessageParts.weixinImagePositions(parts).length()==0&&ChannelMessageParts.ownerMediaPositions(parts).length()==0)return null; // Native media-only data retains empty user text; no fabricated command or goal.
        Object time=message.opt("create_time_ms");if(!(time instanceof Number)||((Number)time).longValue()<=0||((Number)time).doubleValue()!=((Number)time).longValue())throw new IllegalArgumentException("微信消息时间无效");
        return new JSONObject().put("messageId","wx-"+id).put("senderId",sender).put("kind","dm").put("text",text).put("messageParts",parts).put("nativeMediaOnly",text.trim().isEmpty()).put("partialInput",ChannelMessageParts.hasUnsupported(parts)).put("sentAt",((Number)time).longValue());
    }
    private static JSONObject baseInfo()throws Exception{return new JSONObject().put("channel_version",VERSION).put("bot_agent","PocketWorld/"+VERSION);}
    private static void requireSuccess(JSONObject result)throws Exception {
        if(result.optInt("ret",Integer.MIN_VALUE)==-14||result.optInt("errcode",0)==-14)throw new SessionExpired();
        if(!numberEquals(result.opt("ret"),0)||result.has("errcode")&&!numberEquals(result.opt("errcode"),0))throw new IllegalStateException("微信服务未确认请求成功，请检查授权状态");
    }
    private static boolean numberEquals(Object value,int expected){return value instanceof Number&&((Number)value).doubleValue()==expected;}
    /** Preserve the protocol uint64 ID before Android JSONObject could round it to a double.
     * This only quotes integer message_id value tokens outside JSON strings; it is not a second
     * JSON parser. The normal JSONObject parser still validates the complete response. */
    static JSONObject responseJson(String raw)throws Exception {
        StringBuilder safe=new StringBuilder(raw.length());int cursor=0;
        while(cursor<raw.length()){
            if(raw.charAt(cursor)!='"'){safe.append(raw.charAt(cursor++));continue;}
            int start=cursor++;boolean ended=false;
            while(cursor<raw.length()){char c=raw.charAt(cursor++);if(c=='\\'){if(cursor<raw.length())cursor++;}else if(c=='"'){ended=true;break;}}
            if(!ended)throw new IllegalArgumentException("微信返回的JSON字符串未结束");
            safe.append(raw,start,cursor);
            if(!raw.substring(start,cursor).equals("\"message_id\""))continue;
            int colon=cursor;while(colon<raw.length()&&Character.isWhitespace(raw.charAt(colon)))colon++;
            if(colon>=raw.length()||raw.charAt(colon)!=':')continue;
            int number=colon+1;while(number<raw.length()&&Character.isWhitespace(raw.charAt(number)))number++;
            int end=number;while(end<raw.length()&&raw.charAt(end)>='0'&&raw.charAt(end)<='9')end++;
            if(end==number)continue;
            if(end-number>30||end-number>1&&raw.charAt(number)=='0'||end<raw.length()&&",}] \r\n\t".indexOf(raw.charAt(end))<0)throw new IllegalArgumentException("微信消息ID不是有界十进制整数");
            safe.append(raw,cursor,number).append('"').append(raw,number,end).append('"');cursor=end;
        }
        return new JSONObject(safe.toString());
    }
    private JSONObject request(String base,String path,String method,String token,JSONObject body,boolean authenticated,RunControl control,ChannelHttp.DispatchGuard guard)throws Exception {
        String checked=validateBase(base,loopbackTest);if(authenticated&&(token==null||token.isEmpty()||token.length()>8192||token.indexOf('\r')>=0||token.indexOf('\n')>=0))throw new SecurityException("微信登录凭据无效");
        // Only the package-private fixture constructor maps official identities to loopback.
        // Production always uses the validated official root; no command exposes this mapping.
        String endpoint=loopbackTest?validateBase(loginBase,true):checked;
        control.check();guard.check();HttpURLConnection connection=(HttpURLConnection)new URI(endpoint+path).toURL().openConnection();
        connection.setRequestMethod(method);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10_000);connection.setReadTimeout(40_000);
        connection.setRequestProperty("iLink-App-Id","bot");connection.setRequestProperty("iLink-App-ClientVersion","768");
        if(method.equals("POST")){
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("AuthorizationType","ilink_bot_token");
            long random=Integer.toUnsignedLong(new SecureRandom().nextInt());connection.setRequestProperty("X-WECHAT-UIN",Base64.getEncoder().encodeToString(String.valueOf(random).getBytes(StandardCharsets.UTF_8)));
        }
        if(authenticated)connection.setRequestProperty("Authorization","Bearer "+token);
        Runnable cancel=connection::disconnect;control.onCancel(cancel);AtomicBoolean finished=new AtomicBoolean();
        Thread deadline=new Thread(()->{try{Thread.sleep(50_000);if(!finished.get())connection.disconnect();}catch(InterruptedException ignored){}},"pocket-weixin-deadline");deadline.setDaemon(true);deadline.start();
        try{
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IllegalArgumentException("微信请求超出支持范围");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream output=connection.getOutputStream()){control.check();guard.check();output.write(bytes);}}
            control.check();int status=connection.getResponseCode();if(status<200||status>=300)throw new IllegalStateException("微信接口未完成请求（HTTP "+status+"）");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=connection.getInputStream()){byte[] chunk=new byte[4096];int count;
                while((count=input.read(chunk))!=-1){control.check();if(bytes.size()+count>1_000_000)throw new IllegalStateException("微信接口返回过大");bytes.write(chunk,0,count);}}
            control.check();return responseJson(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }finally{finished.set(true);deadline.interrupt();control.removeCancelAction(cancel);connection.disconnect();}
    }
    public static final class SessionExpired extends Exception{SessionExpired(){super("微信会话已失效，请重新扫码登录；未自动重发消息");}}
}
