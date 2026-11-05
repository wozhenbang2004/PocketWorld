package dev.pocketengine.runtime.extensions;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.core.ScopedToolCatalog;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.PlanningCapabilityPort;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real Runner/gateway/SQLite/W08 composition; JS uses Node fixture, never a model account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ExtensionLoopIntegrationTest {
    private final List<TaskStore> stores=new ArrayList<>();
    private TaskStore store(){TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());stores.add(store);return store;}
    @After public void closeFixtureDatabases()throws Exception {for(TaskStore store:stores){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(store)).close();}}
    private static final String INSTALL="33333333-3333-3333-3333-333333333333";
    private static final String SOURCE="""
            defineExtension({
              tools:[{name:'twice',description:'Pure arithmetic',parameters:{type:'object',properties:{n:{type:'integer'}},required:['n'],additionalProperties:false},execute(args,state){return {value:args.n*2,state};}}],
              context(input,state){return {text:'iteration='+(state.turns||0)+' results='+(state.results||0)+'; fixture-read-only-policy',state};},
              beforeTool(call,state){return {block:call.name==='workspace_write',reason:call.name==='workspace_write'?'fixture read-only gate':'',state};},
              onEvent(event,state){return {state:{turns:(state.turns||0)+(event.type==='turn_start'?1:0),results:(state.results||0)+(event.type==='tool_result'?1:0)}};}
            });
            """;
    private static ExtensionProgram program()throws Exception {byte[] source=SOURCE.getBytes(StandardCharsets.UTF_8);return new ExtensionProgram("fixture.extension","1",INSTALL,1,source,ExtensionProgram.sha256(source),2000);}
    private static JSONObject attempt(TaskStore tasks)throws Exception {String id=tasks.createCapabilityTask("calculate with a reviewed extension","").getString("id");return tasks.startAttempt(id,"capabilities",10);}
    private static JSONObject catalogAttempt(TaskStore tasks,boolean planning)throws Exception {
        return catalogAttempt(tasks,planning,"");
    }
    private static JSONObject catalogAttempt(TaskStore tasks,boolean planning,String conversation)throws Exception {
        String id=tasks.createCapabilityTask("inspect the original authorized tool catalogue",conversation).getString("id");tasks.initializeExecutionScope(id,20);
        JSONObject attempt=tasks.startAttempt(id,"capabilities",20);if(!planning)return attempt;
        String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");long revision=attempt.getJSONObject("executionScope").getLong("revision");
        assertThrows(SecurityException.class,()->tasks.enterExecutionPlanning(id,run,epoch,revision,()->true));
        // This controlled native fixture has dispatched no work. Pause the existing run
        // before owner planning admission, then prepare its first native tool surface.
        tasks.status(id,"paused","Native catalogue fixture paused before owner planning review");
        JSONObject entered=tasks.enterExecutionPlanning(id,run,epoch,revision,()->true);
        assertFalse(entered.getBoolean("executionStarted"));assertEquals(run,entered.getString("runId"));assertEquals(epoch,entered.getInt("coordinatorEpoch"));
        assertEquals("planning",entered.getJSONObject("executionScope").getString("executionPhase"));
        tasks.status(id,"preparing","Preparing the original native catalogue after planning review");return tasks.get(id,false);
    }
    private static RunControl control(JSONObject task){RunControl control=new RunControl();control.bindHostRun(task.optString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"));return control;}
    private static ExtensionRuntime.Binding binding(JSONObject task){return new ExtensionRuntime.Binding(task.optString("id"),task.optString("runId"),task.optInt("coordinatorEpoch"));}
    private static ExtensionHost.Catalogue catalogue(AtomicReference<List<ExtensionProgram>> selection){return new ExtensionHost.Catalogue(){
        public List<ExtensionProgram> approved(ExtensionRuntime.Binding binding,RunControl control){return selection.get();}
        public void checkCurrent(ExtensionProgram program,ExtensionRuntime.Binding binding,RunControl control){for(ExtensionProgram live:selection.get())if(live.id.equals(program.id)&&live.installationId.equals(program.installationId)&&live.approvalRevision==program.approvalRevision&&live.sourceSha256.equals(program.sourceSha256))return;throw new SecurityException("source revoked");}
    };}
    private static CapabilityPort combined(TaskStore tasks,FeatureHost features,JSONObject task,RunControl control,EngineModule attachments)throws Exception {
        var scope=new ModelCapabilityAdapters.Binding(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control,true,"");
        return new ModelCapabilityAdapters(scope,new TaskStoreCapabilityAuthority(tasks,scope,event->{}),new HostToolSupervisor(features,tasks,scope.taskId,scope.runId,scope.epoch,true),null,null,attachments,null);
    }
    private static EngineModule workspace(AtomicInteger writes){return new EngineModule(){public String id(){return "workspace";}public JSONObject snapshot()throws Exception{return new JSONObject().put("projects",new JSONArray());}public JSONObject handle(String command,JSONObject input)throws Exception{writes.incrementAndGet();return new JSONObject().put("ok",true);}};}

    /** A native scope mutation under the real Runner journal, not a model-supplied grant. */
    private static final class ScopeAllocationPort implements CapabilityPort {
        static final String TOOL="native_scope_allocate";
        final TaskStore tasks;final JSONObject task;
        final AtomicInteger executions=new AtomicInteger(),callbacks=new AtomicInteger();
        final AtomicReference<Exception> sourceFailure=new AtomicReference<>();
        ScopeAllocationPort(TaskStore tasks,JSONObject task){this.tasks=tasks;this.task=task;}
        public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",TOOL).put("description","Allocate part of the current native scope").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));}
        public boolean supports(String name){return TOOL.equals(name);}
        public boolean sideEffecting(String name){return TOOL.equals(name);}
        public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control){snapshot.retainRouteCheck(()->{Exception failure=sourceFailure.get();if(failure!=null)throw failure;});}
        public JSONObject execute(String name,JSONObject arguments,RunControl control)throws Exception{
            assertEquals(TOOL,name);executions.incrementAndGet();
            JSONObject scope=tasks.get(task.getString("id"),false).getJSONObject("executionScope");
            tasks.configureExecutionScope(task.getString("id"),scope.getLong("revision"),scope.getLong("deadlineAt"),scope.getInt("toolLimit")-4,scope.getInt("modelCallLimit")-4);
            return new JSONObject().put("ok",true).put("allocationApplied",true).put("operationId",arguments.getString("_hostOperationId"));
        }
        public void afterTool(String name,JSONObject arguments,JSONObject receipt,RunControl control){callbacks.incrementAndGet();}
    }

    @Test public void realLoopExecutesJsToolContextAndGateBeforeAnySqliteIntentOrApproval()throws Exception {
        TaskStore tasks=store();JSONObject task=attempt(tasks);RunControl control=control(task);AtomicInteger writes=new AtomicInteger();AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(List.of(program()));
        AtomicInteger ended=new AtomicInteger();
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,()->new ExtensionRuntime.Evaluator(){
            final ExtensionRuntimeTest.Node actual=new ExtensionRuntimeTest.Node();
            public String evaluate(byte[] source,JSONObject request,int budget,RunControl live,ExtensionRuntime.CurrentCheck check)throws Exception{if(request.optString("method").equals("event")&&request.getJSONObject("input").optString("type").equals("run_end"))ended.incrementAndGet();return actual.evaluate(source,request,budget,live,check);}
            public void close(){actual.close();}
        })){
            features.register(workspace(writes));CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);AtomicReference<String> custom=new AtomicReference<>();
            try(Server server=new Server((request,index)->{
                if(index==0)return call("blocked","workspace_write",new JSONObject().put("projectId","fixture").put("path","notes.txt").put("content","not written").put("expectedVersion",0));
                if(index==1){String name=extensionName(request);custom.set(name);return call("calculation",name,new JSONObject().put("n",21));}
                return new JSONObject().put("content","Actual arithmetic result received; no host write was made.");
            })){
                RunOutcome result=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{try{tasks.event(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
                assertEquals("needs_confirmation",result.status);assertEquals(1,ended.get());assertFalse(result.evidence.optBoolean("extensionEndUnavailable"));assertEquals(3,server.requests.size());assertEquals(0,writes.get());JSONObject stored=tasks.get(task.getString("id"),false);assertEquals(0,stored.getJSONArray("operations").length());assertNull(stored.optJSONArray("approvals"));
                assertTrue(server.requests.get(0).toString().contains("iteration=1 results=0"));assertTrue(server.requests.get(2).toString().contains("iteration=3 results=1"));assertTrue(server.requests.get(2).toString().contains("\\\"output\\\":42"));
                String durable=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control).restore().toString();assertFalse(durable.contains("fixture-read-only-policy"));assertTrue(durable.contains("extension_blocked"));
                assertThrows(SecurityException.class,()->tasks.checkCapability(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),custom.get(),control));
            }
        }
    }

    @Test public void revocationDuringActualModelCallDiscardsOldDecisionAndWithdrawsNextRequestTools()throws Exception {
        TaskStore tasks=store();JSONObject task=attempt(tasks);RunControl control=control(task);AtomicInteger writes=new AtomicInteger();AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(List.of(program()));
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            features.register(workspace(writes));CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try(Server server=new Server((request,index)->{
                if(index==0){selected.set(List.of());extensions.invalidate(INSTALL);return call("old-decision","workspace_write",new JSONObject().put("projectId","fixture").put("path","notes.txt").put("content","not written").put("expectedVersion",0));}
                return new JSONObject().put("content","Extension was withdrawn; no old action was executed.");
            })){
                RunOutcome result=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
                assertEquals("needs_confirmation",result.status);assertEquals(2,server.requests.size());assertEquals(0,writes.get());assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());assertNotNull(extensionName(server.requests.get(0)));assertNull(extensionName(server.requests.get(1)));assertFalse(server.requests.get(1).toString().contains("fixture-read-only-policy"));
            }
        }
    }

    @Test public void stopDuringExitHookCannotBeOverwrittenByTheDeferredSuccessfulResult()throws Exception {
        TaskStore tasks=store();JSONObject task=attempt(tasks);RunControl control=control(task);AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(List.of(program()));
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,()->new ExtensionRuntime.Evaluator(){
            final ExtensionRuntimeTest.Node actual=new ExtensionRuntimeTest.Node();
            public String evaluate(byte[] source,JSONObject request,int budget,RunControl live,ExtensionRuntime.CurrentCheck check)throws Exception{if(request.optString("method").equals("event")&&request.getJSONObject("input").optString("type").equals("run_end"))control.cancel();return actual.evaluate(source,request,budget,live,check);}
            public void close(){actual.close();}
        });Server server=new Server((request,index)->new JSONObject().put("content","Model answered; no external action."))){
            CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            RunOutcome result=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{try{tasks.event(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),event);}catch(Exception failure){throw new IllegalStateException(failure);}},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
            assertEquals("cancelled",result.status);assertEquals("cancelled",tasks.get(task.getString("id"),false).getString("status"));assertEquals(1,server.requests.size());assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());
        }
    }

    @Test public void combinedW08RealFileReceiptSurvivesCancellationAndPreparedJournalUnderOuterPort()throws Exception {
        TaskStore tasks=store();JSONObject task=attempt(tasks);RunControl control=control(task);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        AttachmentsModule actual=new AttachmentsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));AtomicReference<JSONObject> created=new AtomicReference<>();AtomicInteger writes=new AtomicInteger();
        EngineModule nativePort=new EngineModule(){public String id(){return "attachments";}public JSONObject snapshot()throws Exception{return actual.snapshot();}public JSONObject handle(String command,JSONObject input)throws Exception{JSONObject result=actual.handle(command,input);if(command.equals("createText")){writes.incrementAndGet();created.set(result);control.cancel();}return result;}};
        JSONObject args=new JSONObject().put("name","receipt.txt").put("mimeType","text/plain").put("content","kept after stop");JSONObject capability=new JSONObject().put("feature","attachments").put("command","createText").put("hostTaskId",id).put("projectId","");
        JSONObject approval=tasks.requireCapabilityApproval(id,run,"attachments_create_text",args,capability);tasks.decideCapabilityApproval(id,approval.getString("approvalId"),true);
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=ExtensionHost.disabled(tasks);Server server=new Server((request,index)->call("create","attachments_create_text",args))){
            features.register(actual);CapabilityPort port=extensions.bind(combined(tasks,features,task,control,nativePort),binding(task),control);
            RunOutcome result=new AgentRunner(null,port).run(id,task.getString("goal"),server.config(),control,event->{},tasks.journal(id,run,epoch,control));
            assertEquals("cancelled",result.status);assertEquals(1,writes.get());assertEquals(1,server.requests.size());JSONArray operations=tasks.get(id,false).getJSONArray("operations");assertEquals(1,operations.length());JSONObject operation=operations.getJSONObject(0);assertEquals("receipt",operation.getString("state"));assertEquals("created",operation.getJSONObject("receipt").getString("status"));assertEquals(created.get().getString("id"),operation.getJSONObject("receipt").getJSONObject("data").getString("id"));assertEquals("kept after stop",actual.handle("readText",new JSONObject().put("id",created.get().getString("id"))).getString("text"));assertFalse(tasks.hasUnresolved(id));
        }
    }

    @Test public void changedHttpPluginInstallationCannotRepointAnOldModelDecision()throws Exception {
        TaskStore tasks=store();JSONObject task=attempt(tasks);RunControl control=control(task);AtomicInteger dispatched=new AtomicInteger();AtomicReference<String> installation=new AtomicReference<>("installation-one");String name="plugin_0123456789abcdef01234567";
        EngineModule plugins=new EngineModule(){public String id(){return "plugins";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject args)throws Exception{
            if(command.equals("registry")){
                JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("query",new JSONObject().put("type","string"))).put("required",new JSONArray().put("query")).put("additionalProperties",false);
                JSONObject definition=new JSONObject().put("name",name).put("pluginId","fixture").put("tool","query").put("installationId",installation.get()).put("version",installation.get()).put("effect","read").put("description",installation.get()).put("schema",schema).put("executionBinding",new JSONObject().put("installationId",installation.get()).put("version",installation.get()));
                return new JSONObject().put("tools",new JSONArray().put(definition));
            }
            dispatched.incrementAndGet();return new JSONObject().put("ok",true);
        }};
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=ExtensionHost.disabled(tasks)){
            features.register(plugins);CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try(Server server=new Server((request,index)->{if(index==0){installation.set("installation-two");return call("old-install",name,new JSONObject().put("query","not dispatched"));}return new JSONObject().put("content","Only current metadata was refreshed.");})){
                RunOutcome result=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
                assertEquals("needs_confirmation",result.status);assertEquals(0,dispatched.get());assertEquals(2,server.requests.size());assertTrue(server.requests.get(0).getJSONArray("tools").toString().contains("installation-one"));assertTrue(server.requests.get(1).getJSONArray("tools").toString().contains("installation-two"));assertFalse(server.requests.get(1).getJSONArray("messages").toString().contains("old-install"));assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());
            }
        }
    }

    @Test public void skillDependingOnTheActuallyAdmittedJsToolAppearsInTheFirstRealModelRequest()throws Exception {
        TaskStore tasks=store();JSONObject task=attempt(tasks);RunControl control=control(task);ExtensionProgram selectedProgram=program();
        String wire="ext_"+ExtensionProgram.sha256((selectedProgram.id+"\n"+selectedProgram.installationId+"\n"+selectedProgram.approvalRevision+"\n"+selectedProgram.sourceSha256).getBytes(StandardCharsets.UTF_8)).substring(0,12)+"_twice";
        SkillsModule skills=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
        JSONObject skill=skills.handle("import",new JSONObject().put("source","test:final-admitted").put("manifest","---\nname: calculate-check\ndescription: calculate with a reviewed extension\n---\nREAD_ONLY_METHOD_BODY")
                .put("requiredCapabilities",new JSONArray().put(wire))).getJSONObject("skill");
        skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",skill.getString("digest")));
        AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(List.of(selectedProgram));
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new);Server server=new Server((request,index)->new JSONObject().put("content","Only reference discovery inspected; no method execution claimed."))){
            features.register(skills);CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
            assertEquals(1,server.requests.size());assertTrue(server.requests.get(0).getJSONArray("tools").toString().contains(wire));
            assertTrue("a currently callable JS dependency must not hide the matching Skill",server.requests.get(0).getJSONArray("messages").toString().contains(skill.getString("id")));
            assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());
        }
    }
    @Test public void stableJsDependencySurvivesANewInstallWhileTheActualCallableAndRunChange()throws Exception {
        TaskStore tasks=store();JSONObject first=attempt(tasks);ExtensionProgram one=program();
        String semantic=ExtensionRuntime.semanticCapability(one.id,"twice");assertEquals("js:fixture.extension:twice",semantic);
        SkillsModule skills=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));
        JSONObject skill=skills.handle("import",new JSONObject().put("source","test:semantic-dependency").put("manifest","---\nname: calculate-stable\ndescription: calculate with a reviewed extension\n---\nMATCHED_METHOD")
                .put("requiredCapabilities",new JSONArray().put(semantic))).getJSONObject("skill");
        skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",skill.getString("digest")));
        AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(List.of(one));String previousWire=null;JSONObject task=first;
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            features.register(skills);
            for(int attempt=0;attempt<2;attempt++){
                RunControl control=control(task);CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
                String callId="calculation-"+attempt;
                try(Server server=new Server((request,index)->index==0?call(callId,extensionName(request),new JSONObject().put("n",21)):new JSONObject().put("content","The actual pure calculation returned; business success is not inferred."))){
                    RunOutcome result=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
                    assertEquals(result.status+" · "+result.summary+" · "+result.evidence,2,server.requests.size());JSONObject request=server.requests.get(0);String wire=extensionName(request);assertNotNull(wire);if(previousWire!=null)assertNotEquals(previousWire,wire);previousWire=wire;
                    assertTrue(request.getJSONArray("tools").toString().contains(semantic));assertTrue(request.getJSONArray("messages").toString().contains(skill.getString("id")));assertTrue(server.requests.get(1).getJSONArray("messages").toString().contains("\\\"output\\\":42"));
                    assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());
                    tasks.endAttempt(task.getString("id"),task.getString("runId"),result.status,result.summary,result.evidence);
                }
                if(attempt==0){control.cancel();byte[] source=SOURCE.getBytes(StandardCharsets.UTF_8);selected.set(List.of(new ExtensionProgram(one.id,"2","44444444-4444-4444-4444-444444444444",2,source,ExtensionProgram.sha256(source),2000)));task=tasks.startAttempt(task.getString("id"),"capabilities",10);}
            }
        }
    }

    private static List<ExtensionProgram> discoveryPrograms()throws Exception {
        List<ExtensionProgram> result=new ArrayList<>();
        for(int packageIndex=0;packageIndex<2;packageIndex++){
            StringBuilder source=new StringBuilder("defineExtension({tools:[");
            for(int tool=0;tool<8;tool++){if(tool>0)source.append(',');source.append("{name:'calc_").append(tool).append("',description:'Deferred arithmetic operation ").append(tool).append("',parameters:").append(catalogSchema("n",new JSONObject().put("type","integer"))).append(",execute(args){return {value:args.n*2};}}");}
            byte[] bytes=source.append("]});").toString().getBytes(StandardCharsets.UTF_8);
            result.add(new ExtensionProgram("fixture.discovery"+packageIndex,"1",packageIndex==0?INSTALL:"44444444-4444-4444-4444-444444444444",1,bytes,ExtensionProgram.sha256(bytes),2000));
        }
        return List.copyOf(result);
    }
    private static JSONArray definitionNames(JSONArray tools)throws Exception {JSONArray names=new JSONArray();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));return names;}
    private static boolean hasTool(JSONObject request,String name)throws Exception {JSONArray names=definitionNames(request.getJSONArray("tools"));for(int i=0;i<names.length();i++)if(name.equals(names.getString(i)))return true;return false;}
    private static JSONObject catalogSchema(String required,JSONObject type)throws Exception {
        JSONObject properties=new JSONObject().put(required,type);
        for(int i=0;i<12;i++)properties.put("formatOption"+i,new JSONObject().put("type","string").put("enum",new JSONArray().put("preserve-original").put("fixed-decimal").put("scientific")));
        return new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray().put(required)).put("additionalProperties",false);
    }

    @Test public void deferredSchemasPublishOnlyAfterOriginalRunnerCheckpointAndActualNextRequest()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(discoveryPrograms());AtomicInteger writes=new AtomicInteger();
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            features.register(workspace(writes));CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try(Server server=new Server((request,index)->{
                if(index==0){assertNull(extensionName(request));assertTrue(hasTool(request,"tools_search"));assertTrue(hasTool(request,"workspace_read"));assertFalse(hasTool(request,"workspace_write"));assertTrue(request.getJSONArray("messages").toString().contains("pocketworld.tool-catalog-summary.v1"));return call("discover","tools_search",new JSONObject().put("query","Deferred arithmetic").put("limit",1));}
                if(index==1){String name=extensionName(request);assertNotNull(name);JSONObject restored=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control).restore();assertTrue(restored.getJSONArray("messages").toString().contains("pocketworld.tool-discovery.v1"));tasks.checkCapability(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),name,control);return call("calculate",name,new JSONObject().put("n",21));}
                return new JSONObject().put("content","The discovered exact approved extension returned its pure result.");
            })){
                RunOutcome outcome=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
                assertEquals(outcome.summary,3,server.requests.size());assertEquals("needs_confirmation",outcome.status);assertTrue(server.requests.get(2).getJSONArray("messages").toString().contains("\\\"output\\\":42"));assertEquals(0,writes.get());assertCatalogReads(tasks,task,1,false);
                assertFalse(port.supports("tools_search"));
            }
        }
    }

    @Test public void unpublishedSchemaIsRefusedAtBothNativeGatesAndReceiptCannotActivateCurrentRequest()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(discoveryPrograms());
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try{port.beginRun("discover",control);JSONObject first=port.prepareTurn("arithmetic",1,0,control);assertNull(extensionName(first));String hidden=extensionName(new JSONObject().put("tools",port.tools()));assertNotNull(hidden);
                assertThrows(SecurityException.class,()->port.beforeTool(hidden,new JSONObject().put("n",21),control));assertThrows(SecurityException.class,()->port.execute(hidden,new JSONObject().put("n",21),control));
                JSONObject receipt=port.execute("tools_search",new JSONObject().put("query","Deferred arithmetic").put("limit",1),control);port.afterTool("tools_search",new JSONObject(),receipt,control);
                String activated=receipt.getJSONArray("tools").getJSONObject(0).getString("toolName");assertThrows(SecurityException.class,()->port.execute(activated,new JSONObject().put("n",21),control));
                JSONObject next=port.prepareTurn("arithmetic",2,1,control);assertTrue(hasTool(next,activated));assertEquals(42,port.execute(activated,new JSONObject().put("n",21),control).getInt("output"));
                assertThrows(SecurityException.class,()->port.afterTool("tools_search",new JSONObject(),receipt,control));
            }finally{port.close();}
        }
    }

    @Test public void frozenOriginalToolScopeUsesMetadataWithoutAddingABusinessGrant()throws Exception {
        TaskStore tasks=store();String id=tasks.createCapabilityTask("bounded extension discovery","").getString("id");tasks.initializeExecutionScope(id,20);JSONObject task=tasks.startAttempt(id,"capabilities",10);RunControl control=control(task);AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(discoveryPrograms());
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try{port.beginRun("native frozen scope",control);JSONArray original=definitionNames(port.tools());tasks.authorizeTools(id,task.getString("runId"),original);JSONObject scope=tasks.executionScopeSnapshot(id,task.getString("runId"),task.getInt("coordinatorEpoch"),control);tasks.extendExecutionToolsForOwner(id,task.getString("runId"),task.getInt("coordinatorEpoch"),scope.getLong("revision"),new JSONArray());
                JSONObject prepared=port.prepareTurn("arithmetic",1,0,control);assertTrue(hasTool(prepared,"tools_search"));assertTrue(prepared.getJSONArray("tools").length()<original.length());JSONObject after=tasks.executionScopeSnapshot(id,task.getString("runId"),task.getInt("coordinatorEpoch"),control);assertEquals(original.toString(),after.getJSONArray("tools").toString());assertThrows(SecurityException.class,()->tasks.checkCapability(id,task.getString("runId"),task.getInt("coordinatorEpoch"),"tools_search",control));
            }finally{port.close();}
        }
    }

    @Test public void revokedSelectedPackageCannotActivateAnAlreadyReturnedDiscoveryReceipt()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(discoveryPrograms());
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try{port.beginRun("discover",control);port.prepareTurn("arithmetic",1,0,control);JSONObject receipt=port.execute("tools_search",new JSONObject().put("query","Deferred arithmetic").put("limit",1),control);selected.set(List.of());extensions.invalidate(INSTALL);assertThrows(SecurityException.class,()->port.afterTool("tools_search",new JSONObject(),receipt,control));JSONObject current=port.prepareTurn("revoked",2,1,control);assertNull(extensionName(current));assertFalse(hasTool(current,"tools_search"));assertFalse(port.supports(receipt.getJSONArray("tools").getJSONObject(0).getString("toolName")));
            }finally{port.close();}
        }
    }

    @Test public void originalHundredBusinessGrantsDeferWithoutLosingTheHundredthAndStayFullyPublishedAfterActivation()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);AtomicReference<List<ExtensionProgram>> selected=new AtomicReference<>(discoveryPrograms());
        JSONArray baseTools=new JSONArray();for(int i=0;i<84;i++)baseTools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","native_"+i).put("description","Original resident definition").put("parameters",new JSONObject().put("type","object"))));
        CapabilityPort base=new CapabilityPort(){public JSONArray tools()throws Exception{return new JSONArray(baseTools.toString());}public boolean supports(String name){return name.startsWith("native_");}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{throw new AssertionError("No native effect is used by this surface test");}};
        try(ExtensionHost extensions=new ExtensionHost(catalogue(selected),tasks,ExtensionRuntimeTest.Node::new)){
            CapabilityPort port=extensions.bind(base,binding(task),control);
            try{port.beginRun("bounded discovery",control);JSONArray full=port.tools();assertEquals(100,full.length());JSONObject prepared=port.prepareTurn("arithmetic",1,0,control);assertEquals(85,prepared.getJSONArray("tools").length());assertTrue(hasTool(prepared,"tools_search"));assertNull(extensionName(prepared));
                assertTrue(prepared.getJSONArray("tools").toString().getBytes(StandardCharsets.UTF_8).length+prepared.getJSONObject("reference").optString("context").getBytes(StandardCharsets.UTF_8).length<full.toString().getBytes(StandardCharsets.UTF_8).length);
                JSONObject originalScope=tasks.executionScopeSnapshot(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);assertEquals(100,originalScope.getJSONArray("tools").length());assertThrows(SecurityException.class,()->tasks.checkCapability(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),"tools_search",control));
                JSONObject query=new JSONObject().put("query","Deferred arithmetic").put("limit",12);JSONObject first=port.execute("tools_search",query,control);assertTrue(first.getBoolean("truncated"));checkpointDiscovery(tasks,task,control,port,query,first,1);
                JSONObject secondQuery=new JSONObject(query.toString()).put("cursor",first.getString("nextCursor"));JSONObject second=port.execute("tools_search",secondQuery,control);assertFalse(second.getBoolean("truncated"));assertEquals(16,first.getJSONArray("tools").length()+second.getJSONArray("tools").length());checkpointDiscovery(tasks,task,control,port,secondQuery,second,2);
                String hidden=first.getJSONArray("tools").getJSONObject(0).getString("toolName");assertThrows(SecurityException.class,()->port.execute(hidden,new JSONObject().put("n",1),control));
                for(int round=2;round<=3;round++){JSONObject current=port.prepareTurn("arithmetic",round,2,control);assertEquals(100,current.getJSONArray("tools").length());assertFalse(hasTool(current,"tools_search"));assertFalse(current.getJSONObject("reference").optString("context").contains("tool_discovery_reference"));assertEquals(ScopedToolCatalog.fingerprint(definitionsByName(full)),ScopedToolCatalog.fingerprint(definitionsByName(current.getJSONArray("tools"))));assertThrows(SecurityException.class,()->port.execute("tools_search",query,control));}
                assertEquals(originalScope.getJSONArray("tools").toString(),tasks.executionScopeSnapshot(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control).getJSONArray("tools").toString());assertCatalogReads(tasks,task,2,false);
            }finally{port.close();}
        }
    }

    @Test public void hostNativeAndPluginDiscoveryNeedsNoJsAndRejectsHiddenBaseToolsAndChangedInstallations()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);AtomicInteger jsCreated=new AtomicInteger(),dispatches=new AtomicInteger();AtomicReference<JSONArray> registry=new AtomicReference<>(catalogPluginDescriptors());
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(new AtomicReference<>(List.of())),tasks,()->{jsCreated.incrementAndGet();throw new AssertionError("Metadata search must not create a JS evaluator");})){
            features.register(workspace(dispatches));features.register(catalogPlugins(registry,dispatches));CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try{
                port.beginRun("native catalogue",control);JSONObject first=port.prepareTurn("inspect tools",1,0,control);assertTrue(hasTool(first,"workspace_read"));assertTrue(hasTool(first,"tools_search"));assertFalse(hasTool(first,"workspace_write"));assertFalse(hasTool(first,"plugin_catalog_0"));
                JSONObject write=new JSONObject().put("projectId","fixture").put("path","x.txt").put("content","hidden").put("expectedVersion",0);assertThrows(SecurityException.class,()->port.beforeTool("workspace_write",write,control));assertThrows(SecurityException.class,()->port.execute("workspace_write",write,control));
                assertTrue(port.execute("workspace_read",new JSONObject().put("projectId","fixture").put("path","x.txt"),control).getBoolean("ok"));assertEquals(1,dispatches.get());
                JSONObject query=new JSONObject().put("query","Catalog operation").put("limit",1),receipt=port.execute("tools_search",query,control);String plugin=receipt.getJSONArray("tools").getJSONObject(0).getString("toolName");assertEquals("PLUGIN",receipt.getJSONArray("tools").getJSONObject(0).getString("sourceKind"));checkpointDiscovery(tasks,task,control,port,query,receipt,1);
                assertThrows(SecurityException.class,()->port.execute(plugin,new JSONObject().put("query","hidden until next request"),control));JSONObject next=port.prepareTurn("inspect tools",2,1,control);assertTrue(hasTool(next,plugin));
                JSONObject row=registry.get().getJSONObject(0);String definition=ScopedToolCatalog.fingerprint(row.getJSONObject("schema"));row.put("installationId","reinstalled-same-schema");assertEquals(definition,ScopedToolCatalog.fingerprint(row.getJSONObject("schema")));
                assertThrows(SecurityException.class,()->port.execute(plugin,new JSONObject().put("query","old decision"),control));assertFalse(port.responseCurrent(control));assertThrows(SecurityException.class,()->port.afterTool("tools_search",query,receipt,control));assertEquals(1,dispatches.get());assertEquals(0,jsCreated.get());assertCatalogReads(tasks,task,1,false);
            }finally{port.close();}
        }
    }

    @Test public void planningFiltersPluginClaimsBeforeCatalogueAndKeepsOriginalNativeReads()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,true);RunControl control=control(task);AtomicInteger jsCreated=new AtomicInteger(),dispatches=new AtomicInteger();AtomicReference<JSONArray> registry=new AtomicReference<>(catalogPluginDescriptors());
        ExecutionPlanning planning=tasks.executionPlanningForRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(new AtomicReference<>(List.of())),tasks,()->{jsCreated.incrementAndGet();throw new AssertionError("Planning metadata must not start JS");})){
            features.register(workspace(dispatches));features.register(catalogPlugins(registry,dispatches));CapabilityPort port=PlanningCapabilityPort.wrap(extensions.bind(combined(tasks,features,task,control,null),binding(task),control),planning);
            try{
                port.beginRun("read-only planning",control);JSONObject prepared=port.prepareTurn("inspect",1,0,control);
                JSONArray granted=tasks.get(task.getString("id"),false).getJSONObject("executionScope").getJSONArray("tools");java.util.Set<String> originalTools=new java.util.HashSet<>();for(int i=0;i<granted.length();i++)originalTools.add(granted.getString(i));
                assertTrue("Planning must filter the original native and plugin grants, not an empty fixture scope",originalTools.containsAll(java.util.Set.of("workspace_read","workspace_write","plugin_catalog_0")));
                assertTrue(hasTool(prepared,"workspace_read"));assertFalse(hasTool(prepared,"workspace_write"));assertFalse(hasTool(prepared,"plugin_catalog_0"));assertFalse(prepared.getJSONObject("reference").toString().contains("Catalog operation"));
                assertEquals(ExecutionPlanning.Access.DENY,port.planningAccess("plugin_catalog_0",new JSONObject().put("query","declared read")));assertThrows(SecurityException.class,()->port.execute("plugin_catalog_0",new JSONObject().put("query","declared read"),control));
                JSONObject args=new JSONObject().put("projectId","fixture").put("path","x.txt");ExecutionPlanning.Snapshot phase=planning.capture();ExecutionPlanning.ReadClaim claim=planning.beginRead(phase,"workspace_read",args,"planning-read",ExecutionPlanning.Access.READ);
                JSONObject receipt=port.execute("workspace_read",new JSONObject(args.toString()).put("_hostOperationId",claim.operationId),control);planning.finishRead(claim,receipt,ExecutionPlanning.ReadOutcome.RECEIPT);assertEquals(1,dispatches.get());assertEquals(0,jsCreated.get());
                JSONArray operations=tasks.get(task.getString("id"),false).getJSONArray("operations");assertEquals(1,operations.length());assertEquals("read_receipt",operations.getJSONObject(0).getString("state"));assertEquals("workspace_read",operations.getJSONObject(0).getString("tool"));assertEquals(1,tasks.get(task.getString("id"),false).getJSONObject("executionScope").getInt("toolsUsed"));
            }finally{port.close();}
        }
    }

    @Test public void smallNativeCatalogueKeepsFullDefinitionsWhenExactReferenceCostsMore()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);AtomicInteger dispatches=new AtomicInteger();
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=ExtensionHost.disabled(tasks)){
            features.register(workspace(dispatches));CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try{port.beginRun("small native surface",control);JSONArray full=port.tools();JSONObject prepared=port.prepareTurn("inspect",1,0,control);assertFalse(hasTool(prepared,"tools_search"));assertEquals(ScopedToolCatalog.fingerprint(full),ScopedToolCatalog.fingerprint(prepared.getJSONArray("tools")));assertTrue(hasTool(prepared,"workspace_write"));assertEquals(0,dispatches.get());assertEquals(0,tasks.get(task.getString("id"),false).getJSONArray("operations").length());}
            finally{port.close();}
        }
    }

    @Test public void completedNativeAllocationKeepsItsReceiptAndAllowsANewTurnWithoutReplayingTheEffect()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);ScopeAllocationPort nativePort=new ScopeAllocationPort(tasks,task);
        JSONObject originalScope=task.getJSONObject("executionScope");
        try(ExtensionHost extensions=ExtensionHost.disabled(tasks);Server server=new Server((request,index)->{
            if(index==0){assertTrue(hasTool(request,ScopeAllocationPort.TOOL));return call("allocate",ScopeAllocationPort.TOOL,new JSONObject());}
            assertEquals("only the next normal model request may answer",1,index);
            assertTrue(request.getJSONArray("messages").toString().contains("allocationApplied"));
            return new JSONObject().put("content","The native allocation is recorded; the foreground answer is complete.");
        })){
            CapabilityPort port=PlanningCapabilityPort.wrap(extensions.bind(nativePort,binding(task),control),tasks.executionPlanningForRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
            RunOutcome outcome=new AgentRunner(null,port).run(task.getString("id"),task.getString("goal"),server.config(),control,event->{},tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control));
            assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(2,server.requests.size());assertEquals(1,nativePort.executions.get());
            assertEquals("old callbacks cannot inherit the revised scope",0,nativePort.callbacks.get());
            JSONObject stored=tasks.get(task.getString("id"),false),scope=stored.getJSONObject("executionScope");JSONArray operations=stored.getJSONArray("operations");
            assertEquals(1,operations.length());assertEquals("receipt",operations.getJSONObject(0).getString("state"));assertTrue(operations.getJSONObject(0).getJSONObject("receipt").getBoolean("allocationApplied"));assertFalse(tasks.hasUnresolved(task.getString("id")));
            assertEquals(task.getString("runId"),stored.getString("runId"));assertEquals(task.getInt("coordinatorEpoch"),stored.getInt("coordinatorEpoch"));
            assertEquals(originalScope.getLong("revision")+1,scope.getLong("revision"));assertEquals(originalScope.getInt("toolLimit")-4,scope.getInt("toolLimit"));assertEquals(originalScope.getInt("modelCallLimit")-4,scope.getInt("modelCallLimit"));
            assertEquals(1,scope.getInt("toolsUsed"));assertEquals(2,scope.getInt("modelCallsUsed"));
        }
    }

    @Test public void scopeRevisionChangeStillRejectsTheOldDecisionAndCannotBeRefreshedByAnAfterToolReceipt()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);ScopeAllocationPort nativePort=new ScopeAllocationPort(tasks,task);
        try(ExtensionHost extensions=ExtensionHost.disabled(tasks)){
            CapabilityPort port=extensions.bind(nativePort,binding(task),control);
            try{
                port.beginRun("original scope",control);port.prepareTurn("allocate",1,0,control);
                ExecutionPlanning planning=tasks.executionPlanningForRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);ExecutionPlanning.Snapshot old=planning.capture();
                JSONObject scope=tasks.get(task.getString("id"),false).getJSONObject("executionScope");tasks.configureExecutionScope(task.getString("id"),scope.getLong("revision"),scope.getLong("deadlineAt"),scope.getInt("toolLimit")-4,scope.getInt("modelCallLimit")-4);
                assertThrows(ExecutionPlanning.ScopeRevisionChanged.class,()->planning.requireCurrent(old));
                assertThrows(ExecutionPlanning.ScopeRevisionChanged.class,()->port.execute(ScopeAllocationPort.TOOL,new JSONObject(),control));assertEquals(0,nativePort.executions.get());
                assertEquals("stale_extension_context",port.beforeTool(ScopeAllocationPort.TOOL,new JSONObject(),control).getString("code"));
                assertThrows(SecurityException.class,()->port.afterTool(ScopeAllocationPort.TOOL,new JSONObject(),new JSONObject().put("ok",true),control));
                port.prepareTurn("new scope",2,0,control);port.beforeModelRequest("fresh-request",new JSONArray(),control);
                assertNull(port.beforeTool(ScopeAllocationPort.TOOL,new JSONObject(),control));assertEquals(0,nativePort.executions.get());assertEquals(0,nativePort.callbacks.get());
            }finally{port.close();}
        }
    }

    @Test public void planAndPhaseChangesRemainErrorsAtThePostReceiptBoundary()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,true);RunControl control=control(task);AtomicInteger dispatches=new AtomicInteger();
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=ExtensionHost.disabled(tasks)){
            features.register(workspace(dispatches));CapabilityPort port=extensions.bind(combined(tasks,features,task,control,null),binding(task),control);
            try{
                port.beginRun("original planning scope",control);port.prepareTurn("inspect",1,0,control);
                ExecutionPlanning planning=tasks.executionPlanningForRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);
                JSONObject proposal=new JSONObject().put("format","pocketworld.execution-plan.v1").put("goal","Inspect the original evidence before execution").put("steps",new JSONArray().put(new JSONObject().put("id","inspect").put("title","Inspect the source").put("detail","Read the original source and review the exact plan.")));
                JSONObject submitted=planning.submitPlan(planning.capture(),proposal,"native-plan-fixture");
                SecurityException planChanged=assertThrows(SecurityException.class,()->port.afterTool("workspace_read",new JSONObject(),new JSONObject().put("ok",true),control));assertFalse(planChanged instanceof ExecutionPlanning.ScopeRevisionChanged);
                port.prepareTurn("reviewed plan",2,1,control);
                ExecutionPlanning.Snapshot beforeApproval=planning.capture();
                tasks.decideExecutionPlanApproval(task.getString("id"),submitted.getString("approvalId"),true,task.getString("runId"),task.getInt("coordinatorEpoch"),submitted.getLong("scopeRevision"),submitted.getLong("planRevision"),submitted.getString("planArtifactRef"),submitted.getString("planHash"),()->true);
                assertThrows(SecurityException.class,()->planning.reference(beforeApproval));
                SecurityException phaseChanged=assertThrows(SecurityException.class,()->port.afterTool("workspace_read",new JSONObject(),new JSONObject().put("ok",true),control));assertFalse(phaseChanged instanceof ExecutionPlanning.ScopeRevisionChanged);assertEquals(0,dispatches.get());
            }finally{port.close();}
        }
    }
    @Test public void approvedPlanContinuationPublishesNativeDecisionWithoutForgingOwnerFeedback()throws Exception {
        approvedPlanContinuation(null);
    }
    @Test public void newReadOnlyOwnerInputAfterPlanApprovalStillHidesWrite()throws Exception {
        approvedPlanContinuation("Continue only read-only inspection; do not modify the file.");
    }
    @Test public void sameTextNewOwnerInputAfterPlanApprovalRestoresReadOnlyHint()throws Exception {
        approvedPlanContinuation("Continue the same fixed QA file goal in native read-only planning. Submit exactly the plan JSON from the original owner message; stop for native review.");
    }
    private void approvedPlanContinuation(String newOwnerInput)throws Exception {
        TaskStore tasks=store();String conversation=java.util.UUID.randomUUID().toString();JSONObject original=catalogAttempt(tasks,true,conversation);String id=original.getString("id");RunControl first=control(original);AtomicInteger writes=new AtomicInteger();
        String ownerFeedback="Continue the same fixed QA file goal in native read-only planning. Submit exactly the plan JSON from the original owner message; stop for native review.";
        String untrustedMarker="UNTRUSTED_EXTENSION_CONTEXT_FIXTURE";
        byte[] referenceSource=("defineExtension({context(input,state){return {text:'"+untrustedMarker+"',state};}});").getBytes(StandardCharsets.UTF_8);
        ExtensionProgram referenceProgram=new ExtensionProgram("fixture.native_context","1",INSTALL,referenceSource,ExtensionProgram.sha256(referenceSource),2000);
        try(FeatureHost features=new FeatureHost(event->{},tasks,()->false);ExtensionHost extensions=new ExtensionHost(catalogue(new AtomicReference<>(List.of(referenceProgram))),tasks,ExtensionRuntimeTest.Node::new)){
            tasks.feedback(id,ownerFeedback);
            features.register(workspace(writes));CapabilityPort initial=extensions.bind(combined(tasks,features,original,first,null),binding(original),first);JSONObject submitted;
            try{
                initial.beginRun("original planning scope",first);initial.prepareTurn("inspect",1,0,first);
                ExecutionPlanning planning=tasks.executionPlanningForRun(id,original.getString("runId"),original.getInt("coordinatorEpoch"),first);
                JSONObject proposal=new JSONObject().put("format","pocketworld.execution-plan.v1").put("goal","Complete only the original single-file goal").put("steps",new JSONArray().put(new JSONObject().put("id","edit").put("title","Write and read the exact file").put("detail","Keep the original file, version and content constraints; no other effects.")));
                submitted=planning.submitPlan(planning.capture(),proposal,"native-plan-fixture");
                String oldCall="historical-plan-submit";
                JSONArray historical=new JSONArray().put(new JSONObject().put("role","user").put("content",original.getString("goal")))
                        .put(new JSONObject().put("role","user").put("content","机主对上次结果的反馈：\n"+ownerFeedback).put("ownerRecallFeedback",true))
                        .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id",oldCall).put("type","function")
                                .put("function",new JSONObject().put("name",ExecutionPlanning.SUBMIT).put("arguments",new JSONObject().put("plan",proposal).toString())))))
                        .put(new JSONObject().put("role","tool").put("tool_call_id",oldCall).put("content",submitted.toString()));
                tasks.journal(id,original.getString("runId"),original.getInt("coordinatorEpoch"),first).checkpoint(historical,1,new JSONObject());
                tasks.endAttempt(id,original.getString("runId"),"awaiting_user","Await the original plan review",new JSONObject());
                tasks.decideExecutionPlanApproval(id,submitted.getString("approvalId"),true,original.getString("runId"),original.getInt("coordinatorEpoch"),submitted.getLong("scopeRevision"),submitted.getLong("planRevision"),submitted.getString("planArtifactRef"),submitted.getString("planHash"),()->true);
            }finally{initial.close();first.cancel();}
            JSONObject resumed=tasks.startAttempt(id,"capabilities",10);RunControl current=control(resumed);
            ExecutionPlanning planning=tasks.executionPlanningForRun(id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"),current);
            JSONObject nativeFacts=planning.reference(planning.capture());
            assertEquals("executing",nativeFacts.getString("executionPhase"));assertTrue(nativeFacts.getBoolean("planApprovalRecorded"));assertFalse(nativeFacts.getBoolean("authorizesExecution"));
            assertEquals(submitted.getString("approvalId"),nativeFacts.getJSONObject("nativePlanApproval").getString("approvalId"));
            assertEquals(original.getString("runId"),nativeFacts.getJSONObject("nativePlanApproval").getString("runId"));
            assertEquals(resumed.getString("runId"),nativeFacts.getString("runId"));assertNotEquals(original.getString("runId"),resumed.getString("runId"));
            CapabilityPort port=PlanningCapabilityPort.wrap(extensions.bind(combined(tasks,features,resumed,current,null),binding(resumed),current),planning);
            AtomicInteger newInputReady=new AtomicInteger(),newInputDelivered=new AtomicInteger();
            if(newOwnerInput!=null)tasks.openOwnerInputMailbox(id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"),conversation,current);
            AgentRunner.OwnerInputs ownerInputs=newOwnerInput==null?null:(included,boundary,close,control)->{
                if(newInputReady.get()>0&&newInputDelivered.compareAndSet(0,1))tasks.appendOwnerInput(id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"),conversation,
                        new JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("clientMessageId",java.util.UUID.randomUUID().toString()).put("content",newOwnerInput).put("attachments",new JSONArray()),"steer",control);
                return tasks.pollOwnerInputs(id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"),included,boundary,close,control);
            };
            AgentRunner.RequestProjection wireProjection=new dev.pocketengine.features.assistant.AttachmentRequestProjection(
                    new dev.pocketengine.features.attachments.AttachmentBook(new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(),"approved-plan-projection-"+java.util.UUID.randomUUID())),null);
            try(Server server=new Server((request,index)->{newInputReady.incrementAndGet();return new JSONObject().put("content","The current native plan decision is visible; no fixture business operation was requested.");})){
                RunOutcome outcome=new AgentRunner(null,port,dev.pocketengine.runtime.context.ContextBudgetPolicy.currentHostSource(),wireProjection,ownerInputs).run(id,resumed.getString("goal"),server.config(),current,event->{},tasks.journal(id,resumed.getString("runId"),resumed.getInt("coordinatorEpoch"),current));
                assertEquals(outcome.summary,newOwnerInput==null?1:2,server.requests.size());JSONObject firstRequest=server.requests.get(0);
                assertTrue("The approved stage resumes before any new owner input",hasTool(firstRequest,"workspace_write")||hasTool(firstRequest,"tools_search"));
                JSONObject request=server.requests.get(server.requests.size()-1);JSONArray offeredNames=new JSONArray();
                for(int i=0;i<request.getJSONArray("tools").length();i++)offeredNames.put(request.getJSONArray("tools").getJSONObject(i).getJSONObject("function").getString("name"));
                assertTrue("Original read must remain published: "+offeredNames,hasTool(request,"workspace_read"));
                if(newOwnerInput==null)assertTrue("Current approved execution scope must still offer write or discovery; stage-only feedback must not remove both: "+offeredNames,hasTool(request,"workspace_write")||hasTool(request,"tools_search"));
                else assertFalse("A later real owner input must restore the read-only filter, including identical text: "+offeredNames,hasTool(request,"workspace_write"));
                boolean nativeApproval=false,genuineFeedback=false,untrustedReference=false,historicalWait=false,currentReviewMeaning=false,newInputPreserved=false;
                for(int i=0;i<request.getJSONArray("messages").length();i++){
                    JSONObject message=request.getJSONArray("messages").getJSONObject(i);String content=message.optString("content");
                    if("system".equals(message.optString("role"))){assertFalse(content.contains(untrustedMarker));if(content.contains("nativePlanApproval")&&content.contains(submitted.getString("approvalId"))){nativeApproval=true;currentReviewMeaning=content.contains("历史计划待审回执不再表示当前状态")&&content.contains("批准来源run/epoch")&&content.contains("新的主人停止或修改仍然有效");}}
                    if("tool".equals(message.optString("role"))&&"historical-plan-submit".equals(message.optString("tool_call_id")))historicalWait="execution_plan_awaiting_review".equals(new JSONObject(content).optString("code"));
                    if("user".equals(message.optString("role"))){assertFalse(message.has("ownerMessageId"));assertFalse(message.has("ownerInputMode"));assertFalse(content.contains(submitted.getString("approvalId")));genuineFeedback|=content.contains(ownerFeedback);untrustedReference|=content.contains(untrustedMarker);newInputPreserved|=newOwnerInput!=null&&content.equals(newOwnerInput);}
                }
                if(newOwnerInput!=null){assertEquals(1,newInputDelivered.get());assertTrue("The new owner input remains genuine user content",newInputPreserved);JSONArray admitted=tasks.get(id,false).getJSONArray("ownerInputs");assertEquals(1,admitted.length());assertEquals("materialized",admitted.getJSONObject(0).getString("queueState"));assertEquals(resumed.getString("runId"),admitted.getJSONObject(0).getString("materializedRunId"));}
                assertTrue("Keep the actual prior awaiting-review receipt; it is historical evidence, not a new current wait",historicalWait);
                assertTrue("The actual model request must carry the current native plan decision",nativeApproval);assertTrue(genuineFeedback);assertTrue(untrustedReference);
                assertTrue("The current SQL-validated approval must explain the satisfied old review wait and the original approval-run provenance",currentReviewMeaning);
                ModelConfig account=new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","44444444-4444-4444-4444-444444444444").put("accountVersion",1L).put("modelId","fixture-model")));
                var wire=dev.pocketengine.runtime.providers.AccountResponsesAdapter.request(account,request.getJSONArray("messages"),request.getJSONArray("tools"));
                var bytes=dev.pocketengine.features.proxy.providers.ResponsesRequest.class.getDeclaredField("bytes");bytes.setAccessible(true);JSONObject actual=new JSONObject(new String((byte[])bytes.get(wire),StandardCharsets.UTF_8));
                boolean accountNative=false,accountRead=false;JSONArray input=actual.getJSONArray("input"),functions=actual.getJSONArray("tools").getJSONObject(0).getJSONArray("tools");
                for(int i=0;i<functions.length();i++)accountRead|="workspace_read".equals(functions.getJSONObject(i).optString("name"));
                for(int i=0;i<input.length();i++){JSONObject message=input.getJSONObject(i);String content=message.optString("content");if("developer".equals(message.optString("role"))){assertFalse(content.contains(untrustedMarker));accountNative|=content.contains("nativePlanApproval")&&content.contains(submitted.getString("approvalId"));}if("user".equals(message.optString("role")))assertFalse(content.contains(submitted.getString("approvalId")));}
                assertTrue(accountNative);assertTrue(accountRead);
                assertEquals(0,writes.get());assertEquals(ownerFeedback,tasks.get(id,false).getString("feedback"));
            }finally{port.close();current.cancel();}
        }
    }

    @Test public void sourceRevocationAndStorageErrorsAreNotSwallowedWhenTheScopeAlsoChanged()throws Exception {
        TaskStore tasks=store();JSONObject task=catalogAttempt(tasks,false);RunControl control=control(task);ScopeAllocationPort nativePort=new ScopeAllocationPort(tasks,task);
        try(ExtensionHost extensions=ExtensionHost.disabled(tasks)){
            CapabilityPort port=extensions.bind(nativePort,binding(task),control);
            try{
                port.beginRun("original source",control);port.prepareTurn("allocate",1,0,control);
                JSONObject scope=tasks.get(task.getString("id"),false).getJSONObject("executionScope");tasks.configureExecutionScope(task.getString("id"),scope.getLong("revision"),scope.getLong("deadlineAt"),scope.getInt("toolLimit")-4,scope.getInt("modelCallLimit")-4);
                SecurityException revoked=new SecurityException("fixture native source revoked");nativePort.sourceFailure.set(revoked);
                assertSame(revoked,assertThrows(SecurityException.class,()->port.afterTool(ScopeAllocationPort.TOOL,new JSONObject(),new JSONObject().put("ok",true),control)));
                android.database.sqlite.SQLiteException unavailable=new android.database.sqlite.SQLiteException("fixture native source database unavailable");nativePort.sourceFailure.set(unavailable);
                assertSame(unavailable,assertThrows(android.database.sqlite.SQLiteException.class,()->port.afterTool(ScopeAllocationPort.TOOL,new JSONObject(),new JSONObject().put("ok",true),control)));
                assertEquals(0,nativePort.executions.get());assertEquals(0,nativePort.callbacks.get());
            }finally{port.close();}
        }
    }

    private static JSONArray catalogPluginDescriptors()throws Exception{
        JSONArray rows=new JSONArray();for(int i=0;i<12;i++)rows.put(new JSONObject().put("name","plugin_catalog_"+i).put("pluginId","catalogue-fixture").put("tool","query_"+i).put("version","1").put("installationId","installed-original").put("effect","read").put("description","Catalog operation "+i).put("schema",catalogSchema("query",new JSONObject().put("type","string"))).put("executionBinding",new JSONObject().put("installationId","installed-original").put("stateRevision",1).put("grantsDigest","a".repeat(64))));return rows;
    }
    /** Existing Host transport fixture; this does not replace actual PluginsModule native acceptance. */
    private static EngineModule catalogPlugins(AtomicReference<JSONArray> registry,AtomicInteger dispatches){return new EngineModule(){
        public String id(){return "plugins";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject args)throws Exception{if(command.equals("registry"))return new JSONObject().put("tools",new JSONArray(registry.get().toString()));dispatches.incrementAndGet();return new JSONObject().put("ok",true);}
    };}
    private static JSONObject definitionsByName(JSONArray tools)throws Exception{JSONObject result=new JSONObject();for(int i=0;i<tools.length();i++){JSONObject row=tools.getJSONObject(i);result.put(row.getJSONObject("function").getString("name"),row);}return result;}
    private static void checkpointDiscovery(TaskStore tasks,JSONObject task,RunControl control,CapabilityPort port,JSONObject args,JSONObject receipt,int steps)throws Exception{
        RunJournal journal=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);JSONArray history=journal.restore().optJSONArray("messages");if(history==null)history=new JSONArray();String call="native-catalog-fixture-"+steps;
        history.put(call(call,"tools_search",args).put("role","assistant")).put(new JSONObject().put("role","tool").put("tool_call_id",call).put("content",receipt.toString()));journal.checkpoint(history,steps,new JSONObject().put("noDeviceObservation",true));assertTrue(journal.restore().getJSONArray("messages").toString().contains(receipt.getString("discoveryId")));port.afterTool("tools_search",args,receipt,control);
    }
    private static void assertCatalogReads(TaskStore tasks,JSONObject task,int count,boolean planning)throws Exception{
        JSONObject stored=tasks.get(task.getString("id"),false);JSONArray operations=stored.getJSONArray("operations");assertEquals(count,operations.length());assertEquals(count,stored.getJSONObject("executionScope").getInt("toolsUsed"));
        for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);assertEquals("tools_search",row.getString("tool"));assertEquals("read_receipt",row.getString("state"));assertTrue(row.getJSONObject("receipt").getBoolean("catalogRead"));assertEquals(planning,row.getJSONObject("receipt").getBoolean("planningRead"));}
    }

    private static String extensionName(JSONObject request)throws Exception {JSONArray tools=request.getJSONArray("tools");for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getJSONObject("function").getString("name");if(name.startsWith("ext_"))return name;}return null;}
    private static JSONObject call(String id,String name,JSONObject arguments)throws Exception{return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",arguments.toString()))));}
    @FunctionalInterface private interface Reply {JSONObject message(JSONObject request,int index)throws Exception;}
    private static final class Server implements AutoCloseable {
        final ServerSocket socket;final Thread worker;final Reply reply;final List<JSONObject> requests=new CopyOnWriteArrayList<>();volatile Exception failure;
        Server(Reply reply)throws Exception {this.reply=reply;socket=new ServerSocket();socket.bind(new InetSocketAddress("127.0.0.1",0));worker=new Thread(this::serve,"extension-fixture-model");worker.setDaemon(true);worker.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+socket.getLocalPort()+"/v1","fixture-secret","fixture-model",false,8,10);}
        private void serve(){while(!socket.isClosed())try(Socket connection=socket.accept()){
            InputStream input=new BufferedInputStream(connection.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
            if(length<0||length>512000)throw new IllegalArgumentException("fixture request limit");JSONObject request=new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8));int index=requests.size();requests.add(request);JSONObject message=reply.message(request,index).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));connection.getOutputStream().write(body);connection.getOutputStream().flush();
        }catch(Exception error){if(!socket.isClosed())failure=error;}}
        @Override public void close()throws Exception {socket.close();worker.join(2000);if(failure!=null)throw failure;}
        private static String line(InputStream input)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int value;(value=input.read())!=-1;){if(value=='\n')return out.toString(StandardCharsets.US_ASCII).replace("\r","");if(out.size()>8192)throw new IllegalArgumentException("fixture header limit");out.write(value);}throw new IllegalStateException("fixture EOF");}
    }
}
