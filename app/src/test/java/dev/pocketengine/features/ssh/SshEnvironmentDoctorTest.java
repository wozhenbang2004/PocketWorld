package dev.pocketengine.features.ssh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Executes our fixed collector in a local POSIX shell; this is not an SSH fixture. */
public final class SshEnvironmentDoctorTest {
    private static final String SECRET="DO_NOT_RETAIN_CREDENTIAL_SENTINEL";
    private SshProfile profile(Path workspace)throws Exception {return new SshProfile(new JSONObject().put("id","doctor-test").put("name","fixture").put("host","unused.invalid").put("username","developer").put("workspace",workspace.toString()).put("authType","key"));}
    private JSONObject run(SshProfile profile,Path bin)throws Exception {
        ProcessBuilder builder=new ProcessBuilder("/bin/sh","-c",SshEnvironmentDoctor.script(profile));
        Map<String,String> env=builder.environment();env.clear();env.put("PATH",bin.toString());env.put("SHELL","/bin/bash");
        env.put("PRIVATE_TEST_SECRET",SECRET);env.put("NODE_OPTIONS",SECRET);env.put("JAVA_TOOL_OPTIONS",SECRET);env.put("VIRTUAL_ENV",SECRET);
        Process process=builder.start();
        try{byte[] bytes=process.getInputStream().readNBytes(SshEnvironmentDoctor.MAX_OUTPUT+1);assertTrue("collector finished",process.waitFor(5,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue());assertEquals("",new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
            assertTrue(bytes.length<=SshEnvironmentDoctor.MAX_OUTPUT);return raw(new String(bytes,StandardCharsets.UTF_8),"completed");
        }finally{process.destroyForcibly();}
    }
    private JSONObject raw(String stdout,String status)throws Exception {return new JSONObject().put("status",status).put("exitCode",0).put("accepted",true).put("stdout",stdout).put("stderr",SECRET);}
    private void tool(Path bin,String name,String output)throws Exception {
        Path target=bin.resolve(name);Files.writeString(target,"#!/bin/sh\nif [ -n \"${PRIVATE_TEST_SECRET-}${NODE_OPTIONS-}${JAVA_TOOL_OPTIONS-}\" ]; then echo '"+SECRET+"'; exit 1; fi\nprintf '%s\\n' "+SshProfile.quote(output)+"\n");assertTrue(target.toFile().setExecutable(true));
    }
    private void tools(Path bin)throws Exception {tool(bin,"git","git version 2.47.1\n"+SECRET);tool(bin,"java","openjdk version \"17.0.20\" 2026-07-21\n"+SECRET);tool(bin,"python3","Python 3.12.7\n"+SECRET);tool(bin,"node","v22.10.0\n"+SECRET);}
    private void remove(Path root)throws Exception {try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}

    @Test public void actualLocalCollectorReturnsTypedMetadataAndNoCredentialEnvironment()throws Exception {
        Path root=Files.createTempDirectory("ssh-doctor-local-");try{Path bin=Files.createDirectory(root.resolve("bin")),workspace=Files.createDirectory(root.resolve("work space ' 测试"));tools(bin);
            JSONObject result=SshEnvironmentDoctor.report(run(profile(workspace),bin),profile(workspace)),doctor=result.getJSONObject("doctor");
            assertEquals("completed",result.getString("status"));assertTrue(doctor.getBoolean("complete"));assertTrue(result.getBoolean("readOnly"));
            assertEquals("2.47.1",doctor.getJSONObject("tools").getJSONObject("git").getString("version"));assertEquals("17.0.20",doctor.getJSONObject("tools").getJSONObject("java").getString("version"));
            assertEquals("3.12.7",doctor.getJSONObject("tools").getJSONObject("python").getString("version"));assertEquals("22.10.0",doctor.getJSONObject("tools").getJSONObject("node").getString("version"));
            assertTrue(doctor.getJSONObject("workspace").getBoolean("exists"));assertTrue(doctor.getJSONObject("virtualEnvironment").getBoolean("venvPresent"));
            assertFalse(result.toString().contains(SECRET));assertFalse(result.has("stdout"));assertFalse(result.has("stderr"));assertEquals(0,Files.list(workspace).count());
        }finally{remove(root);}
    }
    @Test public void absentToolsProduceSuggestionsWithoutCreatingAnything()throws Exception {
        Path root=Files.createTempDirectory("ssh-doctor-absent-");try{Path bin=Files.createDirectory(root.resolve("bin")),missing=root.resolve("not-created");SshProfile profile=profile(missing);
            JSONObject result=SshEnvironmentDoctor.report(run(profile,bin),profile),doctor=result.getJSONObject("doctor");
            assertTrue(doctor.getBoolean("complete"));assertFalse(doctor.getJSONObject("workspace").getBoolean("exists"));assertFalse(Files.exists(missing));
            for(String tool:new String[]{"git","java","python","node"})assertEquals("unavailable",doctor.getJSONObject("tools").getJSONObject(tool).getString("status"));
            assertTrue(doctor.getJSONArray("suggestions").length()>=5);assertEquals(0,Files.list(bin).count());
        }finally{remove(root);}
    }
    @Test public void aFailedVersionProbeIsDistinctFromMissingBinary()throws Exception {
        Path root=Files.createTempDirectory("ssh-doctor-failure-");try{Path bin=Files.createDirectory(root.resolve("bin"));Path git=bin.resolve("git");Files.writeString(git,"#!/bin/sh\nprintf '%s\\n' '"+SECRET+"'\nexit 3\n");assertTrue(git.toFile().setExecutable(true));SshProfile profile=profile(root);
            JSONObject result=SshEnvironmentDoctor.report(run(profile,bin),profile);assertEquals("probe_failed",result.getJSONObject("doctor").getJSONObject("tools").getJSONObject("git").getString("status"));assertFalse(result.toString().contains(SECRET));
        }finally{remove(root);}
    }
    @Test public void oversizedToolOutputIsLimitedBeforeBecomingARecord()throws Exception {
        Path root=Files.createTempDirectory("ssh-doctor-limit-");try{Path bin=Files.createDirectory(root.resolve("bin"));Path git=bin.resolve("git");Files.writeString(git,"#!/bin/sh\nprintf '%05000d\\n' 1\n");assertTrue(git.toFile().setExecutable(true));SshProfile profile=profile(root);
            JSONObject result=SshEnvironmentDoctor.report(run(profile,bin),profile);assertEquals("probe_failed",result.getJSONObject("doctor").getJSONObject("tools").getJSONObject("git").getString("status"));assertTrue(result.toString().length()<10000);
        }finally{remove(root);}
    }
    @Test public void unframedBannerAndMalformedDuplicateFieldsNeverSurfaceRawOutput()throws Exception {
        SshProfile profile=profile(Path.of("/workspace"));
        for(String output:new String[]{SECRET,"banner\0PW_SSH_DOCTOR_V1\0","PW_SSH_DOCTOR_V1\0os\0available\0Linux\0os\0available\0"+SECRET+"\0","PW_SSH_DOCTOR_V1\0private_key\0available\0"+SECRET+"\0"}){
            JSONObject result=SshEnvironmentDoctor.report(raw(output,"completed"),profile);assertEquals("failed",result.getString("status"));assertFalse(result.toString().contains(SECRET));assertFalse(result.getJSONObject("doctor").getBoolean("complete"));
        }
    }
    @Test public void interruptedReportKeepsOnlyCompleteRecognizedFields()throws Exception {
        SshProfile profile=profile(Path.of("/workspace"));JSONObject result=SshEnvironmentDoctor.report(raw("PW_SSH_DOCTOR_V1\0os\0available\0Linux\0java_version\0available\0"+SECRET,"unknown"),profile);
        assertEquals("partial",result.getString("status"));assertEquals("Linux",result.getJSONObject("doctor").getJSONObject("system").getString("os"));assertFalse(result.toString().contains(SECRET));assertFalse(result.getJSONObject("doctor").getBoolean("complete"));
    }
    @Test public void suspiciousPathAndControlCharactersAreOmitted()throws Exception {
        SshProfile profile=profile(Path.of("/workspace"));String output="PW_SSH_DOCTOR_V1\0path\0available\0/usr/bin::.:relative:/private?token="+SECRET+":/bad\npath\0shell\0available\0/bad\n"+SECRET+"\0";
        JSONObject result=SshEnvironmentDoctor.report(raw(output,"unknown"),profile),paths=result.getJSONObject("doctor").getJSONObject("path");
        assertEquals(1,paths.getJSONArray("entries").length());assertEquals(3,paths.getInt("omitted"));assertTrue(paths.getBoolean("currentDirectorySearch"));assertFalse(result.toString().contains(SECRET));
    }
    @Test public void totalOversizeAndCancelledDiagnosticDoNotClaimCompletion()throws Exception {
        SshProfile profile=profile(Path.of("/workspace"));JSONObject result=SshEnvironmentDoctor.report(raw("x".repeat(SshEnvironmentDoctor.MAX_OUTPUT+1),"unknown").put("cancelled",true),profile);
        assertEquals("cancelled",result.getString("status"));assertFalse(result.getBoolean("persistReport"));assertFalse(result.getBoolean("remoteTerminationConfirmed"));assertFalse(result.getJSONObject("doctor").getBoolean("complete"));
    }
    @Test public void sameControlCancelledBeforeAuthenticationDoesNotReadInvalidCredentials()throws Exception {
        SshProfile profile=new SshProfile(profile(Path.of("/workspace")).document().put("pinnedKey",Base64.getEncoder().encodeToString("fixture-host-key-1111111111111111".getBytes(StandardCharsets.UTF_8))));
        dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();control.cancel();
        assertThrows(InterruptedException.class,()->DirectSshClient.connect(profile,new JSONObject().put("privateKey",SECRET),control));
    }
    @Test public void localConnectionCloseIsIdempotentAndUnregistersItsCancellationAction()throws Exception {
        SshProfile profile=profile(Path.of("/workspace"));dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();Runnable cancellation=calls::incrementAndGet;control.onCancel(cancellation);
        DirectSshClient.Connection connection=new DirectSshClient.Connection(profile,new com.jcraft.jsch.JSch().getSession("developer","unused.invalid",22),control,cancellation,new DirectSshClient.OwnedSocket(control));
        connection.close();connection.close();assertTrue(connection.localClosed());control.cancel();assertEquals(0,calls.get());
        assertThrows(InterruptedException.class,connection::doctor);
    }
    @Test public void ownedSocketClosesActualLoopbackConnectionAndRejectsLateCreation()throws Exception {
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){
            dev.pocketengine.core.RunControl control=new dev.pocketengine.core.RunControl();DirectSshClient.OwnedSocket owner=new DirectSshClient.OwnedSocket(control);
            java.net.Socket local=owner.createSocket("127.0.0.1",server.getLocalPort());try(java.net.Socket peer=server.accept()){
                assertFalse(local.isClosed());owner.close();assertTrue(local.isClosed());assertTrue(owner.localClosed());assertEquals(-1,peer.getInputStream().read());
                assertThrows(java.io.IOException.class,()->owner.createSocket("127.0.0.1",server.getLocalPort()));owner.close();
            }
        }
    }
}
