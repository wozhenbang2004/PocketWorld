package dev.pocketengine.features.workspace;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import okhttp3.*;
import okio.BufferedSink;

/** RFC 9110 conditional PUT and RFC 4918 Depth:1 metadata discovery. No directory writer/scheduler.
 * A 201 acknowledges creation, not byte equality or restorability. ETags are never content hashes. */
final class OwnerBackupWebDavClient {
    interface BeforePut {void enter()throws Exception;}
    private final OkHttpClient http=new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .writeTimeout(30,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build();
    static final class Result {
        final int httpStatus;final String status,code;
        Result(int httpStatus,String status,String code){this.httpStatus=httpStatus;this.status=status;this.code=code;}
    }
    Result put(OwnerBackupWebDavTarget target,String action,File encrypted,long expectedBytes,String expectedSha,String basic,RunControl control,BeforePut beforePut)throws Exception {
        requireFile(encrypted,expectedBytes,expectedSha,control);AtomicBoolean bodyEntered=new AtomicBoolean();
        RequestBody body=new RequestBody(){
            @Override public MediaType contentType(){return MediaType.get("application/octet-stream");}
            @Override public long contentLength(){return expectedBytes;}
            @Override public boolean isOneShot(){return true;}
            @Override public void writeTo(BufferedSink sink)throws IOException{
                if(!bodyEntered.compareAndSet(false,true))throw new IOException("原加密文件已经进入一次传输，禁止重放");
                try{copy(encrypted,sink.outputStream(),expectedBytes,expectedSha,control);}catch(IOException failure){throw failure;}catch(Exception failure){throw new IOException("原加密备份传输中止",failure);}
            }
        };
        Request.Builder request=request(target,action,basic).header("If-None-Match","*").put(body);
        Call call=http.newCall(request.build());Runnable stop=call::cancel;control.onCancel(stop);
        try{
            control.checkpoint();beforePut.enter();control.check();if(control.isPaused())throw new SecurityException("原上传已暂停，未进入网络请求");
            try(Response response=call.execute()){
                int status=response.code();if(status==201)return new Result(status,"accepted","remote_http_accepted_not_restore_verified");
                if(status==401||status==403)return new Result(status,"rejected","remote_auth_rejected");
                if(status==404||status==409)return new Result(status,"rejected","remote_collection_unavailable");
                if(status==412)return new Result(status,"rejected","remote_file_already_exists_not_overwritten");
                if(status==413||status==507)return new Result(status,"rejected","remote_capacity_rejected");
                if(status==429)return new Result(status,"rejected","remote_rate_limited_no_retry");
                return new Result(status,"unknown","remote_upload_result_unknown");
            }
        }finally{control.removeCancelAction(stop);}
    }
    /** Writes only to the caller's new private quarantine. Exact bytes are checked before use. */
    void readBack(OwnerBackupWebDavTarget target,String action,long expectedBytes,String expectedSha,String basic,OutputStream destination,RunControl control)throws Exception {
        requireSize(expectedBytes,expectedSha);Call call=http.newCall(request(target,action,basic).get().build());Runnable stop=call::cancel;control.onCancel(stop);
        try{
            control.checkpoint();try(Response response=call.execute()){
                if(response.code()!=200||response.body()==null)throw new IOException("原远端备份未取得完整读取响应；没有重试上传");
                String encoding=response.header("Content-Encoding");if(encoding!=null&&!"identity".equalsIgnoreCase(encoding))throw new SecurityException("远端改变了原加密字节的编码");
                long declared=response.body().contentLength();if(declared!=-1&&declared!=expectedBytes)throw new SecurityException("远端备份长度不符，未采用");
                try(InputStream input=response.body().byteStream()){copy(input,destination,expectedBytes,expectedSha,control);}
            }
        }finally{control.removeCancelAction(stop);}
    }
    /** Explicit owner-selected version; a new device can read it without an old task database.
     * Authentication and the existing archive verifier still run before any restore preview. */
    void download(OwnerBackupWebDavTarget target,String name,String basic,OutputStream destination,RunControl control)throws Exception {
        Call call=http.newCall(requestUrl(target.file(name),basic).get().build());Runnable stop=call::cancel;control.onCancel(stop);
        try{control.checkpoint();try(Response response=call.execute()){
            if(response.code()!=200||response.body()==null)throw new IOException("所选远端备份无法完整读取；没有恢复或重试上传");
            requireIdentityEncoding(response);long expected=response.body().contentLength(),maximum=OwnerBackupEnvelope.encryptedBytes(1536L*1024*1024);
            if(expected>maximum)throw new SecurityException("所选远端备份超出支持大小");
            try(InputStream input=response.body().byteStream()){byte[] buffer=new byte[65536];long count=0;for(int read;(read=input.read(buffer))>=0;){control.checkpoint();if(read==0)continue;if((count+=read)>maximum)throw new SecurityException("所选远端备份超过支持大小");destination.write(buffer,0,read);}if(count<OwnerBackupEnvelope.encryptedBytes(22)||expected>=0&&count!=expected)throw new SecurityException("所选远端备份长度不完整");control.checkpoint();}
        }}finally{control.removeCancelAction(stop);}
    }
    JSONArray list(OwnerBackupWebDavTarget target,String basic,RunControl control)throws Exception {
        String properties="<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>";
        Request request=requestUrl(target.collection,basic).header("Depth","1").method("PROPFIND",RequestBody.create(properties.getBytes(StandardCharsets.UTF_8),MediaType.get("application/xml; charset=utf-8"))).build();
        Call call=http.newCall(request);Runnable stop=call::cancel;control.onCancel(stop);
        try{control.checkpoint();try(Response response=call.execute()){
            if(response.code()!=207||response.body()==null)throw new IOException("此目录未提供可用的 WebDAV 文件列表；仍可明确填写备份文件名读取");
            requireIdentityEncoding(response);if(response.body().contentLength()>OwnerBackupWebDavListing.MAX_BYTES)throw new SecurityException("WebDAV 列表过大，请选择专用备份目录");
            try(InputStream input=response.body().byteStream()){return OwnerBackupWebDavListing.read(target,input,control);}
        }}finally{control.removeCancelAction(stop);}
    }
    private static void requireIdentityEncoding(Response response){String encoding=response.header("Content-Encoding");if(encoding!=null&&!"identity".equalsIgnoreCase(encoding))throw new SecurityException("远端改变了原字节的编码");}
    private static Request.Builder request(OwnerBackupWebDavTarget target,String action,String basic){
        return requestUrl(target.destination(action),basic);
    }
    private static Request.Builder requestUrl(String url,String basic){
        Request.Builder request=new Request.Builder().url(url).header("Accept-Encoding","identity").header("Cache-Control","no-store");
        if(basic!=null&&!basic.isEmpty()){if(!basic.matches("Basic [A-Za-z0-9+/]+=*"))throw new SecurityException("原连接凭据格式无效");request.header("Authorization",basic);}
        return request;
    }
    static void requireFile(File file,long bytes,String sha,RunControl control)throws Exception {
        requireSize(bytes,sha);if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()!=bytes)throw new SecurityException("原加密备份文件已改变");
        copy(file,new OutputStream(){@Override public void write(int value){}@Override public void write(byte[] bytes,int offset,int length){}},bytes,sha,control);
    }
    private static void requireSize(long bytes,String sha){if(bytes<OwnerBackupEnvelope.encryptedBytes(22)||bytes>OwnerBackupEnvelope.encryptedBytes(1536L*1024*1024)||sha==null||!sha.matches("[a-f0-9]{64}"))throw new SecurityException("原加密备份定位不完整");}
    private static void copy(File file,OutputStream out,long bytes,String sha,RunControl control)throws Exception{
        if(Files.isSymbolicLink(file.toPath()))throw new SecurityException("原加密备份不是固定文件");
        try(InputStream input=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){copy(input,out,bytes,sha,control);}
    }
    private static void copy(InputStream input,OutputStream out,long bytes,String sha,RunControl control)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long total=0;
        for(int read;(read=input.read(buffer))>=0;){control.checkpoint();if(read==0)continue;if((total+=read)>bytes)throw new SecurityException("原加密备份超过固定长度");hash.update(buffer,0,read);out.write(buffer,0,read);}
        control.checkpoint();if(total!=bytes||!OwnerBackupWebDavTarget.hex(hash.digest()).equals(sha))throw new SecurityException("原加密备份实际长度或摘要不符");
    }
}
