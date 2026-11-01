package dev.pocketengine.features.memory;

import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Calls real MemoryModule/MemoryBook and the actual single-call workflow, with no Android service. */
public final class MemoryConsolidationWorkflowTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static final class Fence implements MemoryHostFence {
        boolean live=true;int admissions,revokeOnAdmission=-1;
        @Override public synchronized void check(){if(!live)throw new SecurityException("真实host代次已撤销");}
        @Override public synchronized JSONObject admit(Admission action)throws Exception{if(++admissions==revokeOnAdmission)live=false;check();return action.run();}
    }
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private MemoryModule module()throws Exception {
        File root=temp.getRoot();Context android=new ContextWrapper(null){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return root;}};
        return new MemoryModule(new FeatureContext(android,null,null,event->{}));
    }
    private JSONObject save(MemoryModule memory,String text)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text",text))).getJSONObject("record");}
    private JSONArray refs(JSONObject... rows)throws Exception {JSONArray result=new JSONArray();for(JSONObject row:rows)result.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));return result;}
    private String draft()throws Exception{return new JSONObject().put("text","待审的综合知识").put("layer","fact").put("reason","二者共同支持但未经独立核实").toString();}
    private JSONObject reply(String text)throws Exception{return new JSONObject().put("content",text);}
    private int count(MemoryModule memory)throws Exception{return memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length();}
    private interface Checked{void run()throws Exception;}
    private void rejected(Checked action)throws Exception{try{action.run();fail("unauthorized model result admitted");}catch(IllegalArgumentException|IllegalStateException|SecurityException|org.json.JSONException expected){}}

    @Test public void oneModelCallUsesExactReferenceInputAndOnlyStagesPending()throws Exception {
        MemoryModule memory=module();JSONArray selected=refs(save(memory,"Android 项目"),save(memory,"Java 项目"));AtomicInteger calls=new AtomicInteger();
        MemoryConsolidationWorkflow workflow=new MemoryConsolidationWorkflow(memory,(messages,control)->{
            calls.incrementAndGet();assertEquals(2,messages.length());assertEquals("system",messages.getJSONObject(0).getString("role"));
            JSONObject input=new JSONObject(messages.getJSONObject(1).getString("content"));assertEquals(2,input.getJSONArray("recordRefs").length());assertFalse(input.getBoolean("executionAuthorized"));return reply(draft());
        });
        Fence fence=new Fence();JSONObject result=workflow.propose(scope(),selected,new RunControl(),fence);assertEquals(1,calls.get());assertEquals(2,fence.admissions);
        assertTrue(result.getBoolean("proposed"));assertTrue(result.getBoolean("reviewRequired"));assertFalse(result.getBoolean("formalMemorySaved"));assertEquals("pending",result.getJSONObject("candidate").getString("status"));
        assertEquals(1,count(memory));assertEquals(2,memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
    }

    @Test public void parentChangeDuringModelCallRejectsLateResultWithoutCandidate()throws Exception {
        MemoryModule memory=module();JSONObject a=save(memory,"旧事实"),b=save(memory,"另一事实");
        MemoryConsolidationWorkflow workflow=new MemoryConsolidationWorkflow(memory,(messages,control)->{
            memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject(a.toString()).put("text","新事实")));return reply(draft());
        });
        rejected(()->workflow.propose(scope(),refs(a,b),new RunControl(),new Fence()));assertEquals(0,count(memory));
    }

    @Test public void cancellationAfterModelAndAtomicFinalAdmissionRevocationBothPreventSave()throws Exception {
        MemoryModule memory=module();JSONArray selected=refs(save(memory,"一"),save(memory,"二"));RunControl stopped=new RunControl();
        MemoryConsolidationWorkflow cancelled=new MemoryConsolidationWorkflow(memory,(messages,control)->{control.cancel();return reply(draft());});
        try{cancelled.propose(scope(),selected,stopped,new Fence());fail();}catch(InterruptedException expected){}assertEquals(0,count(memory));
        Fence fence=new Fence();fence.revokeOnAdmission=2;MemoryConsolidationWorkflow revoked=new MemoryConsolidationWorkflow(memory,(messages,control)->reply(draft()));
        rejected(()->revoked.propose(scope(),selected,new RunControl(),fence));assertEquals(0,count(memory));
    }

    @Test public void insufficientEvidenceIsExplicitNoOpAndModelFailuresAreNeverRetried()throws Exception {
        MemoryModule memory=module();JSONArray selected=refs(save(memory,"一"),save(memory,"二"));AtomicInteger calls=new AtomicInteger();
        JSONObject result=new MemoryConsolidationWorkflow(memory,(messages,control)->reply("null")).propose(scope(),selected,new RunControl(),new Fence());assertFalse(result.getBoolean("proposed"));assertEquals(0,count(memory));
        MemoryConsolidationWorkflow failed=new MemoryConsolidationWorkflow(memory,(messages,control)->{calls.incrementAndGet();throw new IOException("network interrupted");});
        try{failed.propose(scope(),selected,new RunControl(),new Fence());fail();}catch(IOException expected){}assertEquals(1,calls.get());assertEquals(0,count(memory));
    }

    @Test public void modelCannotProvideSourceIdentityCoreApprovalOrToolCalls()throws Exception {
        MemoryModule memory=module();JSONArray selected=refs(save(memory,"一"),save(memory,"二"));
        for(String field:new String[]{"scope","recordRefs","sourceRefs","core","approved","revision"}){
            String body=new JSONObject(draft()).put(field,true).toString();rejected(()->new MemoryConsolidationWorkflow(memory,(m,c)->reply(body)).propose(scope(),selected,new RunControl(),new Fence()));
        }
        for(String body:new String[]{draft()+" {}","[]","```json\n"+draft()+"\n```","x".repeat(24001)})
            rejected(()->new MemoryConsolidationWorkflow(memory,(m,c)->reply(body)).propose(scope(),selected,new RunControl(),new Fence()));
        rejected(()->new MemoryConsolidationWorkflow(memory,(m,c)->reply(draft()).put("tool_calls",new JSONArray().put(new JSONObject().put("name","save")))).propose(scope(),selected,new RunControl(),new Fence()));
        assertEquals(0,count(memory));
    }

    @Test public void callerCannotSwapScopeOrSelectionsWhileAResponseIsOutstanding()throws Exception {
        MemoryModule memory=module();JSONObject a=save(memory,"源一"),b=save(memory,"源二"),auth=scope();JSONArray selected=refs(a,b);
        JSONObject result=new MemoryConsolidationWorkflow(memory,(m,c)->{
            auth.put("spaceId","qq:group:unrelated");selected.getJSONObject(0).put("id",b.getString("id"));return reply(draft());
        }).propose(auth,selected,new RunControl(),new Fence());
        assertEquals("private",result.getJSONObject("candidate").getString("spaceId"));assertEquals(a.getString("id"),result.getJSONObject("candidate").getJSONArray("recordRefs").getJSONObject(0).getString("id"));
    }

    @Test public void newOwnerOnlyMethodsHaveNoRawJsonDispatchOrModelCommandName()throws Exception {
        MemoryModule memory=module();for(String command:new String[]{"proposeConsolidation","consolidationSnapshot","workingSnapshot"})rejected(()->memory.handle(command,new JSONObject().put("scope",scope())));
        String commands=memory.snapshot().getJSONArray("commands").toString();assertFalse(commands.contains("proposeConsolidation"));assertFalse(commands.contains("acceptCandidate"));
        assertTrue(memory.snapshot().getBoolean("controlledConsolidation"));assertFalse(memory.snapshot().getBoolean("automaticLearning"));
    }

    @Test public void repeatedRejectedHypothesisIsNotPresentedAsANewPendingReview()throws Exception {
        MemoryModule memory=module();JSONArray selected=refs(save(memory,"一"),save(memory,"二"));MemoryConsolidationWorkflow workflow=new MemoryConsolidationWorkflow(memory,(m,c)->reply(draft()));
        JSONObject first=workflow.propose(scope(),selected,new RunControl(),new Fence()),candidate=first.getJSONObject("candidate");
        memory.handle("rejectCandidate",new JSONObject().put("scope",scope()).put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("reason","证据不足"));
        JSONObject repeated=workflow.propose(scope(),selected,new RunControl(),new Fence());assertFalse(repeated.getBoolean("proposed"));assertFalse(repeated.getBoolean("reviewRequired"));assertEquals("rejected",repeated.getJSONObject("candidate").getString("status"));assertEquals(1,count(memory));
    }
}
