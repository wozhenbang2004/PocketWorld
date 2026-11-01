package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original VWS bytes/head and MemoryBook source lifecycle. No task is created for a file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class WorkspaceMemorySourceNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Application android=RuntimeEnvironment.getApplication();final TaskStore tasks;
        final FeatureContext context;final FeatureHost host=new FeatureHost(event->{});final MemorySourceBridge bridge;
        final WorkspaceModule workspace;final MemoryModule memory;final ExecutorService worker=Executors.newSingleThreadExecutor();
        final String project,path="资料/学习原文.txt";final String text="PREFIX🙂 原始文件内容 后续未选范围";
        Fixture()throws Exception{android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});bridge=new MemorySourceBridge(host,tasks);workspace=new WorkspaceModule(context);host.register(workspace);bridge.bindWorkspace(workspace);memory=new MemoryModule(context,bridge::resolve);host.register(memory);project=workspace.handle("create",new JSONObject().put("name","明确选择原文件")).getString("id");write(text,0);}
        JSONObject write(String body,int expected)throws Exception{return workspace.handle("write",new JSONObject().put("projectId",project).put("path",path).put("content",body).put("expectedVersion",expected));}
        <T>T io(Callable<T> operation)throws Exception{try{return worker.submit(operation).get(10,TimeUnit.SECONDS);}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}}
        SourceRef select(int start,int chars)throws Exception{String id=SourceRef.workspaceSourceId(project,path,start,chars);return io(()->{bridge.prepareSourceHead("workspace_file",id,new RunControl());return bridge.resolve(MemoryBook.Scope.from(scope()),"workspace_file",id).ref;});}
        JSONObject remember(SourceRef ref)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",new JSONObject().put("text","原文件得到的明确候选结论").put("sourceRefs",new JSONArray().put(ref.toJson())))).getJSONObject("record");}
        public void close()throws Exception{worker.shutdownNow();host.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
    }
    @Test public void explicitFileRangeUsesActualVersionRevisionAndHashWithoutNewTaskOrBodyMirror()throws Exception{
        try(Fixture f=new Fixture()){int before=f.tasks.list().length();SourceRef ref=f.select(8,6);var body=f.io(()->f.bridge.read(ref));assertEquals(f.text.substring(8,14),body.text);assertEquals(1,ref.revision);assertTrue(ref.workspace.sha256.matches("[0-9a-f]{64}"));assertEquals(f.workspace.fileSnapshotForHost(f.project,f.path,1).revisionId,ref.workspace.revisionId);assertTrue(body.title.contains("UTF-16 8–14"));assertFalse(body.text.contains("未选范围"));assertEquals(before,f.tasks.list().length());
            SourceRef changed=SourceRef.workspaceFile(f.project,f.path,1,ref.workspace.revisionId,"f".repeat(64),8,6,"owner","private");assertThrows(Exception.class,()->f.io(()->f.bridge.read(changed)));
        }
    }
    @Test public void realWriteFencesOldReferenceAndDerivedRecallBeforeAnySnapshotReuse()throws Exception{
        try(Fixture f=new Fixture()){SourceRef old=f.select(0,24000);f.remember(old);assertEquals(1,f.memory.handle("search",new JSONObject().put("scope",scope()).put("query","候选")).getJSONArray("records").length());f.write("确切新文件正文",1);assertThrows(Exception.class,()->f.io(()->f.bridge.read(old)));assertEquals(0,f.memory.handle("search",new JSONObject().put("scope",scope()).put("query","候选")).getJSONArray("records").length());SourceRef current=f.select(0,24000);assertEquals(2,current.revision);assertNotEquals(old,current);assertEquals("确切新文件正文",f.io(()->f.bridge.read(current)).text);}
    }
    @Test public void coldUnknownProjectionDoesNotReadUntilNativeIoBootstrapAndCloseRevokes()throws Exception{
        try(Fixture f=new Fixture()){SourceRef old=f.select(0,24000);f.workspace.close();assertNull(f.bridge.resolve(MemoryBook.Scope.from(scope()),old.kind,old.sourceId));assertThrows(Exception.class,()->f.io(()->f.bridge.read(old)));
            WorkspaceModule reopened=new WorkspaceModule(f.context);try(FeatureHost newHost=new FeatureHost(event->{})){newHost.register(reopened);MemorySourceBridge cold=new MemorySourceBridge(newHost,f.tasks);cold.bindWorkspace(reopened);assertNull(cold.resolve(MemoryBook.Scope.from(scope()),old.kind,old.sourceId));f.io(()->{cold.prepareSourceHead(old.kind,old.sourceId,new RunControl());return null;});assertEquals(old,cold.resolve(MemoryBook.Scope.from(scope()),old.kind,old.sourceId).ref);assertEquals(f.text,f.io(()->cold.read(old)).text);}
        }
    }
    @Test public void actualDeletionTombstoneCannotBeReadOrConfusedWithMissingOrRestoredFile()throws Exception{
        try(Fixture f=new Fixture()){SourceRef old=f.select(0,24000);f.workspace.handle("removeFile",new JSONObject().put("projectId",f.project).put("path",f.path).put("expectedVersion",1));SourceRef.State deleted=f.bridge.resolve(MemoryBook.Scope.from(scope()),old.kind,old.sourceId);assertTrue(deleted.deleted);assertEquals(2,deleted.ref.revision);assertEquals("absent",deleted.ref.workspace.sha256);assertThrows(Exception.class,()->f.io(()->f.bridge.read(deleted.ref)));f.write("明确的新第三版",2);SourceRef restored=f.select(0,24000);assertEquals(3,restored.revision);assertNotEquals(old,restored);assertThrows(Exception.class,()->f.io(()->f.bridge.read(old)));}
    }
    @Test public void memberReadStillRequiresExplicitSpanAndRechecksSourceAndAudience()throws Exception{
        try(Fixture f=new Fixture()){SourceRef ref=f.select(0,24000);boolean[] current={true};ReadAudience selected=new ReadAudience(){public String ownerId(){return "owner";}public String spaceId(){return "private";}public String audienceId(){return "native-test-member";}public long revision(){return 1;}public JSONArray allowedRecords(){return new JSONArray();}public JSONArray allowedSources()throws Exception{return new JSONArray().put(new JSONObject().put("sourceRef",ref.toJson()).put("offset",8).put("chars",6).put("unit","UTF-16"));}public void checkCurrent(){if(!current[0])throw new SecurityException("scope revoked");}};
            TaskMemoryUse use=f.memory.taskMemoryUse(null,r->{var body=f.bridge.read(r);return new TaskMemoryUse.Evidence(body.ref,body.title,body.text);});JSONObject result=f.io(()->use.readAudienceSource(scope(),ref,8,6,selected));assertEquals(f.text.substring(8,14),result.getString("text"));assertEquals("所选原文范围",result.getString("title"));assertFalse(result.getBoolean("executionAuthorized"));assertThrows(SecurityException.class,()->f.io(()->use.readAudienceSource(scope(),ref,0,20,selected)));current[0]=false;assertThrows(SecurityException.class,()->f.io(()->use.readAudienceSource(scope(),ref,8,6,selected)));assertNotNull(f.io(()->f.bridge.read(ref)));}
    }
}
