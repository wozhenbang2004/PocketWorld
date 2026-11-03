package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

public final class ManagedScriptCallbackProtocolTest {
    private static final String OP="2f3425e7-b44f-47e1-9df1-23aad9486c45",NONCE="n".repeat(43);
    static JSONObject request(JSONObject descriptor,String path)throws Exception{return new JSONObject().put("format",ManagedScriptCallbackWire.REQUEST).put("nonce",descriptor.getString("nonce")).put("requestId",UUID.randomUUID().toString()).put("operationId",descriptor.getString("operationId")).put("path",path);}
    static byte[] frame(JSONObject value){return (value.toString()+"\n").getBytes(StandardCharsets.UTF_8);}
    private static byte[] raw(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8);}
    private static JSONObject descriptor()throws Exception{return new JSONObject().put("nonce",NONCE).put("operationId",OP);}
    @Test public void strictWireHasExactScopeAndNoOtherToolOrAuthorityFields()throws Exception {
        JSONObject valid=request(descriptor(),"output/中文🙂.bin");var actual=ManagedScriptCallbackWire.request(raw(valid),NONCE,OP);assertEquals("output/中文🙂.bin",actual.path);assertEquals(valid.getString("requestId"),actual.id);
        for(String key:List.of("phone","progress","taskId","jobId","url","arguments")){JSONObject forged=new JSONObject(valid.toString()).put(key,"forged");assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(raw(forged),NONCE,OP));}
        assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(raw(new JSONObject(valid.toString()).put("nonce","other")),NONCE,OP));
        assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(raw(new JSONObject(valid.toString()).put("operationId",UUID.randomUUID().toString())),NONCE,OP));
        for(String path:List.of("../private","/private","output/../private","output\\private","output/",""))assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(raw(new JSONObject(valid.toString()).put("path",path)),NONCE,OP));
        assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(raw(new JSONObject(valid.toString()).put("requestId","1-1-1-1-1")),NONCE,OP));
    }
    @Test public void malformedUnicodeDuplicateKeysBareWordsAndFrameLimitsAreRejected()throws Exception {
        String valid=request(descriptor(),"output/result.bin").toString();
        for(String text:List.of("\ufeff"+valid,valid+"\n",valid+"\r",valid.replace("\"path\":","\"path\":\"output/a\",\"path\":"),"{'path':'out'}",valid.replace("\"output/result.bin\"","bare"),valid+"{}"))assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(text.getBytes(StandardCharsets.UTF_8),NONCE,OP));
        assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(new byte[]{(byte)0xc3,(byte)0x28},NONCE,OP));assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(new byte[4096],NONCE,OP));
        assertThrows(Exception.class,()->ManagedScriptCallbackWire.request(valid.replace("output/result.bin","\\ud800").getBytes(StandardCharsets.UTF_8),NONCE,OP));
        byte[] response=ManagedScriptCallbackWire.reply(UUID.randomUUID().toString(),OP,ManagedScriptCallbackWire.rejected());assertEquals('\n',response[response.length-1]);assertFalse(new String(response,StandardCharsets.UTF_8).contains(NONCE));
    }
    @Test public void fixedClientAndQuotedCommandExecuteWithoutExposingMissingPrivateDescriptorDetails()throws Exception {
        Path root=Files.createTempDirectory("own skill callback 中文 "),bin=Files.createDirectory(root.resolve(".native-skill-tools"));
        Path python=Path.of(Objects.requireNonNull(System.getProperty("a25.python"))).toRealPath(),quoted=Files.createSymbolicLink(root.resolve("python ' fixed"),python);
        Files.write(bin.resolve("client.py"),ManagedScriptCallbackClient.source());Path command=bin.resolve("pocketworld");Files.write(command,ManagedScriptCallbackClient.command(List.of(quoted.toString())));assertTrue(command.toFile().setExecutable(true,false));
        assertThrows(SecurityException.class,()->ManagedScriptCallbackClient.command(List.of("relative-python")));assertThrows(SecurityException.class,()->ManagedScriptCallbackClient.command(List.of(python.toString()+"\nmalicious")));
        Path injected=root.resolve("not-created");Process own=new ProcessBuilder(command.toString(),"share","$(touch "+injected+")").directory(root.toFile()).redirectErrorStream(true).start();
        try{assertTrue(own.waitFor(5,TimeUnit.SECONDS));assertEquals(2,own.exitValue());String text=new String(own.getInputStream().readAllBytes(),StandardCharsets.UTF_8);JSONObject result=new JSONObject(text);assertEquals("unknown",result.getString("status"));assertFalse(result.getBoolean("replayAllowed"));assertFalse(result.getBoolean("externalTransmission"));assertFalse(text.contains(root.toString()));assertFalse(text.contains("Traceback"));assertFalse(Files.exists(injected));}
        finally{if(own.isAlive()){own.destroy();if(!own.waitFor(1,TimeUnit.SECONDS)){own.destroyForcibly();own.waitFor(2,TimeUnit.SECONDS);}}}
    }
    @Test public void optionalNativeOwnerChoiceIsStrictDefaultOffAndChangesTheOriginalFingerprint()throws Exception {
        JSONObject legacy=ManagedSkillPolicyTest.configuration();String old=legacy.toString();var absent=ManagedSkillPolicy.authority(legacy);assertFalse(absent.immediatePrivateArtifacts);assertEquals(old,ManagedSkillPolicy.checked(legacy).toString());
        var enabled=ManagedSkillPolicy.authority(new JSONObject(old).put("immediatePrivateArtifacts",true));assertTrue(enabled.immediatePrivateArtifacts);assertNotEquals(absent.fingerprint,enabled.fingerprint);
        for(Object bad:List.of("true",1,JSONObject.NULL))assertThrows(IllegalArgumentException.class,()->ManagedSkillPolicy.checked(new JSONObject(old).put("immediatePrivateArtifacts",bad)));
    }
    @Test public void frozenServerIdentityUsesOriginalNativeTransportAndOnlyTheSelectedCredentialView()throws Exception {
        for(boolean fakeRoot:List.of(false,true)){
            var transport=new ManagedScriptCallbackTestTransport();
            try(var scope=new ManagedScriptCallbacks(transport,OP,new RunControl(),System.currentTimeMillis()+10000,2,fakeRoot,()->{},path->{throw new AssertionError("Descriptor inspection cannot publish");})){
                JSONObject actual=scope.descriptor();assertEquals(7,actual.length());assertEquals(42,actual.getInt("hostUid"));assertEquals(12345,actual.getLong("hostPid"));assertEquals(fakeRoot?"proot-id0":"native",actual.getString("peerCredentialView"));
                assertEquals(OP,actual.getString("operationId"));assertTrue(actual.getString("nonce").matches("[A-Za-z0-9_-]{43}"));assertEquals(0,transport.clients.get());
            }assertEquals(0,transport.servers.get());
        }
    }
    @Test public void fragmentedFrameAndPartialReplyUseOnePublicationThenCloseTheirOriginalResources()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();AtomicInteger publications=new AtomicInteger();var control=new RunControl();
        try(var scope=new ManagedScriptCallbacks(transport,OP,control,System.currentTimeMillis()+10000,2,()->{},path->{publications.incrementAndGet();return new JSONObject().put("ok",true).put("status","published");})){
            var descriptor=scope.descriptor();scope.start();var channel=transport.offer(frame(request(descriptor,"output/result.bin")));JSONObject reply=new JSONObject(new String(channel.reply(),StandardCharsets.UTF_8));assertEquals("published",reply.getJSONObject("result").getString("status"));assertEquals(1,publications.get());assertFalse(reply.toString().contains(descriptor.getString("nonce")));
            scope.close();assertTrue(scope.awaitStopped(1000));assertTrue(scope.closeConfirmed());
        }assertEquals(0,transport.servers.get());assertEquals(0,transport.clients.get());
    }
    @Test public void malformedEofForeignUidAndWrongNonceCannotReachTheNativePublisher()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();AtomicInteger calls=new AtomicInteger();var control=new RunControl();
        try(var scope=new ManagedScriptCallbacks(transport,OP,control,System.currentTimeMillis()+10000,2,()->{},path->{calls.incrementAndGet();return new JSONObject();})){
            var d=scope.descriptor();scope.start();var wrong=transport.offer(frame(request(d,"output/result.bin").put("nonce","bad")));assertEquals(0,wrong.reply().length);
            var partial=transport.offer(raw(request(d,"output/result.bin")));assertEquals(0,partial.reply().length);
            var peer=transport.new Channel(frame(request(d,"output/result.bin")));peer.peer=false;transport.pending.add(peer);assertEquals(0,peer.reply().length);assertEquals(0,calls.get());
            scope.close();assertTrue(scope.awaitStopped(1000));
        }assertEquals(0,transport.clients.get());assertEquals(0,transport.servers.get());
    }
    @Test public void originalPauseThenResumePermanentlyRevokesTheOldScope()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();var control=new RunControl();AtomicInteger calls=new AtomicInteger();
        try(var scope=new ManagedScriptCallbacks(transport,OP,control,System.currentTimeMillis()+10000,2,()->{},path->{calls.incrementAndGet();return new JSONObject();})){
            scope.start();control.pause();control.resume();assertThrows(Exception.class,scope::checkCurrent);assertTrue(scope.awaitStopped(1000));assertEquals(0,calls.get());assertTrue(scope.closeConfirmed());
        }assertEquals(0,transport.servers.get());
    }
    @Test public void cancelDuringIncompleteReadReturnsWithoutWaitingForTheFrameTimeout()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();var control=new RunControl();AtomicInteger calls=new AtomicInteger();
        try(var scope=new ManagedScriptCallbacks(transport,OP,control,System.currentTimeMillis()+10000,2,()->{},path->{calls.incrementAndGet();return new JSONObject();})){
            scope.start();var channel=transport.new Channel(new byte[]{'{'});channel.eof=false;transport.pending.add(channel);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(transport.clients.get()==0&&System.nanoTime()<until)Thread.sleep(2);assertEquals(1,transport.clients.get());long start=System.nanoTime();control.cancel();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300);assertTrue(scope.awaitStopped(1000));assertEquals(0,channel.reply().length);assertEquals(0,calls.get());
        }assertEquals(0,transport.clients.get());assertEquals(0,transport.servers.get());
    }
    @Test public void lostReplyDoesNotRepeatAnAlreadyEnteredPublication()throws Exception {
        var transport=new ManagedScriptCallbackTestTransport();var control=new RunControl();AtomicInteger calls=new AtomicInteger();
        try(var scope=new ManagedScriptCallbacks(transport,OP,control,System.currentTimeMillis()+10000,2,()->{},path->{calls.incrementAndGet();return new JSONObject().put("ok",true).put("status","published");})){
            var d=scope.descriptor();var channel=transport.new Channel(frame(request(d,"output/result.bin")));channel.failWrite=true;transport.pending.add(channel);scope.start();assertEquals(0,channel.reply().length);Thread.sleep(30);assertEquals(1,calls.get());scope.close();assertTrue(scope.awaitStopped(1000));
        }assertEquals(0,transport.clients.get());
    }
    @Test public void unavailableCurrentOrCancellationDuringPublishCannotEmitALateSuccess()throws Exception {
        for(boolean cancel:List.of(false,true)){
            var transport=new ManagedScriptCallbackTestTransport();var control=new RunControl();AtomicInteger calls=new AtomicInteger();
            try(var scope=new ManagedScriptCallbacks(transport,OP,control,System.currentTimeMillis()+10000,2,()->{if(!cancel)throw new java.io.IOException("own uncertain original job");},path->{calls.incrementAndGet();control.cancel();return new JSONObject().put("ok",true).put("status","published");})){
                var d=scope.descriptor();scope.start();var channel=transport.offer(frame(request(d,"output/result.bin")));assertEquals(0,channel.reply().length);assertEquals(cancel?1:0,calls.get());scope.close();assertTrue(scope.awaitStopped(1000));
            }assertEquals(0,transport.clients.get());assertEquals(0,transport.servers.get());
        }
    }
}
