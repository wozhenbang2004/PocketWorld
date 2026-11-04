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
        String id=tasks.createCapabilityTask("inspect the original authorized tool catalogue","").getString("id");JSONObject created=tasks.initializeExecutionScope(id,20);
        if(planning)tasks.enterExecutionPlanning(id,"",0,created.getJSONObject("executionScope").getLong("revision"),()->true);
        return tasks.startAttempt(id,"capabilities",20);
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
                port.beginRun("read-only planning",control);JSONObject prepared=port.prepareTurn("inspect",1,0,control);assertTrue(hasTool(prepared,"workspace_read"));assertFalse(hasTool(prepared,"workspace_write"));assertFalse(hasTool(prepared,"plugin_catalog_0"));assertFalse(prepared.getJSONObject("reference").toString().contains("Catalog operation"));
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
