package dev.pocketengine.store;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.memory.SourceRef;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Original SQLite/schema/repository paths. Requires the real Robolectric native SQLite runtime. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskDatabaseMaintenanceNativeTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static String id(){return UUID.randomUUID().toString();}

    /** Changes only fixture paths; SQLite operations remain the actual Android API. */
    private static final class PrivateContext extends ContextWrapper {
        final File files,databases,noBackup;
        PrivateContext(Context base,File root)throws Exception{super(base);files=new File(root,"files");databases=new File(root,"databases");noBackup=new File(root,"no-backup");Files.createDirectory(files.toPath());Files.createDirectory(databases.toPath());Files.createDirectory(noBackup.toPath());}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return files;}
        @Override public File getNoBackupFilesDir(){return noBackup;}
        @Override public File getDatabasePath(String name){File selected=new File(name);return selected.isAbsolute()?selected:new File(databases,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openDatabase(getDatabasePath(name).getAbsolutePath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return openOrCreateDatabase(name,mode,factory,new TaskDatabaseCorruptionHandler(null));}
    }
    private final class Fixture {
        final PrivateContext context=new PrivateContext(RuntimeEnvironment.getApplication(),temporary.newFolder().getCanonicalFile());
        final File database=context.getDatabasePath(TaskStore.DATABASE_NAME);
        final AtomicLong generation=new AtomicLong(7);
        final AtomicInteger stops=new AtomicInteger();
        final AtomicBoolean present=new AtomicBoolean(true);
        final BooleanSupplier owner=present::get;
        final TaskDatabaseMaintenance.Host host=new TaskDatabaseMaintenance.Host(){public long generation(){return generation.get();}public boolean quiesce(){stops.incrementAndGet();return true;}};
        final TaskDatabaseMaintenance maintenance;
        Fixture(String cause)throws Exception{TaskStore.createMaintenanceStaging(context,database,id());maintenance=new TaskDatabaseMaintenance(context,host);maintenance.failure(cause,"fixture",TaskStore.DATABASE_SCHEMA);}
        JSONObject input()throws Exception{JSONObject state=maintenance.snapshot();return new JSONObject().put("expectedGeneration",generation.get()).put("requestId",id()).put("repairId",state.opt("repairId"));}
        JSONObject exact()throws Exception{JSONObject state=maintenance.snapshot();return input().put("sourceFingerprint",state.getString("sourceFingerprint")).put("backupFingerprint",state.getString("backupFingerprint"));}
        JSONObject preserve()throws Exception{return maintenance.command("repair.preserve",input(),owner);}
        JSONObject review(String mode)throws Exception{return maintenance.command("repair.review",exact().put("mode",mode).put("sections",new JSONArray().put("conversations")).put("includeConversationContent",true),owner);}
        JSONObject confirmInput()throws Exception{return exact().put("reviewId",maintenance.snapshot().getJSONObject("review").getString("reviewId")).put("confirmed",true);}
        File repairDirectory()throws Exception{return new File(new File(context.getNoBackupFilesDir(),"database-maintenance"),maintenance.snapshot().getString("repairId"));}
        File candidate()throws Exception{return new File(new File(repairDirectory(),"staging-"+maintenance.snapshot().getString("stagingId")),TaskStore.DATABASE_NAME);}
        File state(){return new File(context.getNoBackupFilesDir(),"database-maintenance/current.json");}
        void conversation(String conversation,String source,String body)throws Exception{
            try(SQLiteDatabase db=open(database,false)){
                ConversationRepository repository=new ConversationRepository(db,new Object());repository.transaction(()->{
                    repository.saveConversation(new JSONObject().put("conversationId",conversation).put("title","旧会话").put("createdAt",1).put("updatedAt",1));
                    repository.saveSource(new SourceRef("message",source,1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));
                    repository.saveMessage(conversation,new JSONObject().put("id",source).put("revision",1).put("role","user").put("content",body).put("at",1));return null;
                });
            }
        }
    }
    private static SQLiteDatabase open(File file,boolean readOnly){return SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,readOnly?SQLiteDatabase.OPEN_READONLY:SQLiteDatabase.OPEN_READWRITE,new TaskDatabaseCorruptionHandler(null));}
    private static long count(SQLiteDatabase db,String table){try(Cursor rows=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(rows.moveToFirst());return rows.getLong(0);}}
    private static JSONObject read(File file)throws Exception{return new JSONObject(Files.readString(file.toPath()));}

    @Test public void corruptionCallbackNeverClosesDeletesOrRetriesEvenWhenObservationFails()throws Exception {
        Fixture f=new Fixture("database_locked");AtomicInteger callbacks=new AtomicInteger();
        try(SQLiteDatabase db=open(f.database,false)){
            var before=DatabaseFilePreservation.inspect(f.database);
            SQLiteDatabaseCorruptException failure=assertThrows(SQLiteDatabaseCorruptException.class,()->new TaskDatabaseCorruptionHandler(reason->{callbacks.incrementAndGet();throw new IllegalStateException("observer failed");}).onCorruption(db));
            assertEquals(1,callbacks.get());assertEquals(1,failure.getSuppressed().length);assertTrue(db.isOpen());assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(f.database).fingerprint());
        }
    }

    @Test public void wrongRepairCannotPreserveAndExactDuplicateDoesNotCopyAgain()throws Exception {
        Fixture f=new Fixture("suspected_corruption");JSONObject wrong=f.input().put("repairId",id());
        assertThrows(SecurityException.class,()->f.maintenance.command("repair.preserve",wrong,f.owner));assertEquals(0,f.stops.get());
        for(String suffix:new String[]{"-wal","-shm","-journal","-mjfixture"})Files.writeString(new File(f.database+suffix).toPath(),"original "+suffix);
        var before=DatabaseFilePreservation.inspect(f.database);JSONObject request=f.input();JSONObject preserved=f.maintenance.command("repair.preserve",request,f.owner);
        assertEquals("preserved",preserved.getString("phase"));assertEquals(before.fingerprint(),preserved.getString("backupFingerprint"));
        f.maintenance.command("repair.preserve",request,f.owner);assertEquals(1,f.stops.get());
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(f.database).fingerprint());
    }

    @Test public void maintenanceDoesNotCanonicalizeASymbolicDatabaseIntoAnotherOriginal()throws Exception {
        Fixture f=new Fixture("database_locked");File outside=new File(temporary.newFolder().getCanonicalFile(),TaskStore.DATABASE_NAME);Files.move(f.database.toPath(),outside.toPath());Files.createSymbolicLink(f.database.toPath(),outside.toPath());String originalHash=DatabaseFilePreservation.sha256(outside);
        TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(f.context,f.host);cold.failure("suspected_corruption","fixture",TaskStore.DATABASE_SCHEMA);
        JSONObject request=new JSONObject().put("expectedGeneration",f.generation.get()).put("requestId",id()).put("repairId",cold.snapshot().getString("repairId"));
        assertThrows(Exception.class,()->cold.command("repair.preserve",request,f.owner));assertEquals(originalHash,DatabaseFilePreservation.sha256(outside));assertTrue(Files.isSymbolicLink(f.database.toPath()));
    }

    @Test public void conversationTextRequiresARealExplicitBooleanSelection()throws Exception {
        Fixture f=new Fixture("suspected_corruption");f.conversation("source-conversation","source-message","private");f.preserve();
        JSONObject request=f.exact().put("mode","verified_copy").put("sections",new JSONArray().put("conversations"));
        assertThrows(SecurityException.class,()->f.maintenance.command("repair.review",request,f.owner));request.put("includeConversationContent","true");
        assertThrows(SecurityException.class,()->f.maintenance.command("repair.review",request,f.owner));assertEquals("preserved",f.maintenance.snapshot().getString("phase"));
    }

    @Test public void verifiedCopyExtractsReadableConversationAndReportsUnreadableOneWithNewIdentity()throws Exception {
        Fixture f=new Fixture("suspected_corruption");f.conversation("source-good","source-message-good","真实可读文字");f.conversation("source-bad","source-message-bad","不应冒用");
        try(SQLiteDatabase db=open(f.database,false)){db.execSQL("UPDATE assistant_conversations SET header_json='broken-json' WHERE conversation_id='source-bad'");}
        var original=DatabaseFilePreservation.inspect(f.database);f.preserve();JSONObject staged=f.review("verified_copy"),restoration=staged.getJSONObject("review").getJSONObject("restoration");
        assertEquals(2,restoration.getInt("sourceConversationCount"));assertEquals(1,restoration.getInt("conversationCount"));assertEquals(1,restoration.getInt("messageCount"));assertEquals(1,restoration.getInt("unreadableConversationCount"));assertTrue(restoration.getBoolean("partial"));
        assertTrue(restoration.getJSONArray("gaps").getJSONObject(0).isNull("recordCount"));assertTrue(restoration.get("sourceGeneration") instanceof String);
        String restored=restoration.getJSONArray("conversations").getJSONObject(0).getString("restoredConversationId");assertNotEquals("source-good",restored);assertEquals(original.fingerprint(),DatabaseFilePreservation.inspect(f.database).fingerprint());
        try(SQLiteDatabase db=open(f.candidate(),true)){
            for(String table:new String[]{"tasks","runs","operations","leases","assistant_sources","assistant_messages"})assertEquals(table,0,count(db,table));
            ConversationRepository repository=new ConversationRepository(db,new Object());JSONObject exported=repository.exportVerifiedReadOnlyCopy(new JSONObject().put("conversationId",restored).put("format","json"));assertTrue(exported.getString("content").contains("真实可读文字"));
            assertFalse(repository.header(restored).getBoolean("historicalIdentityVerified"));
        }
        JSONObject completed=f.maintenance.command("repair.confirm",f.confirmInput(),f.owner);assertEquals("completed",completed.getString("phase"));assertFalse(completed.getJSONObject("result").getBoolean("executionEnabled"));
        TaskStore.verifyMaintenanceStaging(f.database,completed.getString("repairId"));
        DatabaseFilePreservation.verify(new File(new File(f.repairDirectory(),"original-at-cutover"),TaskStore.DATABASE_NAME),original);
    }

    @Test public void zeroRecoverableMessagesNeverTurnsIntoSuccessfulEmptyRecovery()throws Exception {
        Fixture f=new Fixture("suspected_corruption");f.preserve();String fingerprint=DatabaseFilePreservation.inspect(f.database).fingerprint();
        assertThrows(IllegalStateException.class,()->f.review("verified_copy"));JSONObject state=f.maintenance.snapshot();assertEquals("review_failed",state.getString("phase"));assertEquals("verified_copy_no_recoverable_messages",state.getString("reasonCode"));assertTrue(state.isNull("review"));assertEquals(fingerprint,DatabaseFilePreservation.inspect(f.database).fingerprint());
    }

    @Test public void changedExtractionOrClosedWindowCannotConfirmStagedBytes()throws Exception {
        Fixture f=new Fixture("suspected_corruption");f.conversation("source-one","source-message","keep");f.preserve();f.review("verified_copy");String original=DatabaseFilePreservation.inspect(f.database).fingerprint();
        JSONObject confirm=f.confirmInput();f.present.set(false);assertThrows(SecurityException.class,()->f.maintenance.command("repair.confirm",confirm,f.owner));f.present.set(true);
        File extraction=new File(new File(f.repairDirectory(),"read-only-copy-"+f.maintenance.snapshot().getString("stagingId")),TaskDatabaseRecoveryManifest.exportName(0));Files.writeString(extraction.toPath(),"changed");
        assertThrows(Exception.class,()->f.maintenance.command("repair.confirm",confirm,f.owner));assertEquals(original,DatabaseFilePreservation.inspect(f.database).fingerprint());assertFalse(new File(f.repairDirectory(),"original-at-cutover").exists());
    }

    @Test public void retainedDirectoryPreparationMustSucceedBeforeTheFirstOriginalMove()throws Exception {
        Fixture f=new Fixture("suspected_corruption");f.preserve();f.review("empty_database");String original=DatabaseFilePreservation.inspect(f.database).fingerprint();
        File blocked=new File(f.repairDirectory(),"original-at-cutover");Files.writeString(blocked.toPath(),"do not replace");
        assertThrows(IllegalStateException.class,()->f.maintenance.command("repair.confirm",f.confirmInput(),f.owner));
        assertEquals("unknown",f.maintenance.snapshot().getString("phase"));assertEquals(0,f.maintenance.snapshot().getJSONArray("movedOriginalFiles").length());assertEquals(original,DatabaseFilePreservation.inspect(f.database).fingerprint());assertEquals("do not replace",Files.readString(blocked.toPath()));
    }

    @Test public void stagingRejectsNativeSourceAuthorityRows()throws Exception {
        Fixture f=new Fixture("suspected_corruption");File staging=new File(temporary.newFolder(),TaskStore.DATABASE_NAME);String repair=id();TaskStore.createMaintenanceStaging(f.context,staging,repair);
        try(SQLiteDatabase db=open(staging,false)){String generation;try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key='assistant_history_generation'",null)){row.moveToFirst();generation=row.getString(0);}db.execSQL("INSERT INTO assistant_sources(generation,source_id,conversation_id,revision,role,deleted,unavailable_reason,source_json) VALUES(?,?,?,1,'user',0,'','{}')",new Object[]{generation,"old-source","old-conversation"});}
        assertThrows(SecurityException.class,()->TaskStore.verifyMaintenanceStaging(staging,repair));
    }

    @Test public void stagingRejectsOldHistoryReadScopeMetaEvenWithNoRuntimeRows()throws Exception {
        Fixture f=new Fixture("suspected_corruption");File staging=new File(temporary.newFolder(),TaskStore.DATABASE_NAME);String repair=id();TaskStore.createMaintenanceStaging(f.context,staging,repair);
        try(SQLiteDatabase db=open(staging,false)){db.execSQL("INSERT INTO meta(key,value) VALUES('assistant.history_read_scope.old','{}')");}
        assertThrows(SecurityException.class,()->TaskStore.verifyMaintenanceStaging(staging,repair));
    }

    @Test public void readPublishesPhysicalEvidenceAndDisablesRetryWhenPreservedBytesChange()throws Exception {
        Fixture f=new Fixture("database_locked");JSONObject captured=f.preserve();assertTrue(captured.getBoolean("originalRetryAvailable"));
        File backup=new File(new File(f.repairDirectory(),"preserved"),TaskStore.DATABASE_NAME);Files.write(backup.toPath(),new byte[]{1});
        f.maintenance.command("repair.read",new JSONObject().put("repairId",captured.getString("repairId")),f.owner);
        JSONObject publicState=f.maintenance.snapshot();assertFalse(publicState.getBoolean("preservationVerified"));assertFalse(publicState.getBoolean("originalRetryAvailable"));assertFalse(publicState.getJSONObject("currentReadback").getBoolean("preservedMatchesCaptured"));assertEquals("[\"repair.read\"]",publicState.getJSONArray("allowedCommands").toString());
    }

    @Test public void interruptedAtomicRecordIsObservedWithoutDeletingOrReplayingIt()throws Exception {
        Fixture f=new Fixture("database_locked");JSONObject captured=f.preserve();byte[] prior=Files.readAllBytes(f.state().toPath());File pending=new File(f.state()+".new");Files.writeString(pending.toPath(),read(f.state()).put("phase","original_retry_prepared").toString());byte[] pendingBytes=Files.readAllBytes(pending.toPath());
        TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(f.context,f.host);assertEquals("unknown",cold.snapshot().getString("phase"));assertEquals(captured.getString("repairId"),cold.snapshot().getString("repairId"));
        assertThrows(SecurityException.class,()->TaskDatabaseMaintenance.requireBusinessOpen(f.context));assertThrows(SecurityException.class,()->cold.command("repair.review",f.exact().put("mode","empty_database"),f.owner));
        cold.command("repair.read",new JSONObject().put("repairId",captured.getString("repairId")),f.owner);
        assertArrayEquals(prior,Files.readAllBytes(f.state().toPath()));assertArrayEquals(pendingBytes,Files.readAllBytes(pending.toPath()));assertEquals(1,f.stops.get());
    }

    @Test public void nonCorruptOriginalRetryOpensOnceWithTheExactPermitAndCompletesBeforeReady()throws Exception {
        Fixture f=new Fixture("database_locked");f.preserve();JSONObject request=f.exact();
        var permit=f.maintenance.prepareOriginalRetry(request,f.owner,f.generation.get()+1);
        assertTrue(f.maintenance.observesOriginalRetry(request));assertFalse(f.maintenance.snapshot().getBoolean("originalRetryAvailable"));
        assertThrows(SecurityException.class,()->TaskDatabaseMaintenance.requireBusinessOpen(f.context));f.generation.incrementAndGet();AtomicReference<Throwable> otherThread=new AtomicReference<>();
        Thread outsider=new Thread(()->{try{TaskDatabaseMaintenance.requireBusinessOpen(f.context);}catch(Throwable failure){otherThread.set(failure);}});outsider.start();outsider.join(5000);assertFalse(outsider.isAlive());assertTrue(otherThread.get() instanceof SecurityException);
        assertTrue("There is no ambient thread permission: ordinary opens remain closed",f.maintenance.blocksBusinessOpen());
        assertThrows(SecurityException.class,()->new TaskStore(f.context));
        TaskStore tasks=new TaskStore(f.context,()->false,(stage,schema)->{},permit);
        try{assertThrows(SecurityException.class,()->new TaskStore(f.context,()->false,(stage,schema)->{},permit));tasks.verifyBootstrapReadback();f.maintenance.completeOriginalRetry(permit,tasks);}finally{tasks.closeForMaintenance();}
        assertEquals("original_retry_completed",f.maintenance.snapshot().getString("phase"));assertFalse(f.maintenance.blocksBusinessOpen());assertTrue(f.maintenance.observesOriginalRetry(request));
        assertThrows(SecurityException.class,()->f.maintenance.prepareOriginalRetry(request,f.owner,f.generation.get()+1));
    }

    @Test public void incompleteOriginalRetryRemainsQueryOnlyAcrossAColdInstance()throws Exception {
        Fixture f=new Fixture("database_locked");f.preserve();JSONObject request=f.exact();var permit=f.maintenance.prepareOriginalRetry(request,f.owner,f.generation.get()+1);f.generation.incrementAndGet();
        assertThrows(SecurityException.class,()->f.maintenance.completeOriginalRetry(permit,null));f.maintenance.failOriginalRetry(permit,"original_retry_unconfirmed");assertEquals("unknown",f.maintenance.snapshot().getString("phase"));
        TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(f.context,f.host);assertTrue(cold.blocksBusinessOpen());assertTrue(cold.observesOriginalRetry(request));assertFalse(cold.snapshot().getBoolean("originalRetryAvailable"));assertThrows(SecurityException.class,()->TaskDatabaseMaintenance.requireBusinessOpen(f.context));
    }

    @Test public void suspectedCorruptionNeverGetsAnOriginalRetryPermit()throws Exception {
        Fixture f=new Fixture("suspected_corruption");f.preserve();assertFalse(f.maintenance.snapshot().getBoolean("originalRetryAvailable"));assertThrows(SecurityException.class,()->f.maintenance.prepareOriginalRetry(f.exact(),f.owner,f.generation.get()+1));
    }

    // Retained original admission/cutover cases; the same FQN is compiled once.
    private Context context;private File database;private TaskDatabaseMaintenance maintenance;private String originalConversation;
    private final AtomicBoolean present=new AtomicBoolean(true);
    private final AtomicLong generation=new AtomicLong(1);
    private final AtomicReference<File> stopAfterOriginalMove=new AtomicReference<>();
    private final BooleanSupplier owner=()->present.get()&&(stopAfterOriginalMove.get()==null||!stopAfterOriginalMove.get().exists());
    private final TaskDatabaseMaintenance.Host quiet=new TaskDatabaseMaintenance.Host(){public long generation(){return generation.get();}public boolean quiesce(){return true;}};
    @Before public void setup()throws Exception {
        context=new PrivateContext(RuntimeEnvironment.getApplication(),temporary.newFolder().getCanonicalFile());
        database=context.getDatabasePath(TaskStore.DATABASE_NAME);TaskStore original=new TaskStore(context,()->false,(stage,schema)->{});
        original.createCapabilityTask("isolated original history","");
        var repository=original.conversationRepository();repository.initializeEmpty();String conversation=UUID.randomUUID().toString();originalConversation=conversation;
        repository.saveConversation(new JSONObject().put("conversationId",conversation).put("title","isolated original conversation").put("createdAt",1).put("updatedAt",1));
        repository.saveSource(new dev.pocketengine.features.memory.SourceRef("message","source-message-1",1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));
        repository.saveMessage(conversation,new JSONObject().put("id","source-message-1").put("revision",1).put("role","user").put("content","isolated original text").put("at",1));
        original.closeForMaintenance();assertTrue(original.closedForMaintenance());maintenance=new TaskDatabaseMaintenance(context,quiet);
    }
    private JSONObject input()throws Exception{return new JSONObject().put("expectedGeneration",generation.get()).put("requestId",UUID.randomUUID().toString()).put("repairId",maintenance.snapshot().opt("repairId"));}
    private JSONObject preserve(String cause)throws Exception{maintenance.failure(cause,"isolated failure fixture",10);return maintenance.command("repair.preserve",input(),owner);}
    private JSONObject exact()throws Exception{JSONObject current=maintenance.snapshot();return input().put("repairId",current.getString("repairId")).put("sourceFingerprint",current.getString("sourceFingerprint")).put("backupFingerprint",current.getString("backupFingerprint"));}
    private JSONObject review(String mode)throws Exception{JSONObject request=exact().put("mode",mode);if(mode.equals("verified_copy"))request.put("sections",new JSONArray().put("conversations")).put("includeConversationContent",true);return maintenance.command("repair.review",request,owner);}
    private JSONObject confirmInput()throws Exception{return exact().put("reviewId",maintenance.snapshot().getJSONObject("review").getString("reviewId")).put("confirmed",true);}

    @Test public void corruptionCallbackAlwaysThrowsAndNeverDeletesOriginalBytes()throws Exception {
        var before=DatabaseFilePreservation.inspect(database);try{new TaskDatabaseCorruptionHandler(reason->{throw new IllegalStateException("record writer unavailable");}).onCorruption(null);fail();}catch(SQLiteDatabaseCorruptException expected){assertEquals(1,expected.getSuppressed().length);}
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());
    }
    @Test public void readPreserveAndReviewDoNotRenameOrReplaceLiveDatabase()throws Exception {
        var before=DatabaseFilePreservation.inspect(database);JSONObject preserved=preserve("suspected_corruption");assertTrue(preserved.getBoolean("preservationVerified"));review("empty_database");
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());
        String repair=maintenance.snapshot().getString("repairId");TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(context,quiet);assertEquals(repair,cold.command("repair.read",new JSONObject().put("repairId",repair),null).getString("repairId"));
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());assertTrue(cold.blocksBusinessOpen());
        try{new TaskStore(context);fail();}catch(SecurityException expected){}
    }
    @Test public void cancelReviewKeepsSameRepairAndBlocksBusinessOpen()throws Exception {
        preserve("suspected_corruption");review("empty_database");String repair=maintenance.snapshot().getString("repairId"),phase=maintenance.snapshot().getString("phase");
        maintenance.command("repair.discard",input().put("repairId",repair),owner);
        assertEquals(repair,maintenance.snapshot().getString("repairId"));assertEquals(phase,maintenance.snapshot().getString("phase"));assertTrue(maintenance.snapshot().isNull("review"));assertTrue(maintenance.blocksBusinessOpen());assertTrue(new TaskDatabaseMaintenance(context,quiet).blocksBusinessOpen());
    }
    @Test public void onlyExactCurrentWindowMayConfirmAndDuplicateDoesNotSwitchAgain()throws Exception {
        var before=DatabaseFilePreservation.inspect(database);preserve("suspected_corruption");review("empty_database");JSONObject request=confirmInput();
        try{maintenance.command("repair.confirm",request,()->true);fail();}catch(SecurityException expected){}
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());JSONObject done=maintenance.command("repair.confirm",request,owner);assertEquals("completed",done.getString("phase"));assertFalse(done.getJSONObject("result").getBoolean("restoredHistory"));assertFalse(done.getJSONObject("result").getBoolean("executionEnabled"));
        var newBytes=DatabaseFilePreservation.inspect(database);assertEquals("completed",maintenance.command("repair.confirm",request,owner).getString("phase"));assertEquals(newBytes.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());
        TaskStore reopened=new TaskStore(context,()->false,(stage,schema)->{});assertFalse(reopened.ownerExecutionState().getBoolean("enabled"));assertEquals(0,reopened.list().length());reopened.closeForMaintenance();
    }
    @Test public void newerSchemaNeverOffersEmptyReset()throws Exception {assertNoReset("newer_schema");}
    @Test public void lockedDatabaseNeverOffersEmptyReset()throws Exception {assertNoReset("database_locked");}
    @Test public void migrationFailureNeverOffersEmptyReset()throws Exception {assertNoReset("migration_failed");}
    private void assertNoReset(String reason)throws Exception {preserve(reason);assertFalse(maintenance.snapshot().getJSONArray("allowedCommands").toString().contains("repair.review"));try{review("empty_database");fail();}catch(SecurityException expected){}}
    @Test public void departureDuringCutoverStaysUnknownAcrossColdReadWithoutReplay()throws Exception {
        preserve("suspected_corruption");review("empty_database");String repair=maintenance.snapshot().getString("repairId");stopAfterOriginalMove.set(new File(context.getNoBackupFilesDir(),"database-maintenance/"+repair+"/original-at-cutover/"+TaskStore.DATABASE_NAME));
        try{maintenance.command("repair.confirm",confirmInput(),owner);fail();}catch(IllegalStateException expected){}
        assertTrue(stopAfterOriginalMove.get().exists());String retained=DatabaseFilePreservation.sha256(stopAfterOriginalMove.get());TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(context,quiet);
        JSONObject read=cold.command("repair.read",new JSONObject().put("repairId",repair),null);assertEquals("unknown",read.getString("phase"));assertEquals(1,read.getJSONArray("allowedCommands").length());assertEquals(retained,DatabaseFilePreservation.sha256(stopAfterOriginalMove.get()));assertFalse(database.exists());
    }
    @Test public void verifiedCopyRestoresOnlyContentWithNewIdentityAndNoExecutionRows()throws Exception {
        preserve("suspected_corruption");JSONObject reviewed=review("verified_copy");JSONObject scope=reviewed.getJSONObject("review").getJSONObject("restoration");assertEquals(1,scope.getInt("conversationCount"));assertEquals(1,scope.getInt("messageCount"));assertFalse(scope.getBoolean("restoresExistingIds"));
        JSONObject done=maintenance.command("repair.confirm",confirmInput(),owner);assertEquals("verified_copy",done.getJSONObject("result").getString("mode"));assertTrue(done.getJSONObject("result").has("restoration"));
        try(SQLiteDatabase current=SQLiteDatabase.openDatabase(database.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY,new TaskDatabaseCorruptionHandler(null))){
            for(String table:new String[]{"tasks","runs","operations","leases","assistant_sources","assistant_messages"})try(Cursor row=current.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());assertEquals(table,0,row.getInt(0));}
            try(Cursor row=current.rawQuery("SELECT count(*) FROM assistant_import_records",null)){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));}
            try(Cursor row=current.rawQuery("SELECT conversation_id,header_json FROM assistant_conversations",null)){assertTrue(row.moveToFirst());assertNotEquals(originalConversation,row.getString(0));assertEquals(originalConversation,new JSONObject(row.getString(1)).getString("sourceExportConversationId"));}
        }
    }
    @Test public void exactReadRejectsAnotherRepairIdentity()throws Exception {preserve("suspected_corruption");try{maintenance.command("repair.read",new JSONObject().put("repairId",UUID.randomUUID().toString()),null);fail();}catch(SecurityException expected){}}

    @Test public void nonCorruptPreservationAllowsOneExactOriginalReadAndKeepsItsBackup()throws Exception {
        JSONObject saved=preserve("database_locked");assertTrue(maintenance.originalRetryAvailable());String repair=saved.getString("repairId");JSONObject request=exact();
        TaskDatabaseMaintenance.OriginalReadPermit permit=maintenance.prepareOriginalRetry(request,owner,2);
        assertEquals("original_retry_prepared",maintenance.snapshot().getString("phase"));assertFalse(maintenance.originalRetryAvailable());
        TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(context,quiet);assertTrue(cold.blocksBusinessOpen());assertFalse(cold.originalRetryAvailable());
        assertEquals(repair,cold.command("repair.read",new JSONObject().put("repairId",repair),null).getString("repairId"));
        try{new TaskStore(context);fail();}catch(SecurityException expected){}
        generation.set(2);TaskStore original=new TaskStore(context,()->false,(stage,schema)->{},permit);
        assertEquals(1,original.list().length());assertTrue(original.ownerExecutionState().getBoolean("enabled"));assertFalse(original.executionEnabled());
        try{new TaskStore(context,()->false,(stage,schema)->{},permit);fail();}catch(SecurityException expected){}
        maintenance.completeOriginalRetry(permit,original);assertEquals("original_retry_completed",maintenance.snapshot().getString("phase"));assertFalse(maintenance.snapshot().getJSONObject("result").getBoolean("replacedOriginalDatabase"));
        assertTrue(maintenance.observesOriginalRetry(request));assertFalse(new TaskDatabaseMaintenance(context,quiet).blocksBusinessOpen());
        File backup=new File(context.getNoBackupFilesDir(),"database-maintenance/"+repair+"/preserved/"+TaskStore.DATABASE_NAME);
        assertEquals(saved.getString("sourceFingerprint"),DatabaseFilePreservation.inspect(backup).fingerprint());original.closeForMaintenance();
    }
    @Test public void lostRetryWindowCannotOpenAndColdProcessCannotRemintItsPermit()throws Exception {
        preserve("database_locked");JSONObject request=exact();String repair=request.getString("repairId");var permit=maintenance.prepareOriginalRetry(request,owner,2);generation.set(2);present.set(false);
        var before=DatabaseFilePreservation.inspect(database);try{new TaskStore(context,()->false,(stage,schema)->{},permit);fail();}catch(SecurityException expected){}
        assertEquals(before.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());maintenance.failOriginalRetry(permit,"original_owner_or_source_unavailable");
        TaskDatabaseMaintenance cold=new TaskDatabaseMaintenance(context,quiet);JSONObject read=cold.command("repair.read",new JSONObject().put("repairId",repair),null);assertEquals("unknown",read.getString("phase"));assertEquals(1,read.getJSONArray("allowedCommands").length());assertFalse(cold.originalRetryAvailable());
        present.set(true);try{cold.prepareOriginalRetry(exact(),owner,3);fail();}catch(SecurityException expected){}
        try{new TaskStore(context,()->false,(stage,schema)->{},permit);fail();}catch(SecurityException expected){}
    }
    @Test public void changedOriginalBytesRejectThePermitBeforeAnySqliteOpen()throws Exception {
        preserve("database_locked");JSONObject request=exact();var permit=maintenance.prepareOriginalRetry(request,owner,2);generation.set(2);
        Files.write(database.toPath(),new byte[]{42},java.nio.file.StandardOpenOption.APPEND);var changed=DatabaseFilePreservation.inspect(database);
        try{new TaskStore(context,()->false,(stage,schema)->{},permit);fail();}catch(IllegalStateException expected){assertEquals("database_maintenance_record_unreadable",expected.getMessage());}
        assertEquals(changed.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());maintenance.failOriginalRetry(permit,"original_source_changed");assertTrue(maintenance.blocksBusinessOpen());
    }
    @Test public void survivingWalWithoutMainIsNotSilentlyOpenedAsAnEmptyDatabase()throws Exception {
        Files.delete(database.toPath());File wal=new File(database+"-wal");Files.write(wal.toPath(),new byte[]{3,1,4,1});String bytes=DatabaseFilePreservation.sha256(wal);
        try{new TaskStore(context);fail();}catch(SQLiteDatabaseCorruptException expected){assertEquals("original_database_missing_with_auxiliary_files",expected.getMessage());}
        assertFalse(database.exists());Files.createFile(database.toPath());try{new TaskStore(context);fail();}catch(SQLiteDatabaseCorruptException expected){}assertEquals(0,database.length());Files.delete(database.toPath());
        assertEquals(bytes,DatabaseFilePreservation.sha256(wal));JSONObject preserved=preserve("suspected_corruption");assertTrue(preserved.getBoolean("preservationVerified"));
        File backup=new File(context.getNoBackupFilesDir(),"database-maintenance/"+preserved.getString("repairId")+"/preserved/"+TaskStore.DATABASE_NAME+"-wal");assertEquals(bytes,DatabaseFilePreservation.sha256(backup));assertFalse(database.exists());
    }
    @Test public void missingEntireCohortDoesNotInventARepairThatBlocksFreshInstallation()throws Exception {
        for(var member:DatabaseFilePreservation.inspect(database).members())if(member.exists())Files.delete(new File(database.getParentFile(),member.name()).toPath());
        maintenance.failure("storage_unwritable","isolated missing storage",null);try{maintenance.command("repair.preserve",input(),owner);fail();}catch(IllegalStateException expected){}
        assertTrue(maintenance.snapshot().isNull("repairId"));assertFalse(maintenance.blocksBusinessOpen());assertFalse(new File(context.getNoBackupFilesDir(),"database-maintenance/current.json").exists());
        TaskStore fresh=new TaskStore(context,()->false,(stage,schema)->{});fresh.verifyBootstrapReadback();assertEquals(0,fresh.list().length());fresh.closeForMaintenance();
    }
    @Test public void corruptedCompletedPhaseCannotBypassTheOriginalMaintenanceGate()throws Exception {
        preserve("suspected_corruption");File record=new File(context.getNoBackupFilesDir(),"database-maintenance/current.json");JSONObject damaged=new JSONObject(Files.readString(record.toPath())).put("phase","completed");Files.writeString(record.toPath(),damaged.toString());
        var original=DatabaseFilePreservation.inspect(database);try{new TaskDatabaseMaintenance(context,quiet);fail();}catch(SecurityException expected){}
        try{new TaskStore(context);fail();}catch(SecurityException expected){}
        assertEquals(original.fingerprint(),DatabaseFilePreservation.inspect(database).fingerprint());
    }
}
