package dev.pocketengine.features.proxy.accounts;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A short-lived OAuth callback only. Never a model relay or an external execution entrance. */
final class LoopbackAuthorization implements AutoCloseable {
    interface Handler {void complete(LoopbackAuthorization session,Map<String,String> callback)throws GatewayFailure;void expired(LoopbackAuthorization session);}
    final String id=UUID.randomUUID().toString(),state=random(),nonce=random(),verifier=random(),callback,accountId;
    final long expiresAt;
    volatile GatewayCall operation;
    final Handler handler;
    final ServerSocket server;
    private final long deadline;
    private volatile boolean closed,consumed;
    private volatile Socket peer;
    volatile long accountVersion;
    /** A returning login must not displace an existing usable grant before publication. */
    volatile boolean preserveExisting;
    volatile String status="pending",error="";
    LoopbackAuthorization(String accountId,long accountVersion,long ttlMillis,Handler handler)throws GatewayFailure {
        this.accountId=accountId;this.accountVersion=accountVersion;this.handler=handler;
        expiresAt=System.currentTimeMillis()+ttlMillis;deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        try{server=new ServerSocket();server.bind(new InetSocketAddress("127.0.0.1",0),4);server.setSoTimeout(250);callback="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";}
        catch(Exception error){throw new GatewayFailure("CALLBACK_LISTENER_UNAVAILABLE");}
    }
    /** The expiry worker is cleanup, not the authority for crossing this deadline. */
    boolean deadlineExpired(){return System.nanoTime()>=deadline;}
    String challenge()throws GatewayFailure {try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}catch(Exception error){throw new GatewayFailure("AUTHORIZATION_UNAVAILABLE");}}
    void start(){
        Thread thread=new Thread(this::listen,"pocket-account-login");thread.setDaemon(true);thread.start();
        // Code exchange can block after the listener has consumed its callback. The original TTL still applies.
        Thread expiry=new Thread(()->{
            try{while(!closed&&System.nanoTime()<deadline)Thread.sleep(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
                if(!closed)handler.expired(this);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },"pocket-account-login-expiry");expiry.setDaemon(true);expiry.start();
    }
    private void listen(){
        int attempts=0;
        try{
            while(!closed&&!consumed&&System.nanoTime()<deadline&&attempts<16){
                try(Socket socket=server.accept()){
                    peer=socket;socket.setSoTimeout(2000);attempts++;
                    Map<String,String> args=parse(socket);
                    if(!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),args.getOrDefault("state","").getBytes(StandardCharsets.UTF_8))){reply(socket,400);continue;}
                    if(System.nanoTime()>=deadline){reply(socket,410);break;}
                    boolean hasCode=args.containsKey("code"),hasError=args.containsKey("error");
                    if(hasCode==hasError||hasCode&&(args.get("code").isEmpty()||args.get("code").length()>4096)||hasError&&args.get("error").isEmpty()){reply(socket,400);continue;}
                    synchronized(this){if(closed||consumed){reply(socket,410);break;}consumed=true;}
                    // A valid callback is not a completed token exchange. Send the receipt first;
                    // the native account snapshot, not the browser page, owns the final outcome.
                    try{reply(socket,200);}catch(Exception browserClosed){/* A closed tab must not lose the authorization code. */}
                    try{handler.complete(this,args);}catch(GatewayFailure failure){/* The owner records the failure. */}
                }catch(java.net.SocketTimeoutException waiting){}catch(Exception invalid){if(peer!=null)try{reply(peer,400);}catch(Exception ignored){}}
                finally{peer=null;}
            }
            if(!closed&&!consumed)handler.expired(this);
        }catch(Exception ignored){if(!closed&&!consumed)handler.expired(this);}
        finally{close();}
    }
    private Map<String,String> parse(Socket socket)throws Exception {
        long readDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);java.io.InputStream input=socket.getInputStream();
        String start=line(input,readDeadline);String[] pieces=start.split(" ");
        if(pieces.length!=3||!"GET".equals(pieces[0])||!"HTTP/1.1".equals(pieces[2])||!pieces[1].startsWith("/auth/callback?"))throw new Exception();
        int bytes=start.length();Map<String,String> headers=new LinkedHashMap<>();
        while(true){String line=line(input,readDeadline);bytes+=line.length();if(bytes>8192)throw new Exception();if(line.isEmpty())break;int colon=line.indexOf(':');if(colon<1)throw new Exception();String key=line.substring(0,colon).toLowerCase(java.util.Locale.ROOT);if(headers.put(key,line.substring(colon+1).trim())!=null)throw new Exception();}
        if(!("127.0.0.1:"+server.getLocalPort()).equals(headers.get("host"))||headers.containsKey("transfer-encoding")||headers.containsKey("origin")||headers.containsKey("content-length"))throw new Exception();
        Map<String,String> args=new LinkedHashMap<>();String query=pieces[1].substring("/auth/callback?".length());
        for(String pair:query.split("&")){String[] kv=pair.split("=",2);String key=URLDecoder.decode(kv[0],"UTF-8"),value=kv.length==2?URLDecoder.decode(kv[1],"UTF-8"):"";if(args.put(key,value)!=null)throw new Exception();}
        return args;
    }
    private static String line(java.io.InputStream input,long deadline)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1){if(System.nanoTime()>deadline||bytes.size()>8192)throw new Exception();if(b=='\n'){byte[] value=bytes.toByteArray();int n=value.length;if(n==0||value[n-1]!='\r')throw new Exception();return new String(value,0,n-1,StandardCharsets.US_ASCII);}if(b<32&&b!='\r')throw new Exception();if(b>126)throw new Exception();bytes.write(b);}throw new Exception();
    }
    private static void reply(Socket socket,int status)throws Exception {
        String heading=status==200?"Authorization response received":"Authorization was not completed";
        String detail=status==200?"Return to Pocket World to check whether sign-in has finished.":"Return to Pocket World to check the login error.";
        byte[] body=("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Pocket World sign-in</title><body><main><h1>"+heading+"</h1><p>"+detail+"</p></main></body></html>").getBytes(StandardCharsets.UTF_8);
        String headers="HTTP/1.1 "+status+" Result\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    @Override public void close(){closed=true;if(operation!=null)operation.cancel();try{server.close();}catch(Exception ignored){}Socket active=peer;if(active!=null)try{active.close();}catch(Exception ignored){}}
}
