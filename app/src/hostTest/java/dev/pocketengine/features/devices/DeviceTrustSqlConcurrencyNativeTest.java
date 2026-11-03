package dev.pocketengine.features.devices;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original TaskStore database and Android connection pool, with ordered contention.
 * No TLS admission, model request, phone execution or physical-device result is fabricated.
 * The SQL-only rows below do not claim a native AuthenticatedPeer or Permission.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class DeviceTrustSqlConcurrencyNativeTest {
    private static final String PEER="pw1_"+"a".repeat(64),LOCAL="pw1_"+"b".repeat(64),GRANT="trust-concurrency-grant";
    private static final long NOW=2_000_000_000_000L;
    private static final long BARRIER_SECONDS=5;
    private TaskStore tasks;
    private SQLiteDatabase sql;
    private TaskStoreDeviceTrust trust;
    private final ThreadMXBean threads=ManagementFactory.getThreadMXBean();

    @Before public void openOriginalDatabase()throws Exception {
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");
        tasks=new TaskStore(app);Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);
        sql=(SQLiteDatabase)field.get(tasks);trust=tasks.deviceTrust();
        assertTrue("The host must expose actual monitor ownership for this deadlock regression",threads.isObjectMonitorUsageSupported());
        assertFalse("Exercise the original non-WAL connection boundary",sql.isWriteAheadLoggingEnabled());
        ContentValues peer=new ContentValues();peer.put("device_id",PEER);peer.put("certificate_der",new byte[]{1,2,3});
        peer.put("fingerprint","a".repeat(64));peer.put("instance_id","sql-fixture-instance");peer.put("authorization_epoch",1);peer.put("revoked",0);peer.put("updated_at",NOW);
        sql.insertOrThrow("device_peers",null,peer);
        JSONObject grant=new JSONObject().put("grantId",GRANT).put("peerDeviceId",PEER).put("localDeviceId",LOCAL)
            .put("authorizationEpoch",1).put("issuedAt",NOW).put("expiresAt",NOW+60_000)
            .put("capabilities",new JSONArray().put("task.acquireControl")).put("toolNames",new JSONArray())
            .put("resources",new JSONArray().put(new JSONObject().put("screenMode","capabilities")))
            .put("allowedOrigins",new JSONArray().put(PEER)).put("allowDelegation",false).put("maxDelegationDepth",0).put("toolBudget",0).put("modelBudget",0);
        ContentValues stored=new ContentValues();stored.put("grant_id",GRANT);stored.put("peer_device_id",PEER);stored.put("local_device_id",LOCAL);
        stored.put("authorization_epoch",1);stored.put("expires_at",NOW+60_000);stored.put("body_json",grant.toString());stored.put("revoked",0);
        sql.insertOrThrow("device_grants",null,stored);
        trust.putOutgoingGrant(PEER,GRANT,new JSONObject().put("sqlFixtureOnly",true).put("revision",1),NOW);
    }
    @After public void closeOriginalDatabase(){if(sql!=null&&sql.isOpen())sql.close();}

    @FunctionalInterface private interface SqlCall<T>{T run()throws Exception;}
    private static final class Pending<T> {
        final CountDownLatch entered=new CountDownLatch(1);
        final CompletableFuture<T> result=new CompletableFuture<>();
        final Thread thread;
        Pending(String name,SqlCall<T> call){
            thread=new Thread(()->{entered.countDown();try{result.complete(call.run());}catch(Throwable failed){result.completeExceptionally(failed);}},name);
            thread.setDaemon(true);thread.start();
        }
        T awaitCompletion()throws Exception {
            T value=result.get(BARRIER_SECONDS,TimeUnit.SECONDS);thread.join(TimeUnit.SECONDS.toMillis(BARRIER_SECONDS));
            assertFalse("The original SQL contender must exit after its transaction is released",thread.isAlive());return value;
        }
    }
    /** Wait for actual pool contention, never infer it from a fixed sleep or an unfinished future. */
    private ThreadInfo awaitOriginalConnectionWait(Pending<?> pending)throws Exception {
        assertTrue("SQL contender did not enter",pending.entered.await(BARRIER_SECONDS,TimeUnit.SECONDS));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(BARRIER_SECONDS);
        while(System.nanoTime()<end){
            if(pending.result.isDone()){pending.awaitCompletion();fail("Contender did not wait for the original transaction connection");}
            ThreadInfo info=threads.getThreadInfo(new long[]{pending.thread.getId()},true,true)[0];
            if(info!=null)for(StackTraceElement frame:info.getStackTrace()){
                // Robolectric instruments Android method names; keep the native owner class exact.
                if(frame.getClassName().equals("android.database.sqlite.SQLiteConnectionPool")&&frame.getMethodName().contains("waitForConnection"))return info;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Original SQLite connection wait barrier was not reached");
    }
    private boolean holdsTrustMonitor(ThreadInfo info){
        for(MonitorInfo monitor:info.getLockedMonitors())if(monitor.getIdentityHashCode()==System.identityHashCode(trust)
                &&monitor.getClassName().equals(TaskStoreDeviceTrust.class.getName()))return true;
        return false;
    }
    /** The assertion precedes the same-thread recheck, so the old implementation fails without
     * leaving an irreversible two-thread deadlock. The finally always releases the real SQL owner.
     */
    private JSONObject recheckWhileSameReadIsQueued(String label,SqlCall<JSONObject> read)throws Exception {
        Pending<JSONObject> queued=null;JSONObject inside=null;
        try{
            synchronized(tasks){sql.beginTransaction();try{
                assertTrue(sql.inTransaction());assertTrue(sql.isDbLockedByCurrentThread());
                queued=new Pending<>("native-trust-sql-"+label,read);ThreadInfo waiting=awaitOriginalConnectionWait(queued);
                assertFalse("Queued "+label+" read holds Trust while waiting for the original SQL connection needed by the transaction's current-authority recheck",holdsTrustMonitor(waiting));
                inside=read.run();assertNotNull(inside);sql.setTransactionSuccessful();
            }finally{sql.endTransaction();}}
        }finally{if(queued!=null){JSONObject after=queued.awaitCompletion();if(inside!=null)assertEquals(DeviceProtocol.canonical(inside),DeviceProtocol.canonical(after));}}
        return inside;
    }
    @Test public void peerRecheckUsesOriginalTransactionWhileAnotherPeerReadWaits()throws Exception {
        JSONObject current=recheckWhileSameReadIsQueued("peer",()->trust.peer(PEER));assertEquals(1,current.getLong("authorizationEpoch"));assertFalse(current.getBoolean("revoked"));
    }
    @Test public void grantRecheckUsesOriginalTransactionWhileAnotherGrantReadWaits()throws Exception {
        JSONObject current=recheckWhileSameReadIsQueued("grant",()->trust.grant(GRANT));assertEquals(1,current.getLong("authorizationEpoch"));assertFalse(current.getBoolean("revoked"));
    }
    @Test public void outgoingRecheckUsesOriginalTransactionAndReadsLatestCommittedReceipt()throws Exception {
        JSONObject first=recheckWhileSameReadIsQueued("outgoing",()->trust.outgoingGrant(PEER,GRANT));assertEquals(1,first.getInt("revision"));
        trust.putOutgoingGrant(PEER,GRANT,new JSONObject().put("sqlFixtureOnly",true).put("revision",2),NOW+1);
        assertEquals(2,trust.outgoingGrant(PEER,GRANT).getInt("revision"));assertEquals(1,first.getInt("revision"));
    }
    @Test public void queuedRevocationRemainsAtomicAndFreshReadsSeeItAfterOriginalCommit()throws Exception {
        // Also keeps this case bounded on the pre-fix tree: detect the real wait/monitor cycle
        // before asking its transaction owner to enter that monitor.
        recheckWhileSameReadIsQueued("peer-before-revoke",()->trust.peer(PEER));
        recheckWhileSameReadIsQueued("grant-before-revoke",()->trust.grant(GRANT));
        Pending<Long> revocation=null;
        try{
            synchronized(tasks){sql.beginTransaction();try{
                revocation=new Pending<>("native-trust-sql-revoke",()->trust.revoke(PEER,NOW+1));awaitOriginalConnectionWait(revocation);
                // revoke still owns its writer monitor and waits for SQL; current reads use this
                // original thread's transaction and cannot depend on that separate monitor.
                assertEquals(1,trust.peer(PEER).getLong("authorizationEpoch"));assertFalse(trust.peer(PEER).getBoolean("revoked"));
                assertEquals(1,trust.grant(GRANT).getLong("authorizationEpoch"));assertFalse(trust.grant(GRANT).getBoolean("revoked"));
                sql.setTransactionSuccessful();
            }finally{sql.endTransaction();}}
        }finally{if(revocation!=null)assertEquals(Long.valueOf(2),revocation.awaitCompletion());}
        JSONObject current=trust.peer(PEER);assertEquals(2,current.getLong("authorizationEpoch"));assertTrue(current.getBoolean("revoked"));
        assertTrue(trust.grant(GRANT).getBoolean("revoked"));assertFalse(trust.revocationStatus(PEER).getBoolean("peerAcknowledged"));
    }
}
