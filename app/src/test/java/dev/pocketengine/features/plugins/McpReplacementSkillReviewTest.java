package dev.pocketengine.features.plugins;

import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.skills.NativeSkillExecutionHost;
import dev.pocketengine.features.skills.NativeSkillExecutionHostTest;
import dev.pocketengine.runtime.extensions.ExtensionHost;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import dev.pocketengine.store.SecretVault;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Independent review probe. Reuses unmodified original canonical/MCP wire fixtures.
 * Its command never executes the user Skill resource; no external accounts or traffic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpReplacementSkillReviewTest {
    private static JSONObject install(PluginsModule plugins,McpFixture wire)throws Exception {
        JSONObject preview=plugins.handle("discoverMcp",new JSONObject().put("id","remote").put("name","same-name service").put("endpoint",wire.endpoint()).put("protocolVersion",McpData.VERSION));
        JSONObject row=plugins.handle("installMcp",new JSONObject().put("previewId",preview.getString("previewId")).put("expectedInstallationId",preview.getString("expectedInstallationId")).put("tools",new JSONArray().put("lookup")));
        row=plugins.setToolGrantReviewed("remote",row.getString("installationId"),row.getString("grantsDigest"),"lookup",true);
        return dev.pocketengine.features.plugins.PluginOwnerTestReview.enableReviewed(plugins,row,true);
    }
    @Test public void replacementWithIdenticalToolDefinitionMustInvalidateTheRetainedSkillReview()throws Exception {
        try(NativeSkillExecutionHostTest.Fixture f=new NativeSkillExecutionHostTest.Fixture();McpFixture oldWire=new McpFixture();McpFixture newWire=new McpFixture()){
            // Keep the original fixture byte-for-byte. Its unused initial task is not this probe's task.
            f.host.close();
            PluginsModule plugins=new PluginsModule(new FeatureContext(f.android,null,new SecretVault(f.android),e->{}));
            JSONObject oldInstall=install(plugins,oldWire);
            JSONObject skill=f.skills.handle("import",new JSONObject().put("source","test:retained-mcp-identity").put("manifest","---\nname: retained-mcp-identity\ndescription: same-name service dependency review\n---\nMethod content is not authorization.")
                    .put("requiredCapabilities",new JSONArray().put("plugin:remote:lookup")).put("resources",new JSONObject().put("scripts/main.py","raise RuntimeError('NEVER EXECUTE USER SKILL IN PROTOCOL FIXTURE')\n"))).getJSONObject("skill");
            f.skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",skill.getString("digest")));
            JSONObject task=f.tasks.startAttempt(f.tasks.createCapabilityTask("same-name service dependency review","").getString("id"),"capabilities",20);
            String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(id,run,epoch);
            try(NativeSkillExecutionHost nativeHost=new NativeSkillExecutionHost(f.android,f.skills,f.wire,f.tasks,(t,r,e,c)->{assertSame(control,c);assertTrue(c.matchesHostRun(t,r,e));c.check();},t->{});FeatureHost features=new FeatureHost(e->{},f.tasks,()->false);ExtensionHost extensions=ExtensionHost.disabled(f.tasks)){
                features.register(plugins);features.register(f.skills);features.setSkillExecutionHost(nativeHost);
                HostToolSupervisor local=new HostToolSupervisor(features,f.tasks,id,run,epoch,true);
                var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,"");
                CapabilityPort combined=new ModelCapabilityAdapters(binding,new TaskStoreCapabilityAuthority(f.tasks,binding,e->{}),local,null,null,null,null);
                try(CapabilityPort port=extensions.bind(combined,new ExtensionRuntime.Binding(id,run,epoch),control)){
                    port.beginRun(task.getString("goal"),control);JSONObject admitted=port.prepareTurn(task.getString("goal"),0,0,control);
                    String originalDefinitions=admitted.getJSONArray("tools").toString();String mcpName=plugins.toolRegistry().getJSONObject(0).getString("name");
                    assertTrue(local.skillCapabilityNames().contains("plugin:remote:lookup"));
                    JSONObject mcpArgs=new JSONObject();JSONObject oldCapability=new JSONObject().put("feature","plugins").put("command","invoke").put("pluginId","remote").put("tool","lookup").put("installationId",oldInstall.getString("installationId")).put("version",oldInstall.getJSONObject("manifest").getString("version"));
                    JSONObject pending=f.tasks.requireCapabilityApproval(id,run,mcpName,mcpArgs,oldCapability);f.tasks.decideCapabilityApproval(id,pending.getString("approvalId"),true);
                    JSONObject args=new JSONObject().put("id",skill.getString("id")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest")).put("scriptPath","scripts/main.py").put("arguments",new JSONArray());
                    assertNull(port.beforeTool("skills_execute",args,control));
                    String operation=f.tasks.journal(id,run,epoch,control).beginOperation("skills_execute",args);
                    CompletableFuture<JSONObject> work=CompletableFuture.supplyAsync(()->{try{return port.execute("skills_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),control);}catch(Exception failure){throw new RuntimeException(failure);}});
                    NativeSkillExecutionHost.Review review=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(review==null&&System.nanoTime()<until){review=nativeHost.pendingReview(id);if(review==null)Thread.sleep(20);}assertNotNull("the exact old native Skill review must exist",review);
                    JSONObject replacement=install(plugins,newWire);assertNotEquals(oldInstall.getString("installationId"),replacement.getString("installationId"));
                    boolean validationRejected=false;String validated="";try{validated=nativeHost.validateReview(review);}catch(Exception expected){validationRejected=true;}
                    boolean nativeSubmissionBlocked=true;JSONObject actual=new JSONObject();
                    var owner=nativeHost.openOwnerWindow();owner.setForeground(true);
                    try{nativeHost.decide(owner,review,review.plan.digest,true);actual=work.get(12,TimeUnit.SECONDS);nativeSubmissionBlocked=!"completed".equals(actual.optString("status"));}
                    catch(Exception expected){validationRejected=true;}
                    owner.close();
                    JSONObject invoke=port.execute(mcpName,mcpArgs,control);
                    JSONObject observation=new JSONObject().put("oldInstallation",oldInstall.getString("installationId")).put("newInstallation",replacement.getString("installationId"))
                            .put("sameFunctionDefinitions",originalDefinitions.equals(port.tools().toString())).put("oldReviewValidationRejected",validationRejected).put("oldReviewDigestStillCurrent",validated.equals(review.plan.digest))
                            .put("nativeSubmissionBlocked",nativeSubmissionBlocked).put("nativeReceiptStatus",actual.optString("status")).put("canonicalFixtureJobPosts",f.posts.get())
                            .put("mcpInvokeCode",invoke.optString("code")).put("oldMcpCalls",oldWire.count("tools/call")).put("replacementMcpCalls",newWire.count("tools/call"));
                    System.out.println("REVIEW_OBSERVATION="+observation);
                    // This control proves the final MCP write authority still demands a NEW exact approval.
                    assertEquals("approval_required",invoke.optString("code"));assertEquals(0,newWire.count("tools/call"));assertEquals(0,oldWire.count("tools/call"));
                    assertTrue("same-definition replacement must invalidate the old Skill review; observation="+observation,validationRejected||nativeSubmissionBlocked);
                }
            }
        }
    }
}
