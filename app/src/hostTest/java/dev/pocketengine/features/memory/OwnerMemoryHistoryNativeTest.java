package dev.pocketengine.features.memory;

import android.app.Application;
import dev.pocketengine.features.*;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.*;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original owner workflow/Book/SQLite/workspace span. No model or original-text substitute. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerMemoryHistoryNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject fact(String text,String city,long event,long validFrom)throws Exception {
        return new JSONObject().put("layer","fact").put("text",text).put("assertion",new JSONObject().put("version",1).put("kind","told").put("subjectId","owner").put("subjectType","owner").put("subjectLabel","主人").put("predicate","居住城市").put("object",city).put("condition","").put("eventTime",event).put("validFrom",validFrom).put("evidenceSpans",new JSONArray()));
    }
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final FeatureContext context;final FeatureHost host;final WorkspaceModule workspace;final MemorySourceBridge sources;final MemoryModule memory;final NativeOwnerWorkflows owner;
        Fixture()throws Exception{this(true);}
        Fixture(boolean clean)throws Exception {
            if(clean){app.deleteDatabase("pocket-world.db");new File(app.getFilesDir(),"memory/records.json").delete();}
            tasks=new TaskStore(app);context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});host=new FeatureHost(event->{},tasks,()->false);workspace=new WorkspaceModule(context);host.register(workspace);sources=new MemorySourceBridge(host,tasks);sources.bindWorkspace(workspace);memory=new MemoryModule(context,sources.assertionResolver());host.register(memory);host.register(new SkillsModule(context,memory::resolveExperience));owner=new NativeOwnerWorkflows(host,context,tasks,sources);
        }
        JSONObject save(JSONObject record)throws Exception{return memory.handle("save",new JSONObject().put("scope",scope()).put("record",record)).getJSONObject("record");}
        JSONObject history(String query,String cursor,int limit)throws Exception {
            try{return io(()->owner.memory("searchHistory",new JSONObject().put("query",query).put("cursor",cursor).put("limit",limit)));}
            catch(IllegalArgumentException unsupported){if(!"此操作不属于原生记忆审核".equals(unsupported.getMessage()))throw unsupported;return new JSONObject().put("matches",new JSONArray()).put("unsupported",true);}
        }
        JSONObject read(JSONObject header)throws Exception{return io(()->owner.memory("readHistoryRecord",new JSONObject().put("id",header.getString("id")).put("revision",header.getLong("revision"))));}
        JSONObject readSpan(JSONObject span)throws Exception{return io(()->owner.memory("readAssertionEvidence",span));}
        MemoryBook book()throws Exception{Field field=MemoryModule.class.getDeclaredField("book");field.setAccessible(true);return (MemoryBook)field.get(memory);}
        public void close()throws Exception{host.close();Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}
    }
    private static <T>T io(java.util.concurrent.Callable<T> action)throws Exception {
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();try{return executor.submit(action).get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}finally{executor.shutdownNow();}
    }
    private static JSONObject first(JSONObject page)throws Exception{JSONArray rows=page.getJSONArray("matches");assertTrue("native historical query must find the retained version",rows.length()>0);return rows.getJSONObject(0);}
    @Test public void oldFactsAreSearchableWithoutKnowingSubjectIdButNeverBecomeCurrentRecallAgain()throws Exception {
        try(Fixture f=new Fixture()){
            long old=Instant.parse("2023-04-06T00:00:00Z").toEpochMilli(),now=System.currentTimeMillis();JSONObject past=f.save(fact("过去住A城的已存记忆，不是原话","A城",old,old));f.save(fact("现在住B城","B城",now-1000,now-1000));f.save(fact("迟到导入的旧A城材料","A城",old-1000,old-1000));
            assertEquals(0,f.memory.handle("search",new JSONObject().put("scope",scope()).put("query","A城")).getJSONArray("records").length());JSONObject page=f.history("2023-04-06","",10);assertFalse("native historical search is actually mounted",page.optBoolean("unsupported"));assertEquals(1,page.getJSONArray("matches").length());JSONObject hit=page.getJSONArray("matches").getJSONObject(0);assertEquals(past.getString("id"),hit.getString("id"));assertEquals("historical",hit.getString("temporalStatus"));assertFalse(hit.getBoolean("memoryTextIsOriginalEvidence"));assertFalse(page.getBoolean("activeRecall"));
            JSONObject record=f.read(hit).getJSONObject("record");assertEquals(past.getString("text"),record.getString("text"));assertEquals("historical",record.getString("temporalStatus"));assertFalse(record.getBoolean("memoryTextIsOriginalEvidence"));assertEquals("B城",f.memory.handle("cognitionProjection",new JSONObject().put("scope",scope()).put("subjectId","owner")).getJSONArray("current").getJSONObject(0).getJSONObject("assertion").getString("object"));
        }
    }
    @Test public void storedMemoryAndActualOriginalSpanRemainDifferentAndSourceEditsCannotSwapEvidence()throws Exception {
        try(Fixture f=new Fixture()){
            String original="原项目笔记：那年住在A城。",project=f.workspace.handle("create",new JSONObject().put("name","historical-evidence")).getString("id");f.workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("content",original).put("expectedVersion",0));var file=f.workspace.fileSnapshotForHost(project,"notes.txt",0);
            SourceRef source=SourceRef.workspaceFile(project,"notes.txt",file.version,file.revisionId,file.sha256,0,original.length(),"owner","private");long time=Instant.parse("2023-04-06T00:00:00Z").toEpochMilli();JSONObject row=fact("A城经历的存储概述，绝不是原件正文","A城",time,time),span=new JSONObject().put("sourceRef",source.toJson()).put("offset",0).put("chars",original.length()).put("sha256",dev.pocketengine.features.local.project.ProjectSources.sha256(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)));row.getJSONObject("assertion").getJSONArray("evidenceSpans").put(span);row.put("sourceRefs",new JSONArray().put(source.toJson()));io(()->{f.sources.prepareSourceHead(source.kind,source.sourceId,null);return null;});f.save(row);
            JSONObject page=f.history("A城","",10);assertEquals(1,page.getJSONArray("matches").length());JSONObject hit=page.getJSONArray("matches").getJSONObject(0);assertEquals(1,hit.getInt("evidenceSpanCount"));assertFalse(f.read(hit).getBoolean("originalEvidenceRead"));JSONObject read=f.readSpan(span);assertEquals(original,read.getString("text"));assertTrue(read.getBoolean("verifiedOriginalSpan"));assertFalse(read.getBoolean("assertionTruthVerified"));
            f.workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("content","新原件只说B城").put("expectedVersion",1));assertEquals(0,f.history("A城","",10).getJSONArray("matches").length());assertThrows(Exception.class,()->f.read(hit));assertThrows(SecurityException.class,()->f.readSpan(span));
        }
    }
    @Test public void canonicalForgetBlocksAnAlreadyDisplayedHistoricalHeaderAndSurvivesColdBook()throws Exception {
        JSONObject header;
        try(Fixture f=new Fixture()){
            JSONObject row=f.save(fact("想找到的旧A城经历","A城",100,100));header=first(f.history("A城","",10));f.owner.memory("forgetReviewed",new JSONObject().put("id",row.getString("id")).put("expectedRevision",row.getLong("revision")));assertEquals(0,f.history("A城","",10).getJSONArray("matches").length());assertThrows(Exception.class,()->f.read(header));
        }
        try(Fixture cold=new Fixture(false)){assertEquals(0,cold.history("A城","",10).getJSONArray("matches").length());assertThrows(Exception.class,()->cold.read(header));}
    }
    @Test public void bodyFreePagesBindMatchingVersionsAndQueryButUnrelatedAppendsDoNotInvalidateThem()throws Exception {
        try(Fixture f=new Fixture()){
            for(int i=0;i<5;i++)f.save(new JSONObject().put("text","同主题旧事"+i));JSONObject first=f.history("同主题","",2);assertEquals(2,first.getJSONArray("matches").length());String cursor=first.getString("nextCursor");assertFalse(cursor.isEmpty());f.save(new JSONObject().put("text","不相干的另一次记录"));assertEquals(2,f.history("同主题",cursor,2).getJSONArray("matches").length());
            assertThrows(IllegalStateException.class,()->f.history("另一查询",cursor,2));f.save(new JSONObject().put("text","同主题新增版本"));assertThrows(IllegalStateException.class,()->f.history("同主题",cursor,2));
        }
    }
    @Test public void genericFeatureJsonAndForeignOrMemberArgumentsNeverGainHistoricalReadAuthority()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject own=f.save(new JSONObject().put("text","主人自己的旧事"));f.book().save(new JSONObject().put("ownerId","another").put("spaceId","private"),new JSONObject().put("text","其他人的旧事"),1);JSONArray matches=f.history("旧事","",10).getJSONArray("matches");assertEquals(1,matches.length());assertEquals(own.getString("id"),matches.getJSONObject(0).getString("id"));
            assertThrows(IllegalArgumentException.class,()->f.memory.handle("searchHistory",new JSONObject().put("scope",scope()).put("query","旧事")));
            for(String key:new String[]{"scope","audience","projectId","spaceId","ownerId"})assertThrows(SecurityException.class,()->f.owner.memory("searchHistory",new JSONObject().put("query","旧事").put(key,"model supplied")));
        }
    }
    @Test public void futureAndImaginedLabelsCannotDonateIdentityAliasesToRealHistoricalMatches()throws Exception {
        try(Fixture f=new Fixture()){
            f.save(fact("曾在A城","A城",100,100));JSONObject imaginary=fact("仅想象的世界","虚构城",200,200);imaginary.getJSONObject("assertion").put("kind","imagined").put("subjectLabel","fiction-alias").put("predicate","想象关系");f.save(imaginary);JSONObject future=fact("尚未生效的计划","未来城",300,System.currentTimeMillis()+60000);future.getJSONObject("assertion").put("subjectLabel","future-alias").put("predicate","未来计划");f.save(future);
            assertEquals(0,f.history("fiction-alias","",10).getJSONArray("matches").length());assertEquals(0,f.history("future-alias","",10).getJSONArray("matches").length());
        }
    }
    @Test public void restoredUnknownArchiveIsCountedButNeverSilentlyActivatedAsHistoryEvidence()throws Exception {
        JSONObject archive;
        try(Fixture f=new Fixture()){f.save(new JSONObject().put("text","离线包内旧事"));archive=f.memory.ownerArchive(scope());}
        try(Fixture f=new Fixture()){
            f.memory.restoreOwnerArchive(scope(),archive,"history-fixture-native-import");JSONObject page=f.history("旧事","",10);assertFalse(page.optBoolean("unsupported"));assertEquals(0,page.getJSONArray("matches").length());assertTrue(page.getInt("unverifiedArchiveRecords")>0);assertFalse(page.getBoolean("archiveOnlyActivated"));assertFalse(page.getBoolean("originalEvidenceRead"));
        }
    }
    @Test public void previewsAreBoundedAndExplicitlyNotOriginalWhileExactVersionReadsKeepFullStoredText()throws Exception {
        try(Fixture f=new Fixture()){
            String text="旧事"+"甲".repeat(277)+"🙂"+"乙".repeat(300);JSONObject row=f.save(new JSONObject().put("text",text));JSONObject hit=first(f.history("旧事","",1));String preview=hit.getString("textPreview");assertTrue(preview.length()<=280);assertFalse(Character.isHighSurrogate(preview.charAt(preview.length()-1)));assertTrue(hit.getBoolean("previewTruncated"));assertFalse(hit.getBoolean("memoryTextIsOriginalEvidence"));assertFalse(hit.has("subjectId"));assertTrue(hit.isNull("eventTime"));assertEquals(text,f.read(hit).getJSONObject("record").getString("text"));
            f.save(new JSONObject(row.toString()).put("text","修正后的另一件事"));assertThrows(IllegalStateException.class,()->f.read(hit));
        }
    }
}
