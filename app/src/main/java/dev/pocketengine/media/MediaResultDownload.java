package dev.pocketengine.media;

import dev.pocketengine.browser.PublicHttpReader;
import dev.pocketengine.core.RunControl;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;

/** Only an already-confirmed provider result descriptor reaches this downloader. It does not
 * accept a model URL tool, redirect, cookie profile, proxy, second POST or alternate credential. */
public final class MediaResultDownload {
    private static final long DEADLINE_MS=45000;
    private static final Semaphore IN_FLIGHT=new Semaphore(2);
    private static final OkHttpClient BASE=new OkHttpClient.Builder()
        .connectionPool(new ConnectionPool(0,1,TimeUnit.MILLISECONDS))
        .cookieJar(CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .proxy(Proxy.NO_PROXY).cache(null).protocols(Collections.singletonList(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(DEADLINE_MS,TimeUnit.MILLISECONDS).build();
    private MediaResultDownload(){}
    public static URI validate(String raw)throws Exception {
        if(raw==null||raw.length()>16384||raw.chars().anyMatch(c->c<=32||c==127))throw new SecurityException("原产物URL无效或过长");URI uri=new URI(raw);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535||uri.getRawAuthority().contains("%")||uri.getPath()!=null&&uri.getPath().contains("\\"))throw new SecurityException("原产物只支持无账户信息的HTTPS地址");
        String host=uri.getHost();if(host.contains(":")||host.matches("[0-9.]+")){InetAddress address=InetAddress.getByName(host.replace("[","").replace("]",""));if(!PublicHttpReader.publicAddress(address))throw new SecurityException("原产物不是公有地址");}return uri;
    }
    static boolean sameOrigin(URI result,URI configured){return result.getScheme().equalsIgnoreCase(configured.getScheme())&&result.getHost().equalsIgnoreCase(configured.getHost())&&port(result)==port(configured);}
    private static int port(URI uri){return uri.getPort()<0?(uri.getScheme().equalsIgnoreCase("https")?443:80):uri.getPort();}
    public static byte[] getConfirmedResult(String raw,String configuredBase,String providerKey,RunControl control)throws Exception {
        URI target=validate(raw),configured=MediaTransport.base(configuredBase);java.util.Objects.requireNonNull(control).check();
        if(providerKey!=null&&(providerKey.length()>8192||providerKey.chars().anyMatch(c->c<32||c==127)))throw new SecurityException("媒体凭据格式无效");
        if(!IN_FLIGHT.tryAcquire())throw new IllegalStateException("原产物下载的原生调用仍待退出；没有另起下载");
        Set<InetAddress> approved=ConcurrentHashMap.newKeySet();String host=target.getHost();String lookupHost=host.replace("[","").replace("]","");
        if(host.contains(":")||host.matches("[0-9.]+"))approved.add(InetAddress.getByName(lookupHost));
        AtomicBoolean callbackDone=new AtomicBoolean(),consumerDone=new AtomicBoolean(),released=new AtomicBoolean();Runnable release=()->{if(callbackDone.get()&&consumerDone.get()&&released.compareAndSet(false,true))IN_FLIGHT.release();};
        CompletableFuture<byte[]> received=new CompletableFuture<>();Call call=null;Runnable stop=null;boolean enqueued=false;long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        try {
            OkHttpClient client=BASE.newBuilder().dns(requested->{
                try{control.check();if(!requested.equalsIgnoreCase(lookupHost))throw new SecurityException("产物DNS身份改变");List<InetAddress> addresses=Dns.SYSTEM.lookup(requested);if(addresses.isEmpty())throw new UnknownHostException("原产物没有地址");for(InetAddress address:addresses)if(!PublicHttpReader.publicAddress(address))throw new SecurityException("原产物DNS含非公有地址");control.check();approved.addAll(addresses);return addresses;}
                catch(Exception rejected){UnknownHostException failure=new UnknownHostException("原产物DNS准入失败");failure.initCause(rejected);throw failure;}
            }).eventListener(new okhttp3.EventListener(){@Override public void connectStart(Call actual,InetSocketAddress address,Proxy proxy){
                try{control.check();if(proxy.type()!=Proxy.Type.DIRECT||address.isUnresolved()||address.getPort()!=port(target)||!approved.contains(address.getAddress())||!PublicHttpReader.publicAddress(address.getAddress()))throw new SecurityException("原产物实际连接地址未核准");}
                catch(Exception invalid){actual.cancel();throw new SecurityException("原产物实际连接准入结束",invalid);}
            }}).build();
            Request.Builder request=new Request.Builder().url(target.toASCIIString()).get().header("Accept-Encoding","identity");
            // A signed CDN URL gets ZERO provider auth. Same-origin credentials are limited to
            // this exact configured provider and this confirmed output GET, never redirects.
            if(sameOrigin(target,configured)&&providerKey!=null&&!providerKey.isEmpty())request.header("Authorization","Bearer "+providerKey);
            call=client.newCall(request.build());Call original=call;stop=original::cancel;control.onCancel(stop);control.check();
            original.enqueue(new Callback(){
                public void onFailure(Call ignored,IOException failure){try{received.completeExceptionally(failure);}finally{callbackDone.set(true);release.run();}}
                public void onResponse(Call ignored,Response response){try(Response actual=response){
                    control.check();if(actual.code()<200||actual.code()>=300)throw new MediaTransport.Rejected(actual.code());ResponseBody body=actual.body();if(body==null)throw new EOFException("原产物没有正文");long declared=body.contentLength();if(declared>MediaTransport.MAX_MEDIA_BYTES)throw new IOException("原产物超过20MiB");String encoding=actual.header("Content-Encoding");if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("原产物返回未请求的压缩正文");
                    try(InputStream input=body.byteStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))!=-1;){control.check();if(System.nanoTime()>=end)throw new SocketTimeoutException("原产物下载到期");if(bytes.size()+(long)n>MediaTransport.MAX_MEDIA_BYTES)throw new IOException("原产物超过20MiB");bytes.write(buffer,0,n);}control.check();if(declared>=0&&declared!=bytes.size())throw new EOFException("原产物正文不完整");if(System.nanoTime()>=end)throw new SocketTimeoutException("原产物下载到期");byte[] value=bytes.toByteArray();if(!received.complete(value))Arrays.fill(value,(byte)0);
                    }
                }catch(Exception failure){received.completeExceptionally(failure);}finally{callbackDone.set(true);release.run();}}
            });
            enqueued=true;
            for(;;){control.check();long left=end-System.nanoTime();if(left<=0)throw new SocketTimeoutException("原产物读取到期；保留原描述，只能再核原件");try{byte[] bytes=received.get(Math.min(TimeUnit.NANOSECONDS.toMillis(left)+1,50),TimeUnit.MILLISECONDS);control.check();return bytes;}catch(TimeoutException waiting){}catch(ExecutionException failed){Throwable cause=failed.getCause();if(cause instanceof Exception)throw(Exception)cause;throw new IOException("原产物下载异常",cause);}}
        } finally {if(stop!=null)control.removeCancelAction(stop);consumerDone.set(true);if(call!=null&&!received.isDone())call.cancel();if(!enqueued)callbackDone.set(true);release.run();}
    }
}
