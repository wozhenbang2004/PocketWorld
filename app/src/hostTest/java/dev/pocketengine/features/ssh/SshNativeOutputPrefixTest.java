package dev.pocketengine.features.ssh;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

/** Actual production JSch against an explicitly supplied, synthetic loopback MINA
 * fixture. No real OS command/account/device and no model-output reconstruction. */
public final class SshNativeOutputPrefixTest {
    private DirectSshClient.Connection connection()throws Exception {
        assertNotNull("isolated fixture launcher required",System.getProperty("pocket.ssh.port"));
        SshProfile untrusted=new SshProfile(new JSONObject().put("id",UUID.randomUUID().toString()).put("name","Synthetic output fixture").put("host","127.0.0.1").put("port",Integer.parseInt(System.getProperty("pocket.ssh.port"))).put("username",System.getProperty("pocket.ssh.user")).put("workspace","/workspace").put("authType","key"));
        JSONObject observed=DirectSshClient.probe(untrusted);assertFalse(observed.getBoolean("credentialsSent"));assertEquals(System.getProperty("pocket.ssh.expectedHostFingerprint"),observed.getString("fingerprint"));SshProfile pinned=new SshProfile(untrusted.document().put("pinnedKey",observed.getString("key")));
        return DirectSshClient.connect(pinned,new JSONObject().put("privateKey",Files.readString(Path.of(System.getProperty("pocket.ssh.key")))),new RunControl());
    }
    private long commands()throws Exception{return Files.readString(Path.of(System.getProperty("pocket.ssh.log"))).lines().filter(line->line.startsWith("Command ")).count();}
    @Test public void eitherStreamLimitPreservesItsActualBoundedPrefixAndRemainsUnknownWithoutReplay()throws Exception {
        for(String stream:new String[]{"stdout","stderr"})try(DirectSshClient.Connection connection=connection()){
            long before=commands();JSONObject result=connection.exec("fixture_overflow_"+stream,20);System.out.println("overflow "+stream+" "+result.getJSONObject("nativeOutputRange")+" status="+result.optString("status")+" outputTruncated="+result.opt("outputTruncated"));assertEquals("unknown",result.getString("status"));assertTrue(result.isNull("accepted"));assertFalse(result.getBoolean("remoteTerminationConfirmed"));assertFalse(result.getBoolean("outputComplete"));assertTrue(result.getBoolean("outputTruncated"));assertEquals(DirectSshClient.MAX_OUTPUT,result.getString(stream).getBytes(StandardCharsets.UTF_8).length);assertEquals("Z".repeat(DirectSshClient.MAX_OUTPUT),result.getString(stream));
            JSONObject nativeRange=result.getJSONObject("nativeOutputRange"),range=nativeRange.getJSONObject("streams").getJSONObject(stream);assertEquals("output_limit",nativeRange.getString("termination"));assertFalse(nativeRange.getBoolean("losslessWireBytes"));assertEquals(0,range.getInt("fromByte"));assertEquals(DirectSshClient.MAX_OUTPUT,range.getInt("toByteExclusive"));assertEquals(DirectSshClient.MAX_OUTPUT,range.getInt("retainedBytes"));assertFalse(range.getBoolean("complete"));assertTrue(range.getBoolean("truncated"));assertTrue(range.getInt("observedBytesAtLeast")>DirectSshClient.MAX_OUTPUT);assertEquals(before+1,commands());if(stream.equals("stderr"))assertEquals("before stderr cap",result.getString("stdout"));
        }
    }
    @Test public void actualConnectionDropRetainsReceivedUnicodeButDoesNotGuessMissingOutputOrZeroTruncation()throws Exception {
        try(DirectSshClient.Connection connection=connection()){
            long before=commands();JSONObject result=connection.exec("fixture_disconnect_prefix",5);assertEquals("unknown",result.getString("status"));assertEquals("已收到的前缀\n",result.getString("stdout"));assertFalse(result.getBoolean("outputComplete"));assertTrue(result.isNull("outputTruncated"));JSONObject meta=result.getJSONObject("nativeOutputRange"),range=meta.getJSONObject("streams").getJSONObject("stdout");assertTrue(meta.getString("termination").matches("missing_exit|connection_lost"));assertEquals("UTF-8-with-replacement",meta.getString("encoding"));assertEquals("已收到的前缀\n".getBytes(StandardCharsets.UTF_8).length,range.getInt("retainedBytes"));assertFalse(range.getBoolean("complete"));assertTrue(range.isNull("truncated"));assertEquals(before+1,commands());
        }
    }
    @Test public void confirmedNonzeroExitHasCompleteBoundedOutputButIsNotBusinessSuccess()throws Exception {
        try(DirectSshClient.Connection connection=connection()){
            long before=commands();JSONObject result=connection.exec("fixture_complete_nonzero",5);assertEquals("failed",result.getString("status"));assertEquals(7,result.getInt("exitCode"));assertTrue(result.getBoolean("accepted"));assertFalse(result.getBoolean("verified"));assertEquals("native stdout",result.getString("stdout"));assertEquals("native stderr",result.getString("stderr"));assertTrue(result.getBoolean("outputComplete"));assertFalse(result.getBoolean("outputTruncated"));JSONObject meta=result.getJSONObject("nativeOutputRange");assertEquals("exit",meta.getString("termination"));for(String stream:new String[]{"stdout","stderr"}){JSONObject range=meta.getJSONObject("streams").getJSONObject(stream);assertTrue(range.getBoolean("complete"));assertFalse(range.getBoolean("truncated"));assertEquals(result.getString(stream).getBytes(StandardCharsets.UTF_8).length,range.getInt("retainedBytes"));}assertEquals(before+1,commands());
        }
    }
}
