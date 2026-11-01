package dev.pocketengine.features.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Authenticated JSON transport; never follows a redirect carrying the owner's token. */
public final class RemoteTransport {
    private static final int MAX_RESPONSE=2*1024*1024;
    private RemoteTransport() { }
    public static URI validateBase(String value) throws Exception {
        URI uri=new URI(value==null?"":value.trim());
        String scheme=uri.getScheme(),host=uri.getHost();
        boolean loopback="127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host)||"::1".equals(host)||"[::1]".equals(host);
        if(host==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!("https".equalsIgnoreCase(scheme)||("http".equalsIgnoreCase(scheme)&&loopback)))
            throw new IllegalArgumentException("电脑地址需使用 HTTPS；本机回环地址可使用 HTTP");
        if(uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException("电脑接口端口无效");
        return uri;
    }
    public static boolean sameOrigin(String first,String second) {
        try{URI a=validateBase(first),b=validateBase(second);return a.getScheme().equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&port(a)==port(b);}
        catch(Exception invalid){return false;}
    }
    private static int port(URI uri){return uri.getPort()>=0?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;}
    public static JSONObject request(String base,String token,String path,JSONObject body,int timeoutSeconds)throws Exception {
        HttpURLConnection connection=connection(base,token,path,timeoutSeconds);
        try {
            if(body!=null){byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(payload.length);try(java.io.OutputStream output=connection.getOutputStream()){output.write(payload);}}
            return readResponse(connection,token,null);
        }finally{connection.disconnect();}
    }
    private static HttpURLConnection connection(String base,String token,String path,int timeoutSeconds)throws Exception {
        URI selected=validateBase(base);
        if(token==null||token.length()<32)throw new IllegalStateException("请填写电脑 Companion 的访问令牌");
        String prefix=selected.getPath()==null?"":selected.getPath().replaceAll("/+$","");
        String endpointPath=new URI(selected.getScheme(),null,selected.getHost(),selected.getPort(),prefix+path.split("\\?",2)[0],null,null).toASCIIString();
        URL endpoint=new URL(endpointPath+(path.contains("?")?path.substring(path.indexOf('?')):""));
        HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
        connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
        connection.setConnectTimeout(Math.min(10,timeoutSeconds)*1000);connection.setReadTimeout(timeoutSeconds*1000);
        connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Accept","application/json");
        return connection;
    }
    private static JSONObject readResponse(HttpURLConnection connection,String token,dev.pocketengine.core.RunControl control)throws Exception {
            if(control!=null)control.check();
            int status=connection.getResponseCode();
            if(status>=300&&status<400)throw new IOException("电脑地址发生重定向，请填写最终 HTTPS 地址；令牌未转发");
            InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            if(stream==null)throw new IOException("电脑接口未返回数据（HTTP "+status+"）");
            String raw;
            try(InputStream input=stream){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=input.read(chunk))!=-1){if(control!=null)control.check();if(Thread.currentThread().isInterrupted())throw new InterruptedException("远程请求已停止");if(bytes.size()+count>MAX_RESPONSE)throw new IOException("电脑回复超过大小限制");bytes.write(chunk,0,count);}raw=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
            JSONObject response;
            try{response=new JSONObject(raw);}catch(Exception invalid){throw new IOException("电脑接口返回的 JSON 无法读取");}
            if(status<200||status>=300||!response.optBoolean("ok",false)){
                String message=response.optString("message",status==401?"电脑令牌无效":"电脑请求失败（HTTP "+status+"）").replace(token,"[令牌已隐藏]");
                throw new HttpFailure(status,message.length()>300?message.substring(0,300):message);
            }
            JSONObject result=response.optJSONObject("result");
            if(result==null)throw new IOException("电脑接口缺少结果对象");
            return result;
    }
    /** One exact native-approved raw stream. A sent prefix is not a remote hash/cleanup receipt. */
    public static JSONObject uploadInput(String base,String token,String path,String jobOperationId,InputStream input,long bytes,
                                        dev.pocketengine.core.RunControl control,RemoteModule.AssignmentDispatch beforeBinaryDispatch,
                                        java.util.function.LongConsumer written)throws Exception {
        if(input==null||control==null||bytes<0||bytes>262144||!jobOperationId.matches("[a-f0-9-]{36}")||!path.matches("/v1/jobs/[A-Za-z0-9_-]{1,80}/inputs/[0-7]"))throw new SecurityException("二进制传输缺少原job及准确字节范围");
        HttpURLConnection connection=connection(base,token,path,30);
        connection.setRequestMethod("PUT");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/octet-stream");
        connection.setRequestProperty("X-Pocket-Job-Operation",jobOperationId);
        connection.setFixedLengthStreamingMode(bytes);
        try{
            control.check();java.util.Objects.requireNonNull(beforeBinaryDispatch).beforeDispatch();control.check();
            long sent=0;byte[] buffer=new byte[32768];
            try(java.io.OutputStream output=connection.getOutputStream()){
                while(sent<bytes){control.check();int count=input.read(buffer,0,(int)Math.min(buffer.length,bytes-sent));if(count<0)throw new IOException("私人原件提前结束，远端结果未确认");if(count==0)continue;control.check();output.write(buffer,0,count);sent+=count;if(written!=null)written.accept(sent);}
                control.check();if(input.read()!=-1)throw new IOException("原件超过本次明确字节数，停止后继派发");output.flush();
            }finally{java.util.Arrays.fill(buffer,(byte)0);}
            control.check();return readResponse(connection,token,control);
        }finally{connection.disconnect();}
    }
    public static final class HttpFailure extends IOException {
        public final int status;
        public HttpFailure(int status,String message){super(message);this.status=status;}
    }
}
