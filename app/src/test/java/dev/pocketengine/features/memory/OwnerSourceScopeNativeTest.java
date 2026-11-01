package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original AtomicFile importer and native channel registration/run entry, no provider/UI proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerSourceScopeNativeTest {
    private static String id(){return UUID.randomUUID().toString();}
    @Test public void legacyImportedChannelWithConversationLinkResumesWithoutPrivateOwnerSourcePrivilege()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");java.io.File legacy=new java.io.File(app.getFilesDir(),"tasks.json");assertFalse("fixture must not overwrite an unrelated original file",legacy.exists());String task=id(),oldRun=id();
        JSONObject origin=new JSONObject().put("kind","channel").put("requestId",id()).put("eventId",id()).put("profileId","native-profile").put("messageId","native-message").put("senderId","bound-owner").put("authenticatedBy","trusted_relay").put("bindingEpoch",id()).put("taskGrantEpoch",id()).put("ownerId","owner").put("spaceId","private");
        JSONObject old=new JSONObject().put("id",task).put("goal","原渠道目标").put("kind","assistant").put("executionMode","capabilities").put("actorId","owner").put("spaceId","private").put("conversationId","retained-legacy-conversation-link").put("origin",origin).put("runId",oldRun).put("coordinatorEpoch",1).put("status","awaiting_user").put("cancelRequested",false).put("events",new JSONArray()).put("evidence",new JSONObject()).put("createdAt",1L).put("updatedAt",1L);
        Files.write(legacy.toPath(),new JSONArray().put(old).toString().getBytes(StandardCharsets.UTF_8));SQLiteDatabase db=null;
        try{
            TaskStore tasks=new TaskStore(app);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);assertFalse(tasks.registerChannelTask(task,"原渠道目标",origin).getBoolean("created"));assertEquals("retained-legacy-conversation-link",tasks.channelTask(task,origin).getString("conversationId"));
            JSONObject next=tasks.startAttempt(task,"capabilities",5);assertNotEquals(oldRun,next.getString("runId"));RunControl control=new RunControl();control.bindHostRun(task,next.getString("runId"),next.getInt("coordinatorEpoch"));assertEquals(next.getString("runId"),tasks.requireCapabilityAttempt(task,control).getString("runId"));assertFalse(OwnerSourceRunContext.acceptsPrivateOwnerProjection(next));
            assertThrows(SecurityException.class,()->tasks.openOwnerInputMailbox(task,next.getString("runId"),next.getInt("coordinatorEpoch"),"retained-legacy-conversation-link",control));assertThrows(SecurityException.class,()->tasks.recordOwnerSourceExposure(task,next.getString("runId"),next.getInt("coordinatorEpoch"),control,new JSONArray()));
            try(var row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"run.owner_source_context."+next.getString("runId")})){assertTrue(row.moveToFirst());JSONObject fact=new JSONObject(row.getString(0));assertTrue(fact.getBoolean("trackingComplete"));assertEquals(0,fact.getJSONArray("carriers").length());}assertEquals("channel",tasks.get(task,false).getJSONObject("origin").getString("kind"));assertEquals(0,tasks.get(task,false).getJSONArray("operations").length());
        }finally{if(db!=null)db.close();Files.deleteIfExists(legacy.toPath());}
    }
    @Test public void nativeProjectionPredicateRejectsOtherDomainsAndUnicodeBlankWithoutMintingAnyAuthority()throws Exception{
        JSONObject task=new JSONObject().put("kind","assistant").put("actorId","owner").put("spaceId","private").put("conversationId","native-conversation");assertTrue(OwnerSourceRunContext.acceptsPrivateOwnerProjection(task));
        for(String kind:new String[]{"channel","group"})assertFalse(OwnerSourceRunContext.acceptsPrivateOwnerProjection(new JSONObject(task.toString()).put("origin",new JSONObject().put("kind",kind))));
        assertFalse(OwnerSourceRunContext.acceptsPrivateOwnerProjection(new JSONObject(task.toString()).put("actorId","other")));assertFalse(OwnerSourceRunContext.acceptsPrivateOwnerProjection(new JSONObject(task.toString()).put("spaceId","group")));assertFalse(OwnerSourceRunContext.acceptsPrivateOwnerProjection(new JSONObject(task.toString()).put("kind","phone")));assertFalse(OwnerSourceRunContext.acceptsPrivateOwnerProjection(new JSONObject(task.toString()).put("conversationId","\u2003\t")));assertTrue(OwnerSourceRunContext.acceptsPrivateOwnerProjection(new JSONObject(task.toString()).put("origin",new JSONObject().put("kind","automation"))));
    }
}
