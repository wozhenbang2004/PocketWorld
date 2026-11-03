package dev.pocketengine.features.controlplane;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectOwnerWorkflows;
import dev.pocketengine.features.skills.SkillSourcePolicy;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.models.ModelTargetRef;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** Real native project choices, SkillBook, TaskStore and the original Application/Runner.
 * Synthetic loopback inference proves reference delivery, not model/provider quality or device UX. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeWorkProfileSkillScopeTest {
    private EngineApplication app;
    private SkillsModule skills;
    private LocalModule local;
    private HttpServer server;
    private ExecutorService worker;
    private JSONObject connection,checkoutA,checkoutB,global,project,ownerOnly;
    private final List<JSONObject> requests=new CopyOnWriteArrayList<>();
    private static final String MARKER="EXACT_PROJECT_PROFILE_REFERENCE";

    @Before public void open()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();skills=(SkillsModule)app.features.module("skills");local=(LocalModule)app.features.module("local");worker=Executors.newSingleThreadExecutor();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            JSONObject message=new JSONObject().put("role","assistant").put("content","Original project Profile response");
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason","stop"))).put("usage",new JSONObject().put("prompt_tokens",1).put("completion_tokens",2).put("total_tokens",3)).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        worker.submit(()->{
            connection=app.settings.saveApiConnection("",0,"Original project Profile",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("provider","openai_chat").put("model","scope-profile-model").put("maxSteps",8).put("sendScreenshots",false));
            app.settings.selectApiConnection(connection.getString("id"),connection.getLong("revision"));
            checkoutA=project("Original A");checkoutB=project("Original B");
            global=install("scope-guide","GLOBAL_REFERENCE_ONLY",null,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,false);
            project=install("scope-guide",MARKER,checkoutA,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,true);
            ownerOnly=install("owner-private-guide","OWNER_ONLY_MUST_NOT_REACH_MODEL",checkoutA,SkillSourcePolicy.Invocation.OWNER_ONLY,false);
            return null;
        }).get(25,TimeUnit.SECONDS);
    }
    @After public void close()throws Exception {
        if(server!=null)server.stop(0);if(worker!=null){worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}if(app!=null){app.onTerminate();app.features.close();}
    }
    private JSONObject project(String name)throws Exception {
        var workspace=app.features.module("workspace");String id=workspace.handle("create",new JSONObject().put("name",name)).getString("id");
        workspace.handle("write",new JSONObject().put("projectId",id).put("path","a.txt").put("content","Original "+name).put("expectedVersion",0));return local.openProjectForOwner(id);
    }
    private JSONObject choice(JSONObject checkout)throws Exception {
        JSONArray rows=skills.projectSourceChoicesForOwner();for(int i=0;i<rows.length();i++)if(checkout.getString("id").equals(rows.getJSONObject(i).optString("checkoutId")))return rows.getJSONObject(i);throw new AssertionError("original project choice missing");
    }
    private JSONObject install(String name,String body,JSONObject checkout,SkillSourcePolicy.Invocation invocation,boolean projectDependency)throws Exception {
        JSONObject input=new JSONObject().put("source","fixture:profile:"+body).put("manifest","---\nname: "+name+"\ndescription: Original native Profile fixture\n---\n"+body);
        if(projectDependency)input.put("requiredCapabilities",new JSONArray().put("project_read_file"));
        JSONObject row=skills.handle("import",input).getJSONObject("skill");
        if(checkout!=null)skills.configureSourceForOwner(new JSONObject().put("ownerId","owner").put("spaceId","private"),row.getString("id"),row.getInt("revision"),row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,invocation,choice(checkout));
        skills.handle("setEnabled",new JSONObject().put("id",row.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",row.getString("digest")));
        return skills.handle("get",new JSONObject().put("id",row.getString("id"))).getJSONObject("skill");
    }
    private JSONObject definition(JSONObject row)throws Exception {
        JSONObject value=WorkProfile.definition("project-skill-fixture",1,"Original project Skill Profile");
        JSONObject model=ModelTargetRef.exact(connection.getString("id"),"scope-profile-model").toJson();value.getJSONObject("modelPolicies").put("MAIN",model).put("SUMMARY",new JSONObject(model.toString()));
        value.getJSONArray("instructionRefs").put(new JSONObject().put("id",row.getString("id")).put("revision",row.getInt("revision")).put("digest",row.getString("digest")).put("optional",false));return value;
    }
    private ProjectOwnerWorkflows.ProfileProjectSelection selectedProject(JSONObject checkout)throws Exception {
        return app.reviewOwnerWorkProfileProject(checkout.getString("projectId"),checkout.getString("id"),"managed_process_v1");
    }
    private NativeWorkProfileSources sources() {
        return new NativeWorkProfileSources(app.settings,app.features,app.pluginHost,()->HostToolSupervisor.configuredSkillCapabilities(app.features,app.tasks),new NativeWorkProfileTargetSources(app.features),null);
    }
    private static boolean contains(JSONArray rows,String id)throws Exception {for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return true;return false;}
    private static JSONArray skillChoices(JSONObject choices)throws Exception{return choices.getJSONObject("sections").getJSONObject("skills").getJSONArray("items");}
    private File checkoutFile(JSONObject checkout,String path)throws Exception{return new File(app.getFilesDir(),"local-development/checkouts/"+checkout.getString("id")+"/"+path);}
    private static void assertHarnessReference(JSONObject payload,JSONObject selected,String body,String excluded)throws Exception {
        assertEquals("pocketworld.harness-reference-assets.v1",payload.getString("format"));assertFalse(payload.getBoolean("permissionsChanged"));assertFalse(payload.getBoolean("scriptsExecutable"));
        JSONArray assets=payload.getJSONArray("assets");assertEquals(1,assets.length());JSONObject asset=assets.getJSONObject(0);
        assertEquals(selected.getString("id"),asset.getString("id"));assertEquals(selected.getInt("revision"),asset.getInt("revision"));assertEquals(selected.getString("digest"),asset.getString("digest"));
        assertTrue(asset.getBoolean("untrustedReference"));assertFalse(asset.getBoolean("permissionsChanged"));assertFalse(asset.getBoolean("scriptsExecutable"));assertEquals(new JSONArray().put("instruction").toString(),asset.getJSONArray("kinds").toString());
        JSONArray files=asset.getJSONArray("files");assertEquals(1,files.length());JSONObject file=files.getJSONObject(0);assertEquals("SKILL.md",file.getString("path"));
        StringBuilder actual=new StringBuilder();JSONArray chunks=file.getJSONArray("chunks");for(int i=0;i<chunks.length();i++)actual.append(chunks.getString(i));
        String expected="---\nname: scope-guide\ndescription: Original native Profile fixture\n---\n"+body;
        assertEquals(expected,actual.toString());assertEquals(dev.pocketengine.features.local.project.ProjectSources.sha256(expected.getBytes(StandardCharsets.UTF_8)),file.getString("sha256"));
        assertFalse(payload.toString().contains(excluded));assertFalse(payload.toString().contains("OWNER_ONLY_MUST_NOT_REACH_MODEL"));
    }

    @Test public void originalProjectPreflightRunnerAndStoredRecoveryKeepScopeWhileNormalSourceEditsRemainUsable()throws Exception {
        JSONObject result=worker.submit(()->{
            var host=app.nativeWorkProfiles();var page=host.newOwnerPage();page.setForeground(true);RunControl original=new RunControl();
            String taskId;JSONObject before;
            try{
                var selectedProject=selectedProject(checkoutA);JSONArray globals=skillChoices(page.choices(new RunControl())),scoped=skillChoices(page.choices(new RunControl(),selectedProject));
                assertTrue(contains(globals,global.getString("id")));assertFalse(contains(globals,project.getString("id")));
                assertTrue(contains(scoped,project.getString("id")));assertFalse(contains(scoped,global.getString("id")));assertFalse(contains(scoped,ownerOnly.getString("id")));
                JSONObject saved=page.save("",0,"Original scoped reference",definition(project));
                assertFalse(page.review(saved.getString("profileId"),1,new RunControl()).view().getBoolean("ready"));
                var review=page.review(saved.getString("profileId"),1,new RunControl(),selectedProject);assertTrue(review.view().toString(),review.view().getBoolean("ready"));
                assertEquals(checkoutA.getString("projectId"),review.view().getJSONObject("skillScope").getString("projectId"));
                var selected=page.select(review,review.digest,original);host.requireProjectSelection(selected,selectedProject,original);
                JSONObject started=app.runOwnerWorkProfile("Return the selected native project reference","",null,null,selected,original,selectedProject);taskId=started.getString("taskId");before=app.tasks.get(taskId,false);
                assertEquals(checkoutA.getString("id"),before.getJSONObject("developmentContext").getString("checkoutId"));
                JSONObject frozen=app.tasks.workProfileRunSnapshot(taskId,before.getString("runId"),before.getInt("coordinatorEpoch"));
                assertEquals(review.view().getString("skillScopeFingerprint"),frozen.getString("skillScopeFingerprint"));assertFalse(frozen.toString().contains(MARKER));
                app.settings.removeWorkProfile(saved.getString("profileId"),1);
            }finally{page.close();}
            assertEquals(1,requests.size());
            Files.write(checkoutFile(checkoutA,"files/a.txt").toPath(),"Normal project editing after the original start".getBytes(StandardCharsets.UTF_8));
            // Reopening the original persisted run uses the namespace occurrence, not its old
            // initialSourceDigest. It preserves the same task, budget and frozen Skill pins.
            var recovery=host.newOwnerPage();recovery.setForeground(true);
            try{
                var review=recovery.reviewForTask(app.tasks,taskId,before.getString("runId"),before.getInt("coordinatorEpoch"),new RunControl());assertTrue(review.view().getBoolean("ready"));
                RunControl next=new RunControl();var selected=recovery.select(review,review.digest,next);JSONObject continued=app.continueOwnerWorkProfile(taskId,null,selected,next);
                JSONObject after=app.tasks.get(taskId,false);assertEquals(taskId,continued.getString("taskId"));assertEquals(1,app.tasks.list().length());assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));
                for(String field:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit"})assertEquals(before.getJSONObject("executionScope").get(field),after.getJSONObject("executionScope").get(field));
                skills.handle("setEnabled",new JSONObject().put("id",project.getString("id")).put("enabled",false));
                assertThrows(Exception.class,()->recovery.reviewForTask(app.tasks,taskId,after.getString("runId"),after.getInt("coordinatorEpoch"),new RunControl()));return continued;
            }finally{recovery.close();}
        }).get(45,TimeUnit.SECONDS);
        assertEquals(result.toString(),2,requests.size());
        for(JSONObject request:requests){boolean seen=false;JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);String content=message.optString("content");assertFalse(content.contains("OWNER_ONLY_MUST_NOT_REACH_MODEL"));if(content.contains(MARKER)){seen=true;assertEquals("user",message.getString("role"));assertTrue(content.contains("<host_reference_data>"));}}assertTrue("Original Runner HTTP must contain the exact selected project instruction",seen);}
    }

    @Test public void anotherProjectCancellationAndCheckoutReplacementCannotReuseAReviewedScope()throws Exception {
        worker.submit(()->{
            var host=app.nativeWorkProfiles();var page=host.newOwnerPage();page.setForeground(true);
            try{
                var projectA=selectedProject(checkoutA);var projectB=selectedProject(checkoutB);JSONObject saved=page.save("",0,"Scoped source",definition(project));
                assertFalse(page.review(saved.getString("profileId"),1,new RunControl(),projectB).view().getBoolean("ready"));
                var review=page.review(saved.getString("profileId"),1,new RunControl(),projectA);RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(Exception.class,()->page.select(review,review.digest,cancelled));
                RunControl control=new RunControl();var selected=page.select(review,review.digest,control);assertThrows(SecurityException.class,()->page.select(review,review.digest,control));
                assertThrows(SecurityException.class,()->host.requireProjectSelection(selected,projectB,control));assertThrows(SecurityException.class,()->host.requireProjectSelection(selected,null,control));
                assertEquals(0,app.tasks.list().length());
                File record=checkoutFile(checkoutA,"checkout.json");JSONObject occurrence=JsonFiles.read(record);occurrence.put("createdAt",Math.addExact(occurrence.getLong("createdAt"),1));JsonFiles.write(record,occurrence);
                assertThrows(SecurityException.class,()->selected.checkCurrent(control));
                assertNotEquals(selected.snapshot().getString("skillScopeFingerprint"),skillChoices(page.choices(new RunControl(),projectA)).getJSONObject(0).getString("skillScopeFingerprint"));
                assertEquals(0,app.tasks.list().length());return null;
            }finally{page.close();}
        }).get(25,TimeUnit.SECONDS);assertEquals(0,requests.size());
    }

    @Test public void oldUnscopedSqlSnapshotKeepsItsGlobalReferenceInsideAnOriginalProjectTask()throws Exception {
        JSONObject continuedResult=worker.submit(()->{
            NativeWorkProfileSources current=sources();
            // Simulate the earlier native adapter's persisted shape through original typed
            // admission, rather than constructing a new authority from a JSON snapshot.
            WorkProfileResolver.Sources legacy=new WorkProfileResolver.Sources(){
                public ModelConfig model(ModelTargetRef ref,String purpose,RunControl control)throws Exception{return current.model(ref,purpose,control);}
                public void checkModel(ModelConfig model,String purpose,RunControl control)throws Exception{current.checkModel(model,purpose,control);}
                public ModelConfig globalDefault(RunControl control)throws Exception{return current.globalDefault(control);}
                public JSONObject skill(String id,RunControl control)throws Exception{return current.legacyGlobalSkill(id,control);}
                public JSONObject plugin(String id,RunControl control)throws Exception{return current.plugin(id,control);}
                public JSONObject extension(String id,RunControl control)throws Exception{return current.extension(id,control);}
                public JSONObject team(String id,RunControl control)throws Exception{return current.team(id,control);}
                public JSONObject target(JSONObject ref,RunControl control)throws Exception{return current.target(ref,control);}
                public Set<String> hostCapabilities()throws Exception{return current.hostCapabilities();}
            };
            var oldHost=new NativeWorkProfileHost(app.settings,legacy);var page=oldHost.newOwnerPage();page.setForeground(true);JSONObject task;
            try{
                JSONObject saved=page.save("",0,"Original legacy reference",definition(global));var review=page.review(saved.getString("profileId"),1,new RunControl());assertFalse(review.view().has("skillScope"));
                RunControl control=new RunControl();var selected=page.select(review,review.digest,control);JSONObject pending=app.tasks.createCapabilityTaskWithWorkProfile("Continue the existing global reference","",selected,control);
                app.tasks.configureExecutionScope(pending.getString("id"),0,System.currentTimeMillis()+300000,8,4);task=app.tasks.startAttemptWithWorkProfile(pending.getString("id"),"capabilities",8,selected,control);
                var ownerField=EngineApplication.class.getDeclaredField("projectOwnerWorkflows");ownerField.setAccessible(true);((ProjectOwnerWorkflows)ownerField.get(app)).bindWorkProfileProject(selectedProject(checkoutA),task,control);
                // This manually retained old run must contain its original read-only grant.
                // A later continuation may intersect it, never fill an empty frozen tool set.
                app.tasks.authorizeTools(task.getString("id"),task.getString("runId"),new JSONArray().put("project_inspect"));
                var references=app.nativeWorkProfiles().harnessReferencesForRun(app.tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);
                assertNotNull(references);references.checkCurrent();assertHarnessReference(references.view(),global,"GLOBAL_REFERENCE_ONLY",MARKER);
                assertEquals(new JSONArray().put("project_inspect").toString(),app.tasks.get(task.getString("id"),false).getJSONObject("executionScope").getJSONArray("tools").toString());
                app.tasks.endAttempt(task.getString("id"),task.getString("runId"),"awaiting_user","Original read-only fixture stopped before inference",new JSONObject());
            }finally{page.close();}
            TaskStore reopened=new TaskStore(app);try{
                var restoredHost=new NativeWorkProfileHost(app.settings,sources());var restored=restoredHost.newOwnerPage();restored.setForeground(true);
                try{
                    var review=restored.reviewForTask(reopened,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl());JSONObject view=review.view();assertTrue(view.toString(),view.getBoolean("ready"));assertFalse(view.has("skillScope"));
                    JSONArray items=view.getJSONArray("items");for(int i=0;i<items.length();i++)if("instruction".equals(items.getJSONObject(i).getString("kind"))){JSONObject binding=items.getJSONObject(i).getJSONObject("binding");assertEquals(global.getString("id"),binding.getString("id"));assertFalse(binding.has("skillScopeFingerprint"));}
                    assertEquals(project.getString("id"),skills.catalogueForHost(skills.taskScopeForHost(reopened.get(task.getString("id"),false))).getJSONArray("skills").getJSONObject(0).getString("id"));
                    assertEquals(1,reopened.list().length());assertEquals(0,reopened.get(task.getString("id"),false).getJSONArray("operations").length());
                }finally{restored.close();}
            }finally{var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((android.database.sqlite.SQLiteDatabase)database.get(reopened)).close();}
            var originalPage=app.nativeWorkProfiles().newOwnerPage();originalPage.setForeground(true);
            try{
                JSONObject originalScope=app.tasks.get(task.getString("id"),false).getJSONObject("executionScope");
                var review=originalPage.reviewForTask(app.tasks,task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),new RunControl());RunControl next=new RunControl();var selected=originalPage.select(review,review.digest,next);
                JSONObject result=app.continueOwnerWorkProfile(task.getString("id"),null,selected,next);assertEquals(task.getString("id"),result.getString("taskId"));assertEquals(1,app.tasks.list().length());
                JSONObject continued=app.tasks.get(task.getString("id"),false),scope=continued.getJSONObject("executionScope");assertEquals(task.getInt("coordinatorEpoch")+1,continued.getInt("coordinatorEpoch"));
                for(String field:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit"})assertEquals(originalScope.get(field),scope.get(field));
                assertEquals(new JSONArray().put("project_inspect").toString(),scope.getJSONArray("tools").toString());assertEquals(originalScope.getJSONArray("tools").toString(),scope.getJSONArray("tools").toString());assertEquals(0,continued.getJSONArray("operations").length());return result;
            }finally{originalPage.close();}
        }).get(40,TimeUnit.SECONDS);assertEquals(continuedResult.toString(),1,requests.size());boolean delivered=false;
        JSONArray messages=requests.get(0).getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);String content=message.optString("content");assertFalse("Old global pin must not gain the shadowing project source",content.contains(MARKER));if(content.contains("GLOBAL_REFERENCE_ONLY")){delivered=true;assertEquals("user",message.getString("role"));assertTrue(content.contains("<host_reference_data>"));}}assertTrue("Legacy cold continuation must read its original global body through the actual Runner",delivered);
    }

    @Test public void projectHarnessReferencesKeepExactScopedBytesAndOriginalWithdrawalFences()throws Exception {
        worker.submit(()->{
            var host=app.nativeWorkProfiles();var ownerField=EngineApplication.class.getDeclaredField("projectOwnerWorkflows");ownerField.setAccessible(true);var projects=(ProjectOwnerWorkflows)ownerField.get(app);
            for(String withdrawal:new String[]{"cancel","checkout","disable"}){
                int priorTasks=app.tasks.list().length();var page=host.newOwnerPage();page.setForeground(true);RunControl original=new RunControl();JSONObject task=null;
                try{
                    var projectSelection=selectedProject(checkoutA);JSONObject saved=page.save("",0,"Original Harness "+withdrawal,definition(project));
                    var review=page.review(saved.getString("profileId"),1,new RunControl(),projectSelection);assertTrue(review.view().toString(),review.view().getBoolean("ready"));
                    var selected=page.select(review,review.digest,original);host.requireProjectSelection(selected,projectSelection,original);
                    JSONObject pending=app.tasks.createCapabilityTaskWithWorkProfile("Read the original project reference without execution","",selected,original);
                    app.tasks.configureExecutionScope(pending.getString("id"),0,System.currentTimeMillis()+300000,8,4);task=app.tasks.startAttemptWithWorkProfile(pending.getString("id"),"capabilities",8,selected,original);
                    projects.bindWorkProfileProject(projectSelection,task,original);host.bindOriginalTaskScope(app.tasks,task.getString("id"),selected,original);
                    JSONArray readTools=new JSONArray().put("project_inspect").put("project_read_file");app.tasks.authorizeTools(task.getString("id"),task.getString("runId"),readTools);
                    final String id=task.getString("id"),run=task.getString("runId");final int epoch=task.getInt("coordinatorEpoch");
                    JSONObject before=app.tasks.get(id,false),scope=before.getJSONObject("executionScope");
                    var references=host.harnessReferencesForRun(app.tasks,id,run,epoch,original);assertNotNull(references);references.checkCurrent();
                    assertHarnessReference(references.view(),project,MARKER,"GLOBAL_REFERENCE_ONLY");assertEquals(review.view().getString("resolutionDigest"),references.view().getString("resolutionDigest"));
                    assertEquals(priorTasks+1,app.tasks.list().length());assertEquals(scope.toString(),app.tasks.get(id,false).getJSONObject("executionScope").toString());assertEquals(0,app.tasks.get(id,false).getJSONArray("operations").length());
                    if("cancel".equals(withdrawal))original.cancel();
                    else if("checkout".equals(withdrawal)){File record=checkoutFile(checkoutA,"checkout.json");JSONObject occurrence=JsonFiles.read(record);occurrence.put("createdAt",Math.addExact(occurrence.getLong("createdAt"),1));JsonFiles.write(record,occurrence);}
                    else skills.handle("setEnabled",new JSONObject().put("id",project.getString("id")).put("enabled",false));
                    if("cancel".equals(withdrawal))assertThrows(InterruptedException.class,references::checkCurrent);else assertThrows(SecurityException.class,references::checkCurrent);
                    assertThrows(Exception.class,()->host.harnessReferencesForRun(app.tasks,id,run,epoch,original));
                    JSONObject after=app.tasks.get(id,false);assertEquals(run,after.getString("runId"));assertEquals(epoch,after.getInt("coordinatorEpoch"));assertEquals(priorTasks+1,app.tasks.list().length());assertEquals(0,after.getJSONArray("operations").length());
                    assertEquals(scope.toString(),after.getJSONObject("executionScope").toString());assertEquals(readTools.toString(),after.getJSONObject("executionScope").getJSONArray("tools").toString());assertEquals(0,requests.size());
                }finally{
                    if(task!=null)app.tasks.endAttempt(task.getString("id"),task.getString("runId"),original.isCancelled()?"cancelled":"awaiting_user","Original reference-only fixture ended without dispatch",new JSONObject());
                    original.cancel();page.close();
                }
            }return null;
        }).get(40,TimeUnit.SECONDS);assertEquals(3,app.tasks.list().length());assertEquals(0,requests.size());
    }
}
