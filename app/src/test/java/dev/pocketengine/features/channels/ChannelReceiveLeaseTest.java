package dev.pocketengine.features.channels;

import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChannelReceiveLeaseTest {
    @Test public void explicitConfirmationAndExactSessionFenceStartStopAndReplacement()throws Exception {
        try(Fixture f=new Fixture()){
            String profile=f.profile("qq",true);JSONArray selected=new JSONArray().put(profile);
            assertThrows(SecurityException.class,()->f.lease.begin(selected,f.core,false));JSONObject first=f.lease.begin(selected,f.core,true);String id=first.getString("sessionId");
            assertFalse(first.getBoolean("foregroundActive"));assertThrows(IllegalStateException.class,()->f.lease.begin(selected,f.core,true));assertThrows(SecurityException.class,()->f.lease.foreground(java.util.UUID.randomUUID().toString()));
            f.lease.foreground(id);f.lease.check(id,f.core);assertTrue(f.lease.snapshot().getBoolean("foregroundActive"));assertTrue(f.lease.finish(id,"stopped","owner"));assertThrows(SecurityException.class,()->f.lease.check(id,f.core));
            String replacement=f.lease.begin(selected,f.core,true).getString("sessionId");assertFalse(f.lease.finish(id,"stopped","stale stop"));assertTrue(f.lease.canClaim(replacement));assertFalse(f.lease.canClaim(id));
        }
    }
    @Test public void processRecoveryRecordsInterruptionWithoutReplayingAStart()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.lease.begin(new JSONArray().put(f.profile("weixin",true)),f.core,true).getString("sessionId");f.lease.foreground(id);
            ChannelReceiveLease restored=new ChannelReceiveLease(f.path.toFile());assertEquals("interrupted",restored.snapshot().getString("status"));assertFalse(restored.snapshot().getBoolean("foregroundActive"));assertFalse(restored.canClaim(id));assertFalse(restored.snapshot().getBoolean("restartAfterProcessDeath"));
            assertEquals(id,restored.snapshot().getString("sessionId"));assertEquals(0,f.core.snapshot().getJSONArray("taskRequests").length());
        }
    }
    @Test public void quotaAccountingDoesNotInvalidateReviewedSetupButSavingSetupDoes()throws Exception {
        try(Fixture f=new Fixture()){
            String profile=f.profile("qq",true),id=f.lease.begin(new JSONArray().put(profile),f.core,true).getString("sessionId");f.lease.foreground(id);
            JSONObject event=f.core.accept(profile,new JSONObject().put("messageId","quota-message").put("senderId","human").put("kind","dm").put("text","问题"),"qq_gateway").getJSONObject("event");f.core.beginAnswer(event.getString("id"));f.lease.check(id,f.core);
            Thread.sleep(2);JSONObject saved=f.core.profile(profile).put("name","changed");f.core.saveProfile(saved);assertThrows(SecurityException.class,()->f.lease.check(id,f.core));
        }
    }
    @Test public void onlyEnabledDirectProvidersAndBoundedDistinctSelectionsAreAllowed()throws Exception {
        try(Fixture f=new Fixture()){
            assertThrows(SecurityException.class,()->f.lease.begin(new JSONArray().put(f.profile("webhook",true)),f.core,true));assertThrows(SecurityException.class,()->f.lease.begin(new JSONArray().put(f.profile("qq",false)),f.core,true));
            String same=f.profile("qq",true);assertThrows(IllegalArgumentException.class,()->f.lease.begin(new JSONArray().put(same).put(same),f.core,true));
            JSONArray tooMany=new JSONArray();for(int i=0;i<4;i++)tooMany.put(f.profile("qq",true));assertThrows(IllegalArgumentException.class,()->f.lease.begin(tooMany,f.core,true));assertEquals("stopped",f.lease.snapshot().getString("status"));
        }
    }
    @Test public void failedStopPersistenceStillRevokesInMemoryAndReportsFailure()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.lease.begin(new JSONArray().put(f.profile("qq",true)),f.core,true).getString("sessionId");f.lease.foreground(id);Files.createDirectory(f.path.resolve("background-receiver.pending"));
            assertThrows(Exception.class,()->f.lease.finish(id,"stopped","owner stop"));assertFalse(f.lease.active());assertTrue(f.lease.snapshot().getBoolean("persistenceFailed"));assertThrows(SecurityException.class,()->f.lease.check(id,f.core));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final java.nio.file.Path path=Files.createTempDirectory("channel-receive-lease-");final ChannelCore core=new ChannelCore(path.toFile());final ChannelReceiveLease lease=new ChannelReceiveLease(path.toFile());
        Fixture()throws Exception{}
        String profile(String provider,boolean enabled)throws Exception {return core.saveProfile(new JSONObject().put("name","fixture").put("provider",provider).put("appId","bot-"+java.util.UUID.randomUUID()).put("enabled",enabled)).getString("id");}
        public void close()throws Exception {try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(path)){files.sorted(java.util.Comparator.reverseOrder()).forEach(file->{try{Files.delete(file);}catch(Exception ignored){}});}}
    }
}
