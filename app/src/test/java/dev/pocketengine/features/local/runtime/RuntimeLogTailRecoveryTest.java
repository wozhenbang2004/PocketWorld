package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;

/** Original Job logger with actual private files; only this test's bytes are damaged.
 * Reconstructing a reader is not a real Android process-death/device claim. */
public final class RuntimeLogTailRecoveryTest {
    private Path root,log;private ManagedProcessJobs owner;private RuntimeInvocation original;private byte[] pristine;
    private final AtomicInteger released=new AtomicInteger();
    @Before public void createOriginalLog()throws Exception {
        root=Files.createTempDirectory("runtime-tail-recovery-");Path workspace=Files.createDirectory(root.resolve("workspace"));Files.write(workspace.resolve("Main.java"),"class Main {}\n".getBytes(StandardCharsets.UTF_8));
        JSONObject source=RuntimeContractsTest.source();original=new RuntimeInvocation(RuntimeContractsTest.exact(source,"termux.prepare",null),RuntimeContractsTest.binding("original-log"),source,RuntimeContractsTest.original(),()->{},new RuntimeWorkspace(workspace.toFile(),"project",RuntimeContractsTest.CHECKOUT));
        owner=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor());CountDownLatch written=new CountDownLatch(1);
        owner.startPreparation(original,"a".repeat(64),context->{context.output(1,"ORIGINAL_UTF8_正文\n".getBytes(StandardCharsets.UTF_8));context.output(2,"ORIGINAL_ERROR\n".getBytes(StandardCharsets.UTF_8));written.countDown();while(true){context.check();Thread.sleep(5);}},released::incrementAndGet);
        assertTrue(written.await(2,TimeUnit.SECONDS));log=root.resolve("jobs").resolve(original.executionRef().operationId()).resolve("output.bin");pristine=Files.readAllBytes(log);
    }
    @After public void settleOnlyThisFixture()throws Exception {
        // The production read must never repair/truncate bytes. Restore only our deliberate
        // test corruption so even the unmodified baseline can settle its own original Job.
        if(log!=null&&pristine!=null)Files.write(log,pristine);if(owner!=null)owner.close();
        if(root!=null)try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}
    }
    private void append(byte[] tail)throws Exception {try(OutputStream output=Files.newOutputStream(log,StandardOpenOption.APPEND)){output.write(tail);}}
    private static byte[] record(int stream,int length,byte[] body)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(bytes)){output.writeLong(3);output.writeByte(stream);output.writeInt(length);output.write(body);}return bytes.toByteArray();}
    private void recover(byte[] tail)throws Exception {
        append(tail);byte[] exactDamagedBytes=Files.readAllBytes(log);
        try(ManagedProcessJobs reader=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor())){
            JSONObject page=reader.readLog(original.executionRef(),0,8192);assertEquals(2,page.getJSONArray("chunks").length());assertEquals(2,page.getLong("nextCursor"));assertFalse(page.getBoolean("hasMore"));assertTrue(page.getBoolean("logIncompleteTail"));assertFalse(page.getBoolean("outputTruncated"));
            ByteArrayOutputStream prefix=new ByteArrayOutputStream();for(int i=0;i<2;i++)prefix.write(Base64.getDecoder().decode(page.getJSONArray("chunks").getJSONObject(i).getString("dataBase64")));assertEquals("ORIGINAL_UTF8_正文\nORIGINAL_ERROR\n",prefix.toString(StandardCharsets.UTF_8.name()));
            JSONObject unknown=reader.query(original.executionRef()).json();assertEquals("unknown",unknown.getString("status"));assertEquals("owner_process_restarted_no_replay",unknown.getString("observation"));assertTrue(unknown.getBoolean("logIncompleteTail"));assertTrue(unknown.getBoolean("summaryOutputTruncated"));assertFalse(unknown.getBoolean("receiptReceived"));assertFalse(unknown.getBoolean("processExited"));assertFalse(unknown.getBoolean("resourcesReleased"));assertFalse(unknown.getBoolean("ownedWorkerLive"));assertTrue(unknown.getString("stdout").contains("ORIGINAL_UTF8_正文"));assertTrue(unknown.getString("stderr").contains("ORIGINAL_ERROR"));
            var projection=dev.pocketengine.features.local.project.ProjectDevelopment.class.getDeclaredMethod("normalize",JSONObject.class,JSONObject.class,JSONObject.class,JSONObject.class,JSONObject.class);projection.setAccessible(true);JSONObject plan=original.exact(),step=new JSONObject().put("id",plan.getString("stepId")).put("localCommand","termux.prepare").put("payload",plan.getJSONObject("payload"));
            JSONObject projected=(JSONObject)projection.invoke(null,plan,step,unknown,original.binding(),null);assertTrue(projected.getBoolean("logIncompleteTail"));assertEquals("unknown",projected.getString("status"));assertFalse(projected.getBoolean("resourcesReleased"));assertFalse(projected.getBoolean("stopConfirmed"));assertFalse(projected.getBoolean("outputTruncated"));
            JSONObject next=reader.readLog(original.executionRef(),2,8192);assertEquals(0,next.getJSONArray("chunks").length());assertEquals(2,next.getLong("nextCursor"));assertTrue(next.getBoolean("logIncompleteTail"));assertFalse(next.getBoolean("hasMore"));
            JSONObject cancel=reader.requestCancel(original.executionRef()).json();assertEquals("no_owned_live_process",cancel.getString("cancelDelivery"));assertFalse(cancel.getBoolean("resourcesReleased"));assertThrows(IllegalStateException.class,()->reader.requireEnvironmentIdle("a".repeat(64)));assertEquals(0,released.get());assertFalse(original.originalControl().isCancelled());
        }
        assertArrayEquals("Read/query/stop observation must preserve every original tail byte",exactDamagedBytes,Files.readAllBytes(log));
    }
    @Test public void partialSequenceHeaderRetainsPrefixAndMarksIncompleteInsteadOfSilentEof()throws Exception {recover(new byte[]{0,0,0,0});}
    @Test public void missingStreamByteRetainsPrefixAndKeepsOriginalUnknownLease()throws Exception {recover(Arrays.copyOf(record(1,6,new byte[]{1,2,3}),8));}
    @Test public void partialLengthHeaderRetainsPrefixWithoutSearchingForAnotherRecord()throws Exception {recover(Arrays.copyOf(record(1,6,new byte[]{1,2,3}),11));}
    @Test public void incompleteBodyDoesNotBreakOriginalUnknownQueryOrMintExitProof()throws Exception {recover(record(1,6,new byte[]{1,2,3}));}
    @Test public void completeInvalidStreamOrLengthStillFailsClosedAndPreservesAllOriginalBytes()throws Exception {
        for(byte[] invalid:List.of(record(3,1,new byte[]{1}),record(1,9000,new byte[]{1}))){Files.write(log,pristine);append(invalid);byte[] actual=Files.readAllBytes(log);try(ManagedProcessJobs reader=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor())){assertThrows(IOException.class,()->reader.readLog(original.executionRef(),0,8192));assertThrows(IOException.class,()->reader.query(original.executionRef()));}assertArrayEquals(actual,Files.readAllBytes(log));}
    }
    @Test public void exactCompleteEndIsNotLabelledDamagedAndCursorDoesNotReplayAnyChunk()throws Exception {
        JSONObject page=owner.readLog(original.executionRef(),0,8192);assertFalse(page.getBoolean("logIncompleteTail"));assertEquals(2,page.getLong("nextCursor"));JSONObject end=owner.readLog(original.executionRef(),page.getLong("nextCursor"),8192);assertEquals(0,end.getJSONArray("chunks").length());assertFalse(end.getBoolean("logIncompleteTail"));assertFalse(end.getBoolean("hasMore"));assertArrayEquals(pristine,Files.readAllBytes(log));
    }
    @Test public void noCompleteRecordIsAnExplicitDamagedObservationNotAnEmptySuccessfulExecution()throws Exception {
        byte[] onlyTail=new byte[]{0,0,0};Files.write(log,onlyTail);
        try(ManagedProcessJobs reader=new ManagedProcessJobs(root.toFile(),new NativeProcessSupervisor())){
            JSONObject page=reader.readLog(original.executionRef(),0,8192);assertTrue(page.getBoolean("logIncompleteTail"));assertEquals(0,page.getJSONArray("chunks").length());assertEquals(0,page.getLong("nextCursor"));
            JSONObject unknown=reader.query(original.executionRef()).json();assertEquals("unknown",unknown.getString("status"));assertTrue(unknown.getBoolean("logIncompleteTail"));assertTrue(unknown.getBoolean("summaryOutputTruncated"));assertFalse(unknown.getBoolean("receiptReceived"));assertFalse(unknown.getBoolean("resourcesReleased"));assertEquals("",unknown.getString("stdout"));assertEquals("",unknown.getString("stderr"));assertArrayEquals(onlyTail,Files.readAllBytes(log));
        }
    }
}
