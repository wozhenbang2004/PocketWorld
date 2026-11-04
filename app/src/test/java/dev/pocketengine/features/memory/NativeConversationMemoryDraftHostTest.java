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
        final Context android;final TaskStore tasks;final MemoryModule memory;final AssistantModule assistant;final FeatureHost features;final MemorySourceBridge bridge;final NativeConversationMemoryDraftHost host;final RunControl original=new RunControl();final JSONObject task;final String conversation=id();final JSONArray sourceSelection=new JSONArray();final String[] originalTexts;private boolean ready=true;
        Fixture()throws Exception {this(new String[]{"准备出行时先检查天气。","再按气温决定携带衣物。","就记这个流程，保留不确定条件。"});}
        Fixture(String[] texts)throws Exception {
            originalTexts=texts.clone();
            File home=Files.createTempDirectory("conversation-memory-fixture-").toFile();android=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public File getFilesDir(){return home;}@Override public Context getApplicationContext(){return this;}};
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android,()->ready,(stage,version)->{});FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});features=new FeatureHost(event->{});bridge=new MemorySourceBridge(features,tasks);memory=new MemoryModule(context,bridge.assertionResolver());assistant=new AssistantModule(context,memory,null,tasks);
            JSONArray messages=new JSONArray();var repo=tasks.conversationRepository();repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Synthetic multi-turn review").put("messages",new JSONArray()));for(String content:originalTexts){String message=id();repo.saveSource(new SourceRef("message",message,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));repo.saveMessage(conversation,new JSONObject().put("id",message).put("revision",1).put("role","user").put("content",content));messages.put(new JSONObject().put("role","user").put("content",content).put("ownerMessageId",message).put("ownerMessageRevision",1));sourceSelection.put(new JSONObject().put("sourceId",message).put("revision",1));}return null;});
            task=tasks.startAttempt(tasks.createCapabilityTask("提出多轮记忆草稿",conversation).getString("id"),"capabilities",30);original.bindHostRun(task(),run(),epoch());tasks.openOwnerInputMailbox(task(),run(),epoch(),conversation,original);tasks.authorizeTools(task(),run(),new JSONArray().put("memory_propose").put("memory_draft_sources"));tasks.status(task(),"running","Synthetic original proposal");
            features.register(memory);features.register(assistant);features.register(new dev.pocketengine.features.skills.SkillsModule(context));bridge.bindAssistant(assistant);assistant.setSourceSynchronizer(()->bridge.synchronize(scope()));host=new NativeConversationMemoryDraftHost(tasks,memory,bridge);
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
        JSONObject typed(String kind,int source,String quote)throws Exception{return new JSONObject().put("action","create").put("layer","preference").put("text",quote).put("reason","保留本次真实原句和条件，等待主人审阅").put("sources",new JSONArray().put(sourceSelection.getJSONObject(source)))
                .put("assertion",new JSONObject().put("kind",kind).put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","饮食偏好").put("object",quote).put("quote",quote).put("offset",originalTexts[source].indexOf(quote)).put("sourceIndex",0));}
        JSONObject accept(JSONObject draft)throws Exception{JSONObject proposal=propose(draft);try(var review=review(proposal)){review.view();JSONObject result=review.confirm(new RunControl());assertEquals("saved",result.getString("status"));return record(result.getJSONObject("record").getString("id"));}}
        void editSource(int index,String replacement)throws Exception{assistant.handle("editMessage",new JSONObject().put("conversationId",conversation).put("messageId",sourceSelection.getJSONObject(index).getString("sourceId")).put("revision",1).put("content",replacement));}
        int records()throws Exception{return memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length();}
        @Override public void close()throws Exception{original.cancel();features.close();ready=false;tasks.closeForMaintenance();}
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
    @Test public void typedOriginalQuoteSurvivesNativeSchemaCandidateReviewConfirmationAndColdProjection()throws Exception {
        String quote="工作日，我喜欢清淡口味。";try(Fixture f=new Fixture(new String[]{"前言🙂"+quote+"后记","这是我的当前饮食偏好。"})){
            long now=System.currentTimeMillis();JSONObject draft=f.typed("user_said",0,quote);draft.getJSONObject("assertion").put("condition","工作日").put("validFrom",now-1000).put("validTo",now+86_400_000).put("reviewAfter",now+3_600_000);
            JSONObject schema=new JSONObject().put("type","object").put("properties",NativeConversationMemoryDraftHost.proposalProperties()).put("required",new JSONArray().put("action").put("sources").put("reason")).put("additionalProperties",false);
            dev.pocketengine.features.plugins.PluginRules.validateArguments(draft,schema);
            JSONObject proposal=f.propose(draft);assertEquals(0,f.records());JSONObject saved;
            try(var review=f.review(proposal)){
                JSONObject shown=review.view(),assertion=shown.getJSONObject("after").getJSONObject("assertion"),span=assertion.getJSONArray("evidenceSpans").getJSONObject(0);
                assertEquals("user_said",assertion.getString("kind"));assertEquals(quote,assertion.getString("object"));assertEquals("工作日",assertion.getString("condition"));assertEquals(now+86_400_000,assertion.getLong("validTo"));assertEquals(now+3_600_000,assertion.getLong("reviewAfter"));assertTrue(assertion.isNull("assertedAt"));assertTrue(assertion.isNull("eventTime"));
                assertEquals(f.originalTexts[0].indexOf(quote),span.getInt("offset"));assertEquals(quote.length(),span.getInt("chars"));assertEquals(TaskMemoryUse.digest(quote),span.getString("sha256"));assertEquals(f.sourceSelection.getJSONObject(0).getString("sourceId"),span.getJSONObject("sourceRef").getString("sourceId"));assertFalse(shown.getBoolean("truthVerified"));
                JSONObject original=shown.getJSONObject("after").getJSONArray("assertionEvidence").getJSONObject(0);assertEquals(quote,original.getString("text"));assertTrue(original.getBoolean("verifiedOriginalSpan"));assertFalse(original.getBoolean("assertionTruthVerified"));assertEquals(4,span.length());
                JSONObject receipt=review.confirm(new RunControl());assertEquals("saved",receipt.getString("status"));saved=f.record(receipt.getJSONObject("record").getString("id"));assertEquals(MemoryArchive.canonical(assertion),MemoryArchive.canonical(saved.getJSONObject("assertion")));
            }
            assertFalse(f.memory.automaticLearningPolicy().getBoolean("enabled"));assertFalse(saved.getBoolean("core"));assertEquals("unverified",saved.getString("validationStatus"));assertFalse(saved.has("assertionEvidence"));
            assertTrue(f.memory.ownerContextSnapshot(scope(),"无关的新问题",16,12000).getString("context").contains(quote));
            MemoryBook cold=new MemoryBook(new File(f.android.getFilesDir(),"memory/records.json"),f.bridge.assertionResolver());assertEquals(saved.getString("id"),cold.cognitionProjection(scope(),"owner",System.currentTimeMillis()).getJSONArray("current").getJSONObject(0).getString("id"));assertEquals(MemoryArchive.canonical(saved.getJSONObject("assertion")),MemoryArchive.canonical(cold.get(scope(),saved.getString("id")).getJSONObject("assertion")));cold.closeRetrieval();
        }
    }
    @Test public void typedCandidateSemanticEditInvalidatesTheOriginalDigestAndCannotDropItsFields()throws Exception {
        String quote="我喜欢清淡口味。";try(Fixture f=new Fixture(new String[]{quote})){
            JSONObject proposal=f.propose(f.typed("inferred",0,quote));try(var review=f.review(proposal)){
                JSONObject before=review.view().getJSONObject("after"),assertion=new JSONObject(before.getJSONObject("assertion").toString()).put("condition","仅午餐");
                f.memory.handle("editCandidate",new JSONObject().put("scope",scope()).put("id",before.getString("id")).put("revision",before.getLong("revision")).put("changes",new JSONObject().put("assertion",assertion)));
                assertEquals("rejected",review.confirm(new RunControl()).getString("status"));assertEquals(0,f.records());
                assertEquals("仅午餐",f.memory.handle("getCandidate",new JSONObject().put("scope",scope()).put("id",before.getString("id"))).getJSONObject("candidate").getJSONObject("assertion").getString("condition"));
            }
        }
    }
    @Test public void typedSameTextCorrectionBindsNewQuoteRetainsOldSourceAndRetiresOldContext()throws Exception {
        String quote="我喜欢清淡口味。",correction="从下周开始，只在周末按这个口味点餐。";try(Fixture f=new Fixture(new String[]{quote,correction})){
            JSONObject old=f.accept(f.typed("user_said",0,quote)),dependency=f.memory.contextDependency(f.memory.ownerContextSnapshot(scope(),"",16,12000));
            JSONObject derived=f.memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","从原饮食偏好派生的建议").put("sourceIds",new JSONArray().put(old.getString("id"))))).getJSONObject("record");
            long validFrom=System.currentTimeMillis()+604_800_000;JSONObject change=f.mutation("update",old).put("text",quote).put("sources",new JSONArray().put(f.sourceSelection.getJSONObject(1)));
            JSONObject claim=f.typed("inferred",1,correction).getJSONObject("assertion").put("object",quote).put("condition","仅周末").put("validFrom",validFrom).put("reviewAfter",validFrom+86_400_000);change.put("assertion",claim);
            JSONObject proposal=f.propose(change);try(var review=f.review(proposal)){
                JSONObject shown=review.view();assertEquals(quote,shown.getJSONObject("before").getString("text"));assertEquals(quote,shown.getJSONObject("after").getString("text"));assertEquals(2,shown.getJSONObject("after").getJSONArray("sourceRefs").length());assertEquals("仅周末",shown.getJSONObject("after").getJSONObject("assertion").getString("condition"));
                assertEquals("saved",review.confirm(new RunControl()).getString("status"));
            }
            JSONObject current=f.record(old.getString("id"));assertEquals(2,current.getLong("revision"));assertEquals(2,current.getJSONArray("sourceRefs").length());assertEquals(validFrom,current.getJSONObject("assertion").getLong("validFrom"));assertEquals(f.sourceSelection.getJSONObject(1).getString("sourceId"),current.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0).getJSONObject("sourceRef").getString("sourceId"));
            assertThrows(IllegalArgumentException.class,()->f.record(derived.getString("id")));assertThrows(SecurityException.class,()->f.memory.requireContextDependency(dependency));assertEquals(0,f.memory.ownerContextSnapshot(scope(),"",16,12000).getJSONArray("records").length());
            assertThrows(IllegalArgumentException.class,()->f.memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("id",current.getString("id")).put("revision",2).put("text",quote).put("sourceRefs",new JSONArray().put(new SourceRef("message",f.sourceSelection.getJSONObject(1).getString("sourceId"),1,"owner","private").toJson())))));
        }
    }
    @Test public void typedProposalRejectsInventedQuotesIndexesProofAndStableOtherPersonUpdates()throws Exception {
        String quote="我喜欢清淡口味。";
        // A refused dispatch still has its original prepared operation. Each adversarial
        // input uses a fresh native run; the fixture must not bypass that reconciliation gate.
        for(String invalid:new String[]{"offset","quote","sourceIndex"})try(Fixture f=new Fixture(new String[]{"🙂"+quote})){
            JSONObject draft=f.typed("inferred",0,quote);draft.getJSONObject("assertion").put(invalid,invalid.equals("offset")?0:invalid.equals("sourceIndex")?1:"原消息中没有这句话");
            if(invalid.equals("sourceIndex"))assertThrows(IllegalArgumentException.class,()->f.propose(draft));else assertThrows(SecurityException.class,()->f.propose(draft));
            assertEquals(0,f.records());assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
        }
        try(Fixture f=new Fixture(new String[]{"🙂"+quote})){
            for(String key:new String[]{"evidenceSpans","sourceRef","assertedAt","objectId"}){JSONObject authority=f.typed("inferred",0,quote);authority.getJSONObject("assertion").put(key,1);assertThrows(SecurityException.class,()->NativeConversationMemoryDraftHost.checkedDraft(authority));}
            assertEquals(0,f.records());assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
            JSONObject current=f.accept(f.typed("inferred",0,quote)),foreign=f.mutation("update",current).put("assertion",f.typed("told",0,quote).getJSONObject("assertion").put("subjectId","person:guessed").put("subjectType","person"));assertThrows(SecurityException.class,()->f.propose(foreign));assertEquals(1,f.record(current.getString("id")).getLong("revision"));
        }
    }
    @Test public void reselectingTheSameOriginalQuoteCanCorrectOnlyItsConditionsWhileOldDerivedContextRetires()throws Exception {
        String quote="工作日，我喜欢清淡口味。";try(Fixture f=new Fixture(new String[]{quote})){
            JSONObject old=f.accept(f.typed("user_said",0,quote)),dependency=f.memory.contextDependency(f.memory.ownerContextSnapshot(scope(),"",16,12000));
            JSONObject change=f.mutation("update",old).put("text",quote).put("assertion",f.typed("user_said",0,quote).getJSONObject("assertion").put("condition","工作日"));
            JSONObject proposal=f.propose(change);try(var review=f.review(proposal)){review.view();assertEquals("saved",review.confirm(new RunControl()).getString("status"));}
            JSONObject current=f.record(old.getString("id"));assertEquals("user_said",current.getJSONObject("assertion").getString("kind"));assertEquals("工作日",current.getJSONObject("assertion").getString("condition"));assertEquals(2,current.getLong("revision"));
            assertThrows(SecurityException.class,()->f.memory.requireContextDependency(dependency));assertTrue(f.memory.ownerContextSnapshot(scope(),"",16,12000).getString("context").contains(quote));
            SourceRef source=SourceRef.fromJson(current.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0).getJSONObject("sourceRef"));assertEquals(quote,f.assistant.readMemorySource(source).text);
        }
    }
    @Test public void actualQuoteEditAfterReviewRejectsConfirmationAndRetiresAlreadyPreparedFormalContext()throws Exception {
        String quote="我喜欢清淡口味。";try(Fixture f=new Fixture(new String[]{quote})){
            JSONObject proposal=f.propose(f.typed("user_said",0,quote));try(var review=f.review(proposal)){review.view();f.editSource(0,"我现在偏好少盐。");assertEquals("rejected",review.confirm(new RunControl()).getString("status"));assertEquals(0,f.records());}
        }
        try(Fixture f=new Fixture(new String[]{quote})){
            f.accept(f.typed("user_said",0,quote));JSONObject dependency=f.memory.contextDependency(f.memory.ownerContextSnapshot(scope(),"",16,12000));f.editSource(0,"我现在偏好少盐。");
            assertThrows(SecurityException.class,()->f.memory.requireContextDependency(dependency));assertEquals(0,f.memory.ownerContextSnapshot(scope(),"",16,12000).getJSONArray("records").length());
        }
    }
    @Test public void reviewedForgetStopsReproposalFromTheSameRealQuoteWithoutDeletingTheOriginalMessage()throws Exception {
        String quote="我喜欢清淡口味。";try(Fixture f=new Fixture(new String[]{quote})){
            JSONObject record=f.accept(f.typed("user_said",0,quote)),dependency=f.memory.contextDependency(f.memory.ownerContextSnapshot(scope(),"",16,12000));
            f.bridge.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertThrows(SecurityException.class,()->f.memory.requireContextDependency(dependency));assertEquals(0,f.records());
            SourceRef source=new SourceRef("message",f.sourceSelection.getJSONObject(0).getString("sourceId"),1,"owner","private");assertEquals(quote,f.tasks.conversationRepository().message(f.conversation,source.sourceId).getString("content"));assertTrue(f.memory.isSourceForgotten(scope(),source));assertThrows(Exception.class,()->f.propose(f.typed("user_said",0,quote)));
        }
    }
    private static void failSavedReceipt(SQLiteDatabase db){db.execSQL("CREATE TRIGGER fixture_memory_receipt BEFORE UPDATE OF receipt ON operations WHEN NEW.tool='memory_propose' AND instr(NEW.receipt,'\"status\":\"saved\"')>0 BEGIN SELECT RAISE(ABORT,'synthetic original receipt write failure'); END");}
}
