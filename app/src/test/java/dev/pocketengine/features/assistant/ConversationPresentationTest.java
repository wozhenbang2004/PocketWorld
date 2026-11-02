package dev.pocketengine.features.assistant;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ConversationPresentationTest {
    @Test public void ownerNameAndPinSurviveLateAutomaticTitleAndReopenWithCas()throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.create(null)){
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("INSERT INTO meta VALUES('assistant_history_new_install','1')");
            ConversationRepository.createSchema(db);Object monitor=new Object();ConversationRepository repository=new ConversationRepository(db,monitor);repository.initializeEmpty();
            repository.transaction(()->{repository.saveConversation(new JSONObject().put("conversationId","older").put("title","自动名称").put("updatedAt",10));repository.saveConversation(new JSONObject().put("conversationId","newer").put("title","最近对话").put("updatedAt",20));return null;});
            JSONObject renamed=repository.updatePresentation("older",1,"机主项目",null,30);assertEquals(2,renamed.getLong("metadataRevision"));
            try{repository.updatePresentation("older",1,"旧界面覆盖",null,31);fail("stale revision");}catch(IllegalStateException expected){}
            repository.updatePresentation("older",2,null,true,40);
            repository.transaction(()->{repository.saveConversation(new JSONObject().put("conversationId","older").put("title","迟到自动标题").put("updatedAt",10));return null;});
            ConversationRepository reopened=new ConversationRepository(db,monitor);
            assertEquals("机主项目",reopened.header("older").getString("title"));
            JSONObject first=reopened.list("",1);assertEquals("older",first.getJSONArray("conversations").getJSONObject(0).getString("conversationId"));
            assertEquals("newer",reopened.list(first.getString("nextCursor"),1).getJSONArray("conversations").getJSONObject(0).getString("conversationId"));
            reopened.updatePresentation("older",3,null,false,50);
            assertEquals("newer",reopened.list("",20).getJSONArray("conversations").getJSONObject(0).getString("conversationId"));
            reopened.transaction(()->{reopened.deleteConversation("older","user_conversation_delete");return null;});
            try(var prefs=db.rawQuery("SELECT count(*) FROM assistant_conversation_preferences WHERE conversation_id='older'",null)){assertTrue(prefs.moveToFirst());assertEquals(0,prefs.getInt(0));}
            try{reopened.updatePresentation("older",4,"复活删除会话",null,60);fail("deleted conversation");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void legacyOwnerPresentationMigratesAndAbandonedStageErasesItsName()throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.create(null)){
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");ConversationRepository.createSchema(db);Object monitor=new Object();ConversationRepository repository=new ConversationRepository(db,monitor);
            JSONObject conversation=new JSONObject().put("conversationId","legacy-owner-conversation").put("title","自动标题").put("userTitle","机主保留标题").put("pinnedAt",40).put("metadataRevision",4).put("updatedAt",12).put("messages",new org.json.JSONArray());
            JSONObject legacy=new JSONObject().put("version",2).put("conversations",new org.json.JSONArray().put(conversation)).put("sourceStates",new org.json.JSONArray());
            repository.stageLegacy("abandoned",legacy,()->null);repository.discardStage("abandoned");
            try(var prefs=db.rawQuery("SELECT count(*) FROM assistant_conversation_preferences WHERE generation='abandoned'",null)){assertTrue(prefs.moveToFirst());assertEquals(0,prefs.getInt(0));}
            JSONObject counts=repository.stageLegacy("retained",legacy,()->null);
            synchronized(monitor){db.beginTransaction();try{repository.activateStaged("retained","a".repeat(64),counts);db.setTransactionSuccessful();}finally{db.endTransaction();}repository.activatedAfterCommit();}
            JSONObject reopened=new ConversationRepository(db,monitor).header("legacy-owner-conversation");assertEquals("机主保留标题",reopened.getString("title"));assertEquals(40,reopened.getLong("pinnedAt"));assertEquals(4,reopened.getLong("metadataRevision"));
        }
    }
    @Test public void equalPinAndTimeCursorPagesNeverRepeatPinnedOrUnpinnedConversations()throws Exception{
        try(SQLiteDatabase db=SQLiteDatabase.create(null)){
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");db.execSQL("INSERT INTO meta VALUES('assistant_history_new_install','1')");
            ConversationRepository.createSchema(db);ConversationRepository repository=new ConversationRepository(db,new Object());repository.initializeEmpty();
            repository.transaction(()->{
                for(String id:new String[]{"pin-a","pin-b"})repository.saveConversation(new JSONObject().put("conversationId",id).put("title",id).put("updatedAt",10));
                repository.saveConversation(new JSONObject().put("conversationId","unpinned-newer").put("title","newer").put("updatedAt",40));
                repository.saveConversation(new JSONObject().put("conversationId","unpinned-older").put("title","older").put("updatedAt",5));return null;
            });
            repository.updatePresentation("pin-a",1,null,true,50);repository.updatePresentation("pin-b",1,null,true,50);
            java.util.List<String> expected=java.util.List.of("pin-b","pin-a","unpinned-newer","unpinned-older"),actual=new java.util.ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();String cursor="";
            for(int page=0;page<expected.size();page++){
                JSONObject result=repository.list(cursor,1);assertEquals(1,result.getJSONArray("conversations").length());String id=result.getJSONArray("conversations").getJSONObject(0).getString("conversationId");assertTrue("cursor repeated original conversation "+id,seen.add(id));actual.add(id);
                assertEquals(page<expected.size()-1,result.getBoolean("hasMore"));cursor=result.getString("nextCursor");if(result.getBoolean("hasMore"))assertFalse(cursor.isEmpty());else assertEquals("",cursor);
            }
            assertEquals(expected,actual);assertEquals(4,seen.size());
        }
    }
}
