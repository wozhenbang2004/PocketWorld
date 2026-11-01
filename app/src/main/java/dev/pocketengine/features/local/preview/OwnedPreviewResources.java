package dev.pocketengine.features.local.preview;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** The same isolated dev viewer's bounded HTTP interception for a native owned tunnel.
 * WebView interception alone does not constrain WebSockets: every actual resource response
 * receives a non-removable HTTP CSP, and tunnel lifetime remains the original host's job. */
public final class OwnedPreviewResources implements AutoCloseable {
    public static final int MAX_RESOURCE_BYTES=4*1024*1024;
    public static final long MAX_VIEW_BYTES=32L*1024*1024;
    public static final class Resource {
        public final String mimeType,encoding,reason;public final int status;public final Map<String,String> headers;private final byte[] bytes;
        private Resource(String mime,String encoding,int status,String reason,Map<String,String> headers,byte[] bytes){mimeType=mime;this.encoding=encoding;this.status=status;this.reason=reason;this.headers=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(headers));this.bytes=bytes;}
        public byte[] bytes(){return bytes.clone();}
    }
    private final OwnedLoopbackPreview owner;private final String identity,base,origin,websocket;
    private final URI baseUri;private final OkHttpClient client;
    private final Set<Call> active=ConcurrentHashMap.newKeySet();private final Semaphore reads=new Semaphore(4);
    private final AtomicBoolean closed=new AtomicBoolean();private final AtomicLong delivered=new AtomicLong();
    public OwnedPreviewResources(OwnedLoopbackPreview owner)throws Exception {
        this.owner=java.util.Objects.requireNonNull(owner);identity=owner.identity();base=owner.baseUrl();
        if(identity==null||!identity.matches("[A-Za-z0-9_.:-]{1,200}"))throw new SecurityException("原生预览身份无效");
        baseUri=URI.create(base);if(!"http".equals(baseUri.getScheme())||!"127.0.0.1".equals(baseUri.getHost())||baseUri.getPort()<1||baseUri.getPort()>65535||baseUri.getRawUserInfo()!=null||baseUri.getRawQuery()!=null||baseUri.getRawFragment()!=null||!"/".equals(baseUri.getRawPath()))throw new SecurityException("只支持原native签发的精确127.0.0.1 HTTP根地址");
        origin="http://127.0.0.1:"+baseUri.getPort();websocket="ws://127.0.0.1:"+baseUri.getPort();
        client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build();requireCurrent();
    }
    public String identity(){return identity;}
    public String entryUrl(){return base;}
    public String profileKey()throws Exception{return dev.pocketengine.features.local.project.ProjectSources.sha256(("native-owned-loopback-view-v1:"+identity).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,32);}
    public void requireCurrent()throws Exception {if(closed.get())throw new SecurityException("原活预览已关闭");owner.requireCurrent();if(!identity.equals(owner.identity())||!base.equals(owner.baseUrl()))throw new SecurityException("原预览对象身份或端口改变，未切换连接");}
    public boolean allows(String url){try{requireCurrent();if(url==null||url.length()>8192)return false;URI actual=URI.create(url);String path=actual.getPath();if(!"http".equals(actual.getScheme())||!"127.0.0.1".equals(actual.getHost())||actual.getPort()!=baseUri.getPort()||actual.getRawUserInfo()!=null||path==null||path.indexOf('\\')>=0||path.indexOf('\0')>=0||!actual.normalize().getRawPath().equals(actual.getRawPath()))return false;for(String part:path.split("/",-1))if(part.equals(".")||part.equals(".."))return false;return true;}catch(Exception invalid){return false;}}
    public String contentSecurityPolicy(){return "default-src 'none'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' blob:; connect-src 'self' "+websocket+"; object-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'; worker-src 'none'";}
    public Resource read(String url,String method)throws Exception {
        if(!allows(url)||!Set.of("GET","HEAD").contains(method))throw new SecurityException("预览请求离开原隧道或不是只读GET/HEAD");
        if(!reads.tryAcquire())throw new IllegalStateException("活预览同时最多读取4个资源，请核对页面后重开");Call call=null;
        try{
            requireCurrent();call=client.newCall(new Request.Builder().url(url).method(method,null).header("Accept","*/*").header("Cache-Control","no-cache").build());active.add(call);
            if(closed.get()){call.cancel();throw new SecurityException("原活预览已关闭");}
            try(Response response=call.execute()){
                if(response.isRedirect())throw new SecurityException("活预览重定向未在本次固定地址内执行，请核对原服务地址");
                ByteArrayOutputStream output=new ByteArrayOutputStream();if(response.body()!=null&&!"HEAD".equals(method))try(var input=response.body().byteStream()){byte[] buffer=new byte[8192];int count=0;for(int n;(n=input.read(buffer))>=0;){if(n==0)continue;requireCurrent();count+=n;if(count>MAX_RESOURCE_BYTES||delivered.addAndGet(n)>MAX_VIEW_BYTES)throw new IllegalStateException("活预览资源超过单项4MiB或本页32MiB上限；未返回部分文件");output.write(buffer,0,n);}}
                requireCurrent();String mime="application/octet-stream",encoding="UTF-8";if(response.body()!=null&&response.body().contentType()!=null){var type=response.body().contentType();mime=type.type()+"/"+type.subtype();encoding=type.charset(java.nio.charset.StandardCharsets.UTF_8).name();}
                Map<String,String> headers=Map.of("Content-Security-Policy",contentSecurityPolicy(),"Cache-Control","no-store","Referrer-Policy","no-referrer","X-Content-Type-Options","nosniff","Cross-Origin-Resource-Policy","same-origin");
                String reason=response.message();if(reason==null||reason.trim().isEmpty())reason="Native Preview";
                return new Resource(mime,encoding,response.code(),reason,headers,output.toByteArray());
            }
        }finally{if(call!=null)active.remove(call);reads.release();}
    }
    public long deliveredBytes(){return delivered.get();}
    /** Only this viewer's HTTP calls. The original native tunnel has its own explicit close. */
    @Override public void close(){if(!closed.compareAndSet(false,true))return;for(Call call:active)call.cancel();active.clear();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
}
