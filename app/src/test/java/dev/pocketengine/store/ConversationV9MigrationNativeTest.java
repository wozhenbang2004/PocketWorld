package dev.pocketengine.store;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.memory.SourceRef;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original helper migration, not an overlay database or substituted TaskStore. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationV9MigrationNativeTest {
    private final List<SQLiteDatabase> opened=new ArrayList<>();
    @Before public void reset(){RuntimeEnvironment.getApplication().deleteDatabase("pocket-world.db");}
    @After public void close(){for(SQLiteDatabase db:opened)if(db.isOpen())db.close();}
    private TaskStore store(){return new TaskStore(RuntimeEnvironment.getApplication());}
    private SQLiteDatabase db(TaskStore tasks)throws Exception {
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);
        SQLiteDatabase result=(SQLiteDatabase)field.get(tasks);opened.add(result);return result;
    }
    private String scalar(SQLiteDatabase db,String query,String... args){
        try(Cursor row=db.rawQuery(query,args)){assertTrue(row.moveToFirst());return row.getString(0);}
    }
    @Test public void version8AddsOwnerMetadataWithoutChangingOriginalHistoryOrTaskRows()throws Exception {
        TaskStore original=store();SQLiteDatabase sql=db(original);assertEquals(TaskStore.DATABASE_SCHEMA,sql.getVersion());
        ConversationRepository history=original.conversationRepository();history.initializeEmpty();
        history.transaction(()->{
            history.saveConversation(new JSONObject().put("conversationId","original").put("title","原始🙂").put("createdAt",1).put("updatedAt",2));
            history.saveSource(new SourceRef("message","original-message",1,"owner","private").toJson().put("conversationId","original").put("role","user").put("deleted",false));
            history.saveMessage("original",new JSONObject().put("id","original-message").put("revision",1).put("role","user").put("content","保留原文\n🙂").put("at",3));return null;
        });
        String task=original.createCapabilityTask("retain original task identity","").getString("id");
        original.status(task,"completed","original terminal fixture; no running work to recover");
        String generation=scalar(sql,"SELECT value FROM meta WHERE key='assistant_history_generation'");
        String header=scalar(sql,"SELECT header_json FROM assistant_conversations WHERE conversation_id='original'");
        String body=scalar(sql,"SELECT body FROM assistant_messages WHERE source_id='original-message'");
        String fingerprint=scalar(sql,"SELECT body_sha FROM assistant_messages WHERE source_id='original-message'");
        String document=scalar(sql,"SELECT document FROM tasks WHERE task_id=?",task);
        // Restore precisely the original v8 conversation layout, retaining its original rows.
        sql.execSQL("CREATE TABLE assistant_conversations_v8(generation TEXT NOT NULL,conversation_id TEXT NOT NULL,header_json TEXT NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,delete_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,conversation_id))");
        sql.execSQL("INSERT INTO assistant_conversations_v8 SELECT generation,conversation_id,header_json,updated_at,deleted,delete_reason FROM assistant_conversations");
        sql.execSQL("DROP TABLE assistant_conversations");sql.execSQL("ALTER TABLE assistant_conversations_v8 RENAME TO assistant_conversations");
        sql.execSQL("CREATE INDEX assistant_conversations_page ON assistant_conversations(generation,deleted,updated_at,conversation_id)");
        sql.execSQL("DROP TABLE assistant_conversation_groups");sql.execSQL("DROP TABLE phone_stop_batches");sql.setVersion(8);sql.close();
        TaskStore migrated=store();sql=db(migrated);assertEquals(TaskStore.DATABASE_SCHEMA,sql.getVersion());
        assertEquals(generation,scalar(sql,"SELECT value FROM meta WHERE key='assistant_history_generation'"));
        assertEquals(header,scalar(sql,"SELECT header_json FROM assistant_conversations WHERE conversation_id='original'"));
        assertEquals(body,scalar(sql,"SELECT body FROM assistant_messages WHERE source_id='original-message'"));
        assertEquals(fingerprint,scalar(sql,"SELECT body_sha FROM assistant_messages WHERE source_id='original-message'"));
        assertEquals(document,scalar(sql,"SELECT document FROM tasks WHERE task_id=?",task));
        assertEquals("0",scalar(sql,"SELECT organization_revision FROM assistant_conversations WHERE conversation_id='original'"));
        assertEquals("",scalar(sql,"SELECT group_id FROM assistant_conversations WHERE conversation_id='original'"));
        assertEquals("0",scalar(sql,"SELECT count(*) FROM assistant_conversation_groups"));
        sql.close();TaskStore reopened=store();assertEquals(TaskStore.DATABASE_SCHEMA,db(reopened).getVersion());
        assertEquals(body,reopened.conversationRepository().read(()->reopened.conversationRepository().message("original","original-message")).getString("content"));
    }
    @Test public void originalVersion8WithoutLaterCandidateTableReceivesTheCurrentNativePairingSchema()throws Exception {
        TaskStore original=store();SQLiteDatabase sql=db(original);
        String task=original.createCapabilityTask("retain pre-bootstrap version eight","").getString("id");
        original.status(task,"completed","native fixture only");
        String document=scalar(sql,"SELECT document FROM tasks WHERE task_id=?",task);
        // Inert revoked/expired native rows prove schema creation cannot reset trust or issue grants.
        sql.execSQL("INSERT INTO device_peers VALUES(?,?,?,?,?,?,?)",new Object[]{"fixture-peer",new byte[]{1,2,3},"fixture-native-fingerprint","fixture-instance",4,1,17});
        sql.execSQL("INSERT INTO device_grants VALUES(?,?,?,?,?,?,?)",new Object[]{"fixture-revoked-grant","fixture-peer","fixture-local",4,1,"{\"nativeFixture\":\"preserve-original-grant-body\"}",1});
        String trust=scalar(sql,"SELECT hex(certificate_der)||':'||fingerprint||':'||instance_id||':'||authorization_epoch||':'||revoked||':'||updated_at FROM device_peers WHERE device_id='fixture-peer'");
        String grant=scalar(sql,"SELECT body_json||':'||authorization_epoch||':'||expires_at||':'||revoked FROM device_grants WHERE grant_id='fixture-revoked-grant'");
        sql.execSQL("DROP TABLE device_pairing_candidates");sql.execSQL("DROP TABLE phone_stop_batches");sql.setVersion(8);sql.close();
        TaskStore migrated=store();sql=db(migrated);assertEquals(TaskStore.DATABASE_SCHEMA,sql.getVersion());
        assertEquals("1",scalar(sql,"SELECT count(*) FROM sqlite_master WHERE type='table' AND name='device_pairing_candidates'"));
        assertEquals("0",scalar(sql,"SELECT count(*) FROM device_pairing_candidates"));
        assertEquals(trust,scalar(sql,"SELECT hex(certificate_der)||':'||fingerprint||':'||instance_id||':'||authorization_epoch||':'||revoked||':'||updated_at FROM device_peers WHERE device_id='fixture-peer'"));
        assertEquals(grant,scalar(sql,"SELECT body_json||':'||authorization_epoch||':'||expires_at||':'||revoked FROM device_grants WHERE grant_id='fixture-revoked-grant'"));
        assertEquals(document,scalar(sql,"SELECT document FROM tasks WHERE task_id=?",task));
    }
    @Test public void everySupportedEarlierSchemaUsesTheOriginalContinuousUpgradeWithoutNewGrants()throws Exception {
        for(int version=1;version<TaskStore.DATABASE_SCHEMA;version++){
            RuntimeEnvironment.getApplication().deleteDatabase("pocket-world.db");
            TaskStore original=store();SQLiteDatabase sql=db(original);
            String task=original.createCapabilityTask("legacy version "+version,"").getString("id");
            original.status(task,"completed","no executable work to resume");
            String goal=original.get(task,false).getString("goal");
            List<String> tables=new ArrayList<>();try(Cursor row=sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null)){while(row.moveToNext())tables.add(row.getString(0));}
            for(String table:tables){
                boolean newer=version<8&&table.startsWith("device_")||version<9&&table.equals("assistant_conversation_groups")||version<10&&table.equals("phone_stop_batches")
                    ||version<7&&table.startsWith("harness_gateway_")||version<6&&table.equals("inference_requests")
                    ||version<5&&table.startsWith("task_output_")||version<4&&(table.startsWith("project_plan")||table.equals("team_parent_plans"))
                    ||version<3&&table.equals("task_events")||version<2&&table.startsWith("assistant_");
                if(newer)sql.execSQL("DROP TABLE "+table);
            }
            if(version>=2&&version<9){
                sql.execSQL("CREATE TABLE assistant_conversations_original(generation TEXT NOT NULL,conversation_id TEXT NOT NULL,header_json TEXT NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,delete_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,conversation_id))");
                sql.execSQL("INSERT INTO assistant_conversations_original SELECT generation,conversation_id,header_json,updated_at,deleted,delete_reason FROM assistant_conversations");
                sql.execSQL("DROP TABLE assistant_conversations");sql.execSQL("ALTER TABLE assistant_conversations_original RENAME TO assistant_conversations");
                sql.execSQL("CREATE INDEX assistant_conversations_page ON assistant_conversations(generation,deleted,updated_at,conversation_id)");
            }
            if(version<3){
                sql.execSQL("CREATE TABLE tasks_original(task_id TEXT PRIMARY KEY,document TEXT NOT NULL,updated_at INTEGER NOT NULL)");
                sql.execSQL("INSERT INTO tasks_original SELECT task_id,document,updated_at FROM tasks");sql.execSQL("DROP TABLE tasks");sql.execSQL("ALTER TABLE tasks_original RENAME TO tasks");
            }
            sql.setVersion(version);sql.close();TaskStore migrated=store();sql=db(migrated);
            assertEquals("legacy version "+version,TaskStore.DATABASE_SCHEMA,sql.getVersion());assertEquals(goal,migrated.get(task,false).getString("goal"));
            for(String table:new String[]{"assistant_conversation_groups","device_pairing_candidates","device_grants","harness_gateway_claims","inference_requests","team_parent_plans","project_plans","task_output_sources","phone_stop_batches"})assertEquals(table+" from version "+version,"0",scalar(sql,"SELECT count(*) FROM "+table));
            assertEquals("completed",migrated.get(task,false).getString("status"));sql.close();
        }
    }
    @Test public void failedOrganizationUpgradeRollsBackPartialAlterationsAndKeepsOriginalRows()throws Exception {
        TaskStore original=store();SQLiteDatabase sql=db(original);
        String task=original.createCapabilityTask("retain failed migration bytes","").getString("id");
        original.status(task,"completed","no executable work to restore");
        String document=scalar(sql,"SELECT document FROM tasks WHERE task_id=?",task);
        // Corrupt legacy metadata deliberately: ALTERs can start, but the real index must fail.
        sql.execSQL("DROP TABLE assistant_conversations");
        sql.execSQL("CREATE TABLE assistant_conversations(generation TEXT NOT NULL,conversation_id TEXT NOT NULL,header_json TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,delete_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,conversation_id))");
        sql.setVersion(8);sql.close();TaskStore.DatabaseOpenFailure failed=assertThrows(TaskStore.DatabaseOpenFailure.class,this::store);assertEquals("migration_failed",failed.reasonCode);assertTrue(failed.getCause() instanceof android.database.sqlite.SQLiteException);
        sql=SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);opened.add(sql);
        assertEquals(8,sql.getVersion());assertEquals(document,scalar(sql,"SELECT document FROM tasks WHERE task_id=?",task));
        try(Cursor columns=sql.rawQuery("PRAGMA table_info(assistant_conversations)",null)){while(columns.moveToNext())assertNotEquals("organization_revision",columns.getString(1));}
    }
    @Test public void unknownFutureVersionKeepsRowsAndFailsClosed()throws Exception {
        TaskStore original=store();SQLiteDatabase sql=db(original);String task=original.createCapabilityTask("future schema survives","").getString("id");
        int futureVersion=TaskStore.DATABASE_SCHEMA+1;sql.setVersion(futureVersion);sql.close();TaskStore.DatabaseOpenFailure failed=assertThrows(TaskStore.DatabaseOpenFailure.class,this::store);assertEquals("newer_schema",failed.reasonCode);
        sql=SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE);opened.add(sql);
        assertEquals(futureVersion,sql.getVersion());assertEquals(task,scalar(sql,"SELECT task_id FROM tasks"));
    }
}
