package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.memory.SourceRef;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual repository + native SQLite copy. Does not claim a device or maintenance cutover. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationReadOnlyRecoveryNativeTest {
    private static ConversationRepository seed(SQLiteDatabase database)throws Exception {
        database.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        database.execSQL("INSERT INTO meta(key,value) VALUES('assistant_history_new_install','1')");
        ConversationRepository.createSchema(database);
        ConversationRepository repo=new ConversationRepository(database,new Object());repo.initializeEmpty();
        repo.transaction(()->{
            repo.saveConversation(new JSONObject().put("conversationId","source-conversation").put("title","原对话").put("updatedAt",1).put("messages",new JSONArray()));
            repo.saveSource(new SourceRef("message","source-message",1,"owner","private").toJson().put("conversationId","source-conversation").put("role","user").put("deleted",false));
            repo.saveMessage("source-conversation",new JSONObject().put("id","source-message").put("revision",1).put("role","user").put("content","保留原文🙂\n第二行").put("at",100));
            return null;
        });return repo;
    }
    private static JSONObject request()throws Exception{return new JSONObject().put("conversationId","source-conversation").put("format","json");}

    @Test public void maintenanceExportRejectsAWritableBusinessHandle()throws Exception {
        File file=new File(Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(),"writable-history-").toFile(),"source.db");
        try(SQLiteDatabase database=SQLiteDatabase.openOrCreateDatabase(file,null)){
            ConversationRepository repo=seed(database);
            assertThrows(SecurityException.class,()->repo.exportVerifiedReadOnlyCopy(request()));
            assertEquals(1,new JSONObject(repo.exportConversation(request()).getString("content")).getJSONArray("messages").length());
        }
    }

    @Test public void verifiedReadOnlyMirrorExportsExactContentWithoutChangingEitherDatabase()throws Exception {
        File directory=Files.createTempDirectory(RuntimeEnvironment.getApplication().getCacheDir().toPath(),"read-only-history-").toFile();
        File source=new File(directory,"source.db"),mirror=new File(directory,"mirror.db");
        try(SQLiteDatabase database=SQLiteDatabase.openOrCreateDatabase(source,null)){seed(database);}
        byte[] original=Files.readAllBytes(source.toPath());Files.copy(source.toPath(),mirror.toPath());
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(mirror.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)){
            ConversationRepository repo=new ConversationRepository(database,new Object());
            JSONObject exported=repo.exportVerifiedReadOnlyCopy(request());
            assertEquals("verified_private_read_only_database_copy_no_new_tail",exported.getString("snapshotSemantics"));
            assertEquals("保留原文🙂\n第二行",new JSONObject(exported.getString("content")).getJSONArray("messages").getJSONObject(0).getString("content"));
            assertFalse(database.inTransaction());assertFalse(exported.getBoolean("executionAuthorized"));
        }
        assertArrayEquals(original,Files.readAllBytes(source.toPath()));
        assertArrayEquals(original,Files.readAllBytes(mirror.toPath()));
    }
}
