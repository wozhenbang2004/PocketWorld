package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Original factory/SQL/Host/Local/catalog; controlled receipt-loss boundary, not an external model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectPackageNativeDeliveryJourneyTest {
    private ProjectManagedRunnerJourneyTest original;private LocalModule local;private WorkspaceModule workspace;private TaskStore tasks;private TaskStoreProjectHost host;private RunControl control;private String taskId,project,checkout,run;private int epoch;private ProjectCapabilityPort port;
    @Before public void create()throws Exception {original=new ProjectManagedRunnerJourneyTest();original.createTerminalFixture();call("selectedProject",new Class<?>[]{String.class},"新F010真实项目");call("file",new Class<?>[]{String.class,String.class},"index.html","<h1>40.00</h1><link rel=stylesheet href=site.css><script type=module src=app.mjs></script><img src=pixel.png>");call("file",new Class<?>[]{String.class,String.class},"site.css","h1{color:#124}");call("file",new Class<?>[]{String.class,String.class},"app.mjs","globalThis.amount=40.00;\n");call("startTask",new Class<?>[]{String.class,String.class},"Read real project, obtain an approved native immutable package then reopen original version",UUID.randomUUID().toString());local=field("local");workspace=field("workspace");tasks=field("tasks");host=field("host");taskId=field("taskId");project=field("project");checkout=field("checkout");Path active=active();Files.write(active.resolve("pixel.png"),new byte[]{(byte)137,80,78,71,0,1,-1});Files.createDirectories(active.resolve("empty/sub"));Files.writeString(active.resolve(".env"),"not exported");Files.createDirectories(active.resolve("dist"));Files.writeString(active.resolve("dist/generated.txt"),"generated-40");
        JSONObject attempt=(JSONObject)call("startFixtureAttempt",new Class<?>[0]);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");java.util.concurrent.atomic.AtomicReference<RunControl> live=field("live");control=live.get();var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,control,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,"managed_process_v1");JSONArray tools=new JSONArray();for(int i=0;i<port.tools().length();i++)tools.put(port.tools().getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,tools);
    }
    @After public void close()throws Exception {try{if(port!=null)port.close();}finally{if(original!=null)original.close();}}
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception {var f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(original);}
    private Object call(String name,Class<?>[] types,Object... args)throws Exception {var m=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(original,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}}
    private Path active(){return RuntimeEnvironment.getApplication().getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files");}
    private JSONObject proposal()throws Exception {String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");return port.execute("project_plan",new JSONObject().put("intent","freeze_package").put("expectedSourceDigest",digest).put("includeGeneratedRoots",true),control);}
    /** Exact production claim, stopping immediately before Host would record Local's receipt. */
    private JSONObject claim(JSONObject ref)throws Exception {JSONObject args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId","freeze_package"),full=tasks.projectArguments(taskId,run,epoch,args).getJSONObject("plan"),exact=ProjectDevelopment.exact(full,ProjectBuildPlan.step(full,"freeze_package"));String op=tasks.journal(taskId,run,epoch,control).beginOperation("project_execute",args);JSONObject binding=new JSONObject().put("taskId",taskId).put("runId",run).put("coordinatorEpoch",epoch).put("operationId",op),capability=local.projectCapabilityForHost(exact).put("nativeProjectPlan",new JSONObject().put("fullPlanStoredInOriginalTaskStore",true).put("planDigest",full.getString("planDigest")).put("step",ProjectPlanRecords.reviewStep(ProjectBuildPlan.step(full,"freeze_package"))).put("executionAuthorized",false));JSONObject approval=tasks.requireCapabilityApproval(taskId,run,"project_execute",args,capability);assertFalse(approval.optBoolean("approved"));tasks.decideCapabilityApproval(taskId,approval.getString("approvalId"),true);JSONObject claimed=tasks.claimProjectOperation(binding,exact,capability,control);assertTrue(claimed.getBoolean("fresh"));return new JSONObject().put("binding",binding).put("route",claimed.getJSONObject("projectExecution")).put("exact",exact).put("plan",full);}
    private JSONObject nativeEffect(JSONObject claimed)throws Exception {return local.executeProjectForHost(claimed.getJSONObject("exact"),claimed.getJSONObject("route"),control,()->{control.check();tasks.checkCapabilityCurrent(taskId,run,epoch,"project_execute");tasks.projectOperation(claimed.getJSONObject("binding"),claimed.getJSONObject("exact"));});}
    /** SQL metadata fixture only: these descriptors deliberately create no native Selection authority. */
    private JSONObject previewReferenceMetadata(JSONObject source)throws Exception {
        JSONObject ref=new JSONObject().put("referenceId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString())
            .put("documentGeneration",2L).put("previewId","a".repeat(64)).put("path","index.html").put("resourceSha256","b".repeat(64))
            .put("selectorHint","h1").put("text","40.00").put("htmlSnippet","<h1>40.00</h1>")
            .put("bounds",new JSONObject().put("x",1).put("y",2).put("width",80).put("height",24))
            .put("truncated",new JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false))
            .put("untrusted",true).put("selectorIsSourceLocation",false);
        for(String key:new String[]{"taskId","runId","epoch","conversationId","projectId","checkoutId","sourceDigest","sourceKind","artifactId","artifactSha256","packageOperationId"})ref.put(key,source.get(key));
        return dev.pocketengine.features.local.preview.PreviewElementRef.checked(ref);
    }
    private JSONObject settledPreviewSource()throws Exception {
        JSONObject claimed=claim(proposal()),receipt=nativeEffect(claimed);assertEquals("completed",receipt.getString("status"));
        tasks.recordProjectOperation(claimed.getJSONObject("binding"),claimed.getJSONObject("exact"),receipt);
        return tasks.ownerPackagePreviewSource(taskId,claimed.getJSONObject("binding").getString("operationId"));
    }
    @Test public void packagePreviewSourceRequiresSettledSqlReceiptEvenWhenCatalogAlreadyContainsBytes()throws Exception {
        JSONObject claimed=claim(proposal()),receipt=nativeEffect(claimed);String operation=claimed.getJSONObject("binding").getString("operationId");
        assertEquals("completed",receipt.getString("status"));assertEquals(1,workspace.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
        assertThrows(SecurityException.class,()->tasks.ownerPackagePreviewSource(taskId,operation));
        tasks.recordProjectOperation(claimed.getJSONObject("binding"),claimed.getJSONObject("exact"),receipt);
        JSONObject source=tasks.ownerPackagePreviewSource(taskId,operation);
        assertEquals(receipt.getJSONObject("packageArtifact").getString("id"),source.getString("artifactId"));
        assertEquals("fixed_workspace_project_artifact",source.getString("sourceKind"));assertFalse(source.has("previewId"));
        assertEquals(operation,source.getString("packageOperationId"));assertFalse(source.getBoolean("authorityAdded"));
    }
    @Test public void packagePreviewSourceKeepsProducingRunAfterOriginalTaskStartsNextRun()throws Exception {
        JSONObject source=settledPreviewSource();String producingRun=source.getString("runId");int producingEpoch=source.getInt("epoch");
        tasks.endAttempt(taskId,run,"awaiting_user","Original file operation settled; later run is a separate receiver",new JSONObject());
        RunControl nextControl=new RunControl();JSONObject next=tasks.startOriginalCapabilityAttempt(taskId,run,epoch,120,nextControl,"Explicit owner fixture continuation of the same scoped task");assertNotEquals(producingRun,next.getString("runId"));
        JSONObject reread=tasks.ownerPackagePreviewSource(taskId,source.getString("packageOperationId"));
        assertEquals(producingRun,reread.getString("runId"));assertEquals(producingEpoch,reread.getInt("epoch"));
        tasks.requireOwnerPreviewReference(previewReferenceMetadata(reread));
        JSONObject wrong=previewReferenceMetadata(reread).put("runId",next.getString("runId")).put("epoch",next.getInt("coordinatorEpoch"));
        assertThrows(SecurityException.class,()->tasks.requireOwnerPreviewReference(wrong));
        assertEquals(next.getString("runId"),tasks.get(taskId,false).getString("runId"));
    }
    @Test public void equalArchiveBytesCannotExchangeOriginalPackageOperationOrArtifact()throws Exception {
        JSONObject first=settledPreviewSource(),second=settledPreviewSource();
        assertEquals(first.getString("artifactSha256"),second.getString("artifactSha256"));
        assertNotEquals(first.getString("artifactId"),second.getString("artifactId"));
        JSONObject reference=previewReferenceMetadata(first);tasks.requireOwnerPreviewReference(reference);
        for(String key:new String[]{"artifactId","packageOperationId"}){
            JSONObject wrong=new JSONObject(reference.toString()).put(key,second.get(key));
            assertThrows(SecurityException.class,()->tasks.requireOwnerPreviewReference(wrong));
        }
        assertThrows(IllegalArgumentException.class,()->tasks.ownerPackagePreviewSource(UUID.randomUUID().toString(),first.getString("packageOperationId")));
        assertEquals(2,workspace.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
    }
    @Test public void packagePreviewSourceRequiresActualProducingRunEpochRow()throws Exception {
        JSONObject source=settledPreviewSource();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);
        var db=(android.database.sqlite.SQLiteDatabase)field.get(tasks);String[] identity={taskId,source.getString("runId")};
        synchronized(tasks){try{
            var changed=new android.content.ContentValues();changed.put("epoch",source.getInt("epoch")+1);assertEquals(1,db.update("runs",changed,"task_id=? AND run_id=?",identity));
            assertThrows(SecurityException.class,()->tasks.ownerPackagePreviewSource(taskId,source.getString("packageOperationId")));
        }finally{var originalEpoch=new android.content.ContentValues();originalEpoch.put("epoch",source.getInt("epoch"));assertEquals(1,db.update("runs",originalEpoch,"task_id=? AND run_id=?",identity));}}
        assertEquals(source.getInt("epoch"),tasks.ownerPackagePreviewSource(taskId,source.getString("packageOperationId")).getInt("epoch"));
    }
    @Test public void changedGeneratedAssetFailsBeforePublicationThenFreshExactReviewCanSaveBinaryPackage()throws Exception {
        JSONObject first=claim(proposal());String source=first.getJSONObject("exact").getString("sourceDigest");Files.writeString(active().resolve("dist/generated.txt"),"generated-45");assertEquals(source,ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest"));JSONObject failed=nativeEffect(first);assertEquals("failed",failed.getString("status"));assertFalse(failed.getBoolean("nativePublicationEntered"));assertTrue(failed.getBoolean("receiptReceived"));assertFalse(failed.has("packageArtifact"));tasks.recordProjectOperation(first.getJSONObject("binding"),first.getJSONObject("exact"),failed);assertFalse(tasks.hasUnresolved(taskId));assertEquals(0,workspace.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
        JSONObject second=claim(proposal()),saved=nativeEffect(second);
        JSONObject facts=new JSONObject().put("operationId",second.getJSONObject("binding").getString("operationId")).put("status",saved.optString("status")).put("receiptReceived",saved.opt("receiptReceived")).put("nativePublicationEntered",saved.opt("nativePublicationEntered")).put("processExitCode",saved.opt("processExitCode"));
        assertEquals(facts.toString(),"completed",saved.getString("status"));assertTrue(facts.toString(),saved.isNull("processExitCode"));
        tasks.recordProjectOperation(second.getJSONObject("binding"),second.getJSONObject("exact"),saved);
        JSONObject ref=port.execute("project_package",new JSONObject().put("executionId",second.getJSONObject("binding").getString("operationId")),control);
        assertEquals("Original operation must return the same catalog artifact; "+facts,saved.getJSONObject("packageArtifact").getString("id"),ref.getString("artifactId"));
        JSONObject actual=local.handle("project.package.get",new JSONObject().put("artifactId",ref.getString("artifactId")).put("sha256",ref.getString("archiveSha256")));
        JSONArray directoryRows=actual.getJSONObject("manifest").getJSONArray("directories");java.util.List<String> directories=new java.util.ArrayList<>();for(int i=0;i<directoryRows.length();i++)directories.add(directoryRows.getString(i));
        System.out.println("NEW_F010_SQL_DIRECTORY_FACTS original="+facts+" serialized="+directoryRows+" decoded="+directories+" previousSerializedSubstring="+directoryRows.toString().contains("empty/sub"));
        assertTrue("Exact decoded directory must survive the original codec/catalog: "+directories+"; "+facts,directories.contains("empty/sub"));
        JSONArray fileRows=actual.getJSONObject("manifest").getJSONArray("files");java.util.List<String> filePaths=new java.util.ArrayList<>();for(int i=0;i<fileRows.length();i++)filePaths.add(fileRows.getJSONObject(i).getString("path"));
        assertFalse("Private file must stay excluded from actual catalog file paths: "+filePaths,filePaths.contains(".env"));
        assertEquals("Exactly one package after failed pre-publication then explicit new review; "+facts,1,workspace.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
        System.out.println("NEW_F010_SQL generated-only drift beyond source fence -> actual native pre-publication failed receipt -> no package/no unknown -> new exact approval and native save -> original SQL/model package reference, binary/empty dirs/exclusions; not old six-test proof.");
    }
    @Test public void publicationReceiptLossOnlyQueriesOriginalCatalogAndTamperedNativeTupleCannotSettle()throws Exception {
        JSONObject claim=claim(proposal()),receipt=nativeEffect(claim),binding=claim.getJSONObject("binding"),exact=claim.getJSONObject("exact");assertEquals("completed",receipt.getString("status"));assertTrue(tasks.hasUnresolved(taskId));JSONObject wrong=new JSONObject(receipt.toString());wrong.getJSONObject("packageArtifact").put("sha256","f".repeat(64));tasks.recordProjectOperation(binding,exact,wrong);assertTrue(tasks.hasUnresolved(taskId));assertEquals("unknown",tasks.projectOperationReceipt(binding,exact).getString("state"));
        // Deliberately withhold the actual receipt at Local-return -> original Store-record boundary.
        // The next Host observation calls only findProjectPackage; it never enters the producer again.
        JSONObject found=host.operation(binding);assertNotNull(found.getJSONObject("nativeReceipt"));assertTrue(found.getJSONObject("nativeReceipt").getBoolean("reconciledFromOriginalCatalog"));assertFalse(tasks.hasUnresolved(taskId));JSONObject evidence=tasks.projectPackageEvidenceForOwner(taskId,binding.getString("operationId")),pin=evidence.getJSONObject("packageArtifact");WorkspaceModule cold=new WorkspaceModule(new dev.pocketengine.features.FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));JSONObject actual=cold.projectPackageMetadata(pin.getString("id"),pin.getString("sha256"));var verified=ProjectPackageDelivery.requireArtifact(evidence,actual);assertEquals(binding.getString("operationId"),verified.operationId);assertEquals(1,cold.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());assertEquals(pin.getString("id"),host.operation(binding).getJSONObject("nativeReceipt").getJSONObject("packageArtifact").getString("id"));assertEquals(1,cold.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
        System.out.println("NEW_F010_SQL actual catalog publication with deliberately withheld Store receipt -> tampered archive tuple stays unknown -> original Host read-only catalog reconcile -> cold catalog exact artifact and no second save; native file operation not OS process.");
    }
    @Test public void pendingPackagePlanOwnerReviewAllowsOriginalPrivateTaskAndRejectsAnyTeamOrigin()throws Exception {
        JSONObject reference=proposal(),args=new JSONObject().put("planRef",reference.getString("planRef")).put("planDigest",reference.getString("planDigest")).put("stepId","freeze_package"),full=tasks.projectArguments(taskId,run,epoch,args).getJSONObject("plan"),exact=ProjectDevelopment.exact(full,ProjectBuildPlan.step(full,"freeze_package"));
        var journal=tasks.journal(taskId,run,epoch,control);String operation=journal.beginOperation("project_execute",args);
        JSONObject capability=local.projectCapabilityForHost(exact).put("nativeProjectPlan",new JSONObject().put("fullPlanStoredInOriginalTaskStore",true).put("planDigest",full.getString("planDigest")).put("step",ProjectPlanRecords.reviewStep(ProjectBuildPlan.step(full,"freeze_package"))).put("executionAuthorized",false));
        JSONObject approval=tasks.requireCapabilityApproval(taskId,run,"project_execute",args,capability);assertFalse(approval.getBoolean("approved"));String approvalId=approval.getString("approvalId");
        // Original pending approval is definitely not dispatched. Finish only that original
        // read/approval intent as the real Runner does; no new grant or effect is synthesized.
        journal.finishOperation(operation,new JSONObject().put("ok",false).put("accepted",false).put("code","approval_required").put("status","not_dispatched").put("approvalId",approvalId));
        JSONObject allowed=tasks.projectPackagePlanForOwner(taskId,approvalId);assertEquals(full.getString("planDigest"),allowed.getString("planDigest"));assertEquals(taskId,allowed.getString("reviewTaskId"));assertEquals(0,workspace.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(tasks);String originalDocument;try(var row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{taskId})){assertTrue(row.moveToFirst());originalDocument=row.getString(0);}
        // Refusal-only input: an origin marker is NOT a native team association/grant.
        // A real typed team delivery is covered separately by C6's original S03 factory.
        synchronized(tasks){try{JSONObject rejectedOrigin=new JSONObject(originalDocument).put("origin",new JSONObject().put("kind","team_integration"));var values=new android.content.ContentValues();values.put("document",rejectedOrigin.toString());assertEquals(1,db.update("tasks",values,"task_id=?",new String[]{taskId}));SecurityException refused=assertThrows(SecurityException.class,()->tasks.projectPackagePlanForOwner(taskId,approvalId));assertEquals("请选择私人原开发任务",refused.getMessage());}
            finally{var values=new android.content.ContentValues();values.put("document",originalDocument);assertEquals(1,db.update("tasks",values,"task_id=?",new String[]{taskId}));}}
        assertEquals(full.getString("planDigest"),tasks.projectPackagePlanForOwner(taskId,approvalId).getString("planDigest"));assertFalse(tasks.hasUnresolved(taskId));assertFalse(approval.optBoolean("approved"));
        System.out.println("NEW_F010_PRIVATE_REVIEW original private pending plan readable; refusal-only team origin denied by identical ForOwner predicate; restoring original private row readable; no dispatch/artifact/grant/team authority synthesized. Actual C6 typed final delivery remains distinct.");
    }

}
