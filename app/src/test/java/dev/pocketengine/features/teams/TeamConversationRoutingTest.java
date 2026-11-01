package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native coordinator/model-gateway fixture, not a live provider or Android persistence acceptance. */
public class TeamConversationRoutingTest {
    private JSONObject team()throws Exception{return new JSONObject().put("id","fixture-team").put("name","原共同现场").put("leaderId","long").put("members",new JSONArray().put(new JSONObject().put("id","short").put("name","小麦").put("role","制作")).put(new JSONObject().put("id","long").put("name","小麦审查").put("role","检查")));}
    private ModelConfig config(){return new ModelConfig("https://example.invalid/v1","fixture-secret","fixture",false,20,10);}
    private JSONObject input(JSONObject run,String target,String text)throws Exception {JSONObject member=null;for(int i=0;i<run.getJSONArray("members").length();i++)if(target.equals(run.getJSONArray("members").getJSONObject(i).getString("id")))member=run.getJSONArray("members").getJSONObject(i);return new JSONObject().put("runId",run.getString("id")).put("messageId",UUID.randomUUID().toString()).put("expectedRevision",run.getLong("revision")).put("expectedGeneration",run.getInt("generation")).put("recipientMemberId",target).put("expectedMemberGeneration",member.getInt("generation")).put("mode","discussion").put("text",text);}
    @Test public void explicitStableIdRoutesWithoutPrefixEmailOrAccidentalBroadcast()throws Exception {
        try(TeamCoordinator host=new TeamCoordinator(new File(Files.createTempDirectory("team-routing").toFile(),"runs.json"),(m,p,c)->new JSONObject().put("content","实际成员fixture"),e->{})){
            String id=host.start(team(),"原目标",1,config()).getString("id");JSONObject run=host.pause(id,null);int generation=run.getInt("generation");JSONObject sent=host.sendMessage(input(run,"short","@{short} 请检查 a@long.example，提到小麦审查不是收件指令"));
            assertEquals("short",sent.getJSONObject("messageReceipt").getString("recipientMemberId"));assertEquals(generation,sent.getInt("generation"));assertEquals("paused",sent.getString("status"));assertEquals("pending",sent.getJSONArray("inbox").getJSONObject(0).getString("status"));
            JSONObject current=host.get(id);assertThrows(IllegalArgumentException.class,()->host.sendMessage(input(current,"short","@{long} 错误收件人")));assertEquals(1,host.get(id).getJSONArray("inbox").length());
        }
    }
    @Test public void nativeDefaultCoordinatorDuplicateAndForeignQuoteAreUnambiguous()throws Exception {
        try(TeamCoordinator host=new TeamCoordinator(new File(Files.createTempDirectory("team-routing").toFile(),"runs.json"),(m,p,c)->new JSONObject().put("content","真实独立调用fixture"),e->{})){
            String id=host.start(team(),"原目标",1,config()).getString("id");JSONObject run=host.pause(id,null),request=input(run,"long","普通消息只给协调者");request.remove("recipientMemberId");JSONObject sent=host.sendMessage(request);assertEquals("long",sent.getJSONObject("messageReceipt").getString("recipientMemberId"));assertTrue(host.sendMessage(request).getJSONObject("messageReceipt").getBoolean("duplicate"));assertEquals(1,host.get(id).getJSONArray("inbox").length());
            JSONObject current=host.get(id),foreign=input(current,"short","不能冒充原消息引用").put("quoteMessageId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->host.sendMessage(foreign));host.cancel(id);JSONObject stopped=host.get(id);assertThrows(IllegalStateException.class,()->host.sendMessage(input(stopped,"short","取消后不唤醒")));
        }
    }
}
