package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.automation.AutomationModule;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class CommitmentEvidenceIntegrationTest {
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private AutomationModule module(TaskStore store)throws Exception{android.content.Context app=RuntimeEnvironment.getApplication();return new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),store::resolveCommitmentEvidence);}
    private JSONObject create(AutomationModule automation,TaskStore tasks,String task)throws Exception{
        JSONObject declaration=new JSONObject().put("kind","explicit_user").put("title","确认订单已经送达").put("dueAt",System.currentTimeMillis()+60000).put("taskId",task);
        declaration.put("sourceRef",tasks.recordCommitmentDelegation(declaration));
        return automation.handle("createCommitment",new JSONObject().put("scope",scope()).put("commitment",declaration)).getJSONObject("commitment");
    }
    @Test public void persistentOwnerEvidenceCompletesTheExactRevisionAndRejectsFabricatedReferences()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AutomationModule automation=module(tasks);JSONObject record=create(automation,tasks,"");
        assertTrue(automation.snapshot().getBoolean("completionEvidenceAvailable"));
        assertThrows(IllegalStateException.class,()->automation.handle("completeCommitment",new JSONObject().put("scope",scope()).put("id",record.getString("id")).put("revision",1).put("evidenceRef",new JSONObject().put("kind","owner_confirmation").put("id","model-claims-verified"))));
        JSONObject reference=tasks.recordOwnerCommitmentConfirmation(record);TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());
        JSONObject completed=module(reopened).handle("completeCommitment",new JSONObject().put("scope",scope()).put("id",record.getString("id")).put("revision",1).put("evidenceRef",reference)).getJSONObject("commitment");
        assertEquals("completed",completed.getString("status"));assertEquals(2,completed.getLong("revision"));
    }
    @Test public void taskCompletionRequiresPersistedOwnerVerificationAndCallsTheNativeHook()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AutomationModule automation=module(tasks);String task=tasks.create("检查送达结果").getString("id");JSONObject record=create(automation,tasks,task);tasks.bindCommitment(record);
        JSONObject binding=tasks.get(task,false).getJSONArray("commitmentBindings").getJSONObject(0);
        tasks.status(task,"completed","模型说完成");assertThrows(SecurityException.class,()->tasks.recordTaskCommitmentCompletion(task,binding));
        tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));JSONObject reference=tasks.recordTaskCommitmentCompletion(task,binding);
        JSONObject result=automation.onCommitmentTaskCompleted(scope(),record.getString("id"),1,reference.getString("id"),task,reference);assertTrue(result.getBoolean("applied"));
        assertFalse(automation.onCommitmentTaskCompleted(scope(),record.getString("id"),1,reference.getString("id"),task,reference).getBoolean("applied"));
    }
    @Test public void editingACommitmentDuringItsTaskDoesNotPromoteOldRevisionEvidence()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());AutomationModule automation=module(tasks);String task=tasks.create("检查订单").getString("id");JSONObject record=create(automation,tasks,task);tasks.bindCommitment(record);
        automation.handle("updateCommitment",new JSONObject().put("scope",scope()).put("id",record.getString("id")).put("revision",1).put("patch",new JSONObject().put("title","需要重新确认新的订单")));
        tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));tasks.status(task,"completed","主人确认旧目标完成");
        JSONObject binding=tasks.get(task,false).getJSONArray("commitmentBindings").getJSONObject(0),reference=tasks.recordTaskCommitmentCompletion(task,binding);
        assertThrows(IllegalStateException.class,()->automation.onCommitmentTaskCompleted(scope(),record.getString("id"),1,reference.getString("id"),task,reference));
        assertEquals("pending",automation.handle("getCommitment",new JSONObject().put("scope",scope()).put("id",record.getString("id"))).getJSONObject("commitment").getString("status"));
    }
}
