package dev.pocketengine.features.skills;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.OwnerSourceRequestProjection;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.util.Set;
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

/** Original SQLite, projection, skill files and confirmation journal. No fake source grants. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeConversationSkillDraftHostTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static String manifest(){return "---\nname: review-fixture\ndescription: 核对原材料后保存说明\n---\n保留未知和失败，不声称未执行的结果。\n";}
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks;final FeatureHost featureHost;final SkillsModule skills;final AssistantModule assistant;final NativeConversationSkillDraftHost host;final RunControl control=new RunControl();final JSONObject task;final String conversation=id(),message=id();
        Fixture()throws Exception {
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});AtomicReference<AssistantModule> original=new AtomicReference<>();MemoryModule memory=new MemoryModule(context,(owner,kind,source)->original.get().resolveSource(owner,source));assistant=new AssistantModule(context,memory,null,tasks);original.set(assistant);
            var repo=tasks.conversationRepository();repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Synthetic skill draft").put("messages",new JSONArray()));JSONObject source=new SourceRef("message",message,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false);repo.saveSource(source);repo.saveMessage(conversation,new JSONObject().put("id",message).put("revision",1).put("role","user").put("content","请根据这些真实约束生成一个待审阅技能"));return null;});
            task=tasks.startAttempt(tasks.createCapabilityTask("生成技能草稿",conversation).getString("id"),"capabilities",30);control.bindHostRun(task(),run(),epoch());tasks.openOwnerInputMailbox(task(),run(),epoch(),conversation,control);tasks.authorizeTools(task(),run(),new JSONArray().put("skills_propose").put("skills_draft_sources").put("skills_read"));tasks.status(task(),"running","Synthetic original draft operation");
            skills=new SkillsModule(context,new SkillBook(Files.createTempDirectory("skill-conversation-fixture-").toFile()),new SkillGitClient());featureHost=new FeatureHost(event->{});featureHost.register(memory);featureHost.register(assistant);featureHost.register(skills);MemorySourceBridge bridge=new MemorySourceBridge(featureHost,tasks);bridge.bindAssistant(assistant);host=new NativeConversationSkillDraftHost(tasks,skills,(refs,action)->bridge.withCurrentUserSources(refs,action));
            JSONObject nativeRow=new JSONObject().put("role","user").put("content","请根据这些真实约束生成一个待审阅技能").put("ownerMessageId",message).put("ownerMessageRevision",1);JSONArray rows=new JSONArray().put(nativeRow);ModelConfig config=new ModelConfig("http://127.0.0.1","","fixture",false,30,10);
            try(var projection=new OwnerSourceRequestProjection(assistant,control,conversation,null,(carriers,parts,live)->tasks.recordOwnerSourceExposure(task(),run(),epoch(),live,carriers,parts))){projection.prepared(rows,projection.project(rows,config,control),config,control);}
        }
        String task()throws Exception{return task.getString("id");}String run()throws Exception{return task.getString("runId");}int epoch()throws Exception{return task.getInt("coordinatorEpoch");}
        RunJournal journal()throws Exception{return tasks.journal(task(),run(),epoch(),control);}
        SQLiteDatabase sql(){return SQLiteDatabase.openDatabase(android.getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);}
        JSONObject draft()throws Exception{return new JSONObject().put("action","create").put("manifest",manifest()).put("resources",new JSONArray().put(new JSONObject().put("path","references/details.md").put("content","完整的新文件"))).put("sources",new JSONArray().put(new JSONObject().put("sourceId",message).put("revision",1))).put("reason","保存主人明确要求的独立技能，启用仍另行确认");}
        JSONObject propose(JSONObject draft)throws Exception{String operation=journal().beginOperation("skills_propose",draft);JSONObject receipt=host.proposeForTask(task(),run(),epoch(),operation,draft,control);journal().finishOperation(operation,receipt);return receipt.getJSONObject("skillDraft");}
        NativeConversationSkillDraftHost.Review review(JSONObject proposal)throws Exception{return host.reviewForOwner(task(),run(),epoch(),proposal.getString("operationId"),proposal.getString("proposalDigest"));}
        void allFiles(NativeConversationSkillDraftHost.Review review)throws Exception{JSONObject view=review.view();for(String side:new String[]{"before","after"}){JSONArray files=view.getJSONArray(side+"Files");for(int i=0;i<files.length();i++){String path=files.getJSONObject(i).getString("path");int offset=0;while(true){JSONObject page=review.readFile(side,path,offset,12000);if(!page.getBoolean("hasMore"))break;assertTrue(page.getInt("nextOffset")>offset);offset=page.getInt("nextOffset");}}}assertTrue(review.view().getBoolean("allFilePagesProvided"));}
        @Override public void close()throws Exception{featureHost.close();}
    }
    @Test public void originalProposalIsInertAndRequiresCompleteOpaqueOwnerReview()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.draft());assertFalse(proposal.getBoolean("formalSkillSaved"));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(1,f.host.sourcesForTask(f.task(),f.run(),f.epoch(),f.control).getJSONArray("sources").length());
        try(var review=f.review(proposal)){assertThrows(SecurityException.class,()->review.confirm(new RunControl()));assertEquals("pending_owner",f.host.queryForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId")).getJSONObject("apply").getString("status"));f.allFiles(review);JSONObject saved=review.confirm(new RunControl());assertEquals("saved",saved.getString("status"));assertFalse(saved.getJSONObject("skill").getBoolean("enabled"));assertTrue(saved.getBoolean("formalSkillSaved"));assertThrows(SecurityException.class,()->review.confirm(new RunControl()));}
        assertEquals(1,f.skills.snapshot().getJSONArray("skills").length());assertEquals("saved",new TaskStore(f.android).skillProposalForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId")).getJSONObject("apply").getString("status"));
    }}
    @Test public void lostReceiptQueriesActualNativeMarkerWithoutASecondImport()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.draft());try(var db=f.sql();var review=f.review(proposal)){f.allFiles(review);db.execSQL("CREATE TRIGGER fixture_skill_apply_receipt BEFORE UPDATE OF receipt ON operations WHEN NEW.tool='skills_propose' AND instr(NEW.receipt,'\"formalSkillSaved\":true')>0 BEGIN SELECT RAISE(ABORT,'synthetic receipt storage failure'); END");try{JSONObject saved=review.confirm(new RunControl());assertEquals("saved",saved.getString("status"));assertFalse(saved.getBoolean("journalRecorded"));assertEquals("applying_unknown",f.tasks.skillProposalForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId")).getJSONObject("apply").getString("status"));}finally{db.execSQL("DROP TRIGGER fixture_skill_apply_receipt");}}
        assertThrows(SecurityException.class,()->f.review(proposal));assertEquals("saved",f.host.queryForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId")).getJSONObject("apply").getString("status"));assertEquals(1,f.skills.snapshot().getJSONArray("skills").length());assertEquals(1,f.skills.snapshot().getJSONArray("skills").getJSONObject(0).getInt("revision"));
    }}
    @Test public void originalJournalAndOwnerPagesKeepLongManifestAndResourceWithoutSanitizing()throws Exception{try(Fixture f=new Fixture()){
        String longManifest=manifest()+"原约束须完整审阅。".repeat(1800),longResource="原材料仅作为待审阅数据。".repeat(1800);assertTrue(longManifest.length()>12000);assertTrue(longResource.length()>12000);
        JSONObject input=f.draft().put("manifest",longManifest).put("resources",new JSONArray().put(new JSONObject().put("path","references/long.md").put("content",longResource)));assertTrue(input.toString().length()<=131072);
        JSONObject proposal=f.propose(input),journal=f.tasks.skillProposalForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId"));assertEquals(longManifest,journal.getJSONObject("arguments").getString("manifest"));assertEquals(longResource,journal.getJSONObject("arguments").getJSONArray("resources").getJSONObject(0).getString("content"));assertEquals(NativeConversationSkillDraftHost.proposalDigest(input),proposal.getString("proposalDigest"));
        try(var review=f.review(proposal)){JSONObject first=review.readFile("after","references/long.md",0,12000);assertTrue(first.getBoolean("hasMore"));assertThrows(SecurityException.class,()->review.confirm(new RunControl()));StringBuilder received=new StringBuilder(first.getString("content"));int offset=first.getInt("nextOffset");while(offset<longResource.length()){JSONObject page=review.readFile("after","references/long.md",offset,12000);received.append(page.getString("content"));assertTrue(page.getInt("nextOffset")>offset);offset=page.getInt("nextOffset");}assertEquals(longResource,received.toString());f.allFiles(review);assertEquals("saved",review.confirm(new RunControl()).getString("status"));}
    }}
    @Test public void multipleReviewsCanConsumeTheOriginalOwnerConfirmationOnlyOnce()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.draft());try(var first=f.review(proposal);var stale=f.review(proposal)){f.allFiles(first);f.allFiles(stale);assertEquals("saved",first.confirm(new RunControl()).getString("status"));assertThrows(SecurityException.class,()->stale.confirm(new RunControl()));}assertEquals(1,f.skills.snapshot().getJSONArray("skills").length());
    }}
    @Test public void sourceWithdrawalBlocksSavingButStillAllowsExplicitDiscard()throws Exception{try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.draft());try(var review=f.review(proposal)){f.allFiles(review);f.assistant.handle("delete",new JSONObject().put("conversationId",f.conversation));JSONObject result=review.confirm(new RunControl());assertEquals("rejected",result.getString("status"));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());}
    }try(Fixture f=new Fixture()){
        JSONObject proposal=f.propose(f.draft());f.assistant.handle("delete",new JSONObject().put("conversationId",f.conversation));assertEquals("cancelled",f.host.cancelForOwner(f.task(),f.run(),f.epoch(),proposal.getString("operationId"),proposal.getString("proposalDigest")).getJSONObject("apply").getString("status"));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());
    }}
    @Test public void inventedSourceAndUnpreparedOperationCannotCreateAReviewableProposal()throws Exception{try(Fixture f=new Fixture()){
        JSONObject input=f.draft();assertThrows(SecurityException.class,()->f.host.proposeForTask(f.task(),f.run(),f.epoch(),id(),input,f.control));input.getJSONArray("sources").getJSONObject(0).put("sourceId",id());String operation=f.journal().beginOperation("skills_propose",input);assertThrows(Exception.class,()->f.host.proposeForTask(f.task(),f.run(),f.epoch(),operation,input,f.control));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());
    }}
    @Test public void updateRequiresEveryActualOriginalFilePageIncludingEmptyResources()throws Exception{try(Fixture f=new Fixture()){
        JSONObject old=f.skills.handle("import",new JSONObject().put("source","local:synthetic-edit-target").put("manifest",manifest()+"有界原说明。".repeat(3000)).put("resources",new JSONObject().put("references/empty.md",""))).getJSONObject("skill");f.skills.handle("setEnabled",new JSONObject().put("id",old.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",old.getString("digest")));
        var selected=f.skills.selectReferenceForHost(scope(),old.getString("id"),1,old.getString("digest"),Set.of("skills_read","skills_propose"));JSONObject page=f.skills.readForHost(scope(),old.getString("id"),"SKILL.md",0,12000,1,old.getString("digest"),Set.of("skills_read","skills_propose"));assertTrue(page.getBoolean("hasMore"));f.tasks.recordSkillReferenceRead(f.task(),f.run(),f.epoch(),selected,"SKILL.md",0,page.getInt("nextOffset"),f.control);
        JSONObject change=f.draft().put("action","update").put("target",new JSONObject().put("id",old.getString("id")).put("revision",1).put("digest",old.getString("digest")));String attempted=f.journal().beginOperation("skills_propose",change);assertThrows(SecurityException.class,()->f.host.proposeForTask(f.task(),f.run(),f.epoch(),attempted,change,f.control));f.journal().finishOperation(attempted,new JSONObject().put("ok",false).put("accepted",false).put("code","full_original_read_required"));
        int offset=page.getInt("nextOffset");while(page.getBoolean("hasMore")){page=f.skills.readForHost(scope(),old.getString("id"),"SKILL.md",offset,12000,1,old.getString("digest"),Set.of("skills_read","skills_propose"));f.tasks.recordSkillReferenceRead(f.task(),f.run(),f.epoch(),selected,"SKILL.md",offset,page.getInt("nextOffset"),f.control);offset=page.getInt("nextOffset");}
        JSONObject empty=f.skills.readForHost(scope(),old.getString("id"),"references/empty.md",0,12000,1,old.getString("digest"),Set.of("skills_read","skills_propose"));assertEquals("",empty.getString("content"));f.tasks.recordSkillReferenceRead(f.task(),f.run(),f.epoch(),selected,"references/empty.md",0,empty.getInt("nextOffset"),f.control);
        JSONObject proposal=f.propose(change);try(var review=f.review(proposal)){f.allFiles(review);JSONObject saved=review.confirm(new RunControl());assertEquals("saved",saved.getString("status"));assertEquals(old.getString("id"),saved.getJSONObject("skill").getString("id"));assertEquals(2,saved.getJSONObject("skill").getInt("revision"));assertFalse(saved.getJSONObject("skill").getBoolean("enabled"));}
    }}
}
