package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;

/** Actual original review/SQLite/JNI Python or shell -> fixed private CLI -> real
 * Linux Unix socket -> same native Publisher/Book -> owner task snapshot/read.
 * Android transport is separately compiled, not claimed exercised on a device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ManagedScriptCallbackNativeJourneyTest {
    private static final String VALUE="private original result 中文🙂";
    @Test public void credentialApiUsesItsRealJdkModuleWithoutOpeningTheFixtureEndpoint()throws Exception {
        var transport=ManagedScriptCallbackTestSupport.transport();JSONObject actual=(JSONObject)transport.getClass().getMethod("credentialApiFacts").invoke(transport);
        assertEquals("jdk.net",actual.getString("module"));assertTrue(actual.getBoolean("platformModule"));assertEquals("SO_PEERCRED",actual.getString("option"));assertEquals(0,ManagedScriptCallbackTestSupport.openServers(transport));assertEquals(0,ManagedScriptCallbackTestSupport.openClients(transport));
    }
    private static String python() {
        return "import json,pathlib,subprocess,time\np=pathlib.Path('output');p.mkdir(exist_ok=True)\n(p/'result.bin').write_bytes("+JSONObject.quote(VALUE)+".encode('utf-8'))\n"+
                "r=subprocess.run(['pocketworld','share','output/result.bin'],capture_output=True,text=True)\nprint('PRIVATE_CLIENT_EXIT',r.returncode,flush=True)\nif r.returncode: print(r.stdout,flush=True); raise RuntimeError('private callback failed')\n"+
                "while not (p/'release').exists(): time.sleep(.02)\n";
    }
    private static String shell(){return "set -eu\nmkdir -p output\nprintf '%s' '"+VALUE+"' > output/result.bin\npocketworld share output/result.bin\nwhile [ ! -f output/release ]; do sleep .02; done\n";}
    private static JSONObject publication(ManagedSkillNativeJourneyTest.Fixture f)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        do{JSONArray rows=f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation());if(rows.length()>0&&"published".equals(rows.getJSONObject(0).optString("state")))return rows.getJSONObject(0);if(f.work.isDone())throw new AssertionError("Original callback ended early: "+f.work.get()+"; original="+f.record());Thread.sleep(15);}while(System.nanoTime()<end);
        throw new AssertionError("Original callback publication absent: "+f.record());
    }
    private static void readyFile(ManagedSkillNativeJourneyTest.Fixture f,String path)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(!Files.isRegularFile(f.copy().resolve("workspace/"+path))&&System.nanoTime()<end){if(f.work.isDone())throw new AssertionError("Original managed script ended before marker: "+f.work.get());Thread.sleep(10);}assertTrue(Files.isRegularFile(f.copy().resolve("workspace/"+path)));
    }
    private static void release(ManagedSkillNativeJourneyTest.Fixture f)throws Exception {Files.write(f.copy().resolve("workspace/output/release"),new byte[]{1});}
    private static void kernelDrained(ManagedScriptCallbackTransport.Factory transport)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while((ManagedScriptCallbackTestSupport.openServers(transport)!=0||ManagedScriptCallbackTestSupport.openClients(transport)!=0)&&System.nanoTime()<end)Thread.sleep(10);
        assertEquals(0,ManagedScriptCallbackTestSupport.openServers(transport));assertEquals(0,ManagedScriptCallbackTestSupport.openClients(transport));
    }
    private static void livePublication(String interpreter,String source)throws Exception {
        var transport=ManagedScriptCallbackTestSupport.transport();
        try(var f=new ManagedSkillNativeJourneyTest.Fixture(interpreter,source,Map.of(),false,true,transport)){
            var future=f.execute();var reviewed=f.review();assertTrue(reviewed.plan.review().getJSONObject("request").getJSONObject("nativeExecutionContract").getBoolean("immediatePrivateArtifacts"));f.approve(reviewed);
            JSONObject published=publication(f);assertFalse("The artifact must be visible BEFORE the same script finishes",future.isDone());assertEquals(1,ManagedScriptCallbackTestSupport.openServers(transport));
            byte[] exact=VALUE.getBytes(StandardCharsets.UTF_8);assertArrayEquals(exact,f.host.readPublishedArtifact(f.id(),f.operation(),published.getString("attachmentId"),published.getString("sha256")));assertEquals(1,AttachmentRepository.get(f.android).book.list().length());
            JSONObject snapshot=f.host.taskSnapshot(f.id(),Set.of("skills_execute"),new JSONObject().put("plugins",new JSONArray()));JSONArray executions=snapshot.getJSONArray("executions");assertEquals("published",executions.getJSONObject(0).getJSONObject("arguments").getJSONObject("nativeSkill").getJSONObject("scriptSharedArtifacts").getJSONObject("script_output_0").getString("state"));assertTrue(f.host.privateArtifactRevision(f.id())>0);
            JSONObject descriptor=new JSONObject(new String(Files.readAllBytes(f.copy().resolve(".native-skill-callback.json")),StandardCharsets.UTF_8));String nonce=descriptor.getString("nonce"),endpoint=descriptor.getJSONObject("endpoint").getString("name");assertFalse(snapshot.toString().contains(nonce));assertFalse(snapshot.toString().contains(endpoint));assertFalse(f.tasks.get(f.id(),false).toString().contains(nonce));
            release(f);JSONObject finished=future.get(10,TimeUnit.SECONDS);assertTrue(finished.toString(),finished.getBoolean("ok"));assertEquals("40.00",f.originalInput());assertArrayEquals(exact,f.host.readPublishedArtifact(f.id(),f.operation(),published.getString("attachmentId"),published.getString("sha256")));assertFalse(f.tasks.get(f.id(),false).optBoolean("taskCompleted"));assertNotEquals("completed",f.tasks.get(f.id(),false).optString("status"));
            String log=f.host.readExecutionLog(f.id(),f.operation(),0,16000).toString();assertFalse(log.contains(nonce));kernelDrained(transport);
        }
        kernelDrained(transport);
    }
    @Test public void pythonSameOriginalJobPublishesPrivateArtifactBeforeExitAndOwnerCanReadIt()throws Exception{livePublication("python3",python());}
    @Test public void shellSameOriginalJobPublishesPrivateArtifactBeforeExitAndOwnerCanReadIt()throws Exception{livePublication("sh",shell());}
    @Test public void aLateFixedClientCannotReviveNormalOrCancelledOriginalScopes()throws Exception {
        for(boolean cancelled:List.of(false,true)){
            var transport=ManagedScriptCallbackTestSupport.transport();
            String script="from pathlib import Path\nimport time\np=Path('output');p.mkdir();(p/'result.bin').write_bytes(b'original unused private output')\n(p/'ready').write_text('ready')\nwhile not (p/'release').exists(): time.sleep(.02)\n";
            try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",script,Map.of(),false,true,transport)){
                f.execute();f.approve(f.review());readyFile(f,"output/ready");if(cancelled)f.control.cancel();else release(f);JSONObject original=f.work.get(10,TimeUnit.SECONDS);assertEquals(!cancelled,original.getBoolean("ok"));kernelDrained(transport);
                // This is only our fixed client against the now-closed endpoint. It does
                // not run the original script/Program again or create another task/op.
                Process late=new ProcessBuilder(f.copy().resolve(".native-skill-tools/pocketworld").toString(),"share","output/result.bin").directory(f.copy().resolve("workspace").toFile()).redirectErrorStream(true).start();
                try{assertTrue("own late client did not settle",late.waitFor(8,TimeUnit.SECONDS));assertEquals(2,late.exitValue());JSONObject response=new JSONObject(new String(late.getInputStream().readAllBytes(),StandardCharsets.UTF_8));assertEquals("unknown",response.getString("status"));assertFalse(response.getBoolean("replayAllowed"));}
                finally{if(late.isAlive()){late.destroy();if(!late.waitFor(1,TimeUnit.SECONDS)){late.destroyForcibly();late.waitFor(2,TimeUnit.SECONDS);}}}
                assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());kernelDrained(transport);
            }
        }
    }
    @Test public void legacyConfigurationDoesNotOpenCallbackOrMintPublicationFromOutputPaths()throws Exception {
        var transport=ManagedScriptCallbackTestSupport.transport();String script="import pathlib\np=pathlib.Path('output');p.mkdir();(p/'result.bin').write_bytes(b'ordinary result')\nassert not pathlib.Path('../.native-skill-callback.json').exists()\nassert not pathlib.Path('../.native-skill-tools').exists()\n";
        try(var f=new ManagedSkillNativeJourneyTest.Fixture("python3",script,Map.of(),false,false,transport)){
            var future=f.execute();var reviewed=f.review();assertFalse(reviewed.plan.review().getJSONObject("request").getJSONObject("nativeExecutionContract").getBoolean("immediatePrivateArtifacts"));f.approve(reviewed);assertTrue(future.get(10,TimeUnit.SECONDS).getBoolean("ok"));assertEquals(0,f.tasks.scriptArtifactPublicationsForOwner(f.id(),f.operation()).length());assertEquals(0,AttachmentRepository.get(f.android).book.list().length());kernelDrained(transport);
        }
    }
}
