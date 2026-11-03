package dev.pocketengine.features.skills.hostfixture;

import dev.pocketengine.features.skills.ManagedScriptCallbackTransport;
import java.net.StandardProtocolFamily;
import java.net.SocketOption;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipal;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** JDK17/Linux test adapter only, compiled by the explicit test fixture outside
 * Android sources. This never ships in the APK or stands in for device evidence. */
public final class SkillCallbackUnixTransport implements ManagedScriptCallbackTransport.Factory {
    private final int uid;private final String user;private final AtomicInteger servers=new AtomicInteger(),clients=new AtomicInteger();
    public SkillCallbackUnixTransport(int uid,String user){this.uid=uid;this.user=user;}
    public int openServers(){return servers.get();}public int openClients(){return clients.get();}
    /** Module-only regression observation; never opens this fixture's endpoint. */
    public JSONObject credentialApiFacts()throws Exception {
        Class<?> api=credentialApi();Object option=peerCredentials();
        return new JSONObject().put("module",api.getModule().getName()).put("platformModule",api==Class.forName("jdk.net.ExtendedSocketOptions",false,ClassLoader.getPlatformClassLoader())).put("option",((java.net.SocketOption<?>)option).name());
    }
    private static Class<?> credentialApi()throws Exception {
        // The product interface must stay in the Android test sandbox, but JDK
        // network classes must retain their REAL named module (bootstrap-owned
        // on JDK17). Do not let SandboxClassLoader redefine jdk.net in unnamed.
        return Class.forName("jdk.net.ExtendedSocketOptions",true,ClassLoader.getPlatformClassLoader());
    }
    private static SocketOption<?> peerCredentials()throws Exception{return (SocketOption<?>)credentialApi().getField("SO_PEERCRED").get(null);}
    private static String peerUser(SocketChannel channel)throws Exception {
        Object actual=channel.getOption(peerCredentials()); // Actual kernel credentials, never a fixture value.
        return ((UserPrincipal)actual.getClass().getMethod("user").invoke(actual)).getName();
    }
    @Override public ManagedScriptCallbackTransport.Server open(String ignored)throws Exception{return new Server();}
    private final class Server implements ManagedScriptCallbackTransport.Server {
        final Path directory=Files.createTempDirectory("pw-skill-unix-"),path=directory.resolve("socket");final ServerSocketChannel channel;boolean closed;
        Server()throws Exception {ServerSocketChannel opened=null;try{opened=ServerSocketChannel.open(StandardProtocolFamily.UNIX);opened.bind(UnixDomainSocketAddress.of(path));opened.configureBlocking(false);servers.incrementAndGet();channel=opened;}catch(Exception failure){if(opened!=null)opened.close();Files.deleteIfExists(path);Files.delete(directory);throw failure;}}
        public JSONObject endpoint()throws Exception{return new JSONObject().put("namespace","filesystem").put("name",path.toString());}public int hostUid(){return uid;}
        public long hostPid(){return ProcessHandle.current().pid();}
        public synchronized ManagedScriptCallbackTransport.Connection accept()throws Exception {if(closed)return null;SocketChannel client=channel.accept();if(client==null)return null;try{return new Connection(client);}catch(Exception|Error failed){client.close();throw failed;}}
        public synchronized void close()throws Exception {if(!closed){closed=true;try{channel.close();}finally{servers.decrementAndGet();Files.deleteIfExists(path);Files.deleteIfExists(directory);}}}
    }
    private final class Connection implements ManagedScriptCallbackTransport.Connection {
        final SocketChannel channel;final boolean same;boolean closed;
        Connection(SocketChannel channel)throws Exception{this.channel=channel;channel.configureBlocking(false);same=user.equals(peerUser(channel));clients.incrementAndGet();}
        public boolean sameHostUid(){return same;}
        public synchronized int read(byte[] bytes,int offset,int length)throws Exception{return closed?-1:channel.read(ByteBuffer.wrap(bytes,offset,length));}
        public synchronized int write(byte[] bytes,int offset,int length)throws Exception{if(closed)throw new java.io.IOException("test original channel closed");return channel.write(ByteBuffer.wrap(bytes,offset,length));}
        public synchronized void close()throws Exception{if(!closed){closed=true;try{channel.close();}finally{clients.decrementAndGet();}}}
    }
}
