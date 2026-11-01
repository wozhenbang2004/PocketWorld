package dev.pocketengine.features.local.project;

import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceArtifacts;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual original codec/catalog with protocol-only operation envelopes; not a native dispatch claim. */
public final class ProjectPackageDeliveryTest {
    interface Action {void run()throws Exception;}
    private static void rejects(Action action)throws Exception {try{action.run();fail("invalid package proof accepted");}catch(AssertionError failure){throw failure;}catch(Exception expected){}}
    private static final class Fixture implements AutoCloseable,EngineModule {
        final File root=Files.createTempDirectory("f010-new-delivery-").toFile(),active=new File(root,"active");
        final VersionedWorkspace workspace=new VersionedWorkspace(new File(root,"workspace"));final WorkspaceArtifacts catalog=new WorkspaceArtifacts(new File(root,"catalog"),workspace);
        final String project=workspace.create("原文件项目").getString("id"),checkout=UUID.randomUUID().toString();
        final JSONObject snapshot;final String digest;
        Fixture()throws Exception {assertTrue(active.mkdir());write("index.html","<h1>40.00</h1><script src=app.js></script><img src=raw.bin>");write("app.js","globalThis.amount=40.00;\n");Files.write(new File(active,"raw.bin").toPath(),new byte[]{0,1,-1,3});write(".env","not included");write("node_modules/pkg/private.js","not included");assertTrue(new File(active,"empty/sub").mkdirs());JSONArray files=new JSONArray();for(String path:new String[]{"index.html","app.js","raw.bin",".env"}){byte[] bytes=Files.readAllBytes(new File(active,path).toPath());JSONObject file=new JSONObject().put("path",path);if(path.equals("raw.bin"))file.put("binary",true).put("contentBase64",java.util.Base64.getEncoder().encodeToString(bytes));else file.put("content",new String(bytes,StandardCharsets.UTF_8));files.put(file);}snapshot=new JSONObject().put("checkout",new JSONObject().put("id",checkout).put("projectId",project)).put("files",files);digest=ProjectSources.describe(snapshot).getString("sourceDigest");}
        void write(String path,String text)throws Exception {File file=new File(active,path);file.getParentFile().mkdirs();Files.writeString(file.toPath(),text);}
        ProjectPackageArchive.Frozen frozen(String mode)throws Exception {return ProjectPackageArchive.capture(active,new JSONObject().put("projectId",project).put("checkoutId",checkout).put("sourceDigest",digest).put("name","原文件项目"),mode,mode.equals("web_output")?"index.html":"",false);}
        JSONObject plan()throws Exception {return ProjectBuildPlan.create(this,new JSONObject().put("checkoutId",checkout).put("runtimeId","managed_process_v1").put("intent","freeze_package").put("expectedSourceDigest",digest));}
        public String id(){return "local";}public JSONObject snapshot(){return snapshot;}
        public JSONObject handle(String command,JSONObject input)throws Exception {if(command.equals("export"))return new JSONObject(snapshot.toString());if(command.equals("project.runtime.describe"))return new JSONObject().put("identity","actual-protocol-fixture-environment");if(command.equals("project.package.plan")){var frozen=frozen(input.getString("mode"));JSONObject archive=ProjectPackageDelivery.archiveIdentity(frozen);return new JSONObject().put("manifest",frozen.manifest()).put("manifestIdentity",frozen.identity).put("sourceDigest",digest).put("archiveSha256",archive.getString("archiveSha256")).put("archiveBytes",archive.getLong("archiveBytes"));}throw new IllegalArgumentException(command);}
        JSONObject operation(JSONObject plan,JSONObject artifact)throws Exception {String op=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();return operation(plan,artifact,op,run);}
        JSONObject operation(JSONObject plan,JSONObject artifact,String op,String run)throws Exception {JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,"freeze_package")),route=new JSONObject().put("taskId","task").put("runId",run).put("coordinatorEpoch",1).put("operationId",op).put("jobId",op).put("authority","native_host_operation");for(String key:new String[]{"command","projectId","checkoutId","sourceDigest","planDigest","stepId","intent","requestDigest"})route.put(key,exact.get(key));JSONObject backend=new JSONObject().put("id",op).put("operationId",op).put("checkoutId",checkout).put("sourceDigest",digest).put("projectExecution",new JSONObject(route.toString())).put("provider","app_private_checkout").put("kind","project_package_freeze").put("status","completed").put("receiptReceived",true).put("exitCode",0).put("nativeOperationCode",0).put("processExitCode",JSONObject.NULL).put("nativeFileOperation",true).put("nativePublicationEntered",true).put("artifactReady",true).put("sourceCheckStatus","native_checkout_verified").put("packageArtifact",ProjectPackageDelivery.metadata(artifact));return new JSONObject().put("tool","project_execute").put("operationId",op).put("runId",run).put("state","receipt").put("arguments",new JSONObject().put("plan",plan).put("stepId","freeze_package")).put("receipt",new JSONObject().put("code","project_file_receipt").put("status","completed").put("accepted",true).put("projectExecution",route).put("executionReceipt",backend));}
        public void close(){try{try(var paths=Files.walk(root.toPath())){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}catch(Exception failed){throw new IllegalStateException(failed);}}
    }
    @Test public void originalCodecArchiveIdentityPinsActualBinaryDirectoriesAndExcludedPrivateBytes()throws Exception {
        try(Fixture f=new Fixture()){var frozen=f.frozen("source_project");JSONObject identity=ProjectPackageDelivery.archiveIdentity(frozen);ByteArrayOutputStream output=new ByteArrayOutputStream();ProjectPackageArchive.write(frozen,output);assertEquals(output.size(),identity.getLong("archiveBytes"));assertEquals(ProjectSources.sha256(output.toByteArray()),identity.getString("archiveSha256"));assertEquals(identity.toString(),ProjectPackageDelivery.archiveIdentity(frozen).toString());assertArrayEquals(new byte[]{0,1,-1,3},frozen.file("raw.bin"));assertFalse(frozen.files().containsKey(".env"));assertFalse(frozen.files().containsKey("node_modules/pkg/private.js"));assertTrue(frozen.manifest().getJSONArray("directories").toString().contains("empty/sub"));assertEquals(0,f.catalog.list(f.project).length());}
    }
    @Test public void nativeOriginalRowAndActualCatalogMustAgreeAndTamperedOrRetiredArtifactsReject()throws Exception {
        try(Fixture f=new Fixture()){JSONObject plan=f.plan();String op=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();JSONObject artifact=f.catalog.createProjectPackage(f.frozen("source_project"),ProjectPackageDelivery.actionId(op)),row=f.operation(plan,artifact,op,run),evidence=ProjectPackageDelivery.evidence(plan,row),actual=f.catalog.projectPackage(artifact.getString("id"),artifact.getString("sha256"));var reference=ProjectPackageDelivery.requireArtifact(evidence,actual);assertEquals(op,reference.operationId);assertEquals(artifact.getString("id"),reference.artifactId);assertEquals(artifact.getLong("sizeBytes"),reference.archiveBytes);assertFalse(reference.json().getBoolean("automaticallyExported"));assertFalse(reference.json().has("manifest"));
            rejects(()->ProjectPackageDelivery.requireArtifact(evidence,new JSONObject(actual.toString()).put("sha256","f".repeat(64))));rejects(()->ProjectPackageDelivery.requireArtifact(evidence,new JSONObject(actual.toString()).put("availability","retired")));rejects(()->ProjectPackageDelivery.evidence(plan,new JSONObject(row.toString()).put("state","unknown")));
            JSONObject backend=row.getJSONObject("receipt").getJSONObject("executionReceipt"),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,"freeze_package"));assertTrue(ProjectPackageDelivery.terminalReceipt(exact,backend));assertFalse(ProjectPackageDelivery.terminalReceipt(exact,new JSONObject(backend.toString()).put("processExitCode",0)));assertFalse(ProjectPackageDelivery.terminalReceipt(exact,new JSONObject(backend.toString()).put("nativePublicationEntered",false)));assertFalse(ProjectPackageDelivery.terminalReceipt(exact,new JSONObject(backend.toString()).put("kind","command")));
        }
    }
    private static JSONObject reseal(JSONObject plan)throws Exception {JSONObject core=new JSONObject(plan.toString());core.remove("planId");core.remove("planDigest");for(int i=0;i<core.getJSONArray("steps").length();i++)core.getJSONArray("steps").getJSONObject(i).remove("hostAction");String digest=ProjectSources.digest(core);return core.put("planId",digest).put("planDigest",digest);}
    @Test public void fullImmutablePlanBindsPackageScopeAndRejectsExtraProcessExportOrChangedManifestEvenWhenResealed()throws Exception {
        try(Fixture f=new Fixture()){JSONObject plan=f.plan();assertEquals("freeze_package",ProjectBuildPlan.verified(plan).getString("intent"));assertFalse(plan.getJSONArray("steps").getJSONObject(0).has("hostAction"));assertEquals(3,ProjectPackageDelivery.selectionSummary(plan).getInt("fileCount"));assertTrue(ProjectPlanRecords.reference(UUID.randomUUID().toString(),plan).has("packageSelection"));
            JSONObject extra=new JSONObject(plan.toString());extra.getJSONArray("steps").getJSONObject(0).getJSONObject("payload").put("executable","sh");rejects(()->ProjectBuildPlan.verified(reseal(extra)));
            JSONObject export=new JSONObject(plan.toString());export.getJSONArray("steps").getJSONObject(0).getJSONObject("effects").put("automaticallyExported",true);rejects(()->ProjectBuildPlan.verified(reseal(export)));
            JSONObject changed=new JSONObject(plan.toString());changed.getJSONObject("packageManifest").getJSONArray("files").getJSONObject(0).put("bytes",99);rejects(()->ProjectBuildPlan.verified(reseal(changed)));
        }
    }
}
