package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual Android SQLite organization migration and owner read/write behavior; no provider or UI claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ConversationOrganizationTest {
    private static final String GENERATION="original-generation";
    private static final class Fixture implements AutoCloseable {
        final File file=new File(RuntimeEnvironment.getApplication().getFilesDir(),"conversation-organization-"+UUID.randomUUID()+".db");
        SQLiteDatabase db;
        Fixture(){
            db=SQLiteDatabase.openOrCreateDatabase(file,null);
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            // The pre-v9 original schema deliberately lacks organization columns.
            db.execSQL("CREATE TABLE assistant_conversations(generation TEXT NOT NULL,conversation_id TEXT NOT NULL,header_json TEXT NOT NULL,updated_at INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,delete_reason TEXT NOT NULL DEFAULT '',PRIMARY KEY(generation,conversation_id))");
            db.execSQL("CREATE TABLE assistant_messages(generation TEXT,source_id TEXT,conversation_id TEXT,sequence INTEGER,body TEXT)");
            db.execSQL("CREATE TABLE assistant_sources(generation TEXT,source_id TEXT,role TEXT,deleted INTEGER)");
            db.execSQL("CREATE TABLE assistant_import_records(generation TEXT,conversation_id TEXT,sequence INTEGER,role TEXT,deleted INTEGER)");
        }
        void migrate(){ConversationOrganization.createSchema(db);}
        void seed(String id,long updated)throws Exception{db.execSQL("INSERT INTO assistant_conversations(generation,conversation_id,header_json,updated_at) VALUES(?,?,?,?)",new Object[]{GENERATION,id,new JSONObject().put("conversationId",id).put("title","原题 "+id).toString(),updated});ConversationOrganization.changed(db,GENERATION);}
        void reply(String id,long sequence)throws Exception{String message=id+":"+sequence;db.execSQL("INSERT INTO assistant_messages VALUES(?,?,?,?,?)",new Object[]{GENERATION,message,id,sequence,"不可混入组织列表的正文"});db.execSQL("INSERT INTO assistant_sources VALUES(?,?,?,?)",new Object[]{GENERATION,message,"assistant",0});ConversationOrganization.changed(db,GENERATION);}
        JSONObject get(String id)throws Exception{return ConversationOrganization.get(db,GENERATION,id);}
        JSONObject update(String id,long revision,JSONObject changes)throws Exception{return transaction(()->ConversationOrganization.update(db,GENERATION,new JSONObject().put("conversationId",id).put("expectedRevision",revision).put("changes",changes)));}
        JSONObject list(JSONObject input)throws Exception{return ConversationOrganization.list(db,GENERATION,input);}
        <T>T transaction(java.util.concurrent.Callable<T> action)throws Exception{db.beginTransaction();try{T value=action.call();db.setTransactionSuccessful();return value;}finally{db.endTransaction();}}
        void reopen(){db.close();db=SQLiteDatabase.openOrCreateDatabase(file,null);}
        public void close(){db.close();}
    }
    @Test public void explicitMigrationIsIdempotentAndPreservesOriginalRowsAcrossReopen()throws Exception{
        try(Fixture f=new Fixture()){
            f.db.execSQL("INSERT INTO assistant_conversations(generation,conversation_id,header_json,updated_at) VALUES(?,?,?,?)",new Object[]{GENERATION,"existing","{\"conversationId\":\"existing\",\"title\":\"旧原文标题\"}",17});
            f.migrate();f.migrate();assertEquals("旧原文标题",f.get("existing").getString("title"));
            JSONObject changed=f.update("existing",0,new JSONObject().put("title","中文新题🙂").put("pinOrder",2).put("archived",true));
            assertEquals(1,changed.getLong("organizationRevision"));f.reopen();assertEquals("中文新题🙂",f.get("existing").getString("title"));assertTrue(f.get("existing").getBoolean("archived"));assertEquals(2,f.get("existing").getLong("pinOrder"));
            try(Cursor row=f.db.rawQuery("SELECT count(*) FROM assistant_conversations",null)){row.moveToFirst();assertEquals(1,row.getInt(0));}
        }
    }
    @Test public void manualUnreadAndExplicitReadWatermarkDoNotConsumeANewerReply()throws Exception{
        try(Fixture f=new Fixture()){f.migrate();f.seed("c",1);f.reply("c",1);f.reply("c",2);
            assertEquals(2,f.get("c").getLong("unreadReplyCount"));JSONObject read=f.update("c",0,new JSONObject().put("readWatermark",1));assertTrue(read.getBoolean("unread"));assertEquals(1,read.getLong("unreadReplyCount"));
            f.update("c",1,new JSONObject().put("readWatermark",2).put("manualUnread",true));assertTrue(f.get("c").getBoolean("unread"));
            f.reply("c",3);f.update("c",2,new JSONObject().put("manualUnread",false));assertEquals(1,f.get("c").getLong("unreadReplyCount"));assertTrue(f.get("c").getBoolean("unread"));
            assertThrows(IllegalArgumentException.class,()->f.update("c",3,new JSONObject().put("readWatermark",4)));
            assertThrows(IllegalArgumentException.class,()->f.update("c",3,new JSONObject().put("readWatermark",1)));
            f.db.execSQL("UPDATE assistant_sources SET deleted=1 WHERE source_id='c:3'");f.db.execSQL("UPDATE assistant_messages SET body=NULL WHERE source_id='c:3'");
            assertEquals(3,f.get("c").getLong("latestWatermark"));f.update("c",3,new JSONObject().put("readWatermark",3));f.reply("c",4);assertEquals(1,f.get("c").getLong("unreadReplyCount"));
        }
    }
    @Test public void pinnedAndArchivedPagesHaveStableIdentityAndRejectChangedCatalogCursors()throws Exception{
        try(Fixture f=new Fixture()){f.migrate();for(int i=0;i<25;i++)f.seed("c"+String.format("%02d",i),i+1);f.update("c00",0,new JSONObject().put("pinOrder",1));f.update("c01",0,new JSONObject().put("archived",true));
            JSONObject first=f.list(new JSONObject().put("limit",20));assertEquals("c00",first.getJSONArray("conversations").getJSONObject(0).getString("conversationId"));assertFalse(first.toString().contains("不可混入"));
            JSONObject second=f.list(new JSONObject().put("limit",20).put("cursor",first.getString("nextCursor")));assertEquals(4,second.getJSONArray("conversations").length());
            java.util.Set<String> ids=new java.util.HashSet<>();for(JSONArray page:java.util.List.of(first.getJSONArray("conversations"),second.getJSONArray("conversations")))for(int i=0;i<page.length();i++)assertTrue(ids.add(page.getJSONObject(i).getString("conversationId")));
            f.reply("c24",1);JSONObject changed=f.list(new JSONObject().put("cursor",first.getString("nextCursor")));assertEquals("source_changed",changed.getString("status"));assertTrue(changed.getBoolean("restartRequired"));assertEquals(0,changed.getJSONArray("conversations").length());
            assertEquals(1,f.list(new JSONObject().put("archive","archived")).getJSONArray("conversations").length());
        }
    }
    @Test public void groupsAreOriginalOwnerMetadataAndCannotChangeSourceOrTaskAuthority()throws Exception{
        try(Fixture f=new Fixture()){f.migrate();f.seed("c",1);String action=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("actionId",action).put("title","研究组");JSONObject group=f.transaction(()->ConversationOrganization.createGroup(f.db,GENERATION,request));String id=group.getString("groupId");
            assertTrue(f.transaction(()->ConversationOrganization.createGroup(f.db,GENERATION,request)).getBoolean("duplicate"));
            assertThrows(SecurityException.class,()->f.transaction(()->ConversationOrganization.createGroup(f.db,GENERATION,new JSONObject().put("actionId",action).put("title","不同组"))));
            f.update("c",0,new JSONObject().put("groupId",id));assertEquals(1,f.list(new JSONObject().put("groupId",id)).getJSONArray("conversations").length());f.reopen();assertEquals(id,f.get("c").getString("groupId"));
            assertThrows(SecurityException.class,()->f.update("c",1,new JSONObject().put("taskId","invented-task")));
            assertThrows(IllegalArgumentException.class,()->f.update("c",1,new JSONObject().put("manualUnread","true")));
            assertThrows(IllegalStateException.class,()->f.update("c",0,new JSONObject().put("title","stale")));
            assertThrows(IllegalArgumentException.class,()->f.update("c",1,new JSONObject().put("groupId","foreign-group")));
            assertFalse(f.get("c").getBoolean("executionAuthorized"));
        }
    }
}
