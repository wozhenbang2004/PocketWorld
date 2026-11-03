package dev.pocketengine.features.skills;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Deliberately in-memory protocol seam. It proves scope/ledger gates, NOT Unix
 * kernel credentials, the fixed CLI, Android sockets or a device journey. */
public final class ManagedScriptCallbackTestTransport implements ManagedScriptCallbackTransport.Factory {
    public final BlockingQueue<Channel> pending=new LinkedBlockingQueue<>();
    public final AtomicInteger servers=new AtomicInteger(),clients=new AtomicInteger();
    @Override public ManagedScriptCallbackTransport.Server open(String name){servers.incrementAndGet();return new ManagedScriptCallbackTransport.Server(){
        private boolean closed;
        public JSONObject endpoint()throws Exception{return new JSONObject().put("namespace","test-memory").put("name",name);}
        public int hostUid(){return 42;}
        public long hostPid(){return 12345;}
        public synchronized ManagedScriptCallbackTransport.Connection accept(){if(closed)return null;Channel next=pending.poll();if(next!=null)clients.incrementAndGet();return next;}
        public synchronized void close(){if(!closed){closed=true;servers.decrementAndGet();}}
    };}
    public Channel offer(byte[] frame){Channel channel=new Channel(frame);pending.add(channel);return channel;}
    public final class Channel implements ManagedScriptCallbackTransport.Connection {
        public boolean peer=true,eof=true,failWrite;public int readWidth=3,writeWidth=7;
        private final byte[] frame;private int read;private boolean closed;
        private final ByteArrayOutputStream response=new ByteArrayOutputStream();private final CountDownLatch done=new CountDownLatch(1);
        Channel(byte[] frame){this.frame=frame;}
        public boolean sameHostUid(){return peer;}
        public synchronized int read(byte[] bytes,int offset,int length){if(closed)return -1;if(read==frame.length)return eof?-1:0;int count=Math.min(Math.min(length,readWidth),frame.length-read);System.arraycopy(frame,read,bytes,offset,count);read+=count;return count;}
        public synchronized int write(byte[] bytes,int offset,int length)throws Exception{if(closed||failWrite)throw new java.io.IOException("own simulated reply loss");int count=Math.min(length,writeWidth);response.write(bytes,offset,count);return count;}
        public synchronized void close(){if(!closed){closed=true;clients.decrementAndGet();done.countDown();}}
        public byte[] reply()throws Exception{if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("own memory request did not close");synchronized(this){return response.toByteArray();}}
        public boolean awaitClosed(long millis)throws Exception{return done.await(millis,TimeUnit.MILLISECONDS);}
    }
}
