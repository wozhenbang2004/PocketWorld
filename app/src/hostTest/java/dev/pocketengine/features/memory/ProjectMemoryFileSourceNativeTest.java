package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Original FeatureHost/Bridge/VWS/SQLite cleanup, not a new source authority or Task context. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectMemoryFileSourceNativeTest {
    private FeatureContext context;private TaskStore tasks;private FeatureHost host;private WorkspaceModule workspace;private LocalModule local;private MemorySourceBridge bridge;private MemoryModule memory;private ProjectMemoryNamespaces spaces;
    private JSONObject checkout;private ProjectMemoryNamespaces.Selection selected;
    private static final String ORIGINAL="真实文件原文：项目采用方案甲，因为约束不同。";
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Before public void setup()throws Exception{
        context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});tasks=new TaskStore(context.android);host=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);host.register(workspace);host.register(local);bridge=new MemorySourceBridge(host,tasks);bridge.bindWorkspace(workspace);memory=new MemoryModule(context,bridge.assertionResolver());host.register(memory);host.register(new dev.pocketengine.features.skills.SkillsModule(context,memory::resolveExperience));spaces=new NativeOwnerWorkflows(host,context,tasks,bridge).projectMemoryForOwner();checkout=create("真实项目",ORIGINAL);selected=select(spaces,checkout);
    }
    @After public void close()throws Exception{host.close();var field=tasks.getClass().getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    private JSONObject create(String name,String text)throws Exception{
        String project=workspace.handle("create",new JSONObject().put("name",name)).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("content",text).put("expectedVersion",0));return local.handle("prepare",new JSONObject().put("projectId",project));
    }
    private ProjectMemoryNamespaces.Selection select(ProjectMemoryNamespaces target,JSONObject checkout)throws Exception{
        JSONArray all=target.choicesForOwner();for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(checkout.getString("id").equals(row.getString("checkoutId")))return target.selectForOwner(row.getString("checkoutId"),row.getString("projectId"),row.getString("revision"));}throw new AssertionError("missing checkout");
    }
    private JSONObject fileChoice()throws Exception{return spaces.fileChoicesForOwner(selected).getJSONObject(0);}
    private JSONObject propose(JSONObject file)throws Exception{return spaces.proposeFileForOwner(selected,file.getString("path"),file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),0,4000);}
    private JSONObject accept(JSONObject candidate)throws Exception{return spaces.handleForOwner(selected,"acceptCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision"))).getJSONObject("record");}
    private JSONObject global(SourceRef source)throws Exception{
        JSONObject draft=new JSONObject().put("text",ORIGINAL).put("layer","episodic").put("reason","主人明确选择原文件").put("applicability","").put("confidence",0.5);JSONObject candidate=memory.proposeLearning(owner(),draft,source).getJSONObject("candidate");return memory.acceptCandidate(owner(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");
    }
    private File bookFile(){return new File(context.android.getFilesDir(),"memory/records.json");}
    private JSONObject disk()throws Exception{return new JSONObject(new String(Files.readAllBytes(bookFile().toPath()),StandardCharsets.UTF_8));}
    @Test public void realNativeFileSelectionKeepsOriginalPrivateSourceRefAndNeedsReview()throws Exception{
        JSONObject result=propose(fileChoice()),candidate=result.getJSONObject("candidate");SourceRef origin=SourceRef.fromJson(result.getJSONObject("sourceRef"));assertEquals("owner",origin.ownerId);assertEquals("private",origin.spaceId);assertEquals(checkout.getString("projectId"),origin.workspace.projectId);assertEquals(ORIGINAL.length(),origin.workspace.chars);assertEquals(ORIGINAL,candidate.getString("text"));assertEquals("pending",candidate.getString("status"));assertFalse(result.getBoolean("formalMemorySaved"));assertFalse(result.getBoolean("modelCalled"));assertEquals(0,spaces.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").length());
        JSONObject formal=accept(candidate);assertEquals(origin.toJson().toString(),formal.getJSONArray("sourceRefs").getJSONObject(0).toString());assertEquals("project:"+checkout.getString("projectId"),formal.getString("spaceId"));assertEquals(0,memory.handle("list",new JSONObject().put("scope",owner())).getJSONArray("records").length());assertTrue(spaces.handleForOwner(selected,"snapshot",new JSONObject()).getBoolean("taskContextSupported"));
    }
    @Test public void sourceVersionChangesRetireOnlyItsDerivedTextNotTheNamespace()throws Exception{
        JSONObject file=fileChoice(),candidate=propose(file).getJSONObject("candidate");accept(candidate);workspace.handle("write",new JSONObject().put("projectId",checkout.getString("projectId")).put("path","notes.txt").put("content","新原文：现在改方案乙").put("expectedVersion",1));
        assertEquals(0,spaces.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").length());JSONObject stale=spaces.handleForOwner(selected,"getCandidate",new JSONObject().put("id",candidate.getString("id"))).getJSONObject("candidate");assertEquals("invalidated",stale.getString("status"));assertFalse(stale.has("text"));assertTrue(selected.metadata().getBoolean("selectionCurrent"));assertThrows(SecurityException.class,()->propose(file));
        JSONObject fresh=propose(fileChoice()).getJSONObject("candidate");assertEquals(2,SourceRef.fromJson(fresh.getJSONArray("sourceRefs").getJSONObject(0)).revision);assertEquals("新原文：现在改方案乙",accept(fresh).getString("text"));
    }
    @Test public void namespaceForgetFencesUsageWithoutForgettingOwnerGlobalSource()throws Exception{
        JSONObject result=propose(fileChoice());SourceRef original=SourceRef.fromJson(result.getJSONObject("sourceRef"));JSONObject privateRecord=global(original),projectRecord=accept(result.getJSONObject("candidate"));spaces.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",projectRecord.getString("id")).put("expectedRevision",projectRecord.getLong("revision")));
        assertEquals(privateRecord.getString("id"),memory.handle("get",new JSONObject().put("scope",owner()).put("id",privateRecord.getString("id"))).getJSONObject("record").getString("id"));assertFalse(memory.isSourceForgotten(owner(),original));assertEquals(0,spaces.handleForOwner(selected,"search",new JSONObject().put("query","方案甲")).getJSONArray("records").length());assertThrows(IllegalStateException.class,()->propose(fileChoice()));
        JSONObject stored=disk();assertEquals(1,stored.getJSONArray("namespaceSourceFences").length());JSONObject fence=stored.getJSONArray("namespaceSourceFences").getJSONObject(0);assertEquals(original.toJson().toString(),fence.getJSONObject("sourceRef").toString());assertEquals(0,stored.getJSONArray("sources").getJSONObject(0).getLong("forgottenThrough"));
    }
    @Test public void originalGlobalForgetBlocksAndErasesAllProjectUsesWithoutExpandingTaskScope()throws Exception{
        JSONObject result=propose(fileChoice());SourceRef original=SourceRef.fromJson(result.getJSONObject("sourceRef"));JSONObject privateRecord=global(original);accept(result.getJSONObject("candidate"));JSONObject forgotten=bridge.forgetReviewed(owner(),privateRecord.getString("id"),privateRecord.getLong("revision"));
        assertTrue(memory.isSourceForgotten(owner(),original));assertEquals(0,spaces.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").length());assertThrows(IllegalStateException.class,()->propose(fileChoice()));assertFalse(disk().toString().contains(ORIGINAL));
        JSONArray refs=forgotten.getJSONArray("removedRefs");assertEquals(2,refs.length());int privateRows=0,projectRows=0;for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if("private".equals(ref.getString("spaceId")))privateRows++;else if(ref.getString("spaceId").startsWith("project:"))projectRows++;}assertEquals(1,privateRows);assertEquals(1,projectRows);assertTrue(spaces.handleForOwner(selected,"snapshot",new JSONObject()).getBoolean("taskContextSupported"));
    }
    @Test public void realVwsDeletionRetiresProjectCandidatesAndFormalBodiesThroughOriginalBridge()throws Exception{
        JSONObject candidate=propose(fileChoice()).getJSONObject("candidate");accept(candidate);var snapshot=workspace.fileSnapshotForHost(checkout.getString("projectId"),"notes.txt",1);workspace.deleteForHost(snapshot);
        assertEquals(0,spaces.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").length());assertEquals("invalidated",spaces.handleForOwner(selected,"getCandidate",new JSONObject().put("id",candidate.getString("id"))).getJSONObject("candidate").getString("status"));assertFalse(disk().toString().contains(ORIGINAL));assertTrue(selected.metadata().getBoolean("selectionCurrent"));
    }
    @Test public void unrelatedProjectSourceCannotBorrowTheCurrentProjectSelection()throws Exception{
        JSONObject other=create("其他项目","别处原文");var source=workspace.fileSnapshotForHost(other.getString("projectId"),"notes.txt",1);assertThrows(SecurityException.class,()->spaces.proposeFileForOwner(selected,"notes.txt",source.version,source.revisionId,source.sha256,0,4000));assertThrows(IllegalArgumentException.class,()->spaces.proposeFileForOwner(selected,"../notes.txt",1,"bad","bad",0,10));assertEquals(0,spaces.handleForOwner(selected,"listCandidates",new JSONObject()).getJSONArray("candidates").length());
    }
    @Test public void archivedOwnerGlobalDataDoesNotClearProjectUsageFence()throws Exception{
        JSONObject result=propose(fileChoice());SourceRef source=SourceRef.fromJson(result.getJSONObject("sourceRef"));global(source);JSONObject oldArchive=memory.ownerArchive(owner()),record=accept(result.getJSONObject("candidate"));spaces.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")));memory.restoreOwnerArchive(owner(),oldArchive,"namespace-fixture-restore");assertThrows(IllegalStateException.class,()->propose(fileChoice()));assertEquals(1,disk().getJSONArray("namespaceSourceFences").length());
    }
    @Test public void coldBookRetainsUsageFenceButDoesNotRestoreItsOpaqueSelection()throws Exception{
        JSONObject result=propose(fileChoice()),record=accept(result.getJSONObject("candidate"));spaces.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")));MemoryBook cold=new MemoryBook(bookFile(),bridge.assertionResolver());try{
            ProjectMemoryNamespaces noBridge=new ProjectMemoryNamespaces(cold,local,workspace);assertThrows(SecurityException.class,()->noBridge.handleForOwner(selected,"list",new JSONObject()));assertEquals(0,cold.list(owner()).length());ProjectMemoryNamespaces.Selection newSelection=select(noBridge,checkout);assertThrows(UnsupportedOperationException.class,()->noBridge.handleForOwner(newSelection,"snapshot",new JSONObject()));
        }finally{cold.closeRetrieval();}
        // Reopen the same persisted Book through a freshly composed original native host. This
        // is a cold Book/host proof, not an Android process restart or permission restoration.
        FeatureHost reopened=new FeatureHost(event->{},tasks,()->false);reopened.register(workspace);reopened.register(local);MemorySourceBridge current=new MemorySourceBridge(reopened,tasks);current.bindWorkspace(workspace);MemoryModule reloaded=new MemoryModule(context,current.assertionResolver());reopened.register(reloaded);reopened.register(new dev.pocketengine.features.skills.SkillsModule(context,reloaded::resolveExperience));
        try{
            ProjectMemoryNamespaces fresh=new NativeOwnerWorkflows(reopened,context,tasks,current).projectMemoryForOwner();assertThrows(SecurityException.class,()->fresh.handleForOwner(selected,"snapshot",new JSONObject()));ProjectMemoryNamespaces.Selection newSelection=select(fresh,checkout);JSONObject file=fresh.fileChoicesForOwner(newSelection).getJSONObject(0);assertThrows(IllegalStateException.class,()->fresh.proposeFileForOwner(newSelection,"notes.txt",file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),0,4000));assertFalse(disk().toString().contains("native_owner_explicit_project_selection"));
        }finally{reopened.close();}
    }
    @Test public void exactUtf16SpanIsNativeBoundAndCannotStartInsideSurrogatePair()throws Exception{
        workspace.handle("write",new JSONObject().put("projectId",checkout.getString("projectId")).put("path","notes.txt").put("content","A🙂B原文").put("expectedVersion",1));JSONObject file=fileChoice();assertThrows(IllegalArgumentException.class,()->spaces.proposeFileForOwner(selected,"notes.txt",file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),2,2));JSONObject result=spaces.proposeFileForOwner(selected,"notes.txt",file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),1,2);assertEquals("🙂",result.getJSONObject("candidate").getString("text"));SourceRef source=SourceRef.fromJson(result.getJSONObject("sourceRef"));assertEquals(1,source.workspace.offset);assertEquals(2,source.workspace.chars);assertEquals("private",source.spaceId);
    }
    private static <T> T worker(java.util.concurrent.Callable<T> action)throws Exception{
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();try{return executor.submit(action).get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}finally{executor.shutdownNow();}
    }
    @Test public void projectSourceReaderUsesExactNativeSpanAndCannotOpenOtherProjects()throws Exception{
        JSONObject result=propose(fileChoice());SourceRef source=SourceRef.fromJson(result.getJSONObject("sourceRef"));JSONObject opened=worker(()->spaces.readFileSourceForOwner(selected,source));assertEquals(ORIGINAL,opened.getString("text"));assertEquals(source.toJson().toString(),opened.getJSONObject("sourceRef").toString());assertFalse(opened.getBoolean("executionAuthorized"));
        JSONObject other=create("另一个项目","不同项目原文");var file=workspace.fileSnapshotForHost(other.getString("projectId"),"notes.txt",1);SourceRef foreign=SourceRef.workspaceFile(file.projectId,file.path,file.version,file.revisionId,file.sha256,0,2,"owner","private");assertThrows(SecurityException.class,()->worker(()->spaces.readFileSourceForOwner(selected,foreign)));
    }
    @Test public void projectSourceReaderCannotBypassUsageForgetViaRetainedGlobalRecord()throws Exception{
        JSONObject result=propose(fileChoice());SourceRef source=SourceRef.fromJson(result.getJSONObject("sourceRef"));JSONObject retained=global(source),record=accept(result.getJSONObject("candidate"));spaces.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",record.getLong("revision")));assertThrows(SecurityException.class,()->worker(()->spaces.readFileSourceForOwner(selected,source)));assertEquals(retained.getString("id"),memory.handle("get",new JSONObject().put("scope",owner()).put("id",retained.getString("id"))).getJSONObject("record").getString("id"));
        assertThrows(UnsupportedOperationException.class,()->spaces.handleForOwner(selected,"contextSnapshot",new JSONObject()));assertThrows(UnsupportedOperationException.class,()->spaces.handleForOwner(selected,"proposeConsolidation",new JSONObject()));
    }
    @Test public void closedWorkspaceIsUnavailableNotAnEmptyProjectBook()throws Exception{
        accept(propose(fileChoice()).getJSONObject("candidate"));workspace.close();assertThrows(IllegalStateException.class,()->spaces.handleForOwner(selected,"snapshot",new JSONObject()));assertFalse(selected.metadata().getBoolean("selectionCurrent"));
    }
}
