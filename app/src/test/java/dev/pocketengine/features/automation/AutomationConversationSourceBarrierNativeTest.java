package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.assistant.OwnerSourceReferences;
import dev.pocketengine.features.assistant.OwnerSourceRequestProjection;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.OwnerSourceRunContext;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskMemoryNativeHost;
import dev.pocketengine.features.memory.TaskStoreMemoryQuery;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original SQLite/source projection and native conversation writer. No provider/device call
 * or model-consumption claim: the original pre-dispatch exposure recorder establishes the dependency. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AutomationConversationSourceBarrierNativeTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();
        final TaskStore tasks;final SQLiteDatabase db;final ConversationRepository history;
        final FeatureHost host;final AssistantModule assistant;final MemoryModule memory;
        final MemorySourceBridge sources;final TaskMemoryNativeHost nativeOwner;
        final String conversation=id(),sourceConversation=id(),sourceMessage=id(),ownerMessage=id();
        final String sourceText="SC003-EXECUTION-ONLY-ORIGINAL-"+id();
        final String summary="SC003 scheduled result derived from "+sourceText;
        final String instruction="Use only this explicitly selected original for the scheduled result.";
        final String creatorTask,creatorRun,task,run;final int creatorEpoch,epoch;
        final JSONObject originalHit,selectedReference;final JSONArray references;

        Fixture()throws Exception {
            app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            history=tasks.conversationRepository();history.initializeEmpty();
            for(String conversationId:new String[]{conversation,sourceConversation})history.transaction(()->{
                history.saveConversation(new JSONObject().put("conversationId",conversationId).put("title","Native source barrier fixture").put("messages",new JSONArray()).put("updatedAt",System.currentTimeMillis()));return null;
            });
            FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
            AtomicReference<AssistantModule> bound=new AtomicReference<>();
            memory=new MemoryModule(context,(identity,kind,source)->bound.get().resolveSource(identity,source));
            assistant=new AssistantModule(context,memory,null,tasks);bound.set(assistant);
            host=new FeatureHost(event->{});host.register(memory);host.register(assistant);host.register(new SkillsModule(context));
            sources=new MemorySourceBridge(host,tasks);sources.bindAssistant(assistant);
            nativeOwner=new TaskMemoryNativeHost(host,tasks,sources);

            creatorTask=tasks.createCapabilityTask("Prepare the original schedule",conversation).getString("id");
            tasks.initializeExecutionScope(creatorTask,4);JSONObject creator=tasks.startAttempt(creatorTask,"capabilities",4);
            creatorRun=creator.getString("runId");creatorEpoch=creator.getInt("coordinatorEpoch");
            tasks.endAttempt(creatorTask,creatorRun,"completed","Original native schedule created",new JSONObject());
            JSONObject nativeOrigin=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",creatorTask).put("conversationId",conversation).put("runId",creatorRun).put("epoch",creatorEpoch);
            String plan=id(),step=id(),occurrence=id();
            JSONObject origin=new JSONObject().put("kind","automation").put("occurrenceId",occurrence).put("planId",plan).put("commitmentId",step)
                    .put("originTaskId",creatorTask).put("originConversationId",conversation).put("nativeOrigin",nativeOrigin);
            // Same native capability-plan request shape as AutomationPlanScopeTest. No phone,
            // artifact freeze, tool approval or new owner execution scope is issued here.
            JSONObject request=new JSONObject().put("goal","Read the selected original and retain the scheduled result").put("ruleId",plan).put("ruleRevision",1)
                    .put("planId",plan).put("commitmentId",step).put("allowPrivateArtifactFreeze",false)
                    .put("verification",new JSONObject().put("kind","workspace_artifact").put("projectId",id()).put("path","reports/result.md").put("requiredHeading","# Result").put("minBytes",16))
                    .put("origin",origin);
            task=tasks.createReminderTask(request,"capabilities").getString("id");
            JSONObject execution=tasks.startAttempt(task,"capabilities",4);run=execution.getString("runId");epoch=execution.getInt("coordinatorEpoch");
            assertEquals("assistant",execution.getString("kind"));assertEquals(conversation,execution.getString("conversationId"));

            saveUser(sourceConversation,sourceMessage,sourceText,null);
            JSONObject hits=nativeOwner.handle("searchOwnerOriginals",new JSONObject().put("query",sourceText).put("cursor","").put("limit",20).put("maxChars",16000),new RunControl());
            assertEquals(1,hits.getJSONArray("entries").length());originalHit=hits.getJSONArray("entries").getJSONObject(0);
            selectedReference=nativeOwner.handle("selectOwnerOriginalReference",new JSONObject().put("ownerLocator",originalHit.getJSONObject("ownerLocator")).put("offset",0).put("chars",sourceText.length()),new RunControl()).getJSONObject("ownerSourceRef");
            references=OwnerSourceReferences.checked(new JSONArray().put(selectedReference));
            saveUser(conversation,ownerMessage,instruction,references);
            JSONObject carrier=new JSONObject().put("role","user").put("content",instruction).put("ownerMessageId",ownerMessage).put("ownerMessageRevision",1).put("ownerSourceRefs",references);
            RunControl original=new RunControl();original.bindHostRun(task,run,epoch);
            try(var projection=new OwnerSourceRequestProjection(assistant,original,conversation,null,
                    (carriers,live)->tasks.recordOwnerSourceExposure(task,run,epoch,live,carriers))){
                JSONArray prepared=projection.project(new JSONArray().put(carrier),null,original);
                assertTrue("actual native projection read the selected body",prepared.toString().contains(sourceText));
                assertFalse(prepared.getJSONObject(0).has("ownerSourceRefs"));
            }
            assertEquals(0,fact(creatorRun).getJSONArray("carriers").length());
            assertEquals(1,fact(run).getJSONArray("carriers").length());
            assertEquals("prepared_before_model_dispatch",fact(run).getString("exposureStage"));
            assertFalse("native dependency facts contain metadata, not source prose",fact(run).toString().contains(sourceText));
            tasks.endAttempt(task,run,"completed",summary,new JSONObject());
        }
        void saveUser(String conversationId,String messageId,String content,JSONArray refs)throws Exception {
            JSONObject message=new JSONObject().put("id",messageId).put("revision",1).put("role","user").put("content",content);
            if(refs!=null)message.put("ownerSourceRefs",OwnerSourceReferences.checked(refs));
            history.transaction(()->{
                history.saveSource(new SourceRef("message",messageId,1,"owner","private").toJson().put("conversationId",conversationId).put("role","user").put("deleted",false));
                history.saveMessage(conversationId,message);return null;
            });
        }
        JSONObject fact(String selectedRun)throws Exception {
            try(var row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{"run.owner_source_context."+selectedRun})){
                assertTrue(row.moveToFirst());return new JSONObject(row.getString(0));
            }
        }
        String grant()throws Exception {
            try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}
        }
        long assistantMessages()throws Exception {
            long count=0;try(var rows=db.rawQuery("SELECT source_json FROM assistant_sources WHERE deleted=0",null)){
                while(rows.moveToNext())if("assistant".equals(new JSONObject(rows.getString(0)).optString("role")))count++;
            }return count;
        }
        JSONObject publish(JSONObject selected)throws Exception {
            return assistant.appendNativeTaskDelivery(conversation,task,selected.getString("deliveryId"),
                    new JSONObject().put("content",selected.getString("content")).put("runId",run).put("epoch",epoch));
        }
        void requireOtherDeliveryGuardsStillCurrent()throws Exception {
            assertTrue(history.exists(conversation));assertEquals(0,fact(creatorRun).getJSONArray("carriers").length());
            TaskStoreMemoryQuery.requireOwnerSourceContinuation(db,history,creatorTask,creatorRun,conversation);
            assertEquals(0,fact(run).getJSONArray("outputAttachmentSources").length());
            assertEquals(0,fact(run).getJSONArray("formalMemoryDependencies").length());
            assertFalse(fact(run).getBoolean("outputSourcesWithdrawn"));
            TaskStoreMemoryQuery.requireOutputContext(db,null,task,run);
        }
        void assertNoPublicationAfterSourceLoss(JSONObject originalSnapshot,String originalGrant)throws Exception {
            requireOtherDeliveryGuardsStillCurrent();
            assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->tasks.claimAutomationConversationDelivery(task));
            assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->publish(originalSnapshot));
            assertThrows(OwnerSourceRunContext.ContinuationUnavailable.class,()->tasks.automationConversationDeliverySnapshot(task));
            assertEquals(0,assistantMessages());
            JSONObject index=assistant.queryNativeTaskDelivery(conversation,task,originalSnapshot.getString("deliveryId"));
            assertFalse(index.getBoolean("found"));assertFalse(index.getBoolean("bodyIncluded"));
            String retained=assistant.readOriginalConversation(conversation,"",80).toString();
            assertTrue(retained.contains(instruction));assertFalse(retained.contains(summary));assertFalse(retained.contains(sourceText));
            assertEquals("the original task audit is not republished as a new answer",summary,tasks.get(task,false).getString("summary"));
            assertEquals(originalGrant,grant());assertEquals(run,tasks.get(task,false).getString("runId"));assertEquals(epoch,tasks.get(task,false).getInt("coordinatorEpoch"));
        }
        public void close()throws Exception {
            try{host.close();var field=AssistantModule.class.getDeclaredField("turns");field.setAccessible(true);
                assertTrue("owned assistant worker exited",((ExecutorService)field.get(assistant)).awaitTermination(5,TimeUnit.SECONDS));
            }finally{db.close();}
        }
    }

    @Test public void currentExecutionOriginalCanPublishOnceWithoutChangingTheOriginalGrant()throws Exception {
        try(Fixture f=new Fixture()){
            f.requireOtherDeliveryGuardsStillCurrent();String grant=f.grant();
            JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);assertTrue(selected.getBoolean("dispatchAllowed"));
            assertEquals(f.summary,selected.getString("content"));assertEquals(f.run,selected.getString("runId"));
            JSONObject receipt=f.publish(selected);assertTrue(receipt.getBoolean("accepted"));
            assertEquals("delivered",f.tasks.recordAutomationConversationDelivery(selected,receipt).getString("phase"));
            assertEquals(1,f.assistantMessages());assertEquals(grant,f.grant());
            assertEquals("current",f.history.state(f.sourceConversation,SourceRef.fromJson(f.selectedReference.getJSONObject("sourceRef"))).getString("status"));
            assertTrue(f.assistant.readOriginalConversation(f.conversation,"",80).toString().contains(f.summary));
            assertFalse(f.tasks.claimAutomationConversationDelivery(f.task).getBoolean("dispatchAllowed"));assertEquals(1,f.assistantMessages());
        }
    }
    @Test public void withdrawnExecutionOriginalCannotBeClaimedOrAppendedFromAPreWithdrawalSnapshot()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject selected=f.tasks.automationConversationDeliverySnapshot(f.task);String grant=f.grant();
            f.nativeOwner.handle("deleteOriginalMessage",new JSONObject().put("conversationId",f.sourceConversation).put("messageId",f.sourceMessage).put("revision",1).put("confirmDelete",true),new RunControl());
            assertTrue(f.history.source(f.sourceMessage).getBoolean("deleted"));
            f.assertNoPublicationAfterSourceLoss(selected,grant);
            assertFalse("a rejected first claim must roll back its publication marker",f.tasks.get(f.task,false).has("automationConversationDelivery"));
            assertTrue("denied publication cannot recreate the deleted source",f.history.source(f.sourceMessage).getBoolean("deleted"));
        }
    }
    @Test public void forgottenExecutionOriginalCannotUseAnEarlierClaimOrReviveItsDerivedAnswer()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject selected=f.tasks.claimAutomationConversationDelivery(f.task);assertTrue(selected.getBoolean("dispatchAllowed"));String grant=f.grant();
            JSONObject record=f.memory.handle("importFromSources",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","Reviewed fixture derivative "+f.sourceMessage).put("sourceRefs",new JSONArray().put(f.selectedReference.getJSONObject("sourceRef"))))).getJSONObject("record");
            f.sources.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));
            SourceRef source=SourceRef.fromJson(f.selectedReference.getJSONObject("sourceRef"));assertTrue(f.memory.isSourceForgotten(scope(),source));
            f.assertNoPublicationAfterSourceLoss(selected,grant);
            JSONObject marker=f.tasks.get(f.task,false).getJSONObject("automationConversationDelivery");
            assertEquals("publication_unknown",marker.getString("phase"));assertEquals(selected.getString("publicationClaimId"),marker.getString("publicationClaimId"));
            assertTrue(f.history.source(f.sourceMessage).getLong("memorySourceRetiredThrough")>=source.revision);
            assertThrows(Exception.class,()->f.nativeOwner.handle("selectOwnerOriginalReference",new JSONObject().put("ownerLocator",f.originalHit.getJSONObject("ownerLocator")).put("offset",0).put("chars",f.sourceText.length()),new RunControl()));
            assertTrue("a denied stale append cannot reactivate the forgotten source",f.memory.isSourceForgotten(scope(),source));
        }
    }
}
