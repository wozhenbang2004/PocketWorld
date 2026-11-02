package dev.pocketengine.features.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.pocketengine.core.RunControl;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.json.JSONObject;

/** Authenticated JSON transport; never follows a redirect carrying the owner's token. */
public final class RemoteTransport {
    private static final int MAX_RESPONSE=2*1024*1024;
    private static final OkHttpClient HTTP=new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8");
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
        return request(base,token,path,body,timeoutSeconds,null);
    }
    /** The original native run controls this call only; cancellation never proves a remote write was undone. */
    public static JSONObject request(String base,String token,String path,JSONObject body,int timeoutSeconds,RunControl original)throws Exception {
        return requestJson(base,token,path,body,timeoutSeconds,original,MAX_RESPONSE);
    }
    static JSONObject requestProject(String base,String token,String path,JSONObject body,int timeoutSeconds)throws Exception {
        return requestProject(base,token,path,body,timeoutSeconds,null);
    }
    static JSONObject requestProject(String base,String token,String path,JSONObject body,int timeoutSeconds,RunControl original)throws Exception {
        if(!(path.startsWith("/v1/assignment-projects")||path.matches("/v1/jobs/[A-Za-z0-9_-]{1,80}/(?:project-contributions|snapshot-operations/[a-f0-9-]{36})")))throw new SecurityException("不是原完整项目协议路径");
        return requestJson(base,token,path,body,timeoutSeconds,original,4194304);
    }
    private static JSONObject requestJson(String base,String token,String path,JSONObject body,int seconds,RunControl original,int maximum)throws Exception {
        check(original);Request.Builder request=request(base,token,path);
        if(body!=null){byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);if(maximum==4194304&&payload.length>2097152)throw new SecurityException("原项目manifest超过有界协议");request.post(RequestBody.create(payload,JSON));}
        Call call=call(request.build(),seconds);Runnable cancel=call::cancel;if(original!=null)original.onCancel(cancel);
        try{check(original);try(Response response=call.execute()){check(original);return readResponse(response,token,original,maximum);}}
        catch(IOException failed){check(original);throw failed;}
        finally{if(original!=null)original.removeCancelAction(cancel);call.cancel();}
    }
    private static Request.Builder request(String base,String token,String path)throws Exception {
        URI selected=validateBase(base);
        if(token==null||token.length()<32)throw new IllegalStateException("请填写电脑 Companion 的访问令牌");
        String prefix=selected.getPath()==null?"":selected.getPath().replaceAll("/+$","");
        String endpoint=new URI(selected.getScheme(),null,selected.getHost(),selected.getPort(),prefix+path.split("\\?",2)[0],null,null).toASCIIString();
        endpoint+=path.contains("?")?path.substring(path.indexOf('?')):"";
        return new Request.Builder().url(endpoint).header("Authorization","Bearer "+token).header("Accept","application/json");
    }
    private static Call call(Request request,int seconds) {
        if(seconds<1||seconds>900)throw new IllegalArgumentException("电脑请求总期限需为1至900秒");
        AtomicBoolean exchanged=new AtomicBoolean();
        // callTimeout spans connect, request upload, headers and the entire response body.
        // Even with retryOnConnectionFailure(false), retryable HTTP statuses can otherwise
        // make another exchange. The original operation is never sent twice here.
        OkHttpClient client=HTTP.newBuilder().connectTimeout(Math.min(10,seconds),TimeUnit.SECONDS)
                .readTimeout(seconds,TimeUnit.SECONDS).writeTimeout(seconds,TimeUnit.SECONDS).callTimeout(seconds,TimeUnit.SECONDS)
                .addNetworkInterceptor(chain->{if(!exchanged.compareAndSet(false,true))throw new IOException("电脑请求第二次网络派发已拒绝；请只查询原操作");return chain.proceed(chain.request());}).build();
        return client.newCall(request);
    }
    private static void check(RunControl original)throws InterruptedException {
        if(original!=null)original.check();
        else if(Thread.currentThread().isInterrupted())throw new InterruptedException("远程请求已停止");
    }
    private static JSONObject readResponse(Response response,String token,RunControl original,int maximum)throws Exception {
        check(original);int status=response.code();
        if(status>=300&&status<400)throw new IOException("电脑地址发生重定向，请填写最终 HTTPS 地址；令牌未转发");
        ResponseBody body=response.body();if(body==null)throw new IOException("电脑接口未返回数据（HTTP "+status+"）");String raw;
        try(InputStream input=body.byteStream()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=input.read(chunk))!=-1){check(original);if(bytes.size()+count>maximum)throw new IOException("电脑回复超过大小限制");bytes.write(chunk,0,count);}check(original);raw=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
        JSONObject value;try{value=new JSONObject(raw);}catch(Exception invalid){throw new IOException("电脑接口返回的 JSON 无法读取");}
        if(status<200||status>=300||!value.optBoolean("ok",false)){
            String message=value.optString("message",status==401?"电脑令牌无效":"电脑请求失败（HTTP "+status+"）").replace(token,"[令牌已隐藏]");
            throw new HttpFailure(status,message.length()>300?message.substring(0,300):message);
        }
        JSONObject result=value.optJSONObject("result");if(result==null)throw new IOException("电脑接口缺少结果对象");return result;
    }
    /** One exact native-approved raw stream. A sent prefix is not a remote hash/cleanup receipt. */
    public static JSONObject uploadInput(String base,String token,String path,String jobOperationId,InputStream input,long bytes,
                                        dev.pocketengine.core.RunControl control,RemoteModule.AssignmentDispatch beforeBinaryDispatch,
                                        java.util.function.LongConsumer written)throws Exception {
        if(input==null||control==null||bytes<0||bytes>262144||!jobOperationId.matches("[a-f0-9-]{36}")||!path.matches("/v1/jobs/[A-Za-z0-9_-]{1,80}/inputs/[0-7]"))throw new SecurityException("二进制传输缺少原job及准确字节范围");
        return uploadBinary(base,token,path,"X-Pocket-Job-Operation",jobOperationId,input,bytes,control,beforeBinaryDispatch,written);
    }
    /** Full original native package slot. No generic URL/body upload entry exists. */
    public static JSONObject uploadProjectFile(String base,String token,String path,String prepareOperation,InputStream input,long bytes,
                                               dev.pocketengine.core.RunControl control,RemoteModule.AssignmentDispatch current,
                                               java.util.function.LongConsumer written)throws Exception {
        if(input==null||control==null||bytes<0||bytes>RemoteProjectAssignments.MAX_FILE_BYTES||!prepareOperation.matches("[a-f0-9-]{36}")||!path.matches("/v1/assignment-projects/"+prepareOperation+"/files/[0-9]{1,4}"))throw new SecurityException("完整项目传输缺少原prepare/确切slot字节范围");
        return uploadBinary(base,token,path,"X-Pocket-Prepare-Operation",prepareOperation,input,bytes,control,current,written);
    }
    private static JSONObject uploadBinary(String base,String token,String path,String header,String operation,InputStream input,long bytes,
                                           RunControl original,RemoteModule.AssignmentDispatch beforeBinaryDispatch,
                                           java.util.function.LongConsumer written)throws Exception {
        check(original);java.util.Objects.requireNonNull(beforeBinaryDispatch);
        RequestBody body=new RequestBody(){
            private boolean entered;
            @Override public MediaType contentType(){return MediaType.get("application/octet-stream");}
            @Override public long contentLength(){return bytes;}
            @Override public boolean isOneShot(){return true;}
            @Override public void writeTo(BufferedSink output)throws IOException {
                if(entered)throw new IOException("原二进制请求不能再次发送");entered=true;
                byte[] buffer=new byte[32768];long sent=0;
                try{
                    check(original);
                    while(sent<bytes){check(original);int count=input.read(buffer,0,(int)Math.min(buffer.length,bytes-sent));if(count<0)throw new IOException("私人原件提前结束，远端结果未确认");if(count==0)continue;check(original);output.write(buffer,0,count);sent+=count;if(written!=null)written.accept(sent);}
                    check(original);if(input.read()!=-1)throw new IOException("原件超过本次明确字节数，停止后继派发");output.flush();check(original);
                }catch(IOException failure){throw failure;}
                catch(Exception failure){throw new IOException("原二进制传输已停止；结果只能查询原操作",failure);}
                finally{java.util.Arrays.fill(buffer,(byte)0);}
            }
        };
        Call call=call(request(base,token,path).header(header,operation).put(body).build(),30);Runnable cancel=call::cancel;original.onCancel(cancel);
        try{check(original);beforeBinaryDispatch.beforeDispatch();check(original);try(Response response=call.execute()){check(original);return readResponse(response,token,original,header.equals("X-Pocket-Prepare-Operation")?4194304:MAX_RESPONSE);}}
        catch(IOException failure){check(original);throw failure;}
        finally{original.removeCancelAction(cancel);call.cancel();}
    }
    /** Original immutable manifest only; actual octets/hash checked before caller receives bytes. */
    public static byte[] readProjectFile(String base,String token,String path,long bytes,String sha,
                                         RemoteModule.AssignmentDispatch current)throws Exception {
        if(bytes<0||bytes>RemoteProjectAssignments.MAX_FILE_BYTES||!sha.matches("[a-f0-9]{64}")||!path.matches("/v1/jobs/[A-Za-z0-9_-]{1,80}/project-contributions/[a-f0-9-]{36}/file\\?.*"))throw new SecurityException("原不可变项目文件范围不明");
        current.beforeDispatch();Call call=call(request(base,token,path).build(),30);
        try(Response response=call.execute()){
            current.beforeDispatch();check(null);int status=response.code();
            if(status<200||status>=300){readResponse(response,token,null,MAX_RESPONSE);throw new IOException("原项目文件未返回原始字节");}
            ResponseBody body=response.body();
            if(body==null||!"application/octet-stream".equals(response.header("Content-Type"))||body.contentLength()!=bytes||!sha.equals(response.header("X-Pocket-Content-SHA256")))throw new SecurityException("原项目文件长度/类型/hash回执不同");
            try(InputStream input=body.byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream((int)bytes)){
                byte[] chunk=new byte[32768];int count;while((count=input.read(chunk))!=-1){current.beforeDispatch();check(null);if(out.size()+count>bytes)throw new SecurityException("实际项目文件超出冻结范围");out.write(chunk,0,count);}byte[] raw=out.toByteArray();if(raw.length!=bytes||!dev.pocketengine.features.local.project.ProjectSources.sha256(raw).equals(sha)){java.util.Arrays.fill(raw,(byte)0);throw new SecurityException("实际项目成果字节/hash与原冻结manifest不同");}current.beforeDispatch();return raw;
            }
        }finally{call.cancel();}
    }
    public static final class HttpFailure extends IOException {
        public final int status;
        public HttpFailure(int status,String message){super(message);this.status=status;}
    }
}
