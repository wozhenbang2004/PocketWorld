package dev.pocketengine.browser;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed same-process abstract socket client. No TCP listener, forwarding, endpoint or CDP tool. */
final class BrowserDevToolsConnection implements AutoCloseable {
    static final int MAX_MESSAGE=262144,MAX_TARGETS=16;
    private static final Set<String> METHODS=Set.of("Runtime.enable","Runtime.evaluate","Page.enable","Page.getFrameTree","Network.enable","Network.getResponseBody");
    private static final SecureRandom RANDOM=new SecureRandom();
    private final LocalSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private final Consumer<JSONObject> event;
    private final Consumer<String> disconnected;
    private final Map<Integer,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final AtomicInteger sequence=new AtomicInteger();
    private volatile boolean closed;
    /** Includes ignored CDP events, console records and protocol responses, not just retained rows. */
    static final class WireBudget {
        int messages;long bytes;
        void accept(String message)throws IOException {bytes+=message.getBytes(StandardCharsets.UTF_8).length;if(++messages>8192||bytes>16L*1024*1024)throw new IOException("devtools_wire_budget");}
    }

    private static LocalSocket local()throws Exception {
        LocalSocket socket=new LocalSocket();
        try {
            socket.setSoTimeout(1200);
            socket.connect(new LocalSocketAddress("webview_devtools_remote_"+Process.myPid(),LocalSocketAddress.Namespace.ABSTRACT));
            android.net.Credentials peer=socket.getPeerCredentials();
            if(peer.getUid()!=Process.myUid()||peer.getPid()!=Process.myPid())throw new SecurityException("devtools_peer_not_this_process");
            return socket;
        }catch(Exception failure){socket.close();throw failure;}
    }
    /** Does not switch debugging on; only a native owner UI may do that. */
    static boolean alreadyListening(){try(LocalSocket ignored=local()){return true;}catch(Exception absent){return false;}}
    static List<String> targets()throws Exception {
        try(LocalSocket socket=local()){
            OutputStream out=socket.getOutputStream();out.write("GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();
            InputStream in=socket.getInputStream();Map<String,String> headers=headers(in,200);int length=Integer.parseInt(headers.getOrDefault("content-length","-1"));
            if(length<0||length>131072||headers.containsKey("transfer-encoding"))throw new IOException("devtools_target_list_unbounded");
            JSONArray raw=new JSONArray(utf8(exact(in,length)));if(raw.length()>MAX_TARGETS)throw new IOException("devtools_target_limit");List<String> paths=new ArrayList<>();
            for(int i=0;i<raw.length();i++){JSONObject target=raw.getJSONObject(i);if(!target.optString("type").equals("page"))continue;String path=targetPath(target.optString("webSocketDebuggerUrl"));if(!paths.contains(path))paths.add(path);}
            return paths;
        }
    }
    static String targetPath(String supplied)throws Exception {
        URI uri=new URI(supplied);String path=uri.getRawPath();
        if(!"ws".equals(uri.getScheme())||!Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost()==null?"":uri.getHost())||uri.getPort()>65535||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||path==null||!path.matches("/devtools/page/[A-Za-z0-9_-]{1,128}"))throw new SecurityException("devtools_target_path_invalid");
        // Deliberately discard the advertised host/port. The only destination is local().
        return path;
    }
    BrowserDevToolsConnection(String path,Consumer<JSONObject> event,Consumer<String> disconnected)throws Exception {
        if(!path.matches("/devtools/page/[A-Za-z0-9_-]{1,128}"))throw new SecurityException("devtools_target_path_invalid");
        this.event=event;this.disconnected=disconnected;socket=local();
        try{
            input=socket.getInputStream();output=socket.getOutputStream();byte[] random=new byte[16];RANDOM.nextBytes(random);String key=Base64.getEncoder().encodeToString(random);
            output.write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+key+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
            Map<String,String> response=headers(input,101);String expected=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            if(!expected.equals(response.get("sec-websocket-accept"))||!"websocket".equalsIgnoreCase(response.get("upgrade"))||!response.getOrDefault("connection","").toLowerCase(Locale.ROOT).contains("upgrade")||response.containsKey("sec-websocket-extensions"))throw new IOException("devtools_upgrade_invalid");
            socket.setSoTimeout(0);Thread reader=new Thread(this::read,"pw-native-network-reader");reader.setDaemon(true);reader.start();
        }catch(Exception failure){socket.close();throw failure;}
    }
    JSONObject call(String method,JSONObject parameters,long timeoutMs)throws Exception {
        if(closed||!METHODS.contains(method)||timeoutMs<1||timeoutMs>3000)throw new IOException("devtools_call_unavailable");
        int id=sequence.incrementAndGet();CompletableFuture<JSONObject> future=new CompletableFuture<>();pending.put(id,future);
        try{
            byte[] payload=new JSONObject().put("id",id).put("method",method).put("params",parameters).toString().getBytes(StandardCharsets.UTF_8);
            synchronized(output){if(closed)throw new IOException("devtools_closed");writeFrame(output,1,payload);}
            JSONObject message=future.get(timeoutMs,TimeUnit.MILLISECONDS);if(message.has("error"))throw new IOException("devtools_command_rejected");return message.getJSONObject("result");
        }finally{pending.remove(id);}
    }
    private void read(){
        WireBudget budget=new WireBudget();
        try{while(!closed){String text=readMessage(input,(opcode,body)->{synchronized(output){writeFrame(output,opcode,body);}});budget.accept(text);JSONObject message=new JSONObject(text);if(message.has("id")){CompletableFuture<JSONObject> future=pending.get(message.getInt("id"));if(future!=null)future.complete(message);}else if(message.has("method"))event.accept(message);}}
        catch(Exception failure){if(!closed){close();disconnected.accept("devtools_wire_budget".equals(failure.getMessage())?"native_network_transport_budget":"native_network_connection_lost");}}
    }
    interface ControlWriter {void write(int opcode,byte[] body)throws IOException;}
    static String readMessage(InputStream in,ControlWriter control)throws IOException {
        ByteArrayOutputStream message=new ByteArrayOutputStream();boolean continuation=false;
        for(;;){int first=in.read(),second=in.read();if(first<0||second<0)throw new EOFException();boolean fin=(first&128)!=0;int opcode=first&15;
            if((first&112)!=0||(second&128)!=0)throw new IOException("devtools_frame_flags");long length=second&127;
            if(length==126)length=((long)one(in)<<8)|one(in);else if(length==127){length=0;for(int i=0;i<8;i++){int b=one(in);if(i==0&&(b&128)!=0)throw new IOException("devtools_frame_length");length=(length<<8)|b;}}
            if(length>MAX_MESSAGE||length<0||opcode<8&&message.size()+length>MAX_MESSAGE)throw new IOException("devtools_message_limit");
            if(opcode>=8){if(!fin||length>125)throw new IOException("devtools_control_frame");byte[] body=exact(in,(int)length);if(opcode==8)throw new EOFException();if(opcode==9)control.write(10,body);else if(opcode!=10)throw new IOException("devtools_control_opcode");continue;}
            if(opcode!=0&&opcode!=1||opcode==0&&!continuation||opcode==1&&continuation)throw new IOException("devtools_message_opcode");
            message.write(exact(in,(int)length));if(fin)return utf8(message.toByteArray());continuation=true;
        }
    }
    static void writeFrame(OutputStream out,int opcode,byte[] bytes)throws IOException {
        if(bytes.length>MAX_MESSAGE)throw new IOException("devtools_message_limit");byte[] mask=new byte[4];RANDOM.nextBytes(mask);out.write(128|opcode);
        if(bytes.length<126)out.write(128|bytes.length);else if(bytes.length<=65535){out.write(128|126);out.write(bytes.length>>>8);out.write(bytes.length);}else{out.write(128|127);for(int i=7;i>=0;i--)out.write((int)(((long)bytes.length>>>(8*i))&255));}
        out.write(mask);for(int i=0;i<bytes.length;i++)out.write(bytes[i]^mask[i&3]);out.flush();
    }
    private static Map<String,String> headers(InputStream input,int status)throws IOException {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();int state=0;
        while(state<4){int b=one(input);if(raw.size()>=16384)throw new IOException("devtools_headers_limit");raw.write(b);state=(state==0||state==2)?(b==13?state+1:0):(b==10?state+1:b==13?1:0);}
        String[] lines=new String(raw.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");if(!lines[0].matches("HTTP/1\\.[01] "+status+"(?: .*)?"))throw new IOException("devtools_http_status");
        Map<String,String> result=new LinkedHashMap<>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<=0)throw new IOException("devtools_header_invalid");String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);if(result.put(name,lines[i].substring(colon+1).trim())!=null)throw new IOException("devtools_duplicate_header");}return result;
    }
    private static int one(InputStream in)throws IOException {int value=in.read();if(value<0)throw new EOFException();return value;}
    private static byte[] exact(InputStream input,int count)throws IOException {byte[] bytes=new byte[count];int read=0;while(read<count){int n=input.read(bytes,read,count-read);if(n<0)throw new EOFException();read+=n;}return bytes;}
    private static String utf8(byte[] bytes)throws IOException {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    @Override public void close(){if(closed)return;closed=true;try{socket.close();}catch(IOException ignored){}for(CompletableFuture<JSONObject> future:pending.values())future.completeExceptionally(new IOException("devtools_closed"));pending.clear();}
}
