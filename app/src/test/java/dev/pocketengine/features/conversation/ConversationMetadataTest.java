package dev.pocketengine.features.conversation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConversationMetadataTest {
    @Test public void ownerTitleSurvivesAutomaticHeaderAndStalePreferenceCannotOverwrite()throws Exception{
        JSONObject current=new JSONObject().put("title","旧名称");JSONObject changed=ConversationMetadata.changed(current,1," 主人的名称 ",null,1);
        assertEquals("主人的名称",ConversationMetadata.project(new JSONObject().put("title","迟到的自动名称"),changed).getString("title"));
        assertEquals("旧名称",current.getString("title"));
        try{ConversationMetadata.changed(changed,1,"旧界面",null,2);fail("stale revision");}catch(IllegalStateException expected){}
        JSONObject pinned=ConversationMetadata.changed(changed,2,null,true,10);assertEquals(10,pinned.getLong("pinnedAt"));assertEquals(0,ConversationMetadata.changed(pinned,3,null,false,20).getLong("pinnedAt"));
        try{ConversationMetadata.changed(current,1,"\n",null,1);fail("blank name");}catch(IllegalArgumentException expected){}
    }
}
