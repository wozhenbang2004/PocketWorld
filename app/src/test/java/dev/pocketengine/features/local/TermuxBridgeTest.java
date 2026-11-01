package dev.pocketengine.features.local;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.JSONArray;
import static org.robolectric.Shadows.shadowOf;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.project.GeneratedArtifacts;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TermuxBridgeTest {
    @Test public void missingTermuxNeverAdvertisesAnInstalledInterpreter()throws Exception {
        Context app=RuntimeEnvironment.getApplication();TermuxBridge bridge=new TermuxBridge(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));assertFalse(bridge.status().getBoolean("installed"));assertFalse(bridge.status().getBoolean("runCommandAvailable"));
        try{bridge.probe(new JSONObject());fail("Missing Termux was treated as an interpreter");}catch(IllegalStateException expected){}
        assertEquals(0,bridge.list().length());
    }
    @Test public void resultMustMatchPrivateNonceAndCannotBeConsumedTwice()throws Exception {
        Context app=RuntimeEnvironment.getApplication();File receipt=new File(app.getFilesDir(),"termux-receipts/fixture-operation.json");JsonFiles.write(receipt,new JSONObject().put("id","fixture-operation").put("operationId","fixture-operation").put("nonce","fixture-nonce").put("status","awaiting_result"));
        Bundle bundle=new Bundle();bundle.putInt("err",-1);bundle.putInt("exitCode",0);bundle.putString("stdout","actual callback payload");
        Intent forged=new Intent().setData(Uri.parse("pocketworld-local://result/fixture-operation/wrong-nonce")).putExtra("result",bundle);assertNull(TermuxBridge.receive(app,forged));assertEquals("awaiting_result",JsonFiles.read(receipt).getString("status"));
        Intent actual=new Intent().setData(Uri.parse("pocketworld-local://result/fixture-operation/fixture-nonce")).putExtra("result",bundle);JSONObject result=TermuxBridge.receive(app,actual);assertEquals("completed",result.getString("status"));assertEquals("actual callback payload",result.getString("stdout"));assertFalse(result.has("nonce"));assertNull(TermuxBridge.receive(app,actual));
    }
    private TermuxBridge enabled(Context app)throws Exception {
        PackageInfo info=new PackageInfo();info.packageName="com.termux";info.versionName="fixture";shadowOf(app.getPackageManager()).installPackage(info);shadowOf((Application)app).grantPermissions(TermuxBridge.PERMISSION);
        TermuxBridge bridge=new TermuxBridge(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));bridge.setEnabled(true);return bridge;
    }
    private JSONObject callback(Context app,String operation,int exit)throws Exception {
        JSONObject value=JsonFiles.read(new File(app.getFilesDir(),"termux-receipts/"+operation+".json"));Bundle bundle=new Bundle();bundle.putInt("err",-1);bundle.putInt("exitCode",exit);bundle.putString("stdout","fixture callback");
        return TermuxBridge.receive(app,new Intent().setData(Uri.parse("pocketworld-local://result/"+operation+"/"+value.getString("nonce"))).putExtra("result",bundle));
    }
    @Test public void stageReceiptBindsCheckoutAndRunCannotChangeInitialDirectory()throws Exception {
        Context app=RuntimeEnvironment.getApplication();TermuxBridge bridge=enabled(app);String checkout="12345678-1234-1234-1234-123456789abc";
        JSONObject snapshot=new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId","project").put("files",new JSONArray())).put("files",new JSONArray().put(new JSONObject().put("path","main.py").put("content","print(1)")));
        JSONObject staged=bridge.stage(snapshot,new JSONObject().put("operationId","stage-op"));assertEquals(checkout,staged.getString("checkoutId"));assertEquals("awaiting_result",staged.getString("status"));assertTrue(staged.getString("workdir").endsWith(checkout));
        Intent stage=shadowOf((Application)app).getNextStartedService();assertEquals("com.termux.RUN_COMMAND",stage.getAction());assertTrue(stage.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND",false));assertTrue(stage.getStringExtra("com.termux.RUN_COMMAND_STDIN").contains("base64 -d"));
        try{bridge.run(new JSONObject().put("checkoutId",checkout).put("executable","python3").put("operationId","before-stage"));fail("Run before complete stage accepted");}catch(IllegalStateException expected){}
        callback(app,"stage-op",0);
        try{bridge.run(new JSONObject().put("checkoutId",checkout).put("executable","python3").put("workdir","/data/data/com.termux/files/home/other"));fail("Changed initial directory accepted");}catch(SecurityException expected){}
        JSONObject input=new JSONObject().put("checkoutId",checkout).put("executable","python3").put("argv",new JSONArray().put("main.py")).put("operationId","run-op");JSONObject run=bridge.run(input);assertEquals(checkout,run.getString("checkoutId"));assertTrue(bridge.busy(checkout));
        Intent actual=shadowOf((Application)app).getNextStartedService();assertTrue(actual.getStringExtra("com.termux.RUN_COMMAND_WORKDIR").endsWith(checkout));assertEquals("main.py",actual.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")[0]);assertEquals(run.getString("id"),bridge.run(input).getString("id"));assertNull(shadowOf((Application)app).getNextStartedService());
        JSONObject cancelled=bridge.cancel("run-op");assertEquals("unknown",cancelled.getString("status"));assertTrue(cancelled.getBoolean("cancelRequested"));assertTrue(bridge.busy(checkout));
        try{bridge.run(new JSONObject(input.toString()).put("operationId","new-run"));fail("Unknown operation was bypassed");}catch(IllegalStateException expected){}
        JSONObject completed=callback(app,"run-op",1);assertTrue(completed.getLong("updatedAt")>cancelled.getLong("updatedAt"));assertTrue(cancelled.getLong("updatedAt")>run.getLong("updatedAt"));assertFalse(bridge.busy(checkout));
    }
    @Test public void collectionUsesFixedReadOnlyScriptAndKeepsSnapshotPrivate()throws Exception {
        Context app=RuntimeEnvironment.getApplication();TermuxBridge bridge=enabled(app);String checkout="12345678-1234-1234-1234-123456789abc";
        File stage=new File(app.getFilesDir(),"termux-receipts/stage.json");JsonFiles.write(stage,new JSONObject().put("id","stage").put("checkoutId",checkout).put("kind","project_stage").put("status","completed"));
        JSONObject source=new JSONObject().put("id",checkout).put("projectId","project").put("files",new JSONArray());JSONObject job=bridge.collect(source,new JSONObject().put("operationId","collect"));
        assertFalse(job.has("sourceCheckout"));assertEquals(checkout,bridge.collection("collect").getJSONObject("sourceCheckout").getString("id"));Intent actual=shadowOf((Application)app).getNextStartedService();assertEquals(TermuxWorkspaceTransfer.collectScript(),actual.getStringExtra("com.termux.RUN_COMMAND_STDIN"));assertEquals(0,actual.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS").length);assertTrue(actual.getStringExtra("com.termux.RUN_COMMAND_WORKDIR").endsWith(checkout));
    }

    @Test public void malformedSuccessfulCollectionDoesNotAdvertiseReadyOrExposeRawPayload()throws Exception {
        Context app=RuntimeEnvironment.getApplication();TermuxBridge bridge=new TermuxBridge(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));
        File receipt=new File(app.getFilesDir(),"termux-receipts/invalid-collection.json");JsonFiles.write(receipt,new JSONObject().put("id","invalid-collection").put("operationId","invalid-collection").put("kind","workspace_collect").put("nonce","nonce").put("status","awaiting_result"));
        JSONObject response=callback(app,"invalid-collection",0);assertFalse(response.getBoolean("transferReady"));assertTrue(response.has("transferError"));assertFalse(response.has("stdout"));assertFalse(bridge.list().getJSONObject(0).has("stdout"));
    }
    @Test public void officialOriginalOutputLengthFlagsTruncatedCallback()throws Exception {
        Context app=RuntimeEnvironment.getApplication();File receipt=new File(app.getFilesDir(),"termux-receipts/truncated-callback.json");JsonFiles.write(receipt,new JSONObject().put("id","truncated-callback").put("nonce","nonce").put("status","awaiting_result"));
        Bundle bundle=new Bundle();bundle.putInt("err",-1);bundle.putInt("exitCode",0);bundle.putString("stdout","tail");bundle.putString("stdout_original_length","100001");
        JSONObject response=TermuxBridge.receive(app,new Intent().setData(Uri.parse("pocketworld-local://result/truncated-callback/nonce")).putExtra("result",bundle));assertTrue(response.getBoolean("outputTruncated"));assertEquals("tail",response.getString("stdout"));
    }

    @Test public void digestRevisionsKeepDistinctSourcesAndPersistExactNativePlanLink()throws Exception {
        Context app=RuntimeEnvironment.getApplication();TermuxBridge bridge=enabled(app);String checkout="12345678-1234-1234-1234-123456789abc";
        JSONObject source=new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId","project")).put("files",new JSONArray().put(new JSONObject().put("path","main.py").put("content","print(1)\n")));
        String first=ProjectSources.describe(source).getString("sourceDigest");JSONObject stage=bridge.stage(source,new JSONObject().put("expectedSourceDigest",first).put("operationId","revision_1"));assertTrue(stage.getString("workdir").endsWith("/revisions/"+first));shadowOf((Application)app).getNextStartedService();callback(app,"revision_1",0);
        source.getJSONArray("files").getJSONObject(0).put("content","print(2)\n");String second=ProjectSources.describe(source).getString("sourceDigest");
        try{bridge.stage(source,new JSONObject().put("expectedSourceDigest",first).put("operationId","stale_stage"));fail();}catch(SecurityException expected){}assertNull(shadowOf((Application)app).getNextStartedService());
        JSONObject payload=new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",second),plan=new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",checkout).put("projectId","project").put("sourceDigest",second).put("sourceManifest",ProjectSources.describe(source).getJSONArray("files")).put("intent","build").put("steps",new JSONArray().put(new JSONObject().put("id","prepare").put("localCommand","termux.prepare").put("payload",payload)));
        String seal=ProjectSources.digest(plan);plan.put("planId",seal).put("planDigest",seal);JSONObject input=new JSONObject(payload.toString()).put("projectPlan",plan).put("projectStepId","prepare").put("_taskId","native_task").put("_runId","revision_2").put("operationId","revision_2");
        JSONObject newer=bridge.stage(source,input);assertNotEquals(stage.getString("workdir"),newer.getString("workdir"));assertEquals("native_task",newer.getJSONObject("projectExecution").getString("hostTaskId"));assertEquals("prepare",newer.getJSONObject("projectExecution").getString("intent"));assertEquals(seal,JsonFiles.read(new File(app.getFilesDir(),"termux-receipts/revision_2.json")).getJSONObject("projectExecution").getString("planDigest"));Intent stageIntent=shadowOf((Application)app).getNextStartedService();assertTrue(stageIntent.getStringExtra("com.termux.RUN_COMMAND_STDIN").contains(second));callback(app,"revision_2",0);
        JSONObject run=bridge.run(new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",second).put("executable","python3").put("argv",new JSONArray().put("main.py")).put("operationId","guarded_run"));assertEquals("python3",run.getString("requestedExecutable"));assertFalse(run.getBoolean("atomicSourceFence"));assertFalse(run.has("projectExecution"));Intent command=shadowOf((Application)app).getNextStartedService();assertTrue(command.getStringExtra("com.termux.RUN_COMMAND_PATH").endsWith("/sh"));assertTrue(command.getStringExtra("com.termux.RUN_COMMAND_STDIN").contains("sha256sum"));assertTrue(command.getStringExtra("com.termux.RUN_COMMAND_WORKDIR").endsWith(second));
        assertTrue(stage.getLong("checkoutSequence")<newer.getLong("checkoutSequence"));assertTrue(newer.getLong("checkoutSequence")<run.getLong("checkoutSequence"));
    }

    @Test public void selectedCollectionUsesOriginalTrackedTransportAndPersistsExactProvenance()throws Exception {
        Context app=RuntimeEnvironment.getApplication();TermuxBridge bridge=enabled(app);String checkout="12345678-1234-1234-1234-123456789abc";
        JSONObject source=new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId","project")).put("files",new JSONArray().put(new JSONObject().put("path","main.js").put("content","console.log(1)\n")));String digest=ProjectSources.describe(source).getString("sourceDigest");
        bridge.stage(source,new JSONObject().put("expectedSourceDigest",digest).put("operationId","selected_stage"));shadowOf((Application)app).getNextStartedService();callback(app,"selected_stage",0);
        JSONObject payload=new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",digest).put("executable","node").put("argv",new JSONArray().put("--check").put("./main.js"));JSONObject plan=new JSONObject().put("format","pocketworld.project-plan.v1").put("checkoutId",checkout).put("projectId","project").put("sourceDigest",digest).put("sourceManifest",ProjectSources.describe(source).getJSONArray("files")).put("intent","build").put("steps",new JSONArray().put(new JSONObject().put("id","build").put("localCommand","termux.run").put("payload",payload)));String seal=ProjectSources.digest(plan);plan.put("planId",seal).put("planDigest",seal);
        bridge.run(new JSONObject(payload.toString()).put("projectPlan",plan).put("projectStepId","build").put("_taskId","build_task").put("_runId","selected_build").put("operationId","selected_build"));shadowOf((Application)app).getNextStartedService();JSONObject build=JsonFiles.read(new File(app.getFilesDir(),"termux-receipts/selected_build.json"));Bundle built=new Bundle();built.putInt("err",-1);built.putInt("exitCode",0);built.putString("stdout",ProjectSources.marker(digest)+"\nfixture CLI callback\n");TermuxBridge.receive(app,new Intent().setData(Uri.parse("pocketworld-local://result/selected_build/"+build.getString("nonce"))).putExtra("result",built));
        EngineModule local=new EngineModule(){public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{if(command.equals("export"))return source;if(command.equals("listJobs"))return new JSONObject().put("termuxJobs",bridge.list());if(command.equals("getJob"))return bridge.get(input.getString("jobId"));throw new AssertionError(command);}};
        JSONObject selection=GeneratedArtifacts.prepare(local,new JSONObject().put("plan",plan).put("stepId","build").put("jobId","selected_build").put("path","dist/index.html")),input=new JSONObject(selection.getJSONObject("hostAction").getJSONObject("payload").toString()).put("_taskId","collect_task").put("_runId","selected_collect").put("operationId","selected_collect");JSONObject collected=bridge.collect(source.getJSONObject("checkout"),input);Intent actual=shadowOf((Application)app).getNextStartedService();String script=actual.getStringExtra("com.termux.RUN_COMMAND_STDIN");assertTrue(script.contains("dist/index.html"));assertFalse(script.contains("scan ."));assertTrue(actual.getStringExtra("com.termux.RUN_COMMAND_WORKDIR").endsWith(digest));assertEquals("selected_text_artifact",collected.getString("collectionMode"));assertEquals("collect_task",collected.getJSONObject("artifactCollection").getString("hostTaskId"));assertTrue(collected.getLong("checkoutSequence")>build.getLong("checkoutSequence"));assertFalse(collected.getBoolean("artifactReady"));
        JSONObject cancelled=bridge.cancel("selected_collect");assertEquals("unknown",cancelled.getString("status"));try{GeneratedArtifacts.read(local,new JSONObject().put("collectionPlan",selection).put("collectionJobId","selected_collect"));fail();}catch(SecurityException expected){}assertNull(shadowOf((Application)app).getNextStartedService());
    }

}
