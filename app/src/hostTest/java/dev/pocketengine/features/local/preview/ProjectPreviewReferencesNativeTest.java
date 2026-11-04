package dev.pocketengine.features.local.preview;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.assistant.OwnerSourceRequestProjection;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectBuildPlan;
import dev.pocketengine.features.local.project.ProjectDevelopment;
import dev.pocketengine.features.local.project.ProjectPlanRecords;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.features.local.runtime.RuntimeInvocation;
import dev.pocketengine.features.local.runtime.RuntimeReceipt;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Real original SQL/checkout/carrier/request hook. No model call, WebView gesture or SDK substitute. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectPreviewReferencesNativeTest {
    private static final String HTML="<!doctype html><button id='original'><span>Original element</span></button>";
    private static final class NoProcess implements ProjectRuntime {
        public JSONObject describe()throws Exception{return new JSONObject().put("runtimeId","preview_read_fixture").put("available",false).put("canExecute",false).put("phases",new JSONArray());}
        public RuntimeReceipt dispatch(RuntimeInvocation ignored){throw new AssertionError("Opening a registered preview must not launch a process");}
        public RuntimeReceipt query(RuntimeExecutionRef ignored){throw new AssertionError("Preview reference validation must not query a process");}
        public RuntimeReceipt requestCancel(RuntimeExecutionRef ignored){throw new AssertionError("Preview reference validation must not cancel a process");}
    }
    private static JSONObject observation()throws Exception{return new JSONObject().put("selectorHint","button#original > span").put("text","Original element").put("htmlSnippet","<span>Original element</span>")
            .put("bounds",new JSONObject().put("x",4).put("y",12).put("width",120).put("height",24))
            .put("truncated",new JSONObject().put("selectorHint",false).put("text",false).put("htmlSnippet",false));}
    private static ModelConfig config(){return new ModelConfig("http://127.0.0.1","","fixture-model",false,8,10);}
    private static final class F implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final WorkspaceModule workspace;final LocalModule local;
        final ProjectPreviewReferences references;final ProjectPreviewBundle.Verified bundle;final ProjectPreviewDocument document;
        final String conversation=UUID.randomUUID().toString(),task,run,project,checkout;final int epoch;final RunControl control=new RunControl();final Path sourceFile;
        final AssistantModule assistant;final MemoryModule memory;final ConversationRepository repository;
        final java.util.List<ProjectPreviewDocument> packageDocuments=new java.util.ArrayList<>();
        F()throws Exception{
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
            workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace,new NoProcess());
            project=workspace.handle("create",new JSONObject().put("name","Original preview source")).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","index.html").put("expectedVersion",0).put("content",HTML));
            checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");sourceFile=android.getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files/index.html");
            String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");
            task=tasks.createCapabilityTask("Inspect the original preview, not an edit authorization",conversation).getString("id");tasks.initializeExecutionScope(task,20);
            JSONObject attempt=tasks.startAttempt(task,"capabilities",20);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");control.bindHostRun(task,run,epoch);
            tasks.bindDevelopmentContext(task,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",digest).put("clientMessageId",UUID.randomUUID().toString()));
            tasks.authorizeTools(task,run,new JSONArray().put("project_preview").put("project_read_file").put("project_plan").put("project_execute"));
            JSONObject preview=local.handle("project.preview",new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",digest).put("entryPath","index.html"));
            tasks.recordDevelopmentPreview(task,run,epoch,control,preview);bundle=local.projectPreviewForOwner(preview.getString("previewId"),checkout);
            document=new ProjectPreviewDocument(bundle);document.loading();document.loaded(bundle.entryUrl);references=new ProjectPreviewReferences(tasks,local);
            AtomicReference<AssistantModule> actual=new AtomicReference<>();memory=new MemoryModule(context,(identity,kind,source)->actual.get().resolveSource(identity,source));
            assistant=new AssistantModule(context,memory,null,tasks);actual.set(assistant);assistant.setPreviewReferences(references);repository=tasks.conversationRepository();
            repository.transaction(()->{repository.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original project").put("messages",new JSONArray()));return null;});
        }
        JSONObject reference()throws Exception{long ticket=document.beginSelection();var selection=document.select(document.generation(),ticket,bundle.entryUrl,observation());document.endSelection();return references.register(task,selection);}
        /** Real approved file operation and original SQL receipt; NoProcess rejects any process launch. */
        JSONObject freezePackage(String mode,String entry)throws Exception {
            String digest=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");
            JSONObject input=new JSONObject().put("checkoutId",checkout).put("runtimeId","termux").put("intent","freeze_package").put("expectedSourceDigest",digest).put("packageMode",mode);
            if(entry!=null)input.put("entryPath",entry);
            JSONObject plan=local.handle("project.plan",input),ref=tasks.recordProjectPlan(task,run,epoch,control,plan);
            JSONObject args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId","freeze_package");
            JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,"freeze_package"));
            String operation=tasks.journal(task,run,epoch,control).beginOperation("project_execute",args);
            JSONObject binding=new JSONObject().put("taskId",task).put("runId",run).put("coordinatorEpoch",epoch).put("operationId",operation);
            JSONObject capability=local.projectCapabilityForHost(exact).put("nativeProjectPlan",new JSONObject().put("fullPlanStoredInOriginalTaskStore",true)
                .put("planDigest",plan.getString("planDigest")).put("step",ProjectPlanRecords.reviewStep(ProjectBuildPlan.step(plan,"freeze_package"))).put("executionAuthorized",false));
            JSONObject approval=tasks.requireCapabilityApproval(task,run,"project_execute",args,capability);assertFalse(approval.getBoolean("approved"));
            tasks.decideCapabilityApproval(task,approval.getString("approvalId"),true);
            JSONObject claimed=tasks.claimProjectOperation(binding,exact,capability,control);assertTrue(claimed.getBoolean("fresh"));
            JSONObject receipt=local.executeProjectForHost(exact,claimed.getJSONObject("projectExecution"),control,()->{control.check();tasks.checkCapabilityCurrent(task,run,epoch,"project_execute");tasks.projectOperation(binding,exact);});
            assertEquals("completed",receipt.getString("status"));assertTrue(receipt.getBoolean("nativeFileOperation"));assertTrue(receipt.isNull("processExitCode"));
            tasks.recordProjectOperation(binding,exact,receipt);assertFalse(tasks.hasUnresolved(task));return tasks.ownerPackagePreviewSource(task,operation);
        }
        ProjectPreviewDocument packageDocument(JSONObject evidence,String entry)throws Exception {
            ProjectPreviewBundle.Verified original=local.openProjectPackagePreviewForOwner(evidence,entry);
            ProjectPreviewDocument result=new ProjectPreviewDocument(original);result.loading();result.loaded(original.entryUrl);packageDocuments.add(result);return result;
        }
        ProjectPreviewDocument.Selection selection(ProjectPreviewDocument selected)throws Exception {
            long ticket=selected.beginSelection();var result=selected.select(selected.generation(),ticket,selected.bundle().entryUrl,observation());selected.endSelection();return result;
        }
        JSONObject packageReference(JSONObject evidence,ProjectPreviewDocument selected)throws Exception{return references.register(task,evidence.getString("packageOperationId"),selection(selected));}
        JSONObject target()throws Exception{return new JSONObject().put("taskId",task).put("runId",run).put("epoch",epoch);}
        JSONObject carrier(JSONArray refs)throws Exception{
            String id=UUID.randomUUID().toString();JSONObject message=new JSONObject().put("id",id).put("revision",1).put("role","user").put("content","Please inspect the selected element; no edit is authorized").put("ownerPreviewRefs",PreviewElementRef.checked(refs));
            repository.transaction(()->{repository.saveSource(new SourceRef("message",id,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));repository.saveMessage(conversation,message);return null;});
            return new JSONObject().put("role","user").put("content",message.getString("content")).put("ownerMessageId",id).put("ownerMessageRevision",1).put("ownerPreviewRefs",PreviewElementRef.checked(refs));
        }
        OwnerSourceRequestProjection projection(){return new OwnerSourceRequestProjection(assistant,control,conversation,null,(carriers,parts,original)->tasks.recordOwnerSourceExposure(task,run,epoch,original,carriers,parts));}
        @Override public void close()throws Exception{control.cancel();references.close();document.release();packageDocuments.forEach(ProjectPreviewDocument::release);assistant.close();memory.close();local.close();workspace.close();db.close();}
    }
    @Test public void modeOffKeepsOriginalReferenceAndRepeatedAdmissionDoesNotCreateOperations()throws Exception{
        try(F f=new F()){
            JSONArray refs=new JSONArray().put(f.reference());String scope=f.tasks.get(f.task,false).getJSONObject("executionScope").toString();
            assertTrue(f.references.available(f.conversation,refs));assertEquals(1,f.references.admit(f.conversation,f.target(),refs,f.control).length());
            f.references.requireCurrent(f.conversation,refs,f.control);f.references.requireCurrent(f.conversation,refs,f.control);
            assertEquals(0,f.tasks.get(f.task,false).getJSONArray("operations").length());assertEquals(scope,f.tasks.get(f.task,false).getJSONObject("executionScope").toString());
            f.document.loading();f.document.loaded(f.bundle.entryUrl);assertFalse(f.references.available(f.conversation,refs));assertThrows(SecurityException.class,()->f.references.requireSourcesCurrent(f.conversation,refs));
        }
    }
    @Test public void coldDescriptorsAndModifiedBodiesCannotRecreateNativeSelection()throws Exception{
        try(F f=new F()){
            JSONArray refs=new JSONArray().put(f.reference());try(ProjectPreviewReferences cold=new ProjectPreviewReferences(f.tasks,f.local)){
                assertFalse(cold.available(f.conversation,new JSONArray(refs.toString())));assertThrows(SecurityException.class,()->cold.requireSourcesCurrent(f.conversation,refs));
            }
            JSONObject forged=new JSONObject(refs.getJSONObject(0).toString()).put("htmlSnippet","<button>different</button>");
            assertThrows(SecurityException.class,()->f.references.requireSourcesCurrent(f.conversation,new JSONArray().put(forged)));
            assertThrows(SecurityException.class,()->f.references.admit(f.conversation,new JSONObject(),refs,f.control));
            assertThrows(SecurityException.class,()->f.references.admit(f.conversation,f.target().put("epoch",1.0),refs,f.control));
        }
    }
    @Test public void originalMailboxPreservesCanonicalLongEpochAndDeduplicatesTheSameInput()throws Exception{
        try(F f=new F()){
            JSONArray refs=PreviewElementRef.checked(new JSONArray().put(f.reference()));assertTrue(refs.getJSONObject(0).get("epoch") instanceof Long);
            f.tasks.openOwnerInputMailbox(f.task,f.run,f.epoch,f.conversation,f.control);
            String before=dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(f.tasks.get(f.task,false).getJSONObject("executionScope"));
            JSONObject message=new JSONObject().put("id",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("revision",1)
                    .put("content","Inspect the original selected element").put("inputMode","queue").put("attachments",new JSONArray()).put("ownerPreviewRefs",refs);
            JSONObject accepted=f.tasks.appendOwnerInput(f.task,f.run,f.epoch,f.conversation,message,"queue",f.control);
            JSONObject duplicate=f.tasks.appendOwnerInput(f.task,f.run,f.epoch,f.conversation,new JSONObject(message.toString()),"queue",f.control);
            assertFalse(accepted.getBoolean("duplicate"));assertTrue(duplicate.getBoolean("duplicate"));assertEquals(accepted.getString("messageId"),duplicate.getString("messageId"));
            JSONObject actual=f.tasks.get(f.task,false);assertEquals(1,actual.getJSONArray("ownerInputs").length());
            assertEquals(dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",refs)),dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(new JSONObject().put("refs",actual.getJSONArray("ownerInputs").getJSONObject(0).getJSONArray("ownerPreviewRefs"))));
            assertEquals(before,dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(actual.getJSONObject("executionScope")));assertEquals(0,actual.getJSONArray("operations").length());
        }
    }
    @Test public void actualSourceChangeRetiresRefsEvenIfBytesAreLaterRestored()throws Exception{
        try(F f=new F()){
            JSONArray refs=new JSONArray().put(f.reference());Files.writeString(f.sourceFile,HTML+"\nchanged source");
            assertTrue("metadata availability is deliberately not a source hash proof",f.references.available(f.conversation,refs));
            assertThrows(SecurityException.class,()->f.references.requireSourcesCurrent(f.conversation,refs));Files.writeString(f.sourceFile,HTML);
            assertFalse(f.references.available(f.conversation,refs));assertThrows(SecurityException.class,()->f.references.requireSourcesCurrent(f.conversation,refs));
        }
    }
    @Test public void originalRecipientGrantAndRunAreRequiredWithoutSourceIoUnderSqlLock()throws Exception{
        try(F f=new F()){
            JSONArray refs=new JSONArray().put(f.reference());
            synchronized(f.tasks){IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.references.requireSourcesCurrent(f.conversation,refs));assertEquals("preview_source_check_requires_unlocked_tasks",failure.getMessage());}
            assertThrows(SecurityException.class,()->f.references.admit("another",f.target(),refs,f.control));
            assertThrows(SecurityException.class,()->f.references.admit(f.conversation,f.target().put("epoch",f.epoch+1),refs,f.control));
            f.control.pause();assertThrows(SecurityException.class,()->f.references.requireCurrent(f.conversation,refs,f.control));f.control.resume();
            f.tasks.authorizeTools(f.task,f.run,new JSONArray().put("project_preview"));assertThrows(SecurityException.class,()->f.references.requireCurrent(f.conversation,refs,f.control));
        }
    }
    @Test public void trueLaterReceiverRunDoesNotReplaceTheProducingPreviewRun()throws Exception{
        try(F f=new F()){
            JSONArray refs=new JSONArray().put(f.reference());f.tasks.endAttempt(f.task,f.run,"awaiting_user","explicit fixture continuation",new JSONObject());
            assertThrows(SecurityException.class,()->f.references.admit(f.conversation,f.target(),refs,f.control));
            RunControl next=new RunControl();JSONObject attempt=f.tasks.startOriginalCapabilityAttempt(f.task,f.run,f.epoch,20,next,"");
            try{f.tasks.authorizeTools(f.task,attempt.getString("runId"),new JSONArray().put("project_preview").put("project_read_file"));f.references.requireCurrent(f.conversation,refs,next);assertEquals(f.run,refs.getJSONObject(0).getString("runId"));assertNotEquals(f.run,attempt.getString("runId"));}finally{next.cancel();}
        }
    }
    @Test public void realProjectionRejectsSourceChangesBetweenProjectAndPhysicalPrepared()throws Exception{
        try(F f=new F();OwnerSourceRequestProjection projection=f.projection()){
            JSONArray nativeRows=new JSONArray().put(f.carrier(new JSONArray().put(f.reference())));JSONArray wire=projection.project(nativeRows,config(),f.control);
            assertTrue(wire.toString().contains("Original element"));assertFalse(wire.getJSONObject(0).has("ownerPreviewRefs"));
            projection.derivedPrepared(nativeRows,f.control);
            Files.writeString(f.sourceFile,HTML+"\nchanged before physical request");
            assertThrows(Exception.class,()->projection.derivedPrepared(nativeRows,f.control));
            assertThrows(Exception.class,()->projection.prepared(nativeRows,wire,config(),f.control));
        }
    }
    @Test public void realProjectionRejectsReloadAndGrantRevocationBeforePhysicalPrepared()throws Exception{
        try(F f=new F();OwnerSourceRequestProjection projection=f.projection()){
            JSONArray rows=new JSONArray().put(f.carrier(new JSONArray().put(f.reference())));JSONArray wire=projection.project(rows,config(),f.control);
            f.document.loading();f.document.loaded(f.bundle.entryUrl);
            assertThrows(Exception.class,()->projection.derivedPrepared(rows,f.control));
            assertThrows(Exception.class,()->projection.prepared(rows,wire,config(),f.control));
        }
        try(F f=new F();OwnerSourceRequestProjection projection=f.projection()){
            JSONArray rows=new JSONArray().put(f.carrier(new JSONArray().put(f.reference())));JSONArray wire=projection.project(rows,config(),f.control);
            f.tasks.authorizeTools(f.task,f.run,new JSONArray().put("project_preview"));
            assertThrows(Exception.class,()->projection.derivedPrepared(rows,f.control));
            assertThrows(Exception.class,()->projection.prepared(rows,wire,config(),f.control));
        }
    }
    @Test public void fixedPackageKeepsOriginalOperationAndBothPhysicalGatesWithoutAdditionalGrants()throws Exception{
        try(F f=new F();OwnerSourceRequestProjection projection=f.projection()){
            JSONObject source=f.freezePackage("source_project",null);ProjectPreviewDocument document=f.packageDocument(source,"index.html");
            String before=dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(f.tasks.get(f.task,false).getJSONObject("executionScope"));int operations=f.tasks.get(f.task,false).getJSONArray("operations").length();
            JSONObject reference=f.packageReference(source,document);assertEquals(24,reference.length());assertEquals(source.getString("packageOperationId"),reference.getString("packageOperationId"));assertEquals(f.run,reference.getString("runId"));
            JSONArray refs=new JSONArray().put(reference);f.references.admit(f.conversation,f.target(),refs,f.control);f.references.requireCurrent(f.conversation,refs,f.control);
            f.tasks.openOwnerInputMailbox(f.task,f.run,f.epoch,f.conversation,f.control);
            JSONObject message=new JSONObject().put("id",UUID.randomUUID().toString()).put("clientMessageId",UUID.randomUUID().toString()).put("revision",1)
                .put("content","Inspect this original package element").put("inputMode","queue").put("attachments",new JSONArray()).put("ownerPreviewRefs",refs);
            assertFalse(f.tasks.appendOwnerInput(f.task,f.run,f.epoch,f.conversation,message,"queue",f.control).getBoolean("duplicate"));
            assertTrue(f.tasks.appendOwnerInput(f.task,f.run,f.epoch,f.conversation,new JSONObject(message.toString()),"queue",f.control).getBoolean("duplicate"));
            JSONObject stored=f.tasks.get(f.task,false).getJSONArray("ownerInputs").getJSONObject(0).getJSONArray("ownerPreviewRefs").getJSONObject(0);assertEquals(24,stored.length());assertEquals(reference.getString("artifactId"),stored.getString("artifactId"));
            JSONArray rows=new JSONArray().put(f.carrier(refs)),wire=projection.project(rows,config(),f.control);projection.prepared(rows,wire,config(),f.control);projection.derivedPrepared(rows,f.control);
            assertEquals(operations,f.tasks.get(f.task,false).getJSONArray("operations").length());assertEquals(before,dev.pocketengine.features.memory.TaskMemoryTrace.rawFingerprint(f.tasks.get(f.task,false).getJSONObject("executionScope")));
            synchronized(f.tasks){assertThrows(IllegalStateException.class,()->f.references.requireSourcesCurrent(f.conversation,refs));assertThrows(IllegalStateException.class,()->f.references.register(f.task,source.getString("packageOperationId"),f.selection(document)));}
        }
    }
    @Test public void fixedPackageCannotBorrowActiveSelectionOrAnotherSameBytePackageOperation()throws Exception{
        try(F f=new F()){
            JSONObject first=f.freezePackage("source_project",null),second=f.freezePackage("source_project",null);assertEquals(first.getString("artifactSha256"),second.getString("artifactSha256"));assertNotEquals(first.getString("artifactId"),second.getString("artifactId"));
            ProjectPreviewDocument document=f.packageDocument(first,"index.html");ProjectPreviewDocument.Selection selected=f.selection(document);
            assertThrows(SecurityException.class,()->f.references.register(f.task,selected));
            assertThrows(SecurityException.class,()->f.references.register(f.task,second.getString("packageOperationId"),selected));
            assertThrows(SecurityException.class,()->f.references.register(f.task,first.getString("packageOperationId"),f.selection(f.document)));
            JSONObject original=f.references.register(f.task,first.getString("packageOperationId"),selected);
            JSONObject forged=new JSONObject(original.toString()).put("packageOperationId",second.getString("packageOperationId"));
            assertThrows(SecurityException.class,()->f.references.requireSourcesCurrent(f.conversation,new JSONArray().put(forged)));
            try(ProjectPreviewReferences cold=new ProjectPreviewReferences(f.tasks,f.local)){assertThrows(SecurityException.class,()->cold.requireSourcesCurrent(f.conversation,new JSONArray().put(original)));}
        }
    }
    @Test public void changedSourceKeepsHistoricalPackageBytesButRefusesSelectionAndMainAndSummary()throws Exception{
        try(F f=new F();OwnerSourceRequestProjection projection=f.projection()){
            JSONObject source=f.freezePackage("source_project",null);ProjectPreviewDocument document=f.packageDocument(source,"index.html");
            JSONArray refs=new JSONArray().put(f.packageReference(source,document)),rows=new JSONArray().put(f.carrier(refs)),wire=projection.project(rows,config(),f.control);
            Files.writeString(f.sourceFile,HTML+"\ncurrent source changed");
            ProjectPreviewBundle.Verified reopened=f.local.openProjectPackagePreviewForOwner(source,"index.html");assertSame(document.bundle(),reopened);assertEquals(HTML,new String(reopened.resource(reopened.entryUrl).bytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(SecurityException.class,()->f.local.requireProjectPackagePreviewCurrent(reopened,source));assertThrows(SecurityException.class,()->f.packageReference(source,document));
            assertThrows(Exception.class,()->projection.prepared(rows,wire,config(),f.control));assertThrows(Exception.class,()->projection.derivedPrepared(rows,f.control));
            Files.writeString(f.sourceFile,HTML);assertFalse(f.references.available(f.conversation,refs));
        }
    }
    @Test public void damagedOrMissingOriginalPackageNeverFallsBackToCurrentFiles()throws Exception{
        try(F f=new F()){
            JSONObject source=f.freezePackage("source_project",null);ProjectPreviewDocument document=f.packageDocument(source,"index.html");JSONArray refs=new JSONArray().put(f.packageReference(source,document));
            Path archive=f.android.getFilesDir().toPath().resolve("workspace-artifacts/"+source.getString("artifactId")+".project.zip");byte[] original=Files.readAllBytes(archive);byte[] changed=original.clone();changed[changed.length/2]^=1;Files.write(archive,changed);
            assertThrows(Exception.class,()->f.local.openProjectPackagePreviewForOwner(source,"index.html"));assertThrows(Exception.class,()->f.references.requireSourcesCurrent(f.conversation,refs));
            Files.write(archive,original);assertFalse(f.references.available(f.conversation,refs));Files.delete(archive);
            assertThrows(Exception.class,()->f.local.inspectProjectPackagePreviewForOwner(source));assertEquals(HTML,Files.readString(f.sourceFile));
        }
    }
    @Test public void packageChoicesAreExactManifestMembersAndEndedTaskNeverGetsANewReceiver()throws Exception{
        try(F f=new F()){
            Files.writeString(f.sourceFile.resolveSibling("second.html"),"<!doctype html><p>Second</p>");JSONObject source=f.freezePackage("source_project",null);
            JSONObject choices=f.local.inspectProjectPackagePreviewForOwner(source);assertEquals("package_preview_choices",choices.getString("kind"));assertEquals("",choices.getString("entryPath"));assertEquals(2,choices.getJSONArray("entryChoices").length());assertFalse(choices.has("sessionId"));assertFalse(choices.has("ready"));
            assertThrows(Exception.class,()->f.local.openProjectPackagePreviewForOwner(source,""));assertThrows(Exception.class,()->f.local.openProjectPackagePreviewForOwner(source,"missing.html"));
            JSONObject web=f.freezePackage("web_output","index.html"),webChoices=f.local.inspectProjectPackagePreviewForOwner(web);assertEquals("index.html",webChoices.getString("entryPath"));assertEquals(1,webChoices.getJSONArray("entryChoices").length());assertThrows(Exception.class,()->f.local.openProjectPackagePreviewForOwner(web,"second.html"));
            ProjectPreviewDocument document=f.packageDocument(source,"index.html");f.tasks.endAttempt(f.task,f.run,"completed","original task ended",new JSONObject());JSONArray refs=new JSONArray().put(f.packageReference(source,document));
            assertThrows(SecurityException.class,()->f.references.admit(f.conversation,f.target(),refs,f.control));assertEquals("completed",f.tasks.get(f.task,false).getString("status"));assertEquals(f.run,f.tasks.get(f.task,false).getString("runId"));
        }
        try(F f=new F()){
            Files.delete(f.sourceFile);Files.writeString(f.sourceFile.resolveSibling("notes.txt"),"No HTML in this actual package");JSONObject source=f.freezePackage("source_project",null);
            assertEquals(0,f.local.inspectProjectPackagePreviewForOwner(source).getJSONArray("entryChoices").length());assertThrows(Exception.class,()->f.local.openProjectPackagePreviewForOwner(source,"index.html"));
        }
    }
}
