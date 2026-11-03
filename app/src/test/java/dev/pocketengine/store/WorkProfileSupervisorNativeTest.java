package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.controlplane.*;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.plugins.catalog.ExecutablePluginCatalog;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import dev.pocketengine.testutil.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native modules, Runner/journal and actual loopback HTTP. No model service, phone or JS engine. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkProfileSupervisorNativeTest {
    private TaskStore tasks; private SettingsStore settings; private FeatureHost features; private SkillsModule skills; private PluginsModule plugins; private NativePluginHost nativePlugins;
    private JSONObject chosenSkill, otherSkill, chosenPlugin, otherPlugin, definition, run; private RunControl original; private NativeWorkProfileHost.Selection selection;
    private String selectedTool, hiddenTool;
    private SQLiteDatabase db() throws Exception { var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(tasks); }
    @Before public void open() throws Exception {
        var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);settings=new SettingsStore(app.getSharedPreferences("supervisor-profile-"+java.util.UUID.randomUUID(),0));
        var context=new FeatureContext(app,settings,new SecretVault(app),e->{});skills=new SkillsModule(context);plugins=new PluginsModule(context);features=new FeatureHost(e->{},tasks,()->false);features.register(skills);features.register(plugins);
        nativePlugins=new NativePluginHost(app,plugins,tasks,(binding,control)->tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,control),id->{});
        chosenSkill=skill("zephyr-chosen","Selected exact reference","selected reference body");otherSkill=skill("unselected","Globally active private reference","HIDDEN_UNSELECTED_SKILL_BODY");
        chosenPlugin=plugin("selected-plugin",new String[]{"read","other"});otherPlugin=plugin("other-plugin",new String[]{"read"});
        JSONArray registry=plugins.toolRegistry();for(int i=0;i<registry.length();i++){JSONObject entry=registry.getJSONObject(i);if(entry.getString("pluginId").equals("selected-plugin")&&entry.getString("tool").equals("read"))selectedTool=entry.getString("name");if(entry.getString("pluginId").equals("other-plugin"))hiddenTool=entry.getString("name");}
        JSONObject draft=WorkProfile.definition("draft-profile",1,"Project exact selection");draft.getJSONArray("skillRefs").put(new JSONObject().put("id",chosenSkill.getString("id")).put("revision",chosenSkill.getInt("revision")).put("digest",chosenSkill.getString("digest")).put("optional",false));
        draft.getJSONArray("pluginRefs").put(new JSONObject().put("id",chosenPlugin.getString("id")).put("installationId",chosenPlugin.getString("installationId")).put("stateRevision",chosenPlugin.getLong("stateRevision")).put("manifestDigest",WorkProfile.fingerprint(chosenPlugin.getJSONObject("manifest"))).put("tools",new JSONArray().put("read")).put("optional",false));
        var sources=new NativeWorkProfileSources(settings,features,nativePlugins,Set.of(),null);var host=new NativeWorkProfileHost(settings,sources);var page=host.newOwnerPage();page.setForeground(true);definition=page.save("",0,"Project exact selection",draft);var review=page.review(definition.getString("profileId"),1,new RunControl());original=new RunControl();selection=page.select(review,review.digest,original);page.close();
        run=tasks.startAttemptWithWorkProfile(tasks.createCapabilityTask("Compute the fixture result","").getString("id"),"capabilities",8,selection,original);
    }
    private JSONObject skill(String name,String description,String body)throws Exception {JSONObject row=skills.handle("import",new JSONObject().put("source","fixture:owner").put("manifest","---\nname: "+name+"\ndescription: "+description+"\n---\n"+body)).getJSONObject("skill");skills.handle("setEnabled",new JSONObject().put("id",row.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",row.getString("digest")));return skills.handle("get",new JSONObject().put("id",row.getString("id"))).getJSONObject("skill");}
    private JSONObject plugin(String id,String[] tools)throws Exception {JSONArray definitions=new JSONArray();for(String tool:tools)definitions.put(new JSONObject().put("name",tool).put("kind","echo"));JSONObject row=plugins.handle("install",new JSONObject().put("manifest",new JSONObject().put("id",id).put("name",id).put("version","1").put("tools",definitions)));row=plugins.setEnabledReviewed(id,row.getString("installationId"),true);for(String tool:tools)row=plugins.setToolGrantReviewed(id,row.getString("installationId"),row.getString("grantsDigest"),tool,true);return row;}
    private HostToolSupervisor supervisor()throws Exception {String id=run.getString("id"),r=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");String digest=selection.snapshot().getString("resolutionDigest");var policy=new WorkProfileToolSelection(selection,id,r,epoch,original,()->tasks.checkWorkProfileRun(id,r,epoch,digest,original),()->tasks.checkWorkProfileCapabilityBinding(id,r,epoch,digest,original),(tool,op,args,live)->tasks.checkWorkProfilePreparedTool(id,r,epoch,digest,tool,op,args,live));return new HostToolSupervisor(features,tasks,id,r,epoch,true,null,policy);}
    private void authorize(CapabilityPort port)throws Exception {JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(run.getString("id"),run.getString("runId"),names);}
    private void recordEvent(JSONObject event){try{tasks.event(run.getString("id"),run.getString("runId"),run.getInt("coordinatorEpoch"),event);}catch(Exception failed){throw new IllegalStateException(failed);}}
    private void instructionProfile(JSONObject source)throws Exception {
        JSONObject draft=WorkProfile.definition("instruction-draft",1,"Native owner task guidance");
        draft.getJSONArray("instructionRefs").put(new JSONObject().put("id",source.getString("id")).put("revision",source.getInt("revision")).put("digest",source.getString("digest")).put("optional",false));
        var host=new NativeWorkProfileHost(settings,new NativeWorkProfileSources(settings,features,nativePlugins,Set.of(),null));var page=host.newOwnerPage();page.setForeground(true);
        try{definition=page.save("",0,"Native owner task guidance",draft);var review=page.review(definition.getString("profileId"),1,new RunControl());assertTrue(review.view().getBoolean("ready"));original=new RunControl();selection=page.select(review,review.digest,original);}
        finally{page.close();}
        run=tasks.startAttemptWithWorkProfile(tasks.createCapabilityTask("Use exact native guidance only","").getString("id"),"capabilities",8,selection,original);
    }
    @After public void close()throws Exception {nativePlugins.close();features.close();db().close();}
    @Test public void originalSupervisorFiltersRegistryDiscoveryAndHiddenSkillRead()throws Exception {
        try(var port=supervisor()){String offered=port.tools().toString();assertTrue(offered.contains(selectedTool));assertFalse(offered.contains(hiddenTool));assertEquals(3,plugins.toolRegistry().length());
            JSONObject context=port.contextSnapshot("a query unrelated to zephyr");String discovery=context.getJSONObject("skillsDiscovery").toString();assertTrue(discovery.contains(chosenSkill.getString("id")));assertFalse(discovery.contains(otherSkill.getString("id")));assertEquals(1,context.getJSONObject("referenceValidation").getJSONArray("skills").length());
            assertThrows(SecurityException.class,()->port.validate("skills_read",new JSONObject().put("id",otherSkill.getString("id")).put("digest",otherSkill.getString("digest"))));
            port.validate("skills_read",new JSONObject().put("id",chosenSkill.getString("id")).put("digest",chosenSkill.getString("digest")));assertFalse(port.supports(hiddenTool));assertEquals(0,tasks.get(run.getString("id"),false).getJSONArray("operations").length());}
    }
    @Test public void originalPreparedSkillsListAndReadUseExactProfileSourceChecksNotNewDispatch() throws Exception {
        try(var port=supervisor()){
            authorize(port);String task=run.getString("id"),attempt=run.getString("runId");int epoch=run.getInt("coordinatorEpoch");
            var journal=tasks.journal(task,attempt,epoch,original);
            String list=journal.beginOperation("skills_list",new JSONObject());
            JSONObject discovered=port.execute("skills_list",new JSONObject().put("_hostOperationId",list),original);
            assertTrue(discovered.toString().contains(chosenSkill.getString("id")));assertFalse(discovered.toString().contains(otherSkill.getString("id")));
            journal.finishOperation(list,discovered);
            JSONObject args=new JSONObject().put("id",chosenSkill.getString("id")).put("digest",chosenSkill.getString("digest"));
            String read=journal.beginOperation("skills_read",args);
            JSONObject body=port.execute("skills_read",new JSONObject(args.toString()).put("_hostOperationId",read),original);
            assertTrue(body.toString().contains("selected reference body"));journal.finishOperation(read,body);
            assertEquals(2,tasks.get(task,false).getJSONArray("operations").length());
        }
    }
    @Test public void preparedSkillReadStillRejectsSourceRevocationAndMismatchedArguments() throws Exception {
        try(var port=supervisor()){
            authorize(port);var journal=tasks.journal(run.getString("id"),run.getString("runId"),run.getInt("coordinatorEpoch"),original);
            JSONObject args=new JSONObject().put("id",chosenSkill.getString("id")).put("digest",chosenSkill.getString("digest"));
            String read=journal.beginOperation("skills_read",args);
            assertThrows(SecurityException.class,()->port.execute("skills_read",new JSONObject(args.toString()).put("path","other.txt").put("_hostOperationId",read),original));
            skills.handle("setEnabled",new JSONObject().put("id",chosenSkill.getString("id")).put("enabled",false));
            assertThrows(Exception.class,()->port.execute("skills_read",new JSONObject(args.toString()).put("_hostOperationId",read),original));
            assertEquals(0,tasks.inferenceUsage(run.getString("id")).getJSONArray("receipts").length());
        }
    }
    @Test public void missingProfilePolicyCannotSilentlyUseTheLegacyGlobalSurface()throws Exception {
        assertThrows(SecurityException.class,()->new HostToolSupervisor(features,tasks,run.getString("id"),run.getString("runId"),run.getInt("coordinatorEpoch"),true));
        assertEquals(3,plugins.toolRegistry().length());assertEquals(0,tasks.get(run.getString("id"),false).getJSONArray("operations").length());
    }
    @Test public void originalSkillBookPagesTheEntireSelectedInstructionIntoOnlyTheLowerReferenceLane()throws Exception {
        String body="native-guidance-page ".repeat(660)+"NATIVE_GUIDANCE_LAST_PAGE_MARKER";
        JSONObject source=skill("paged-guidance","Full original task guidance",body);instructionProfile(source);
        try(var port=supervisor()){
            JSONObject context=port.contextSnapshot("unrelated query must not lose owner-selected guidance");String text=context.getString("context");
            assertTrue(text.contains("NATIVE_GUIDANCE_LAST_PAGE_MARKER"));assertTrue(text.contains(body));assertFalse(text.contains("HIDDEN_UNSELECTED_SKILL_BODY"));
            assertEquals("lower_priority_reference_data",context.getString("authority"));assertFalse(context.getBoolean("executionAuthorized"));
            JSONObject ref=context.getJSONArray("workProfileInstructionRefs").getJSONObject(0);assertEquals(source.getString("id"),ref.getString("id"));assertEquals(source.getInt("revision"),ref.getInt("revision"));assertEquals(source.getString("digest"),ref.getString("digest"));assertFalse(ref.has("content"));
            assertEquals(1,context.getJSONObject("referenceValidation").getJSONArray("skills").length());
            String tools=port.tools().toString();assertFalse(tools.contains("skills_read"));assertFalse(tools.contains("skills_execute"));assertFalse(tools.contains(selectedTool));
            assertThrows(SecurityException.class,()->port.validate("skills_read",new JSONObject().put("id",source.getString("id")).put("digest",source.getString("digest"))));
            assertEquals(0,tasks.get(run.getString("id"),false).getJSONArray("operations").length());
        }
    }
    @Test public void instructionWindowOverflowRefusesTheWholeReferenceRatherThanTruncatingOrDroppingIt()throws Exception {
        instructionProfile(skill("large-guidance","Cannot fit original host reference window","full-guidance ".repeat(1800)+"MUST_NOT_SILENTLY_LOSE_THE_TAIL"));
        try(var port=supervisor()){
            var refused=assertThrows(dev.pocketengine.runtime.ModelGateway.GatewayException.class,()->port.contextSnapshot("original task"));
            assertTrue(refused.getMessage().contains("全文超过原参考窗口"));assertTrue(refused.getMessage().contains("请减少指导引用"));assertTrue(refused.getMessage().contains("没有截断"));
            assertEquals(0,tasks.inferenceUsage(run.getString("id")).getJSONArray("receipts").length());
            assertEquals(0,tasks.get(run.getString("id"),false).getJSONArray("operations").length());
        }
    }
    @Test public void nativeInstructionRevocationAfterAssemblyPreventsTheOriginalModelRequest()throws Exception {
        instructionProfile(chosenSkill);
        try(var port=supervisor()){
            JSONObject context=port.contextSnapshot("original task");assertTrue(context.getString("context").contains("selected reference body"));
            skills.handle("setEnabled",new JSONObject().put("id",chosenSkill.getString("id")).put("enabled",false));
            assertThrows(Exception.class,()->port.beforeModelRequest("native-guidance-request",new JSONArray().put(new JSONObject().put("role","user").put("content",context.getString("context"))),original));
            assertEquals(0,tasks.inferenceUsage(run.getString("id")).getJSONArray("receipts").length());
        }
    }
    @Test public void realRunnerUsesOnlySelectedPluginAndDoesNotWarmAnEnabledUnselectedJs()throws Exception {
        var field=NativePluginHost.class.getDeclaredField("executable");field.setAccessible(true);var catalog=(ExecutablePluginCatalog)field.get(nativePlugins);
        var pkg=JsPluginPackage.author(new ByteArrayInputStream("throw new Error('unselected code must not run')".getBytes(StandardCharsets.UTF_8)),"unselected-js","Unselected JS","1",new RunControl());var inspected=catalog.inspect(pkg,new RunControl());catalog.installReviewed(inspected,inspected.reviewDigest,new RunControl());var approved=catalog.reviewInstalled("unselected-js",new RunControl());catalog.approveReviewed(approved,approved.reviewDigest,true,new RunControl());var enabled=catalog.reviewInstalled("unselected-js",new RunControl());catalog.setEnabledReviewed(enabled,enabled.reviewDigest,true,new RunControl());assertEquals(1,nativePlugins.approvedExtensionReferences(new RunControl()).length());
        CapabilityPort port=nativePlugins.bind(supervisor(),new ExtensionRuntime.Binding(run.getString("id"),run.getString("runId"),run.getInt("coordinatorEpoch")),original);authorize(port);List<JSONObject> requests=new ArrayList<>();
        try(Server server=new Server(requests,null)){var outcome=new AgentRunner(null,port).run(run.getString("id"),"Compute the fixture result",server.config(),original,this::recordEvent,tasks.journal(run.getString("id"),run.getString("runId"),run.getInt("coordinatorEpoch"),original));
            assertEquals(outcome.status+" "+outcome.summary,2,requests.size());String offered=requests.get(0).getJSONArray("tools").toString();assertTrue(offered.contains(selectedTool));assertFalse(offered.contains(hiddenTool));assertFalse(requests.toString().contains("HIDDEN_UNSELECTED_SKILL_BODY"));assertEquals("not_prepared",nativePlugins.runtimeSnapshot().getString("status"));
            JSONArray operations=tasks.get(run.getString("id"),false).getJSONArray("operations");assertEquals("The original echo tool is read-only and must not invent an effect operation",0,operations.length());JSONArray events=tasks.get(run.getString("id"),false).getJSONArray("events");boolean recorded=false;for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if("tool".equals(event.optString("type"))&&selectedTool.equals(event.optString("tool")))recorded=true;}assertTrue("Original read receipt event must be preserved",recorded);}
    }
    @Test public void revocationAfterModelAssemblyRejectsTheOldResponseBeforeAnyToolDispatch()throws Exception {
        CapabilityPort port=supervisor();authorize(port);List<JSONObject> requests=new ArrayList<>();
        try(Server server=new Server(requests,()->{try{plugins.setToolGrantReviewed(chosenPlugin.getString("id"),chosenPlugin.getString("installationId"),chosenPlugin.getString("grantsDigest"),"read",false);}catch(Exception e){throw new IllegalStateException(e);}})){
            var outcome=new AgentRunner(null,port).run(run.getString("id"),"Compute the fixture result",server.config(),original,this::recordEvent,tasks.journal(run.getString("id"),run.getString("runId"),run.getInt("coordinatorEpoch"),original));assertEquals(outcome.status+" "+outcome.summary,1,requests.size());assertEquals(0,tasks.get(run.getString("id"),false).getJSONArray("operations").length());assertFalse("completed".equals(outcome.status));}
    }
    private final class Server implements AutoCloseable {
        final HttpServer server;final List<JSONObject> requests;final Runnable afterFirst;
        Server(List<JSONObject> requests,Runnable afterFirst)throws Exception {this.requests=requests;this.afterFirst=afterFirst;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);JSONObject message=new JSONObject().put("role","assistant").put("content","Selected original native tool completed.");if(requests.size()==1){message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","selected-call").put("type","function").put("function",new JSONObject().put("name",selectedTool).put("arguments","{}"))));if(afterFirst!=null)afterFirst.run();}JSONObject reply=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",requests.size()==1?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",1).put("completion_tokens",1).put("total_tokens",2));byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","fixture-model",false,8,10);}
        public void close(){server.stop(0);}
    }
}
