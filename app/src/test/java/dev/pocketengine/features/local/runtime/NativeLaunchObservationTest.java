package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Real host JNI negative paths; never exhaust host descriptors/memory to force fork failure. */
public final class NativeLaunchObservationTest {
    private static NativeProcessSupervisor.Observer observer(boolean cancel){return new NativeProcessSupervisor.Observer(){public boolean cancelled(){return cancel;}public boolean output(int stream,byte[] bytes){return true;}};}
    @Test public void missingExecutableExits127ButEntryCancellationActuallyCreatesNoChild()throws Exception {
        Path root=Files.createTempDirectory("c2-native-launch-");try{
            NativeProcessSupervisor nativeProcess=new NativeProcessSupervisor();assertTrue(nativeProcess.available());
            var missing=nativeProcess.execute(List.of(root.resolve("certainly-missing-executable").toString()),Map.of(),root.toFile(),3000,100,observer(false));
            assertEquals(127,missing.exitCode);assertTrue(missing.launchErrno>0);assertEquals(0,missing.signal);assertTrue(missing.groupAbsent);assertTrue(missing.outputComplete);assertFalse(missing.noChildCreated);
            var cancelled=nativeProcess.execute(List.of("/bin/sh","-c","exit 73"),Map.of(),root.toFile(),3000,100,observer(true));
            assertEquals(-1,cancelled.exitCode);assertEquals(0,cancelled.signal);assertTrue(cancelled.launchErrno>0);assertTrue(cancelled.cancelled);assertFalse(cancelled.timedOut);assertTrue(cancelled.groupAbsent);assertTrue(cancelled.outputComplete);assertTrue(cancelled.noChildCreated);
        }finally{Files.delete(root);}
    }
    @Test public void oldUnknownOrIncompleteReceiptsNeverGainTheNativeNoChildFact()throws Exception {
        assertTrue(java.lang.reflect.Modifier.isPrivate(NativeProcessSupervisor.Exit.class.getDeclaredConstructors()[0].getModifiers()));
        JSONObject receipt=new JSONObject().put("nativeResultContract",NativeProcessSupervisor.EXIT_RESULT_CONTRACT).put("launchPhase","no_child_created").put("noChildCreated",true).put("processLaunched",false).put("processExited",false)
                .put("exitCode",-1).put("terminationSignal",0).put("launchErrno",2).put("timedOut",false).put("processGroupAbsent",true).put("outputComplete",true);
        assertTrue("validation is not original-operation authority",NativeProcessSupervisor.validatesNoChildReceipt(receipt));
        JSONObject exactPrevious=new JSONObject(receipt.toString()).put("nativeResultContract","pocketworld.posix-exit7-no-child-v1:cb2abfe8465e654402d1344d11345bb7f43047fecb3dd0220fed44e07ef0571f");assertTrue(NativeProcessSupervisor.validatesNoChildReceipt(exactPrevious));assertFalse(NativeProcessSupervisor.validatesPipeContract("pocketworld.posix-exit7-no-child-v1:unverified"));
        for(String key:new String[]{"nativeResultContract","launchPhase","noChildCreated","processLaunched","processExited","exitCode","terminationSignal","launchErrno","timedOut","processGroupAbsent","outputComplete"}){
            JSONObject missing=new JSONObject(receipt.toString());missing.remove(key);assertFalse(key,NativeProcessSupervisor.validatesNoChildReceipt(missing));
        }
        for(JSONObject wrong:new JSONObject[]{new JSONObject(receipt.toString()).put("nativeResultContract","unknown-native"),new JSONObject(receipt.toString()).put("exitCode",127),new JSONObject(receipt.toString()).put("terminationSignal",15),new JSONObject(receipt.toString()).put("launchErrno",0),new JSONObject(receipt.toString()).put("launchErrno","2"),new JSONObject(receipt.toString()).put("processExited",true),new JSONObject(receipt.toString()).put("timedOut",true),new JSONObject(receipt.toString()).put("noChildCreated","true")})assertFalse(NativeProcessSupervisor.validatesNoChildReceipt(wrong));
        JSONObject legacy=new JSONObject(receipt.toString());legacy.remove("nativeResultContract");legacy.remove("launchPhase");legacy.remove("noChildCreated");assertFalse(NativeProcessSupervisor.validatesNoChildReceipt(legacy));assertFalse(legacy.has("noChildCreated"));
        var constructor=NativeProcessSupervisor.Exit.class.getDeclaredConstructor(long[].class);constructor.setAccessible(true);
        for(long[] invalid:new long[][]{new long[6],new long[8],new long[]{-1,0,2,1,0,2,1},new long[]{-1,0,Long.MAX_VALUE,1,0,1,1}}){var thrown=assertThrows(java.lang.reflect.InvocationTargetException.class,()->constructor.newInstance((Object)invalid));assertTrue(thrown.getCause() instanceof IllegalStateException);}
    }
    @Test public void nativeStartHandshakeNeedsNoStdoutAndRejectedAckStopsItsOwnedGroup()throws Exception {
        Path root=Files.createTempDirectory("c2-native-start-ack-");try{
            var starts=new java.util.concurrent.atomic.AtomicInteger();var bytes=new java.util.concurrent.atomic.AtomicInteger();var process=new NativeProcessSupervisor();
            var actual=process.execute(List.of("/bin/sh","-c","exec sleep 60"),Map.of("PATH","/usr/bin:/bin"),root.toFile(),3000,100,new NativeProcessSupervisor.Observer(){public boolean started(){starts.incrementAndGet();return false;}public boolean cancelled(){return false;}public boolean output(int stream,byte[] data){bytes.addAndGet(data.length);return true;}});
            assertEquals(1,starts.get());assertEquals(0,bytes.get());assertTrue(actual.cancelled);assertTrue(actual.signal>0);assertTrue(actual.groupAbsent);assertFalse(actual.noChildCreated);
            var missing=process.execute(List.of(root.resolve("missing").toString()),Map.of(),root.toFile(),3000,100,new NativeProcessSupervisor.Observer(){public boolean started(){starts.incrementAndGet();return true;}public boolean cancelled(){return false;}public boolean output(int stream,byte[] data){return true;}});assertEquals(127,missing.exitCode);assertEquals("exec failure is not a native start ACK",1,starts.get());
        }finally{Files.delete(root);}
    }

}
