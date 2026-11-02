package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.MemoryHostFence;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original workflow -> pending Book -> native review -> rebuilt cognition path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class KnowledgeLearningAssertionWorkflowTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static SourceRef ref(){return new SourceRef("message","typed-original-message",1,"owner","private");}
    private static final class Fixture {
        final Application app=RuntimeEnvironment.getApplication();
        final String body="我现在住B城。我的同事小王喜欢咖啡。";
        SourceRef.State state=new SourceRef.State(ref(),false);
        final FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        final MemoryModule memory=new MemoryModule(context,(scope,kind,id)->state);
        final SkillsModule skills=new SkillsModule(context,memory::resolveExperience);
        int calls;
        Fixture()throws Exception{}
        JSONObject answer(String kind,String subject)throws Exception{return new JSONObject().put("layer","fact").put("text","B城").put("reason","本次原话的待审推断").put("applicability","").put("confidence",0.5)
                .put("assertion",new JSONObject().put("kind",kind).put("subjectId",subject).put("subjectType",subject.equals("owner")?"owner":"person").put("subjectLabel",subject.equals("owner")?"主人":"小王").put("predicate","居住城市").put("object","B城").put("quote","我现在住B城。").put("offset",0).put("eventTime",100));}
        KnowledgeLearningWorkflow flow(JSONObject answer,Runnable afterModel){return new KnowledgeLearningWorkflow(memory,skills,expected->state!=null&&!state.deleted&&ref().equals(expected)?new KnowledgeLearningWorkflow.SourceDocument(expected,"真实选中原消息",body):null,(messages,control)->{
            calls++;assertFalse(messages.getJSONObject(1).getString("content").contains("sourceId"));afterModel.run();return new JSONObject().put("content",answer.toString());
        },new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}});}
        JSONObject view()throws Exception{return memory.handle("cognitionProjection",new JSONObject().put("scope",scope()).put("subjectId","owner"));}
        JSONObject accept(JSONObject proposed)throws Exception{JSONObject candidate=proposed.getJSONObject("candidate");return memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"));}
    }
    @Test public void explicitSourceLearningBindsTypedEvidenceAndRequiresSeparateNativeReview()throws Exception {
        Fixture f=new Fixture();JSONObject proposed=f.flow(f.answer("inferred","owner"),()->{}).proposeLearning(scope(),ref(),new RunControl());
        assertEquals(1,f.calls);assertFalse(proposed.getBoolean("formalMemorySaved"));assertEquals(0,f.view().getJSONArray("current").length());
        JSONObject candidate=proposed.getJSONObject("candidate"),span=candidate.getJSONObject("assertion").getJSONArray("evidenceSpans").getJSONObject(0);assertEquals(ref(),SourceRef.fromJson(span.getJSONObject("sourceRef")));assertEquals("pending",candidate.getString("status"));
        f.accept(proposed);assertEquals("inferred",f.view().getJSONArray("current").getJSONObject(0).getJSONObject("assertion").getString("kind"));
    }
    @Test public void recordedSourceDraftRetainsTypedEvidenceWithoutASecondModelCall()throws Exception {
        Fixture f=new Fixture();KnowledgeLearningWorkflow flow=f.flow(f.answer("inferred","owner"),()->{});String fingerprint=KnowledgeLearningWorkflow.sourceFingerprint(new KnowledgeLearningWorkflow.SourceDocument(ref(),"真实选中原消息",f.body));
        JSONObject recorded=flow.extractRecordedLearning(scope(),ref(),fingerprint,new RunControl());assertEquals(1,f.calls);assertEquals(0,f.view().getJSONArray("current").length());
        JSONObject proposed=flow.publishRecordedLearning(scope(),new JSONObject(recorded.toString()),new RunControl());f.accept(proposed);assertEquals(1,f.calls);assertEquals(1,f.view().getJSONArray("current").length());
    }
    @Test public void thirdPersonOrImaginedCandidatesNeverBecomeCurrentOwnerFacts()throws Exception {
        Fixture f=new Fixture();JSONObject third=f.flow(f.answer("told","person:unselected-xiaowang"),()->{}).proposeLearning(scope(),ref(),new RunControl());assertFalse(third.getJSONObject("candidate").has("assertion"));f.accept(third);assertEquals(0,f.view().getJSONArray("current").length());
        JSONObject imaginary=f.flow(f.answer("imagined","owner"),()->{}).proposeLearning(scope(),ref(),new RunControl());f.accept(imaginary);assertEquals(0,f.view().getJSONArray("current").length());assertEquals(1,f.view().getJSONArray("history").length());
    }
    @Test public void sourceWithdrawnAfterModelCannotPublishTypedCandidate()throws Exception {
        Fixture f=new Fixture();KnowledgeLearningWorkflow flow=f.flow(f.answer("inferred","owner"),()->f.state=new SourceRef.State(ref(),true));assertThrows(IllegalStateException.class,()->flow.proposeLearning(scope(),ref(),new RunControl()));
        assertEquals(1,f.calls);assertEquals(0,f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
    }
}
