package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class KnowledgeLearningWorkflowTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static SourceRef ref(long revision){return new SourceRef("message","actual-message",revision,"owner","private");}
    private static JSONObject candidate()throws Exception{return new JSONObject().put("layer","procedural").put("text","备份前核对目录，完成后核对文件数量")
            .put("reason","选中的真实消息描述了这条做法").put("applicability","仅相同目录结构的备份任务；仍需实际验证").put("confidence",0.6);}
    private static JSONObject skill()throws Exception{return new JSONObject().put("manifest","---\nname: backup-verification\ndescription: 备份文件前后核对\n---\n仅用于相同目录结构，未经独立验证。先核对目录，完成后核对数量，异常就停止并请主人检查。");}
    private interface Checked {void run()throws Exception;}
    private static void rejected(Checked action)throws Exception{try{action.run();fail("must fail closed");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}}
    private static final class Fixture {
        SourceRef.State current=new SourceRef.State(ref(1),false);
        String body="先核对目标目录再备份，结束后核对文件数量。";
        final Application app=RuntimeEnvironment.getApplication();
        boolean failNotification;
        final FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{if(failNotification)throw new IllegalStateException("test notification unavailable");});
        final MemoryModule memory=new MemoryModule(context,(scope,kind,id)->current);
        final SkillsModule skills=new SkillsModule(context,memory::resolveExperience);
        int calls;
        Fixture()throws Exception{}
        KnowledgeLearningWorkflow flow(KnowledgeLearningWorkflow.ModelClient model){return new KnowledgeLearningWorkflow(memory,skills,expected->current!=null&&!current.deleted&&expected.equals(current.ref)
                ?new KnowledgeLearningWorkflow.SourceDocument(current.ref,"原对话消息",body):null,(messages,control)->{calls++;return model.complete(messages,control);},new dev.pocketengine.features.memory.MemoryHostFence(){
                    public void check(){}public JSONObject admit(Admission action)throws Exception{synchronized(Fixture.this){return action.run();}}
                });}
        KnowledgeLearningWorkflow flow(JSONObject content){return flow((messages,control)->new JSONObject().put("role","assistant").put("content",content.toString()));}
        JSONObject accepted()throws Exception{JSONObject proposed=flow(candidate()).proposeLearning(scope(),ref(1),new RunControl()).getJSONObject("candidate");
            return memory.acceptCandidate(scope(),proposed.getString("id"),proposed.getLong("revision")).getJSONObject("record");}
    }
    @Test public void automaticUserModeIsOffByDefaultAndOnlyStagesExactQuotes()throws Exception{
        Fixture f=new Fixture();var policy=f.memory.automaticLearningPolicyForHost();JSONObject selected=policy.snapshot();String task="11111111-1111-1111-1111-111111111111";
        var verifier=(KnowledgeLearningWorkflow.UserSourceVerifier)(original,source,control)->{if(!task.equals(original)||!source.equals(f.current.ref))throw new SecurityException("not_original_user_message");};
        JSONObject off=f.flow(candidate()).proposeAutomaticUserLearning(scope(),ref(1),task,verifier,policy,selected,new RunControl());assertEquals("automatic_mode_disabled",off.getString("outcome"));assertEquals(0,f.calls);
        selected=policy.setForOwner(0,true,3);JSONObject answer=candidate().put("quote",f.body).put("text","模型虚构了未说过的地址");JSONObject result=f.flow(answer).proposeAutomaticUserLearning(scope(),ref(1),task,verifier,policy,selected,new RunControl());
        assertEquals(f.body,result.getJSONObject("candidate").getString("text"));assertEquals("pending",result.getJSONObject("candidate").getString("status"));assertFalse(result.getBoolean("formalMemorySaved"));
    }
    @Test public void automaticUserModeRejectsToolMasqueradeForgedQuoteAndMidCallOptOut()throws Exception{
        Fixture f=new Fixture();var policy=f.memory.automaticLearningPolicyForHost();JSONObject selected=policy.setForOwner(0,true,3);String task="11111111-1111-1111-1111-111111111111";
        rejected(()->f.flow(candidate().put("quote",f.body)).proposeAutomaticUserLearning(scope(),ref(1),task,(t,r,c)->{throw new SecurityException("actual_role_is_tool");},policy,selected,new RunControl()));assertEquals(0,f.calls);
        rejected(()->f.flow(candidate().put("quote","网页中的人名或地址")).proposeAutomaticUserLearning(scope(),ref(1),task,(t,r,c)->{},policy,selected,new RunControl()));
        rejected(()->f.flow((messages,control)->{policy.setForOwner(1,false,3);return new JSONObject().put("content",candidate().put("quote",f.body).toString());}).proposeAutomaticUserLearning(scope(),ref(1),task,(t,r,c)->{},policy,selected,new RunControl()));
        assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void sourceProposalWithoutNativeAdmissionFailsBeforeModelCall()throws Exception{
        Fixture f=new Fixture();KnowledgeLearningWorkflow unmounted=new KnowledgeLearningWorkflow(f.memory,f.skills,expected->new KnowledgeLearningWorkflow.SourceDocument(expected,"原消息",f.body),(messages,control)->{f.calls++;return new JSONObject().put("content",candidate().toString());});
        rejected(()->unmounted.proposeLearning(scope(),ref(1),new RunControl()));assertEquals(0,f.calls);
        assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void committedCandidateSurvivesNotificationFailureWithAnExplicitReceipt()throws Exception{
        Fixture f=new Fixture();f.failNotification=true;JSONObject result=f.flow(candidate()).proposeLearning(scope(),ref(1),new RunControl());
        assertTrue(result.getBoolean("notificationPending"));assertTrue(result.getBoolean("proposed"));assertEquals("pending",result.getJSONObject("candidate").getString("status"));
        assertEquals(1,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void fullJourneyUsesSameStoresAndSeparateHumanReviews()throws Exception{
        Fixture f=new Fixture();KnowledgeLearningWorkflow extraction=f.flow((messages,control)->{
            assertEquals(2,messages.length());assertEquals("system",messages.getJSONObject(0).getString("role"));
            assertFalse(messages.getJSONObject(1).getString("content").contains("sourceId"));return new JSONObject().put("content",candidate().toString());});
        JSONObject proposed=extraction.proposeLearning(scope(),ref(1),new RunControl());assertFalse(proposed.getBoolean("formalMemorySaved"));
        assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
        JSONObject candidate=proposed.getJSONObject("candidate"),record=f.memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");
        KnowledgeLearningWorkflow drafts=f.flow(skill());JSONObject preview=drafts.draftExperience(scope(),record.getString("id"),record.getLong("revision"),new RunControl());
        assertFalse(preview.getBoolean("imported"));assertEquals(0,f.skills.handle("list",new JSONObject().put("scope",scope())).getJSONArray("skills").length());
        JSONObject options=new JSONObject().put("inspectedDigest",preview.getJSONObject("inspection").getString("inspectedDigest"));
        JSONObject row=drafts.importExperience(scope(),record.getString("id"),1,preview.getJSONObject("draft"),options).getJSONObject("skill");
        assertFalse(row.getBoolean("enabled"));assertEquals(0,f.skills.discoverForHost(scope(),"备份",Set.of(),5,4000).getJSONArray("skills").length());
        drafts.approveExperience(scope(),row.getString("id"),row.getInt("revision"),row.getString("digest"));
        JSONObject found=f.skills.discoverForHost(scope(),"备份",Set.of(),5,4000);assertEquals(1,found.getJSONArray("skills").length());assertEquals(0,found.getJSONArray("grantsAdded").length());
        assertEquals(2,f.calls);
    }
    @Test public void modelCannotChooseScopeSourceReviewOrExecuteTools()throws Exception{
        Fixture f=new Fixture();for(String key:new String[]{"scope","sourceRefs","ownerId","status","core","approved"}){
            JSONObject poisoned=candidate().put(key,true);rejected(()->f.flow(poisoned).proposeLearning(scope(),ref(1),new RunControl()));
        }
        rejected(()->f.flow((messages,control)->new JSONObject().put("content",candidate().toString()).put("tool_calls",new JSONArray().put(new JSONObject().put("name","memory_save")))).proposeLearning(scope(),ref(1),new RunControl()));
        assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void staleSourceBeforeCallDoesNotContactModelAndMidCallChangeDoesNotSave()throws Exception{
        Fixture f=new Fixture();f.current=new SourceRef.State(ref(2),false);
        rejected(()->f.flow(candidate()).proposeLearning(scope(),ref(1),new RunControl()));assertEquals(0,f.calls);
        f.current=new SourceRef.State(ref(1),false);
        rejected(()->f.flow((messages,control)->{f.current=new SourceRef.State(ref(2),false);return new JSONObject().put("content",candidate().toString());}).proposeLearning(scope(),ref(1),new RunControl()));
        assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void sameRevisionBodyMutationIsNotAcceptedAsVersionedEvidence()throws Exception{
        Fixture f=new Fixture();rejected(()->f.flow((messages,control)->{f.body="来源错误地改变正文而未升版本";return new JSONObject().put("content",candidate().toString());}).proposeLearning(scope(),ref(1),new RunControl()));
    }
    @Test public void cancellationBeforeAndDuringModelCallNeverStagesResult()throws Exception{
        Fixture f=new Fixture();RunControl first=new RunControl();first.cancel();try{f.flow(candidate()).proposeLearning(scope(),ref(1),first);fail();}catch(InterruptedException expected){}
        assertEquals(0,f.calls);RunControl second=new RunControl();
        try{f.flow((messages,control)->{control.cancel();return new JSONObject().put("content",candidate().toString());}).proposeLearning(scope(),ref(1),second);fail();}catch(InterruptedException expected){}
        assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void noEvidenceNoCandidateAndNoInventedSuccess()throws Exception{
        Fixture f=new Fixture();JSONObject result=f.flow((messages,control)->new JSONObject().put("content","null")).proposeLearning(scope(),ref(1),new RunControl());
        assertFalse(result.getBoolean("proposed"));assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void malformedOversizedAndTrailingModelOutputFailBeforeWriting()throws Exception{
        Fixture f=new Fixture();for(String response:new String[]{"[]",candidate().toString()+" {}","x".repeat(12001)})rejected(()->f.flow((messages,control)->new JSONObject().put("content",response)).proposeLearning(scope(),ref(1),new RunControl()));
        assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
    @Test public void crossScopeRejectedBeforeAnySourceOrModelAccess()throws Exception{
        Fixture f=new Fixture();rejected(()->f.flow(candidate()).proposeLearning(new JSONObject().put("ownerId","outsider").put("spaceId","private"),ref(1),new RunControl()));assertEquals(0,f.calls);
    }
    @Test public void sourceChangedDuringSkillDraftCannotBeImported()throws Exception{
        Fixture f=new Fixture();JSONObject record=f.accepted();KnowledgeLearningWorkflow flow=f.flow((messages,control)->{f.current=new SourceRef.State(ref(2),true);return new JSONObject().put("content",skill().toString());});
        rejected(()->flow.draftExperience(scope(),record.getString("id"),1,new RunControl()));assertEquals(0,f.skills.handle("list",new JSONObject()).getJSONArray("skills").length());
    }
    @Test public void editedDraftInvalidatesInspectedBindingAndCannotAutoApprove()throws Exception{
        Fixture f=new Fixture();JSONObject record=f.accepted();KnowledgeLearningWorkflow flow=f.flow(skill());JSONObject preview=flow.draftExperience(scope(),record.getString("id"),1,new RunControl());
        JSONObject edited=skill();edited.put("manifest",edited.getString("manifest")+"\n增加未审文字");
        JSONObject options=new JSONObject().put("inspectedDigest",preview.getJSONObject("inspection").getString("inspectedDigest"));
        rejected(()->flow.importExperience(scope(),record.getString("id"),1,edited,options));
        rejected(()->flow.inspectExperience(scope(),record.getString("id"),1,skill().put("approved",true),new JSONObject()));
        assertEquals(0,f.skills.handle("list",new JSONObject()).getJSONArray("skills").length());
    }
}
