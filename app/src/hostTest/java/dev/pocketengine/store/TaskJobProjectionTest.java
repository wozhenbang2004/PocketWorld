package dev.pocketengine.store;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.remote.RemoteTaskRequest;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;

/** Real original TaskStore/SQLite authority. Synthetic receipt/header payloads exercise
 * bounded projection only; they are not actual media, remote-provider or phone execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskJobProjectionTest {
    private TaskStore tasks;private SQLiteDatabase db;private String conversation;
    @Before public void open()throws Exception {var context=RuntimeEnvironment.getApplication();context.deleteDatabase("pocket-world.db");tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);tasks.conversationRepository().initializeEmpty();conversation=conversation();}
    @After public void close(){db.close();}
    private String conversation()throws Exception {String id=UUID.randomUUID().toString();tasks.conversationRepository().saveConversation(new JSONObject().put("conversationId",id).put("title","Original job projection fixture").put("createdAt",1));return id;}
    private JSONObject task(String conversation)throws Exception {return tasks.startAttempt(tasks.createCapabilityTask("Original projection fixture",conversation).getString("id"),"capabilities",60);}
    private RunJournal journal(JSONObject task,String tool)throws Exception {String id=task.getString("id"),run=task.getString("runId");RunControl control=new RunControl();control.bindHostRun(id,run,task.getInt("coordinatorEpoch"));tasks.authorizeTools(id,run,new JSONArray().put(tool));return tasks.journal(id,run,task.getInt("coordinatorEpoch"),control);}
    private String row(JSONObject task,String tool,JSONObject receipt)throws Exception {RunJournal original=journal(task,tool);String operation=original.beginOperation(tool,new JSONObject());if(receipt!=null)original.finishOperation(operation,receipt);return operation;}
    private JSONObject first(JSONObject page)throws Exception{return page.getJSONArray("entries").getJSONObject(0);}
    private String rawHeader(String id)throws Exception {try(Cursor row=db.rawQuery("SELECT ui_header FROM tasks WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());return row.getString(0);}}
    private void header(String id,JSONObject header){ContentValues values=new ContentValues();values.put("ui_header",header.toString());assertEquals(1,db.update("tasks",values,"task_id=?",new String[]{id}));}
    private long count(String table){try(Cursor row=db.rawQuery("SELECT COUNT(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}

    @Test public void conversationDirectoryPreservesDomainsUnknownAndOriginalEpochWithoutWritingAuthority()throws Exception {
        JSONObject anchor=task(conversation),media=task(conversation),team=task(conversation),other=task(conversation());String remote=row(anchor,"remote_job",new JSONObject().put("status","unknown").put("processStarted",JSONObject.NULL).put("pendingApprovals",new JSONArray().put(new JSONObject().put("id","fixture"))));
        row(media,"media_generate_image",new JSONObject().put("status","completed"));row(team,"teams_start",new JSONObject().put("code","approval_required"));String excluded=row(other,"remote_job",new JSONObject().put("status","completed"));
        long operations=count("operations"),runs=count("runs"),leases=count("leases"),events=count("task_events");JSONObject page=tasks.taskHistoryPage(anchor.getString("id"),"jobs","",40,65536);assertFalse(page.getBoolean("executionAuthorized"));assertFalse(page.getBoolean("liveStatusQueried"));assertFalse(page.getBoolean("completeHistory"));assertEquals(3,page.getJSONArray("entries").length());Set<String> domains=new HashSet<>();
        for(int i=0;i<page.getJSONArray("entries").length();i++){JSONObject entry=page.getJSONArray("entries").getJSONObject(i);domains.add(entry.getString("domain"));assertNotEquals(excluded,entry.getString("operationId"));assertTrue(entry.getBoolean("observingOnly"));assertFalse(entry.has("arguments"));if(remote.equals(entry.getString("operationId"))){assertEquals(anchor.getString("runId"),entry.getString("runId"));assertEquals(anchor.getInt("coordinatorEpoch"),entry.getInt("epoch"));assertEquals("unknown",entry.getJSONObject("reportedFacts").getString("originalReceiptStatus"));assertFalse(entry.getJSONObject("reportedFacts").has("processStarted"));assertEquals(1,entry.getJSONObject("reportedFacts").getInt("reportedPendingApprovalCount"));}if(entry.getString("domain").equals("child_work"))assertEquals("approval_intent",entry.getString("entryKind"));}
        assertEquals(Set.of("remote","media","child_work"),domains);assertEquals(operations,count("operations"));assertEquals(runs,count("runs"));assertEquals(leases,count("leases"));assertEquals(events,count("task_events"));
    }
    @Test public void headerBudgetResumesBeforeUnreadRowAndKeepsFrozenUpperWithoutDuplicates()throws Exception {
        JSONObject anchor=task(conversation);Set<String> original=new HashSet<>();for(int i=0;i<28;i++){JSONObject task=task(conversation);String id=task.getString("id");original.add(row(task,"media_generate_image",new JSONObject().put("status","running")));header(id,new JSONObject(rawHeader(id)).put("projectionOnlyPadding","x".repeat(3100)));}
        JSONObject first=tasks.taskHistoryPage(anchor.getString("id"),"jobs","",40,65536);assertTrue(first.getBoolean("hasMore"));assertTrue(first.getBoolean("readLimited"));assertTrue(first.getJSONArray("entries").length()<28);String tail=row(anchor,"remote_job",new JSONObject().put("status","unknown"));Set<String> seen=new HashSet<>();JSONObject page=first;int pages=0;
        do{pages++;assertTrue(pages<5);assertTrue(page.getInt("headerBytesRead")<=65536);JSONArray entries=page.getJSONArray("entries");for(int i=0;i<entries.length();i++)assertTrue(seen.add(entries.getJSONObject(i).getString("operationId")));String cursor=page.getString("nextCursor");if(cursor.isEmpty())break;page=tasks.taskHistoryPage(anchor.getString("id"),"jobs",cursor,40,65536);}while(true);
        assertEquals(original,seen);assertFalse(seen.contains(tail));assertEquals(tail,first(tasks.taskHistoryPage(anchor.getString("id"),"jobs","",40,65536)).getString("operationId"));
    }
    @Test public void emptyFilteredPageAndOversizedOrMalformedBodiesRetainContinuationAndUnknownIdentity()throws Exception {
        JSONObject anchor=task(conversation),other=task(conversation());String wanted=row(anchor,"remote_job",new JSONObject().put("status","unknown").put("fixtureOnlyLargeBody","x".repeat(3000)));row(other,"remote_job",new JSONObject().put("status","running"));JSONObject filtered=tasks.taskHistoryPage(anchor.getString("id"),"jobs","",1,1024);assertEquals(0,filtered.getJSONArray("entries").length());assertTrue(filtered.getBoolean("hasMore"));JSONObject page=tasks.taskHistoryPage(anchor.getString("id"),"jobs",filtered.getString("nextCursor"),1,1024);assertEquals(wanted,first(page).getString("operationId"));assertEquals("read_limited",first(page).getString("status"));assertFalse(first(page).has("receipt"));
        db.execSQL("UPDATE operations SET receipt=? WHERE operation_id=?",new Object[]{"{broken",wanted});JSONObject broken=first(tasks.taskHistoryPage(anchor.getString("id"),"jobs",filtered.getString("nextCursor"),1,1024));assertEquals(wanted,broken.getString("operationId"));assertEquals("source_unreadable",broken.getString("status"));assertFalse(broken.getBoolean("projectLogCandidate"));
    }
    @Test public void cursorCannotCrossTaskConversationOrForgottenSourceAndPrivateHeaderIsRequired()throws Exception {
        JSONObject anchor=task(conversation),same=task(conversation);row(anchor,"remote_job",new JSONObject().put("status","completed"));row(anchor,"media_generate_video",new JSONObject().put("status","completed"));String id=anchor.getString("id"),cursor=tasks.taskHistoryPage(id,"jobs","",1,1024).getString("nextCursor");assertFalse(cursor.isEmpty());assertThrows(SecurityException.class,()->tasks.taskHistoryPage(same.getString("id"),"jobs",cursor,1,1024));
        JSONObject original=new JSONObject(rawHeader(id));header(id,new JSONObject(original.toString()).put("spaceId","shared"));assertThrows(SecurityException.class,()->tasks.taskHistoryPage(id,"jobs","",1,1024));header(id,new JSONObject(original.toString()).put("conversationId",conversation()));assertThrows(SecurityException.class,()->tasks.taskHistoryPage(id,"jobs",cursor,1,1024));header(id,original);tasks.conversationRepository().deleteConversation(conversation,"owner_fixture_forget");assertThrows(SecurityException.class,()->tasks.taskHistoryPage(id,"jobs","",1,1024));
    }
    @Test public void originalRemoteDelegationCollapsesOnlyItsProvenSameConversationChild()throws Exception {
        JSONObject parent=task(conversation);String id=parent.getString("id"),run=parent.getString("runId");int epoch=parent.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("remote_start_task"));RunJournal journal=tasks.journal(id,run,epoch,original);
        JSONObject arguments=new JSONObject().put("profileId","computer").put("bindingHash","a".repeat(64)).put("environmentId","fixture").put("environmentBinding","b".repeat(64)).put("worker","codex_app_server").put("prompt","Original controlled delegation").put("model","fixture-model").put("readOnly",true).put("timeoutSeconds",20);
        String operation=journal.beginOperation("remote_start_task",arguments),child=tasks.prepareRemoteDelegation(id,run,epoch,operation,RemoteTaskRequest.first(arguments),original).getString("taskId");tasks.updateExternal(child,new JSONObject().put("profileId","computer").put("serviceBase","http://127.0.0.1:1").put("environmentId","fixture").put("worker","codex_app_server").put("operationId",child).put("id","controlled-original-job").put("status","failed").put("pendingApprovals",new JSONArray()));journal.finishOperation(operation,new JSONObject().put("hostTaskId",child).put("ok",true));assertEquals(id,tasks.requirePrivateRemoteChild(child).getString("parentTaskId"));
        JSONObject page=tasks.taskHistoryPage(id,"jobs","",40,65536);assertEquals(1,page.getInt("collapsedOriginalDelegations"));assertEquals(1,page.getJSONArray("entries").length());assertEquals(child,first(page).getString("taskId"));assertEquals("failed",first(page).getJSONObject("reportedFacts").getString("originalReceiptStatus"));
        header(child,new JSONObject(rawHeader(child)).put("conversationId",conversation()));page=tasks.taskHistoryPage(id,"jobs","",40,65536);assertEquals(0,page.getInt("collapsedOriginalDelegations"));assertEquals(operation,first(page).getString("operationId"));assertEquals("delegation_intent",first(page).getString("entryKind"));assertFalse(first(page).getBoolean("liveStatusQueried"));
    }
}
