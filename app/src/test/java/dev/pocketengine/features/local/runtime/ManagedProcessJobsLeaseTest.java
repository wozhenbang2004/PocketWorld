package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Every pre-worker failure must release its caller lease once, outside the jobs monitor. */
public final class ManagedProcessJobsLeaseTest {
    private Path directory;private ManagedProcessJobs jobs;private boolean releaseUnconfirmed;
    @Before public void create() throws Exception {
        directory=Files.createTempDirectory("a25f1-lease-exits-");jobs=new ManagedProcessJobs(directory.toFile(),new NativeProcessSupervisor());
    }
    @After public void clean() throws Exception {
        if(releaseUnconfirmed)assertEquals("runtime_process_drain_unconfirmed",assertThrows(IllegalStateException.class,jobs::close).getMessage());else jobs.close();
        try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private RuntimeInvocation invocation(String operation,ProjectRuntime.LiveGate gate) throws Exception {
        JSONObject source=RuntimeContractsTest.source();
        return new RuntimeInvocation(RuntimeContractsTest.exact(source,"termux.prepare",null),RuntimeContractsTest.binding(operation),
                source,RuntimeContractsTest.original(),gate);
    }
    private AutoCloseable lease(AtomicInteger count) {
        return ()->{assertFalse("lease release entered environment under jobs monitor",Thread.holdsLock(jobs));count.incrementAndGet();};
    }
    @Test public void revokedNativeGateClosesTheLeaseOutsideTheMonitor() throws Exception {
        AtomicBoolean live=new AtomicBoolean(true);AtomicInteger closed=new AtomicInteger();
        RuntimeInvocation call=invocation("revoked",()->{assertFalse(Thread.holdsLock(jobs));if(!live.get())throw new SecurityException("revoked");});
        live.set(false);
        assertThrows(SecurityException.class,()->jobs.start(call,List.of("/bin/sh"),Map.of(),directory.toFile(),5000,lease(closed)));
        assertEquals(1,closed.get());assertFalse(Files.exists(directory.resolve("jobs/revoked/receipt.json")));
    }
    @Test public void invalidCachedReceiptAndClosedRuntimeAlsoCloseExactlyOnce() throws Exception {
        RuntimeInvocation cached=invocation("corrupt",()->{});Path record=directory.resolve("jobs/corrupt/receipt.json");
        Files.createDirectories(record.getParent());Files.writeString(record,"{\"invalid\":true}");AtomicInteger corruptClosed=new AtomicInteger();
        assertThrows(Exception.class,()->jobs.start(cached,List.of("/bin/sh"),Map.of(),directory.toFile(),5000,lease(corruptClosed)));
        assertEquals(1,corruptClosed.get());assertEquals("{\"invalid\":true}",Files.readString(record));
        jobs.close();AtomicInteger shutdownClosed=new AtomicInteger();RuntimeInvocation stopped=invocation("closed",()->{});
        assertThrows(IllegalStateException.class,()->jobs.start(stopped,List.of("/bin/sh"),Map.of(),directory.toFile(),5000,lease(shutdownClosed)));
        assertEquals(1,shutdownClosed.get());
    }
    @Test public void observedWorkSuccessCannotSettleWhenItsLeaseReleaseFailed() throws Exception {
        Path active=Files.createDirectory(directory.resolve("active"));Files.writeString(active.resolve("Main.java"),"class Main {}\n");
        JSONObject source=RuntimeContractsTest.source();RuntimeInvocation call=new RuntimeInvocation(RuntimeContractsTest.exact(source,"termux.prepare",null),RuntimeContractsTest.binding("release-failed"),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(active.toFile(),"project",RuntimeContractsTest.CHECKOUT));
        releaseUnconfirmed=true; jobs.startPreparation(call,"environment",context->new JSONObject(),()->{throw new java.io.IOException("controlled release failure");});
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);RuntimeReceipt actual=null;
        while(System.nanoTime()<end){actual=jobs.query(call.executionRef());if(actual.json().has("finishedAt"))break;Thread.sleep(5);}
        assertNotNull(actual);assertEquals("unknown",actual.status());assertFalse(actual.terminal());assertFalse(actual.json().getBoolean("resourcesReleased"));assertTrue(actual.json().getBoolean("leaseError"));assertEquals("runtime_resource_release_unconfirmed",actual.json().getString("error"));
    }
    @Test public void commandAndPreparationStayNonterminalThroughoutBlockedLeaseClose() throws Exception {
        Path active=Files.createDirectory(directory.resolve("active"));Files.writeString(active.resolve("Main.java"),"class Main {}\n");assertTrue("current host JNI is required for the command half",jobs.available());
        AtomicInteger callbacks=new AtomicInteger();jobs.setReceiptSink(receipt->callbacks.incrementAndGet());
        for(boolean command:new boolean[]{false,true})for(boolean fail:new boolean[]{false,true}){
            String id=(command?"command":"preparation")+(fail?"-failure":"-success");JSONObject source=RuntimeContractsTest.source();
            JSONObject exact=RuntimeContractsTest.exact(source,command?"termux.run":"termux.prepare",command?new org.json.JSONArray().put("-c").put("printf 'real exit before lease close\\n'"):null);
            RuntimeInvocation call=new RuntimeInvocation(exact,RuntimeContractsTest.binding(id),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(active.toFile(),"project",RuntimeContractsTest.CHECKOUT));
            var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);AtomicInteger closed=new AtomicInteger();int previousCallbacks=callbacks.get();
            AutoCloseable lease=()->{closed.incrementAndGet();entered.countDown();if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("blocked close gate timeout");if(fail)throw new java.io.IOException("controlled lease close failure");};
            try{
                if(command)jobs.start(call,List.of("/bin/sh","-c","printf 'real exit before lease close\\n'"),Map.of(),active.toFile(),3000,lease);
                else jobs.startPreparation(call,"controlled-environment",context->new JSONObject(),lease);
                assertTrue(id,entered.await(2,java.util.concurrent.TimeUnit.SECONDS));RuntimeReceipt pending=jobs.query(call.executionRef());
                assertEquals(id,"awaiting_resource_release",pending.status());assertFalse(id,pending.terminal());assertFalse(id,pending.json().getBoolean("resourcesReleased"));assertFalse(id,pending.json().has("finishedAt"));assertEquals(previousCallbacks,callbacks.get());
                release.countDown();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);RuntimeReceipt actual=null;
                while(System.nanoTime()<until){actual=jobs.query(call.executionRef());if(actual.json().has("finishedAt")&&callbacks.get()==previousCallbacks+1)break;Thread.sleep(5);}
                assertNotNull(actual);assertEquals(1,closed.get());assertEquals(previousCallbacks+1,callbacks.get());assertEquals(id,fail?"unknown":"completed",actual.status());assertEquals(!fail,actual.terminal());assertEquals(!fail,actual.json().getBoolean("resourcesReleased"));
                if(fail){releaseUnconfirmed=true;assertEquals("runtime_resource_release_unconfirmed",actual.json().getString("error"));}
            }finally{release.countDown();}
        }
    }
}
