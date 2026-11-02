package dev.pocketengine.features.channels;
import static org.junit.Assert.*;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;

/** Real durable channel core; synthetic authenticated receipts, never real platform sends. */
public class ChannelAnswerPagesTest {
    private JSONObject accept(ChannelCore core,String profile,String id,String sender,String kind,String room,String text)throws Exception{return core.accept(profile,ChannelSecurityTest.message(id,sender,kind,room).put("text",text),"qq_signature").getJSONObject("event");}
    private JSONObject queue(ChannelCore core,JSONObject event,String text)throws Exception{JSONObject route=core.route(event);return core.queueAnswerPagesExpected(event.getString("id"),text,route.getString("spaceId"),route.optString("bindingEpoch"),new JSONObject().put("testDependency","original-each-page"));}
    private static String text(){return "完整开始\n"+"代码🙂中文\n".repeat(3500)+"```\nTAIL-EXACT-🙂";}
    private static String body(String text){return text.startsWith("[完整答复 ")?text.substring(text.indexOf('\n')+1):text;}
    private void sent(ChannelCore core,JSONArray rows)throws Exception{for(int i=0;i<rows.length();i++){String id=rows.getJSONObject(i).getString("id");core.prepareSend(id);core.checkSendAuthority(id);core.delivery(id,"sent","platform accepted","receipt-"+i);}}
    @Test public void fullAnswerSurvivesRestartAndFourSlotWindowsWithDeterministicNoDuplicatePages()throws Exception {
        var dir=Files.createTempDirectory("answer-pages");ChannelCore core=new ChannelCore(dir.toFile());String profile=ChannelSecurityTest.addProfile(core,"qq-pages","qq");JSONObject event=accept(core,profile,"long","owner","dm","","question");String text=text();JSONObject answer=queue(core,event,text);String answerId=answer.getString("answerId");assertTrue(answer.getInt("parts")>4);assertFalse(answer.getBoolean("completeDelivered"));
        StringBuilder all=new StringBuilder();JSONArray saved=core.snapshot().getJSONArray("outbox");for(int i=0;i<saved.length();i++){JSONObject row=saved.getJSONObject(i);assertTrue(row.getString("text").length()<=4000);assertTrue(row.has("memoryDependency"));all.append(body(row.getString("text")));}assertEquals(text,all.toString());assertEquals(ChannelTextPages.sha256(text),answer.getString("sha256"));
        core=new ChannelCore(dir.toFile());assertEquals(answer.getInt("parts"),queue(core,event,text).getInt("parts"));assertEquals(saved.length(),core.snapshot().getJSONArray("outbox").length());JSONArray first=core.prepareAnswerPages(answerId,event.getString("id"));assertEquals(4,first.length());sent(core,first);assertEquals(0,core.prepareAnswerPages(answerId,event.getString("id")).length());
        for(int i=0;core.answerProgress(answerId).getInt("remainingParts")>0&&i<10;i++){JSONObject more=accept(core,profile,"more-"+i,"owner","dm","","/more "+answerId);JSONArray next=core.prepareAnswerPages(answerId,more.getString("id"));assertTrue(next.length()<=4);assertTrue(next.length()>0);sent(core,next);assertEquals(0,core.prepareAnswerPages(answerId,more.getString("id")).length());}
        assertTrue(core.answerProgress(answerId).getBoolean("completeDelivered"));assertEquals(saved.length(),core.snapshot().getJSONArray("outbox").length());
    }
    @Test public void unknownAndStopAreNotRetriedOrSkippedEvenByDirectOutboxSend()throws Exception {
        var dir=Files.createTempDirectory("answer-unknown");ChannelCore core=new ChannelCore(dir.toFile());String profile=ChannelSecurityTest.addProfile(core,"qq-unknown","qq");JSONObject event=accept(core,profile,"long","member","dm","","question");String answer=queue(core,event,text()).getString("answerId");JSONArray rows=core.prepareAnswerPages(answer,event.getString("id"));String first=rows.getJSONObject(0).getString("id"),second=rows.getJSONObject(1).getString("id");core.prepareSend(first);core.delivery(first,"unknown","connection lost","");assertThrows(SecurityException.class,()->core.resolveDelivery(first,"not_delivered",true,"未见消息不算未派发证明"));
        assertThrows(SecurityException.class,()->core.prepareSend(second));JSONObject more=accept(core,profile,"more","member","dm","","/more "+answer);assertEquals(0,core.prepareAnswerPages(answer,more.getString("id")).length());assertThrows(IllegalStateException.class,()->core.prepareSend(first));
        ChannelCore cold=new ChannelCore(dir.toFile());assertEquals(0,cold.prepareAnswerPages(answer,more.getString("id")).length());assertEquals(1,cold.answerProgress(answer).getInt("unconfirmedParts"));
    }
    @Test public void sameRouteSenderTransportAndOwnerBindingAreRequiredForEveryLaterPage()throws Exception {
        ChannelCore core=new ChannelCore(Files.createTempDirectory("answer-scope").toFile());String profile=ChannelSecurityTest.addProfile(core,"qq-scope","qq");JSONObject bind=accept(core,profile,"bind","owner","dm","","bind");core.bindOwner(profile,bind.getString("id"),true);JSONObject event=accept(core,profile,"q","owner","dm","","private answer");String answer=queue(core,event,text()).getString("answerId");
        JSONObject other=accept(core,profile,"other","stranger","dm","","/more "+answer);assertThrows(SecurityException.class,()->core.prepareAnswerPages(answer,other.getString("id")));
        JSONObject group=accept(core,profile,"group","owner","group","other-group","/more "+answer);assertThrows(SecurityException.class,()->core.prepareAnswerPages(answer,group.getString("id")));
        JSONObject row=core.prepareAnswerPages(answer,event.getString("id")).getJSONObject(0);core.prepareSend(row.getString("id"));core.unbindOwner(profile);assertThrows(SecurityException.class,()->core.checkSendAuthority(row.getString("id")));assertThrows(SecurityException.class,()->core.prepareAnswerPages(answer,event.getString("id")));
    }
    @Test public void existingShortAnswersKeepTheirExactSinglePayloadIncludingTheFourThousandBoundary(){String text="界".repeat(3998)+"🙂";assertEquals(4000,text.length());assertEquals(java.util.List.of(text),ChannelTextPages.bodies(text));assertEquals(text,ChannelTextPages.page(text,0,1,ChannelTextPages.sha256(text),"/more original"));}
    @Test public void revokedChatAndOversizeNeverPartiallyQueueOrLeakTheTail()throws Exception {
        ChannelCore core=new ChannelCore(Files.createTempDirectory("answer-bound").toFile());String profile=ChannelSecurityTest.addProfile(core,"qq-bound","qq");JSONObject event=accept(core,profile,"q","member","group","room","answer");assertThrows(IllegalArgumentException.class,()->queue(core,event,"x".repeat(ChannelTextPages.BODY_CHARS*ChannelTextPages.MAX_PAGES+1)));assertEquals(0,core.snapshot().getJSONArray("outbox").length());String answer=queue(core,event,text()).getString("answerId");core.grant(core.route(event).getString("spaceId"),new JSONArray());assertThrows(SecurityException.class,()->core.prepareAnswerPages(answer,event.getString("id")));
    }
}
