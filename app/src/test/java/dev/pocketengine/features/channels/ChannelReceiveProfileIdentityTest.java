package dev.pocketengine.features.channels;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production identity validator used by ChannelReceiveLease.begin/check; no substitute ChannelCore. */
public final class ChannelReceiveProfileIdentityTest {
    private JSONObject profile()throws Exception {
        return new JSONObject().put("id","20000000-0000-0000-0000-000000000001")
                .put("provider","qq").put("enabled",true).put("updatedAt",1000L)
                .put("transportEpoch","30000000-0000-0000-0000-000000000001");
    }
    @Test public void captureRetainsTheReviewedEpochAndTheOriginalProfileVersion()throws Exception {
        JSONObject profile=profile(),selected=ChannelReceiveProfileIdentity.capture(profile);
        assertEquals(profile.getString("transportEpoch"),selected.getString("transportEpoch"));
        assertEquals(1000L,selected.getLong("profileVersion"));
        ChannelReceiveProfileIdentity.check(selected,profile);
    }
    @Test public void aClockCollisionCannotKeepTheOldTransportAuthorized()throws Exception {
        JSONObject profile=profile(),selected=ChannelReceiveProfileIdentity.capture(profile);
        JSONObject next=new JSONObject(profile.toString()).put("transportEpoch","30000000-0000-0000-0000-000000000002");
        assertEquals(profile.getLong("updatedAt"),next.getLong("updatedAt"));
        assertThrows(SecurityException.class,()->ChannelReceiveProfileIdentity.check(selected,next));
    }
    @Test public void aLegacySelectionWithoutAnEpochFailsClosed()throws Exception {
        JSONObject profile=profile(),selected=ChannelReceiveProfileIdentity.capture(profile);selected.remove("transportEpoch");
        assertThrows(SecurityException.class,()->ChannelReceiveProfileIdentity.check(selected,profile));
    }
    @Test public void aMissingOrInvalidCurrentEpochCannotReuseTheTimestamp()throws Exception {
        JSONObject original=profile(),selected=ChannelReceiveProfileIdentity.capture(original);
        for(Object invalid:new Object[]{JSONObject.NULL,"",123L}){
            JSONObject current=new JSONObject(original.toString()).put("transportEpoch",invalid);
            assertThrows(SecurityException.class,()->ChannelReceiveProfileIdentity.check(selected,current));
            assertThrows(SecurityException.class,()->ChannelReceiveProfileIdentity.capture(current));
        }
    }
    @Test public void versionProviderAndEnabledChecksStillRejectChanges()throws Exception {
        JSONObject original=profile(),selected=ChannelReceiveProfileIdentity.capture(original);
        for(JSONObject changed:new JSONObject[]{new JSONObject(original.toString()).put("updatedAt",1001L),
                new JSONObject(original.toString()).put("provider","weixin"),new JSONObject(original.toString()).put("enabled",false)})
            assertThrows(SecurityException.class,()->ChannelReceiveProfileIdentity.check(selected,changed));
    }
    @Test public void quotaMetadataChangesDoNotRotateTheReviewedIdentity()throws Exception {
        JSONObject original=profile(),selected=ChannelReceiveProfileIdentity.capture(original);
        ChannelReceiveProfileIdentity.check(selected,new JSONObject(original.toString()).put("hourStart",5000L).put("replyCount",3));
    }
    @Test public void captureContainsOnlyTheProfileIdentityAndNoCredentialOrMessageData()throws Exception {
        JSONObject selected=ChannelReceiveProfileIdentity.capture(profile().put("secret","fixture-only").put("text","private fixture"));
        assertEquals(4,selected.length());assertFalse(selected.has("secret"));assertFalse(selected.has("text"));
    }
}
