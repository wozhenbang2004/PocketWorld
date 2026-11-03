package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SQL/owner/HTTP/JobManager/Popen; synthetic pinned Codex protocol peers only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteCodexChildOwnerJourneyTest {
    static JSONObject drain(RemoteEventCursorJourneyTest.Fixture f,JSONObject view)throws Exception {
        for(int n=0;n<5&&view.optJSONObject("eventRead")!=null&&view.getJSONObject("eventRead").optBoolean("hasMore");n++)view=f.refresh(view);
        assertEquals(view.toString(),"confirmed",view.getString("eventReadStatus"));assertFalse(view.getJSONObject("eventRead").getBoolean("hasMore"));return view;
    }
    static SQLiteDatabase db(RemoteEventCursorJourneyTest.Fixture f)throws Exception{var field=dev.pocketengine.store.TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(f.tasks);}
    static JSONObject originalDocument(RemoteEventCursorJourneyTest.Fixture f)throws Exception{try(var row=db(f).rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{f.task})){assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));}}
    static void replaceDocument(RemoteEventCursorJourneyTest.Fixture f,JSONObject document)throws Exception{ContentValues values=new ContentValues();values.put("document",document.toString());assertEquals(1,db(f).update("tasks",values,"task_id=?",new String[]{f.task}));}
    static void close(RemoteEventCursorJourneyTest.Fixture f)throws Exception{try{f.close();}finally{db(f).close();}}
    @Test public void actualNativePagesKeepTwoPublicChildrenAndOriginalApprovalWithoutAnotherRunOrParentUsage()throws Exception {
        var f=new RemoteEventCursorJourneyTest.Fixture("codex_app_server",true);try{
            JSONObject view=drain(f,f.open()),read=view.getJSONObject("eventRead"),scope=read.getJSONObject("scope");assertEquals(RemoteJobEvents.CODEX_CHILD_VERSION,scope.getString("nativeRuntimeVersion"));assertEquals(f.source().getString("turnId"),scope.getString("turnId"));
            JSONArray rows=read.getJSONArray("events");java.util.Set<String> children=new java.util.HashSet<>();int chunks=0;
            for(int i=0;i<rows.length();i++){JSONObject event=rows.getJSONObject(i),source=RemoteJobEvents.sourceView(event,scope);if(source.optString("kind").equals("child")){children.add(source.getString("nativeChildThreadId"));if(event.optString("nativeType").equals("item/agentMessage/delta"))chunks++;assertFalse(source.getBoolean("executionAuthorized"));assertFalse(source.getBoolean("childSessionControl"));}}
            assertEquals(java.util.Set.of("codex-child-a","codex-child-b"),children);assertEquals(4,chunks);assertFalse(read.toString().contains("NEVER_EXPOSE_CODEX_CHILD_THINKING"));assertFalse(read.toString().contains("PRIVATE_CHILD_"));assertFalse(read.toString().contains("999999"));assertEquals(1,f.source().getJSONArray("pendingApprovals").length());assertEquals("wire-read-approval",f.source().getJSONArray("pendingApprovals").getJSONObject(0).getString("requestId"));
            f.waitFile("workspace/child-declined-count");assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(0,f.count("question-post-count"));String generation=read.getString("generation");long cursor=read.getLong("nextSequence");String run=f.source().getString("runId");
            f.owner.setForeground(f.nativeOwner,false);f.remote.close();f.host.close();db(f).close();f.boot(false);view=drain(f,f.open());assertEquals(generation,view.getJSONObject("eventRead").getString("generation"));assertEquals(cursor,view.getJSONObject("eventRead").getLong("nextSequence"));assertEquals(run,f.source().getString("runId"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(1,f.source().getJSONArray("pendingApprovals").length());
        }finally{close(f);}
    }
    @Test public void corruptChildParentOnAuthenticatedPageCannotAdvanceSqlCursorOrBecomeMainText()throws Exception {
        var f=new RemoteEventCursorJourneyTest.Fixture("codex_app_server",true);try{
            f.marker("corrupt-child-parent");JSONObject view=f.open();long before=0;for(int n=0;n<5&&view.optString("eventReadStatus").equals("confirmed")&&view.getJSONObject("eventRead").optBoolean("hasMore");n++){before=f.tasks.originalRemoteEventRead(f.task).getLong("nextSequence");view=f.refresh(view);}assertEquals("unconfirmed",view.getString("eventReadStatus"));JSONObject retained=f.tasks.originalRemoteEventRead(f.task);assertEquals(before,retained==null?0:retained.getLong("nextSequence"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));
            f.remove("corrupt-child-parent");view=drain(f,f.refresh(view));assertTrue(view.getJSONObject("eventRead").getLong("nextSequence")>0);assertTrue(view.getJSONObject("eventRead").getJSONArray("events").toString().contains("codex-child-a"));assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));
        }finally{close(f);}
    }
    @Test public void exactReceivedPageCannotCommitAfterCurrentNativeTurnOrRuntimeDriftsInsideOriginalSqlBoundary()throws Exception {
        var f=new RemoteEventCursorJourneyTest.Fixture("codex_app_server",true);try{
            JSONObject source=f.source(),original=originalDocument(f);String binding=f.remote.profileBindingHash(source.getString("profileId"));RemoteJobEvents.Page page=f.remote.readOriginalJobEvents(source,binding,null,()->{});f.remote.close();
            for(String drift:new String[]{"turn","version"}){
                JSONObject changed=new JSONObject(original.toString()),evidence=changed.getJSONObject("evidence");if(drift.equals("turn")){evidence.put("turnId","changed-turn");evidence.getJSONObject("modelRuntime").put("turnId","changed-turn");}else evidence.getJSONObject("modelRuntime").put("runtimeVersion","0.165.0");
                try{replaceDocument(f,changed);assertThrows(drift,SecurityException.class,()->f.tasks.recordOriginalRemoteEventPage(f.task,page));assertNull(f.tasks.originalRemoteEventRead(f.task));}finally{replaceDocument(f,original);}
            }
            assertTrue(f.tasks.recordOriginalRemoteEventPage(f.task,page).getLong("nextSequence")>0);assertEquals(1,f.count("submit-count"));assertEquals(0,f.count("approval-post-count"));assertEquals(source.getString("runId"),f.source().getString("runId"));
        }finally{close(f);}
    }
}
