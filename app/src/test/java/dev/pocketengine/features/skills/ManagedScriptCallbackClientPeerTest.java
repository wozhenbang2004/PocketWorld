package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;

/** Actual fixed Python client with own descriptor files and explicitly mocked
 * transport; no socket syscall, PRoot execution, Book or published receipt. */
public final class ManagedScriptCallbackClientPeerTest {
    private static JSONObject scenario(String name)throws Exception {
        Path root=Files.createTempDirectory("own-peer-client-"),tools=Files.createDirectory(root.resolve(".native-skill-tools")),client=Files.write(tools.resolve("client.py"),ManagedScriptCallbackClient.source());
        Path fixture=Path.of(Objects.requireNonNull(System.getProperty("pocket.gateway.source")),"tools/host-fixtures/skill_callback_client_fixture.py");
        Process own=new ProcessBuilder(Objects.requireNonNull(System.getProperty("a25.python")),"-I",fixture.toString(),client.toString(),name).redirectErrorStream(true).start();
        try{assertTrue("Own protocol-only client fixture did not settle",own.waitFor(5,TimeUnit.SECONDS));String output=new String(own.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(output,0,own.exitValue());JSONObject row=new JSONObject(output);assertFalse(row.getBoolean("kernelSocketOpened"));assertFalse(row.getBoolean("artifactPublished"));assertEquals(2,row.getInt("clientExit"));assertEquals(1,row.getInt("attempts"));assertEquals(1,row.getInt("closes"));return row;}
        finally{if(own.isAlive()){own.destroy();if(!own.waitFor(1,TimeUnit.SECONDS)){own.destroyForcibly();own.waitFor(2,TimeUnit.SECONDS);}}try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
    @Test public void nativeAndPinnedFakeRootCredentialViewsReachOnlyTheirOriginalCorrelatedRejectedReply()throws Exception {
        for(String mode:List.of("native_peer","proot_peer")){JSONObject row=scenario(mode);assertEquals(row.toString(),1,row.getInt("sends"));assertEquals("own_protocol_fixture_server_rejected",row.getJSONObject("result").getString("code"));assertEquals("协议正文🙂",row.getJSONObject("result").getString("message"));}
    }
    @Test public void foreignUidOrServerPidCannotSendAndUncertainTicketsNeverRetry()throws Exception {
        for(String mode:List.of("wrong_uid","wrong_pid","proot_wrong_uid","proot_wrong_pid")){JSONObject row=scenario(mode);assertEquals(row.toString(),0,row.getInt("sends"));assertEquals("rejected",row.getJSONObject("result").getString("status"));}
        for(String mode:List.of("half_write","lost_reply")){JSONObject row=scenario(mode);assertEquals(1,row.getInt("sends"));assertEquals("unknown",row.getJSONObject("result").getString("status"));assertFalse(row.getJSONObject("result").getBoolean("replayAllowed"));}
    }
}
