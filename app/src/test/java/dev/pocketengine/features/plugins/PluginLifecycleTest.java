package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class PluginLifecycleTest {
    private PluginsModule module(Context context)throws Exception {
        return new PluginsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
    }
    private JSONObject manifest(String version)throws Exception {
        return new JSONObject().put("id","lifecycle").put("name","版本测试").put("version",version)
                .put("tools",new JSONArray().put(new JSONObject().put("name","show").put("kind","echo")));
    }
    private JSONObject install(PluginsModule module,String version)throws Exception {
        return module.handle("install",new JSONObject().put("manifest",manifest(version)));
    }
    private void enable(PluginsModule module)throws Exception {
        dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(module,"lifecycle",true);
        module.handle("grant",new JSONObject().put("id","lifecycle").put("grants",new JSONArray().put("tool:show")));
    }
    private JSONObject current(PluginsModule module)throws Exception {return module.snapshot().getJSONArray("plugins").getJSONObject(0);}
    private JSONObject rollback(JSONObject current,JSONObject target)throws Exception {
        return new JSONObject().put("id","lifecycle").put("expectedInstallationId",current.getString("installationId"))
                .put("expectedStateRevision",current.optLong("stateRevision"))
                .put("targetInstallationId",target.getString("installationId"));
    }
    private String credentialFixture(Context context,String label)throws Exception {
        // Lifecycle tests only need slot presence/removal. Do not fake AndroidKeyStore encryption.
        File file=new File(context.getFilesDir(),"plugins/manifests.json");JSONObject stored=JsonFiles.read(file);
        String slot="plugin-install:"+label;stored.getJSONArray("plugins").getJSONObject(0).put("credentialRef",slot);
        JsonFiles.write(file,stored);
        assertTrue(context.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).edit().putString(slot,"fixture-only-not-encrypted").commit());
        return slot;
    }

    @Test public void previewIsReadOnlyAndRestoreSurvivesRestartWithoutResurrectingCredentialsOrGrants()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        File file=new File(context.getFilesDir(),"plugins/manifests.json");SecretVault vault=new SecretVault(context);
        try{
            JSONObject preview=module.handle("previewInstall",new JSONObject().put("manifest",manifest("1")));
            assertFalse(file.exists());assertFalse(preview.getBoolean("isUpdate"));
            JSONObject first=module.handle("install",new JSONObject().put("manifest",manifest("1")));
            String oldRef=credentialFixture(context,"old-slot");enable(module);
            JSONObject second=module.handle("install",new JSONObject().put("manifest",manifest("2"))
                    .put("expectedInstallationId",first.getString("installationId")));
            assertFalse(vault.contains(oldRef));assertFalse(second.getBoolean("enabled"));assertEquals(1,second.getInt("historyCount"));
            String secondRef=credentialFixture(context,"new-slot");assertTrue(current(module).getBoolean("hasToken"));
            module.close();module=module(context);
            JSONObject history=module.handle("history",new JSONObject().put("id","lifecycle"));
            assertEquals("1",history.getJSONArray("versions").getJSONObject(0).getJSONObject("manifest").getString("version"));
            for(String secret:new String[]{"fixture-only-not-encrypted",oldRef,secondRef,"credentialRef","grants"})assertFalse(history.toString().contains(secret));
            JSONObject restored=module.handle("rollback",rollback(second,first));
            assertEquals("1",restored.getJSONObject("manifest").getString("version"));assertFalse(restored.getBoolean("enabled"));
            assertFalse(restored.getBoolean("hasToken"));assertFalse(vault.contains(secondRef));assertEquals(0,restored.getJSONArray("grants").length());
            assertNotEquals(first.getString("installationId"),restored.getString("installationId"));
            assertNotEquals(second.getString("installationId"),restored.getString("installationId"));
            assertEquals(first.getString("installationId"),restored.getString("restoredFromInstallationId"));
            assertEquals(0,module.toolRegistry().length());assertFalse(restored.has("history"));
            enable(module);
            JSONObject oldDescriptor=new JSONObject().put("id","lifecycle").put("tool","show").put("expectedVersion","1")
                    .put("expectedInstallationId",first.getString("installationId"));
            try{module.handle("invoke",oldDescriptor);fail("restoring a version must not resurrect its installation identity");}catch(SecurityException expected){}
            assertEquals("completed",module.handle("invoke",new JSONObject().put("id","lifecycle").put("tool","show")).getString("status"));
            module.close();module=module(context);assertEquals(restored.getString("installationId"),current(module).getString("installationId"));
            assertEquals(2,current(module).getInt("historyCount"));
        }finally{module.close();}
    }

    @Test public void reviewedToggleRejectsReplacementAndNeverAddsGrants()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject first=install(module,"1"),replacement=install(module,"2");
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            try{dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,first,true);fail();}catch(IllegalStateException expected){}
            try{module.setEnabledReviewed("lifecycle","",0,replacement.getString("grantsDigest"),true);fail();}catch(IllegalArgumentException expected){}
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertFalse(current(module).getBoolean("enabled"));
            JSONObject enabled=dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,replacement,true);
            assertTrue(enabled.getBoolean("enabled"));assertEquals(0,enabled.getJSONArray("grants").length());assertEquals(0,module.toolRegistry().length());
            assertFalse(dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,enabled,false).getBoolean("enabled"));
            assertFalse(module.snapshot().getJSONArray("commands").toString().contains("setEnabledReviewed"));
            try{module.handle("setEnabledReviewed",new JSONObject().put("id","lifecycle").put("expectedInstallationId",replacement.getString("installationId")).put("enabled",true));fail();}catch(IllegalArgumentException expected){}
        }finally{module.close();}
    }

    @Test public void queuedReviewedToggleCannotEnableAnInterveningInstallation()throws Exception {
        PluginsModule module=module(RuntimeEnvironment.getApplication());ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch queued=new CountDownLatch(1);
        try{
            JSONObject first=install(module,"1");Future<?> result;
            synchronized(module){
                result=worker.submit(()->{queued.countDown();try{dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,first,true);fail();}catch(IllegalStateException expected){}return null;});
                assertTrue(queued.await(2,TimeUnit.SECONDS));install(module,"2");
            }
            result.get(2,TimeUnit.SECONDS);assertFalse(current(module).getBoolean("enabled"));assertEquals(0,current(module).getJSONArray("grants").length());
        }finally{worker.shutdownNow();module.close();}
    }

    @Test public void sameInstallationGrantChangeInvalidatesEnableAndNoOpReviews()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject reviewed=install(module,"1");
            JSONObject granted=module.setToolGrantReviewed("lifecycle",reviewed.getString("installationId"),reviewed.getString("grantsDigest"),"show",true);
            assertEquals(reviewed.getString("installationId"),granted.getString("installationId"));
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            assertThrows(IllegalStateException.class,()->PluginOwnerTestReview.enableReviewed(module,reviewed,true));
            assertThrows(IllegalStateException.class,()->PluginOwnerTestReview.enableReviewed(module,reviewed,false));
            // A current revision cannot stand in for the actual grants that the owner reviewed.
            assertThrows(IllegalStateException.class,()->module.setEnabledReviewed("lifecycle",granted.getString("installationId"),granted.getLong("stateRevision"),reviewed.getString("grantsDigest"),true));
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertFalse(current(module).getBoolean("enabled"));assertEquals(0,module.toolRegistry().length());
            JSONObject enabled=PluginOwnerTestReview.enableReviewed(module,granted,true);
            assertTrue(enabled.getBoolean("enabled"));assertEquals(1,module.toolRegistry().length());
        }finally{module.close();}
    }

    @Test public void enableDisableCycleCannotReviveAnOlderToggleEvenForAnUnchangedValue()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject reviewed=install(module,"1"),enabled=PluginOwnerTestReview.enableReviewed(module,reviewed,true);
            JSONObject disabled=PluginOwnerTestReview.enableReviewed(module,enabled,false);
            assertEquals(reviewed.getString("installationId"),disabled.getString("installationId"));
            assertEquals(reviewed.getString("grantsDigest"),disabled.getString("grantsDigest"));assertEquals(reviewed.getLong("stateRevision")+2,disabled.getLong("stateRevision"));
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            assertThrows(IllegalStateException.class,()->PluginOwnerTestReview.enableReviewed(module,reviewed,true));
            assertThrows(IllegalStateException.class,()->PluginOwnerTestReview.enableReviewed(module,reviewed,false));
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));
            JSONObject unchanged=PluginOwnerTestReview.enableReviewed(module,disabled,false);
            assertEquals(disabled.getLong("stateRevision"),unchanged.getLong("stateRevision"));
        }finally{module.close();}
    }

    @Test public void queuedReviewedEnableRechecksChangedGrantsInsideTheMutationLock()throws Exception {
        PluginsModule module=module(RuntimeEnvironment.getApplication());ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch queued=new CountDownLatch(1);
        try{
            JSONObject reviewed=install(module,"1");Future<?> result;
            synchronized(module){
                result=worker.submit(()->{queued.countDown();assertThrows(IllegalStateException.class,()->PluginOwnerTestReview.enableReviewed(module,reviewed,true));return null;});
                assertTrue(queued.await(2,TimeUnit.SECONDS));
                module.setToolGrantReviewed("lifecycle",reviewed.getString("installationId"),reviewed.getString("grantsDigest"),"show",true);
            }
            result.get(2,TimeUnit.SECONDS);assertFalse(current(module).getBoolean("enabled"));assertEquals(1,current(module).getJSONArray("grants").length());assertEquals(0,module.toolRegistry().length());
        }finally{worker.shutdownNow();module.close();}
    }

    @Test public void genericEnableRequiresTheSameExactReviewAndCannotBypassTheNativeGate()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject reviewed=install(module,"1"),accepted=PluginOwnerTestReview.payload(reviewed,true);
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            for(String field:new String[]{"expectedInstallationId","expectedStateRevision","expectedGrantsDigest"}){
                JSONObject missing=new JSONObject(accepted.toString());missing.remove(field);
                assertThrows(org.json.JSONException.class,()->module.handle("enable",missing));
            }
            for(Object revision:new Object[]{"0",-1,0.5,true,new java.math.BigInteger("9223372036854775808")}){
                JSONObject invalid=new JSONObject(accepted.toString()).put("expectedStateRevision",revision);
                assertThrows(IllegalArgumentException.class,()->module.handle("enable",invalid));
            }
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertFalse(current(module).getBoolean("enabled"));
            JSONObject enabled=module.handle("enable",accepted);assertTrue(enabled.getBoolean("enabled"));
            JSONObject disabled=module.handle("enable",PluginOwnerTestReview.payload(enabled,false));
            assertThrows(IllegalStateException.class,()->module.handle("enable",accepted));
            assertEquals(disabled.getLong("stateRevision"),current(module).getLong("stateRevision"));assertFalse(current(module).getBoolean("enabled"));
        }finally{module.close();}
    }

    @Test public void stalePreviewAndRollbackCannotOverwriteAnInterveningInstallation()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject first=install(module,"1");JSONObject preview=module.handle("previewInstall",new JSONObject().put("manifest",manifest("2")));
            JSONObject actual=install(module,"other");
            try{module.handle("install",new JSONObject().put("manifest",manifest("2")).put("token","unused")
                    .put("expectedInstallationId",preview.getString("expectedInstallationId")));fail();}catch(IllegalStateException expected){}
            try{module.handle("rollback",rollback(first,first));fail();}catch(IllegalStateException expected){}
            JSONObject missing=rollback(actual,first);missing.remove("expectedInstallationId");
            try{module.handle("rollback",missing);fail();}catch(IllegalArgumentException expected){}
            JSONObject missingState=rollback(actual,first);missingState.remove("expectedStateRevision");
            try{module.handle("rollback",missingState);fail("installation identity alone cannot authorize a restore");}catch(IllegalArgumentException expected){}
            assertEquals(actual.getString("installationId"),current(module).getString("installationId"));assertEquals(1,current(module).getInt("historyCount"));
        }finally{module.close();}
    }

    @Test public void rollbackReviewedBeforeNewPermissionCannotEraseThatPermission()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject first=install(module,"1"),second=install(module,"2");
            JSONObject history=module.handle("history",new JSONObject().put("id","lifecycle"));
            JSONObject request=rollback(second,first).put("expectedStateRevision",history.optLong("currentStateRevision"));
            module.handle("grant",new JSONObject().put("id","lifecycle").put("grants",new JSONArray().put("tool:show")));
            JSONObject newer=current(module);assertEquals(second.getString("installationId"),newer.getString("installationId"));
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            try{module.handle("rollback",request);fail("an older restore must not erase a newer owner grant");}catch(IllegalStateException expected){}
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));
            assertEquals("tool:show",current(module).getJSONArray("grants").getString(0));
        }finally{module.close();}
    }

    @Test public void rollbackReviewedBeforeEnableDisableCycleCannotUseAnOldOwnerDecision()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject first=install(module,"1"),second=install(module,"2");
            JSONObject history=module.handle("history",new JSONObject().put("id","lifecycle"));
            JSONObject request=rollback(second,first).put("expectedStateRevision",history.optLong("currentStateRevision"));
            JSONObject enabled=dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,second,true);
            dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,enabled,false);
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            try{module.handle("rollback",request);fail("a restored value must not revive a stale restore approval");}catch(IllegalStateException expected){}
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));
            assertEquals(second.getString("installationId"),current(module).getString("installationId"));
        }finally{module.close();}
    }

    @Test public void restorePreviewShowsActualTargetChangesWithoutChangingTheInstallation()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject old=manifest("1"),tool=old.getJSONArray("tools").getJSONObject(0);
            tool.put("kind","http").put("endpoint","https://old.example/tool").put("effect","read")
                    .put("schema",new JSONObject().put("type","object").put("required",new JSONArray().put("query")));
            JSONObject first=module.handle("install",new JSONObject().put("manifest",old));
            JSONObject next=new JSONObject(old.toString()).put("version","2");
            next.getJSONArray("tools").getJSONObject(0).put("endpoint","https://new.example/tool").put("effect","write")
                    .put("schema",new JSONObject().put("type","object").put("required",new JSONArray().put("different")));
            JSONObject second=module.handle("install",new JSONObject().put("manifest",next));
            File file=new File(context.getFilesDir(),"plugins/manifests.json");byte[] before=Files.readAllBytes(file.toPath());
            JSONObject preview=module.handle("previewRollback",rollback(second,first));
            assertTrue(preview.getBoolean("restoration"));assertEquals(first.getString("installationId"),preview.getString("targetInstallationId"));
            assertEquals("2",preview.getString("currentVersion"));assertEquals("1",preview.getString("targetVersion"));
            assertEquals(0,preview.getLong("expectedStateRevision"));
            JSONObject change=preview.getJSONArray("changedTools").getJSONObject(0);
            assertEquals(3,change.getJSONArray("fields").length());
            assertEquals("https://new.example/tool",change.getJSONObject("before").getString("endpoint"));
            assertEquals("https://old.example/tool",change.getJSONObject("after").getString("endpoint"));
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals(second.getString("installationId"),current(module).getString("installationId"));
        }finally{module.close();}
    }

    @Test public void revokeThenRegrantDoesNotReviveAnAdmittedExecutionBinding()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject installed=install(module,"1");
            JSONObject enabled=dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,installed,true);
            JSONObject granted=module.setToolGrantReviewed("lifecycle",installed.getString("installationId"),enabled.getString("grantsDigest"),"show",true);
            JSONObject admitted=module.toolRegistry().getJSONObject(0).getJSONObject("executionBinding");
            JSONObject revoked=module.setToolGrantReviewed("lifecycle",installed.getString("installationId"),granted.getString("grantsDigest"),"show",false);assertEquals(0,module.toolRegistry().length());
            module.setToolGrantReviewed("lifecycle",installed.getString("installationId"),revoked.getString("grantsDigest"),"show",true);
            JSONObject renewed=module.toolRegistry().getJSONObject(0).getJSONObject("executionBinding");
            assertEquals(admitted.getString("installationId"),renewed.getString("installationId"));assertEquals(admitted.getString("grantsDigest"),renewed.getString("grantsDigest"));
            assertNotEquals("a restored grant value cannot restore an old model or Skill plan identity",admitted.toString(),renewed.toString());
        }finally{module.close();}
    }

    @Test public void disableThenEnableDoesNotReviveAnAdmittedExecutionBinding()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject installed=install(module,"1");enable(module);
            JSONObject admitted=module.toolRegistry().getJSONObject(0).getJSONObject("executionBinding");
            JSONObject disabled=dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,current(module),false);assertEquals(0,module.toolRegistry().length());
            dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,disabled,true);
            JSONObject renewed=module.toolRegistry().getJSONObject(0).getJSONObject("executionBinding");
            assertEquals(admitted.getString("installationId"),renewed.getString("installationId"));assertEquals(admitted.getString("grantsDigest"),renewed.getString("grantsDigest"));
            assertNotEquals("an enable cycle cannot restore an old model or Skill plan identity",admitted.toString(),renewed.toString());
        }finally{module.close();}
    }

    @Test public void restoredDeclarationExecutesItsRealEndpointOnlyAfterFreshEnableAndGrant()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        java.util.concurrent.atomic.AtomicReference<String> requestBody=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> serverFailure=new java.util.concurrent.atomic.AtomicReference<>();
        try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            Thread responder=new Thread(()->{try(Socket socket=server.accept()){
                socket.setSoTimeout(2000);InputStream in=socket.getInputStream();String requestLine=line(in);int length=0;
                while(true){String header=line(in);if(header.isEmpty())break;if(header.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());}
                byte[] body=in.readNBytes(length);requestBody.set(requestLine+"\n"+new String(body,StandardCharsets.UTF_8));
                byte[] response="{\"result\":\"restored endpoint returned actual result\"}".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(response);
            }catch(Exception failure){serverFailure.set(failure);}});responder.setDaemon(true);responder.start();
            JSONObject firstManifest=manifest("1");firstManifest.getJSONArray("tools").getJSONObject(0).put("kind","http")
                    .put("endpoint","http://127.0.0.1:"+server.getLocalPort()+"/restored").put("effect","read");
            JSONObject first=module.handle("install",new JSONObject().put("manifest",firstManifest));
            JSONObject second=install(module,"2"),restore=rollback(second,first);
            assertTrue(module.handle("previewRollback",restore).getBoolean("restoration"));
            module.close();module=module(context);
            JSONObject restored=module.handle("rollback",restore);assertFalse(restored.getBoolean("enabled"));assertFalse(restored.getBoolean("hasToken"));assertEquals(0,restored.getJSONArray("grants").length());
            JSONObject invoke=new JSONObject().put("id","lifecycle").put("tool","show").put("expectedVersion","1")
                    .put("expectedInstallationId",restored.getString("installationId")).put("arguments",new JSONObject().put("query","real task input"));
            try{module.handle("invoke",invoke);fail("restoration must not execute before enabling");}catch(IllegalStateException disabled){}
            JSONObject enabled=dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(module,restored,true);assertEquals(1,enabled.getLong("stateRevision"));
            try{module.handle("invoke",invoke);fail("enabling does not restore an old grant");}catch(SecurityException ungranted){}
            JSONObject granted=module.setToolGrantReviewed("lifecycle",restored.getString("installationId"),enabled.getString("grantsDigest"),"show",true);assertEquals(2,granted.getLong("stateRevision"));
            JSONObject receipt=module.handle("invoke",invoke);assertEquals("response_received",receipt.getString("status"));assertFalse(receipt.getBoolean("verified"));
            assertEquals("restored endpoint returned actual result",receipt.getJSONObject("toolResult").getString("result"));
            responder.join(2000);assertFalse(responder.isAlive());assertNull(serverFailure.get());assertTrue(requestBody.get().startsWith("POST /restored "));assertTrue(requestBody.get().contains("real task input"));
            module.close();module=module(context);assertEquals(2,current(module).getLong("stateRevision"));
            try{module.handle("invoke",new JSONObject(invoke.toString()).put("expectedInstallationId",first.getString("installationId")));fail("old target installation identity cannot return");}catch(SecurityException oldIdentity){}
        }finally{module.close();}
    }

    @Test public void onlyThreePriorDeclarationsRemainAndUninstallDeletesTheirRecoveryPath()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        try{
            JSONObject first=install(module,"1"),latest=null;for(int i=2;i<=6;i++)latest=install(module,Integer.toString(i));
            JSONArray versions=module.handle("history",new JSONObject().put("id","lifecycle")).getJSONArray("versions");
            assertEquals(3,versions.length());assertEquals("5",versions.getJSONObject(0).getJSONObject("manifest").getString("version"));
            try{module.handle("rollback",rollback(latest,first));fail();}catch(IllegalArgumentException expected){}
            module.handle("remove",new JSONObject().put("id","lifecycle"));assertEquals(0,module.snapshot().getJSONArray("plugins").length());
            try{module.handle("history",new JSONObject().put("id","lifecycle"));fail();}catch(IllegalArgumentException expected){}
            assertEquals(0,install(module,"fresh").getInt("historyCount"));
        }finally{module.close();}
    }

    @Test public void failedHistoryCommitLeavesThePreviousInstallationAndCredentialIntact()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        File file=new File(context.getFilesDir(),"plugins/manifests.json");SecretVault vault=new SecretVault(context);
        try{
            JSONObject first=module.handle("install",new JSONObject().put("manifest",manifest("1")));credentialFixture(context,"preserved-slot");enable(module);
            JSONObject stored=JsonFiles.read(file),old=stored.getJSONArray("plugins").getJSONObject(0);String oldRef=old.getString("credentialRef");
            // Fill a separate record to just below the shared atomic file limit. Archiving then exceeds it.
            JSONObject other=new JSONObject(old.toString()).put("id","padding-record").put("padding","");other.remove("credentialRef");
            stored.getJSONArray("plugins").put(other);
            int remaining=8*1024*1024-32-stored.toString().getBytes(StandardCharsets.UTF_8).length;
            other.put("padding","x".repeat(remaining));JsonFiles.write(file,stored);byte[] before=Files.readAllBytes(file.toPath());
            try{module.handle("install",new JSONObject().put("manifest",manifest("2"))
                    .put("expectedInstallationId",first.getString("installationId")));fail("the new declaration and history must commit together");}catch(java.io.IOException expected){}
            assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertTrue(vault.contains(oldRef));
            assertEquals("fixture-only-not-encrypted",context.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).getString(oldRef,""));
            assertEquals(first.getString("installationId"),current(module).getString("installationId"));assertTrue(current(module).getBoolean("enabled"));
            assertEquals(0,current(module).getInt("historyCount"));
        }finally{module.close();}
    }

    @Test public void rollbackCancelsInFlightHttpAndNeverPublishesItsLateSuccess()throws Exception {
        Context context=RuntimeEnvironment.getApplication();PluginsModule module=module(context);
        CountDownLatch accepted=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            Thread responder=new Thread(()->{try(Socket socket=server.accept()){
                socket.setSoTimeout(2000);InputStream in=socket.getInputStream();int length=0;
                while(true){String header=line(in);if(header.isEmpty())break;if(header.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());}
                for(int i=0;i<length;i++)if(in.read()<0)throw new java.io.IOException();accepted.countDown();release.await(3,TimeUnit.SECONDS);
                byte[] body="{\"result\":\"late success\"}".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            }catch(Exception ignored){}});responder.setDaemon(true);responder.start();
            JSONObject declaration=manifest("1");declaration.getJSONArray("tools").getJSONObject(0).put("kind","http")
                    .put("endpoint","http://127.0.0.1:"+server.getLocalPort()+"/tool");
            JSONObject first=module.handle("install",new JSONObject().put("manifest",declaration));
            declaration.put("version","2");JSONObject second=module.handle("install",new JSONObject().put("manifest",declaration));enable(module);
            Future<JSONObject> response=worker.submit(()->module.handle("invoke",new JSONObject().put("id","lifecycle").put("tool","show")));
            assertTrue(accepted.await(2,TimeUnit.SECONDS));
            assertEquals(1,module.handle("previewInstall",new JSONObject().put("manifest",declaration)).getInt("activeCallsToCancel"));
            long before=System.nanoTime();module.handle("rollback",rollback(current(module),first));
            assertTrue("restore must not wait for the HTTP timeout",System.nanoTime()-before<1_000_000_000L);
            release.countDown();JSONObject receipt=response.get(2,TimeUnit.SECONDS);
            assertEquals("unknown",receipt.getString("status"));assertTrue(receipt.getBoolean("cancelRequested"));assertFalse(receipt.has("toolResult"));
            assertEquals(0,module.toolRegistry().length());assertEquals(0,module.snapshot().getJSONArray("active").length());responder.join(1000);
        }finally{release.countDown();worker.shutdownNow();module.close();}
    }

    private static String line(InputStream in)throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();int before=-1;
        while(true){int value=in.read();if(value<0)throw new java.io.IOException();if(before=='\r'&&value=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}result.write(value);before=value;}
    }
}
