package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.features.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual existing native project Selection/Bridge/Book/VWS/SQLite, no new read grant or UI claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectMemoryHistoryNativeTest {
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final FeatureHost host;final WorkspaceModule workspace;final LocalModule local;final MemorySourceBridge bridge;final MemoryModule memory;final ProjectMemoryNamespaces spaces;final ExecutorService io=Executors.newSingleThreadExecutor();
        Fixture()throws Exception{app.deleteDatabase("pocket-world.db");new File(app.getFilesDir(),"memory/records.json").delete();FeatureContext context=new FeatureContext(app,null,null,event->{});tasks=new TaskStore(app);host=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);host.register(workspace);host.register(local);bridge=new MemorySourceBridge(host,tasks);bridge.bindWorkspace(workspace);memory=new MemoryModule(context,bridge.assertionResolver());host.register(memory);host.register(new SkillsModule(context,memory::resolveExperience));spaces=new NativeOwnerWorkflows(host,context,tasks,bridge).projectMemoryForOwner();}
        JSONObject create(String name)throws Exception{String id=workspace.handle("create",new JSONObject().put("name",name)).getString("id");workspace.handle("write",new JSONObject().put("projectId",id).put("path","notes.txt").put("content","共同主题：项目选择方案甲的原文与理由。").put("expectedVersion",0));return local.handle("prepare",new JSONObject().put("projectId",id));}
        ProjectMemoryNamespaces.Selection select(ProjectMemoryNamespaces facade,JSONObject checkout)throws Exception{JSONArray rows=facade.choicesForOwner();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(checkout.getString("id").equals(row.getString("checkoutId")))return facade.selectForOwner(row.getString("checkoutId"),row.getString("projectId"),row.getString("revision"));}throw new AssertionError("missing original checkout");}
        ProjectMemoryNamespaces.Selection select(JSONObject checkout)throws Exception{return select(spaces,checkout);}
        JSONObject save(ProjectMemoryNamespaces.Selection token,String text)throws Exception{return spaces.handleForOwner(token,"save",new JSONObject().put("record",new JSONObject().put("text",text))).getJSONObject("record");}
        <T>T worker(Callable<T> action)throws Exception{try{return io.submit(action).get(10,TimeUnit.SECONDS);}catch(ExecutionException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}}
        JSONObject history(ProjectMemoryNamespaces facade,ProjectMemoryNamespaces.Selection token,String query,String cursor,int limit)throws Exception{try{return worker(()->facade.handleForOwner(token,"searchHistory",new JSONObject().put("query",query).put("cursor",cursor).put("limit",limit)));}catch(UnsupportedOperationException unsupported){if(!"此项目记忆操作未接入原生管理；没有回退主人全局空间".equals(unsupported.getMessage()))throw unsupported;return new JSONObject().put("unsupported",true).put("matches",new JSONArray());}}
        JSONObject history(ProjectMemoryNamespaces.Selection token,String query)throws Exception{return history(spaces,token,query,"",12);}
        JSONObject read(ProjectMemoryNamespaces.Selection token,JSONObject record)throws Exception{return worker(()->spaces.handleForOwner(token,"readHistoryRecord",new JSONObject().put("id",record.getString("id")).put("revision",record.getLong("revision"))));}
        JSONObject excerpt(ProjectMemoryNamespaces.Selection token)throws Exception{return worker(()->{JSONObject file=spaces.fileChoicesForOwner(token).getJSONObject(0);return spaces.proposeFileForOwner(token,"notes.txt",file.getInt("version"),file.getString("revisionId"),file.getString("sha256"),0,4000);});}
        JSONObject accept(ProjectMemoryNamespaces.Selection token,JSONObject candidate)throws Exception{return spaces.handleForOwner(token,"acceptCandidate",new JSONObject().put("id",candidate.getString("id")).put("revision",candidate.getLong("revision"))).getJSONObject("record");}
        JSONObject global(SourceRef ref)throws Exception{JSONObject candidate=memory.proposeLearning(owner(),new JSONObject().put("text","共同主题：主人全局保留的原文件摘录").put("layer","episodic").put("reason","主人明确选择原文件").put("applicability","").put("confidence",0.5),ref).getJSONObject("candidate");return memory.acceptCandidate(owner(),candidate.getString("id"),1).getJSONObject("record");}
        public void close()throws Exception{io.shutdownNow();host.close();var field=tasks.getClass().getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    }
    private static JSONObject first(JSONObject page)throws Exception{assertFalse("The native project query must be mounted",page.optBoolean("unsupported"));assertTrue(page.getJSONArray("matches").length()>0);return page.getJSONArray("matches").getJSONObject(0);}
    @Test public void selectedProjectHistoryNeverFallsBackToOwnerOrAnotherProject()throws Exception{try(Fixture f=new Fixture()){
        f.memory.handle("save",new JSONObject().put("record",new JSONObject().put("text","共同主题：主人全局")));JSONObject a=f.create("甲"),b=f.create("乙");ProjectMemoryNamespaces.Selection one=f.select(a);JSONObject record=f.save(one,"共同主题：甲项目"),header=first(f.history(one,"共同主题"));assertEquals(record.getString("id"),header.getString("id"));assertEquals("project:"+a.getString("projectId"),f.history(one,"共同主题").getJSONObject("scope").getString("spaceId"));assertEquals(record.getString("text"),f.read(one,header).getJSONObject("record").getString("text"));assertFalse(f.read(one,header).getBoolean("activeRecall"));
        ProjectMemoryNamespaces.Selection two=f.select(b);f.save(two,"共同主题：乙项目");assertThrows(Exception.class,()->f.read(two,header));assertThrows(SecurityException.class,()->f.history(one,"共同主题"));assertEquals(1,f.memory.searchHistoryForOwner("共同主题","",12).getJSONArray("matches").length());
    }}
    @Test public void jsonLocatorsCannotTurnAProjectQueryIntoAnotherAudienceOrModelRead()throws Exception{try(Fixture f=new Fixture()){
        ProjectMemoryNamespaces.Selection token=f.select(f.create("原生选择"));JSONObject record=f.save(token,"共同主题：所选记录");first(f.history(token,"共同主题"));assertThrows(SecurityException.class,()->f.spaces.handleForOwner(null,"searchHistory",new JSONObject().put("query","共同主题")));
        for(String key:new String[]{"scope","ownerId","spaceId","projectId","namespaceToken","audience"})assertThrows(SecurityException.class,()->f.spaces.handleForOwner(token,"searchHistory",new JSONObject().put("query","共同主题").put(key,"forged")));
        assertThrows(IllegalArgumentException.class,()->f.memory.handle("searchHistory",new JSONObject().put("scope",owner()).put("query","共同主题")));assertFalse(token.metadata().getBoolean("memberReadAuthorized"));assertFalse(token.metadata().getBoolean("groupRetentionAuthorized"));assertThrows(UnsupportedOperationException.class,()->f.spaces.handleForOwner(token,"groupMemory",new JSONObject()));assertThrows(IllegalArgumentException.class,()->f.memory.readHistoryRecordForOwner(record.getString("id"),1));
    }}
    @Test public void boundedProjectPagesKeepQueryVersionFencesAndExactRecordRevisions()throws Exception{try(Fixture f=new Fixture()){
        ProjectMemoryNamespaces.Selection token=f.select(f.create("分页"));for(int i=0;i<4;i++)f.save(token,"共同主题旧事"+i);JSONObject page=f.history(f.spaces,token,"共同主题","",2),header=first(page);String cursor=page.getString("nextCursor");f.save(token,"不相干的其他记录");assertEquals(2,f.history(f.spaces,token,"共同主题",cursor,2).getJSONArray("matches").length());assertThrows(IllegalStateException.class,()->f.history(f.spaces,token,"不同问题",cursor,2));f.save(token,"共同主题新增");assertThrows(IllegalStateException.class,()->f.history(f.spaces,token,"共同主题",cursor,2));assertThrows(IllegalStateException.class,()->f.read(token,new JSONObject(header.toString()).put("revision",99)));assertThrows(IllegalArgumentException.class,()->f.history(f.spaces,token,"共同主题","",21));
    }}
    @Test public void coldBookRequiresFreshNativeSelectionAndNeverRestoresOldTokenAuthority()throws Exception{try(Fixture f=new Fixture()){
        JSONObject checkout=f.create("冷开");ProjectMemoryNamespaces.Selection old=f.select(checkout);JSONObject record=f.save(old,"共同主题：持久项目数据");first(f.history(old,"共同主题"));MemoryBook cold=new MemoryBook(new File(f.app.getFilesDir(),"memory/records.json"),f.bridge.assertionResolver());try{ProjectMemoryNamespaces facade=new ProjectMemoryNamespaces(cold,f.local,f.workspace);assertThrows(SecurityException.class,()->f.history(facade,old,"共同主题","",12));ProjectMemoryNamespaces.Selection fresh=f.select(facade,checkout);assertEquals(record.getString("id"),first(f.history(facade,fresh,"共同主题","",12)).getString("id"));assertEquals(0,cold.searchHistoryForOwner("共同主题","",12).getJSONArray("matches").length());}finally{cold.closeRetrieval();}
    }}
    @Test public void namespaceForgetBlocksHistoricalReadsWithoutWipingRetainedOwnerGlobalEvidence()throws Exception{try(Fixture f=new Fixture()){
        ProjectMemoryNamespaces.Selection token=f.select(f.create("局部屏障"));JSONObject excerpt=f.excerpt(token);SourceRef ref=SourceRef.fromJson(excerpt.getJSONObject("sourceRef"));JSONObject global=f.global(ref),record=f.accept(token,excerpt.getJSONObject("candidate")),header=first(f.history(token,"共同主题"));f.spaces.handleForOwner(token,"forgetReviewed",new JSONObject().put("id",record.getString("id")).put("expectedRevision",1));assertEquals(0,f.history(token,"共同主题").getJSONArray("matches").length());assertThrows(Exception.class,()->f.read(token,header));assertFalse(f.memory.isSourceForgotten(owner(),ref));assertEquals(global.getString("id"),first(f.memory.searchHistoryForOwner("共同主题","",12)).getString("id"));
    }}
    @Test public void originalGlobalForgetStillBlocksEveryProjectUseOfTheSameUnchangedSourceKey()throws Exception{try(Fixture f=new Fixture()){
        ProjectMemoryNamespaces.Selection token=f.select(f.create("全局屏障"));JSONObject excerpt=f.excerpt(token);SourceRef ref=SourceRef.fromJson(excerpt.getJSONObject("sourceRef"));JSONObject global=f.global(ref);f.accept(token,excerpt.getJSONObject("candidate"));JSONObject header=first(f.history(token,"共同主题"));f.bridge.forgetReviewed(owner(),global.getString("id"),1);assertTrue(f.memory.isSourceForgotten(owner(),ref));assertEquals(0,f.history(token,"共同主题").getJSONArray("matches").length());assertThrows(Exception.class,()->f.read(token,header));assertEquals(0,f.memory.searchHistoryForOwner("共同主题","",12).getJSONArray("matches").length());assertThrows(IllegalStateException.class,()->f.excerpt(token));
    }}
}
