package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual native permission CAS + real MCP wire + original TaskStore approval/resume/journal. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskMcpPreparationTest {
    private static JSONObject install(PluginsModule plugins,McpFixture wire)throws Exception {
        JSONObject preview=plugins.handle("discoverMcp",new JSONObject().put("id","remote").put("name","任务所需查询工具").put("endpoint",wire.endpoint()).put("protocolVersion",McpData.VERSION));
        return plugins.handle("installMcp",new JSONObject().put("previewId",preview.getString("previewId")).put("expectedInstallationId",preview.getString("expectedInstallationId")).put("tools",new JSONArray().put("lookup")));
    }
    /** Actual native owner adapter consumed by the focused Compose journey, with real loopback discovery. */
    public static final class UiDomain implements AutoCloseable {
        private final McpFixture wire=new McpFixture();
        private final FeatureHost features;
        public final PluginsModule plugins;
        public final dev.pocketengine.features.NativeOwnerWorkflows owner;
        public UiDomain()throws Exception {
            Context android=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,null,new SecretVault(android),e->{});
            plugins=new PluginsModule(context);features=new FeatureHost(e->{},tasks,()->false);features.register(plugins);
            owner=new dev.pocketengine.features.NativeOwnerWorkflows(features,context,tasks,new dev.pocketengine.features.MemorySourceBridge(features,tasks));
            install(plugins,wire);JSONObject other=plugins.handle("discoverMcp",new JSONObject().put("id","unrelated").put("name","不相关服务").put("endpoint",wire.endpoint()).put("protocolVersion",McpData.VERSION));
            plugins.handle("installMcp",new JSONObject().put("previewId",other.getString("previewId")).put("expectedInstallationId",other.getString("expectedInstallationId")).put("tools",new JSONArray().put("lookup")));
        }
        public JSONObject snapshot()throws Exception{return owner.mcpSnapshot();}
        public int callCount(){return wire.count("tools/call");}
        @Override public void close()throws Exception {features.close();wire.close();}
    }
    private static JSONArray toolNames(HostToolSupervisor port)throws Exception {JSONArray out=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)out.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));return out;}
    @Test public void onlyMissingToolIsGrantedAndActualMcpRunsOnTheOriginalTaskAfterExactApproval()throws Exception {
        Context android=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(android);FeatureContext context=new FeatureContext(android,null,new SecretVault(android),e->{});
        PluginsModule plugins=new PluginsModule(context);SkillsModule skills=new SkillsModule(context);FeatureHost host=new FeatureHost(e->{},tasks,()->false);host.register(plugins);host.register(skills);
        try(McpFixture wire=new McpFixture()){
            JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
            JSONObject skill=skills.handle("import",new JSONObject().put("source","test:task-mcp").put("manifest","---\nname: lookup-procedure\ndescription: 核对查询任务工具\n---\n只读参考，调用仍须授权。")
                    .put("requiredCapabilities",new JSONArray().put("plugin:remote:lookup"))).getJSONObject("skill");
            skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",skill.getString("digest")));
            assertEquals("missingCapabilities",skills.diagnoseDiscovery(scope,"查询任务",HostToolSupervisor.configuredSkillCapabilities(host,tasks)).getJSONArray("skills").getJSONObject(0).getString("status"));
            JSONObject installed=install(plugins,wire);assertFalse(installed.getBoolean("enabled"));assertEquals(0,plugins.toolRegistry().length());assertEquals(0,wire.count("tools/call"));
            JSONObject granted=plugins.setToolGrantReviewed("remote",installed.getString("installationId"),installed.getString("grantsDigest"),"lookup",true);
            assertThrows(IllegalStateException.class,()->plugins.setToolGrantReviewed("remote",installed.getString("installationId"),installed.getString("grantsDigest"),"lookup",false));
            plugins.setEnabledReviewed("remote",installed.getString("installationId"),true);assertEquals(1,plugins.toolRegistry().length());
            assertEquals("active",skills.diagnoseDiscovery(scope,"查询任务",HostToolSupervisor.configuredSkillCapabilities(host,tasks)).getJSONArray("skills").getJSONObject(0).getString("status"));
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("查询并核对原任务的真实MCP结果","").getString("id"),"capabilities",20);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
            HostToolSupervisor first=new HostToolSupervisor(host,tasks,id,run,epoch,true);tasks.authorizeTools(id,run,toolNames(first));RunControl control=new RunControl();control.bindHostRun(id,run,epoch);
            String name=plugins.toolRegistry().getJSONObject(0).getString("name");String original=tasks.journal(id,run,epoch,control).beginOperation(name,new JSONObject());
            JSONObject approval=first.execute(name,new JSONObject().put("_hostOperationId",original),control);assertEquals("approval_required",approval.getString("code"));assertEquals(0,wire.count("tools/call"));tasks.journal(id,run,epoch,control).finishOperation(original,approval);
            tasks.decideCapabilityApproval(id,approval.getString("approvalId"),true);tasks.endAttempt(id,run,"awaiting_user","等待本机批准",new JSONObject());
            JSONObject resumed=tasks.startAttempt(id,"capabilities",20);String next=resumed.getString("runId");int nextEpoch=resumed.getInt("coordinatorEpoch");RunControl current=new RunControl();current.bindHostRun(id,next,nextEpoch);
            HostToolSupervisor second=new HostToolSupervisor(host,tasks,id,next,nextEpoch,true);tasks.authorizeTools(id,next,toolNames(second));
            String operation=tasks.journal(id,next,nextEpoch,current).beginOperation(name,new JSONObject());JSONObject receipt=second.execute(name,new JSONObject().put("_hostOperationId",operation),current);
            assertEquals("response_received",receipt.getString("status"));assertTrue(receipt.getJSONObject("toolResult").getBoolean("untrusted"));tasks.journal(id,next,nextEpoch,current).finishOperation(operation,receipt);
            assertEquals(1,wire.count("tools/call"));assertEquals(1,tasks.list().length());assertEquals(2,tasks.get(id,false).getJSONArray("operations").length());assertNotEquals("completed",tasks.get(id,false).getString("status"));
            JSONObject revoked=plugins.setToolGrantReviewed("remote",installed.getString("installationId"),granted.getString("grantsDigest"),"lookup",false);
            assertEquals(0,plugins.toolRegistry().length());assertThrows(dev.pocketengine.core.CapabilityPort.Unavailable.class,()->second.validate(name,new JSONObject()));assertEquals(1,wire.count("tools/call"));
            JSONObject replacement=install(plugins,wire);assertNotEquals(installed.getString("installationId"),replacement.getString("installationId"));assertEquals(0,replacement.getJSONArray("grants").length());assertFalse(replacement.getBoolean("enabled"));
            assertThrows(IllegalStateException.class,()->plugins.setToolGrantReviewed("remote",installed.getString("installationId"),revoked.getString("grantsDigest"),"lookup",true));assertEquals(1,wire.count("tools/call"));
        }finally{host.close();}
    }
}
