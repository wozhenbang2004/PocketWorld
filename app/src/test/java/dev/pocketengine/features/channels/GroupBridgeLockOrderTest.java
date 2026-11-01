package dev.pocketengine.features.channels;

import dev.pocketengine.features.groups.GroupIngressPort;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual native Core/bridge lock order. The accessor hook only schedules existing instructions. */
public final class GroupBridgeLockOrderTest {
    private static final String SECRET="0123456789abcdef0123456789abcdef";
    @Test public void concurrentAuthenticatedPinAndOutboxAuthorityDoNotDeadlockOrBlockDm()throws Exception {
        File directory=Files.createTempDirectory("group-lock-regression-").toFile();ChannelCore core=new ChannelCore(directory);
        JSONObject profile=profile(core);GroupChannelBridge bridge=new GroupChannelBridge(core,new ChannelHttp(),id->SECRET,directory);
        JSONObject first=core.accept("bot",event("first","group"),"trusted_relay").getJSONObject("event");acceptedWithoutHost(bridge,profile,first.getString("id"));
        GroupIngressPort.Event proof=bridge.authenticate(first.getString("id"));JSONObject second=core.accept("bot",event("second","group"),"trusted_relay").getJSONObject("event");
        CountDownLatch pinEntered=new CountDownLatch(1),coreEntered=new CountDownLatch(1),finished=new CountDownLatch(2);AtomicInteger reads=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        JSONObject scheduled=new JSONObject(profile.toString()){
            @Override public String getString(String key)throws JSONException {String result=super.getString(key);
                // accepted() reads epoch once before pin, then once at pin entry. Do not add a lock.
                if(key.equals("transportEpoch")&&reads.incrementAndGet()==2){pinEntered.countDown();try{if(!coreEntered.await(2,TimeUnit.SECONDS))throw new AssertionError("Core queue did not reach its real authority callback");}catch(InterruptedException stopped){throw new AssertionError(stopped);}}
                return result;
            }
        };
        Thread intake=new Thread(()->{try{acceptedWithoutHost(bridge,scheduled,second.getString("id"));}catch(Throwable bad){failure.compareAndSet(null,bad);}finally{finished.countDown();}},"native-group-intake");
        Thread delivery=new Thread(()->{try{if(!pinEntered.await(2,TimeUnit.SECONDS))throw new AssertionError("pin did not reach the existing epoch accessor");core.queueGroupOutput(UUID.randomUUID().toString(),first,"original actual result",()->{coreEntered.countDown();bridge.check(proof);});}catch(Throwable bad){failure.compareAndSet(null,bad);}finally{finished.countDown();}},"native-group-queue");
        intake.setDaemon(true);delivery.setDaemon(true);intake.start();delivery.start();boolean complete=finished.await(3,TimeUnit.SECONDS);
        long[] deadlocked=ManagementFactory.getThreadMXBean().findDeadlockedThreads();StringBuilder details=new StringBuilder();boolean ourDeadlock=false;
        if(deadlocked!=null)for(ThreadInfo info:ManagementFactory.getThreadMXBean().getThreadInfo(deadlocked,true,true))if(info.getThreadId()==intake.getId()||info.getThreadId()==delivery.getId()){ourDeadlock=true;details.append(info);}
        assertFalse("Native bridge/Core monitor inversion: "+details,ourDeadlock);assertTrue("Both actual native methods must return",complete);if(failure.get()!=null)throw new AssertionError("Native concurrent call failed",failure.get());
        bridge.authenticate(second.getString("id"));assertEquals(1,core.snapshot().getJSONArray("outbox").length());
        assertEquals("dm",core.accept("bot",event("owner-dm","dm"),"trusted_relay").getJSONObject("event").getString("kind"));bridge.close();
    }
    @Test public void credentialChangeDuringProofPreparationFailsFinalLiveCheck()throws Exception {
        File directory=Files.createTempDirectory("group-pin-revocation-").toFile();ChannelCore core=new ChannelCore(directory);JSONObject profile=profile(core);AtomicBoolean rotate=new AtomicBoolean(true);
        GroupChannelBridge bridge=new GroupChannelBridge(core,new ChannelHttp(),id->{if(rotate.getAndSet(false))core.prepareCredentialChange(id);return SECRET;},directory);
        JSONObject incoming=core.accept("bot",event("revoked-before-dispatch","group"),"trusted_relay").getJSONObject("event");
        assertThrows(SecurityException.class,()->bridge.accepted(profile,incoming.getString("id")));assertThrows(SecurityException.class,()->bridge.authenticate(incoming.getString("id")));assertEquals(0,core.snapshot().getJSONArray("outbox").length());bridge.close();
    }
    private static JSONObject profile(ChannelCore core)throws Exception {return core.saveProfile(new JSONObject().put("id","bot").put("name","Native bot").put("provider","webhook").put("enabled",true));}
    private static JSONObject event(String message,String kind)throws Exception {return new JSONObject().put("messageId",message).put("senderId","member").put("roomId",kind.equals("group")?"room":"").put("kind",kind).put("text","/task public answer").put("sentAt",System.currentTimeMillis());}
    private static void acceptedWithoutHost(GroupChannelBridge bridge,JSONObject profile,String event)throws Exception {try{bridge.accepted(profile,event);fail("This fixture has no task host");}catch(IllegalStateException expected){if(!expected.getMessage().contains("尚未挂载"))throw expected;}}
}
