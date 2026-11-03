package dev.pocketengine.features;

import android.app.AlarmManager;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.automation.AutomationModule;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real native mailbox/checkpoint, SQLite, original source bridge, tool host, Book and alarm.
 * No model, phone, new execution grant or fake task store supplies a source identity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class OrdinaryScheduleNativeJourneyTest {
    private Application app;private TaskStore tasks;private ConversationRepository repo;private FeatureHost features;
    private AutomationPlanHost plans;private AutomationModule module;private MemoryModule memory;private MemorySourceBridge sources;
    private String conversation,message,client,id,run;private int epoch;private RunControl original;private HostToolSupervisor port;
    private JSONObject nativeMessage;private SQLiteDatabase db;
    private final java.util.concurrent.atomic.AtomicInteger sourceChecks=new java.util.concurrent.atomic.AtomicInteger(),failSourceCheck=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<Exception> sourceReadFailure=new java.util.concurrent.atomic.AtomicReference<>();
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Before public void prepare()throws Exception {
        app=RuntimeEnvironment.getApplication();tasks=new TaskStore(app);repo=tasks.conversationRepository();repo.initializeEmpty();
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
        features=new FeatureHost(event->{},tasks,()->false);sources=new MemorySourceBridge(features,tasks);
        FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        memory=new MemoryModule(context,sources::resolve);features.register(memory);features.register(new dev.pocketengine.features.skills.SkillsModule(context));var assistant=new dev.pocketengine.features.assistant.AssistantModule(context,memory,null,tasks);features.register(assistant);sources.bindAssistant(assistant);plans=new AutomationPlanHost(tasks);features.setAutomationPlanHost(plans);
        plans.bindProposalSources((refs,action)->{if(sourceChecks.incrementAndGet()==failSourceCheck.get()){Exception fault=new android.database.sqlite.SQLiteDiskIOException("synthetic final pre-notification read");sourceReadFailure.set(fault);throw fault;}Exception fault=sourceReadFailure.get();if(fault!=null)throw fault;return sources.withCurrentUserSources(refs,action);});
        module=new AutomationModule(context,plans::resolveEvidence,plans);features.register(module);
        conversation=UUID.randomUUID().toString();message=UUID.randomUUID().toString();client=UUID.randomUUID().toString();
        nativeMessage=new JSONObject().put("id",message).put("clientMessageId",client).put("revision",1L).put("role","user")
                .put("content","明天提醒我核对账单，不自动付款").put("inputMode","queue").put("attachments",new JSONArray());
        repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","原私聊").put("messages",new JSONArray()));
            repo.saveSource(ref().toJson().put("conversationId",conversation).put("role","user").put("clientMessageId",client).put("deleted",false));repo.saveMessage(conversation,nativeMessage);return null;});
        JSONObject task=tasks.startAttempt(tasks.createCapabilityTask(nativeMessage.getString("content"),conversation).getString("id"),"capabilities",12);
        id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(id,run,epoch);
        tasks.authorizeTools(id,run,new JSONArray().put("schedule_propose").put("schedule_list"));
        tasks.openOwnerInputMailbox(id,run,epoch,conversation,original);tasks.appendOwnerGoalInput(id,run,epoch,conversation,nativeMessage,"queue",original);
        materialize();port=new HostToolSupervisor(features,tasks,id,run,epoch,true);
    }
    private SourceRef ref(){return new SourceRef("message",message,1,"owner","private");}
    private void materialize()throws Exception {tasks.journal(id,run,epoch,original).checkpoint(new JSONArray().put(new JSONObject(nativeMessage.toString()).put("ownerMessageId",message).put("ownerMessageRevision",1L)),0,new JSONObject());}
    @After public void close()throws Exception {if(port!=null)port.close();if(features!=null)features.close();if(db!=null&&db.isOpen())db.close();}
    private JSONObject draft()throws Exception{return new JSONObject().put("title","核对原账单").put("kind","phone_goal").put("goal","主人核对后自行开始，不自动付款")
            .put("schedule",new JSONObject().put("type","once").put("at",System.currentTimeMillis()+120000));}
    private JSONObject propose(JSONObject draft)throws Exception {
        String operation=tasks.journal(id,run,epoch,original).beginOperation("schedule_propose",draft);
        JSONObject result=port.execute("schedule_propose",new JSONObject(draft.toString()).put("_hostOperationId",operation),original);
        tasks.journal(id,run,epoch,original).finishOperation(operation,result);return result.getJSONObject("proposal");
    }
    private JSONObject approve(JSONObject proposal)throws Exception{return plans.ownerCommand(module,"approveSchedule",new JSONObject().put("id",proposal.getString("id")).put("digest",proposal.getString("digest"))).getJSONObject("schedule");}
    private JSONArray rules()throws Exception{return module.snapshot().getJSONArray("rules");}
    @Test public void originalSourceToDraftToExplicitOwnerApprovalUsesOneOriginalRuleAndAlarm()throws Exception {
        JSONObject row=propose(draft());assertEquals(0,rules().length());JSONArray approvals=tasks.get(id,false).optJSONArray("approvals");assertTrue(approvals==null||approvals.length()==0);
        JSONObject listed=port.execute("schedule_list",new JSONObject(),original);assertEquals(row.getString("id"),listed.getJSONArray("proposals").getJSONObject(0).getString("id"));
        assertThrows(SecurityException.class,()->plans.ownerCommand(module,"approveSchedule",new JSONObject().put("id",row.getString("id")).put("digest","0".repeat(64))));assertEquals(0,rules().length());
        JSONObject approved=approve(row),rule=approved.getJSONObject("rule");assertFalse(approved.getBoolean("executionAuthorized"));assertEquals("notify_then_user_start",rule.getString("executionPolicy"));
        assertEquals(1,rules().length());assertTrue(rule.getBoolean("enabled"));assertEquals(run,rule.getJSONObject("nativeOrigin").getString("runId"));
        assertFalse(Shadows.shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
        assertTrue(approve(row).getBoolean("duplicate"));assertEquals(1,rules().length());assertEquals(1,tasks.list().length());
        assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());assertFalse(plans.ownerSnapshot(module).getJSONArray("scheduleProposals").getJSONObject(0).getBoolean("executionAuthorized"));
    }
    @Test public void cronSchemaAndOriginalPreparedProposalRequireTheSameExplicitOwnerApproval()throws Exception {
        JSONObject tool=null;JSONArray declared=port.tools();for(int i=0;i<declared.length();i++){JSONObject candidate=declared.getJSONObject(i).getJSONObject("function");if("schedule_propose".equals(candidate.optString("name")))tool=candidate;}
        assertNotNull(tool);JSONObject properties=tool.getJSONObject("parameters").getJSONObject("properties").getJSONObject("schedule").getJSONObject("properties");
        boolean cron=false;JSONArray types=properties.getJSONObject("type").getJSONArray("enum");for(int i=0;i<types.length();i++)cron|="cron".equals(types.getString(i));assertTrue(cron);assertEquals(512,properties.getJSONObject("expression").getInt("maxLength"));assertEquals(1,properties.getJSONObject("scheduleVersion").getInt("maximum"));
        JSONObject requested=draft().put("schedule",new JSONObject().put("type","cron").put("scheduleVersion",1).put("expression","*/15 * * * *").put("zone","UTC"));port.validate("schedule_propose",requested);
        JSONObject unknown=new JSONObject(requested.toString());unknown.getJSONObject("schedule").put("scheduleVersion",2);assertThrows(Exception.class,()->port.validate("schedule_propose",unknown));
        JSONObject unsupported=new JSONObject(requested.toString());unsupported.getJSONObject("schedule").put("unexpected",true);assertThrows(Exception.class,()->port.validate("schedule_propose",unsupported));
        JSONObject oversized=new JSONObject(requested.toString());oversized.getJSONObject("schedule").put("expression","x".repeat(513));assertThrows(Exception.class,()->port.validate("schedule_propose",oversized));
        JSONObject proposal=propose(requested);assertEquals("pending",proposal.getString("status"));assertEquals("cron",proposal.getJSONObject("draft").getJSONObject("schedule").getString("type"));assertEquals(0,rules().length());assertFalse(proposal.getBoolean("executionAuthorized"));
        JSONArray approvals=tasks.get(id,false).optJSONArray("approvals");assertTrue(approvals==null||approvals.length()==0);
        assertThrows(SecurityException.class,()->plans.ownerCommand(module,"approveSchedule",new JSONObject().put("id",proposal.getString("id")).put("digest","0".repeat(64))));assertEquals(0,rules().length());
        long before=System.currentTimeMillis();JSONObject approved=approve(proposal),rule=approved.getJSONObject("rule");assertEquals("cron",rule.getJSONObject("schedule").getString("type"));assertEquals(1,rule.getJSONObject("schedule").getInt("scheduleVersion"));assertEquals("UTC",rule.getJSONObject("schedule").getString("zone"));assertTrue(rule.getLong("nextAt")>before);assertEquals("notify_then_user_start",rule.getString("executionPolicy"));assertEquals(run,rule.getJSONObject("nativeOrigin").getString("runId"));assertFalse(approved.getBoolean("executionAuthorized"));
        assertFalse(Shadows.shadowOf(app.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());assertTrue(approve(proposal).getBoolean("duplicate"));assertEquals(1,rules().length());assertEquals(1,tasks.list().length());assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());
    }
    @Test public void forgedPreparedChangedArgumentsReadonlyAndUnsupportedConditionCannotCreateRules()throws Exception {
        JSONObject draft=draft();assertThrows(SecurityException.class,()->port.execute("schedule_propose",new JSONObject(draft.toString()).put("_hostOperationId",UUID.randomUUID().toString()),original));
        String operation=tasks.journal(id,run,epoch,original).beginOperation("schedule_propose",draft);
        assertThrows(SecurityException.class,()->port.execute("schedule_propose",new JSONObject(draft.toString()).put("goal","changed target").put("_hostOperationId",operation),original));
        try(HostToolSupervisor readonly=new HostToolSupervisor(features,tasks,id,run,epoch,false)){assertThrows(SecurityException.class,()->readonly.execute("schedule_propose",new JSONObject(draft.toString()).put("_hostOperationId",operation),original));}
        assertThrows(Exception.class,()->port.validate("schedule_propose",new JSONObject(draft.toString()).put("semanticCondition",new JSONObject().put("goal","condition"))));
        assertThrows(Exception.class,()->port.validate("schedule_propose",new JSONObject(draft.toString()).put("taskId",id)));
        assertEquals(0,rules().length());assertEquals(0,module.scheduleProposalsForTask(id).length());
    }
    @Test public void ownUnknownCancelledOrPausedOperationNeverBecomesANewDraft()throws Exception {
        JSONObject draft=draft();String operation=tasks.journal(id,run,epoch,original).beginOperation("schedule_propose",draft);
        original.pause();assertThrows(Exception.class,()->port.execute("schedule_propose",new JSONObject(draft.toString()).put("_hostOperationId",operation),original));original.resume();
        tasks.journal(id,run,epoch,original).finishOperation(operation,new JSONObject().put("status","unknown"));
        assertThrows(SecurityException.class,()->port.execute("schedule_propose",new JSONObject(draft.toString()).put("_hostOperationId",operation),original));
        original.cancel();assertThrows(Exception.class,()->port.execute("schedule_propose",new JSONObject(draft.toString()).put("_hostOperationId",operation),original));
        assertEquals(0,rules().length());assertEquals(0,module.scheduleProposalsForTask(id).length());
    }
    @Test public void missingNativeMaterializationAndChangedRunTupleAreNotFilledFromCurrentMetadata()throws Exception {
        JSONObject unproven=tasks.startAttempt(tasks.createCapabilityTask("旧来源没有实际纳入证明",conversation).getString("id"),"capabilities",8);
        assertThrows(SecurityException.class,()->tasks.scheduleProposalUserRefs(unproven.getString("id"),unproven.getString("runId"),unproven.getInt("coordinatorEpoch"),conversation));
        JSONArray refs=tasks.scheduleProposalUserRefs(id,run,epoch,conversation);
        assertThrows(SecurityException.class,()->tasks.withScheduleProposalSourceCurrent(id,run,epoch+1,conversation,refs,()->new JSONObject()));
        assertThrows(SecurityException.class,()->tasks.withScheduleProposalSourceCurrent(id,run,epoch,conversation,new JSONArray().put(new SourceRef("message",message,2,"owner","private").toJson()),()->new JSONObject()));
        assertEquals(0,rules().length());
    }
    @Test public void finishedOriginalRunCanApproveProvenanceButCreatesNoNewRunOrGrant()throws Exception {
        JSONObject row=propose(draft());tasks.endAttempt(id,run,"completed","原答复完成",new JSONObject());
        assertFalse(approve(row).getBoolean("executionAuthorized"));assertEquals(run,tasks.get(id,false).getString("runId"));assertEquals(1,tasks.list().length());assertEquals(1,rules().length());
        assertThrows(Exception.class,()->port.execute("schedule_list",new JSONObject(),original));
    }
    @Test public void changedOriginalUserRevisionDeniesAndRedactsWithoutReadingOrRecreatingGoal()throws Exception {
        JSONObject row=propose(draft());repo.transaction(()->{JSONObject source=repo.source(message);source.put("revision",2L);repo.saveSource(source);return null;});
        assertThrows(SecurityException.class,()->approve(row));assertEquals(0,rules().length());JSONObject shown=plans.ownerSnapshot(module).getJSONArray("scheduleProposals").getJSONObject(0);
        assertFalse(shown.getBoolean("sourceAvailable"));assertFalse(shown.has("draft"));assertFalse(shown.toString().contains("核对原账单"));
    }
    @Test public void originalSourceRetirementAndConversationDeletionCannotBeRevivedByRetainedTask()throws Exception {
        JSONObject row=propose(draft());repo.transaction(()->{repo.retire(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(ref().toJson()))),"owner_forgot");return null;});
        assertNotNull(tasks.get(id,false));assertThrows(SecurityException.class,()->approve(row));assertEquals(0,rules().length());
        repo.transaction(()->{repo.deleteConversation(conversation,"owner_deleted");return null;});assertThrows(SecurityException.class,()->approve(row));assertEquals(0,rules().length());
    }
    @Test public void forgottenOriginalUserEvidenceCannotApproveTheOldDraft()throws Exception {
        JSONObject row=propose(draft());JSONObject record=memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("layer","episodic").put("text","主人曾要求核对账单").put("source","原主人明确保存").put("sourceRefs",new JSONArray().put(ref().toJson())))).getJSONObject("record");
        sources.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertTrue(memory.isSourceForgotten(scope(),ref()));
        assertThrows(SecurityException.class,()->approve(row));assertEquals(0,rules().length());assertTrue(plans.ownerSnapshot(module).getJSONArray("scheduleProposals").getJSONObject(0).getBoolean("sourceInvalidated"));
    }
    private JSONObject nearDraft()throws Exception {JSONObject value=draft();value.getJSONObject("schedule").put("at",System.currentTimeMillis()+2000);return value;}
    private void advanceToOccurrence()throws Exception {long until=rules().getJSONObject(0).getJSONObject("schedule").getLong("at")-System.currentTimeMillis();if(until>0)Thread.sleep(until+30);}
    private String storedAutomation()throws Exception {return new String(java.nio.file.Files.readAllBytes(new java.io.File(app.getFilesDir(),"automation/rules.json").toPath()),java.nio.charset.StandardCharsets.UTF_8);}
    private JSONObject rememberSource()throws Exception {return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("layer","episodic").put("text","主人曾要求核对账单").put("source","原主人明确保存").put("sourceRefs",new JSONArray().put(ref().toJson())))).getJSONObject("record");}
    @Test public void currentApprovedSourceReallyNotifiesAndOpaqueIntentRequeriesTheOriginalGoal()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);advanceToOccurrence();JSONArray delivered=module.handle("checkDue",new JSONObject()).getJSONArray("receipts");assertEquals(1,delivered.length());assertEquals("awaiting_user",delivered.getJSONObject(0).getString("status"));
        var notifications=Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).getAllNotifications();assertEquals(1,notifications.size());android.content.Intent intent=Shadows.shadowOf(notifications.get(0).contentIntent).getSavedIntent();assertFalse(intent.hasExtra("automationGoal"));assertEquals(delivered.getJSONObject(0).getString("id"),intent.getStringExtra("automationOccurrence"));
        assertEquals("主人核对后自行开始，不自动付款",module.taskRequest(delivered.getJSONObject(0).getString("id")).getString("goal"));assertEquals(1,tasks.list().length());
    }
    @Test public void approvedThenForgottenErasesOriginalBodiesCancelsNotificationAndOldIntentWithoutAffectingManualRules()throws Exception {
        JSONObject proposal=propose(nearDraft());JSONObject rule=approve(proposal).getJSONObject("rule");
        module.handle("save",new JSONObject().put("rule",new JSONObject().put("title","独立手写提醒").put("kind","reminder").put("schedule",new JSONObject().put("type","once").put("at",System.currentTimeMillis()+900000))));
        advanceToOccurrence();JSONArray delivered=module.handle("checkDue",new JSONObject()).getJSONArray("receipts");String occurrence=delivered.getJSONObject(0).getString("id");var notifications=Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class));android.app.PendingIntent old=notifications.getAllNotifications().get(0).contentIntent;
        JSONObject record=rememberSource();JSONObject forgotten=sources.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));
        assertTrue(notifications.getAllNotifications().isEmpty());assertEquals(0,forgotten.getJSONObject("scheduleSourceRevocation").getInt("notificationClearPending"));assertTrue(Shadows.shadowOf(old).isCanceled());assertThrows(Exception.class,()->module.taskRequest(occurrence));assertFalse(storedAutomation().contains("主人核对后自行开始，不自动付款"));assertFalse(storedAutomation().contains("核对原账单"));
        assertThrows(SecurityException.class,()->module.handle("setEnabled",new JSONObject().put("id",rule.getString("id")).put("enabled",true)));assertTrue(rules().toString().contains("独立手写提醒"));assertTrue(rules().getJSONObject(1).getBoolean("enabled"));
    }
    @Test public void approvedThenDeletedBeforeDueCannotPublishOrExposeRetainedGoal()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);repo.transaction(()->{repo.deleteConversation(conversation,"owner_deleted");return null;});advanceToOccurrence();assertEquals(0,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());
        assertTrue(Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).getAllNotifications().isEmpty());assertFalse(storedAutomation().contains("主人核对后自行开始，不自动付款"));assertFalse(storedAutomation().contains("核对原账单"));assertFalse(rules().getJSONObject(0).getBoolean("enabled"));
    }
    @Test public void approvedThenChangedSourceRevokesExistingOccurrenceAtNativeTaskRequest()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);advanceToOccurrence();String occurrence=module.handle("checkDue",new JSONObject()).getJSONArray("receipts").getJSONObject(0).getString("id");
        repo.transaction(()->{JSONObject source=repo.source(message);source.put("revision",2L);repo.saveSource(source);return null;});assertThrows(SecurityException.class,()->module.taskRequest(occurrence));
        assertTrue(Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).getAllNotifications().isEmpty());assertFalse(storedAutomation().contains("主人核对后自行开始，不自动付款"));assertFalse(rules().getJSONObject(0).getBoolean("enabled"));
    }
    @Test public void missingOriginalHostQuarantinesWithoutErasingAndTheSameReminderRecovers()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});AutomationModule notMounted=new AutomationModule(context);
        JSONObject paused=notMounted.reconcileNativeScheduleSources();assertTrue(storedAutomation().contains("主人核对后自行开始，不自动付款"));assertEquals(1,paused.getInt("sourceChecksPending"));assertTrue(notMounted.snapshot().getJSONArray("rules").getJSONObject(0).getBoolean("enabled"));
        advanceToOccurrence();assertEquals(0,notMounted.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertTrue(Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).getAllNotifications().isEmpty());
        JSONArray recovered=module.handle("checkDue",new JSONObject()).getJSONArray("receipts");assertEquals(1,recovered.length());assertEquals("awaiting_user",recovered.getJSONObject(0).getString("status"));assertEquals(proposal.getString("id"),recovered.getJSONObject(0).getString("ruleId"));assertFalse(rules().getJSONObject(0).optBoolean("sourceInvalidated"));
    }
    @Test public void temporarySqlAndUntypedSecurityFailuresNeverBecomeWithdrawalAndNormalTriggerRecovers()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);sourceReadFailure.set(new android.database.sqlite.SQLiteDiskIOException("synthetic transient native source read"));advanceToOccurrence();
        assertEquals(0,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertTrue(storedAutomation().contains("主人核对后自行开始，不自动付款"));assertTrue(rules().getJSONObject(0).getBoolean("enabled"));assertTrue(rules().getJSONObject(0).getBoolean("sourceCheckPending"));JSONObject hidden=plans.ownerSnapshot(module).getJSONArray("scheduleProposals").getJSONObject(0);assertEquals("source_check_pending",hidden.getString("status"));assertFalse(hidden.getBoolean("sourceInvalidated"));assertFalse(hidden.has("draft"));
        sourceReadFailure.set(new SecurityException("source unavailable is not an authoritative forgot/delete fact"));assertEquals(0,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertTrue(storedAutomation().contains("核对原账单"));
        sourceReadFailure.set(null);JSONArray recovered=module.handle("checkDue",new JSONObject()).getJSONArray("receipts");assertEquals(1,recovered.length());assertEquals("主人核对后自行开始，不自动付款",module.taskRequest(recovered.getJSONObject(0).getString("id")).getString("goal"));assertFalse(rules().getJSONObject(0).optBoolean("sourceInvalidated"));assertEquals(1,tasks.list().length());
    }
    @Test public void temporaryTaskRequestSourceFailurePreservesBodyAndResumesWithoutNotificationReplay()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);advanceToOccurrence();String occurrence=module.handle("checkDue",new JSONObject()).getJSONArray("receipts").getJSONObject(0).getString("id");
        sourceReadFailure.set(new android.database.sqlite.SQLiteDiskIOException("synthetic read fault"));assertThrows(Exception.class,()->module.taskRequest(occurrence));assertTrue(storedAutomation().contains("主人核对后自行开始，不自动付款"));assertFalse(rules().getJSONObject(0).optBoolean("sourceInvalidated"));
        sourceReadFailure.set(null);assertEquals("主人核对后自行开始，不自动付款",module.taskRequest(occurrence).getString("goal"));assertEquals(0,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertTrue(Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).getAllNotifications().isEmpty());
    }
    @Test public void finalPreNotificationReadFailureDefersOnlyTheUnsentOriginalOccurrenceAndRecoversItsSameId()throws Exception {
        JSONObject proposal=propose(nearDraft());approve(proposal);advanceToOccurrence();
        // checkDue's outer reconciliation and mounted deliver reconciliation pass;
        // only its final, pre-OS source check fails. This is not a notification-IO fault.
        failSourceCheck.set(sourceChecks.get()+3);assertEquals(0,module.handle("checkDue",new JSONObject()).getJSONArray("receipts").length());assertTrue(Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).getAllNotifications().isEmpty());
        JSONArray originalRows=new JSONObject(storedAutomation()).getJSONArray("receipts");assertEquals(1,originalRows.length());JSONObject originalOccurrence=originalRows.getJSONObject(0);assertEquals("deferred",originalOccurrence.getString("status"));assertTrue(originalOccurrence.getBoolean("sourceDispatchDeferred"));String occurrence=originalOccurrence.getString("id");
        failSourceCheck.set(0);sourceReadFailure.set(null);JSONArray recovered=module.handle("checkDue",new JSONObject()).getJSONArray("receipts");assertEquals(1,recovered.length());assertEquals(occurrence,recovered.getJSONObject(0).getString("id"));assertEquals("awaiting_user",recovered.getJSONObject(0).getString("status"));assertEquals(1,new JSONObject(storedAutomation()).getJSONArray("receipts").length());assertEquals(1,tasks.list().length());
    }
    @Test public void originalSqlSourceFenceSerializesDirectRepositoryDeletionAndRechecksColdUse()throws Exception {
        JSONObject row=propose(draft());JSONArray refs=tasks.scheduleProposalUserRefs(id,run,epoch,conversation);
        CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1),deleteEntered=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<JSONObject> approval=workers.submit(()->module.approveScheduleProposalForOwner(row.getString("id"),row.getString("digest"),bookAction->sources.withCurrentUserSources(refs,()->tasks.withScheduleProposalSourceCurrent(id,run,epoch,conversation,refs,()->{
                assertTrue(Thread.holdsLock(sources));assertTrue(Thread.holdsLock(tasks));assertTrue(db.inTransaction());var field=MemoryModule.class.getDeclaredField("book");field.setAccessible(true);assertFalse(Thread.holdsLock(field.get(memory)));inside.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return bookAction.call();}))));
            assertTrue(inside.await(5,TimeUnit.SECONDS));Future<?> deletion=workers.submit(()->{deleteEntered.countDown();repo.deleteConversation(conversation,"owner_deleted");return null;});
            assertTrue(deleteEntered.await(5,TimeUnit.SECONDS));assertFalse(deletion.isDone());release.countDown();assertFalse(approval.get(5,TimeUnit.SECONDS).getBoolean("executionAuthorized"));deletion.get(5,TimeUnit.SECONDS);
            assertEquals(1,rules().length());assertThrows(SecurityException.class,()->approve(row));assertEquals(1,rules().length());assertFalse(plans.ownerSnapshot(module).getJSONArray("scheduleProposals").getJSONObject(0).getBoolean("sourceAvailable"));
        } finally {release.countDown();workers.shutdownNow();}
    }
}
