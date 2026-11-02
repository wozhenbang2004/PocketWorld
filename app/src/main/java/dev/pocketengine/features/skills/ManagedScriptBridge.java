package dev.pocketengine.features.skills;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Ephemeral same-UID Unix transport belonging to one approved managed script. */
final class ManagedScriptBridge implements AutoCloseable {
    @FunctionalInterface interface Peer {void require(int pid)throws Exception;}
    final String name,token;private final LocalServerSocket server;private final ManagedScriptBridgeSession session;private final Set<LocalSocket> clients=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    ManagedScriptBridge(Peer peer,ManagedScriptBridgeSession.Phone phone,ManagedScriptBridgeSession.Current current,ManagedScriptBridgeSession.Outputs outputs)throws Exception{
        byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);token=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);name="pw-skill-"+UUID.randomUUID();session=new ManagedScriptBridgeSession(token,phone,current,outputs);server=new LocalServerSocket(name);
        Thread acceptor=new Thread(()->{while(!closed){LocalSocket socket=null;try{socket=server.accept();if(closed||socket.getPeerCredentials().getUid()!=Process.myUid()||clients.size()>=4){socket.close();continue;}try{peer.require(socket.getPeerCredentials().getPid());}catch(Exception refused){socket.close();continue;}socket.setSoTimeout(15000);clients.add(socket);LocalSocket accepted=socket;Thread worker=new Thread(()->serve(accepted),"pocket-skill-bridge-request");worker.setDaemon(true);worker.start();}catch(Exception unavailable){if(socket!=null)try{socket.close();}catch(Exception ignored){}if(!closed)close();}}},"pocket-skill-bridge-accept");acceptor.setDaemon(true);acceptor.start();
    }
    JSONObject metadata()throws Exception{return new JSONObject().put("name",name).put("token",token).put("phoneTools",session.allowedTools()).put("sameUidOnly",true).put("peerAuthority","same_original_owned_session_and_live_leader_birth").put("lifetime","original_operation");}
    private void serve(LocalSocket socket){try{var in=socket.getInputStream();var out=socket.getOutputStream();while(!closed){ByteArrayOutputStream buffer=new ByteArrayOutputStream();int value;while((value=in.read())>=0&&value!='\n'){if(buffer.size()>=16384)throw new SecurityException("bridge_request_too_large");buffer.write(value);}if(value<0)break;String line=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString();JSONObject response=session.handle(line);out.write((response.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}}catch(Exception refused){/* A disconnected client has no authority to replay a callback. */}finally{clients.remove(socket);try{socket.close();}catch(Exception ignored){}}}
    @Override public void close(){if(closed)return;closed=true;session.close();try{server.close();}catch(Exception ignored){}for(LocalSocket socket:clients)try{socket.close();}catch(Exception ignored){}clients.clear();}
}
