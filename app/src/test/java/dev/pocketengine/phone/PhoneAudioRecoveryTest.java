package dev.pocketengine.phone;

import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.setup.PhoneAudioRecovery;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** The actual durable ledger with an explicitly simulated appops transport, not a device test. */
public final class PhoneAudioRecoveryTest {
    private static final class Audio implements PhoneAudioRecovery.Store,PhoneAudioRecovery.Audio {
        String durable="";final Map<AppCopyKey,String> modes=new HashMap<>();
        boolean failSave,failRestore,unknownSilence,ignoreSilence;int writes;Runnable afterRead;
        public String read(){return durable;}
        public void write(String value){if(failSave)throw new IllegalStateException("save denied");durable=value;}
        public String read(AppCopyKey app){String observed=modes.getOrDefault(app,"allow");Runnable change=afterRead;afterRead=null;if(change!=null)change.run();return observed;}
        public void write(AppCopyKey app,String mode){writes++;if(unknownSilence&&mode.equals("ignore"))throw new IllegalStateException("set unknown");if(failRestore&&!mode.equals("ignore"))throw new IllegalStateException("restore unknown");if(!ignoreSilence||!mode.equals("ignore"))modes.put(app,mode);}
    }
    private static final AppCopyKey MUSIC=new AppCopyKey("example.music",0);
    @Test public void ownerOptInAndSuccessfulDurableWriteMustPrecedeAudioMutation()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery recovery=new PhoneAudioRecovery(io,io);
        recovery.silence("original/run/1",MUSIC,false);assertEquals(0,io.writes);assertEquals("",io.durable);
        io.failSave=true;assertThrows(IllegalStateException.class,()->recovery.silence("original/run/1",MUSIC,true));assertEquals(0,io.writes);assertEquals(0,recovery.snapshot().getInt("pendingCount"));
    }
    @Test public void acceptedSetWithoutActualModeChangeDoesNotProveSilence()throws Exception {
        Audio io=new Audio();io.ignoreSilence=true;PhoneAudioRecovery recovery=new PhoneAudioRecovery(io,io);JSONObject result=recovery.silence("a",MUSIC,true);
        assertEquals("silence_unconfirmed",result.getString("outcome"));assertTrue(result.getBoolean("restorationRecorded"));assertEquals("allow",io.read(MUSIC));assertTrue(io.durable.contains("example.music"));assertFalse(result.getBoolean("globalVolumeChanged"));
    }
    @Test public void coldRecoveryRetainsFailedRestoreAndDoesNotTouchAnotherUserOrOwner()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery first=new PhoneAudioRecovery(io,io);AppCopyKey work=new AppCopyKey("example.music",10);
        first.silence("a",MUSIC,true);first.silence("b",work,true);assertThrows(SecurityException.class,()->first.silence("other",MUSIC,true));
        io.failRestore=true;assertEquals(1,new PhoneAudioRecovery(io,io).setOriginalForOwner("a",MUSIC).getInt("pending"));io.failRestore=false;
        PhoneAudioRecovery cold=new PhoneAudioRecovery(io,io);int before=io.writes;JSONObject automatic=cold.restoreOwner("a");assertEquals(1,automatic.getInt("pending"));assertTrue(automatic.getBoolean("needsOwnerAction"));assertEquals(before,io.writes);
        assertEquals(1,cold.setOriginalForOwner("a",MUSIC).getInt("restored"));assertEquals("allow",io.read(MUSIC));assertEquals("ignore",io.read(work));assertEquals(1,cold.snapshot().getInt("pendingCount"));
    }
    @Test public void observedDifferentOwnerModeIsNotOverwrittenByRestoreOrSilence()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery recovery=new PhoneAudioRecovery(io,io);recovery.silence("a",MUSIC,true);io.modes.put(MUSIC,"deny");int before=io.writes;
        assertEquals("audio_changed_by_owner",recovery.silence("a",MUSIC,true).getString("outcome"));assertEquals(1,recovery.restoreOwner("a").getInt("supersededByOwner"));assertEquals("deny",io.read(MUSIC));assertEquals(before,io.writes);
    }
    @Test public void sameRunCannotResilenceAfterOwnerRestoresTheOriginalMode()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery recovery=new PhoneAudioRecovery(io,io);recovery.silence("a",MUSIC,true);io.modes.put(MUSIC,"allow");int before=io.writes;
        assertEquals("audio_changed_by_owner",recovery.silence("a",MUSIC,true).getString("outcome"));assertEquals("allow",io.read(MUSIC));assertEquals(before,io.writes);recovery.restoreOwner("a");assertEquals(before,io.writes);
    }
    @Test public void uncertainFirstSetIsQueryOnlyEvenAfterColdLedgerRead()throws Exception {
        Audio io=new Audio();io.unknownSilence=true;PhoneAudioRecovery first=new PhoneAudioRecovery(io,io);assertEquals("silence_unconfirmed",first.silence("a",MUSIC,true).getString("outcome"));int before=io.writes;io.unknownSilence=false;
        PhoneAudioRecovery cold=new PhoneAudioRecovery(io,io);assertEquals("audio_changed_by_owner",cold.silence("a",MUSIC,true).getString("outcome"));assertEquals(before,io.writes);assertEquals("allow",io.read(MUSIC));assertEquals(1,cold.snapshot().getInt("pendingCount"));
    }
    @Test public void switchingTargetCannotOverwriteSoundAndKeepsThePreviousAppPending()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);AppCopyKey next=new AppCopyKey("example.next",0),other=new AppCopyKey("example.other",10);
        ledger.silence("original",MUSIC,true);ledger.silence("original",next,true);ledger.silence("another",other,true);
        int before=io.writes;assertEquals(1,ledger.restoreOwnerExcept("original",next).getInt("pending"));assertEquals("ignore",io.read(next));assertEquals(before,io.writes);
        ledger.setOriginalForOwner("original",MUSIC);JSONObject restored=ledger.restoreOwnerExcept("original",next);assertEquals(0,restored.getInt("pending"));
        assertEquals("allow",io.read(MUSIC));assertEquals("ignore",io.read(next));assertEquals("ignore",io.read(other));assertEquals(2,ledger.snapshot().getInt("pendingCount"));
    }
    @Test public void reviewedRestoreDoesNotRestoreAnotherAppFromTheSameRun()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);AppCopyKey other=new AppCopyKey("example.other",0);
        ledger.silence("original",MUSIC,true);ledger.silence("original",other,true);JSONObject restored=ledger.setOriginalForOwner("original",MUSIC);
        assertEquals(0,restored.getInt("pending"));assertEquals(1,restored.getInt("restored"));assertEquals("ignore",io.read(other));assertEquals(1,ledger.snapshot().getInt("pendingCount"));
        assertThrows(SecurityException.class,()->ledger.inspectOwner("another",other));assertThrows(SecurityException.class,()->ledger.setOriginalForOwner("another",other));
    }
    @Test public void externalWriteAfterTheLastRestoreReadIsPreservedAndSurvivesColdReconciliation()throws Exception {
        for(int entry=0;entry<4;entry++){
            Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);ledger.silence("original",MUSIC,true);int writes=io.writes;
            io.afterRead=()->io.modes.put(MUSIC,"deny");
            JSONObject receipt=switch(entry){case 0->ledger.restoreOwner("original");case 1->ledger.restoreApp("original",MUSIC);case 2->ledger.restoreOwnerExcept("original",new AppCopyKey("example.next",0));default->ledger.restoreAll();};
            assertEquals("deny",io.read(MUSIC));assertEquals(writes,io.writes);assertEquals(0,receipt.getInt("restored"));assertEquals(1,receipt.getInt("pending"));
            assertTrue(receipt.getBoolean("needsOwnerAction"));assertTrue(receipt.getBoolean("mayRemainMuted"));assertFalse(receipt.getBoolean("atomicRestore"));assertFalse(receipt.getBoolean("ownerRequestedWrite"));
            PhoneAudioRecovery cold=new PhoneAudioRecovery(io,io);assertEquals(1,cold.snapshot().getInt("pendingCount"));
            assertEquals(1,cold.restoreOwner("original").getInt("supersededByOwner"));assertEquals(0,cold.snapshot().getInt("pendingCount"));assertEquals("deny",io.read(MUSIC));assertEquals(writes,io.writes);
        }
    }
    @Test public void pendingMuteIsExplicitInTheOriginalTaskResourceReceiptEvenAfterDisplayRelease()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);ledger.silence("original",MUSIC,true);
        JSONObject audio=ledger.restoreOwner("original");JSONObject resources=dev.pocketengine.phone.PhoneResourceDisposition.release("task","run",1,new JSONObject().put("releaseConfirmed",true).put("audioRestore",audio));
        assertTrue(resources.getBoolean("releaseConfirmed"));assertEquals("pending",resources.getString("audioRecovery"));assertEquals("ignore",io.read(MUSIC));assertEquals(1,io.writes);
        JSONObject explicit=ledger.setAllOriginalForOwner();assertEquals(0,explicit.getInt("pending"));assertTrue(explicit.getBoolean("ownerRequestedWrite"));assertFalse(explicit.getBoolean("atomicRestore"));assertEquals(1,explicit.getInt("writeAttempts"));assertEquals("allow",io.read(MUSIC));
    }
    @Test public void explicitOwnerSetIsReportedAsANewWriteNotAnAtomicRollback()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);ledger.silence("original",MUSIC,true);
        io.afterRead=()->io.modes.put(MUSIC,"deny");JSONObject result=ledger.setOriginalForOwner("original",MUSIC);
        assertTrue(result.getBoolean("ownerRequestedWrite"));assertFalse(result.getBoolean("atomicRestore"));assertEquals(1,result.getInt("writeAttempts"));assertEquals("allow",io.read(MUSIC));
    }
}
