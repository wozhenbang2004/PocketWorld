package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** New recovery implementation evidence only: real original SQLite/Book, synthetic returned JSON.
 * No live SSH/account/model/Android-device or pre-outage validation is inherited. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ToolOutputStoreNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static MemoryBook.Scope identity()throws Exception{return MemoryBook.Scope.from(scope());}
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();TaskStore tasks;SQLiteDatabase db;AttachmentBook book;
        String id,run;int epoch;RunControl control=new RunControl();
        Fixture()throws Exception{context.deleteDatabase("pocket-world.db");open();newTask();}
        void open()throws Exception{tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);book=AttachmentRepository.get(context).book;}
        void newTask()throws Exception{id=tasks.createCapabilityTask("真实SQLite原输出恢复夹具","").getString("id");activate(tasks.startAttempt(id,"capabilities",10));}
        void activate(JSONObject task)throws Exception{run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control=new RunControl();control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("ssh_read_file").put("ssh_exec").put("attachments_read_document"));}
        ToolOutputCapture.Factory factory(){return tasks.toolOutputFactory(id,run,epoch,control,value->ModelGateway.redact(value,"captured-secret-C4"));}
        JSONObject capture(String tool,JSONObject data)throws Exception{String operation=tool.startsWith("ssh_")?tasks.journal(id,run,epoch,control).beginOperation(tool,new JSONObject().put("path","fixture.txt")):"";JSONObject receipt=factory().begin(tool,operation,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,control).record(data,false).metadata();if(!operation.isEmpty())tasks.journal(id,run,epoch,control).finishOperation(operation,new JSONObject().put("status","completed").put("outputCapture",receipt));return receipt.getJSONArray("outputRefs").getJSONObject(0);}
        JSONObject imported(String text)throws Exception{return book.importBytes(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),"source.txt","text/plain",new JSONObject().put("kind","owner_fixture"));}
        JSONObject document(JSONObject source,String text)throws Exception{return new JSONObject().put("attachmentId",source.getString("id")).put("sha256",source.getString("sha256")).put("status","ok").put("text",text);}
        TaskStoreMemoryReader.RawBinding binding(ToolOutputStore.Sources sources)throws Exception{TaskStoreMemoryReader.RawBinding value=new TaskStoreMemoryReader.RawBinding(id,run,epoch,control,new MemoryHostFence(){public void check()throws Exception{control.check();}public JSONObject admit(Admission action)throws Exception{return action.run();}},()->0);value.bindOutputSources(sources);return value;}
        JSONObject request(JSONObject ref,int offset,int max)throws Exception{return new JSONObject().put("sourceId",ref.getString("sourceId")).put("revision",ref.getString("revision")).put("offset",offset).put("maxChars",max);}
        JSONObject read(JSONObject ref,int offset,int max)throws Exception{JSONObject input=request(ref,offset,max);return TaskMemoryTrace.checkedRawSource(tasks.readMemoryRaw(binding(tasks.toolOutputSources()),input),id,run,input);}
        JSONObject audit(JSONObject ref,int offset,int max)throws Exception{return tasks.readNativeOutputAudit(identity(),request(ref,offset,max).put("taskId",ref.getString("taskId")).put("runId",ref.getString("runId")),new RunControl());}
        long count(String table)throws Exception{try(Cursor row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
        void reopenColdBook()throws Exception{db.close();var field=AttachmentRepository.class.getDeclaredField("INSTANCES");field.setAccessible(true);Map<?,?> books=(Map<?,?>)field.get(null);books.remove(context.getFilesDir().getAbsolutePath());open();}
        public void close(){db.close();}
    }
    @Test public void largeRetainedSafeJsonReads64CharactersWithoutMaterializing600KiB()throws Exception{
        try(Fixture f=new Fixture()){
            String large="A".repeat(600*1024)+" END_NATIVE_CAUSE";JSONObject ref=f.capture("ssh_read_file",new JSONObject().put("content",large).put("status","completed"));
            JSONObject page=f.read(ref,32,64);assertEquals("current",page.getString("status"));assertEquals(64,page.getString("body").length());assertTrue(page.getJSONObject("readRange").getBoolean("hasMore"));
            assertTrue(page.getJSONObject("readBudget").getLong("outputBodyUtf8Bytes")<=8192);assertTrue(page.getJSONObject("readBudget").getLong("scannedUtf8Bytes")<65536);assertTrue(f.count("task_output_chunks")>100);
            assertEquals("exact_retained_safe_native_result_json_not_wire",page.getString("coverage"));assertFalse(page.getBoolean("completeOriginalTrace"));
        }
    }
    @Test public void previewIsAnActualUnicodeBoundaryAndSecretsAreCleanedBeforeSqlStorage()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject ref=f.capture("ssh_read_file",new JSONObject().put("content","A".repeat(3987)+"🙂"+" captured-secret-C4 TAIL"));
            int preview=ref.getJSONObject("retainedRange").getInt("previewChars");JSONObject page=f.read(ref,0,Math.max(64,preview));String text=page.getString("body");assertFalse(Character.isHighSurrogate(text.charAt(text.length()-1)));assertFalse(text.contains("captured-secret-C4"));
            try(Cursor rows=f.db.rawQuery("SELECT body FROM task_output_chunks",null)){while(rows.moveToNext())assertFalse(rows.getString(0).contains("captured-secret-C4"));}
            JSONObject changed=new JSONObject(ref.toString()).put("revision","sha256:"+"f".repeat(64));JSONObject unavailable=f.read(changed,0,64);assertEquals("source_changed",unavailable.getString("status"));assertFalse(unavailable.has("body"));
        }
    }
    @Test public void nativeAttachmentReservationClearsOnlyDefiniteNotDispatchedAndActualReturnCommitsDependencies()throws Exception{
        try(Fixture f=new Fixture()){
            ToolOutputCapture.Claim notEntered=f.factory().begin("attachments_read_document","",ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,f.control);
            assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.factory().checkSources(f.control));notEntered.notDispatched();f.factory().checkSources(f.control);
            JSONObject source=f.imported("真实附件原件");JSONObject ref=f.capture("attachments_read_document",f.document(source,"实际native文档片段"));f.factory().checkSources(f.control);f.book.remove(source.getString("id"));
            assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.factory().checkSources(f.control));JSONObject model=f.read(ref,0,64);assertFalse(model.has("body"));JSONObject audit=f.audit(ref,0,4000);assertEquals("current",audit.getString("status"));assertTrue(audit.getBoolean("auditOnly"));assertFalse(audit.getBoolean("modelSourceAdmission"));assertTrue(audit.getString("body").contains("实际native文档片段"));assertFalse(f.control.isCancelled());
        }
    }
    @Test public void bodyArchiveFailureDoesNotUndoAnAlreadyProvedNativeAttachmentDependency()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.imported("真实源");ToolOutputCapture.Factory broken=f.tasks.toolOutputFactory(f.id,f.run,f.epoch,f.control,value->{throw new java.io.IOException("fixture body storage cleaner failure");});
            ToolOutputCapture.Claim call=broken.begin("attachments_read_document","",ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,f.control);assertThrows(java.io.IOException.class,()->call.record(f.document(source,"真实返回"),false));
            assertEquals(0,f.count("task_output_sources"));f.factory().checkSources(f.control);f.book.remove(source.getString("id"));assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.factory().checkSources(f.control));
        }
    }
    @Test public void actualFormalPrepareVersionsSurviveCheckpointEvolutionAndOnlyIntersectingForgetWithdraws()throws Exception{
        try(Fixture f=new Fixture()){
            File file=new File(f.context.getFilesDir(),"memory-output-"+UUID.randomUUID()+".json");MemoryBook memory=new MemoryBook(file);JSONObject used=memory.save(scope(),new JSONObject().put("text","真实正式依据原版本").put("core",true),1);
            TaskMemoryUse.TaskRef task=new TaskMemoryUse.TaskRef(f.id,f.run,f.epoch,"owner","private");TaskMemoryUse use=new TaskMemoryUse(memory,new TaskStoreMemoryReader(f.tasks,null),null);
            TaskMemoryUse.Selection selected=use.prepareTaskRecall(scope(),task,"",16,12000,new MemoryHostFence(){public void check()throws Exception{f.control.check();}public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){return action.run();}}});
            TaskMemoryJournal.Intent prepared=TaskMemoryJournal.prepare(UUID.randomUUID().toString(),task,"native-request",selected);f.tasks.prepareMemoryReference(prepared,f.control);
            JSONObject ref=f.capture("ssh_read_file",new JSONObject().put("content","根据正式依据产生的实际工具原件"));f.tasks.journal(f.id,f.run,f.epoch,f.control).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content","最新检查点不再有原引用键")),0,new JSONObject());
            JSONObject unrelated=memory.save(scope(),new JSONObject().put("text","后新增的无关正式依据"),2);memory.forgetReviewed(scope(),unrelated.getString("id"),unrelated.getLong("revision"),gone->f.tasks.invalidateMemoryContexts(identity(),gone));f.factory().checkSources(f.control);
            memory.forgetReviewed(scope(),used.getString("id"),used.getLong("revision"),gone->f.tasks.invalidateMemoryContexts(identity(),gone));assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.factory().checkSources(f.control));assertFalse(f.read(ref,0,64).has("body"));assertTrue(f.audit(ref,0,4000).getString("body").contains("实际工具原件"));
            f.reopenColdBook();assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.tasks.warmToolOutputSources(f.id,new RunControl()));assertEquals(1,f.count("runs"));
        }
    }
    @Test public void coldBookRechecksActualUnchangedBytesOutsideTasksBeforeNewAttempt()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.imported("真实冷恢复附件");f.capture("attachments_read_document",f.document(source,"原始输出"));String originalRun=f.run;f.reopenColdBook();assertNull(f.book.outputSourceEligibility(source.getString("id"),source.getString("sha256")));
            RunControl next=new RunControl();f.tasks.warmToolOutputSources(f.id,next);assertNotNull(f.book.outputSourceEligibility(source.getString("id"),source.getString("sha256")));assertEquals(1,f.count("runs"));assertEquals(originalRun,f.tasks.get(f.id,false).getString("runId"));
            f.activate(f.tasks.startAttempt(f.id,"capabilities",10));f.factory().checkSources(f.control);assertNotEquals(originalRun,f.run);assertEquals(2,f.count("runs"));
        }
    }
    @Test public void coldChangedBytesCannotCreateNewRunOrWashTheInheritedDependency()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.imported("原始字节");f.capture("attachments_read_document",f.document(source,"原始输出"));String originalRun=f.run;
            Files.write(new File(f.context.getFilesDir(),"attachments/"+source.getString("id")+".blob").toPath(),"被改变字节".getBytes(StandardCharsets.UTF_8));f.reopenColdBook();
            assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.tasks.warmToolOutputSources(f.id,new RunControl()));assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->f.tasks.startAttempt(f.id,"capabilities",10));assertEquals(1,f.count("runs"));assertEquals(originalRun,f.tasks.get(f.id,false).getString("runId"));
        }
    }
    @Test public void deadlineRetainsAlreadyVerifiedHitsAndCursorDoesNotSkipUndeliveredOutput()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject source=f.imported("真实有界来源");JSONObject first=f.capture("attachments_read_document",f.document(source,"第一条已核命中")),second=f.capture("attachments_read_document",f.document(source,"第二条必须可续读"));
            // Stable, distinct keyset times in the actual original SQLite table.
            f.db.execSQL("UPDATE task_output_sources SET created_at=1 WHERE output_id=?",new Object[]{ToolOutputStore.outputId(f.id,f.run,first.getString("sourceId"))});f.db.execSQL("UPDATE task_output_sources SET created_at=2 WHERE output_id=?",new Object[]{ToolOutputStore.outputId(f.id,f.run,second.getString("sourceId"))});
            ToolOutputStore.Sources actual=f.tasks.toolOutputSources();AtomicInteger calls=new AtomicInteger();ToolOutputStore.Sources delayed=new ToolOutputStore.Sources(){public String attachmentGeneration(String id,String sha)throws Exception{return actual.attachmentGeneration(id,sha);}public boolean attachmentCurrent(String id,String sha,String generation)throws Exception{boolean current=actual.attachmentCurrent(id,sha,generation);if(calls.incrementAndGet()==5)Thread.sleep(550);return current;}};
            TaskStoreMemoryReader.RawBinding binding=f.binding(delayed);JSONObject options=new JSONObject().put("section","outputs").put("query","").put("limit",20).put("maxChars",16000);JSONObject page=TaskMemoryTrace.checkedRawPage(f.tasks.queryMemoryRaw(binding,options),f.id,f.run,options);
            assertEquals("read_deadline",page.getString("reason"));assertEquals(1,page.getJSONArray("entries").length());assertEquals(first.getString("sourceId"),page.getJSONArray("entries").getJSONObject(0).getString("sourceId"));assertTrue(page.getInt("charsReturned")>0);assertTrue(page.getBoolean("hasMore"));
            JSONObject nextOptions=new JSONObject(options.toString()).put("cursor",page.getString("nextCursor"));JSONObject next=TaskMemoryTrace.checkedRawPage(f.tasks.queryMemoryRaw(binding,nextOptions),f.id,f.run,nextOptions);assertEquals(second.getString("sourceId"),next.getJSONArray("entries").getJSONObject(0).getString("sourceId"));
        }
    }
    @Test public void ordinaryTaskWritesMustNotBecomeImplicitOutputDeletionWhenRecursiveTriggersAreEnabled()throws Exception{
        try(Fixture f=new Fixture()){
            JSONObject ref=f.capture("ssh_read_file",new JSONObject().put("content","不能被状态更新删除的原件"));
            for(String mode:new String[]{"OFF","ON"}){f.db.execSQL("PRAGMA recursive_triggers="+mode);f.tasks.status(f.id,"running","正常状态写入不是删除任务");f.tasks.event(f.id,new JSONObject().put("type","fixture-ordinary-audit-write").put("message",mode));assertEquals(1,f.count("task_output_sources"));assertEquals(1,f.count("task_output_chunks"));assertEquals("current",f.read(ref,0,64).getString("status"));}
        }
    }
    @Test public void v4UpgradePreservesOriginalRowsAndExplicitDeleteCascadesOnlyMatchingOutputs()throws Exception{
        try(Fixture f=new Fixture()){
            int currentSchemaVersion=f.db.getVersion();String oldTask=f.id,oldRun=f.run;f.tasks.journal(f.id,f.run,f.epoch,f.control).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content","原v4保留正文")),0,new JSONObject());f.tasks.event(f.id,new JSONObject().put("type","v4-fixture-original-audit").put("message","原审计"));
            // Simulates the missing v4 output tables, not a complete historical schema image.
            f.db.execSQL("DROP TRIGGER task_output_task_delete");f.db.execSQL("DROP TRIGGER task_output_run_delete");f.db.execSQL("DROP TRIGGER task_output_header_delete");f.db.execSQL("DROP TABLE task_output_chunks");f.db.execSQL("DROP TABLE task_output_sources");f.db.setVersion(4);f.db.close();f.open();assertEquals(currentSchemaVersion,f.db.getVersion());assertTrue(f.tasks.get(oldTask,false).getJSONArray("events").toString().contains("v4-fixture-original-audit"));
            f.newTask();JSONObject other=f.capture("ssh_read_file",new JSONObject().put("content","别的任务必须保留"));String otherTask=f.id;
            for(String mode:new String[]{"OFF","ON"}){
                f.db.execSQL("PRAGMA recursive_triggers="+mode);f.newTask();JSONObject first=f.capture("ssh_read_file",new JSONObject().put("content","本任务首run输出"));String firstRun=f.run;
                f.tasks.status(f.id,"paused","fixture owner explicitly continues");f.activate(f.tasks.startAttempt(f.id,"capabilities",10));JSONObject sameTaskNextRun=f.capture("ssh_read_file",new JSONObject().put("content","本任务另一run必须保留"));assertEquals(3,f.count("task_output_sources"));
                f.db.delete("runs","run_id=?",new String[]{firstRun});assertEquals(2,f.count("task_output_sources"));assertEquals(2,f.count("task_output_chunks"));assertEquals("current",f.read(sameTaskNextRun,0,64).getString("status"));assertEquals("current",f.audit(other,0,64).getString("status"));
                f.db.delete("tasks","task_id=?",new String[]{f.id});assertEquals(1,f.count("task_output_sources"));assertEquals(1,f.count("task_output_chunks"));assertEquals("current",f.audit(other,0,64).getString("status"));
            }
            f.db.delete("tasks","task_id=?",new String[]{otherTask});assertEquals(0,f.count("task_output_sources"));assertEquals(0,f.count("task_output_chunks"));assertNotNull(f.tasks.get(oldTask,false));try(Cursor saved=f.db.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{oldRun})){assertTrue(saved.moveToFirst());assertTrue(saved.getString(0).contains("原v4保留正文"));}
        }
    }
}
