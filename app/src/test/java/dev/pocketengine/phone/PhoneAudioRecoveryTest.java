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
        boolean failSave,failRestore,unknownSilence,ignoreSilence;int writes;
        public String read(){return durable;}
        public void write(String value){if(failSave)throw new IllegalStateException("save denied");durable=value;}
        public String read(AppCopyKey app){return modes.getOrDefault(app,"allow");}
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
        io.failRestore=true;assertEquals(1,new PhoneAudioRecovery(io,io).restoreOwner("a").getInt("pending"));io.failRestore=false;
        PhoneAudioRecovery cold=new PhoneAudioRecovery(io,io);assertEquals(1,cold.restoreOwner("a").getInt("restored"));assertEquals("allow",io.read(MUSIC));assertEquals("ignore",io.read(work));assertEquals(1,cold.snapshot().getInt("pendingCount"));
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
    @Test public void switchingTargetRestoresOnlyPreviousAppsAndReportsTheirUnknownRestoration()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);AppCopyKey next=new AppCopyKey("example.next",0),other=new AppCopyKey("example.other",10);
        ledger.silence("original",MUSIC,true);ledger.silence("original",next,true);ledger.silence("another",other,true);
        io.failRestore=true;assertEquals(1,ledger.restoreOwnerExcept("original",next).getInt("pending"));assertEquals("ignore",io.read(next));
        io.failRestore=false;JSONObject restored=ledger.restoreOwnerExcept("original",next);assertEquals(0,restored.getInt("pending"));assertEquals(1,restored.getInt("restored"));
        assertEquals("allow",io.read(MUSIC));assertEquals("ignore",io.read(next));assertEquals("ignore",io.read(other));assertEquals(2,ledger.snapshot().getInt("pendingCount"));
    }
    @Test public void reviewedRestoreDoesNotRestoreAnotherAppFromTheSameRun()throws Exception {
        Audio io=new Audio();PhoneAudioRecovery ledger=new PhoneAudioRecovery(io,io);AppCopyKey other=new AppCopyKey("example.other",0);
        ledger.silence("original",MUSIC,true);ledger.silence("original",other,true);JSONObject restored=ledger.restoreApp("original",MUSIC);
        assertEquals(0,restored.getInt("pending"));assertEquals(1,restored.getInt("restored"));assertEquals("ignore",io.read(other));assertEquals(1,ledger.snapshot().getInt("pendingCount"));
        assertThrows(SecurityException.class,()->ledger.inspectOwner("another",other));assertThrows(SecurityException.class,()->ledger.restoreApp("another",other));
    }
}
