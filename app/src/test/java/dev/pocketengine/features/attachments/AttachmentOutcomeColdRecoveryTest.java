package dev.pocketengine.features.attachments;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.attachments.creation.DocumentDraft;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real original SQLite and metadata pages. No restored Bundle, replay or new persistence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AttachmentOutcomeColdRecoveryTest {
    private final List<TaskStore> opened=new ArrayList<>();
    private TaskStore tasks(Application app){TaskStore tasks=new TaskStore(app);opened.add(tasks);return tasks;}
    @After public void closeOriginalFixtureDatabases()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);for(TaskStore tasks:opened)((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    private static DocumentDraft draft(String name)throws Exception{return new DocumentDraft(new JSONObject().put("format","docx").put("name",name).put("body","Only the original source body"));}
    private static String admit(TaskStore tasks,String name)throws Exception{String id=UUID.randomUUID().toString();tasks.beginOwnerDocumentForOwner(id,draft(name));return id;}
    private static AttachmentOutcomeHost host(Application app,TaskStore tasks)throws Exception{return new AttachmentOutcomeHost(tasks,AttachmentRepository.get(app),new AttachmentsModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{})),(a,b,c)->{throw new AssertionError("Recovery cannot dispatch media");});}
    private static void writeFixture(TaskStore tasks,JSONObject row)throws Exception{var write=TaskStore.class.getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(tasks,row);}
    @Test public void coldGlobalPageRetainsCompletedFailedAndUnknownOriginalsWithoutChoosingTheNewest()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore first=tasks(app);
        String completed=admit(first,"Same name"),failed=admit(first,"Same name"),unknown=admit(first,"Same name");
        first.status(completed,"completed","Original output saved");first.status(failed,"failed","Output was not published");first.status(unknown,"unknown","Original result not confirmed");
        TaskStore reopened=tasks(app);JSONObject snapshot=host(app,reopened).snapshot(null,new RunControl()),page=snapshot.getJSONObject("documentActions");
        assertEquals("",snapshot.getString("taskId"));assertFalse(snapshot.has("originalDocumentActionId"));assertFalse(page.getBoolean("hasMore"));
        Set<String> ids=new HashSet<>(),statuses=new HashSet<>();for(int i=0;i<page.getJSONArray("actions").length();i++){JSONObject action=page.getJSONArray("actions").getJSONObject(i);String id=action.getString("taskId");ids.add(id);statuses.add(action.getString("status"));assertEquals(id,action.getString("operationId"));assertEquals(id,action.getString("runId"));assertEquals(1,action.getInt("coordinatorEpoch"));assertFalse(action.getBoolean("replayAllowed"));assertFalse(action.getBoolean("executionAuthorized"));}
        assertEquals(Set.of(completed,failed,unknown),ids);assertEquals(Set.of("completed","failed","unknown"),statuses);assertFalse(page.toString().contains("Only the original source body"));assertFalse(page.toString().contains("planDigest"));
        assertEquals(3,reopened.list().length());assertEquals(0,AttachmentRepository.get(app).book.list().length());
        assertEquals("unknown",reopened.get(unknown,false).getString("status"));assertEquals(0,reopened.get(unknown,false).getJSONArray("operations").length());
    }
    @Test public void olderOriginalIsReachableBeyondTheRecentTwoHundredWithoutUnboundedReadOrReply()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=tasks(app);String original=admit(tasks,"Older original");tasks.status(original,"unknown","No replay");
        for(int i=0;i<205;i++)tasks.createCapabilityTask("Unrelated newer task "+i,"pagination-fixture");
        JSONArray recent=tasks.list();for(int i=0;i<recent.length();i++)assertNotEquals(original,recent.getJSONObject(i).getString("id"));
        AttachmentOutcomeHost host=host(app,tasks);JSONObject page=host.snapshot(null,new RunControl()).getJSONObject("documentActions");Set<String> seen=new HashSet<>();int pages=0;boolean found=false;
        while(true){pages++;assertTrue(page.getInt("headerRowsExamined")<=20);assertTrue(page.getLong("coreBytesRead")<=262144);JSONArray actions=page.getJSONArray("actions");assertTrue(actions.length()<=20);for(int i=0;i<actions.length();i++)found|=original.equals(actions.getJSONObject(i).getString("taskId"));if(!page.getBoolean("hasMore"))break;String cursor=page.getString("nextCursor");assertTrue(seen.add(cursor));page=host.documentActions(cursor,new RunControl());}
        assertTrue(pages>10);assertTrue(found);assertEquals("unknown",tasks.get(original,false).getString("status"));assertEquals(0,AttachmentRepository.get(app).book.list().length());
        RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(Exception.class,()->host.documentActions("",cancelled));
    }
    @Test public void onlyExactNativePrivateIdentityIsProjectedAndOversizedMetadataIsAnExplicitGap()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=tasks(app);String valid=admit(tasks,"Valid original"),large=admit(tasks,"Large original");
        JSONObject oversized=tasks.get(large,false).put("unrelatedLargeBody","x".repeat(70000));writeFixture(tasks,oversized);
        for(String field:new String[]{"actorId","spaceId","origin","operationId","runId","coordinatorEpoch","planDigest"}){
            String id=admit(tasks,field);JSONObject row=tasks.get(id,false);switch(field){case "actorId"->row.put(field,"someone_else");case "spaceId"->row.put(field,"group");case "origin"->row.put(field,new JSONObject().put("kind","group"));case "operationId","runId"->row.put(field,UUID.randomUUID().toString());case "coordinatorEpoch"->row.put(field,2);case "planDigest"->row.getJSONObject("externalRefs").getJSONObject("documentCreation").put(field,"not-native");}writeFixture(tasks,row);
        }
        JSONObject page=tasks.ownerDocumentActionsForOwner("",new RunControl());assertEquals(1,page.getJSONArray("actions").length());assertEquals(valid,page.getJSONArray("actions").getJSONObject(0).getString("taskId"));boolean foundGap=false;for(int i=0;i<page.getJSONArray("gaps").length();i++){JSONObject gap=page.getJSONArray("gaps").getJSONObject(i);foundGap|=large.equals(gap.getString("taskId"))&&gap.getString("reason").equals("original_document_metadata_read_limited");}assertTrue(foundGap);assertFalse(page.toString().contains("unrelatedLargeBody"));assertTrue(page.getLong("coreBytesRead")<=262144);
        assertEquals(9,tasks.list().length());assertEquals(0,AttachmentRepository.get(app).book.list().length());
    }
    @Test public void aggregateCoreBudgetIsChargedBeforeEveryBodyReadAndRemainingRowsBecomeExplicitGaps()throws Exception {
        Application app=RuntimeEnvironment.getApplication();TaskStore tasks=tasks(app);
        for(int i=0;i<8;i++){String id=admit(tasks,"Budgeted original "+i);writeFixture(tasks,tasks.get(id,false).put("unrelatedRetainedBody","x".repeat(40000)));}
        JSONObject page=tasks.ownerDocumentActionsForOwner("",new RunControl());assertFalse(page.getBoolean("hasMore"));assertEquals(8,page.getInt("headerRowsExamined"));assertEquals(8,page.getJSONArray("actions").length()+page.getJSONArray("gaps").length());assertTrue(page.getJSONArray("gaps").length()>0);assertTrue(page.getLong("coreBytesRead")<=262144);
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(tasks);long actual=0;for(int i=0;i<page.getJSONArray("actions").length();i++){String id=page.getJSONArray("actions").getJSONObject(i).getString("taskId");try(var row=db.rawQuery("SELECT length(CAST(document AS BLOB)) FROM tasks WHERE task_id=?",new String[]{id})){assertTrue(row.moveToFirst());assertTrue(row.getLong(0)<=65536);actual+=row.getLong(0);}}
        assertEquals(actual,page.getLong("coreBytesRead"));assertFalse(page.toString().contains("unrelatedRetainedBody"));for(int i=0;i<page.getJSONArray("gaps").length();i++)assertEquals("original_document_metadata_read_limited",page.getJSONArray("gaps").getJSONObject(i).getString("reason"));
    }
}
