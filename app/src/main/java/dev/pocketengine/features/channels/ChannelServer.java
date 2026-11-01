package dev.pocketengine.features.channels;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Explicitly started, loopback-only, bounded HTTP ingress. It cannot dispatch native UI commands. */
final class ChannelServer implements AutoCloseable {
    interface Receiver {JSONObject receive(String profileId,Map<String,String> headers,byte[] body)throws Exception;}
    private final Receiver receiver;
    private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private ThreadPoolExecutor workers;
    ChannelServer(Receiver receiver){this.receiver=receiver;}
    synchronized int start(int port)throws Exception {
        if(server!=null&&!server.isClosed())return server.getLocalPort();
        if(port<0||port>65535)throw new IllegalArgumentException("端口无效");
        server=new ServerSocket();server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),16);
        workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"pocket-channel-http");t.setDaemon(true);return t;});
        ServerSocket listener=server;
        Thread accept=new Thread(()->{
            while(!listener.isClosed()) {
                Socket client=null;
                try {client=listener.accept();client.setSoTimeout(5000);clients.add(client);Socket selected=client;workers.execute(()->serve(selected));}
                catch(Exception stopped){if(client!=null){clients.remove(client);try{client.close();}catch(Exception ignored){}}if(listener.isClosed())break;}
            }
        },"pocket-channel-accept");accept.setDaemon(true);accept.start();return server.getLocalPort();
    }
    synchronized JSONObject snapshot()throws Exception {
        boolean running=server!=null&&!server.isClosed();
        return new JSONObject().put("running",running).put("port",running?server.getLocalPort():0).put("loopbackOnly",true)
                .put("baseUrl",running?"http://127.0.0.1:"+server.getLocalPort():"")
                .put("pathTemplate","/events/{profileId}").put("persistentInBackground",false);
    }
    private void serve(Socket socket) {
        try(Socket connected=socket) {
            try {
                InputStream input=connected.getInputStream();String start=line(input,2048);
                String[] request=start.split(" ");if(request.length!=3||!request[0].equals("POST")||!request[2].equals("HTTP/1.1"))throw new IllegalArgumentException("仅接受HTTP/1.1 POST");
                if(!request[1].matches("/events/[A-Za-z0-9-]{1,80}"))throw new IllegalArgumentException("回调路径无效");
                Map<String,String> headers=new HashMap<>();int total=start.length();
                while(true) {
                    String row=line(input,4096);total+=row.length();if(total>8192)throw new IllegalArgumentException("HTTP请求头过大");if(row.isEmpty())break;
                    int colon=row.indexOf(':');if(colon<=0)throw new IllegalArgumentException("HTTP请求头无效");
                    String key=row.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=row.substring(colon+1).trim();
                    if(headers.put(key,value)!=null)throw new IllegalArgumentException("不接受重复HTTP请求头");
                }
                if(headers.containsKey("transfer-encoding"))throw new IllegalArgumentException("不接受分块请求体");
                if(!headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT).startsWith("application/json"))throw new IllegalArgumentException("请求体需为JSON");
                String length=headers.getOrDefault("content-length","");if(!length.matches("[0-9]{1,6}"))throw new IllegalArgumentException("缺少请求长度");
                int size=Integer.parseInt(length);if(size<=0||size>64_000)throw new IllegalArgumentException("消息请求大小无效");
                byte[] body=new byte[size];int offset=0;while(offset<size){int read=input.read(body,offset,size-offset);if(read<0)throw new IllegalArgumentException("请求体不完整");offset+=read;}
                JSONObject response=receiver.receive(request[1].substring("/events/".length()),headers,body);reply(connected,200,response);
            }catch(SecurityException denied){reply(connected,401,jsonError("消息来源认证失败"));}
            catch(IllegalArgumentException invalid){reply(connected,400,jsonError("消息格式无效或当前不支持"));}
            catch(Exception failed){reply(connected,503,jsonError("消息尚未接收，请稍后重试或检查本机设置"));}
        }catch(Exception ignored){}finally{clients.remove(socket);}
    }
    private static String line(InputStream input,int max)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean carriage=false;
        while(out.size()<=max) {
            int b=input.read();if(b<0)throw new IllegalArgumentException("HTTP头不完整");
            if(carriage){if(b=='\n')return new String(out.toByteArray(),StandardCharsets.US_ASCII);throw new IllegalArgumentException("HTTP换行无效");}
            if(b=='\r')carriage=true;else if(b=='\n')throw new IllegalArgumentException("HTTP换行无效");else out.write(b);
        }
        throw new IllegalArgumentException("HTTP行过长");
    }
    private static JSONObject jsonError(String message){JSONObject result=new JSONObject();try{result.put("ok",false).put("message",message);}catch(Exception ignored){}return result;}
    private static void reply(Socket socket,int status,JSONObject json)throws Exception {
        byte[] body=json.toString().getBytes(StandardCharsets.UTF_8);
        String head="HTTP/1.1 "+status+" "+(status==200?"OK":"Error")+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
        OutputStream output=socket.getOutputStream();output.write(head.getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
    }
    @Override public synchronized void close() {
        if(server!=null)try{server.close();}catch(Exception ignored){}server=null;
        for(Socket client:clients)try{client.close();}catch(Exception ignored){}clients.clear();
        if(workers!=null)workers.shutdownNow();workers=null;
    }
}
