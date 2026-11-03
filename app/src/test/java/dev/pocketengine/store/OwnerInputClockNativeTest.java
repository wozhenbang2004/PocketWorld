package dev.pocketengine.store;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Real SQLite/admission and java.time with explicitly changed test OS zones.
 * No physical second device, remote source declaration or network clock proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class OwnerInputClockNativeTest {
    private static SQLiteDatabase db(TaskStore store)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);}
    private static JSONObject context(OwnerInputMailboxTest.Fixture f,String...ids)throws Exception{return f.store.ownerInputClockContext(f.id,f.run,f.epoch,List.of(ids),f.control,false);}
    @Test public void nativeAdmissionIgnoresMessageClockAndDuplicateKeepsItsOriginalZoneAndTime()throws Exception {
        TimeZone previous=TimeZone.getDefault();OwnerInputMailboxTest.Fixture f=new OwnerInputMailboxTest.Fixture();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));JSONObject input=OwnerInputMailboxTest.message("original input").put("inputOriginClock",new JSONObject().put("ianaZoneId","Pacific/Honolulu").put("receivedAtEpochMillis",1));JSONObject admitted=f.append(input,"queue"),fact=admitted.getJSONObject("inputOriginClock");
            assertEquals("Asia/Shanghai",fact.getString("ianaZoneId"));assertEquals(admitted.getLong("acceptedAt"),fact.getLong("receivedAtEpochMillis"));assertEquals(java.time.Instant.ofEpochMilli(admitted.getLong("acceptedAt")).toString(),fact.getString("receivedAtUtc"));assertEquals("+08:00",fact.getString("utcOffset"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));JSONObject duplicate=f.append(input,"queue");assertTrue(duplicate.getBoolean("duplicate"));assertEquals(fact.toString(),duplicate.getJSONObject("inputOriginClock").toString());
            JSONObject pending=OwnerInputMailboxTest.message("not yet included");f.append(pending,"queue");JSONObject only=context(f,input.getString("id"));assertEquals("unique",only.getString("status"));assertEquals("Asia/Shanghai",only.getString("canonicalZoneId"));assertEquals(1,only.getInt("knownInputCount"));
            JSONObject mixed=context(f,input.getString("id"),pending.getString("id"));assertEquals("mixed",mixed.getString("status"));assertTrue(mixed.isNull("canonicalZoneId"));assertEquals(2,mixed.getJSONArray("zones").length());assertFalse(mixed.toString().contains("original input"));assertFalse(mixed.toString().contains("not yet included"));
            assertEquals("missing",context(f,input.getString("id"),UUID.randomUUID().toString()).getString("status"));assertEquals(0,f.store.get(f.id,false).getJSONArray("operations").length());assertEquals("pending",f.store.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(0).getString("queueState"));
        }finally{TimeZone.setDefault(previous);db(f.store).close();}
    }
    @Test public void legacyAndMissingFactsStayMissingAndCurrentFencesAreNeverSwallowed()throws Exception {
        OwnerInputMailboxTest.Fixture f=new OwnerInputMailboxTest.Fixture();try{
            JSONObject input=OwnerInputMailboxTest.message("legacy row");f.append(input,"queue");SQLiteDatabase database=db(f.store);JSONObject raw;
            try(var rows=database.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{f.id})){assertTrue(rows.moveToFirst());raw=new JSONObject(rows.getString(0));}
            raw.getJSONArray("ownerInputs").getJSONObject(0).remove("inputOriginClock");database.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{raw.toString(),f.id});
            JSONObject missing=context(f,input.getString("id"));assertEquals("missing",missing.getString("status"));assertEquals(0,missing.getInt("knownInputCount"));assertEquals(1,missing.getInt("missingInputCount"));assertTrue(missing.isNull("canonicalZoneId"));
            assertThrows(SecurityException.class,()->f.store.ownerInputClockContext(f.id,f.run,f.epoch+1,List.of(input.getString("id")),f.control,false));f.control.pause();assertThrows(IllegalStateException.class,()->context(f,input.getString("id")));f.control.resume();
            f.store.authorizeTools(f.id,f.run,new JSONArray().put("workspace_write"));var journal=f.store.journal(f.id,f.run,f.epoch,f.control);String operation=journal.beginOperation("workspace_write",new JSONObject());journal.finishOperation(operation,new JSONObject().put("status","unknown"));assertThrows(IllegalStateException.class,()->context(f,input.getString("id")));assertEquals(1,f.store.get(f.id,false).getJSONArray("operations").length());f.control.cancel();assertThrows(Exception.class,()->context(f,input.getString("id")));
        }finally{db(f.store).close();}
    }
    @Test public void explicitNewRunRetainsAdmissionTimeButReopenNeverRestoresAnOldGrant()throws Exception {
        TimeZone previous=TimeZone.getDefault();OwnerInputMailboxTest.Fixture f=new OwnerInputMailboxTest.Fixture();TaskStore reopened=null;
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));JSONObject message=OwnerInputMailboxTest.message("accepted before restart"),row=f.append(message,"queue");String fact=row.getJSONObject("inputOriginClock").toString();
            reopened=new TaskStore(RuntimeEnvironment.getApplication());TaskStore cold=reopened;assertThrows(SecurityException.class,()->cold.ownerInputClockContext(f.id,f.run,f.epoch,List.of(message.getString("id")),f.control,false));
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));JSONObject next=cold.startAttempt(f.id,"capabilities",12);RunControl current=new RunControl();current.bindHostRun(f.id,next.getString("runId"),next.getInt("coordinatorEpoch"));cold.openOwnerInputMailbox(f.id,next.getString("runId"),next.getInt("coordinatorEpoch"),f.conversation,current);
            JSONObject projected=cold.ownerInputClockContext(f.id,next.getString("runId"),next.getInt("coordinatorEpoch"),List.of(message.getString("id")),current,false);assertEquals("Asia/Tokyo",projected.getString("canonicalZoneId"));assertEquals(fact,cold.get(f.id,false).getJSONArray("ownerInputs").getJSONObject(0).getJSONObject("inputOriginClock").toString());assertFalse(current.isCancelled());assertEquals(0,cold.get(f.id,false).getJSONArray("operations").length());
        }finally{TimeZone.setDefault(previous);if(reopened!=null)db(reopened).close();db(f.store).close();}
    }
    @Test public void originalPhoneGoalAndScreenSupplementKeepTheirSeparateAdmissions()throws Exception {
        TimeZone previous=TimeZone.getDefault();TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));JSONObject task=store.create("original phone goal","background"),attempt=store.startAttempt(task.getString("id"),"background",12);String id=task.getString("id"),run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);store.openPhoneOwnerInputMailbox(id,run,epoch,original);
            assertEquals(task.getLong("createdAt"),task.getJSONObject("goalOriginClock").getLong("receivedAtEpochMillis"));assertEquals("Asia/Shanghai",store.ownerInputClockContext(id,run,epoch,List.of(),original,true).getString("canonicalZoneId"));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));JSONObject screen=OwnerInputMailboxTest.message("native selected screen supplement");store.appendPhoneOwnerInput(id,run,epoch,screen,original);assertEquals("mixed",store.ownerInputClockContext(id,run,epoch,List.of(screen.getString("id")),original,true).getString("status"));assertEquals(0,store.get(id,false).getJSONArray("operations").length());
        }finally{TimeZone.setDefault(previous);db(store).close();}
    }
}
