package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real host JNI process ownership. This is not Android ABI or durable process reattachment. */
public final class ManagedProcessJobsLifecycleTest {
    private Path directory,active;private ManagedProcessJobs jobs;
    @Before public void create()throws Exception {
        directory=Files.createTempDirectory("c2-owned-process-");active=Files.createDirectory(directory.resolve("active"));Files.writeString(active.resolve("Main.java"),"class Main {}\n");
        jobs=new ManagedProcessJobs(directory.toFile(),new NativeProcessSupervisor());assertTrue("load the current host JNI library",jobs.available());
    }
    @After public void close()throws Exception {try{jobs.close();}finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
    private RuntimeInvocation call(String operation,String executable,JSONArray argv)throws Exception {return call(operation,executable,argv,false);}
    private RuntimeInvocation call(String operation,String executable,JSONArray argv,boolean service)throws Exception {
        JSONObject source=RuntimeContractsTest.source(),exact=RuntimeContractsTest.exact(source,"termux.run",argv);exact.getJSONObject("payload").put("executable",executable);if(service)exact.getJSONObject("payload").put("runMode","owned_service");exact.put("requestDigest",ProjectSources.digest(exact.getJSONObject("payload")));
        RunControl original=RuntimeContractsTest.original();return new RuntimeInvocation(exact,RuntimeContractsTest.binding(operation),source,original,()->{},new RuntimeWorkspace(active.toFile(),"project",RuntimeContractsTest.CHECKOUT));
    }
    private JSONObject finished(RuntimeInvocation invocation)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<until){JSONObject value=jobs.query(invocation.executionRef()).json();if(value.has("finishedAt")&&!value.optBoolean("ownedWorkerLive"))return value;Thread.sleep(5);}throw new AssertionError("owned process did not drain");
    }
    @Test public void ownerReconstructionNeverAttachesCancelsOrReplaysThePreviousProcess()throws Exception {
        RuntimeInvocation invocation=call("owned", "sh",new JSONArray().put("-c").put("printf 'ORIGINAL_LOG\\n'; exec sleep 60"));
        jobs.start(invocation,List.of("/bin/sh","-c","printf 'ORIGINAL_LOG\\n'; exec sleep 60"),Map.of("PATH","/usr/bin:/bin"),active.toFile(),5000,()->{});
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);JSONObject live=null;while(System.nanoTime()<until){live=jobs.query(invocation.executionRef()).json();if(live.optString("stdout").contains("ORIGINAL_LOG"))break;Thread.sleep(5);}
        assertNotNull(live);assertTrue(live.getBoolean("ownedWorkerLive"));assertFalse(live.getBoolean("receiptReceived"));
        try(ManagedProcessJobs reconstructed=new ManagedProcessJobs(directory.toFile(),new NativeProcessSupervisor())){
            JSONObject unknown=reconstructed.query(invocation.executionRef()).json();assertEquals("unknown",unknown.getString("status"));assertEquals("owner_process_restarted_no_replay",unknown.getString("observation"));assertFalse(unknown.getBoolean("ownedWorkerLive"));assertTrue(unknown.getString("stdout").contains("ORIGINAL_LOG"));
            JSONObject cancel=reconstructed.requestCancel(invocation.executionRef()).json();assertEquals("no_owned_live_process",cancel.getString("cancelDelivery"));assertFalse(cancel.getBoolean("resourcesReleased"));assertTrue("the original owner still supervises its same process",jobs.query(invocation.executionRef()).json().getBoolean("ownedWorkerLive"));
        }
        jobs.requestCancel(invocation.executionRef());JSONObject stopped=finished(invocation);assertTrue(stopped.getBoolean("cancelRequested"));assertTrue(stopped.getBoolean("processExited"));assertTrue(stopped.getBoolean("processGroupAbsent"));assertTrue(stopped.getBoolean("resourcesReleased"));assertEquals("failed",stopped.getString("status"));
    }
    @Test public void nativeDeadlineStopsAndDrainsWithoutReportingBusinessSuccess()throws Exception {
        RuntimeInvocation invocation=call("deadline","sh",new JSONArray().put("-c").put("exec sleep 60"));
        jobs.start(invocation,List.of("/bin/sh","-c","exec sleep 60"),Map.of("PATH","/usr/bin:/bin"),active.toFile(),120,()->{});
        JSONObject actual=finished(invocation);assertTrue(actual.getBoolean("timedOut"));assertTrue(actual.getBoolean("cancelRequested"));assertTrue(actual.getBoolean("processExited"));assertTrue(actual.getBoolean("processGroupAbsent"));assertTrue(actual.getBoolean("resourcesReleased"));assertEquals(120,actual.getInt("timeoutMillis"));assertEquals("failed",actual.getString("status"));
    }
    @Test public void summaryTruncationIsExplicitAndTheOriginalByteCursorStillReadsTheWholeLog()throws Exception {
        String python=Path.of(System.getProperty("a25.python")).toRealPath().toString(),script="import os;os.write(1,b'x'*70000);os.write(2,b'actual-last-error')";
        RuntimeInvocation invocation=call("log-pages","python3",new JSONArray().put("-c").put(script));jobs.start(invocation,List.of(python,"-c",script),Map.of(),active.toFile(),5000,()->{});
        JSONObject actual=finished(invocation);assertTrue(actual.getBoolean("summaryOutputTruncated"));assertFalse(actual.getBoolean("outputTruncated"));assertEquals("completed",actual.getString("status"));
        JSONObject page=jobs.readLog(invocation.executionRef(),0,262144);JSONArray chunks=page.getJSONArray("chunks");int stdout=0;java.io.ByteArrayOutputStream stderr=new java.io.ByteArrayOutputStream();for(int i=0;i<chunks.length();i++){JSONObject chunk=chunks.getJSONObject(i);byte[] bytes=Base64.getDecoder().decode(chunk.getString("dataBase64"));if(chunk.getInt("stream")==1)stdout+=bytes.length;else stderr.write(bytes);}
        assertEquals(70000,stdout);assertEquals("actual-last-error",stderr.toString(java.nio.charset.StandardCharsets.UTF_8));assertFalse(page.getBoolean("hasMore"));assertEquals(0,jobs.readLog(invocation.executionRef(),page.getLong("nextCursor"),8192).getJSONArray("chunks").length());
    }
    @Test public void ownedServiceAckRetainsOriginalLeaseAndLostOwnerCannotBeAdmittedAsKnownRunning()throws Exception {
        var ack=new java.util.concurrent.CountDownLatch(1);var released=new java.util.concurrent.atomic.AtomicBoolean();var starts=new java.util.concurrent.atomic.AtomicInteger();
        jobs.setReceiptSink(value->{if(value.optBoolean("nativeStartObserved")&&!value.optBoolean("resourcesReleased")){starts.incrementAndGet();ack.countDown();}});
        RuntimeInvocation invocation=call("owned-service", "sh",new JSONArray().put("-c").put("exec sleep 60"),true);
        jobs.start(invocation,List.of("/bin/sh","-c","exec sleep 60"),Map.of("PATH","/usr/bin:/bin"),active.toFile(),5000,()->released.set(true));assertTrue(ack.await(3,TimeUnit.SECONDS));
        JSONObject running=jobs.query(invocation.executionRef()).json();assertEquals("running",running.getString("status"));assertEquals(1,starts.get());assertEquals("",running.getString("stdout"));assertFalse(released.get());assertFalse(running.getBoolean("resourcesReleased"));assertFalse(running.getBoolean("receiptReceived"));assertTrue(running.getString("launchAckIdentity").matches("[a-f0-9]{64}"));jobs.requireOwnedOrReleased(invocation.executionRef());assertThrows(IllegalStateException.class,()->jobs.requireCheckoutWritable(invocation.executionRef().checkoutId()));
        try(ManagedProcessJobs reconstructed=new ManagedProcessJobs(directory.toFile(),new NativeProcessSupervisor())){JSONObject unknown=reconstructed.query(invocation.executionRef()).json();assertEquals("unknown",unknown.getString("status"));assertFalse(unknown.getBoolean("ownedWorkerLive"));assertThrows(SecurityException.class,()->reconstructed.requireOwnedOrReleased(invocation.executionRef()));}
        jobs.requestCancel(invocation.executionRef());JSONObject terminal=finished(invocation);assertTrue(released.get());assertFalse(terminal.getBoolean("ownedWorkerLive"));assertTrue(terminal.getBoolean("resourcesReleased"));jobs.requireOwnedOrReleased(invocation.executionRef());jobs.requireCheckoutWritable(invocation.executionRef().checkoutId());
    }

    @Test public void ownedServiceNativeExitWithFailedLeaseCloseIsUnknownAndStillHoldsCheckout()throws Exception {
        var ack=new java.util.concurrent.CountDownLatch(1);jobs.setReceiptSink(value->{if(value.optBoolean("nativeStartObserved")&&!value.optBoolean("receiptReceived"))ack.countDown();});
        RuntimeInvocation invocation=call("service-close-unknown","sh",new JSONArray().put("-c").put("exec sleep 60"),true);jobs.start(invocation,List.of("/bin/sh","-c","exec sleep 60"),Map.of("PATH","/usr/bin:/bin"),active.toFile(),5000,()->{throw new java.io.IOException("controlled owned-service lease close failure");});assertTrue(ack.await(3,TimeUnit.SECONDS));jobs.requestCancel(invocation.executionRef());JSONObject actual=finished(invocation);assertEquals("unknown",actual.getString("status"));assertTrue(actual.getBoolean("processExited"));assertTrue(actual.getBoolean("processGroupAbsent"));assertFalse(actual.getBoolean("resourcesReleased"));assertThrows(SecurityException.class,()->jobs.requireOwnedOrReleased(invocation.executionRef()));assertThrows(IllegalStateException.class,()->jobs.requireCheckoutWritable(invocation.executionRef().checkoutId()));
    }

}
