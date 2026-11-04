package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Production comparisons used inside the original configuration and Core authority locks. */
public final class ChannelConfigurationReviewTest {
    private JSONObject profile(String id,String epoch)throws Exception {
        return new JSONObject().put("id",id).put("appId","original-bot").put("provider","weixin").put("transportEpoch",epoch);
    }
    private JSONObject bound(String epoch,String grant)throws Exception {return new JSONObject().put("bindingEpoch",epoch).put("taskGrantEpoch",grant);}
    @Test public void oldEditorCannotUseANewerConfigurationEvenWhenAccountAndClockMatch()throws Exception {
        ChannelConfigurationReview.profile(profile("p","old").put("updatedAt",100),"old");
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.profile(profile("p","new").put("updatedAt",100),"old"));
    }
    @Test public void newConnectionReviewRequiresAbsenceAndCannotReplaceAnExistingId()throws Exception {
        ChannelConfigurationReview.profile(null,"");
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.profile(profile("p","old"),""));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.profile(null,"old"));
    }
    @Test public void invalidNativeEpochsCannotBeCoercedToReviewedStrings()throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,100,""})
            assertThrows(SecurityException.class,()->ChannelConfigurationReview.profile(profile("p","old").put("transportEpoch",value),"100"));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.profile(null,null));
    }
    @Test public void rebindAndTaskGrantChangesInvalidateOldUnbindIndependently()throws Exception {
        ChannelConfigurationReview.binding(bound("owner-a","grant-a"),"owner-a","grant-a");
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.binding(bound("owner-b","grant-a"),"owner-a","grant-a"));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.binding(bound("owner-a","grant-b"),"owner-a","grant-a"));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.binding(null,"owner-a","grant-a"));
    }
    @Test public void initiallyUnboundReviewCannotReplaceAnOwnerWhoArrivedLater()throws Exception {
        ChannelConfigurationReview.binding(null,"","");
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.binding(bound("owner-b","grant-b"),"",""));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.binding(null,"",null));
    }
    @Test public void loginCaptureKeepsOnlyOriginalIdentityNotCredentialsOrMessageData()throws Exception {
        JSONArray captured=ChannelConfigurationReview.loginProfiles(new JSONArray().put(profile("p","old").put("secret","fixture-only").put("text","private fixture")).put(profile("q","q-epoch").put("provider","qq")));
        assertEquals(1,captured.length());assertEquals(3,captured.getJSONObject(0).length());
        assertFalse(captured.toString().contains("fixture"));
    }
    @Test public void lateOfficialLoginCannotOverwriteAnEditedConnection()throws Exception {
        JSONArray start=ChannelConfigurationReview.loginProfiles(new JSONArray().put(profile("p","old")));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.loginTarget(start,new JSONArray().put(profile("p","new")),"original-bot"));
    }
    @Test public void lateOfficialLoginCannotRecreateARemovedOrReplacedConnection()throws Exception {
        JSONArray start=ChannelConfigurationReview.loginProfiles(new JSONArray().put(profile("p","old")));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.loginTarget(start,new JSONArray(),"original-bot"));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.loginTarget(start,new JSONArray().put(profile("replacement","old")),"original-bot"));
    }
    @Test public void lateNewLoginCannotReplaceTheSameBotCreatedByAnotherWindow()throws Exception {
        assertNull(ChannelConfigurationReview.loginTarget(new JSONArray(),new JSONArray(),"original-bot"));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.loginTarget(new JSONArray(),new JSONArray().put(profile("later","epoch")),"original-bot"));
    }
    @Test public void unchangedOriginalLoginReturnsADetachedNativeProfile()throws Exception {
        JSONObject nativeProfile=profile("p","old");JSONArray current=new JSONArray().put(nativeProfile),start=ChannelConfigurationReview.loginProfiles(current);
        JSONObject result=ChannelConfigurationReview.loginTarget(start,current,"original-bot");assertEquals("p",result.getString("id"));
        result.put("transportEpoch","mutated");assertEquals("old",nativeProfile.getString("transportEpoch"));
    }
    @Test public void ambiguousNativeBotRowsFailClosedInsteadOfPickingTheFirst()throws Exception {
        JSONArray ambiguous=new JSONArray().put(profile("p","old")).put(profile("other","new"));
        assertThrows(SecurityException.class,()->ChannelConfigurationReview.loginTarget(ChannelConfigurationReview.loginProfiles(ambiguous),ambiguous,"original-bot"));
    }
}
