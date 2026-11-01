package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class ReviewedRealtimeTaskTest {
    private final List<TaskStore> stores=new ArrayList<>();private Context context;private TaskStore store;
    private String id,text;private JSONObject source;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();store=open();id=UUID.randomUUID().toString();text="整理明天的安排";
        source=new JSONObject().put("kind","owner_reviewed_realtime").put("commitId",id).put("sessionId",UUID.randomUUID().toString()).put("turnId",UUID.randomUUID().toString()).put("finalRevision",4L)
            .put("segmentId","seg-1").put("participantIdentity","owner-track-reported").put("trackSid","TR-test").put("protocol","text_stream").put("contentHash",hash(text)).put("sourceKind","reported_local_microphone").put("identityVerified",false).put("reviewedBy","owner").put("spaceId","private");
    }
    private TaskStore open(){TaskStore next=new TaskStore(context);stores.add(next);return next;}
    private SQLiteDatabase db(TaskStore store)throws Exception{java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);}
    @After public void close()throws Exception{for(TaskStore item:stores)if(db(item).isOpen())db(item).close();}
    @Test public void creationOnlyPersistsAwaitingOwnerTaskWithNoRunOrOperation()throws Exception{
        JSONObject task=store.createReviewedRealtimeTask(id,text,source);assertEquals(id,task.getString("id"));assertEquals("awaiting_user",task.getString("status"));assertEquals("assistant",task.getString("kind"));assertEquals("capabilities",task.getString("executionMode"));assertEquals("owner_reviewed_realtime",task.getJSONObject("origin").getString("kind"));assertEquals(0,task.getInt("coordinatorEpoch"));
        for(String table:new String[]{"runs","operations","leases"})try(Cursor cursor=db(store).rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));}
    }
    @Test public void duplicateReturnsSameTaskAndNeverRegressesExecutionState()throws Exception{
        assertTrue(store.createReviewedRealtimeTask(id,text,source).getBoolean("created"));store.startAttempt(id,"capabilities",4);JSONObject running=store.get(id,false);JSONObject duplicate=store.createReviewedRealtimeTask(id,text,new JSONObject(source.toString()));assertFalse(duplicate.getBoolean("created"));assertEquals(running.getString("status"),duplicate.getString("status"));assertEquals(running.getString("runId"),duplicate.getString("runId"));assertEquals(1,duplicate.getInt("coordinatorEpoch"));
    }
    @Test public void sameCommitCannotChangeTextRevisionSessionOrParticipant()throws Exception{
        store.createReviewedRealtimeTask(id,text,source);String replacement="删除安排";assertThrows(SecurityException.class,()->store.createReviewedRealtimeTask(id,replacement,new JSONObject(source.toString()).put("contentHash",hash(replacement))));
        for(String field:new String[]{"sessionId","turnId"}){JSONObject changed=new JSONObject(source.toString()).put(field,UUID.randomUUID().toString());assertThrows(SecurityException.class,()->store.createReviewedRealtimeTask(id,text,changed));}
        assertThrows(SecurityException.class,()->store.createReviewedRealtimeTask(id,text,new JSONObject(source.toString()).put("finalRevision",5L)));assertThrows(SecurityException.class,()->store.createReviewedRealtimeTask(id,text,new JSONObject(source.toString()).put("participantIdentity","someone-else")));
    }
    @Test public void invalidOrForgedSourceCannotBecomeOwnerTask()throws Exception{
        for(JSONObject bad:new JSONObject[]{new JSONObject(source.toString()).put("sourceKind","remote_or_unverified"),new JSONObject(source.toString()).put("identityVerified",true),new JSONObject(source.toString()).put("kind","channel"),new JSONObject(source.toString()).put("finalRevision",0),new JSONObject(source.toString()).put("finalRevision",1.5),new JSONObject(source.toString()).put("contentHash","wrong"),new JSONObject(source.toString()).put("tool","send_secret")})assertThrows(SecurityException.class,()->store.createReviewedRealtimeTask(id,text,bad));
        assertNull(store.get(id,false));
    }
    @Test public void missingLookupDoesNotCreateAndPersistenceReopenKeepsIdentity()throws Exception{
        assertNull(store.reviewedRealtimeTask(id,text,source));store.createReviewedRealtimeTask(id,text,source);db(store).close();TaskStore reopened=open();JSONObject found=reopened.reviewedRealtimeTask(id,text,source);assertEquals(id,found.getString("id"));assertEquals("awaiting_user",found.getString("status"));assertFalse(reopened.createReviewedRealtimeTask(id,text,source).getBoolean("created"));
    }
    @Test public void concurrentCreateHasOneDurableWinner()throws Exception{
        TaskStore second=open();ExecutorService workers=Executors.newFixedThreadPool(2);
        try{Future<JSONObject> first=workers.submit(()->store.createReviewedRealtimeTask(id,text,new JSONObject(source.toString())));Future<JSONObject> other=workers.submit(()->second.createReviewedRealtimeTask(id,text,new JSONObject(source.toString())));JSONObject a=first.get(),b=other.get();assertEquals(id,a.getString("id"));assertEquals(id,b.getString("id"));assertNotEquals(a.getBoolean("created"),b.getBoolean("created"));}
        finally{workers.shutdownNow();}
    }
    private static String hash(String value)throws Exception{StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
}
