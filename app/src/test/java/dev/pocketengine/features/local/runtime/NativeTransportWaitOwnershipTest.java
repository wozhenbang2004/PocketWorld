package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;

/** Real Linux JNI, private owner lifetimes and competing App waitpid. No PID reuse or Android-device claim. */
public final class NativeTransportWaitOwnershipTest {
    private static JSONObject fixture(String mode,int phase)throws Exception {
        String cp=System.getProperty("pocketworldHostClasspath",System.getProperty("java.class.path"));
        List<String> entries=Arrays.asList(cp.split(java.util.regex.Pattern.quote(File.pathSeparator)));
        entries.sort(Comparator.comparingInt(path->new File(path).getName().matches("json-[0-9].*\\.jar")?0:1));
        String library=Objects.requireNonNull(System.getProperty("pocketworldHostTransportLostWaitDir"),"isolated exact-child reaper JNI required");
        Path work=Files.createTempDirectory("native-transport-lost-wait-");
        Process process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),"-Xmx96m","-XX:ActiveProcessorCount=2",
                "-Djava.library.path="+library,"-cp",String.join(File.pathSeparator,entries),NativeTransportWaitOwnershipProbe.class.getName(),
                mode,Objects.requireNonNull(System.getProperty("a25.python")),work.toString(),Integer.toString(phase))
                .redirectErrorStream(true).redirectOutput(work.resolve("probe.log").toFile()).start();
        boolean finished;
        try{finished=process.waitFor(12,TimeUnit.SECONDS);}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}}
        assertTrue("Original test JVM did not settle; exact child cleanup unconfirmed; evidence="+work,finished);
        String output=Files.readString(work.resolve("probe.log"));assertEquals(output,0,process.exitValue());
        JSONObject result=null;for(String line:output.split("\\R"))if(line.startsWith("{"))result=new JSONObject(line);
        assertNotNull("Missing exact native facts: "+output,result);JSONObject facts=result.getJSONObject("facts"),receipt=result.getJSONObject("receipt");
        System.out.println("Exact original transport wait facts: "+result);
        assertTrue(facts.getInt("zombieObservations")>0);assertEquals(1,facts.getInt("actualReapCount"));
        assertTrue(facts.getLong("exactReapedPid")>1);assertEquals(1,facts.getInt("ownerReapCount"));
        assertEquals("Numeric signals after original command reap: "+result,0,facts.getInt("signalsAfterReap"));
        assertEquals("The App may only send private control requests: "+result,0,facts.getInt("appNumericSignals"));
        assertTrue(facts.getBoolean("inheritedFdClosed"));assertTrue(facts.getBoolean("controlFdClosed"));assertTrue(facts.getBoolean("appFdPreserved"));
        assertFalse(receipt.getBoolean("noChildCreated"));assertEquals(0,receipt.getInt("launchErrno"));
        assertEquals(mode.equals("pty")?NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT:mode.equals("pipes")?NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT:NativeProcessSupervisor.EXIT_RESULT_CONTRACT,receipt.getString("contract"));
        if(phase==1||phase==2||phase==4) {
            assertEquals(1,facts.getInt("faultTriggered"));assertEquals(-1,receipt.getInt("exitCode"));assertEquals(0,receipt.getInt("signal"));
            assertFalse(receipt.getBoolean("groupAbsent"));assertFalse(receipt.getBoolean("sessionAbsent"));assertFalse(receipt.getBoolean("outputComplete"));
            if(phase!=4){assertEquals(7<<8,facts.getInt("realWaitStatus"));assertTrue(facts.getInt("realEchildCount")>0);}
        } else {
            if(phase==3){assertEquals(7,receipt.getInt("exitCode"));assertEquals(0,receipt.getInt("signal"));}
            else {assertEquals(-1,receipt.getInt("exitCode"));assertTrue(receipt.getInt("signal")>0);}
            assertTrue(receipt.getBoolean("groupAbsent"));assertTrue(receipt.getBoolean("outputComplete"));
            assertEquals(!mode.equals("ordinary"),receipt.getBoolean("sessionAbsent"));assertEquals(0,facts.getInt("ownerWaitStatus"));
        }
        assertTrue("Original deadline must converge: "+result,result.getLong("elapsedMillis")<5000);
        return result;
    }
    @Test public void ptyOwnerDeathAfterFinalSuccessfulObservationCannotSignalTheReapedNumber()throws Exception {fixture("pty",1);}
    @Test public void pipesOwnerDeathAfterFinalSuccessfulObservationCannotSignalTheReapedNumber()throws Exception {fixture("pipes",1);}
    @Test public void ordinaryOwnerDeathAfterFinalSuccessfulObservationCannotSignalTheReapedNumber()throws Exception {fixture("ordinary",1);}
    @Test public void anAlreadyObservedExitDoesNotSurviveLostOwnerAtFinalCleanup()throws Exception {for(String mode:List.of("pty","pipes","ordinary"))fixture(mode,2);}
    @Test public void appReaperCannotConsumeEitherTheCommandOrItsPrivateOwner()throws Exception {
        for(String mode:List.of("pty","pipes","ordinary")) {JSONObject facts=fixture(mode,3).getJSONObject("facts");assertEquals(1,facts.getInt("appReapAttempts"));assertTrue(facts.getInt("realEchildCount")>=2);}
    }
    @Test public void brokenControlConnectionCleansOnlyItsOriginalChildAndRemainsUnknownToCaller()throws Exception {for(String mode:List.of("pty","pipes","ordinary"))fixture(mode,4);}
    @Test public void pidfdUnavailableStillRunsAndReapsThroughThePrivateCloneWaitDomain()throws Exception {
        for(String mode:List.of("pty","pipes","ordinary"))assertTrue(fixture(mode,5).getJSONObject("facts").getInt("pidfdProbes")>0);
    }
}
