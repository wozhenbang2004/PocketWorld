package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real host subprocesses exercise the protocol fixture; they do not pretend to be Android/Termux. */
public final class ProjectDevelopmentTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final String checkout="12345678-1234-1234-1234-123456789abc";
    private interface Throwing {void run()throws Exception;}
    private static void denied(Throwing code)throws Exception {try{code.run();fail("unsafe request accepted");}catch(SecurityException|IllegalArgumentException|IllegalStateException expected){}}

    private final class LocalFixture implements EngineModule {
        final Path root,source,remote,jobs;final ProjectDevelopment facade;int submissions;
        LocalFixture()throws Exception {root=temp.newFolder().toPath();source=Files.createDirectory(root.resolve("source"));remote=Files.createDirectory(root.resolve("remote"));jobs=Files.createDirectory(root.resolve("jobs"));facade=new ProjectDevelopment(this);}
        void write(String path,String content)throws Exception {Path target=source.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,content);}
        JSONObject exported()throws Exception {
            JSONArray entries=new JSONArray();try(var tree=Files.walk(source)){for(Path file:(Iterable<Path>)tree.filter(Files::isRegularFile).sorted()::iterator){String content=Files.readString(file);entries.put(new JSONObject().put("path",source.relativize(file).toString()).put("content",content).put("sha256",ProjectSources.sha256(content.getBytes(StandardCharsets.UTF_8))));}}
            return new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId","project")).put("files",entries);
        }
        @Override public String id(){return "local";}
        @Override public JSONObject snapshot(){return new JSONObject();}
        @Override public JSONObject handle(String command,JSONObject input)throws Exception {
            switch(command){
                case "export":if(!checkout.equals(input.getString("checkoutId")))throw new SecurityException("unknown checkout");return exported();
                case "probe":return new JSONObject().put("termux",new JSONObject().put("installed",true).put("permissionGranted",true).put("enabled",true));
                case "listJobs":{JSONArray all=new JSONArray();try(var files=Files.list(jobs)){for(Path path:(Iterable<Path>)files.sorted()::iterator)all.put(JsonFiles.read(path.toFile()));}return new JSONObject().put("termuxJobs",all);}
                case "getJob":{JSONObject job=JsonFiles.read(jobs.resolve(input.getString("jobId")+".json").toFile());if(job.length()==0)throw new IllegalArgumentException("unknown actual job");return job;}
                case "readFile":{String path=input.getString("path"),text=Files.readString(source.resolve(path));return new JSONObject().put("path",path).put("content",text).put("sha256",ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8)));}
                default:throw new AssertionError("Unexpected backend call "+command);
            }
        }
        JSONObject inspect()throws Exception{return facade.handle("project.inspect",new JSONObject().put("checkoutId",checkout));}
        JSONObject plan(String intent,String ecosystem)throws Exception {return facade.handle("project.plan",new JSONObject().put("checkoutId",checkout).put("intent",intent).put("ecosystem",ecosystem).put("expectedSourceDigest",inspect().getString("sourceDigest")));}
        void save(JSONObject job)throws Exception {JsonFiles.write(jobs.resolve(job.getString("id")+".json").toFile(),job);}
        /** Independent fixture backend: actual staged bytes and actual CLI exit/stdout/stderr. */
        JSONObject owner(JSONObject decorated,String stepId,String operation)throws Exception {
            JSONObject plan=ProjectBuildPlan.verified(decorated),step=ProjectBuildPlan.step(plan,stepId),input=ProjectSources.copy(step.getJSONObject("payload"));
            input.put("projectPlan",plan).put("projectStepId",stepId).put("_taskId","task_"+operation).put("_runId",operation).put("operationId",operation);
            JSONObject link=ProjectBuildPlan.ownerLink(input,step.getString("localCommand"));
            if(!plan.getString("sourceDigest").equals(ProjectSources.describe(exported()).getString("sourceDigest")))throw new SecurityException("actual source changed");
            submissions++;JSONObject job=new JSONObject().put("id",operation).put("operationId",operation).put("checkoutId",checkout).put("projectExecution",link).put("createdAt",System.nanoTime()).put("receiptReceived",true).put("outputTruncated",false).put("sourceDigest",plan.getString("sourceDigest"));
            Path revision=remote.resolve(plan.getString("sourceDigest"));
            if(stepId.equals("prepare")){
                ProcessResult staged=process(root,ProjectSources.stageScript(exported(),revision.toString(),remote.toString()));
                if(staged.exit!=0)throw new IllegalStateException("real staging failed: "+staged.stderr);
                job.put("kind","project_stage").put("status","completed").put("exitCode",staged.exit);
            }else{
                JSONArray args=input.getJSONArray("argv");String executable=input.getString("executable");
                String guard=ProjectSources.launchGuard(plan.getJSONArray("sourceManifest"),plan.getString("sourceDigest"),executable,args);
                ProcessResult result=process(revision,guard);
                String marker=ProjectSources.marker(plan.getString("sourceDigest"))+"\n";boolean verified=result.stdout.startsWith(marker);
                job.put("kind","command").put("requestedExecutable",executable).put("requestedArgv",args).put("executable","sh").put("argv",new JSONArray())
                        .put("status",result.exit==0?"completed":"failed").put("exitCode",result.exit).put("stdout",verified?result.stdout.substring(marker.length()):result.stdout).put("stderr",result.stderr)
                        .put("sourceCheckStatus",verified?"pre_exec_manifest_verified":"unverified_or_mismatch");
            }
            save(job);return job;
        }
        JSONObject request(JSONObject plan,String step,String job)throws Exception{return new JSONObject().put("plan",plan).put("stepId",step).put("jobId",job);}
        void probe(String...names)throws Exception {StringBuilder out=new StringBuilder();for(String name:names)out.append(name).append("=/data/data/com.termux/files/usr/bin/").append(name).append('\n');save(new JSONObject().put("id","probe").put("kind","tool_probe").put("status","completed").put("receiptReceived",true).put("exitCode",0).put("stdout",out).put("createdAt",1));}
    }
    private static final class ProcessResult {final int exit;final String stdout,stderr;ProcessResult(int exit,String out,String err){this.exit=exit;stdout=out;stderr=err;}}
    private ProcessResult process(Path directory,String script)throws Exception {
        Path out=directory.resolveSibling(UUID.randomUUID()+".out"),err=directory.resolveSibling(UUID.randomUUID()+".err");
        Process child=new ProcessBuilder("sh").directory(directory.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try{child.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));child.getOutputStream().close();if(!child.waitFor(15,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("fixture command timeout");}return new ProcessResult(child.exitValue(),Files.readString(out),Files.readString(err));}finally{child.destroyForcibly();Files.deleteIfExists(out);Files.deleteIfExists(err);}
    }
    @Test public void sourceIdentityUsesActualBytesAndCanonicalPaths()throws Exception {
        LocalFixture local=new LocalFixture();local.write("中文 name.py","print('内容')\n");JSONObject before=local.inspect();
        local.write("中文 name.py","print('changed')\n");assertNotEquals(before.getString("sourceDigest"),local.inspect().getString("sourceDigest"));
        JSONObject export=local.exported();export.getJSONArray("files").getJSONObject(0).put("sha256","0".repeat(64));denied(()->ProjectSources.describe(export));
        export.getJSONArray("files").getJSONObject(0).put("path","../escape");denied(()->ProjectSources.files(export));
    }
    @Test public void invalidPackageManifestRemainsInspectableAndRepairPlanKeepsActualFileCas()throws Exception {
        for(String broken:new String[]{"{\"scripts\":", "[\"not a package object\"]"}){
            LocalFixture local=new LocalFixture();local.write("package.json",broken);local.write("main.mjs","console.log('selected-project')\n");
            JSONObject inspection=local.inspect();String source=inspection.getString("sourceDigest"),before=local.handle("readFile",new JSONObject().put("path","package.json")).getString("sha256");
            assertEquals(ProjectSources.describe(local.exported()).getString("sourceDigest"),source);
            assertEquals("node",inspection.getJSONArray("ecosystems").getJSONObject(0).getString("id"));
            assertFalse(inspection.getJSONArray("ecosystems").getJSONObject(0).getBoolean("manifestValid"));
            assertEquals(0,inspection.getJSONArray("dependencies").length());assertTrue(inspection.getJSONArray("warnings").length()>0);
            String repaired="{\"scripts\":{\"build\":\"node --check main.mjs\"}}\n";
            JSONObject request=new JSONObject().put("checkoutId",checkout).put("intent","edit").put("expectedSourceDigest",source).put("path","package.json").put("content",repaired).put("expectedHash",before);
            JSONObject plan=ProjectBuildPlan.verified(local.facade.handle("project.plan",request)),step=ProjectBuildPlan.step(plan,"edit"),payload=step.getJSONObject("payload");
            assertTrue(plan.getBoolean("available"));assertEquals("writeFile",step.getString("localCommand"));assertEquals(before,payload.getString("expectedHash"));assertEquals(repaired,payload.getString("content"));assertEquals(0,local.submissions);
            denied(()->local.facade.handle("project.plan",new JSONObject(request.toString()).put("expectedHash","absent")));
            for(String intent:new String[]{"dependencies","build","test","run"})assertThrows(org.json.JSONException.class,()->local.plan(intent,"node"));
            // The actual fixture tree changes only after consuming the reviewed file payload.
            local.write(payload.getString("path"),payload.getString("content"));
            denied(()->local.facade.handle("project.plan",request));
            JSONObject after=local.inspect();assertNotEquals(source,after.getString("sourceDigest"));
            denied(()->local.facade.handle("project.plan",new JSONObject(request.toString()).put("expectedSourceDigest",after.getString("sourceDigest"))));
            assertEquals("npm",ProjectBuildPlan.step(local.plan("build","node"),"build").getJSONObject("payload").getString("executable"));
        }
    }
    @Test public void declaredDependenciesLocksAndObservedToolsAreSeparateFromAvailability()throws Exception {
        LocalFixture local=new LocalFixture();local.write("package.json","{\"packageManager\":\"pnpm@9.0.0\",\"dependencies\":{\"example\":\"1.2.3\"},\"scripts\":{\"build\":\"node build.js\"}}");local.write("pnpm-lock.yaml","lockfileVersion: '9'\n");
        JSONObject inspect=local.inspect();assertEquals("example",inspect.getJSONArray("dependencies").getJSONObject(0).getString("name"));assertEquals("pnpm",inspect.getJSONArray("ecosystems").getJSONObject(0).getString("packageManager"));
        assertFalse(local.plan("dependencies","node").getBoolean("available"));local.probe("pnpm");JSONObject plan=local.plan("dependencies","node");assertTrue(plan.getBoolean("available"));
        JSONArray args=ProjectBuildPlan.step(plan,"dependencies").getJSONObject("payload").getJSONArray("argv");assertEquals("[\"install\",\"--frozen-lockfile\",\"--ignore-scripts\"]",args.toString());assertFalse(plan.getBoolean("automaticChaining"));
        local.write("package-lock.json","{}");denied(()->local.plan("build","node"));
    }
    @Test public void nativeProbeCannotTurnIntoProgrammingExecutorOrAcknowledgeUnobservedTools()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");assertFalse(local.inspect().getBoolean("nativeProgrammingExecution"));
        local.save(new JSONObject().put("id","probe_bad").put("kind","tool_probe").put("status","completed").put("receiptReceived",true).put("exitCode",0).put("stdout","python3=/some/unreviewed/path\n").put("createdAt",5));
        assertFalse(local.plan("build","python").getBoolean("available"));
    }
    @Test public void everyEffectIsAnOriginalIndividuallyReviewableHostAction()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python");JSONArray steps=plan.getJSONArray("steps");assertEquals(2,steps.length());
        for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i),action=step.getJSONObject("hostAction");assertTrue(step.getBoolean("approvalRequired"));assertEquals("local",action.getString("feature"));assertEquals(step.getString("localCommand"),action.getString("command"));assertEquals(plan.getString("planDigest"),ProjectBuildPlan.verified(action.getJSONObject("payload").getJSONObject("projectPlan")).getString("planDigest"));}
        JSONObject altered=ProjectSources.copy(plan);altered.getJSONArray("steps").getJSONObject(1).getJSONObject("payload").put("executable","git");denied(()->ProjectBuildPlan.verified(altered));
        JSONObject payload=ProjectSources.copy(steps.getJSONObject(1).getJSONObject("hostAction").getJSONObject("payload"));denied(()->ProjectBuildPlan.ownerLink(payload,"termux.run"));
        payload.put("_taskId","real_task").put("_runId","real_run").put("operationId","real_run");assertEquals("build",ProjectBuildPlan.ownerLink(payload,"termux.run").getString("intent"));
        payload.put("argv",new JSONArray().put("other.py"));denied(()->ProjectBuildPlan.ownerLink(payload,"termux.run"));
    }
    @Test public void staleSourceAndAmbiguousEntryRequireNewPlan()throws Exception {
        LocalFixture local=new LocalFixture();local.write("a.py","print(1)\n");local.write("b.py","print(2)\n");denied(()->local.plan("build","python"));
        JSONObject inspect=local.inspect();local.write("a.py","print(3)\n");denied(()->ProjectBuildPlan.create(local,new JSONObject().put("checkoutId",checkout).put("intent","build").put("ecosystem","python").put("target","a.py").put("expectedSourceDigest",inspect.getString("sourceDigest"))));
    }
    @Test public void actualSyntaxFailureFeedsOriginalTaskThenNewRevisionBuildsAndPreviews()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(\n");local.write("index.html","<h1>真实源码</h1>");local.probe("python3");JSONObject first=local.plan("build","python");
        local.owner(first,"prepare","stage_1");JSONObject failed=local.owner(first,"build","build_1");assertEquals("failed",failed.getString("status"));assertTrue(failed.getString("stderr").contains("SyntaxError"));
        JSONObject request=local.request(first,"build","build_1"),feedback=local.facade.handle("project.repair",request);assertFalse(feedback.getBoolean("newTaskCreated"));assertEquals("task_build_1",feedback.getJSONObject("sameTaskRepair").getJSONObject("binding").getString("taskId"));assertTrue(feedback.getJSONObject("sameTaskRepair").getBoolean("requiresNewPlan"));
        local.write("main.py","print('修复完成')\n");JSONObject next=local.plan("build","python");assertNotEquals(first.getString("sourceDigest"),next.getString("sourceDigest"));denied(()->local.owner(first,"build","unsafe_old_approval"));
        local.owner(next,"prepare","stage_2");local.owner(next,"build","build_2");JSONObject receipt=local.facade.handle("project.adoptOwnerReceipt",local.request(next,"build","build_2"));assertEquals("completed",receipt.getString("status"));assertTrue(receipt.getBoolean("sourceVerifiedBeforeExec"));assertFalse(receipt.getBoolean("atomicSourceFence"));
        JSONObject artifact=local.facade.handle("project.artifact",local.request(next,"build","build_2").put("path","index.html"));assertEquals(receipt.getString("receiptIdentity"),artifact.getString("buildReceiptIdentity"));assertEquals(ProjectSources.sha256(artifact.getString("content").getBytes(StandardCharsets.UTF_8)),artifact.getString("sha256"));assertFalse(artifact.getBoolean("generatedArtifact"));
        denied(()->local.facade.handle("project.artifact",local.request(next,"build","build_2").put("path","__pycache__/main.pyc")));
    }
    @Test public void actualNodeEntryAndPythonExecutionReturnCliStdoutAndExit()throws Exception {
        LocalFixture node=new LocalFixture();node.write("main.js","console.log('node-real')\n");JSONObject plan=node.plan("run","node");node.owner(plan,"prepare","node_stage");node.owner(plan,"run","node_run");JSONObject receipt=node.facade.handle("project.poll",node.request(plan,"run","node_run"));assertEquals(0,receipt.getInt("exitCode"));assertEquals("node-real\n",receipt.getString("stdout"));
        LocalFixture python=new LocalFixture();python.write("main.py","import sys; print('python-real'); sys.exit(7)\n");JSONObject p=python.plan("run","python");python.owner(p,"prepare","py_stage");python.owner(p,"run","py_run");JSONObject r=python.facade.handle("project.poll",python.request(p,"run","py_run"));assertEquals(7,r.getInt("exitCode"));assertEquals("python-real\n",r.getString("stdout"));
    }
    @Test public void guardChecksRemoteBytesAndSymlinksBeforeToolCanProduceEffects()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python");local.owner(plan,"prepare","guard_stage");Path revision=local.remote.resolve(plan.getString("sourceDigest"));Path effect=revision.resolve("should_not_exist");
        String guard=ProjectSources.launchGuard(plan.getJSONArray("sourceManifest"),plan.getString("sourceDigest"),"sh",new JSONArray().put("-c").put("printf changed > should_not_exist"));
        Files.writeString(revision.resolve("main.py"),"tampered");ProcessResult mismatch=process(revision,guard);assertEquals(73,mismatch.exit);assertTrue(mismatch.stderr.contains("POCKETWORLD_SOURCE_MISMATCH"));assertFalse(Files.exists(effect));
        Files.delete(revision.resolve("main.py"));Files.createSymbolicLink(revision.resolve("main.py"),local.source.resolve("main.py"));assertEquals(73,process(revision,guard).exit);assertFalse(Files.exists(effect));
    }
    @Test public void shellQuotingKeepsProjectArgumentsLiteral()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","import sys;print(sys.argv[1])\n");JSONObject plan=local.plan("run","python");local.owner(plan,"prepare","quote_stage");String argument="x'; touch ESCAPE; $(printf BAD)\n中文";
        String guard=ProjectSources.launchGuard(plan.getJSONArray("sourceManifest"),plan.getString("sourceDigest"),"python3",new JSONArray().put("main.py").put(argument));Path revision=local.remote.resolve(plan.getString("sourceDigest"));ProcessResult result=process(revision,guard);assertEquals(0,result.exit);assertTrue(result.stdout.endsWith(argument+"\n"));assertFalse(Files.exists(revision.resolve("ESCAPE")));
    }
    @Test public void sourceEntryThatStartsWithDashIsPassedAsAFileNotAnInterpreterOption()throws Exception {
        LocalFixture local=new LocalFixture();String path="--eval=console.log('wrong').js";local.write(path,"console.log('literal-file')\n");JSONObject plan=local.plan("run","node");assertEquals("./"+path,ProjectBuildPlan.step(plan,"run").getJSONObject("payload").getJSONArray("argv").getString(0));local.owner(plan,"prepare","dash_stage");assertEquals("literal-file\n",local.owner(plan,"run","dash_run").getString("stdout"));
    }
    @Test public void uiStatusReceiptAndCrossPlanJobCannotBecomeAuthority()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","bad syntax !\n");JSONObject plan=local.plan("build","python");local.owner(plan,"prepare","adopt_stage");local.owner(plan,"build","adopt_build");
        JSONObject request=local.request(plan,"build","adopt_build").put("receipt",new JSONObject().put("status","completed").put("exitCode",0)).put("approved",true);assertEquals("failed",local.facade.handle("project.adoptOwnerReceipt",request).getString("status"));
        denied(()->local.facade.handle("project.poll",local.request(plan,"prepare","adopt_build")));
        JSONObject job=local.handle("getJob",new JSONObject().put("jobId","adopt_build"));job.put("checkoutId","different");local.save(job);denied(()->local.facade.handle("project.poll",request));
    }
    @Test public void logCursorIsBoundToActualJobAndNeverInventsStreaming()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print('a')\n");JSONObject plan=local.plan("run","python");local.owner(plan,"prepare","cursor_stage");local.owner(plan,"run","cursor_one");JSONObject input=local.request(plan,"run","cursor_one");JSONObject first=local.facade.handle("project.poll",input),cursor=first.getJSONObject("log").getJSONObject("cursor");
        JSONObject next=local.facade.handle("project.poll",new JSONObject(input.toString()).put("cursor",cursor));assertEquals("",next.getJSONObject("log").getString("stdout"));assertFalse(next.getJSONObject("log").getBoolean("streaming"));
        local.owner(plan,"run","cursor_two");denied(()->local.facade.handle("project.poll",local.request(plan,"run","cursor_two").put("cursor",cursor)));
    }
    @Test public void preparationProgressComesOnlyFromTheOriginalBackendReceiptAndIsCopied()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("prepare",""),job=local.owner(plan,"prepare","preparation-receipt");
        JSONObject observed=new JSONObject().put("phase","ready").put("completedBytes",9).put("totalBytes",9).put("error","").put("resumableDownload",false);job.put("preparation",observed);local.save(job);
        JSONObject input=local.request(plan,"prepare","preparation-receipt").put("preparation",new JSONObject().put("phase","untrusted-input-must-not-be-used"));
        JSONObject result=local.facade.handle("project.poll",input);assertEquals(ProjectSources.canonical(observed),ProjectSources.canonical(result.getJSONObject("preparation")));
        result.getJSONObject("preparation").put("phase","mutated-display-copy");assertEquals("ready",local.facade.handle("project.poll",input).getJSONObject("preparation").getString("phase"));
        job.remove("preparation");local.save(job);assertFalse(local.facade.handle("project.poll",input).has("preparation"));
    }
    @Test public void unknownCancelAndMissingPreExecCheckNeverProduceRepairOrBuildArtifact()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python");local.owner(plan,"prepare","unknown_stage");JSONObject job=local.owner(plan,"build","unknown_build");JSONObject request=local.request(plan,"build","unknown_build");int count=local.submissions;
        job.put("status","unknown").put("receiptReceived",false);local.save(job);denied(()->local.facade.handle("project.repair",request));denied(()->local.facade.handle("project.artifact",new JSONObject(request.toString()).put("path","main.py")));assertEquals(count,local.submissions);
        job.put("status","failed").put("receiptReceived",true).put("exitCode",1).put("cancelRequested",true);local.save(job);denied(()->local.facade.handle("project.repair",request));
        JSONObject cancel=local.facade.handle("project.cancel",request);assertFalse(cancel.getBoolean("dispatched"));assertEquals("termux.cancel",cancel.getJSONObject("hostAction").getString("command"));assertEquals(count,local.submissions);
        job.put("status","completed").put("exitCode",0).put("cancelRequested",false).put("sourceCheckStatus","unverified_or_mismatch");local.save(job);denied(()->local.facade.handle("project.artifact",new JSONObject(request.toString()).put("path","main.py")));
        job.put("sourceCheckStatus","pre_exec_manifest_verified").put("exitCode",0.5);local.save(job);denied(()->local.facade.handle("project.artifact",new JSONObject(request.toString()).put("path","main.py")));
        job.put("status","failed").put("exitCode",-1.5);local.save(job);denied(()->local.facade.handle("project.repair",request));
    }
    @Test public void nativeHostRequiredEvenWhenJsonClaimsApprovalAndRunIdentity()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python");JSONObject binding=nativeBinding("automatic");assertFalse(local.facade.snapshot().getBoolean("executionAvailable"));assertTrue(local.facade.snapshot().getBoolean("manualOwnerExecutionAvailable"));
        denied(()->local.facade.handle("project.execute",new JSONObject().put("plan",plan).put("stepId","build").put("binding",binding).put("approved",true)));assertEquals(0,local.submissions);
    }
    private JSONObject nativeBinding(String operation)throws Exception{return new JSONObject().put("taskId","task_native").put("runId","run_native").put("coordinatorEpoch",2).put("operationId",operation);}
    @Test public void knownNativeOperationQueriesOriginalJobWithoutResubmission()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python");local.owner(plan,"prepare","native_stage");JSONObject job=local.owner(plan,"build","native_build"),binding=nativeBinding("native_build"),step=ProjectBuildPlan.step(plan,"build");RunControl control=new RunControl();control.bindHostRun("task_native","run_native",2);int[] dispatches={0};
        JSONObject link=ProjectDevelopment.exact(plan,step).put("jobId",job.getString("id"));for(java.util.Iterator<String> keys=binding.keys();keys.hasNext();){String key=keys.next();link.put(key,binding.get(key));}
        local.facade.setHost(new ProjectDevelopment.HostPort(){public ProjectDevelopment.Claim claim(JSONObject b,JSONObject e){return new ProjectDevelopment.Claim(control,true);}public void dispatch(JSONObject b,JSONObject e){dispatches[0]++;}public JSONObject operation(JSONObject b){return link;}});
        JSONObject result=local.facade.handle("project.execute",new JSONObject().put("plan",plan).put("stepId","build").put("binding",binding));assertEquals("completed",result.getString("status"));assertEquals("native_host_operation",result.getJSONObject("binding").getString("authority"));assertEquals(0,dispatches[0]);
        link.put("jobId","other");denied(()->local.facade.handle("project.poll",new JSONObject().put("plan",plan).put("stepId","build").put("binding",binding).put("jobId","native_build")));assertEquals(0,dispatches[0]);
    }
    @Test public void pausedCancelledAndWrongRunControlsBlockBeforeNativeDispatch()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python"),binding=nativeBinding("gated");int[] dispatches={0};RunControl[] retained={new RunControl()};retained[0].bindHostRun("other_task","run_native",2);
        local.facade.setHost(new ProjectDevelopment.HostPort(){public ProjectDevelopment.Claim claim(JSONObject b,JSONObject e){return new ProjectDevelopment.Claim(retained[0],false);}public void dispatch(JSONObject b,JSONObject e){dispatches[0]++;}public JSONObject operation(JSONObject b){return new JSONObject();}});
        JSONObject input=new JSONObject().put("plan",plan).put("stepId","build").put("binding",binding);denied(()->local.facade.handle("project.execute",input));retained[0]=new RunControl();retained[0].bindHostRun("task_native","run_native",2);retained[0].pause();denied(()->local.facade.handle("project.execute",input));retained[0].resume();retained[0].cancel();try{local.facade.handle("project.execute",input);fail();}catch(InterruptedException expected){}assertEquals(0,dispatches[0]);
    }
    @Test public void uncertainNativeDispatchIsOnlyReconciledAndNeverAutomaticallyReplayed()throws Exception {
        LocalFixture local=new LocalFixture();local.write("main.py","print(1)\n");JSONObject plan=local.plan("build","python"),binding=nativeBinding("uncertain");RunControl control=new RunControl();control.bindHostRun("task_native","run_native",2);boolean[] known={false};int[] dispatches={0};
        local.facade.setHost(new ProjectDevelopment.HostPort(){public ProjectDevelopment.Claim claim(JSONObject b,JSONObject exact){boolean previous=known[0];known[0]=true;return new ProjectDevelopment.Claim(control,previous);}public void dispatch(JSONObject b,JSONObject exact){dispatches[0]++;throw new IllegalStateException("lost original dispatch acknowledgement");}public JSONObject operation(JSONObject b){return new JSONObject();}});
        JSONObject request=new JSONObject().put("plan",plan).put("stepId","build").put("binding",binding);denied(()->local.facade.handle("project.execute",request));assertEquals("unknown",local.facade.handle("project.execute",request).getString("status"));assertEquals(1,dispatches[0]);
    }
    @Test public void actualJavaCompilerAndRuntimeWorkAfterExactSourceGuard()throws Exception {
        LocalFixture local=new LocalFixture();local.write("Main.java","public class Main { public static void main(String[] args) { System.out.println(\"java-real\"); } }\n");JSONObject plan=local.plan("build","java");local.owner(plan,"prepare","java_stage");Path revision=local.remote.resolve(plan.getString("sourceDigest"));
        // This host exposes javac via its JDK module, not a javac executable. Product plans still require observed Termux javac.
        String java=new File(System.getProperty("java.home"),"bin/java").getAbsolutePath();String build=ProjectSources.launchGuard(plan.getJSONArray("sourceManifest"),plan.getString("sourceDigest"),java,new JSONArray().put("com.sun.tools.javac.Main").put("-d").put("build/classes").put("Main.java"));
        ProcessResult compiled=process(revision,build);assertEquals(compiled.stderr,0,compiled.exit);String run=ProjectSources.launchGuard(plan.getJSONArray("sourceManifest"),plan.getString("sourceDigest"),java,new JSONArray().put("-cp").put("build/classes").put("Main"));ProcessResult executed=process(revision,run);assertEquals(0,executed.exit);assertTrue(executed.stdout.endsWith("java-real\n"));
    }
    @Test public void staticPreviewDoesNotInventBuildOrApkSupport()throws Exception {
        LocalFixture local=new LocalFixture();local.write("index.html","<h1>preview</h1>");denied(()->local.plan("build","static"));assertFalse(local.facade.snapshot().getBoolean("binaryArtifacts"));
        LocalFixture gradle=new LocalFixture();gradle.write("build.gradle.kts","plugins {}\n");denied(()->gradle.plan("run","gradle"));
    }
}
