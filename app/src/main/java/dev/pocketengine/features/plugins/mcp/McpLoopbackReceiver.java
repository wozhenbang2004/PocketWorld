package dev.pocketengine.features.plugins.mcp;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Short-lived, exact native OAuth callback. Never an MCP server, relay or Agent/tool execution entrance. */
final class McpLoopbackReceiver implements AutoCloseable {
    interface Callback {void complete(String exactCallback)throws Exception;void expired();}
    private final ServerSocket server;private final String path;private final long deadline;
    private volatile boolean closed,consumed;private volatile Socket peer;private Thread listener,timer;
    McpLoopbackReceiver(int port)throws Exception {this(port,"/mcp/oauth/callback",System.currentTimeMillis()+300_000L);}
    McpLoopbackReceiver(int port,String path,long expiresAt)throws Exception {
        if(port<0||port>65535||!path.equals("/mcp/oauth/callback")||expiresAt<=System.currentTimeMillis())throw new SecurityException("原授权回调已失效");this.path=path;deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(300_000L,expiresAt-System.currentTimeMillis()));
        server=new ServerSocket();try{server.bind(new InetSocketAddress("127.0.0.1",port),4);server.setSoTimeout(250);}catch(Exception unavailable){try{server.close();}catch(Exception ignored){}throw new IllegalStateException("本机回调端口不可用，未发起授权");}
    }
    String redirect(){return "http://127.0.0.1:"+server.getLocalPort()+path;}
    synchronized void start(String state,Callback callback){
        if(listener!=null||closed)throw new IllegalStateException("原授权监听已启动或关闭");
        listener=new Thread(()->listen(state,callback),"pocket-mcp-oauth-callback");listener.setDaemon(true);listener.start();
        timer=new Thread(()->{try{while(!closed&&System.nanoTime()<deadline)Thread.sleep(100);if(!closed)callback.expired();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}},"pocket-mcp-oauth-expiry");timer.setDaemon(true);timer.start();
    }
    private void listen(String expected,Callback callback){
        try{int attempts=0;while(!closed&&!consumed&&System.nanoTime()<deadline&&attempts++<16){
            try(Socket socket=server.accept()){
                peer=socket;socket.setSoTimeout(2000);String target=target(socket);Map<String,String> query=McpOAuth.query(target.substring(path.length()+1));String state=query.get("state");
                if(state==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),state.getBytes(StandardCharsets.UTF_8))){reply(socket,400);continue;}
                synchronized(this){if(closed||consumed||System.nanoTime()>=deadline){reply(socket,410);break;}consumed=true;}
                try{callback.complete(redirect()+target.substring(path.length()));reply(socket,200);}catch(Exception failed){reply(socket,400);}break;
            }catch(java.net.SocketTimeoutException waiting){attempts--;}catch(Exception invalid){Socket current=peer;if(current!=null)try{reply(current,400);}catch(Exception ignored){}}
            finally{peer=null;}
        }if(!closed&&!consumed)callback.expired();}catch(Exception stopped){if(!closed&&!consumed)callback.expired();}finally{close();}
    }
    private String target(Socket socket)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);java.io.InputStream input=socket.getInputStream();String first=line(input,until);String[] parts=first.split(" ");if(parts.length!=3||!parts[0].equals("GET")||!parts[2].equals("HTTP/1.1")||!parts[1].startsWith(path+"?")||parts[1].length()>16384)throw new SecurityException("不是原授权回调");
        Map<String,String> headers=new LinkedHashMap<>();int size=first.length();while(true){String row=line(input,until);size+=row.length();if(size>24576)throw new SecurityException("回调过大");if(row.isEmpty())break;int colon=row.indexOf(':');if(colon<1||headers.put(row.substring(0,colon).toLowerCase(java.util.Locale.ROOT),row.substring(colon+1).trim())!=null)throw new SecurityException("回调标头无效");}
        if(!("127.0.0.1:"+server.getLocalPort()).equals(headers.get("host"))||headers.containsKey("origin")||headers.containsKey("transfer-encoding")||headers.containsKey("content-length"))throw new SecurityException("不是原本机浏览器回调");return parts[1];
    }
    private static String line(java.io.InputStream input,long deadline)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();while(true){int b=input.read();if(b<0||System.nanoTime()>deadline||out.size()>16384)throw new java.io.IOException("授权回调读取未完成");if(b=='\n'){byte[] value=out.toByteArray();if(value.length<1||value[value.length-1]!='\r')throw new java.io.IOException("回调行格式无效");return new String(value,0,value.length-1,StandardCharsets.US_ASCII);}if((b<32&&b!='\r')||b>126)throw new java.io.IOException("回调编码无效");out.write(b);}}
    private static void reply(Socket socket,int status)throws Exception {byte[] body=(status==200?"Authorization finished. Return to Pocket World.":"Authorization was not completed. Return to Pocket World.").getBytes(StandardCharsets.UTF_8);String headers="HTTP/1.1 "+status+" Result\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}
    @Override public void close(){closed=true;try{server.close();}catch(Exception ignored){}Socket active=peer;if(active!=null)try{active.close();}catch(Exception ignored){}Thread expiry=timer;if(expiry!=null)expiry.interrupt();}
}
