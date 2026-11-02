package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original phone-mode SQL grant/lease/budget. No physical action or script process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskStoreScriptPhoneNativeAdmissionTest {
    private static final class Fixture implements AutoCloseable {
        final TaskStore tasks;final SQLiteDatabase db;final RunControl original=new RunControl();
        final String task,run,parent;final int epoch;final RunJournal journal;
        Fixture(String mode)throws Exception {
            var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            task=(mode.equals("capabilities")?tasks.createCapabilityTask("原本机脚本手机回调",""):tasks.create("原本机脚本手机回调",mode)).getString("id");
            tasks.initializeExecutionScope(task,8);JSONObject admitted=tasks.startAttempt(task,mode,8);run=admitted.getString("runId");epoch=admitted.getInt("coordinatorEpoch");original.bindHostRun(task,run,epoch);
            tasks.authorizeTools(task,run,new JSONArray().put("skills_execute"));journal=tasks.journal(task,run,epoch,original);parent=journal.beginOperation("skills_execute",new JSONObject().put("scriptPath","task.py"));
        }
        int remaining()throws Exception {try(Cursor row=db.rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{task,run})){assertTrue(row.moveToFirst());JSONObject grant=new JSONObject(row.getString(0));assertEquals(new JSONArray().put("skills_execute").toString(),grant.getJSONArray("tools").toString());return grant.getInt("remainingOperations");}}
        TaskStore.ScriptPhoneScope scope(Set<String> tools)throws Exception{return tasks.scriptPhoneScope(task,run,epoch,parent,original,tools);}
        public void close(){original.cancel();db.close();}
    }
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static void rejected(Checked action)throws Exception {try{action.run();fail("Original native authority must reject");}catch(SecurityException|IllegalStateException expected){}}
    private static void inheritedPhoneMode(String mode)throws Exception {
        try(Fixture f=new Fixture(mode)){
            int before=f.remaining();
            try(var scope=f.scope(Set.of("phone_observe","phone_tap"))){
                scope.invoke("phone_observe",()->{String child=f.journal.beginOperation("phone_observe",new JSONObject());f.tasks.requirePreparedCapability(f.task,f.run,f.epoch,child,"phone_observe",new JSONObject());f.journal.finishOperation(child,new JSONObject().put("ok",true).put("accepted",true));return child;});
                JSONObject args=new JSONObject().put("observationId","actual-current").put("x",10).put("y",20);
                scope.invoke("phone_tap",()->{String child=f.journal.beginOperation("phone_tap",args);f.tasks.requirePreparedCapability(f.task,f.run,f.epoch,child,"phone_tap",args);f.journal.finishOperation(child,new JSONObject().put("ok",true).put("accepted",true));return child;});
            }
            assertEquals(before-2,f.remaining());assertEquals(3,f.tasks.get(f.task,false).getJSONObject("executionScope").getInt("toolsUsed"));
            assertEquals(3,f.tasks.get(f.task,false).getJSONArray("operations").length());assertTrue(f.tasks.hasUnresolved(f.task));
            rejected(()->f.journal.beginOperation("phone_observe",new JSONObject()));
            f.journal.finishOperation(f.parent,new JSONObject().put("ok",true));assertFalse(f.tasks.hasUnresolved(f.task));
        }
    }
    @Test public void mainScreenUsesItsOriginalPhoneGrantWithoutMintingNamedTools()throws Exception {inheritedPhoneMode("main_screen");}
    @Test public void backgroundUsesItsOriginalPhoneGrantWithoutMintingNamedTools()throws Exception {inheritedPhoneMode("background");}
    @Test public void removedPhoneLeaseRevokesTheFactoryAndExistingCallbackBeforeDebit()throws Exception {
        try(Fixture f=new Fixture("main_screen");var scope=f.scope(Set.of("phone_observe","phone_tap"))){int before=f.remaining();f.db.delete("leases","resource_id='phone.local'",null);rejected(()->scope.invoke("phone_observe",()->f.journal.beginOperation("phone_observe",new JSONObject())));assertEquals(before,f.remaining());scope.close();rejected(()->f.scope(Set.of("phone_observe")));}
    }
    @Test public void mismatchedOrExpiredLeaseCannotBeUsedAsTheOriginalPhoneScope()throws Exception {
        try(Fixture f=new Fixture("main_screen")){
            ContentValues wrong=new ContentValues();wrong.put("epoch",f.epoch+1);f.db.update("leases",wrong,"resource_id='phone.local'",null);rejected(()->f.scope(Set.of("phone_observe")));
            ContentValues expired=new ContentValues();expired.put("epoch",f.epoch);expired.put("expires_at",System.currentTimeMillis()-1);f.db.update("leases",expired,"resource_id='phone.local'",null);rejected(()->f.scope(Set.of("phone_observe")));
        }
    }
    @Test public void namedQueryAndExtractRemainDeniedWhenTheNativeGrantNeverIncludedThem()throws Exception {
        try(Fixture f=new Fixture("main_screen")){int before=f.remaining();for(String name:new String[]{"phone_query","phone_extract"})rejected(()->f.scope(Set.of("phone_observe",name)));assertEquals(before,f.remaining());}
    }
    @Test public void capabilitiesAssistantCannotBorrowAnImplicitPhoneGrant()throws Exception {
        try(Fixture f=new Fixture("capabilities")){int before=f.remaining();rejected(()->f.scope(Set.of("phone_observe","phone_tap")));assertEquals(before,f.remaining());}
    }
}
