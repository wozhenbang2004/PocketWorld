package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
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

/** Actual original SQLite task/source and conversation writer. No model or device call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AutomationConversationDeliveryNativeTest {
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;
        final ConversationRepository history;final AssistantModule assistant;final FeatureHost features;
        final String conversation=UUID.randomUUID().toString(),sourceTask,sourceRun,task,run;final int sourceEpoch,epoch;
        final JSONObject request;
        Fixture()throws Exception{
            app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            history=tasks.conversationRepository();history.initializeEmpty();history.transaction(()->{history.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original creating conversation").put("messages",new JSONArray()).put("updatedAt",System.currentTimeMillis()));return null;});
            sourceTask=tasks.createCapabilityTask("Create original bounded schedule",conversation).getString("id");tasks.initializeExecutionScope(sourceTask,4);JSONObject source=tasks.startAttempt(sourceTask,"capabilities",4);sourceRun=source.getString("runId");sourceEpoch=source.getInt("coordinatorEpoch");
            String message=UUID.randomUUID().toString(),client=UUID.randomUUID().toString();JSONObject owner=new JSONObject().put("id",message).put("clientMessageId",client).put("revision",1L).put("role","user").put("content","Create original bounded schedule").put("inputMode","queue").put("attachments",new JSONArray());history.transaction(()->{history.saveSource(new SourceRef("message",message,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("clientMessageId",client).put("deleted",false));history.saveMessage(conversation,owner);return null;});RunControl original=new RunControl();original.bindHostRun(sourceTask,sourceRun,sourceEpoch);tasks.openOwnerInputMailbox(sourceTask,sourceRun,sourceEpoch,conversation,original);tasks.appendOwnerGoalInput(sourceTask,sourceRun,sourceEpoch,conversation,owner,"queue",original);tasks.journal(sourceTask,sourceRun,sourceEpoch,original).checkpoint(new JSONArray().put(new JSONObject(owner.toString()).put("ownerMessageId",message).put("ownerMessageRevision",1L)),0,new JSONObject());
            tasks.endAttempt(sourceTask,sourceRun,"completed","Schedule proposal prepared",new JSONObject());
            JSONObject nativeOrigin=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",sourceTask).put("conversationId",conversation).put("runId",sourceRun).put("epoch",sourceEpoch);
            JSONObject origin=new JSONObject().put("kind","automation").put("occurrenceId",UUID.randomUUID().toString()).put("originTaskId",sourceTask).put("originConversationId",conversation).put("nativeOrigin",nativeOrigin);
            request=new JSONObject().put("origin",origin).put("goal","Fixture scheduled goal").put("title","Scheduled fixture").put("ruleId",UUID.randomUUID().toString()).put("ruleRevision",1).put("overlapPolicy","buffer_one");
            task=tasks.createReminderTask(request,"background").getString("id");JSONObject execution=tasks.startAttempt(task,"background",4);run=execution.getString("runId");epoch=execution.getInt("coordinatorEpoch");tasks.endAttempt(task,run,"completed","Actual original scheduled result",new JSONObject());
            FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});features=new FeatureHost(event->{},tasks,()->false);MemoryModule memory=new MemoryModule(context);features.register(memory);features.register(new SkillsModule(context));assistant=new AssistantModule(context,memory,null,tasks);features.register(assistant);MemorySourceBridge sources=new MemorySourceBridge(features,tasks);sources.bindAssistant(assistant);assistant.bindNativeTaskDeliverySource((from,action)->sources.withCurrentUserSources(tasks.scheduleProposalUserRefs(from.getString("taskId"),from.getString("runId"),from.getInt("epoch"),from.getString("conversationId")),action));
        }
        JSONObject publish(JSONObject selected)throws Exception{return assistant.appendNativeTaskDelivery(conversation,task,selected.getString("deliveryId"),new JSONObject().put("content",selected.getString("content")).put("runId",run).put("epoch",epoch).put("publicationClaimId",selected.getString("publicationClaimId")));}
        long assistantMessages()throws Exception{long count=0;try(var rows=db.rawQuery("SELECT source_json FROM assistant_sources WHERE deleted=0",null)){while(rows.moveToNext())if("assistant".equals(new JSONObject(rows.getString(0)).optString("role")))count++;}return count;}
        String grant()throws Exception{try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
        public void close(){features.close();db.close();}
    }
    @Test public void actualOriginalResultReturnsOnceToTheCreatingConversation()throws Exception{try(Fixture f=new Fixture()){
        assertEquals(f.conversation,f.tasks.get(f.task,false).getString("conversationId"));String grant=f.grant();
        JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);assertTrue(selected.getBoolean("dispatchAllowed"));assertTrue(selected.getString("deliveryId").endsWith(":"+f.run+":"+f.epoch));assertEquals(f.task,f.tasks.automationConversationDeliveryPage("",20).getJSONArray("taskIds").getString(0));
        JSONObject receipt=f.publish(selected);assertTrue(receipt.getBoolean("accepted"));assertEquals("delivered",f.tasks.recordAutomationConversationDelivery(selected,receipt).getString("phase"));assertEquals(1,f.assistantMessages());assertEquals(grant,f.grant());
        JSONObject repeated=f.tasks.claimAutomationConversationDelivery(f.task);assertFalse(repeated.getBoolean("dispatchAllowed"));assertFalse(repeated.getBoolean("lookupOnly"));assertEquals(1,f.assistantMessages());
        assertEquals(0,f.tasks.automationConversationDeliveryPage("",20).getJSONArray("taskIds").length());
    }}
    @Test public void lostAcknowledgementUsesOnlyTheOriginalSourceIndex()throws Exception{try(Fixture f=new Fixture()){
        JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);f.publish(selected);assertEquals(1,f.assistantMessages());
        JSONObject unknown=f.tasks.claimAutomationConversationDelivery(f.task);assertFalse(unknown.getBoolean("dispatchAllowed"));assertTrue(unknown.getBoolean("lookupOnly"));
        JSONObject receipt=f.assistant.queryNativeTaskDelivery(f.conversation,f.task,unknown.getString("deliveryId"));assertTrue(receipt.getBoolean("found"));assertFalse(receipt.getBoolean("bodyIncluded"));
        assertEquals("delivered",f.tasks.recordAutomationConversationDelivery(unknown,receipt).getString("phase"));assertEquals(1,f.assistantMessages());
        JSONObject absent=f.assistant.queryNativeTaskDelivery(f.conversation,f.task,"automation:unpublished-fixture");assertFalse(absent.getBoolean("found"));assertEquals(1,f.assistantMessages());
    }}
    @Test public void nativeBusyReceiptPreservesTheSameTaskRunAndRemainingGrant()throws Exception{try(Fixture f=new Fixture()){
        String grant=f.grant();JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);
        f.tasks.recordAutomationConversationDelivery(selected,new JSONObject().put("waitingForOriginalConversation",true));assertEquals(0,f.assistantMessages());
        JSONObject ready=f.tasks.claimAutomationConversationDelivery(f.task);assertTrue(ready.getBoolean("dispatchAllowed"));assertEquals(selected.getString("deliveryId"),ready.getString("deliveryId"));assertEquals(f.run,ready.getString("runId"));
        f.tasks.recordAutomationConversationDelivery(ready,f.publish(ready));assertEquals(1,f.assistantMessages());assertEquals(grant,f.grant());
    }}
    @Test public void lateNegativeQueryCannotOverwriteDefiniteBusyOrANewerPublication()throws Exception{try(Fixture f=new Fixture()){
        JSONObject first=f.tasks.claimAutomationConversationDelivery(f.task),lateRead=f.tasks.claimAutomationConversationDelivery(f.task);
        assertTrue(lateRead.getBoolean("lookupOnly"));
        f.tasks.recordAutomationConversationDelivery(first,new JSONObject().put("waitingForOriginalConversation",true));
        assertEquals("waiting_for_original_conversation",f.tasks.recordAutomationConversationDelivery(lateRead,new JSONObject().put("found",false)).getString("phase"));
        JSONObject next=f.tasks.claimAutomationConversationDelivery(f.task);assertTrue(next.getBoolean("dispatchAllowed"));assertNotEquals(first.getString("publicationClaimId"),next.getString("publicationClaimId"));
        JSONObject stale=f.tasks.recordAutomationConversationDelivery(first,new JSONObject().put("waitingForOriginalConversation",true));assertTrue(stale.getBoolean("staleObservationIgnored"));assertEquals("publication_unknown",stale.getString("phase"));
        f.tasks.recordAutomationConversationDelivery(next,f.publish(next));
        assertEquals("delivered",f.tasks.recordAutomationConversationDelivery(lateRead,new JSONObject().put("found",false)).getString("phase"));assertEquals(1,f.assistantMessages());
        assertEquals(0,f.tasks.automationConversationDeliveryPage("",20).getJSONArray("taskIds").length());
    }}
    @Test public void changedNativeOriginOrDeletedConversationCannotRedirectOrResurrectResults()throws Exception{try(Fixture f=new Fixture()){
        JSONObject forged=new JSONObject(f.request.toString());forged.getJSONObject("origin").put("originConversationId",UUID.randomUUID().toString());forged.getJSONObject("origin").put("occurrenceId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->f.tasks.createReminderTask(forged,"background"));
        JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task),receipt=f.publish(selected);String message=receipt.getString("messageId");
        f.assistant.handle("deleteMessage",new JSONObject().put("conversationId",f.conversation).put("messageId",message).put("revision",1));JSONObject absentBody=f.assistant.queryNativeTaskDelivery(f.conversation,f.task,selected.getString("deliveryId"));assertTrue(absentBody.getBoolean("found"));assertFalse(absentBody.getBoolean("available"));assertFalse(absentBody.getBoolean("bodyIncluded"));
        f.history.transaction(()->{f.history.deleteConversation(f.conversation,"owner_deleted_original");return null;});assertThrows(Exception.class,()->f.tasks.automationConversationDeliverySnapshot(f.task));
        assertFalse(f.assistant.queryNativeTaskDelivery(f.conversation,f.task,selected.getString("deliveryId")).getBoolean("available"));
    }}    @Test public void acceptedJsonWithoutTheOriginalSqlSourceCannotConfirmPublication()throws Exception{try(Fixture f=new Fixture()){
        JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);JSONObject forged=new JSONObject().put("accepted",true).put("available",true).put("deliveryId",selected.getString("deliveryId")).put("taskId",f.task).put("conversationId",f.conversation).put("messageId",UUID.randomUUID().toString());
        assertThrows(SecurityException.class,()->f.tasks.recordAutomationConversationDelivery(selected,forged));assertEquals(0,f.assistantMessages());assertEquals("publication_unknown",f.tasks.get(f.task,false).getJSONObject("automationConversationDelivery").getString("phase"));
        assertEquals("delivered",f.tasks.recordAutomationConversationDelivery(selected,f.publish(selected)).getString("phase"));assertEquals(1,f.assistantMessages());
    }}
    @Test public void stalePublicationClaimCannotAppendAfterTheOriginalDefiniteBusyAllowsANewClaim()throws Exception{try(Fixture f=new Fixture()){
        JSONObject first=f.tasks.claimAutomationConversationDelivery(f.task);f.tasks.recordAutomationConversationDelivery(first,new JSONObject().put("waitingForOriginalConversation",true));JSONObject current=f.tasks.claimAutomationConversationDelivery(f.task);assertNotEquals(first.getString("publicationClaimId"),current.getString("publicationClaimId"));
        assertThrows(SecurityException.class,()->f.publish(first));assertEquals(0,f.assistantMessages());f.tasks.recordAutomationConversationDelivery(current,f.publish(current));assertEquals(1,f.assistantMessages());
    }}
    @Test public void changedNativeResponseCannotReplaceTheRetainedTaskResult()throws Exception{try(Fixture f=new Fixture()){
        JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);assertThrows(SecurityException.class,()->f.assistant.appendNativeTaskDelivery(f.conversation,f.task,selected.getString("deliveryId"),new JSONObject().put("content","changed future body").put("runId",f.run).put("epoch",f.epoch).put("publicationClaimId",selected.getString("publicationClaimId"))));assertEquals(0,f.assistantMessages());
    }}
    @Test public void existingConversationAndCompletedTaskWithoutUserAdoptionCannotBePromotedToCreatingSource()throws Exception{try(Fixture f=new Fixture()){
        JSONObject empty=f.tasks.startAttempt(f.tasks.createCapabilityTask("No native source adoption",f.conversation).getString("id"),"capabilities",4);f.tasks.endAttempt(empty.getString("id"),empty.getString("runId"),"completed","No source proof",new JSONObject());JSONObject request=new JSONObject(f.request.toString());JSONObject origin=request.getJSONObject("origin"),source=origin.getJSONObject("nativeOrigin");origin.put("originTaskId",empty.getString("id")).put("occurrenceId",UUID.randomUUID().toString());source.put("taskId",empty.getString("id")).put("runId",empty.getString("runId")).put("epoch",empty.getInt("coordinatorEpoch"));int before=f.tasks.list().length();
        assertThrows(SecurityException.class,()->f.tasks.createReminderTask(request,"background"));assertEquals(before,f.tasks.list().length());assertEquals(0,f.assistantMessages());
    }}

}
