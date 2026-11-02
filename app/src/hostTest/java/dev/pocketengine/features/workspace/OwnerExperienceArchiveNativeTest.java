package dev.pocketengine.features.workspace;

import android.app.Application;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.ImportedExperienceEvidence;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real original SQLite + original archive ZIP. No device/provider/network acceptance claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerExperienceArchiveNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static String hash(String text)throws Exception{return dev.pocketengine.features.local.project.ProjectSources.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final MemoryModule memory;final OwnerExperienceArchive archive;
        Fixture()throws Exception{this(true);}
        Fixture(boolean initialize)throws Exception{context.deleteDatabase("pocket-world.db");File book=new File(context.getFilesDir(),"memory/records.json");Files.deleteIfExists(book.toPath());tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);if(initialize)tasks.conversationRepository().initializeEmpty();memory=new MemoryModule(new FeatureContext(context,null,null,event->{}));archive=new OwnerExperienceArchive(tasks,memory);}
        SourceRef message(String id,String role,String text,long at)throws Exception {
            var repo=tasks.conversationRepository();SourceRef ref=new SourceRef("message",id,1,"owner","private");repo.transaction(()->{repo.saveConversation(new JSONObject().put("conversationId","source-conversation").put("createdAt",at).put("updatedAt",at));repo.saveSource(new JSONObject(ref.toJson().toString()).put("conversationId","source-conversation").put("role",role).put("deleted",false));repo.saveMessage("source-conversation",new JSONObject().put("id",id).put("revision",1L).put("role",role).put("content",text).put("at",at).put("attachments",new JSONArray().put(new JSONObject().put("id","old-attachment").put("sha256","a".repeat(64)))));return null;});return ref;
        }
        void migrate(String raw)throws Exception {
            byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);String source=hash(raw),stage=UUID.randomUUID().toString();var originals=tasks.conversationRepository();JSONObject counts=originals.stageLegacyArray(stage,bytes,source,()->null);
            synchronized(tasks){db.beginTransaction();try{originals.activateStaged(stage,source,counts);db.setTransactionSuccessful();}finally{db.endTransaction();}}originals.activatedAfterCommit();
        }
        List<JSONObject> importedRows()throws Exception{List<JSONObject> rows=new ArrayList<>();String cursor="";do{JSONObject page=tasks.conversationRepository().ownerExperiencePage(cursor,gate(),new RunControl());for(int i=0;i<page.getJSONArray("entries").length();i++){JSONObject row=page.getJSONArray("entries").getJSONObject(i);if("imported_message".equals(row.optString("kind")))rows.add(row);}cursor=page.getString("nextCursor");}while(!cursor.isEmpty());return rows;}
        dev.pocketengine.store.OwnerExperienceProjection.Gate gate(){return new dev.pocketengine.store.OwnerExperienceProjection.Gate(){public boolean blocked(SourceRef source){return false;}public ToolOutputStore.Sources outputSources(){return tasks.toolOutputSources();}};}
        JSONObject capture(Map<String,JSONObject> objects)throws Exception{return archive.exportObjects(object->{String sha=dev.pocketengine.features.local.project.ProjectSources.digest(object);objects.put(sha,new JSONObject(object.toString()));return sha;},new RunControl());}
        public void close(){memory.close();db.close();}
    }
    @Test public void archiveAloneOpensExactOldSentenceTimeAndAttachmentHashWithoutActivatingHistory()throws Exception {
        JSONObject section;Map<String,JSONObject> objects=new LinkedHashMap<>();String text="当时住A城，因为成本和离家近选择X";
        try(Fixture source=new Fixture()){source.message("original-message","user",text,123456L);section=source.capture(objects);assertTrue(section.getJSONObject("coverage").getBoolean("originalMessageBodies"));}
        // New original instance; source DB is deleted, only the actual archive object pages remain.
        try(Fixture target=new Fixture()){
            JSONObject opened=target.archive.readPage(section,0,sha->{JSONObject value=objects.get(sha);if(value==null)throw new SecurityException("missing original chunk");return new JSONObject(value.toString());},new RunControl());
            JSONObject row=opened.getJSONObject("data").getJSONArray("entries").getJSONObject(0);assertEquals(text,row.getString("text"));assertEquals(123456L,row.getLong("at"));assertEquals("a".repeat(64),row.getJSONArray("attachments").getJSONObject(0).getString("sha256"));assertTrue(row.getBoolean("archiveOnly"));assertFalse(opened.getBoolean("executionAuthorized"));
            assertNull(target.tasks.conversationRepository().metadata("original-message"));assertEquals(0,target.memory.contextSnapshot(scope(),"A城",16,12000).getJSONArray("records").length());
        }
    }
    @Test public void sourceRoleAndExactSpanAreProvedByOriginalSQLiteNotModelLabels()throws Exception {
        try(Fixture f=new Fixture()){
            String text="现在住B城";SourceRef user=f.message("user-message","user",text,100),assistant=f.message("assistant-message","assistant",text,101);
            f.tasks.conversationRepository().requireUserSaidSpan(user,0,text.length(),hash(text),text,100L);
            assertThrows(SecurityException.class,()->f.tasks.conversationRepository().requireUserSaidSpan(user,0,text.length(),hash(text),text,999L));
            assertThrows(SecurityException.class,()->f.tasks.conversationRepository().requireUserSaidSpan(assistant,0,text.length(),hash(text),text));
            assertThrows(SecurityException.class,()->f.tasks.conversationRepository().requireUserSaidSpan(user,0,text.length(),hash(text),"用户批准了其他事"));
            assertThrows(SecurityException.class,()->f.tasks.conversationRepository().requireUserSaidSpan(user,0,text.length(),"b".repeat(64),text));
        }
    }
    @Test public void knownLocalDeletionAndPackagedBarrierSuppressOldArchiveBodies()throws Exception {
        try(Fixture f=new Fixture()){
            SourceRef ref=f.message("forget-message","user","不能从旧备份复活",100);Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);String originalPage=section.getJSONArray("pages").getJSONObject(0).getString("object");
            f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().saveSource(new JSONObject(ref.toJson().toString()).put("revision",2L).put("conversationId","source-conversation").put("role","user").put("deleted",true));return null;});
            JSONObject blocked=f.archive.readPage(section,0,objects::get,new RunControl());assertFalse(blocked.toString().contains("不能从旧备份复活"));assertEquals("blocked_by_known_source_barrier",blocked.getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("status"));
            assertEquals("Filtering must not mutate the original content-addressed page",originalPage,dev.pocketengine.features.local.project.ProjectSources.digest(objects.get(originalPage)));
            blocked.getJSONObject("data").put("callerOnly",true);assertEquals("Returned projections must not alias archive objects",originalPage,dev.pocketengine.features.local.project.ProjectSources.digest(objects.get(originalPage)));
            JSONObject packaged=new JSONObject(section.toString());packaged.getJSONObject("barriers").getJSONArray("sourceBarriers").put(new JSONObject(ref.toJson().toString()).put("deleted",false).put("forgottenThrough",1L));packaged.put("sectionDigest",MemoryArchive.sectionDigest(packaged));assertFalse(f.archive.readPage(packaged,0,objects::get,new RunControl()).toString().contains("不能从旧备份复活"));
            assertEquals(originalPage,dev.pocketengine.features.local.project.ProjectSources.digest(objects.get(originalPage)));
        }
        packagedForgetBarrierAlsoWinsOnAnEmptyColdTarget();
    }
    private void packagedForgetBarrierAlsoWinsOnAnEmptyColdTarget()throws Exception {
        JSONObject section;Map<String,JSONObject> objects=new LinkedHashMap<>();
        try(Fixture source=new Fixture()){SourceRef ref=source.message("package-forgotten","user","包内遗忘不能洗成新来源",100);section=source.capture(objects);section.getJSONObject("barriers").getJSONArray("sourceBarriers").put(new JSONObject(ref.toJson().toString()).put("deleted",false).put("forgottenThrough",1L));section.put("sectionDigest",MemoryArchive.sectionDigest(section));}
        try(Fixture target=new Fixture()){assertNull(target.tasks.conversationRepository().metadata("package-forgotten"));JSONObject page=target.archive.readPage(section,0,objects::get,new RunControl());assertFalse(page.toString().contains("包内遗忘不能洗成新来源"));assertEquals("blocked_by_known_source_barrier",page.getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("status"));}
    }
    @Test public void pageHashCorruptionAndMissingOriginalPageAreRejectedNotPartialRestore()throws Exception {
        try(Fixture f=new Fixture()){
            f.message("hash-message","user","原始字节",100);Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);String first=section.getJSONArray("pages").getJSONObject(0).getString("object");JSONObject changed=new JSONObject(objects.get(first).toString());changed.getJSONObject("data").getJSONArray("entries").getJSONObject(0).put("text","被换掉");
            assertThrows(SecurityException.class,()->f.archive.readPage(section,0,sha->sha.equals(first)?changed:objects.get(sha),new RunControl()));
            assertThrows(Exception.class,()->f.archive.reviewObjects(section,"native-action",sha->{if(sha.equals(first))throw new IOException("missing chunk");return objects.get(sha);},new RunControl()));
            assertEquals(0,f.memory.contextSnapshot(scope(),"原始字节",16,12000).getJSONArray("records").length());
        }
    }
    @Test public void realTaskAuditAndSafeOutputChunksRemainPassiveAndMissingMiddleChunkFails()throws Exception {
        try(Fixture f=new Fixture()){
            String task=f.tasks.createCapabilityTask("原操作与决定依据","").getString("id");JSONObject attempt=f.tasks.startAttempt(task,"capabilities",10);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl control=new RunControl();control.bindHostRun(task,run,epoch);f.tasks.authorizeTools(task,run,new JSONArray().put("ssh_read_file"));
            String operation=f.tasks.journal(task,run,epoch,control).beginOperation("ssh_read_file",new JSONObject().put("path","original.txt").put("sessionToken","DO_NOT_EXPORT_SESSION").put("reasoning_content","HIDDEN_PROVIDER_THOUGHT"));
            JSONObject captured=f.tasks.toolOutputFactory(task,run,epoch,control,value->value).begin("ssh_read_file",operation,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,control).record(new JSONObject().put("text","原工具安全返回".repeat(2000)).put("effect","unknown"),false).metadata();
            f.tasks.journal(task,run,epoch,control).finishOperation(operation,new JSONObject().put("status","completed").put("outputCapture",captured));
            String protectedOperation=f.tasks.journal(task,run,epoch,control).beginOperation("ssh_read_file",new JSONObject().put("path","protected.txt"));JSONObject protectedCapture=f.tasks.toolOutputFactory(task,run,epoch,control,value->value).begin("ssh_read_file",protectedOperation,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,control).record(new JSONObject().put("sessionToken","OUTPUT_TOKEN_MARKER"),false).metadata();f.tasks.journal(task,run,epoch,control).finishOperation(protectedOperation,new JSONObject().put("status","completed").put("outputCapture",protectedCapture));
            Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);assertTrue(section.getJSONObject("counts").getInt("outputChunks")>1);assertTrue(section.getJSONObject("counts").getInt("auditEntries")>0);assertFalse(objects.toString().contains("DO_NOT_EXPORT_SESSION"));assertFalse(objects.toString().contains("HIDDEN_PROVIDER_THOUGHT"));assertFalse(objects.toString().contains("OUTPUT_TOKEN_MARKER"));assertTrue(section.getJSONArray("gaps").toString().contains("protected_native_fields_excluded"));
            int outputPage=-1;for(int i=0;i<section.getJSONArray("pages").length();i++)if("outputs".equals(section.getJSONArray("pages").getJSONObject(i).getString("kind"))){outputPage=i;break;}assertTrue(outputPage>=0);JSONObject opened=f.archive.readPage(section,outputPage,objects::get,new RunControl());assertTrue(opened.toString().contains("unknown"));assertEquals(0,opened.getInt("restoredTasks"));assertFalse(opened.getBoolean("executionAuthorized"));
            String output;try(Cursor row=f.db.rawQuery("SELECT output_id FROM task_output_sources WHERE task_id=? AND retained_chars>1000",new String[]{task})){assertTrue(row.moveToFirst());output=row.getString(0);}assertEquals(1,f.db.delete("task_output_chunks","output_id=? AND chunk_index=1",new String[]{output}));assertThrows(SecurityException.class,()->f.capture(new LinkedHashMap<>()));
        }
    }
    @Test public void writerRaceWithDeletionRefusesPublishingTheOldPage()throws Exception {
        try(Fixture f=new Fixture()){
            SourceRef ref=f.message("raced-message","user","删除中的原文",100);boolean[] changed={false};
            assertThrows(SecurityException.class,()->f.archive.exportObjects(object->{if(!changed[0]){changed[0]=true;f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().saveSource(new JSONObject(ref.toJson().toString()).put("revision",2L).put("conversationId","source-conversation").put("role","user").put("deleted",true));return null;});}return dev.pocketengine.features.local.project.ProjectSources.digest(object);},new RunControl()));
        }
    }
    private static String legacy(String first,String second)throws Exception{return "[{\"conversationId\":\"legacy-conversation\",\"messages\":[{\"id\":\"old-unverified-id\",\"role\":\"user\",\"at\":\"unknown old time\",\"content\":\""+first+"\",\"attachments\":[{\"sha256\":\""+"a".repeat(64)+"\"}]},{\"role\":\"assistant\",\"revision\":7,\"at\":123,\"content\":\""+second+"\"}]}]";}
    private static List<JSONObject> archivedMessages(JSONObject section,Map<String,JSONObject> objects)throws Exception{List<JSONObject> rows=new ArrayList<>();JSONArray pages=section.getJSONArray("pages");for(int i=0;i<pages.length();i++){JSONObject descriptor=pages.getJSONObject(i);if(!"messages".equals(descriptor.getString("kind")))continue;JSONArray entries=objects.get(descriptor.getString("object")).getJSONObject("data").getJSONArray("entries");for(int j=0;j<entries.length();j++)rows.add(entries.getJSONObject(j));}return rows;}
    @Test public void migratedOnlyOriginalFragmentsReadBackOnEmptyInstanceWithoutInventingSourcesOrDates()throws Exception {
        JSONObject section;Map<String,JSONObject> objects=new LinkedHashMap<>();String original=legacy("旧原句仍应能打开","旧助手答复");
        try(Fixture source=new Fixture(false)){
            source.migrate(original);assertTrue(source.tasks.conversationRepository().active());List<JSONObject> originals=source.importedRows();assertEquals(2,originals.size());section=source.capture(objects);assertEquals(2,section.getJSONObject("counts").getInt("messages"));assertEquals(2,section.getJSONObject("coverage").getInt("importedMessageCount"));assertEquals(0,section.getJSONObject("coverage").getInt("nativeMessageCount"));assertEquals(0,section.getJSONArray("gaps").length());
            for(JSONObject row:archivedMessages(section,objects)){assertFalse(row.has("sourceRef"));assertFalse(row.has("revision"));assertFalse(row.getBoolean("formalSourceRefAvailable"));assertTrue(row.getBoolean("importAssignedIdentity"));assertEquals(hash(original),row.getJSONObject("importedLocator").getString("sourceSha256"));ImportedExperienceEvidence.checkedRow(row);if(row.getString("text").equals("旧原句仍应能打开")){assertTrue(row.isNull("historicalRevision"));assertTrue(row.isNull("at"));assertFalse(row.getBoolean("historicalTimeKnown"));assertEquals("a".repeat(64),row.getJSONArray("originalAttachmentMetadata").getJSONObject(0).getString("sha256"));}}
        }
        try(Fixture target=new Fixture()){
            List<JSONObject> shown=new ArrayList<>();for(int p=0;p<section.getJSONArray("pages").length();p++){JSONObject page=target.archive.readPage(section,p,objects::get,new RunControl());if(!"messages".equals(page.getString("kind")))continue;JSONArray rows=page.getJSONObject("data").getJSONArray("entries");for(int i=0;i<rows.length();i++)shown.add(rows.getJSONObject(i));}assertEquals(2,shown.size());assertTrue(shown.stream().anyMatch(row->"旧原句仍应能打开".equals(row.optString("text"))));assertEquals(0,target.memory.contextSnapshot(scope(),"旧原句",16,12000).getJSONArray("records").length());assertNull(target.tasks.conversationRepository().metadata("old-unverified-id"));
            JSONObject packaged=new JSONObject(section.toString()),barrier=new JSONObject(archivedMessages(section,objects).get(0).getJSONObject("importedLocator").toString());barrier.put("recordVersion",barrier.getLong("recordVersion")+1);packaged.getJSONObject("coverage").getJSONArray("importedDeletionBarriers").put(barrier);packaged.put("sectionDigest",MemoryArchive.sectionDigest(packaged));JSONObject blocked=target.archive.readPage(packaged,0,objects::get,new RunControl());assertEquals("blocked_by_known_source_barrier",blocked.getJSONObject("data").getJSONArray("entries").getJSONObject(0).getString("status"));assertFalse(blocked.toString().contains("旧原句仍应能打开"));
        }
    }
    @Test public void mixedNativeAndMigratedPaginationPinsBothTablesAndDoesNotDuplicateOrIncludeNewTail()throws Exception {
        try(Fixture f=new Fixture(false)){
            JSONArray messages=new JSONArray();for(int i=0;i<25;i++)messages.put(new JSONObject().put("role",i%2==0?"user":"assistant").put("content","legacy-"+i));f.migrate(new JSONArray().put(new JSONObject().put("conversationId","legacy-conversation").put("messages",messages)).toString());for(int i=0;i<26;i++)f.message(String.format(java.util.Locale.ROOT,"native-%03d",i),"user","native-"+i,i);
            JSONObject first=f.tasks.conversationRepository().ownerExperiencePage("",f.gate(),new RunControl());assertEquals(20,first.getJSONArray("entries").length());String cursor=first.getString("nextCursor");Set<String> ids=new HashSet<>();int nativeRows=0,importedRows=0;
            for(int i=0;i<first.getJSONArray("entries").length();i++){JSONObject row=first.getJSONArray("entries").getJSONObject(i);assertTrue(ids.add("native:"+row.getJSONObject("sourceRef").getString("sourceId")));nativeRows++;}f.message("native-new-tail","user","not-in-pinned-pages",999);
            while(!cursor.isEmpty()){JSONObject page=f.tasks.conversationRepository().ownerExperiencePage(cursor,f.gate(),new RunControl());for(int i=0;i<page.getJSONArray("entries").length();i++){JSONObject row=page.getJSONArray("entries").getJSONObject(i);if("imported_message".equals(row.getString("kind"))){assertFalse(row.has("sourceRef"));assertTrue(ids.add("import:"+row.getString("recordId")));importedRows++;}else{assertFalse(row.getString("text").equals("not-in-pinned-pages"));assertTrue(ids.add("native:"+row.getJSONObject("sourceRef").getString("sourceId")));nativeRows++;}}cursor=page.getString("nextCursor");}assertEquals(26,nativeRows);assertEquals(25,importedRows);assertEquals(51,ids.size());
        }
    }
    @Test public void importedDeletionBeforeDuringAndAfterCaptureCannotReviveOldFragment()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.migrate(legacy("先删除的消息","包生成后删除的消息"));List<JSONObject> originals=f.importedRows();JSONObject removed=originals.stream().filter(row->row.optString("text").equals("先删除的消息")).findFirst().get();f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().deleteImportedMessage(removed.getString("conversationId"),removed.getString("recordId"),removed.getLong("recordVersion"));return null;});Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);assertEquals(1,section.getJSONObject("counts").getInt("messages"));assertTrue(section.getJSONArray("gaps").toString().contains("imported_record_deleted"));assertEquals(1,section.getJSONObject("coverage").getJSONArray("importedDeletionBarriers").length());JSONObject survivor=archivedMessages(section,objects).get(0);f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().deleteImportedMessage(survivor.getString("conversationId"),survivor.getString("recordId"),survivor.getLong("recordVersion"));return null;});JSONObject blocked=f.archive.readPage(section,0,objects::get,new RunControl());assertFalse(blocked.toString().contains("包生成后删除的消息"));assertFalse(blocked.toString().contains("originalFragmentBase64"));
        }
        try(Fixture f=new Fixture(false)){
            f.migrate(legacy("整段会话删除前的原句","conversation reply"));Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().deleteConversation("legacy-conversation","user_conversation_delete");return null;});JSONObject blocked=f.archive.readPage(section,0,objects::get,new RunControl());assertFalse(blocked.toString().contains("整段会话删除前的原句"));assertFalse(blocked.toString().contains("originalFragmentBase64"));
        }
        try(Fixture f=new Fixture(false)){
            f.migrate(legacy("迁移导入删除竞争","second"));JSONObject row=f.importedRows().get(0);boolean[] changed={false};assertThrows(SecurityException.class,()->f.archive.exportObjects(object->{if(!changed[0]){changed[0]=true;f.tasks.conversationRepository().transaction(()->{f.tasks.conversationRepository().deleteImportedMessage(row.getString("conversationId"),row.getString("recordId"),row.getLong("recordVersion"));return null;});}return dev.pocketengine.features.local.project.ProjectSources.digest(object);},new RunControl()));
        }
    }
    @Test public void tamperedMissingOrProtectedImportedFragmentFailsClosedAndCannotGainSourceIdentity()throws Exception {
        try(Fixture f=new Fixture(false)){
            f.migrate(legacy("原始迁移原件段","second"));JSONObject row=f.importedRows().get(0),locator=row.getJSONObject("importedLocator");Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);JSONObject changed=new JSONObject(row.toString()).put("sourceRef",new JSONObject().put("kind","message"));assertThrows(SecurityException.class,()->ImportedExperienceEvidence.checkedRow(changed));
            ContentValues corrupt=new ContentValues();corrupt.put("data","{}".getBytes(StandardCharsets.UTF_8));f.db.update("assistant_import_fragments",corrupt,"record_id=?",new String[]{row.getString("recordId")});assertThrows(SecurityException.class,()->f.capture(new LinkedHashMap<>()));assertTrue(f.tasks.conversationRepository().ownerImportedExperienceBlocked(locator));f.db.delete("assistant_import_fragments","record_id=?",new String[]{row.getString("recordId")});assertThrows(SecurityException.class,()->f.capture(new LinkedHashMap<>()));
            JSONObject invalid=new JSONObject(row.toString()).put("originalFragmentBase64",Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));assertThrows(SecurityException.class,()->ImportedExperienceEvidence.checkedRow(invalid));
        }
        try(Fixture f=new Fixture(false)){
            f.migrate("[{\"conversationId\":\"legacy-conversation\",\"messages\":[{\"role\":\"assistant\",\"content\":\"visible\",\"reasoning_content\":\"PROTECTED_IMPORTED_REASONING\"}]}]");Map<String,JSONObject> objects=new LinkedHashMap<>();JSONObject section=f.capture(objects);assertEquals(0,section.getJSONObject("counts").getInt("messages"));assertTrue(section.getJSONArray("gaps").toString().contains("protected_imported_fields_excluded"));assertFalse(objects.toString().contains("PROTECTED_IMPORTED_REASONING"));
        }
    }

}
