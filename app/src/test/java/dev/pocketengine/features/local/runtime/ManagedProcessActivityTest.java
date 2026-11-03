package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import dev.pocketengine.features.local.project.ProjectCommandSpec;
import dev.pocketengine.features.local.project.ProjectSources;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;

/** Actual original JNI jobs. Silence is an observation, never proof of an input request or success. */
public final class ManagedProcessActivityTest {
    private Path root,active;private ManagedProcessJobs jobs;private RuntimeInvocation original;
    @Before public void create()throws Exception {
        root=Files.createTempDirectory("pw-original-activity-");active=Files.createDirectory(root.resolve("active"));
        Files.write(active.resolve("Main.java"),"class Main {}\n".getBytes(StandardCharsets.UTF_8));
        jobs=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor());assertTrue(jobs.available());
        jobs.setProcessInputSink(receipt->{});
    }
    @After public void close()throws Exception {
        try {if(original!=null)jobs.requestCancel(original.executionRef());jobs.close();}
        finally {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
    private void start(String program,boolean pty,AutoCloseable lease)throws Exception {
        JSONObject source=RuntimeContractsTest.source(),payload;
        List<String> command;
        if(pty){String python=Path.of(Objects.requireNonNull(System.getProperty("a25.python"))).toRealPath().toString();
            command=List.of(python,"-u","-c",program);
            payload=ProjectCommandSpec.normalize(new JSONObject().put("executable","python3").put("argv",new JSONArray().put("-u").put("-c").put(program)).put("runMode","owned_service").put("terminal","pty").put("inputByteLimit",1024)).put("genericCommandVersion",1).put("terminalNativeContract",NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT);
        }else {command=List.of("/bin/sh","-c",program);payload=new JSONObject().put("executable","sh").put("argv",new JSONArray().put("-c").put(program)).put("runMode","owned_service");}
        payload.put("checkoutId",RuntimeContractsTest.CHECKOUT).put("expectedSourceDigest",ProjectSources.describe(source).getString("sourceDigest"));
        JSONObject exact=RuntimeContractsTest.exact(source,"termux.run",payload.getJSONArray("argv")).put("intent",pty?"command":"build").put("payload",payload).put("requestDigest",ProjectSources.digest(payload));
        original=new RuntimeInvocation(exact,RuntimeContractsTest.binding("observed-original"),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(active.toFile(),"project",RuntimeContractsTest.CHECKOUT));
        jobs.start(original,command,Map.of("PATH","/usr/bin:/bin","LANG","C.UTF-8"),active.toFile(),20000,lease);
    }
    private JSONObject observed()throws Exception{return jobs.query(original.executionRef()).json();}
    private JSONObject until(java.util.function.Predicate<JSONObject> condition)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);JSONObject actual=null;
        do {actual=observed();if(condition.test(actual))return actual;Thread.sleep(10);}while(System.nanoTime()<end);
        throw new AssertionError("original activity did not match: "+actual);
    }
    private static JSONObject activity(JSONObject receipt){return receipt.optJSONObject("activity");}
    @Test public void realSilenceIsInferredIdleNotInputReadinessAndByteAckIsNotConsumption()throws Exception {
        start("import sys,time\nprint('READY',flush=True)\nline=sys.stdin.readline()\nprint('GOT:'+line.rstrip(),flush=True)\ntime.sleep(60)\n",true,()->{});
        JSONObject output=until(r->r.optString("stdout").contains("READY"));
        assertNotNull("Original query must expose body-free activity evidence",activity(output));
        assertEquals("active_output",activity(output).getString("state"));
        JSONObject idle=until(r->activity(r)!=null&&"inferred_idle".equals(activity(r).optString("state")));
        assertTrue(activity(idle).getBoolean("inferred"));assertFalse(activity(idle).getBoolean("processTerminal"));
        assertFalse(activity(idle).getBoolean("businessVerified"));assertEquals("unknown",activity(idle).getString("waitingForInput"));
        assertEquals("none",activity(idle).getJSONObject("input").getString("state"));
        JSONObject ack=jobs.processInput(original.executionRef(),original.originalControl(),"original-line",new JSONObject().put("action","text").put("text","own input\n"),()->{});
        assertEquals("delivered",ack.getString("status"));
        JSONObject next=until(r->r.optString("stdout").contains("GOT:own input"));
        assertEquals("active_output",activity(next).getString("state"));
        JSONObject input=activity(next).getJSONObject("input");assertEquals("delivered",input.getString("state"));
        assertEquals("original-line",input.getString("operationId"));assertFalse(input.getBoolean("applicationConsumptionProven"));
        assertFalse(activity(next).toString().contains("own input"));assertFalse(activity(next).getBoolean("replayAllowed"));
        jobs.requestCancel(original.executionRef());JSONObject exit=until(r->r.optBoolean("resourcesReleased"));
        assertEquals("exited",activity(exit).getString("state"));assertTrue(activity(exit).getBoolean("processTerminal"));
        assertFalse(activity(exit).getBoolean("businessVerified"));
    }
    @Test public void coldReadCannotReconstructLiveIdleOrInputReadinessFromRetainedLog()throws Exception {
        start("printf 'RETAINED_LOG\\n'; exec sleep 60",false,()->{});
        until(r->r.optString("stdout").contains("RETAINED_LOG"));
        try(ManagedProcessJobs cold=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor())){
            JSONObject unknown=cold.query(original.executionRef()).json();assertEquals("unknown",unknown.getString("status"));
            assertNotNull(activity(unknown));assertEquals("observation_unknown",activity(unknown).getString("state"));
            assertTrue(activity(unknown).isNull("silenceMillis"));assertEquals("unknown",activity(unknown).getString("waitingForInput"));
            assertFalse(activity(unknown).getBoolean("processTerminal"));
            cold.requestCancel(original.executionRef());assertTrue(observed().getBoolean("ownedWorkerLive"));
        }
    }
    @Test public void nativeExitBeforeLeaseDrainIsNeverAnExitedActivity()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean closed=new AtomicBoolean();
        try {
            start("printf 'real exit\\n'",false,()->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("owned fixture release pending");closed.set(true);});
            assertTrue(entered.await(4,TimeUnit.SECONDS));JSONObject pending=observed();
            assertTrue(pending.getBoolean("processExited"));assertFalse(pending.getBoolean("resourcesReleased"));
            assertNotNull(activity(pending));assertEquals("awaiting_resource_release",activity(pending).getString("state"));
            assertFalse(activity(pending).getBoolean("processTerminal"));assertFalse(closed.get());
        }finally {release.countDown();}
        JSONObject done=until(r->r.optBoolean("resourcesReleased"));assertEquals("exited",activity(done).getString("state"));
        assertTrue(activity(done).getBoolean("processTerminal"));assertTrue(closed.get());
    }
}
