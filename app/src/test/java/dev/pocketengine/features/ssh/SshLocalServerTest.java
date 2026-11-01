package dev.pocketengine.features.ssh;

import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses a real isolated OpenSSH server when supplied by local-server-test.py; no mocked SSH success. */
public final class SshLocalServerTest {
    private SshProfile base()throws Exception {
        Assume.assumeTrue("Isolated SSH fixture not started",System.getProperty("pocket.ssh.port")!=null);
        return new SshProfile(new JSONObject().put("id","localhost").put("name","isolated real OpenSSH").put("host","127.0.0.1")
                .put("port",Integer.parseInt(System.getProperty("pocket.ssh.port"))).put("username",System.getProperty("pocket.ssh.user"))
                .put("workspace",System.getProperty("pocket.ssh.workspace")).put("authType","key"));
    }
    private SshProfile trusted()throws Exception {SshProfile base=base();JSONObject actual=DirectSshClient.probe(base);assertFalse(actual.getBoolean("credentialsSent"));return new SshProfile(base.document().put("pinnedKey",actual.getString("key")));}
    private JSONObject credentials()throws Exception{return new JSONObject().put("privateKey",new String(Files.readAllBytes(Path.of(System.getProperty("pocket.ssh.key"))),java.nio.charset.StandardCharsets.UTF_8));}
    @Test public void realExecSftpReadAndConditionalWriteReturnActualReceipts()throws Exception {
        SshProfile profile=trusted();try(DirectSshClient.Connection ssh=DirectSshClient.connect(profile,credentials(),new RunControl())){
            JSONObject execution=ssh.exec("printf 'ssh-real\\n'; pwd",10);assertEquals("completed",execution.getString("status"));assertEquals(0,execution.getInt("exitCode"));assertTrue(execution.getString("stdout").contains(profile.root));
            JSONObject original=ssh.readFile("document.txt");assertEquals("initial",original.getString("content"));assertTrue(ssh.listFiles("").getJSONArray("files").length()>0);
            assertThrows(SecurityException.class,()->ssh.readFile("linked.txt"));assertThrows(IllegalArgumentException.class,()->ssh.readFile("../outside.txt"));
            assertThrows(SecurityException.class,()->ssh.writeFile("document.txt","unapproved",original.getString("version"),false));
            JSONObject written=ssh.writeFile("document.txt","changed",original.getString("version"),true);assertEquals("completed",written.getString("status"));assertTrue(written.getBoolean("externalWriterRacePossible"));
            assertEquals("changed",ssh.readFile("document.txt").getString("content"));
            JSONObject conflict=ssh.writeFile("document.txt","must-not-overwrite",original.getString("version"),true);assertEquals("version_conflict",conflict.getString("code"));assertEquals("changed",ssh.readFile("document.txt").getString("content"));
        }
    }
    @Test public void realHostKeyMismatchIsBlockedBeforeUserAuthentication()throws Exception {
        SshProfile base=base();String other=new String(Files.readAllBytes(Path.of(System.getProperty("pocket.ssh.otherHostPublic"))),java.nio.charset.StandardCharsets.UTF_8).trim().split(" ")[1];
        SshProfile wrong=new SshProfile(base.document().put("pinnedKey",other));
        assertTrue(DirectSshClient.probe(wrong).getBoolean("changed"));
        assertThrows(SecurityException.class,()->DirectSshClient.connect(wrong,credentials(),new RunControl()));
    }
    @Test public void realDoctorReturnsBoundedMetadataAndClosesItsConnection()throws Exception {
        SshProfile profile=trusted();DirectSshClient.Connection ssh=DirectSshClient.connect(profile,credentials(),new RunControl());
        try{JSONObject result=ssh.doctor();assertEquals("completed",result.getString("status"));assertTrue(result.getBoolean("readOnly"));assertTrue(result.getJSONObject("doctor").getBoolean("complete"));assertTrue(result.getJSONObject("doctor").getJSONObject("workspace").getBoolean("exists"));assertFalse(result.has("stdout"));assertFalse(result.has("stderr"));}
        finally{ssh.close();}assertTrue(ssh.localClosed());
    }
    @Test public void realPtyHasATtyAcceptsInputAndReceivesResize()throws Exception {
        SshProfile profile=trusted();DirectSshClient.Connection ssh=DirectSshClient.connect(profile,credentials(),new RunControl());
        try(DirectSshClient.Pty terminal=ssh.openPty(80,24,30)){
            String marker="PTY_"+UUID.randomUUID().toString().replace("-","");terminal.write(Base64.getEncoder().encodeToString(("printf '%s\\n' '"+marker+"'\n").getBytes(StandardCharsets.UTF_8)));
            assertTrue(awaitLine(terminal,marker));terminal.resize(99,33);terminal.write(Base64.getEncoder().encodeToString("stty size\n".getBytes(StandardCharsets.UTF_8)));assertTrue(awaitLine(terminal,"33 99"));
        }finally{ssh.close();}
    }
    @Test public void disconnectAfterDispatchIsUnknownAndNeverAutoReplaysCommand()throws Exception {
        SshProfile profile=trusted();RunControl control=new RunControl();DirectSshClient.Connection ssh=DirectSshClient.connect(profile,credentials(),control);
        Path marker=Path.of(profile.root,"cancel-started");Files.deleteIfExists(marker);
        FutureTask<JSONObject> call=new FutureTask<>(()->ssh.exec("printf once >> cancel-started; sleep 2",10));Thread worker=new Thread(call);worker.start();
        try{long until=System.nanoTime()+3_000_000_000L;while(!Files.exists(marker)&&System.nanoTime()<until)Thread.sleep(10);assertTrue(Files.exists(marker));control.cancel();
            JSONObject result=call.get(3,TimeUnit.SECONDS);assertEquals("unknown",result.getString("status"));assertFalse(result.getBoolean("remoteTerminationConfirmed"));assertEquals("once",new String(Files.readAllBytes(marker),java.nio.charset.StandardCharsets.UTF_8));
        }finally{control.cancel();ssh.close();worker.join(3000);}
    }
    private boolean awaitLine(DirectSshClient.Pty terminal,String wanted)throws Exception {
        long cursor=0,until=System.nanoTime()+3_000_000_000L;StringBuilder text=new StringBuilder();
        while(System.nanoTime()<until){JSONObject read=terminal.read(cursor);cursor=read.getLong("cursor");text.append(new String(Base64.getDecoder().decode(read.getString("dataBase64")),StandardCharsets.UTF_8));
            for(String line:text.toString().replace("\r","").split("\n"))if(line.equals(wanted))return true;Thread.sleep(20);
        }return false;
    }
}
