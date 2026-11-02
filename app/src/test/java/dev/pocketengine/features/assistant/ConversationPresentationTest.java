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
            try{reopened.updatePresentation("older",4,"复活删除会话",null,60);fail("deleted conversation");}catch(IllegalArgumentException expected){}
        }
    }
}
