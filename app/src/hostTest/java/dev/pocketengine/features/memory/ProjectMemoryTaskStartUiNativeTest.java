package dev.pocketengine.features.memory;

import android.os.Looper;
import dev.pocketengine.ui.ComposeActivity;
import dev.pocketengine.ui.PocketWorldController;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Existing native Controller start -> original Application/Runner/SQL and physical loopback.
 * Compose rendering, real provider meaning and Android phone acceptance are not claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=dev.pocketengine.EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ProjectMemoryTaskStartUiNativeTest {
    @Before public void keys(){java.security.Security.addProvider(new java.security.Provider("ProjectMemoryUiFixtureKeys",1.0d,"Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void clear(){java.security.Security.removeProvider("ProjectMemoryUiFixtureKeys");}
    private static final String KEY="owner.startDevelopmentTask";
    private static final class Fixture implements AutoCloseable {
        final ProjectMemoryTaskAppJourneyNativeTest.Model model;
        final ProjectMemoryTaskAppJourneyNativeTest.Setup original;
        final PocketWorldController controller;final ExecutorService io;
        Fixture(boolean hold)throws Exception{model=new ProjectMemoryTaskAppJourneyNativeTest.Model(hold);original=new ProjectMemoryTaskAppJourneyNativeTest.Setup(model);controller=new PocketWorldController(new ComposeActivity(),original.app);var field=PocketWorldController.class.getDeclaredField("io");field.setAccessible(true);io=(ExecutorService)field.get(controller);}
        void settle()throws Exception{io.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        JSONObject start(boolean project,boolean retry)throws Exception{String id=controller.requestProjectStart(new JSONObject(original.input.toString()),project,retry);settle();assertEquals(id,controller.getState().getModuleResultInputs().get(KEY).getString("_requestId"));return controller.getState().getModuleResults().get(KEY);}
        void sent()throws Exception{assertTrue("original HTTP was not sent",model.first.await(10,TimeUnit.SECONDS));}
        public void close(){model.release.countDown();controller.detach();model.close();}
    }
    @Test public void explicitControllerChoiceReachesProjectOnlyRunAndClosingUiHandleDoesNotCancelIt()throws Exception{try(Fixture f=new Fixture(true)){
        JSONObject ack=f.start(true,false);assertNotNull(ack);f.sent();JSONObject task=f.original.app.tasks.get(ack.getString("taskId"),false);assertEquals("project_only",task.getJSONObject("projectMemoryRead").getString("scopeMode"));assertTrue(f.model.requests.get(0).contains("PROJECT_ONLY_NATIVE_ALPHA_72"));assertFalse(f.model.requests.get(0).contains("OWNER_GLOBAL_PRIVATE_MUST_NOT_FLOW_84"));
        f.controller.releaseProjectStartConsent(f.original.input.getString("clientMessageId"));f.model.release.countDown();task=f.original.await(ack.getString("taskId"));assertEquals(task.toString(),"completed",task.getString("status"));assertEquals(1,f.model.requests.size());
    }}
    @Test public void uncheckedControllerChoiceKeepsTheOriginalOwnerGlobalDefault()throws Exception{try(Fixture f=new Fixture(false)){
        JSONObject ack=f.start(false,false);JSONObject task=f.original.await(ack.getString("taskId"));assertEquals("completed",task.getString("status"));assertFalse(task.has("projectMemoryRead"));assertTrue(f.model.requests.get(0).contains("OWNER_GLOBAL_PRIVATE_MUST_NOT_FLOW_84"));assertFalse(f.model.requests.get(0).contains("PROJECT_ONLY_NATIVE_ALPHA_72"));
    }}
    @Test public void exactUnknownRetryReusesFrozenConsentAndCannotAddNewlySavedProjectRecords()throws Exception{try(Fixture f=new Fixture(true)){
        JSONObject first=f.start(true,false);f.sent();JSONObject before=f.original.app.tasks.get(first.getString("taskId"),false).getJSONObject("projectMemoryRead");var scopes=f.original.scopes;var choices=scopes.choicesForOwner();JSONObject choice=null;for(int i=0;i<choices.length();i++)if(f.original.input.getString("checkoutId").equals(choices.getJSONObject(i).getString("checkoutId")))choice=choices.getJSONObject(i);assertNotNull(choice);var selected=scopes.selectForOwner(choice.getString("checkoutId"),choice.getString("projectId"),choice.getString("revision"));scopes.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("text","FUTURE_RECORD_NOT_IN_FROZEN_NATIVE_CONSENT_95").put("core",true)));
        JSONObject duplicate=f.start(true,true);assertEquals(first.getString("taskId"),duplicate.getString("taskId"));JSONObject after=f.original.app.tasks.get(first.getString("taskId"),false).getJSONObject("projectMemoryRead");assertEquals(before.getString("consentId"),after.getString("consentId"));assertEquals(1,after.getJSONArray("selectedRecords").length());assertEquals(1,f.original.app.tasks.list().length());f.model.release.countDown();assertEquals("completed",f.original.await(first.getString("taskId")).getString("status"));for(String request:f.model.requests)assertFalse(request.contains("FUTURE_RECORD_NOT_IN_FROZEN_NATIVE_CONSENT_95"));
    }}
    @Test public void lostOpaqueConsentCannotBeRecreatedFromRetryMetadataOrGroupWindow()throws Exception{try(Fixture f=new Fixture(false)){
        assertNull(f.start(true,true));assertTrue(f.controller.getState().getModuleResultInputs().get(KEY).getBoolean("_failed"));assertTrue(f.controller.getState().getModuleErrors().get(KEY).contains("未从metadata恢复"));assertEquals(0,f.original.app.tasks.list().length());assertEquals(0,f.model.requests.size());
        f.controller.enterOwnerDomain("groups");assertThrows(SecurityException.class,()->f.controller.requestProjectStart(f.original.input,true,false));assertEquals(0,f.original.app.tasks.list().length());
    }}
    @Test public void sameClientCannotChangePayloadOrSilentlyReissueWiderConsent()throws Exception{try(Fixture f=new Fixture(true)){
        JSONObject ack=f.start(true,false);f.sent();f.controller.requestProjectStart(new JSONObject(f.original.input.toString()).put("goal","替换原内容"),true,true);f.settle();assertTrue(f.controller.getState().getModuleResultInputs().get(KEY).getBoolean("_failed"));assertEquals(1,f.original.app.tasks.list().length());assertEquals(1,f.model.requests.size());f.model.release.countDown();assertEquals("completed",f.original.await(ack.getString("taskId")).getString("status"));
    }}
    @Test public void closingQueuedOwnerWindowBeforeAdmissionCreatesNoTaskOrGrant()throws Exception{try(Fixture f=new Fixture(false)){
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);f.io.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException expected){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));f.controller.requestProjectStart(f.original.input,true,false);f.controller.detach();release.countDown();assertTrue(f.io.awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,f.original.app.tasks.list().length());assertEquals(0,f.model.requests.size());assertThrows(SecurityException.class,()->f.controller.requestProjectStart(f.original.input,true,false));
    }}
}
