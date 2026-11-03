package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.OwnerSourceRequestProjection;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
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

/** Real native message preparation, original SQLite journal and canonical MemoryBook/Bridge. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeConversationMemoryDraftHostTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Context android;final TaskStore tasks;final MemoryModule memory;final AssistantModule assistant;final FeatureHost features;final NativeConversationMemoryDraftHost host;final RunControl original=new RunControl();final JSONObject task;final String conversation=id();final JSONArray sourceSelection=new JSONArray();
        Fixture()throws Exception {
            File home=Files.createTempDirectory("conversation-memory-fixture-").toFile();android=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public File getFilesDir(){return home;}};
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});AtomicReference<AssistantModule> nativeAssistant=new AtomicReference<>();memory=new MemoryModule(context,(owner,kind,source)->nativeAssistant.get().resolveSource(owner,source));assistant=new AssistantModule(context,memory,null,tasks);nativeAssistant.set(assistant);
            JSONArray messages=new JSONArray();var repo=tasks.conversationRepository();repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Synthetic multi-turn review").put("messages",new JSONArray()));for(String content:new String[]{"准备出行时先检查天气。","再按气温决定携带衣物。","就记这个流程，保留不确定条件。"}){String message=id();repo.saveSource(new SourceRef("message",message,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));repo.saveMessage(conversation,new JSONObject().put("id",message).put("revision",1).put("role","user").put("content",content));messages.put(new JSONObject().put("role","user").put("content",content).put("ownerMessageId",message).put("ownerMessageRevision",1));sourceSelection.put(new JSONObject().put("sourceId",message).put("revision",1));}return null;});
            task=tasks.startAttempt(tasks.createCapabilityTask("提出多轮记忆草稿",conversation).getString("id"),"capabilities",30);original.bindHostRun(task(),run(),epoch());tasks.openOwnerInputMailbox(task(),run(),epoch(),conversation,original);tasks.authorizeTools(task(),run(),new JSONArray().put("memory_propose").put("memory_draft_sources"));tasks.status(task(),"running","Synthetic original proposal");
            features=new FeatureHost(event->{});features.register(memory);features.register(assistant);MemorySourceBridge bridge=new MemorySourceBridge(features,tasks);bridge.bindAssistant(assistant);host=new NativeConversationMemoryDraftHost(tasks,memory,bridge);
            ModelConfig config=new ModelConfig("http://127.0.0.1","","fixture",false,30,10);try(var projection=new OwnerSourceRequestProjection(assistant,original,conversation,null,(carriers,parts,control)->tasks.recordOwnerSourceExposure(task(),run(),epoch(),control,carriers,parts))){projection.prepared(messages,projection.project(messages,config,original),config,original);}
        }
        String task()throws Exception{return task.getString("id");}String run()throws Exception{return task.getString("runId");}int epoch()throws Exception{return task.getInt("coordinatorEpoch");}
        RunJournal journal()throws Exception{return tasks.journal(task(),run(),epoch(),original);}
        SQLiteDatabase sql(){return SQLiteDatabase.openDatabase(android.getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);}
        JSONObject create()throws Exception{return new JSONObject().put("action","create").put("layer","procedural").put("text","出行前检查天气，再按气温决定衣物；未知天气须另行核对。").put("reason","主人在三轮讨论后要求保存，仍需审阅具体正文").put("applicability","准备出行时；不是已验证的预测方法").put("confidence",0.5).put("sources",new JSONArray(sourceSelection.toString()));}
        JSONObject mutation(String action,JSONObject target)throws Exception{JSONObject value=new JSONObject().put("action",action).put("target",new JSONObject().put("id",target.getString("id")).put("revision",target.getLong("revision"))).put("reason","主人审阅原记忆与这次讨论后决定").put("sources",new JSONArray(sourceSelection.toString()));if(action.equals("update"))value.put("text","出行前先核对当前天气；未知信息须再次查询。");return value;}
        JSONObject propose(JSONObject input)throws Exception{String operation=journal().beginOperation("memory_propose",input);JSONObject receipt=host.proposeForTask(task(),run(),epoch(),operation,input,original);journal().finishOperation(operation,receipt);return receipt.getJSONObject("memoryDraft");}
        NativeConversationMemoryDraftHost.Review review(JSONObject proposal)throws Exception{return host.reviewForOwner(task(),run(),epoch(),proposal.getString("operationId"),proposal.getString("proposalDigest"));}
        JSONObject formal()throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("layer","fact").put("text","旧的出行规则。"))).getJSONObject("record");}
        JSONObject record(String id)throws Exception{return memory.handle("get",new JSONObject().put("scope",scope()).put("id",id)).getJSONObject("record");}
        int records()throws Exception{return memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length();}
        @Override public void close()throws Exception{features.close();}
    }
    @Test public void threePreparedUserTurnsBecomeOnePendingCandidateAndOneReviewedMemory()throws Exception{try(Fixture f=new Fixture()){
        assertEquals(3,f.host.sourcesForTask(f.task(),f.run(),f.epoch(),f.original).getJSONArray("sources").length());JSONObject proposal=f.propose(f.create());assertEquals(0,f.records());assertEquals(3,proposal.getJSONArray("sourceRefs").length());
        try(var review=f.review(proposal)){assertThrows(SecurityException.class,()->review.confirm(new RunControl()));JSONObject shown=review.view();assertTrue(shown.isNull("before"));assertEquals("assistant_proposed_summary",shown.getString("contentNature"));assertFalse(shown.getBoolean("truthVerified"));JSONObject saved=review.confirm(new RunControl());assertEquals("saved",saved.getString("status"));assertTrue(saved.getBoolean("formalMemorySaved"));JSONObject record=f.record(saved.getJSONObject("record").getString("id"));assertEquals(3,record.getJSONArray("sourceRefs").length());assertEquals("unverified",record.getString("validationStatus"));assertFalse(record.has("assertion"));assertThrows(SecurityException.class,()->review.confirm(new RunControl()));}
        assertEquals(1,f.records());
    }}
    @Test public void aCandidateEditAndAConcurrentRecordRevisionInvalidateOldConfirmations()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create());try(var review=f.review(proposal)){review.view();JSONObject pin=proposal.getJSONObject("candidate");f.memory.handle("editCandidate",new JSONObject().put("scope",scope()).put("id",pin.getString("id")).put("revision",pin.getLong("revision")).put("changes",new JSONObject().put("text","主人另行编辑后的内容。")));assertEquals("rejected",review.confirm(new RunControl()).getString("status"));assertEquals(0,f.records());}
        JSONObject original=f.formal(),change=f.propose(f.mutation("update",original));try(var review=f.review(change)){JSONObject view=review.view();assertEquals(original.getString("text"),view.getJSONObject("before").getString("text"));f.memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("id",original.getString("id")).put("revision",original.getLong("revision")).put("text","另一个界面刚保存的新版本。")));assertEquals("rejected",review.confirm(new RunControl()).getString("status"));assertEquals("另一个界面刚保存的新版本。",f.record(original.getString("id")).getString("text"));}
    }}
    @Test public void withdrawalBeforeConfirmationCannotSaveAndDiscardDoesNotResurrectSources()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create());try(var review=f.review(proposal)){review.view();f.assistant.handle("delete",new JSONObject().put("conversationId",f.conversation));assertEquals("rejected",review.confirm(new RunControl()).getString("status"));assertEquals(0,f.records());}
    }try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create());f.assistant.handle("delete",new JSONObject().put("conversationId",f.conversation));assertEquals("cancelled",f.host.cancelForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId"),proposal.getString("proposalDigest")).getJSONObject("apply").getString("status"));assertEquals(0,f.records());
    }}
    @Test public void lostCreateReceiptUsesTheOriginalAcceptedCandidateWithoutAnotherSave()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create());try(var db=f.sql();var review=f.review(proposal)){review.view();failSavedReceipt(db);try{JSONObject saved=review.confirm(new RunControl());assertEquals("saved",saved.getString("status"));assertFalse(saved.getBoolean("journalRecorded"));assertEquals(1,f.records());}finally{db.execSQL("DROP TRIGGER fixture_memory_receipt");}}
        assertEquals("applying_unknown",f.tasks.memoryProposalForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId")).getJSONObject("apply").getString("status"));JSONObject settled=f.host.queryForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId"));assertEquals("saved",settled.getJSONObject("apply").getString("status"));assertTrue(settled.getJSONObject("apply").getBoolean("observedFromOriginalCandidate"));assertEquals(1,f.records());assertThrows(SecurityException.class,()->f.review(proposal));
    }}
    @Test public void unknownCorrectionOrForgetDoesNotReplayBasedOnCurrentTextOrAbsence()throws Exception{
        for(String action:new String[]{"update","forget"})try(Fixture f=new Fixture()){
            JSONObject original=f.formal(),proposal=f.propose(f.mutation(action,original));try(var db=f.sql();var review=f.review(proposal)){JSONObject view=review.view();if(action.equals("forget")){assertTrue(view.getBoolean("forgetSameEvidenceMemory"));assertTrue(view.getBoolean("blocksOldSourceAutomaticReimport"));}failSavedReceipt(db);try{JSONObject saved=review.confirm(new RunControl());assertEquals("saved",saved.getString("status"));assertFalse(saved.getBoolean("journalRecorded"));}finally{db.execSQL("DROP TRIGGER fixture_memory_receipt");}}
            JSONObject query=f.host.queryForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId"));assertEquals("applying_unknown",query.getJSONObject("apply").getString("status"));assertFalse(query.getBoolean("replayAllowed"));assertThrows(SecurityException.class,()->f.review(proposal));if(action.equals("update"))assertEquals(2,f.record(original.getString("id")).getLong("revision"));else assertEquals(0,f.records());
        }
    }
    @Test public void explicitDiscardRejectsOnlyTheOriginalUnchangedPendingCandidate()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create());JSONObject cancelled=f.host.cancelForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId"),proposal.getString("proposalDigest"));assertEquals("cancelled",cancelled.getJSONObject("apply").getString("status"));assertEquals("rejected",cancelled.getString("candidateReviewStatus"));assertEquals(0,f.records());
    }}
    @Test public void fabricatedUserIdentityAndAuthorityFieldsCannotStageFormalMemory()throws Exception{try(Fixture f=new Fixture()){
        JSONObject forged=f.create().put("confirmed",true);assertThrows(SecurityException.class,()->NativeConversationMemoryDraftHost.checkedDraft(forged));JSONObject draft=f.create();draft.getJSONArray("sources").getJSONObject(0).put("sourceId",id());String operation=f.journal().beginOperation("memory_propose",draft);assertThrows(Exception.class,()->f.host.proposeForTask(f.task(),f.run(),f.epoch(),operation,draft,f.original));assertEquals(0,f.records());assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }}
    @Test public void nativeDraftLinksStayBodyFreeAndFollowTheOriginalApplyReceipt()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create()),before=f.tasks.listConversationDraftsForOwner(f.task());assertFalse(before.getBoolean("bodyIncluded"));assertEquals(1,before.getJSONArray("drafts").length());JSONObject link=before.getJSONArray("drafts").getJSONObject(0);assertEquals(proposal.getString("operationId"),link.getString("operationId"));assertEquals("memory",link.getString("kind"));assertEquals("pending_owner",link.getJSONObject("apply").getString("status"));assertFalse(before.toString().contains(f.create().getString("text")));assertFalse(link.has("arguments"));assertFalse(link.has("sourceRefs"));
        assertThrows(SecurityException.class,()->f.tasks.memoryProposalForOwner(f.task(),id(),f.epoch(),proposal.getString("operationId")));assertThrows(SecurityException.class,()->f.tasks.memoryProposalForOwner(f.task(),f.run(),f.epoch()+1,proposal.getString("operationId")));
        try(var review=f.review(proposal)){review.view();assertEquals("saved",review.confirm(new RunControl()).getString("status"));}
        f.journal().finishOperation(proposal.getString("operationId"),new JSONObject().put("ok",false).put("code","late_model_report"));
        JSONObject after=f.tasks.listConversationDraftsForOwner(f.task());assertEquals("saved",after.getJSONArray("drafts").getJSONObject(0).getJSONObject("apply").getString("status"));assertEquals(proposal.getString("proposalDigest"),f.tasks.memoryProposalForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId")).getJSONObject("proposal").getString("proposalDigest"));assertEquals(1,f.records());
    }}
    @Test public void aSavedReceiptRequiresItsSingleDurableOriginalClaim()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.create());String operation=proposal.getString("operationId"),reviewDigest="b".repeat(64);JSONObject saved=new JSONObject().put("taskId",f.task()).put("runId",f.run()).put("epoch",f.epoch()).put("operationId",operation).put("proposalDigest",proposal.getString("proposalDigest")).put("reviewDigest",reviewDigest).put("status","saved").put("action","create").put("accepted",true).put("mutationApplied",true).put("formalMemorySaved",true).put("executionAuthorized",false).put("replayAllowed",false).put("record",new JSONObject().put("id",id()).put("revision",1));
        assertThrows(SecurityException.class,()->f.tasks.recordMemoryProposalApplyForOwner(f.task(),f.run(),f.epoch(),operation,reviewDigest,saved));assertTrue(f.tasks.claimMemoryProposalApplyForOwner(f.task(),f.run(),f.epoch(),operation,proposal.getString("proposalDigest"),reviewDigest));assertFalse(f.tasks.claimMemoryProposalApplyForOwner(f.task(),f.run(),f.epoch(),operation,proposal.getString("proposalDigest"),reviewDigest));assertEquals("applying_unknown",f.tasks.memoryProposalForOwner(f.task(),f.run(),f.epoch(),operation).getJSONObject("apply").getString("status"));assertEquals(0,f.records());
    }}
    @Test public void projectScopedTasksCannotUseOrdinaryConversationMemoryWrites()throws Exception{for(String scopeKey:new String[]{"developmentContext","projectMemoryRead"})try(Fixture f=new Fixture()){
        JSONObject current=f.tasks.get(f.task(),false).put(scopeKey,new JSONObject().put("projectId",id()));try(var db=f.sql()){android.content.ContentValues update=new android.content.ContentValues();update.put("document",current.toString());assertEquals(1,db.update("tasks",update,"task_id=?",new String[]{f.task()}));}
        assertThrows(SecurityException.class,()->f.host.sourcesForTask(f.task(),f.run(),f.epoch(),f.original));JSONObject draft=f.create();String operation=f.journal().beginOperation("memory_propose",draft);assertThrows(SecurityException.class,()->f.host.proposeForTask(f.task(),f.run(),f.epoch(),operation,draft,f.original));assertEquals(0,f.records());assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }}
    private static void failSavedReceipt(SQLiteDatabase db){db.execSQL("CREATE TRIGGER fixture_memory_receipt BEFORE UPDATE OF receipt ON operations WHEN NEW.tool='memory_propose' AND instr(NEW.receipt,'\"status\":\"saved\"')>0 BEGIN SELECT RAISE(ABORT,'synthetic original receipt write failure'); END");}
}
