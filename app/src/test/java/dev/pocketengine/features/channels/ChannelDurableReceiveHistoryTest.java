package dev.pocketengine.features.channels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original channels.json + original QQ session, not an alternate receiver or ledger. */
public final class ChannelDurableReceiveHistoryTest {
    @Test public void sameMessageIdInDifferentRoomsAndDmsDoesNotCollideAndSurvivesReopen()throws Exception {
        Path directory=Files.createTempDirectory("channel-scoped-replay-");try {
            ChannelCore core=new ChannelCore(directory.toFile());String profile=profile(core);
            String one=core.accept(profile,event("same-id","member","group","room-one","one"),"qq_gateway").getJSONObject("event").getString("id");
            String two=core.accept(profile,event("same-id","member","group","room-two","two"),"qq_gateway").getJSONObject("event").getString("id");
            assertNotEquals(one,two);assertFalse(core.accept(profile,event("same-id","member","dm","","dm"),"qq_gateway").getBoolean("duplicate"));
            core=new ChannelCore(directory.toFile());assertEquals(one,core.accept(profile,event("same-id","member","group","room-one","one"),"qq_signature").getString("eventId"));
            assertEquals(two,core.accept(profile,event("same-id","member","group","room-two","two"),"qq_gateway").getString("eventId"));
            assertEquals(3,core.snapshot().getJSONArray("inbox").length());
        }finally{delete(directory);}
    }
    @Test public void originalSessionSequenceIsDurableOnlyAfterInboxAndScopesOutChangedAccount()throws Exception {
        Path directory=Files.createTempDirectory("qq-durable-position-");try {
            ChannelCore core=new ChannelCore(directory.toFile());String profile=profile(core);JSONObject captured=core.profile(profile);String credential=ChannelCore.digest("fixture-only-secret");
            QqGatewaySession.Resume resume=new QqGatewaySession.Resume(core.originalQqResume(captured,credential),(id,seq)->core.recordQqResume(captured,credential,id,seq));
            List<JSONObject> sent=new ArrayList<>();QqGatewaySession first=new QqGatewaySession(resume,"fixture-token",sent::add,new QqGatewaySession.Events(){public void ready(boolean ignored){}public void message(JSONObject payload)throws Exception{core.accept(profile,ChannelProtocol.canonicalQq(payload),"qq_gateway");}},()->{},0);
            first.receive(QqGatewaySessionTest.hello().toString(),0);first.receive(QqGatewaySessionTest.ready(1).toString(),1);first.receive(QqGatewaySessionTest.message(2,"m1","one").toString(),2);first.retire();
            ChannelCore reopened=new ChannelCore(directory.toFile());JSONObject stored=reopened.originalQqResume(captured,credential);assertEquals(2,stored.getLong("sequence"));assertEquals("fixture-session",stored.getString("sessionId"));assertFalse(reopened.snapshot().toString().contains("fixture-session"));assertFalse(reopened.profile(profile).has("nativeQqResume"));
            List<JSONObject> next=new ArrayList<>();QqGatewaySession cold=new QqGatewaySession(new QqGatewaySession.Resume(stored,(id,seq)->reopened.recordQqResume(captured,credential,id,seq)),"fresh-access-token",next::add,new QqGatewaySession.Events(){public void ready(boolean ignored){}public void message(JSONObject payload)throws Exception{reopened.accept(profile,ChannelProtocol.canonicalQq(payload),"qq_gateway");}},()->{},10);
            cold.receive(QqGatewaySessionTest.hello().toString(),10);assertEquals(6,next.get(0).getInt("op"));assertEquals(2,next.get(0).getJSONObject("d").getLong("seq"));
            cold.receive(QqGatewaySessionTest.message(3,"m2","gap replay").toString(),11);assertEquals(2,reopened.snapshot().getJSONArray("inbox").length());
            assertEquals(0,reopened.originalQqResume(captured,ChannelCore.digest("rotated-secret")).length());
            reopened.saveProfile(new JSONObject(captured.toString()).put("name","new native generation"));assertThrows(SecurityException.class,()->reopened.recordQqResume(captured,credential,"fixture-session",4));
        }finally{delete(directory);}
    }
    @Test public void persistenceFailureNeverAdvancesHeartbeatAndReconnectReplaysOriginalSequence()throws Exception {
        AtomicBoolean fail=new AtomicBoolean();AtomicInteger writes=new AtomicInteger();List<JSONObject> sent=new ArrayList<>();
        QqGatewaySession.Resume resume=new QqGatewaySession.Resume(new JSONObject().put("sessionId","same-session").put("sequence",1),(id,seq)->{writes.incrementAndGet();if(fail.get())throw new java.io.IOException("synthetic disk failure");});
        QqGatewaySession session=new QqGatewaySession(resume,"token",sent::add,new QqGatewaySession.Events(){public void ready(boolean ignored){}public void message(JSONObject payload){}},()->{},0);
        session.receive(QqGatewaySessionTest.hello().toString(),0);fail.set(true);assertThrows(java.io.IOException.class,()->session.receive(QqGatewaySessionTest.message(2,"m1","retained inbox" ).toString(),1));
        fail.set(false);session.receive(new JSONObject().put("op",1).toString(),2);assertEquals(1,sent.get(1).getLong("d"));assertEquals(1,writes.get());
    }
    @Test public void historyIsExplicitlyPartialBoundedAndNeverCrossesAnOwnerConversation()throws Exception {
        Path directory=Files.createTempDirectory("channel-history-coverage-");try {
            ChannelCore core=new ChannelCore(directory.toFile());String profile=profile(core);JSONObject bind=core.accept(profile,event("bind","owner","dm","","before binding"),"qq_gateway").getJSONObject("event");core.bindOwner(profile,bind.getString("id"),true);
            for(int i=0;i<12;i++)core.accept(profile,event("m"+i,"owner","dm","","original "+i),"qq_gateway");
            String other=profile(core);JSONObject foreign=core.accept(other,event("bind","owner","dm","","other account private"),"qq_gateway").getJSONObject("event");core.bindOwner(other,foreign.getString("id"),true);core.accept(other,event("m0","owner","dm","","must not leak"),"qq_gateway");
            JSONObject current=core.accept(profile,event("current","owner","dm","","now"),"qq_gateway").getJSONObject("event"),projection=core.historyProjection(current,8),coverage=projection.getJSONObject("coverage");
            assertEquals("partial",coverage.getString("status"));assertFalse(coverage.getBoolean("completePlatformHistory"));assertFalse(coverage.getBoolean("platformBackfillAvailable"));assertEquals(8,projection.getJSONArray("messages").length());assertEquals(4,coverage.getInt("omittedByLimit"));
            assertFalse(projection.toString().contains("before binding"));assertFalse(projection.toString().contains("must not leak"));assertEquals("unknown_not_reconstructed",coverage.getString("earlierOrOfflineGap"));
        }finally{delete(directory);}
    }
    private static String profile(ChannelCore core)throws Exception{return core.saveProfile(new JSONObject().put("name","synthetic official bot").put("provider","qq").put("appId","fixture-app").put("enabled",true)).getString("id");}
    private static JSONObject event(String message,String sender,String kind,String room,String text)throws Exception{return new JSONObject().put("messageId",message).put("senderId",sender).put("kind",kind).put("roomId",room).put("text",text);}
    private static void delete(Path directory)throws Exception{try(java.util.stream.Stream<Path> files=Files.walk(directory)){files.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}}
}
