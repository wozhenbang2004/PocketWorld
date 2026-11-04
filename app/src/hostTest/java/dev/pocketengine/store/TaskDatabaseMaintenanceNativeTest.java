package dev.pocketengine.store;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import static org.junit.Assert.*;

/** Real isolated Android SQLite + AtomicFile. This suite is not replaced with SDK stubs on a Java-only host. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskDatabaseMaintenanceNativeTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Context context;private File database;private TaskDatabaseMaintenance maintenance;private String originalConversation;
    private final AtomicBoolean present=new AtomicBoolean(true);
    private final AtomicLong generation=new AtomicLong(1);
    private final AtomicReference<File> stopAfterOriginalMove=new AtomicReference<>();
    private final BooleanSupplier owner=()->present.get()&&(stopAfterOriginalMove.get()==null||!stopAfterOriginalMove.get().exists());
    private final TaskDatabaseMaintenance.Host quiet=new TaskDatabaseMaintenance.Host(){public long generation(){return generation.get();}public boolean quiesce(){return true;}};
    @Before public void setup()throws Exception {
        File root=temporary.newFolder();context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return directory("files");}
            @Override public File getNoBackupFilesDir(){return directory("no-backup");}
            @Override public File getDatabasePath(String name){return new File(name).isAbsolute()?new File(name):new File(directory("databases"),name);}
            private File directory(String name){File file=new File(root,name);assertTrue(file.isDirectory()||file.mkdirs());return file;}
        };
        database=context.getDatabasePath(TaskStore.DATABASE_NAME);TaskStore original=new TaskStore(context,()->false,(stage,schema)->{});
        original.createCapabilityTask("isolated original history","");
        var repository=original.conversationRepository();repository.initializeEmpty();String conversation=UUID.randomUUID().toString();originalConversation=conversation;
        repository.saveConversation(new JSONObject().put("conversationId",conversation).put("title","isolated original conversation").put("createdAt",1).put("updatedAt",1));
        repository.saveSource(new dev.pocketengine.features.memory.SourceRef("message","source-message-1",1,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false));
        repository.saveMessage(conversation,new JSONObject().put("id","source-message-1").put("revision",1).put("role","user").put("content","isolated original text").put("at",1));
        original.closeForMaintenance();assertTrue(original.closedForMaintenance());maintenance=new TaskDatabaseMaintenance(context,quiet);
    }
    private JSONObject input()throws Exception{return new JSONObject().put("expectedGeneration",generation.get()).put("requestId",UUID.randomUUID().toString());}
    private JSONObject preserve(String cause)throws Exception{maintenance.failure(cause,"isolated failure fixture",10);return maintenance.command("repair.preserve",input(),owner);}
    private JSONObject exact()throws Exception{JSONObject current=maintenance.snapshot();return input().put("repairId",current.getString("repairId")).put("sourceFingerprint",current.getString("sourceFingerprint")).put("backupFingerprint",current.getString("backupFingerprint"));}
    private JSONObject review(String mode)throws Exception{JSONObject request=exact().put("mode",mode);if(mode.equals("verified_copy"))request.put("sections",new JSONArray().put("conversations"));return maintenance.command("repair.review",request,owner);}
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
