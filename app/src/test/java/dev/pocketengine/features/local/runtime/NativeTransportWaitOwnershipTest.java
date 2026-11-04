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

/** Real Linux JNI and a second-thread exact-child waitpid. No real PID reuse or Android claim. */
public final class NativeTransportWaitOwnershipTest {
    private static void lostWait(String mode,int phase)throws Exception {
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
        assertTrue(facts.getLong("exactReapedPid")>1);assertEquals(7<<8,facts.getInt("realWaitStatus"));assertTrue(facts.getInt("realEchildCount")>0);
        assertEquals("Numeric signals after actual other-thread reap: "+result,0,facts.getInt("signalsAfterReap"));
        assertEquals(-1,receipt.getInt("exitCode"));assertEquals(0,receipt.getInt("signal"));assertEquals(0,receipt.getInt("launchErrno"));
        assertFalse(receipt.getBoolean("noChildCreated"));assertFalse(receipt.getBoolean("groupAbsent"));assertFalse(receipt.getBoolean("sessionAbsent"));assertFalse(receipt.getBoolean("outputComplete"));
        assertEquals(mode.equals("pty")?NativeProcessSupervisor.PTY_EXIT_RESULT_CONTRACT:NativeProcessSupervisor.PIPE_EXIT_RESULT_CONTRACT,receipt.getString("contract"));
        assertTrue("Lost ownership must converge without renewing the deadline: "+result,result.getLong("elapsedMillis")<5000);
    }
    @Test public void ptyLosesWaitBeforeFirstExitObservationWithoutSignallingTheReapedNumber()throws Exception {lostWait("pty",1);}
    @Test public void pipesLoseWaitBeforeFirstExitObservationWithoutSignallingTheReapedNumber()throws Exception {lostWait("pipes",1);}
    @Test public void ptyRechecksAnAlreadyObservedExitAtTheNextOrFinalCleanupScan()throws Exception {lostWait("pty",2);}
    @Test public void pipesRecheckAnAlreadyObservedExitAtTheNextOrFinalCleanupScan()throws Exception {lostWait("pipes",2);}
}
