package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectMemoryNamespacesNativeTest {
    @Rule public final TemporaryFolder temporary=new TemporaryFolder();
    private WorkspaceModule workspace;private LocalModule local;private MemoryBook book;private ProjectMemoryNamespaces spaces;private File file;
    @Before public void setup()throws Exception{
        FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);file=new File(temporary.getRoot(),"one-book.json");book=new MemoryBook(file);spaces=new ProjectMemoryNamespaces(book,local,workspace);
    }
    @After public void close()throws Exception{book.closeRetrieval();local.close();workspace.close();}
    private static JSONObject owner()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject checkout(String name)throws Exception{
        String project=workspace.handle("create",new JSONObject().put("name",name)).getString("id");
        workspace.handle("write",new JSONObject().put("projectId",project).put("path","readme.txt").put("content","原项目笔记").put("expectedVersion",0));
        return local.handle("prepare",new JSONObject().put("projectId",project));
    }
    private JSONObject choice(JSONObject checkout)throws Exception{
        JSONArray all=spaces.choicesForOwner();for(int i=0;i<all.length();i++)if(checkout.getString("id").equals(all.getJSONObject(i).getString("checkoutId")))return all.getJSONObject(i);throw new AssertionError("real checkout missing");
    }
    private ProjectMemoryNamespaces.Selection select(JSONObject checkout)throws Exception{JSONObject current=choice(checkout);return spaces.selectForOwner(current.getString("checkoutId"),current.getString("projectId"),current.getString("revision"));}
    private JSONObject save(ProjectMemoryNamespaces.Selection selected,String text)throws Exception{return spaces.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("text",text).put("layer","fact"))).getJSONObject("record");}
    @Test public void rawProjectScopeSaveRejectedBeforeAnyRowsCreated()throws Exception{
        JSONObject forged=new JSONObject().put("ownerId","owner").put("spaceId","project:11111111-1111-1111-1111-111111111111");
        assertThrows(SecurityException.class,()->book.save(forged,new JSONObject().put("text","不能用JSON选择项目"),1));assertFalse(file.exists());
    }
    @Test public void sameOriginalBookPartitionsOwnerAndExplicitProjectWithoutRecallBleed()throws Exception{
        JSONObject checkout=checkout("甲项目");book.save(owner(),new JSONObject().put("text","全局主人咖啡"),1);ProjectMemoryNamespaces.Selection selected=select(checkout);JSONObject project=save(selected,"甲项目咖啡");
        assertEquals(1,book.list(owner()).length());assertEquals("全局主人咖啡",book.search(owner(),"咖啡",10).getJSONObject(0).getString("text"));
        JSONArray hits=spaces.handleForOwner(selected,"search",new JSONObject().put("query","咖啡")).getJSONArray("records");assertEquals(1,hits.length());assertEquals(project.getString("id"),hits.getJSONObject(0).getString("id"));
        assertEquals("project:"+checkout.getString("projectId"),project.getString("spaceId"));assertEquals(2,new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).getJSONArray("records").length());
    }
    @Test public void serializedSelectionAndDirectIdAreNeverProjectReadAuthority()throws Exception{
        ProjectMemoryNamespaces.Selection selected=select(checkout("甲项目"));JSONObject project=save(selected,"项目秘密"),metadata=selected.metadata();
        assertThrows(SecurityException.class,()->book.list(metadata));assertThrows(SecurityException.class,()->book.get(metadata,project.getString("id")));
        assertThrows(IllegalArgumentException.class,()->book.get(owner(),project.getString("id")));
        assertThrows(SecurityException.class,()->spaces.handleForOwner(null,"get",new JSONObject().put("id",project.getString("id"))));
    }
    @Test public void switchingProjectInvalidatesOldTokenAndCannotReadOtherProjectById()throws Exception{
        JSONObject first=checkout("甲项目"),second=checkout("乙项目");ProjectMemoryNamespaces.Selection a=select(first);JSONObject record=save(a,"甲秘密");ProjectMemoryNamespaces.Selection b=select(second);
        assertThrows(SecurityException.class,()->spaces.handleForOwner(a,"list",new JSONObject()));assertThrows(IllegalArgumentException.class,()->spaces.handleForOwner(b,"get",new JSONObject().put("id",record.getString("id"))));assertEquals(0,spaces.handleForOwner(b,"snapshot",new JSONObject()).getJSONArray("records").length());
    }
    @Test public void activeCheckoutByteEditsDoNotReexportOrInvalidateManualNamespace()throws Exception{
        JSONObject checkout=checkout("甲项目");ProjectMemoryNamespaces.Selection selected=select(checkout);JSONObject original=local.handle("readFile",new JSONObject().put("checkoutId",checkout.getString("id")).put("path","readme.txt"));
        local.handle("writeFile",new JSONObject().put("checkoutId",checkout.getString("id")).put("path","readme.txt").put("content","新副本正文").put("expectedHash",original.getString("sha256")));
        assertEquals("项目手写仍可用",save(selected,"项目手写仍可用").getString("text"));
        // Export would reject this unsafe source entry. Manual memory must not inspect or export it.
        File checkoutFiles=new File(RuntimeEnvironment.getApplication().getFilesDir(),"local-development/checkouts/"+checkout.getString("id")+"/files");
        Files.createSymbolicLink(new File(checkoutFiles,"not-memory-source").toPath(),new File(temporary.getRoot(),"missing").toPath());
        assertEquals(1,spaces.handleForOwner(selected,"list",new JSONObject()).getJSONArray("records").length());
    }
    @Test public void committedFileRevisionsDoNotInvalidateManualNamespaceSelection()throws Exception{
        JSONObject checkout=checkout("甲项目");ProjectMemoryNamespaces.Selection selected=select(checkout);
        workspace.handle("write",new JSONObject().put("projectId",checkout.getString("projectId")).put("path","readme.txt").put("content","新正式版本").put("expectedVersion",1));
        assertEquals("不随文件修订丢选区",save(selected,"不随文件修订丢选区").getString("text"));assertTrue(selected.metadata().getBoolean("selectionCurrent"));
    }
    @Test public void staleChoiceCannotIssueTokenAndClearReallyWithdrawsIt()throws Exception{
        JSONObject checkout=checkout("甲项目"),choice=choice(checkout);assertThrows(SecurityException.class,()->spaces.selectForOwner(choice.getString("checkoutId"),"not-the-project",choice.getString("revision")));
        ProjectMemoryNamespaces.Selection selected=select(checkout);spaces.clearForOwner();assertThrows(SecurityException.class,()->save(selected,"不应保存"));
    }
    @Test public void tokenCannotMoveToAnotherBookOrFacade()throws Exception{
        JSONObject checkout=checkout("甲项目");ProjectMemoryNamespaces.Selection selected=select(checkout);MemoryBook other=new MemoryBook(new File(temporary.getRoot(),"other.json"));try{
            ProjectMemoryNamespaces otherSpaces=new ProjectMemoryNamespaces(other,local,workspace);assertThrows(SecurityException.class,()->otherSpaces.handleForOwner(selected,"list",new JSONObject()));
        }finally{other.closeRetrieval();}
    }
    @Test public void coldBookPreservesPartitionButRestoresNoSelectionOrPermission()throws Exception{
        JSONObject checkout=checkout("甲项目");ProjectMemoryNamespaces.Selection selected=select(checkout);JSONObject saved=save(selected,"项目持久记忆");MemoryBook cold=new MemoryBook(file);try{
            assertEquals(0,cold.list(owner()).length());assertThrows(SecurityException.class,()->cold.get(selected.metadata(),saved.getString("id")));
            ProjectMemoryNamespaces fresh=new ProjectMemoryNamespaces(cold,local,workspace);assertThrows(SecurityException.class,()->fresh.handleForOwner(selected,"list",new JSONObject()));JSONObject choice=fresh.choicesForOwner().getJSONObject(0);ProjectMemoryNamespaces.Selection reselected=fresh.selectForOwner(choice.getString("checkoutId"),choice.getString("projectId"),choice.getString("revision"));assertEquals(saved.getString("id"),fresh.handleForOwner(reselected,"list",new JSONObject()).getJSONArray("records").getJSONObject(0).getString("id"));
        }finally{cold.closeRetrieval();}
    }
    @Test public void genericNamespacePayloadRejectsInsteadOfSilentlyWritingOwner()throws Exception{
        for(String key:new String[]{"namespace","projectId","namespaceToken","projectSelection"})assertThrows(SecurityException.class,()->ProjectMemoryNamespaces.rejectNamespaceArguments(new JSONObject().put(key,"forged")));
        assertThrows(SecurityException.class,()->ProjectMemoryNamespaces.rejectNamespaceArguments(new JSONObject().put("scope",new JSONObject().put("ownerId","owner").put("spaceId","group:123"))));
        assertThrows(SecurityException.class,()->ProjectMemoryNamespaces.rejectNamespaceArguments(new JSONObject().put("record",new JSONObject().put("projectId","forged"))));
        ProjectMemoryNamespaces.Selection selected=select(checkout("甲项目"));assertThrows(SecurityException.class,()->spaces.handleForOwner(selected,"save",new JSONObject().put("projectId",selected.metadata().getString("projectId")).put("record",new JSONObject().put("text","不得覆盖原生选择"))));
    }
    @Test public void ownerManualManagementUsesExactRevisionAndDoesNotGrantTaskContext()throws Exception{
        ProjectMemoryNamespaces.Selection selected=select(checkout("甲项目"));JSONObject saved=save(selected,"项目手写事实");JSONObject pin=new JSONObject().put("id",saved.getString("id")).put("revision",saved.getLong("revision")).put("core",true);spaces.handleForOwner(selected,"setCore",pin);
        assertThrows(IllegalStateException.class,()->spaces.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",saved.getString("id")).put("expectedRevision",saved.getLong("revision"))));
        JSONObject current=spaces.handleForOwner(selected,"get",new JSONObject().put("id",saved.getString("id"))).getJSONObject("record");assertEquals(1,spaces.handleForOwner(selected,"forgetReviewed",new JSONObject().put("id",current.getString("id")).put("expectedRevision",current.getLong("revision"))).getInt("deleted"));
        JSONObject snapshot=spaces.handleForOwner(selected,"snapshot",new JSONObject());assertFalse(snapshot.getBoolean("taskContextSupported"));assertFalse(snapshot.getBoolean("automaticLearning"));
    }
    @Test public void originalNativeSqliteIndexKeepsOwnerAndProjectPartitionsSeparate()throws Exception{
        MemoryRetrievalBackend index=SQLiteMemoryRetrievalBackend.create(false);MemoryBook indexed=new MemoryBook(new File(temporary.getRoot(),"indexed-book.json"),null,index);try{
            JSONObject global=indexed.save(owner(),new JSONObject().put("text","共同关键词：主人全局"),1);ProjectMemoryNamespaces nativeScopes=new ProjectMemoryNamespaces(indexed,local,workspace);JSONObject checkout=checkout("索引项目"),row=nativeScopes.choicesForOwner().getJSONObject(0);ProjectMemoryNamespaces.Selection choice=nativeScopes.selectForOwner(row.getString("checkoutId"),row.getString("projectId"),row.getString("revision"));JSONObject project=nativeScopes.handleForOwner(choice,"save",new JSONObject().put("record",new JSONObject().put("text","共同关键词：当前项目"))).getJSONObject("record");
            assertEquals(global.getString("id"),indexed.search(owner(),"共同关键词",10).getJSONObject(0).getString("id"));JSONArray hits=nativeScopes.handleForOwner(choice,"search",new JSONObject().put("query","共同关键词")).getJSONArray("records");assertEquals(1,hits.length());assertEquals(project.getString("id"),hits.getJSONObject(0).getString("id"));assertEquals(1,indexed.search(owner(),"共同关键词",10).length());assertThrows(SecurityException.class,()->indexed.search(choice.metadata(),"共同关键词",10));assertThrows(IllegalArgumentException.class,()->nativeScopes.handleForOwner(choice,"get",new JSONObject().put("id",global.getString("id"))));
        }finally{indexed.closeRetrieval();}
    }
    @Test public void clearingScopeDoesNotWaitForFacadeIoMonitor()throws Exception{
        ProjectMemoryNamespaces.Selection selected=select(checkout("甲项目"));var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{var holder=executor.submit(()->{synchronized(spaces){entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}});assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));executor.submit(()->spaces.clearForOwner()).get(1,java.util.concurrent.TimeUnit.SECONDS);assertFalse(selected.metadata().getBoolean("selectionCurrent"));release.countDown();holder.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThrows(SecurityException.class,()->save(selected,"离页后不得写入"));}finally{release.countDown();executor.shutdownNow();}
    }
    @Test public void actualGenericModuleRejectsNamespaceBeforeFallbackToPrivate()throws Exception{
        FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});MemoryModule module=new MemoryModule(context);
        assertThrows(SecurityException.class,()->module.handle("save",new JSONObject().put("projectId","forged").put("record",new JSONObject().put("text","不能静默写入private"))));
        assertThrows(SecurityException.class,()->module.handle("list",new JSONObject().put("scope",new JSONObject().put("ownerId","owner").put("spaceId","project:11111111-1111-1111-1111-111111111111"))));
        assertEquals(0,module.snapshot().getInt("recordCount"));
    }
    @Test public void arbitrarySourceJsonCannotBorrowProjectNamespaceAndGroupIsUnsupported()throws Exception{
        ProjectMemoryNamespaces.Selection selected=select(checkout("甲项目"));SourceRef message=new SourceRef("message","private-chat",1,"owner","private");
        assertThrows(SecurityException.class,()->spaces.handleForOwner(selected,"save",new JSONObject().put("record",new JSONObject().put("text","不能借来源身份扩权").put("sourceRefs",new JSONArray().put(message.toJson())))));
        assertThrows(UnsupportedOperationException.class,()->spaces.handleForOwner(selected,"groupMemory",new JSONObject()));assertEquals(0,book.list(owner()).length());assertFalse(selected.metadata().getBoolean("groupRetentionAuthorized"));assertFalse(selected.metadata().getBoolean("memberReadAuthorized"));
    }
    @Test public void actualFeatureHostNativeOwnerGetterUsesOriginalWorkspaceModule()throws Exception{
        FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});dev.pocketengine.store.TaskStore tasks=new dev.pocketengine.store.TaskStore(context.android);dev.pocketengine.features.FeatureHost host=new dev.pocketengine.features.FeatureHost(event->{},tasks,()->false);host.register(workspace);host.register(local);MemoryModule module=new MemoryModule(context);host.register(module);dev.pocketengine.features.MemorySourceBridge sources=new dev.pocketengine.features.MemorySourceBridge(host,tasks);sources.bindWorkspace(workspace);
        try{
            dev.pocketengine.features.NativeOwnerWorkflows nativeOwner=new dev.pocketengine.features.NativeOwnerWorkflows(host,context,tasks,sources);ProjectMemoryNamespaces actual=nativeOwner.projectMemoryForOwner();assertSame(actual,nativeOwner.projectMemoryForOwner());
            checkout("原Host项目");JSONObject row=actual.choicesForOwner().getJSONObject(0);ProjectMemoryNamespaces.Selection selected=actual.selectForOwner(row.getString("checkoutId"),row.getString("projectId"),row.getString("revision"));assertTrue(actual.handleForOwner(selected,"snapshot",new JSONObject()).getBoolean("available"));
        }finally{host.close();var field=tasks.getClass().getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void ownerArchiveDoesNotExportProjectPartitionOrToken()throws Exception{
        ProjectMemoryNamespaces.Selection selected=select(checkout("甲项目"));save(selected,"项目档案不隐式导出");book.save(owner(),new JSONObject().put("text","全局主人"),1);JSONObject archive=book.ownerArchive(owner());assertEquals(1,archive.getJSONArray("records").length());assertFalse(archive.toString().contains("项目档案不隐式导出"));assertFalse(archive.toString().contains("native_owner_explicit_project_selection"));assertEquals(10,new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).getInt("version"));
    }
}
