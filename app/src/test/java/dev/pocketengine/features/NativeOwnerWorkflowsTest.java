package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
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
public final class NativeOwnerWorkflowsTest {
    private interface Checked {void run()throws Exception;}
    private static void rejected(Checked action)throws Exception{try{action.run();fail("must fail closed");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}}
    private JSONObject config()throws Exception{return new JSONObject().put("id","docs").put("name","文档服务").put("endpoint","https://example.com/mcp");}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();
        final FeatureHost host=new FeatureHost(event->{});
        final TaskStore tasks=new TaskStore(app);
        final FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        final MemorySourceBridge sources=new MemorySourceBridge(host,tasks);
        final MemoryModule memory=new MemoryModule(context,sources::resolve);
        final PluginsModule plugins=new PluginsModule(context);
        final NativeOwnerWorkflows flows=new NativeOwnerWorkflows(host,context,tasks,sources);
        Fixture()throws Exception{host.register(memory);host.register(new dev.pocketengine.features.skills.SkillsModule(context,memory::resolveExperience));host.register(plugins);host.setMemorySynchronizer(sources::synchronize);}
        public void close(){try{flows.cancelPendingLogin();}catch(Exception ignored){}host.close();}
    }
    @Test public void safConfigOnlyReturnsCredentialFreeWhitelist()throws Exception{
        JSONObject parsed=NativeOwnerWorkflows.parseMcpConfig(config().toString());
        assertEquals(4,parsed.length());assertEquals("2026-07-28",parsed.getString("protocolVersion"));assertFalse(parsed.has("token"));
        for(String key:new String[]{"token","headers","env","command","mcpServers"}){
            JSONObject input=config().put(key,"secret-never-copy");
            try{NativeOwnerWorkflows.parseMcpConfig(input.toString());fail();}catch(IllegalArgumentException expected){assertFalse(expected.getMessage().contains("secret-never-copy"));}
        }
    }
    @Test public void safRejectsCredentialBearingUrlsAndOversize()throws Exception{
        for(String endpoint:new String[]{"http://example.com/mcp","https://user:password@example.com/mcp","https://example.com/mcp?token=private","https://example.com/mcp#private"})rejected(()->NativeOwnerWorkflows.parseMcpConfig(config().put("endpoint",endpoint).toString()));
        rejected(()->NativeOwnerWorkflows.parseMcpConfig(" ".repeat(65537)));
    }
    @Test public void ownerScopeCannotBeChangedByLeafPayloadAndForgetUsesReviewRevision()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject record=fixture.memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","私人偏好"))).getJSONObject("record");
            JSONObject poisoned=new JSONObject().put("scope",new JSONObject().put("ownerId","outsider").put("spaceId","shared"));
            assertEquals(1,fixture.flows.memory("list",poisoned).getJSONArray("records").length());
            JSONObject request=new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")+1);
            rejected(()->fixture.flows.memory("forgetReviewed",request));assertEquals(1,fixture.flows.memory("list",new JSONObject()).getJSONArray("records").length());
            request.put("expectedRevision",record.getLong("revision"));assertEquals(1,fixture.flows.memory("forgetReviewed",request).getInt("deleted"));
            assertEquals(0,fixture.flows.memory("list",new JSONObject()).getJSONArray("records").length());
        }
    }
    @Test public void nativeCapabilitiesDoNotBecomeModelCommands()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject snapshot=fixture.flows.memorySnapshot();assertTrue(snapshot.getJSONObject("nativeCapabilities").getBoolean("acceptCandidate"));
            assertFalse(snapshot.getJSONArray("commands").toString().contains("acceptCandidate"));
            assertTrue(fixture.flows.mcpSnapshot().getJSONObject("nativeCapabilities").getBoolean("setEnabledReviewed"));
            rejected(()->fixture.flows.memory("sourceUpdated",new JSONObject()));
            rejected(()->fixture.flows.mcp("grant",new JSONObject()));
            rejected(()->fixture.flows.mcp("enable",new JSONObject()));
        }
    }
    @Test public void nativeEnableRequiresTheDisplayedStateAndGrantsEvenOnTheSameInstallation()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject manifest=new JSONObject().put("id","owner-toggle").put("name","Owner toggle").put("version","1")
                    .put("tools",new JSONArray().put(new JSONObject().put("name","lookup").put("kind","echo")));
            JSONObject installed=fixture.plugins.handle("install",new JSONObject().put("manifest",manifest));
            JSONObject reviewed=dev.pocketengine.features.plugins.PluginOwnerTestReview.payload(installed,true);
            JSONObject missingRevision=new JSONObject(reviewed.toString());missingRevision.remove("expectedStateRevision");
            assertThrows(org.json.JSONException.class,()->fixture.flows.mcp("setEnabledReviewed",missingRevision));
            JSONObject missingGrants=new JSONObject(reviewed.toString());missingGrants.remove("expectedGrantsDigest");
            assertThrows(org.json.JSONException.class,()->fixture.flows.mcp("setEnabledReviewed",missingGrants));
            JSONObject granted=fixture.flows.mcp("setToolGrantReviewed",new JSONObject().put("id",installed.getString("id"))
                    .put("expectedInstallationId",installed.getString("installationId")).put("expectedGrantsDigest",installed.getString("grantsDigest")).put("tool","lookup").put("granted",true));
            assertThrows(IllegalStateException.class,()->fixture.flows.mcp("setEnabledReviewed",reviewed));
            assertFalse(fixture.flows.mcpSnapshot().getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"));assertEquals(0,fixture.plugins.toolRegistry().length());
            JSONObject enabled=fixture.flows.mcp("setEnabledReviewed",dev.pocketengine.features.plugins.PluginOwnerTestReview.payload(granted,true));
            assertTrue(enabled.getBoolean("enabled"));assertEquals(1,fixture.plugins.toolRegistry().length());
        }
    }
    @Test public void sourceViewerRequiresExactOwnerAndCurrentTaskEvidence()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject task=fixture.tasks.create("核对文档","background");String id=task.getString("id");
            fixture.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",true));fixture.tasks.status(id,"completed","已经核对");
            SourceRef ref=fixture.tasks.resolveMemorySource(dev.pocketengine.features.memory.MemoryBook.Scope.from(scope()),"task_evidence","task:"+id+":verified_result").ref;
            JSONObject shown=fixture.flows.source(ref.toJson());assertTrue(shown.getString("text").contains("核对文档"));assertEquals(ref.revision,shown.getJSONObject("sourceRef").getLong("revision"));
            rejected(()->fixture.flows.source(ref.toJson().put("ownerId","outsider")));
            fixture.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",false));rejected(()->fixture.flows.source(ref.toJson()));
        }
    }
    @Test public void emptyAccountSnapshotIsHonestAndDoesNotChangeMainModelSettings()throws Exception{
        try(Fixture fixture=new Fixture();GatewayCall call=new GatewayCall(1000)){
            String before=fixture.context.settings.get().publicJson().toString();
            JSONObject first=fixture.flows.account("snapshot",new JSONObject(),call),second=fixture.flows.accountSnapshot();
            assertFalse(first.getBoolean("mainInferenceReady"));assertEquals("account_catalog_only",first.getString("modelSelectionPurpose"));assertEquals(0,first.getJSONArray("accounts").length());assertTrue(second.getLong("nativeSnapshotRevision")>first.getLong("nativeSnapshotRevision"));
            assertFalse(first.toString().contains("authorizationUrl"));assertEquals(before,fixture.context.settings.get().publicJson().toString());
        }
    }
    @Test public void inferenceCapabilityDoesNotClaimASelectionOrDecryptAnUnrelatedApiKey()throws Exception{
        try(Fixture fixture=new Fixture()){
            fixture.app.getSharedPreferences("private_settings",0).edit().putString("encryptedKey","unreadable-fixture").commit();
            fixture.flows.installAccountInference();
            JSONObject snapshot=fixture.flows.accountSnapshot();
            assertTrue(snapshot.getBoolean("mainInferenceAvailable"));assertFalse(snapshot.getBoolean("mainInferenceReady"));
            assertEquals("api_key",snapshot.getString("mainModelRoute"));assertFalse(snapshot.getBoolean("mainInferenceSupportsImages"));
            assertFalse(snapshot.has("selectedModelRef"));
        }finally{dev.pocketengine.runtime.ModelGateway.installAccountInferencePort(null);}
    }

    @Test public void learningPreviewUsesConfirmationTimeBodyNotMutableCurrentSummary()throws Exception{
        try(Fixture f=new Fixture()){
            String id=f.tasks.create("任务来源").getString("id");f.tasks.status(id,"needs_confirmation","当时核对的真实结果");
            f.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",true));f.tasks.status(id,"completed","后来的当前摘要，不是历史来源");
            JSONObject result=f.flows.learning("prepareLearningSource",new JSONObject().put("kind","task_evidence").put("sourceId","task:"+id+":verified_result"),new dev.pocketengine.core.RunControl());
            assertFalse(result.getBoolean("modelCalled"));assertTrue(result.getString("text").contains("当时核对的真实结果"));assertFalse(result.getString("text").contains("后来的当前摘要"));
            f.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",false));
            rejected(()->f.flows.learning("prepareLearningSource",new JSONObject().put("kind","task_evidence").put("sourceId","task:"+id+":verified_result"),new dev.pocketengine.core.RunControl()));
        }
    }
    @Test public void nativeLearningSeparatesImportedDisabledSkillFromReviewedApproval()throws Exception{
        try(Fixture f=new Fixture()){
            String task=f.tasks.create("测试经验来源").getString("id");f.tasks.status(task,"completed","已经人工核对");f.tasks.event(task,new JSONObject().put("type","user_verification").put("completed",true));
            SourceRef source=f.tasks.resolveMemorySource(dev.pocketengine.features.memory.MemoryBook.Scope.from(scope()),"task_evidence","task:"+task+":verified_result").ref;
            rejected(()->f.flows.learning("proposeLearning",new JSONObject().put("sourceRef",source.toJson()),new dev.pocketengine.core.RunControl()));
            JSONObject candidate=new JSONObject().put("layer","procedural").put("text","备份前后核对目录和文件数量").put("reason","来自本次已确认任务").put("applicability","只用于相同目录结构，尚未独立验证").put("confidence",0.5);
            KnowledgeLearningWorkflow fixture=new KnowledgeLearningWorkflow(f.memory,(dev.pocketengine.features.skills.SkillsModule)f.host.module("skills"),f.tasks::readMemorySource,(messages,control)->new JSONObject().put("content",candidate.toString()),new dev.pocketengine.features.memory.MemoryHostFence(){
                public void check(){}public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){return action.run();}}
            });
            JSONObject c=fixture.proposeLearning(scope(),source,new dev.pocketengine.core.RunControl()).getJSONObject("candidate");
            JSONObject record=f.flows.memory("acceptCandidate",new JSONObject().put("id",c.getString("id")).put("revision",c.getLong("revision"))).getJSONObject("record");
            assertEquals(1,f.flows.learningSnapshot().getJSONArray("procedures").length());
            JSONObject draft=new JSONObject().put("manifest","---\nname: checked-backup\ndescription: 备份前后核对\n---\n先检查目录，备份后核对数量。不确定时停止并请主人核对。\n");
            JSONObject input=new JSONObject().put("recordId",record.getString("id")).put("expectedRevision",record.getLong("revision")).put("draft",draft).put("options",new JSONObject());
            JSONObject inspection=f.flows.learning("inspectExperience",input,new dev.pocketengine.core.RunControl());input.getJSONObject("options").put("inspectedDigest",inspection.getJSONObject("inspection").getString("inspectedDigest"));
            JSONObject skill=f.flows.learning("importExperience",input,new dev.pocketengine.core.RunControl()).getJSONObject("skill");assertFalse(skill.getBoolean("enabled"));
            JSONObject read=new JSONObject().put("id",skill.getString("id")).put("revision",skill.getLong("revision")).put("digest",skill.getString("digest")).put("path","SKILL.md");
            assertFalse(f.flows.learning("readSkill",read,new dev.pocketengine.core.RunControl()).getString("content").isEmpty());
            rejected(()->f.flows.learning("readSkill",new JSONObject(read.toString()).put("revision",skill.getLong("revision")+1),new dev.pocketengine.core.RunControl()));
            f.flows.learning("approveExperience",read,new dev.pocketengine.core.RunControl());
            f.flows.memory("forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")));
            assertEquals(0,((dev.pocketengine.features.skills.SkillsModule)f.host.module("skills")).handle("list",new JSONObject().put("scope",scope())).getJSONArray("skills").length());
            assertThrows(IllegalStateException.class,()->f.tasks.readMemorySource(source));
        }
    }

}
