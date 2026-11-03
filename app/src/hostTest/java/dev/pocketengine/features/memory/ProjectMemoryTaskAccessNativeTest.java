package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
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
import static org.junit.Assert.*;

/** Actual original SQLite/Book namespace adapter. Native codec completion is not an HTTP claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectMemoryTaskAccessNativeTest {
    private TaskStore tasks;private FeatureHost host;private WorkspaceModule workspace;private LocalModule local;private MemoryModule memory;private ProjectMemoryNamespaces scopes;private ProjectMemoryNamespaces.Selection selected;private JSONObject checkout,formal;
    @Before public void setup()throws Exception{
        FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});tasks=new TaskStore(context.android);host=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);host.register(workspace);host.register(local);MemorySourceBridge sources=new MemorySourceBridge(host,tasks);sources.bindWorkspace(workspace);memory=new MemoryModule(context,sources.assertionResolver());host.register(memory);host.register(new dev.pocketengine.features.skills.SkillsModule(context,memory::resolveExperience));scopes=new NativeOwnerWorkflows(host,context,tasks,sources).projectMemoryForOwner();checkout=project("原项目");selected=choose(checkout);formal=save("项目规范：正式使用方案甲");memory.handle("save",new JSONObject().put("record",new JSONObject().put("text","项目规范：主人全局秘密，不得回流项目")));
    }
    @After public void close()throws Exception{host.close();var field=tasks.getClass().getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject project(String name)throws Exception{String id=workspace.handle("create",new JSONObject().put("name",name)).getString("id");workspace.handle("write",new JSONObject().put("projectId",id).put("path","notes.txt").put("content","项目规范的原始理由与文件原文").put("expectedVersion",0));return local.handle("prepare",new JSONObject().put("projectId",id));}
    private ProjectMemoryNamespaces.Selection choose(JSONObject checkout)throws Exception{JSONArray all=scopes.choicesForOwner();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(checkout.getString("id").equals(row.getString("checkoutId")))return scopes.selectForOwner(row.getString("checkoutId"),row.getString("projectId"),row.getString("revision"));}throw new AssertionError("missing checkout");}
    private JSONObject save(String text)throws Exception{return scopes.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("text",text))).getJSONObject("record");}
    private record Run(JSONObject task,RunControl control,TaskMemoryUse.TaskRef ref,MemoryHostFence fence){}
    private Run run(JSONObject chosen)throws Exception{
        JSONObject task=tasks.createCapabilityTask("项目规范任务","");task=tasks.startAttempt(task.getString("id"),"capabilities",8);RunControl control=new RunControl();String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);JSONObject source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",chosen.getString("id"))));tasks.bindDevelopmentContext(id,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",chosen.getString("projectId")).put("checkoutId",chosen.getString("id")).put("runtime","termux").put("initialSourceDigest",source.getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
        MemoryHostFence fence=new MemoryHostFence(){public void check()throws Exception{tasks.checkExtensionRun(id,run,epoch,control);}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}};return new Run(tasks.get(id,false),control,new TaskMemoryUse.TaskRef(id,run,epoch,"owner","private"),fence);
    }
    private TaskMemoryUse use(ProjectMemoryAccess access){TaskStoreMemoryReader reader=new TaskStoreMemoryReader(tasks,null,tasks::queryMemoryTasks);return memory.taskMemoryUse(reader,reader,access);}
    @Test public void nativeConsentRecallsOnlyProjectWhileTaskIdentityStaysPrivate()throws Exception{
        ProjectMemoryAccess.Consent consent=scopes.captureTaskConsentForOwner(selected);Run run=run(checkout);ProjectMemoryAccess access=consent.bindForRun(tasks,run.task.getString("id"),run.control);TaskMemoryUse use=use(access);TaskMemoryUse.Selection picked=use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence);JSONObject snapshot=picked.snapshot();assertEquals(1,snapshot.getJSONArray("records").length());assertTrue(snapshot.getString("context").contains("正式使用方案甲"));assertFalse(snapshot.getString("context").contains("主人全局秘密"));assertEquals("private",snapshot.getJSONObject("task").getString("spaceId"));assertEquals("project:"+checkout.getString("projectId"),picked.metadata().getJSONObject("memoryNamespace").getString("spaceId"));assertTrue(snapshot.getString("context").length()<=12000);
    }
    @Test public void frozenConsentDoesNotAuthorizeNewRecordsOrNewVersions()throws Exception{
        ProjectMemoryAccess.Consent consent=scopes.captureTaskConsentForOwner(selected);Run run=run(checkout);ProjectMemoryAccess access=consent.bindForRun(tasks,run.task.getString("id"),run.control);TaskMemoryUse use=use(access);TaskMemoryUse.Selection prior=use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence);save("项目规范：后加入内容未在原冻结范围");assertEquals(1,use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence).snapshot().getJSONArray("records").length());scopes.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("id",formal.getString("id")).put("revision",formal.getLong("revision")).put("text","项目规范：新版本")));assertThrows(IllegalStateException.class,()->use.validateTaskMemorySelection(owner(),prior,run.fence));assertEquals(0,use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence).snapshot().getJSONArray("records").length());
    }
    @Test public void nativeFormatThreeJournalIsProvenanceNotRestoredPrivateReadAuthority()throws Exception{
        Run run=run(checkout);ProjectMemoryAccess access=scopes.captureTaskConsentForOwner(selected).bindForRun(tasks,run.task.getString("id"),run.control);TaskMemoryUse use=use(access);TaskMemoryUse.Selection selected=use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence);String request=UUID.randomUUID().toString(),answer=UUID.randomUUID().toString();TaskMemoryJournal.Intent intent=TaskMemoryJournal.prepare(UUID.randomUUID().toString(),run.ref,request,selected);assertEquals(3,intent.arguments().getInt("format"));assertEquals("private",intent.arguments().getString("spaceId"));tasks.prepareMemoryReference(intent,run.control);tasks.completeMemoryReference(intent,answer,run.control);TaskMemoryUse.ReceiptRef receipt=new TaskMemoryUse.ReceiptRef(run.ref,request,answer);JSONObject proof=use.confirmTaskMemoryUse(owner(),selected,receipt,run.fence);assertEquals("provided_to_request",proof.getString("phase"));assertEquals("current",proof.getString("status"));assertFalse(proof.getBoolean("modelUseVerified"));assertEquals("private",tasks.get(run.task.getString("id"),false).getString("spaceId"));TaskStoreMemoryReader reader=new TaskStoreMemoryReader(tasks,null,tasks::queryMemoryTasks);TaskMemoryUse ordinary=memory.taskMemoryUse(reader,reader);assertThrows(SecurityException.class,()->ordinary.readTaskMemoryReceipt(owner(),receipt,run.fence));
    }
    @Test public void consentCannotMoveToAnotherProjectOrAnotherNativeRun()throws Exception{
        ProjectMemoryAccess.Consent consent=scopes.captureTaskConsentForOwner(selected);Run wrong=run(project("其他项目"));assertThrows(SecurityException.class,()->consent.bindForRun(tasks,wrong.task.getString("id"),wrong.control));Run first=run(checkout);consent.bindForRun(tasks,first.task.getString("id"),first.control);Run second=run(checkout);assertThrows(SecurityException.class,()->consent.bindForRun(tasks,second.task.getString("id"),second.control));
    }
    @Test public void managementPageCloseDoesNotRevokeAlreadyBoundRunButExplicitConsentWithdrawalDoes()throws Exception{
        ProjectMemoryAccess.Consent consent=scopes.captureTaskConsentForOwner(selected);Run run=run(checkout);ProjectMemoryAccess access=consent.bindForRun(tasks,run.task.getString("id"),run.control);scopes.clearForOwner();TaskMemoryUse use=use(access);TaskMemoryUse.Selection picked=use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence);assertEquals(1,picked.snapshot().getJSONArray("records").length());consent.close();assertThrows(SecurityException.class,()->use.validateTaskMemorySelection(owner(),picked,run.fence));assertFalse(run.control.isCancelled());
    }
    @Test public void originalRunCancellationRevokesProjectMemoryWithoutChangingTaskScope()throws Exception{
        Run run=run(checkout);ProjectMemoryAccess access=scopes.captureTaskConsentForOwner(selected).bindForRun(tasks,run.task.getString("id"),run.control);TaskMemoryUse use=use(access);TaskMemoryUse.Selection picked=use.prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence);run.control.cancel();assertThrows(SecurityException.class,()->use.validateTaskMemorySelection(owner(),picked,run.fence));assertEquals("private",tasks.get(run.task.getString("id"),false).getString("spaceId"));
    }
    @Test public void fileSourceKeepsOriginalPrivateLineageAndNamespaceForgetStopsOldSelection()throws Exception{
        JSONObject file=scopes.fileChoicesForOwner(selected).getJSONObject(0);JSONObject candidate=scopes.proposeFileForOwner(selected,"notes.txt",file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),0,4000).getJSONObject("candidate");JSONObject record=scopes.handleForOwner(selected,"acceptCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision"))).getJSONObject("record");Run run=run(checkout);ProjectMemoryAccess access=scopes.captureTaskConsentForOwner(selected).bindForRun(tasks,run.task.getString("id"),run.control);TaskMemoryUse use=use(access);TaskMemoryUse.Selection picked=use.prepareTaskRecall(owner(),run.ref,"原始理由",16,12000,run.fence);JSONObject ref=picked.metadata().getJSONArray("records").getJSONObject(0).getJSONArray("sourceRefs").getJSONObject(0);assertEquals("private",ref.getString("spaceId"));assertEquals(checkout.getString("projectId"),ref.getJSONObject("workspace").getString("projectId"));scopes.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")));assertThrows(IllegalStateException.class,()->use.validateTaskMemorySelection(owner(),picked,run.fence));assertEquals(0,use.prepareTaskRecall(owner(),run.ref,"原始理由",16,12000,run.fence).snapshot().getJSONArray("records").length());
    }
    @Test public void metadataCannotMintScopeAudienceOrConsent()throws Exception{
        ProjectMemoryAccess.Consent consent=scopes.captureTaskConsentForOwner(selected);JSONObject metadata=consent.metadata();assertFalse(metadata.getBoolean("restorablePermission"));assertThrows(SecurityException.class,()->MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId",metadata.getString("memorySpaceId"))));assertThrows(SecurityException.class,()->ReadAudience.checkedSources(new JSONArray(),"owner",metadata.getString("memorySpaceId")));Run run=run(checkout);ProjectMemoryAccess access=consent.bindForRun(tasks,run.task.getString("id"),run.control);access.close();assertThrows(SecurityException.class,()->use(access).prepareTaskRecall(owner(),run.ref,"项目规范",16,12000,run.fence));
    }
}
