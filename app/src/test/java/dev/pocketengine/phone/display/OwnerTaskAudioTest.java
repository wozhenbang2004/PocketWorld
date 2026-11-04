package dev.pocketengine.phone.display;

import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.setup.PhoneAudioRecovery;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real review/ledger policy with an explicit appops and native ownership fixture; no device claim. */
public final class OwnerTaskAudioTest {
    private static final AppCopyKey MUSIC=new AppCopyKey("example.music",0);
    private static final class Fixture implements PhoneAudioRecovery.Store,PhoneAudioRecovery.Audio,OwnerTaskAudio.Current {
        String saved="";JSONObject facts=OwnerTaskAudioTest.ownership("example.music",0,"original-root");
        final Map<AppCopyKey,String> modes=new HashMap<>();
        final PhoneAudioRecovery ledger;final OwnerTaskAudio audio;
        boolean original=true,unknownSet,acceptWithoutEffect;long now=1;int writes,reads;IntConsumer afterRead;
        Fixture()throws Exception {ledger=new PhoneAudioRecovery(this,this);audio=new OwnerTaskAudio("task/run/1",5,ledger,()->now);}
        public String read(){return saved;}
        public void write(String text){saved=text;}
        public void check(){if(!original)throw new SecurityException("original run changed");}
        public JSONObject ownership()throws Exception {check();return new JSONObject(facts.toString());}
        public String read(AppCopyKey app){check();String mode=modes.getOrDefault(app,"allow");reads++;if(afterRead!=null)afterRead.accept(reads);return mode;}
        public void write(AppCopyKey app,String mode)throws Exception {
            check();audio.requireDispatchTarget(new JSONObject().put("packageName",app.packageName()).put("userId",app.userId()),ownership());
            writes++;if(!acceptWithoutEffect)modes.put(app,mode);if(unknownSet)throw new IllegalStateException("reply missing");
        }
        JSONObject review()throws Exception{return audio.review(0,false,this);}
        JSONObject confirm(JSONObject view,boolean muted)throws Exception{return audio.confirm(view.getString("reviewId"),muted,0,false,this);}
    }
    private static JSONObject task(int id,int display,String pkg,int user,String root)throws Exception {
        return new JSONObject().put("taskId",id).put("rootTaskId",id).put("displayId",display).put("packageName",pkg)
                .put("androidUserId",user).put("userId",user).put("component",pkg+"/.Main").put("rootIdentity",root);
    }
    private static JSONObject ownership(String pkg,int user,String root)throws Exception {
        JSONObject primary=task(1,0,"example.launcher",0,"primary"),owned=task(2,5,pkg,user,root);
        return new JSONObject().put("status","observed").put("primary",new JSONArray().put(primary)).put("owned",new JSONArray().put(owned))
                .put("taskTopology",new JSONObject().put("schemaVersion",1).put("status","observed").put("complete",true).put("readOnly",true).put("tasks",new JSONArray().put(primary).put(owned)));
    }
    @Test public void readOnlyReviewThenExplicitMuteAndOriginalRestoreUseOneLedger()throws Exception {
        Fixture f=new Fixture();JSONObject review=f.review();assertEquals(0,f.writes);assertEquals("",f.saved);
        assertEquals("example.music",review.getString("packageName"));assertEquals(0,review.getInt("userId"));assertTrue(review.getJSONObject("state").getBoolean("canMute"));
        JSONObject muted=f.confirm(review,true);assertTrue(muted.getBoolean("confirmed"));assertEquals("ignore",f.modes.get(MUSIC));assertEquals(1,f.writes);
        JSONObject restore=f.review();assertFalse(restore.getJSONObject("state").getBoolean("canMute"));assertTrue(restore.getJSONObject("state").getBoolean("canRestore"));
        JSONObject restored=f.confirm(restore,false);assertTrue(restored.getBoolean("confirmed"));assertEquals("allow",f.modes.get(MUSIC));assertEquals(0,f.ledger.snapshot().getInt("pendingCount"));
        assertFalse(f.audio.automaticMuteAllowed(MUSIC));assertTrue(f.audio.automaticMuteAllowed(new AppCopyKey("example.other",0)));
        assertFalse(restored.getBoolean("globalVolumeChanged"));
    }
    @Test public void sameConfirmationCannotReplayAndFreshReadOfUnknownClaimCannotMuteAgain()throws Exception {
        Fixture f=new Fixture();f.unknownSet=true;f.acceptWithoutEffect=true;JSONObject review=f.review(),result=f.confirm(review,true);
        assertFalse(result.getBoolean("confirmed"));assertEquals(1,f.writes);assertThrows(SecurityException.class,()->f.confirm(review,true));
        f.unknownSet=false;JSONObject read=f.review();assertFalse(read.getJSONObject("state").getBoolean("canMute"));assertTrue(read.getJSONObject("state").getBoolean("canRestore"));
        assertThrows(SecurityException.class,()->f.confirm(read,true));assertEquals(1,f.writes);assertEquals(1,f.ledger.snapshot().getInt("pendingCount"));
    }
    @Test public void acceptedWithoutModeChangeRemainsUnconfirmed()throws Exception {
        Fixture f=new Fixture();f.acceptWithoutEffect=true;JSONObject result=f.confirm(f.review(),true);
        assertFalse(result.getBoolean("confirmed"));assertTrue(result.getBoolean("stateVerified"));assertFalse(result.getJSONObject("state").getBoolean("muted"));assertEquals(1,f.writes);
    }
    @Test public void pauseResumeOrDifferentPausedStateInvalidatesBeforeAnyWrite()throws Exception {
        Fixture f=new Fixture();JSONObject first=f.review();assertThrows(SecurityException.class,()->f.audio.confirm(first.getString("reviewId"),true,2,false,f));
        JSONObject next=f.review();assertThrows(SecurityException.class,()->f.audio.confirm(next.getString("reviewId"),true,0,true,f));assertEquals(0,f.writes);assertEquals("",f.saved);
    }
    @Test public void endedOriginalRunAndExpiredOrSupersededReviewsNeverDispatch()throws Exception {
        Fixture f=new Fixture();JSONObject first=f.review();f.original=false;assertThrows(SecurityException.class,()->f.confirm(first,true));assertEquals(0,f.writes);
        f.original=true;JSONObject expired=f.review();f.now+=TimeUnit.SECONDS.toNanos(31);assertThrows(SecurityException.class,()->f.confirm(expired,true));
        JSONObject old=f.review();f.review();assertThrows(SecurityException.class,()->f.confirm(old,true));assertEquals(0,f.writes);
    }
    @Test public void changedAppRootAndModeRequireNewReviewWithoutBorrowingAnotherTarget()throws Exception {
        Fixture f=new Fixture();JSONObject a=f.review();f.facts=ownership("example.other",0,"other");assertThrows(SecurityException.class,()->f.confirm(a,true));
        f.facts=ownership("example.music",0,"original-root");JSONObject b=f.review();f.facts=ownership("example.music",0,"replacement-root");assertThrows(SecurityException.class,()->f.confirm(b,true));
        JSONObject c=f.review();f.modes.put(MUSIC,"deny");assertThrows(SecurityException.class,()->f.confirm(c,true));assertEquals(0,f.writes);assertEquals("",f.saved);
    }
    @Test public void targetChangeAtActualSetBoundaryKeepsDurableUnknownWithoutAWriteToNewApp()throws Exception {
        Fixture f=new Fixture();JSONObject view=f.review();int changeAt=f.reads+3;
        f.afterRead=n->{if(n==changeAt)try{f.facts=ownership("example.other",0,"new-root");}catch(Exception failed){throw new AssertionError(failed);}};
        JSONObject result=f.confirm(view,true);assertFalse(result.getBoolean("confirmed"));assertFalse(result.getBoolean("stateVerified"));assertEquals(0,f.writes);
        assertEquals(1,f.ledger.snapshot().getInt("pendingCount"));assertThrows(SecurityException.class,()->f.confirm(view,true));assertEquals(0,f.writes);
    }
    @Test public void originalCancellationAtSetBoundaryDoesNotDispatchAndKeepsTheClaim()throws Exception {
        Fixture f=new Fixture();JSONObject view=f.review();int cancelAt=f.reads+3;f.afterRead=n->{if(n==cancelAt)f.original=false;};
        JSONObject result=f.confirm(view,true);assertFalse(result.getBoolean("confirmed"));assertEquals(0,f.writes);assertEquals(1,f.ledger.snapshot().getInt("pendingCount"));
    }
    @Test public void profileUnknownTopologyMultipleOwnersAndCrossDisplayCollisionAreUnavailable()throws Exception {
        assertThrows(SecurityException.class,()->OwnerTaskAudio.target(ownership("example.music",10,"work"),5));
        JSONObject missing=ownership("example.music",0,"root");missing.getJSONArray("owned").getJSONObject(0).remove("userId");assertThrows(Exception.class,()->OwnerTaskAudio.target(missing,5));
        JSONObject incomplete=ownership("example.music",0,"root");incomplete.getJSONObject("taskTopology").put("complete",false);assertThrows(SecurityException.class,()->OwnerTaskAudio.target(incomplete,5));
        JSONObject multiple=ownership("example.music",0,"root");JSONObject second=task(3,5,"example.other",0,"second");multiple.getJSONArray("owned").put(second);multiple.getJSONObject("taskTopology").getJSONArray("tasks").put(second);
        assertThrows(SecurityException.class,()->OwnerTaskAudio.target(multiple,5));
        JSONObject collision=ownership("example.music",0,"root");collision.getJSONObject("taskTopology").getJSONArray("tasks").put(task(3,7,"example.music",0,"elsewhere"));assertThrows(SecurityException.class,()->OwnerTaskAudio.target(collision,5));
    }
    @Test public void ownerChangedSoundIsPreservedAndOriginalSilenceIsNotMislabelledAsAudible()throws Exception {
        Fixture f=new Fixture();f.confirm(f.review(),true);f.modes.put(MUSIC,"deny");int before=f.writes;
        JSONObject result=f.confirm(f.review(),false);assertEquals("owner_mode_preserved",result.getString("outcome"));assertTrue(result.getBoolean("confirmed"));assertEquals(before,f.writes);assertEquals("deny",f.modes.get(MUSIC));
        Fixture already=new Fixture();already.modes.put(MUSIC,"ignore");JSONObject read=already.review();assertFalse(read.getJSONObject("state").getBoolean("canMute"));assertFalse(read.getJSONObject("state").getBoolean("canRestore"));assertEquals(0,already.writes);
    }
    @Test public void anotherRunCannotBorrowTheOriginalLedgerRow()throws Exception {
        Fixture f=new Fixture();f.confirm(f.review(),true);OwnerTaskAudio other=new OwnerTaskAudio("other/run/2",5,f.ledger);
        assertThrows(SecurityException.class,()->other.review(0,false,f));assertEquals(1,f.writes);assertEquals(1,f.ledger.snapshot().getInt("pendingCount"));
    }
}
