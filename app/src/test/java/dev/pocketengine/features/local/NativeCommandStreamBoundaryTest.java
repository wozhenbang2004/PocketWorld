package dev.pocketengine.features.local;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.json.*;
import org.junit.Test;
import dev.pocketengine.features.local.runtime.NativeProcessSupervisor;

/** Only this fixture's short self-ending processes/files. No PID lookup, global
 * process cleanup, signal to an unknown child or inferred native group proof. */
public final class NativeCommandStreamBoundaryTest {
    private static void clean(Path root)throws Exception{try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    @Test(timeout=12000) public void anOriginalChildHoldingInheritedOutputCannotBecomeCompletedOrReleaseItsCheckoutAtParentExit()throws Exception {
        Path root=Files.createTempDirectory("legacy-owned-stream-child-"),cwd=Files.createDirectory(root.resolve("project")),tools=Files.createDirectory(root.resolve("tools"));Path finished=cwd.resolve("owned-child-finished");
        String script="#!/bin/sh\n( printf 'own child started' > owned-child-started; /bin/sleep 4; printf 'own child finished' > owned-child-finished; printf 'tail-after-parent\\n' ) &\nwhile [ ! -f owned-child-started ]; do :; done\nprintf 'parent returned\\n'\nexit 0\n";
        Path tool=Files.write(tools.resolve("pwd"),script.getBytes(StandardCharsets.UTF_8));assertTrue(tool.toFile().setExecutable(true));JSONObject observed=null;boolean beforeChildFinished=false;long fixtureEnd=System.nanoTime()+5_000_000_000L;
        try(var commands=new NativeCommandExecutor(root.resolve("receipts").toFile(),List.of(tools.toFile()),event->{})){
            commands.submit("own-checkout",cwd.toFile(),new JSONArray().put("pwd"),"own-command",8);long until=System.nanoTime()+8_000_000_000L;
            while(System.nanoTime()<until){JSONObject current=commands.get("own-command");if(!List.of("queued","running","stopping").contains(current.optString("status"))){observed=current;beforeChildFinished=!Files.exists(finished);break;}Thread.sleep(10);}
            assertNotNull("original waiter never returned its observed receipt",observed);
            // The pre-fix Java waiter closes its pipes at parent exit, so even
            // EOF/outputComplete=true was not proof the child had stopped.
            while(System.nanoTime()<fixtureEnd)Thread.sleep(10);
            System.out.println("owned inherited-stream case firstStatus="+observed.optString("status")+" outputComplete="+observed.optBoolean("outputComplete")+" groupAbsent="+observed.optBoolean("processGroupAbsent")+" lateChildMarker="+Files.exists(finished));
            assertTrue("case must observe parent result before the child finished",beforeChildFinished);
            assertTrue("parent exit/EOF must not substitute for an original native result",NativeProcessSupervisor.knownOrdinaryExitContract(observed.optString("nativeResultContract")));
            boolean nativeClean=observed.getBoolean("processGroupAbsent")&&observed.getBoolean("outputComplete");assertEquals(nativeClean,observed.getBoolean("resourcesReleased"));
            if(nativeClean){assertEquals("completed",observed.getString("status"));assertFalse(commands.busy("own-checkout"));}else{assertEquals("unknown",observed.getString("status"));assertTrue("unconfirmed original group cannot release the checkout",commands.busy("own-checkout"));}
            assertFalse("the original native group cleanup must stop this own late writer, not just close a view/pipe",Files.exists(finished));
        }finally{
            // With native cleanup it may be terminated; without it this exact
            // own child self-ends after4s. Never find/kill a PID during teardown.
            while(System.nanoTime()<fixtureEnd)Thread.sleep(10);clean(root);
        }
    }
    @Test(timeout=12000) public void actualNativeUnicodeOutputPreservesSeparateLegacyCharacterCapsWithoutDroppingTheGroupProof()throws Exception {
        Path root=Files.createTempDirectory("legacy-native-unicode-cap-"),cwd=Files.createDirectory(root.resolve("project")),tools=Files.createDirectory(root.resolve("tools"));String python=System.getProperty("a25.python","/usr/bin/python3");assertTrue(new File(python).isFile());
        String script="#!/bin/sh\nexec '"+python.replace("'","'\\''")+"' -c \"import sys; sys.stdout.write('你'*70000); sys.stderr.write('错'*70000)\"\n";Path tool=Files.write(tools.resolve("pwd"),script.getBytes(StandardCharsets.UTF_8));assertTrue(tool.toFile().setExecutable(true));
        try(var commands=new NativeCommandExecutor(root.resolve("receipts").toFile(),List.of(tools.toFile()),event->{})){
            commands.submit("own-checkout",cwd.toFile(),new JSONArray().put("pwd"),"unicode-output",8);JSONObject job=null;long until=System.nanoTime()+10_000_000_000L;while(System.nanoTime()<until){job=commands.get("unicode-output");if(!List.of("queued","running","stopping").contains(job.optString("status")))break;Thread.sleep(10);}assertNotNull(job);assertEquals("completed",job.getString("status"));assertEquals("你".repeat(65536),job.getString("stdout"));assertEquals("错".repeat(65536),job.getString("stderr"));assertEquals(131072,job.getString("output").length());assertTrue(job.getBoolean("stdoutTruncated"));assertTrue(job.getBoolean("stderrTruncated"));assertTrue(job.getBoolean("outputTruncated"));assertTrue(job.getBoolean("processGroupAbsent"));assertTrue(job.getBoolean("outputComplete"));assertTrue(job.getBoolean("resourcesReleased"));assertTrue(NativeProcessSupervisor.knownOrdinaryExitContract(job.getString("nativeResultContract")));assertFalse(job.getBoolean("verified"));
        }finally{clean(root);}
    }
}
