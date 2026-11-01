package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Uses the existing OkHttp dependency; no hand-written WebSocket framing or TLS bypass. */
final class QqGatewayClient implements AutoCloseable {
    private final boolean loopbackTest;
    private final OkHttpClient http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).build();
    QqGatewayClient(){loopbackTest=false;}
    QqGatewayClient(boolean loopbackTest){this.loopbackTest=loopbackTest;}
    static String validateGateway(String address)throws Exception{return validateGateway(address,false);}
    private static String validateGateway(String address,boolean test)throws Exception {
        if(address==null||address.length()>2048)throw new SecurityException("QQ网关地址无效");
        URI uri=new URI(address);String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback=test&&host.equals("127.0.0.1")&&"ws".equals(uri.getScheme())&&uri.getPort()>0;
        boolean official="wss".equals(uri.getScheme())&&(host.equals("api.bot.qq.com")||host.equals("api.sgroup.qq.com"))&&(uri.getPort()==-1||uri.getPort()==443);
        if((!official&&!loopback)||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null||!(uri.getPath().equals("/websocket")||uri.getPath().equals("/websocket/")))
            throw new SecurityException("QQ网关必须是官方WSS接入地址");
        return uri.toASCIIString();
    }
    Exit run(ChannelHttp.GatewayAccess access,QqGatewaySession.Resume resume,RunControl control,ChannelHttp.DispatchGuard guard,QqGatewaySession.Events events)throws Exception {
        String address=validateGateway(access.url,loopbackTest);
        if(access.token==null||access.token.isEmpty()||access.token.length()>8192||access.token.indexOf('\r')>=0||access.token.indexOf('\n')>=0)throw new SecurityException("QQ网关登录凭据无效");
        control.check();guard.check();CountDownLatch done=new CountDownLatch(1);
        AtomicReference<WebSocket> active=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();AtomicReference<Exit> result=new AtomicReference<>();
        QqGatewaySession session=new QqGatewaySession(resume,access.token,payload->{
            control.check();guard.check();WebSocket socket=active.get();
            if(socket==null||!socket.send(payload.toString()))throw new IllegalStateException("QQ网关发送队列不可用");
        },events,()->{control.check();guard.check();},System.nanoTime()/1_000_000);
        WebSocket socket=http.newWebSocket(new Request.Builder().url(address).build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket,Response response){active.compareAndSet(null,webSocket);}
            @Override public void onMessage(WebSocket webSocket,String text){
                if(done.getCount()==0)return;
                try{session.receive(text,System.nanoTime()/1_000_000);}
                catch(QqGatewaySession.Reconnect reconnect){result.compareAndSet(null,new Exit(0,reconnect.reset,false,false,3000));done.countDown();webSocket.cancel();}
                catch(Exception failed){error.compareAndSet(null,failed);done.countDown();webSocket.cancel();}
            }
            @Override public void onMessage(WebSocket webSocket,ByteString bytes){error.compareAndSet(null,new IllegalArgumentException("QQ网关不接受二进制消息"));done.countDown();webSocket.cancel();}
            @Override public void onClosing(WebSocket webSocket,int code,String reason){result.compareAndSet(null,Exit.closed(code));done.countDown();webSocket.close(1000,"");}
            @Override public void onClosed(WebSocket webSocket,int code,String reason){result.compareAndSet(null,Exit.closed(code));done.countDown();}
            @Override public void onFailure(WebSocket webSocket,Throwable failure,Response response){
                // Raw exceptions and provider reasons can contain tokens or private content.
                result.compareAndSet(null,Exit.closed(response==null?0:response.code()));done.countDown();
            }
        });
        active.compareAndSet(null,socket);Runnable cancel=()->{socket.cancel();done.countDown();};control.onCancel(cancel);
        try{
            while(!done.await(100,TimeUnit.MILLISECONDS)){
                control.check();
                try{session.tick(System.nanoTime()/1_000_000);}
                catch(QqGatewaySession.Reconnect reconnect){return new Exit(0,reconnect.reset,false,false,3000);}
            }
            control.check();guard.check();Exception failed=error.get();if(failed!=null)throw failed;
            Exit exit=result.get();return exit==null?Exit.closed(0):exit;
        }finally{session.retire();control.removeCancelAction(cancel);socket.cancel();}
    }
    static final class Exit {
        final int code;final boolean clearSession,refreshToken,fatal;final long minimumDelay;
        Exit(int code,boolean clearSession,boolean refreshToken,boolean fatal,long minimumDelay){this.code=code;this.clearSession=clearSession;this.refreshToken=refreshToken;this.fatal=fatal;this.minimumDelay=minimumDelay;}
        static Exit closed(int code){return new Exit(code,code==4006||code==4007||code==4009||code>=4900&&code<=4913,code==4004,code==4914||code==4915||code==401||code==403,code==4008?60_000:3000);}
    }
    @Override public void close(){http.dispatcher().cancelAll();http.connectionPool().evictAll();http.dispatcher().executorService().shutdown();}
}
