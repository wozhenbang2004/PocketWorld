package dev.pocketengine.features.channels;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual durable channel/receiver metadata; no account, network receiver or substitute task host. */
public final class ChannelReceiveEpochRecoveryTest {
    @Test public void reviewedReceiverPinsTransportEpochAlongsideTheOriginalProfileVersion()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject profile=f.core.profile(f.profile);String session=f.begin();
            JSONObject selected=f.lease.snapshot().getJSONArray("profiles").getJSONObject(0);
            assertEquals(profile.getString("transportEpoch"),selected.getString("transportEpoch"));
            assertEquals(profile.getLong("updatedAt"),selected.getLong("profileVersion"));
            f.lease.check(session,f.core);
            assertEquals(0,f.core.snapshot().getJSONArray("taskRequests").length());
        }
    }

    @Test public void sameTimestampWithAChangedTransportEpochRejectsTheOldReceivingLease()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.core.profile(f.profile);String session=f.begin();
            f.core.saveProfile(new JSONObject(original.toString()).put("name","reviewed configuration saved again"));
            ChannelCore current=f.withProfileTimestamp(original.getLong("updatedAt"));
            assertEquals(original.getLong("updatedAt"),current.profile(f.profile).getLong("updatedAt"));
            assertNotEquals(original.getString("transportEpoch"),current.profile(f.profile).getString("transportEpoch"));
            assertThrows(SecurityException.class,()->f.lease.check(session,current));
            assertEquals(0,current.snapshot().getJSONArray("taskRequests").length());
        }
    }

    @Test public void anAbsentCurrentEpochCannotPassEvenWhenProviderAndTimestampStillMatch()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject original=f.core.profile(f.profile);String session=f.begin();
            Path file=f.path.resolve("channels.json");JSONObject stored=new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            stored.getJSONArray("profiles").getJSONObject(0).remove("transportEpoch");
            Files.write(file,stored.toString().getBytes(StandardCharsets.UTF_8));
            ChannelCore migrated=new ChannelCore(f.path.toFile());
            assertEquals(original.getLong("updatedAt"),migrated.profile(f.profile).getLong("updatedAt"));
            assertNotEquals(original.getString("transportEpoch"),migrated.profile(f.profile).getString("transportEpoch"));
            assertThrows(SecurityException.class,()->f.lease.check(session,migrated));
        }
    }

    @Test public void legacyForegroundMetadataIsInterruptedAndOnlyExplicitNewReviewCanStartAgain()throws Exception {
        try(Fixture f=new Fixture()){
            String old=f.begin();Path file=f.path.resolve("background-receiver.json");
            JSONObject legacy=new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            legacy.getJSONArray("profiles").getJSONObject(0).remove("transportEpoch");
            Files.write(file,legacy.toString().getBytes(StandardCharsets.UTF_8));
            ChannelReceiveLease restored=new ChannelReceiveLease(f.path.toFile());
            assertEquals("interrupted",restored.snapshot().getString("status"));
            assertFalse(restored.snapshot().getBoolean("foregroundActive"));
            assertFalse(restored.snapshot().getBoolean("restartAfterProcessDeath"));
            assertFalse(restored.canClaim(old));
            assertThrows(SecurityException.class,()->restored.check(old,f.core));
            assertThrows(SecurityException.class,()->restored.begin(new JSONArray().put(f.profile),f.core,false));
            String next=restored.begin(new JSONArray().put(f.profile),f.core,true).getString("sessionId");
            assertNotEquals(old,next);assertFalse(restored.finish(old,"stopped","late old stop"));
            restored.foreground(next);restored.check(next,f.core);
            assertEquals(f.core.profile(f.profile).getString("transportEpoch"),restored.snapshot().getJSONArray("profiles").getJSONObject(0).getString("transportEpoch"));
            assertEquals(0,f.core.snapshot().getJSONArray("taskRequests").length());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path path=Files.createTempDirectory("channel-receive-epoch-");
        final ChannelCore core=new ChannelCore(path.toFile());
        final ChannelReceiveLease lease=new ChannelReceiveLease(path.toFile());
        final String profile=core.saveProfile(new JSONObject().put("name","receiver epoch fixture").put("provider","qq").put("appId","bot-epoch-fixture").put("enabled",true)).getString("id");
        Fixture()throws Exception{}
        String begin()throws Exception {String id=lease.begin(new JSONArray().put(profile),core,true).getString("sessionId");lease.foreground(id);return id;}
        ChannelCore withProfileTimestamp(long timestamp)throws Exception {
            Path file=path.resolve("channels.json");JSONObject stored=new JSONObject(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
            // A deterministic fixture for a valid millisecond clock collision/rollback:
            // retain the actual newly generated transport epoch and all other saved fields.
            stored.getJSONArray("profiles").getJSONObject(0).put("updatedAt",timestamp);
            Files.write(file,stored.toString().getBytes(StandardCharsets.UTF_8));return new ChannelCore(path.toFile());
        }
        public void close()throws Exception {try(var files=Files.walk(path)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
