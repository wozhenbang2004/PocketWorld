package dev.pocketengine.features.teams;

import dev.pocketengine.core.ModelConfig;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
    @Test public void redactedOriginalRetryIsIdempotentIncludingColdReopen()throws Exception {
        File file=new File(Files.createTempDirectory("team-redacted-retry").toFile(),"runs.json");JSONObject request;String id;int transcriptCount;
        try(TeamCoordinator host=new TeamCoordinator(file,(m,p,c)->new JSONObject().put("content","实际成员fixture"),e->{})){
            id=host.start(team(),"原目标",1,config()).getString("id");request=input(host.pause(id,null),"short","  请检查 "+config().apiKey+" 原配置  ");
            JSONObject sent=host.sendMessage(request),letter=sent.getJSONArray("inbox").getJSONObject(0);transcriptCount=sent.getJSONArray("transcript").length();
            assertEquals("请检查 [密钥已隐藏] 原配置",letter.getString("text"));assertFalse(sent.toString().contains(config().apiKey));
            JSONObject retry=host.sendMessage(request);assertTrue(retry.getJSONObject("messageReceipt").getBoolean("duplicate"));assertEquals("pending",retry.getJSONObject("messageReceipt").getString("status"));
            assertEquals(1,retry.getJSONArray("inbox").length());assertEquals(transcriptCount,retry.getJSONArray("transcript").length());assertEquals(sent.getLong("revision"),retry.getLong("revision"));
            assertEquals(TeamWorkFiles.hash(request.getString("text").trim()),letter.getString("messageTextHash"));assertFalse(Files.readString(file.toPath()).contains(config().apiKey));
        }
        try(TeamCoordinator host=new TeamCoordinator(file,(m,p,c)->new JSONObject().put("content","不应再次调用成员"),e->{})){
            JSONObject retry=host.sendMessage(request);assertTrue(retry.getJSONObject("messageReceipt").getBoolean("duplicate"));assertEquals(1,retry.getJSONArray("inbox").length());assertEquals(transcriptCount,retry.getJSONArray("transcript").length());assertFalse(Files.readString(file.toPath()).contains(config().apiKey));
        }
    }
    @Test public void redactedDuplicateStillBindsOriginalTextTargetModeQuoteAndParent()throws Exception {
        try(TeamCoordinator host=new TeamCoordinator(new File(Files.createTempDirectory("team-redacted-identity").toFile(),"runs.json"),(m,p,c)->new JSONObject().put("content","实际成员fixture"),e->{})){
            String id=host.start(team(),"原目标",1,config()).getString("id");JSONObject quoteRequest=input(host.pause(id,null),"long","原引用");host.sendMessage(quoteRequest);
            JSONObject request=input(host.get(id),"short","检查 "+config().apiKey).put("quoteMessageId",quoteRequest.getString("messageId"));JSONObject sent=host.sendMessage(request);
            assertThrows(SecurityException.class,()->host.sendMessage(new JSONObject(request.toString()).put("text","检查 [密钥已隐藏]")));
            assertThrows(SecurityException.class,()->host.sendMessage(new JSONObject(request.toString()).put("recipientMemberId","long")));
            assertThrows(SecurityException.class,()->host.sendMessage(new JSONObject(request.toString()).put("mode","queue")));
            assertThrows(SecurityException.class,()->host.sendMessage(new JSONObject(request.toString()).put("quoteMessageId","")));
            assertThrows(SecurityException.class,()->selectedMessage(host,request,"parent-a"));
            assertEquals(2,host.get(id).getJSONArray("inbox").length());assertEquals(sent.getLong("revision"),host.get(id).getLong("revision"));
            JSONObject parentRequest=input(host.get(id),"short","父消息 "+config().apiKey);JSONObject parentSent=selectedMessage(host,parentRequest,"parent-a");
            assertTrue(selectedMessage(host,parentRequest,"parent-a").getJSONObject("messageReceipt").getBoolean("duplicate"));
            assertThrows(SecurityException.class,()->selectedMessage(host,parentRequest,"parent-b"));assertThrows(SecurityException.class,()->host.sendMessage(parentRequest));
            assertEquals(3,host.get(id).getJSONArray("inbox").length());assertEquals(parentSent.getLong("revision"),host.get(id).getLong("revision"));assertFalse(host.get(id).toString().contains(config().apiKey));
        }
    }
    @Test public void legacyRowsKeepExactTextComparisonWithoutGuessingRedactedOriginal()throws Exception {
        File file=new File(Files.createTempDirectory("team-legacy-message").toFile(),"runs.json");JSONObject plain,secret;String id;
        try(TeamCoordinator host=new TeamCoordinator(file,(m,p,c)->new JSONObject().put("content","实际成员fixture"),e->{})){
            id=host.start(team(),"原目标",1,config()).getString("id");plain=input(host.pause(id,null),"short","旧普通消息");host.sendMessage(plain);secret=input(host.get(id),"short",config().apiKey);host.sendMessage(secret);
        }
        JSONObject saved=new JSONObject(Files.readString(file.toPath()));JSONArray inbox=saved.getJSONArray("runs").getJSONObject(0).getJSONArray("inbox");for(int i=0;i<inbox.length();i++)inbox.getJSONObject(i).remove("messageTextHash");Files.writeString(file.toPath(),saved.toString());
        try(TeamCoordinator host=new TeamCoordinator(file,(m,p,c)->new JSONObject().put("content","不应再次调用成员"),e->{})){
            assertTrue(host.sendMessage(plain).getJSONObject("messageReceipt").getBoolean("duplicate"));assertThrows(SecurityException.class,()->host.sendMessage(new JSONObject(plain.toString()).put("text","旧普通消息已变")));assertThrows(SecurityException.class,()->host.sendMessage(secret));assertEquals(2,host.get(id).getJSONArray("inbox").length());
            for(int i=0;i<host.get(id).getJSONArray("inbox").length();i++)assertFalse(host.get(id).getJSONArray("inbox").getJSONObject(i).has("messageTextHash"));
        }
    }
    /** White-box actor selector fixture only, not native parent authorization acceptance. */
    private JSONObject selectedMessage(TeamCoordinator host,JSONObject request,String parent)throws Exception {
        Method method=TeamCoordinator.class.getDeclaredMethod("sendMessageSelected",JSONObject.class,TeamCoordinator.AssignmentMessages.class,String.class);method.setAccessible(true);
        synchronized(host){try{return (JSONObject)method.invoke(host,request,null,parent);}catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}}
    }
}
