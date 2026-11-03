package dev.pocketengine.features.skills;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.*;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Original SkillBook -> native per-operation approval -> original TaskStore ->
 * actual managed JNI/Python/sh -> original receipt/log/file cache. Own scripts,
 * explicit host tools, fresh app roots: NOT Android/strong sandbox/provider proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ManagedSkillNativeJourneyTest {
    private static final class Install extends ContextWrapper {
        final File files,cache,databases;final String preferences;
        Install(Context app,Path root)throws Exception{super(app);files=Files.createDirectories(root.resolve("files")).toFile();cache=Files.createDirectories(root.resolve("cache")).toFile();databases=Files.createDirectories(root.resolve("databases")).toFile();preferences="managed-skill-own-"+UUID.randomUUID()+"-";}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return files;}
        @Override public File getCacheDir(){return cache;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(preferences+name,mode);}
        @Override public File getDatabasePath(String name){if(name.contains("/"))throw new IllegalArgumentException("own fixture DB");return new File(databases,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
    }
    static final class Fixture implements AutoCloseable {
        final Path temporary=Files.createTempDirectory("c2-native-skill-");final Install android=new Install(org.robolectric.RuntimeEnvironment.getApplication(),temporary.resolve("install"));
        final dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package pack;final Map<String,RuntimeTool> tools=new LinkedHashMap<>();
        final EngineModule remote=new EngineModule(){public String id(){return "remote";}public JSONObject snapshot()throws Exception{return new JSONObject().put("profiles",new JSONArray());}public JSONObject handle(String name,JSONObject input){throw new AssertionError("Phone Skill must never call Companion: "+name);}};
        final AtomicReference<Exception> callbackFailure=new AtomicReference<>();final java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();final RunControl control=new RunControl();
        ManagedProjectRuntime runtime;WorkspaceModule workspace;LocalModule local;TaskStore tasks;SkillsModule skills;NativeSkillExecutionHost host;NativeSkillExecutionHost.OwnerWindow window;
        dev.pocketengine.features.controlplane.WorkProfileToolSelection profilePolicy;
        dev.pocketengine.features.controlplane.WorkProfileToolSelection.PreparedTool preparedProfile;
        final String project,checkout;final JSONObject task,installed,input;CompletableFuture<JSONObject> work;final ManagedScriptCallbackTransport.Factory callbackTransport;
        Fixture(String interpreter,String script,String... arguments)throws Exception {
            this(interpreter,script,Map.of(),false,arguments);
        }
        Fixture(String interpreter,String script,boolean originalProjectScope,String... arguments)throws Exception {
            this(interpreter,script,Map.of(),false,false,null,originalProjectScope,arguments);
        }
        Fixture(String interpreter,String script,boolean originalProjectScope,boolean legacyGlobalProfile,String... arguments)throws Exception {
            this(interpreter,script,Map.of(),false,false,null,originalProjectScope,legacyGlobalProfile,arguments);
        }
        Fixture(String interpreter,String script,Map<String,String> extraResources,boolean prepareDependencies,String... arguments)throws Exception {
            this(interpreter,script,extraResources,prepareDependencies,false,null,arguments);
        }
        Fixture(String interpreter,String script,Map<String,String> extraResources,boolean prepareDependencies,boolean immediateArtifacts,ManagedScriptCallbackTransport.Factory callbackTransport,String... arguments)throws Exception {
            this(interpreter,script,extraResources,prepareDependencies,immediateArtifacts,callbackTransport,false,arguments);
        }
        Fixture(String interpreter,String script,Map<String,String> extraResources,boolean prepareDependencies,boolean immediateArtifacts,ManagedScriptCallbackTransport.Factory callbackTransport,boolean originalProjectScope,String... arguments)throws Exception {
            this(interpreter,script,extraResources,prepareDependencies,immediateArtifacts,callbackTransport,originalProjectScope,false,arguments);
        }
        Fixture(String interpreter,String script,Map<String,String> extraResources,boolean prepareDependencies,boolean immediateArtifacts,ManagedScriptCallbackTransport.Factory callbackTransport,boolean originalProjectScope,boolean legacyGlobalProfile,String... arguments)throws Exception {
            this.callbackTransport=callbackTransport;
            byte[] marker="self-owned Skill fixture environment; host interpreter is not redistributed\n".getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("share/fixture.txt"));zip.write(marker);zip.closeEntry();}byte[] archive=bytes.toByteArray();
            pack=new dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package(new JSONObject().put("id","c2-managed-skill-host-fixture").put("revision","1").put("license","CC0-1.0 own marker; host tools not redistributed").put("abi","linux-x64-host-only").put("archiveSha256",ProjectSources.sha256(archive)).put("archiveBytes",archive.length).put("files",new JSONArray().put(new JSONObject().put("path","share/fixture.txt").put("bytes",marker.length).put("sha256",ProjectSources.sha256(marker)))));
            File python=Path.of(Objects.requireNonNull(System.getProperty("a25.python"),"exact actual host Python required")).toRealPath().toFile();tools.put("python3",tool("python3",python,List.of("--version")));tools.put("sh",tool("sh",new File("/bin/sh"),List.of("-c","printf own-shell-probe")));
            runtime=ManagedProjectRuntime.create(temporary.resolve("runtime").toFile(),pack,tools);runtime.environmentManager().install(pack,new ByteArrayInputStream(archive),()->{},(phase,done,total)->{});
            FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,runtime);tasks=new TaskStore(android);skills=new SkillsModule(context);
            project=workspace.handle("create",new JSONObject().put("name","Self-owned Skill source")).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","input/data.txt").put("expectedVersion",0).put("content","40.00"));checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
            String path=interpreter.equals("sh")?"scripts/main.sh":"scripts/main.py";
            installed=skills.handle("import",new JSONObject().put("source","own:managed-phone-native-fixture").put("manifest","---\nname: own-phone-skill\ndescription: 原手机托管脚本与原任务成果\n---\n只用于明确的自有验证脚本。\n").put("resources",new JSONObject(extraResources).put(path,script))).getJSONObject("skill");skills.handle("setEnabled",new JSONObject().put("id",installed.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",installed.getString("digest")));
            dev.pocketengine.features.controlplane.NativeWorkProfileHost.Selection profile=null;
            if(legacyGlobalProfile){
                var settings=new dev.pocketengine.store.SettingsStore(android);
                dev.pocketengine.features.controlplane.WorkProfileResolver.Sources legacy=new dev.pocketengine.features.controlplane.WorkProfileResolver.Sources(){
                    public dev.pocketengine.core.ModelConfig model(dev.pocketengine.runtime.models.ModelTargetRef ref,String purpose,RunControl observing){throw new AssertionError("This actual backend journey makes no model requests");}
                    public void checkModel(dev.pocketengine.core.ModelConfig model,String purpose,RunControl observing){throw new AssertionError("No fixture model success");}
                    public JSONObject skill(String id,RunControl observing)throws Exception{observing.check();JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");JSONArray rows=skills.catalogueForHost(scope).getJSONArray("skills");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id"))){JSONObject row=rows.getJSONObject(i);row.remove("skillScopeFingerprint");return row;}return null;}
                    public JSONObject plugin(String id,RunControl observing){return null;}public JSONObject extension(String id,RunControl observing){return null;}public JSONObject team(String id,RunControl observing){return null;}public JSONObject target(JSONObject ref,RunControl observing){return null;}
                    public Set<String> hostCapabilities(){return Set.of("skills_execute");}
                };
                var profileHost=new dev.pocketengine.features.controlplane.NativeWorkProfileHost(settings,legacy);var page=profileHost.newOwnerPage();page.setForeground(true);
                try{JSONObject definition=dev.pocketengine.features.controlplane.WorkProfile.definition("legacy-managed-skill",1,"Original exact global script");definition.getJSONArray("skillRefs").put(new JSONObject().put("id",installed.getString("id")).put("revision",installed.getInt("revision")).put("digest",installed.getString("digest")).put("optional",false));JSONObject saved=page.save("",0,"Original global script",definition);var review=page.review(saved.getString("profileId"),1,new RunControl());assertTrue(review.view().getBoolean("ready"));assertFalse(review.view().has("skillScope"));profile=page.select(review,review.digest,control);}finally{page.close();}
                JSONObject pending=tasks.createCapabilityTaskWithWorkProfile("原项目任务继续既有全局Skill脚本", "",profile,control);tasks.configureExecutionScope(pending.getString("id"),0,System.currentTimeMillis()+300000,30,4);task=tasks.startAttemptWithWorkProfile(pending.getString("id"),"capabilities",30,profile,control);
            }else task=tasks.startAttempt(tasks.createCapabilityTask("在手机原托管环境运行明确的自有Skill，生成并核对45.00结果","").getString("id"),"capabilities",30);
            if(!control.hasHostRun())control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
            if(originalProjectScope){
                var memory=new dev.pocketengine.features.memory.MemoryModule(context);skills.mountProjectNamespaces(memory.projectNamespacesForOwner(local,workspace));
                String sourceDigest=ProjectSources.describe(local.managedSkillSourceForHost(checkout,control)).getString("sourceDigest");
                tasks.bindDevelopmentContext(id(),task.getString("runId"),task.getInt("coordinatorEpoch"),control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","managed_process_v1").put("initialSourceDigest",sourceDigest).put("clientMessageId",UUID.randomUUID().toString()));
            }
            if(profile!=null){var selectedProfile=profile;String digest=profile.snapshot().getString("resolutionDigest"),id=id(),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");profilePolicy=new dev.pocketengine.features.controlplane.WorkProfileToolSelection(selectedProfile,id,run,epoch,control,()->tasks.checkWorkProfileRun(id,run,epoch,digest,control),()->tasks.checkWorkProfileCapabilityBinding(id,run,epoch,digest,control),(tool,operation,args,supplied)->tasks.checkWorkProfilePreparedTool(id,run,epoch,digest,tool,operation,args,supplied));}
            tasks.authorizeTools(task.getString("id"),task.getString("runId"),new JSONArray().put("skills_execute"));connectHost();
            window=host.openOwnerWindow();window.setForeground(true);JSONObject configuration=configuration(interpreter).put("preparePinnedDependencies",prepareDependencies);if(immediateArtifacts)configuration.put("immediatePrivateArtifacts",true);var reviewed=host.inspectPhoneConfiguration(task.getString("id"),configuration);host.installConfiguration(window,reviewed,reviewed.digest);
            input=new JSONObject().put("id",installed.getString("id")).put("revision",installed.getInt("revision")).put("digest",installed.getString("digest")).put("scriptPath",path).put("arguments",new JSONArray(arguments)).put("timeoutSeconds",20).put("maxOutputBytes",65536);String operation=tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control).beginOperation("skills_execute",input);input.put("_hostOperationId",operation);
        }
        static RuntimeTool tool(String name,File file,List<String> probe)throws Exception{file=file.getCanonicalFile();return new RuntimeTool(name,file,ProjectSources.sha256(Files.readAllBytes(file.toPath())),"explicit-host-skill-fixture","host installed binary, not redistributed",List.of(),probe);}
        JSONObject configuration(String runtime)throws Exception{return new JSONObject().put("format",ManagedSkillPolicy.FORMAT).put("runtimeId",runtime).put("checkoutId",checkout).put("workingDirectory",".").put("readPaths",new JSONArray().put("input/data.txt")).put("writePaths",new JSONArray().put("output/")).put("outputPaths",new JSONArray().put("output/result.bin")).put("timeoutSeconds",30).put("maxOutputBytes",65536).put("maxResultChars",12000).put("preparePinnedDependencies",false);}
        void connectHost()throws Exception {
            var projectHost=new TaskStoreProjectHost(tasks,local,id->control,event->{});local.setProjectHost(projectHost);local.setProjectReceiptSink(actual->{try{projectHost.recordBackendReceipt(actual);callbacks.incrementAndGet();}catch(Exception failed){callbackFailure.compareAndSet(null,failed);}});
            NativeSkillExecutionHost.LiveRun live=(id,run,epoch,original)->{if(original!=control||!original.matchesHostRun(id,run,epoch))throw new SecurityException("not original Skill control");original.check();};
            host=callbackTransport==null?new NativeSkillExecutionHost(android,skills,remote,tasks,live,id->{},local):new NativeSkillExecutionHost(android,skills,remote,tasks,live,id->{},local,callbackTransport);
        }
        String id()throws Exception{return task.getString("id");}String operation()throws Exception{return input.getString("_hostOperationId");}
        CompletableFuture<JSONObject> execute(){work=CompletableFuture.supplyAsync(()->{try{if(profilePolicy!=null){preparedProfile=profilePolicy.forTool("skills_execute",input,control);return host.execute(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),input,Set.of("skills_execute"),control,preparedProfile::checkSkillExecutionCurrent,preparedProfile);}return host.execute(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),input,Set.of("skills_execute"),control);}catch(Exception failed){throw new CompletionException(failed);}});return work;}
        NativeSkillExecutionHost.Review review()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<until){var pending=host.pendingReview(id());if(pending!=null)return pending;if(work!=null&&work.isDone())throw new AssertionError("Native review missing; early safe result="+work.get().toString());Thread.sleep(20);}throw new AssertionError("Exact original Skill review did not open");}
        JSONObject record()throws Exception{return tasks.readSkillExecution(id(),operation());}
        void approve(NativeSkillExecutionHost.Review review)throws Exception{assertEquals(review.plan.digest,host.validateReview(review));host.decide(window,review,review.plan.digest,true);}
        Path copy()throws Exception{return android.getFilesDir().toPath().resolve("local-development/skill-execution-copies/"+operation());}
        String originalInput()throws Exception{return new String(Files.readAllBytes(android.getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files/input/data.txt")),StandardCharsets.UTF_8);}
        byte[] saved()throws Exception{JSONObject file=record().getJSONObject("receipt").getJSONArray("artifacts").getJSONObject(0);return tasks.readSkillArtifact(id(),operation(),file.getString("artifactId"),file.getString("sha256"));}
        void reopenForReceiptOnly()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(callbacks.get()==0&&callbackFailure.get()==null&&System.nanoTime()<until)Thread.sleep(10);assertTrue("Original real callback did not finish before this explicit cold checkpoint",callbacks.get()>0);assertNull(callbackFailure.get());window.close();host.close();control.cancel();local.close();closeDb(tasks);runtime=ManagedProjectRuntime.create(temporary.resolve("runtime").toFile(),pack,tools);FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,runtime);tasks=new TaskStore(android);skills=new SkillsModule(context);connectHost();}
        static void closeDb(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
        @Override public void close()throws Exception{try{if(window!=null)window.close();if(host!=null)host.close();control.cancel();if(work!=null&&!work.isDone())try{work.get(6,TimeUnit.SECONDS);}catch(Exception pending){}if(local!=null)local.close();}finally{if(tasks!=null)closeDb(tasks);/* Keep this fixture's temp evidence for the scoped test workspace lifecycle. No unknown process tree deletion. */}}
    }

    @Test public void completedPhoneSkillCopyCleanupRetainsExactOriginalInputLogsAndFixedArtifact()throws Exception {
        try(Fixture f=new Fixture("python3",PY,"cache-cleanup")){
            f.local.bindNativeExecutionCachesForHost(f.tasks);
            var work=f.execute();f.approve(f.review());JSONObject completed=work.get(12,TimeUnit.SECONDS);assertTrue(completed.toString(),completed.getBoolean("ok"));
            byte[] fixed=f.saved();assertTrue(new String(fixed,StandardCharsets.UTF_8).contains("45.00"));JSONObject originalLog=f.host.readExecutionLog(f.id(),f.operation(),0,16000);assertTrue(originalLog.getJSONArray("chunks").length()>0);
            String selected="native_cache:skill_copy:"+f.operation();JSONObject inventory=f.local.ownerStorageInventoryForHost(new RunControl());JSONObject target=null;
            for(int i=0;i<inventory.getJSONArray("groups").length();i++){JSONObject row=inventory.getJSONArray("groups").getJSONObject(i);if(selected.equals(row.getString("id")))target=row;}
            assertNotNull(target);assertTrue(target.toString(),target.getBoolean("cleanable"));
            var review=f.local.reviewOwnerStorageForHost(Set.of(selected),new RunControl());var confirmed=review.confirm();String cleanup=confirmed.actionId();
            // Existing FeatureHost owns the one native external intent/worker, never a new test executor.
            try(var features=new FeatureHost(event->{},f.tasks,()->false)){
                features.register(f.workspace);features.register(f.local);features.submitOwnerStorageCleanupForOwner(confirmed);
                long until=System.nanoTime()+8_000_000_000L;JSONObject observed=null;
                while(System.nanoTime()<until){observed=features.ownerStorageCleanupForOwner(cleanup);if(observed.optString("status").matches("completed|reconciling|failed"))break;Thread.sleep(20);}
                assertNotNull(observed);assertEquals(observed.toString(),"completed",observed.getString("status"));assertFalse(Files.exists(f.copy()));
                assertArrayEquals(fixed,f.saved());assertEquals("40.00",f.originalInput());assertFalse(f.tasks.hasUnresolved(cleanup));JSONObject retainedLog=f.host.readExecutionLog(f.id(),f.operation(),0,16000);assertEquals(originalLog.getJSONArray("chunks").toString(),retainedLog.getJSONArray("chunks").toString());assertEquals(originalLog.getLong("nextCursor"),retainedLog.getLong("nextCursor"));
                JSONObject receiptOnly=f.host.reconcile(f.id(),f.operation(),false);assertEquals("completed",receiptOnly.getJSONObject("receipt").getString("status"));assertArrayEquals(fixed,f.saved());
                JSONObject cold=features.queryOwnerStorageCleanupForOwner(cleanup,new RunControl());assertTrue(cold.getBoolean("allTargetsCurrentlyAbsent"));assertFalse(cold.getBoolean("deletionAttributionProven"));
                assertThrows(Exception.class,()->features.submitOwnerStorageCleanupForOwner(confirmed));assertNull(f.callbackFailure.get());
            }
        }
    }

    @Test public void damagedConfigurationKeepsTheRecoveryEntryVisibleEvenWithNoMatchingSkillOrExecution()throws Exception {
        try(Fixture f=new Fixture("python3",PY,"not-executed")){
            f.skills.handle("delete",new JSONObject().put("id",f.installed.getString("id")));byte[] damaged="{optional-config-broken".getBytes(StandardCharsets.UTF_8);Path file=f.android.getFilesDir().toPath().resolve("skill-execution.json");Files.write(file,damaged);
            JSONObject state=f.host.taskSnapshot(f.id(),Set.of("skills_execute"),new JSONObject());assertEquals(0,state.getJSONArray("skills").length());assertEquals(0,state.getJSONArray("executions").length());assertTrue(state.getBoolean("scriptPreparationRelevant"));assertTrue(state.getJSONObject("configuration").getBoolean("recoveryRequired"));assertFalse(state.getBoolean("executionAuthorized"));assertArrayEquals(damaged,Files.readAllBytes(file));assertFalse(Files.exists(f.copy()));assertEquals(0,f.callbacks.get());
        }
    }
    @Test public void damagedOptionalConfigurationRecoversOnlyThroughOwnerReviewAndANewExactScriptApproval()throws Exception {
        try(Fixture f=new Fixture("python3",PY,"after-owner-repair")){
            JSONObject oldConfig=f.host.configuration();var blocked=f.execute();var oldReview=f.review();String oldOperation=f.operation();Path file=f.android.getFilesDir().toPath().resolve("skill-execution.json");
            byte[] damaged="{\"revision\":1,\"enabled\":true,BROKEN-PRIVATE-CONFIG".getBytes(StandardCharsets.UTF_8);Files.write(file,damaged);
            JSONObject visible=f.host.taskSnapshot(f.id(),Set.of("skills_execute"),new JSONObject());assertTrue(visible.getJSONObject("configuration").getBoolean("recoveryRequired"));assertFalse(visible.getJSONObject("configuration").getBoolean("configured"));assertFalse(visible.getJSONObject("configuration").toString().contains("BROKEN-PRIVATE"));
            assertThrows(Exception.class,()->f.host.validateReview(oldReview));assertEquals("rejected",blocked.get(5,TimeUnit.SECONDS).getString("status"));assertFalse(f.tasks.readSkillExecution(f.id(),oldOperation).has("nativeManagedJob"));assertFalse(Files.exists(f.copy()));assertEquals(0,f.callbacks.get());
            var repair=f.host.inspectPhoneConfiguration(f.id(),f.configuration("python3"));assertTrue(repair.view().getJSONObject("configurationRecovery").getBoolean("required"));
            Path retained=file.getParent().resolve("skill-execution.json.recovery/"+SkillExecution.sha256(damaged)+".bin");
            f.window.setForeground(false);assertThrows(SecurityException.class,()->f.host.installConfiguration(f.window,repair,repair.digest));assertArrayEquals(damaged,Files.readAllBytes(file));assertFalse(Files.exists(retained));
            f.window.setForeground(true);f.host.installConfiguration(f.window,repair,repair.digest);assertArrayEquals(damaged,Files.readAllBytes(retained));assertTrue(f.host.configuration().getBoolean("configured"));assertThrows(Exception.class,()->f.host.decide(f.window,oldReview,oldReview.plan.digest,true));assertEquals(0,f.callbacks.get());
            assertEquals(oldConfig.getLong("revision"),f.host.configuration().getLong("revision"));assertThrows(SecurityException.class,()->f.host.removeConfiguration(f.window,oldConfig.getLong("revision"),oldConfig.getString("stateFingerprint")));assertThrows(SecurityException.class,()->f.host.removeConfiguration(f.window,oldConfig.getLong("revision")));assertTrue(f.host.configuration().getBoolean("configured"));
            // The old execution remains rejected. A fresh original operation and a fresh
            // per-script review are required before the same managed runtime may start once.
            f.input.remove("_hostOperationId");String fresh=f.tasks.journal(f.id(),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).beginOperation("skills_execute",f.input);f.input.put("_hostOperationId",fresh);assertNotEquals(oldOperation,fresh);
            var next=f.execute();var exact=f.review();assertFalse(Files.exists(f.copy()));assertNotEquals(oldReview.plan.digest,exact.plan.digest);f.approve(exact);
            JSONObject result=next.get(15,TimeUnit.SECONDS);assertEquals(result.toString(),"completed",result.getString("status"));assertTrue(result.getBoolean("ok"));assertEquals("available",result.getString("artifactStatus"));assertTrue(new String(f.saved(),StandardCharsets.UTF_8).contains("45.00|after-owner-repair"));assertEquals("40.00",f.originalInput());assertEquals(2,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertEquals("rejected",f.tasks.readSkillExecution(f.id(),oldOperation).getJSONObject("receipt").getString("status"));assertNull(f.callbackFailure.get());
            JSONObject current=f.host.configuration();f.host.removeConfiguration(f.window,current.getLong("revision"),current.getString("stateFingerprint"));assertFalse(f.host.configuration().getBoolean("configured"));assertTrue(new String(f.saved(),StandardCharsets.UTF_8).contains("45.00|after-owner-repair"));
        }
    }

    private static final String PY="from pathlib import Path\nimport sys\nassert sys.stdin.read() == ''\np=Path('input/data.txt').read_text()\nPath('output').mkdir(exist_ok=True)\nPath('output/result.bin').write_bytes(b'\\x00\\xff' + p.replace('40.00','45.00').encode() + b'|' + sys.argv[1].encode())\nprint('OWN_SKILL:45.00:stdin-eof')\nprint('字节日志😀'*5000, flush=True)\nimport time\ntime.sleep(.5)\n";
    @Test public void pythonSkillUsesExactNativeApprovalOneOriginalJobAndColdResultsNeverReplay()throws Exception {
        try(Fixture f=new Fixture("python3",PY,true,true,"$(literal no shell) 中文")){
            JSONObject shadow=f.skills.handle("import",new JSONObject().put("source","own:project-shadow-fixture").put("manifest","---\nname: own-phone-skill\ndescription: Different project script; never the legacy selection\n---\nSHADOW_MUST_NOT_EXECUTE\n").put("resources",new JSONObject().put("scripts/main.py","raise RuntimeError('SHADOW_MUST_NOT_EXECUTE')\n"))).getJSONObject("skill");
            JSONObject choice=f.skills.projectSourceChoicesForOwner().getJSONObject(0);
            f.skills.configureSourceForOwner(new JSONObject().put("ownerId","owner").put("spaceId","private"),shadow.getString("id"),shadow.getInt("revision"),shadow.getString("digest"),shadow.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,choice);
            f.skills.handle("setEnabled",new JSONObject().put("id",shadow.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",shadow.getString("digest")));
            assertEquals(shadow.getString("id"),f.skills.catalogueForHost(f.skills.taskScopeForHost(f.tasks.get(f.id(),false))).getJSONArray("skills").getJSONObject(0).getString("id"));
            var work=f.execute();var review=f.review();assertFalse(Files.exists(f.copy()));JSONObject view=review.plan.review();assertTrue(SkillExecution.isManagedPhonePlan(review.plan));assertEquals("this_phone",view.getString("executionLocation"));assertEquals("possible_same_uid",view.getJSONObject("request").getString("network"));assertFalse(view.getBoolean("packageReadOnly"));assertFalse(view.getJSONObject("backend").getJSONObject("nativeExecutionContract").getBoolean("osSandbox"));assertEquals(3,view.getJSONObject("backend").getJSONArray("fences").length());
            assertFalse(view.getJSONObject("run").has("projectId"));assertEquals(f.checkout,f.tasks.get(f.id(),false).getJSONObject("developmentContext").getString("checkoutId"));
            assertEquals(f.installed.getString("id"),view.getJSONObject("package").getString("id"));String originalScript=f.host.readScript(review).getString("content");assertTrue(originalScript.contains("OWN_SKILL:45.00:stdin-eof"));assertFalse(originalScript.contains("SHADOW_MUST_NOT_EXECUTE"));
            assertTrue(f.tasks.get(f.id(),false).getJSONArray("operations").getJSONObject(0).getJSONObject("arguments").has("nativeSkill"));f.preparedProfile.checkSkillExecutionCurrent();assertThrows(SecurityException.class,f.preparedProfile::checkCurrent);
            assertThrows(SecurityException.class,()->f.preparedProfile.requireSkillExecution(f.id(),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.input,f.control));
            assertThrows(SecurityException.class,()->f.host.decide(f.window,review,"f".repeat(64),true));f.approve(review);JSONObject runningAck=null;long ackUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<ackUntil&&!work.isDone()){JSONObject record=f.record();if(record.has("nativeManagedJob")){JSONObject route=record.getJSONObject("nativeManagedJob");try{JSONObject actual=f.runtime.query(RuntimeExecutionRef.fromHost(route.getJSONObject("exact"),route.getJSONObject("binding"))).json();if(!actual.optBoolean("resourcesReleased")){runningAck=actual;break;}}catch(IllegalArgumentException|java.io.IOException notStarted){}}Thread.sleep(10);}assertNotNull("Capture the actual original pending ACK, never a made-up success or a guessed race",runningAck);JSONObject result=work.get(20,TimeUnit.SECONDS);assertEquals("safe original receipt="+result,"completed",result.getString("status"));f.tasks.recordManagedSkillBackendReceiptForHost(runningAck);assertTrue(f.record().getJSONObject("nativeManagedObservation").getBoolean("resourcesReleased"));assertEquals("completed",f.record().getJSONObject("receipt").getString("status"));assertTrue(result.optBoolean("ok"));assertEquals("available",result.getString("artifactStatus"));assertFalse(result.getBoolean("businessVerified"));assertEquals("40.00",f.originalInput());byte[] expected=new byte[]{0,(byte)255};ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(expected);bytes.write("45.00|$(literal no shell) 中文".getBytes(StandardCharsets.UTF_8));assertArrayEquals(bytes.toByteArray(),f.saved());
            JSONObject record=f.record(),job=record.getJSONObject("nativeManagedJob"),process=record.getJSONObject("nativeManagedObservation");assertTrue(process.getBoolean("resourcesReleased"));assertTrue(process.getBoolean("processExited"));assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertEquals(f.operation(),job.getJSONObject("binding").getString("operationId"));assertEquals(review.plan.digest,job.getJSONObject("exact").getString("planDigest"));
            JSONObject page=f.host.readExecutionLog(f.id(),f.operation(),0,16000);assertTrue(page.getJSONArray("chunks").length()>0);assertTrue(page.getBoolean("hasMore"));long cursor=page.getLong("nextCursor");assertTrue(cursor>0);assertTrue(f.host.readExecutionLog(f.id(),f.operation(),cursor,16000).getLong("nextCursor")>cursor);
            f.tasks.journal(f.id(),f.task.getString("runId"),f.task.getInt("coordinatorEpoch"),f.control).finishOperation(f.operation(),result);f.host.removeConfiguration(f.window,f.host.configuration().getLong("revision"),f.host.configuration().getString("stateFingerprint"));f.skills.handle("delete",new JSONObject().put("id",f.installed.getString("id")));f.reopenForReceiptOnly();JSONObject cold=f.host.reconcile(f.id(),f.operation(),false);assertEquals("completed",cold.getJSONObject("receipt").getString("status"));assertFalse(f.host.configuration().getBoolean("configured"));assertArrayEquals(bytes.toByteArray(),f.saved());assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertNull(f.callbackFailure.get());
        }
    }
    @Test public void shellSkillExecutesOnlyApprovedScriptAndLiteralArgvWithoutAComputer()throws Exception {
        String script="set -eu\nmkdir -p output\nprintf '%s|%s' \"$1\" \"$(cat input/data.txt)\" > output/result.bin\nprintf '%s\\n' 'OWN_SHELL_EXECUTED'\nprintf '%s\\n' 'OWN_STDERR' >&2\n";
        try(Fixture f=new Fixture("sh",script,"$(not interpreted) 中文")){var work=f.execute();var review=f.review();f.approve(review);JSONObject result=work.get(15,TimeUnit.SECONDS);assertEquals("safe original receipt="+result,"completed",result.getString("status"));assertArrayEquals("$(not interpreted) 中文|40.00".getBytes(StandardCharsets.UTF_8),f.saved());assertTrue(result.getString("output").contains("OWN_SHELL_EXECUTED"));assertEquals("40.00",f.originalInput());assertEquals("this_phone",result.getString("executionLocation"));assertNull(f.callbackFailure.get());}
    }
    @Test public void sourceDriftOrNativeDenialCannotExecuteOrClaimStrongIsolation()throws Exception {
        try(Fixture f=new Fixture("python3",PY,"own")){var work=f.execute();var review=f.review();Files.write(f.android.getFilesDir().toPath().resolve("local-development/checkouts/"+f.checkout+"/files/input/data.txt"),"41.00".getBytes(StandardCharsets.UTF_8));assertThrows(SecurityException.class,()->f.host.decide(f.window,review,review.plan.digest,true));assertFalse(Files.exists(f.copy()));f.host.decide(f.window,review,review.plan.digest,false);assertEquals("rejected",work.get(5,TimeUnit.SECONDS).getString("status"));assertFalse(f.record().has("nativeManagedJob"));JSONObject counterfeit=f.configuration("python3").put("network","deny");assertThrows(IllegalArgumentException.class,()->f.host.inspectPhoneConfiguration(f.id(),counterfeit));assertNull(f.callbackFailure.get());}
    }
    @Test public void dependencyLockWithoutOwnerPreparationCannotCreateAProcessOrReview()throws Exception {
        String lock="own_fixture==1.0.0 --hash=sha256:"+"a".repeat(64)+"\n";
        try(Fixture f=new Fixture("python3",PY,Map.of(ManagedSkillDependencies.PATH,lock),false,"denied")){
            JSONObject result=f.execute().get(5,TimeUnit.SECONDS);
            assertEquals(result.toString(),"rejected",result.getString("status"));
            assertFalse(result.getBoolean("ok"));assertNull(f.host.pendingReview(f.id()));
            assertFalse(Files.exists(f.copy()));assertEquals(0,f.callbacks.get());
            assertThrows(Exception.class,f::record);
            assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());
            assertEquals("40.00",f.originalInput());assertNull(f.callbackFailure.get());
        }
    }
    @Test public void changedReviewedDependencyBytesCannotReachVenvOrPip()throws Exception {
        String lock="own_fixture==1.0.0 --hash=sha256:"+"a".repeat(64)+"\n";
        try(Fixture f=new Fixture("python3",PY,Map.of(ManagedSkillDependencies.PATH,lock),true,"changed")){
            var work=f.execute();var review=f.review();
            JSONObject dependencies=review.plan.review().getJSONObject("dependencies");
            assertEquals(SkillExecution.sha256(lock.getBytes(StandardCharsets.UTF_8)),dependencies.getString("requirementsSha256"));
            assertEquals("own-fixture",dependencies.getJSONArray("packages").getJSONObject(0).getString("name"));
            assertTrue(dependencies.getBoolean("networkNeeded"));assertFalse(dependencies.getBoolean("sourceBuildAllowed"));
            Path source=f.android.getFilesDir().toPath().resolve("skills/"+f.installed.getString("id")+"/"+f.installed.getInt("revision")+"/"+f.installed.getString("name")+"/"+ManagedSkillDependencies.PATH);
            Files.write(source,lock.replace("a".repeat(64),"b".repeat(64)).getBytes(StandardCharsets.UTF_8));
            assertThrows(Exception.class,()->f.approve(review));
            assertFalse(Files.exists(f.copy()));assertEquals(0,f.callbacks.get());
            f.host.decide(f.window,review,review.plan.digest,false);
            assertEquals("rejected",work.get(5,TimeUnit.SECONDS).getString("status"));
            assertFalse(f.record().has("nativeManagedJob"));assertNull(f.callbackFailure.get());
        }
    }
    @Test public void stopUsesOriginalManagedJobAndColdUnknownCannotDispatchOrDeleteItsCopy()throws Exception {
        String script="from pathlib import Path\nimport time\nPath('output').mkdir(exist_ok=True)\nPath('output/result.bin').write_bytes(b'own start once')\nprint('OWN_WAITING', flush=True)\nwhile True: time.sleep(.1)\n";
        try(Fixture f=new Fixture("python3",script)){var work=f.execute();var review=f.review();f.approve(review);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.isRegularFile(f.copy().resolve("workspace/output/result.bin"))&&System.nanoTime()<until)Thread.sleep(20);assertTrue(Files.isRegularFile(f.copy().resolve("workspace/output/result.bin")));f.control.cancel();JSONObject result=work.get(10,TimeUnit.SECONDS);JSONObject actual=f.host.reconcile(f.id(),f.operation(),false);assertEquals("safe cancellation="+actual,"cancelled",actual.getJSONObject("receipt").getString("status"));assertFalse(result.getBoolean("ok"));assertTrue(actual.getJSONObject("nativeManagedObservation").getBoolean("resourcesReleased"));assertTrue(actual.getJSONObject("nativeManagedObservation").getBoolean("processExited"));assertEquals("own start once",new String(Files.readAllBytes(f.copy().resolve("workspace/output/result.bin")),StandardCharsets.UTF_8));assertEquals(1,f.tasks.get(f.id(),false).getJSONArray("operations").length());assertThrows(SecurityException.class,()->f.host.decide(f.window,review,review.plan.digest,true));assertNull(f.callbackFailure.get());}
    }
}
