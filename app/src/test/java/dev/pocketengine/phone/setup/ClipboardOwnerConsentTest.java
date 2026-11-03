package dev.pocketengine.phone.setup;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.observation.NativePhoneEntry;
import dev.pocketengine.phone.access.AppCopyKey;
import dev.pocketengine.phone.access.PhoneOwnerPackages;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real owner preferences and original native-entry fences; no Android clipboard capability is inferred. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ClipboardOwnerConsentTest {
    @Test public void revokeThenEnableCannotReviveAQueuedOriginalPaste()throws Exception {
        Context context=RuntimeEnvironment.getApplication();AndroidClipboardRecovery.setOwnerAllowed(context,true);
        long admitted=AndroidClipboardRecovery.policyRevision(context);RunControl original=new RunControl();long runRevision=original.pauseRevision();
        AtomicInteger input=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(()->{try{NativePhoneEntry.call(original,runRevision,()->AndroidClipboardRecovery.requireOwnerAllowed(context,admitted),input::incrementAndGet);}catch(Throwable stopped){failure.set(stopped);}});
        AndroidClipboardRecovery.setOwnerAllowed(context,false);AndroidClipboardRecovery.setOwnerAllowed(context,true);
        long current=AndroidClipboardRecovery.policyRevision(context);assertTrue(current>admitted);assertTrue(AndroidClipboardRecovery.allowed(context));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,input.get());assertTrue(String.valueOf(failure.get()),failure.get() instanceof NativePhoneEntry.Refused);
        assertEquals("clipboard_owner_permission_changed",failure.get().getCause().getMessage());
        NativePhoneEntry.call(original,runRevision,()->AndroidClipboardRecovery.requireOwnerAllowed(context,current),input::incrementAndGet);
        assertEquals("only a new request captures the new owner choice",1,input.get());original.cancel();
    }
    @Test public void changingConsentAfterBorrowRestoresTheOriginalWithoutPastingUnderTheNewConsent()throws Exception {
        Context context=RuntimeEnvironment.getApplication();AndroidClipboardRecovery.setOwnerAllowed(context,true);
        long admitted=AndroidClipboardRecovery.policyRevision(context);RunControl original=new RunControl();AtomicInteger effects=new AtomicInteger();
        class Memory implements ClipboardRestoration.Storage,ClipboardRestoration.Clipboard {
            String value="original clipboard",record;
            public String read(){return record;}public void write(String text){record=text;}public void clear(){record=null;}
            public String snapshot(){return value;}public String replacement(String text){return "temporary:"+text;}public void install(String text){value=text;}
            public void installTemporary(String text)throws Exception{NativePhoneEntry.call(original,original.pauseRevision(),()->AndroidClipboardRecovery.requireOwnerAllowed(context,admitted),()->{value=text;return null;});}
        }
        Memory memory=new Memory();ClipboardRestoration recovery=new ClipboardRestoration(memory,memory);
        JSONObject receipt=recovery.paste("input",original,()->{
            assertEquals("temporary:input",memory.value);AndroidClipboardRecovery.setOwnerAllowed(context,false);AndroidClipboardRecovery.setOwnerAllowed(context,true);
            return NativePhoneEntry.call(original,original.pauseRevision(),()->AndroidClipboardRecovery.requireOwnerAllowed(context,admitted),()->{effects.incrementAndGet();return true;});
        });
        assertEquals(0,effects.get());assertEquals("original clipboard",memory.value);assertFalse(recovery.pending());assertTrue(receipt.getBoolean("clipboardRestored"));
        assertFalse(receipt.getBoolean("automaticReplayAllowed"));assertFalse(receipt.toString().contains("original clipboard"));original.cancel();
    }
    @Test public void aHostClipboardCannotAuthorizeAnotherProfileOrAnAmbiguousCopy()throws Exception {
        RunControl original=new RunControl();AtomicInteger effects=new AtomicInteger();
        PhoneOwnerPackages host=new PhoneOwnerPackages(Set.of(new AppCopyKey("app.example",0)),true);
        for(PhoneOwnerPackages invalid:new PhoneOwnerPackages[]{
                new PhoneOwnerPackages(host.owners(),false),
                new PhoneOwnerPackages(Set.of(new AppCopyKey("app.example",10)),true),
                new PhoneOwnerPackages(Set.of(new AppCopyKey("app.example",0),new AppCopyKey("app.example",10)),true),
                new PhoneOwnerPackages(Set.of(new AppCopyKey("another.app",0)),true)}){
            NativePhoneEntry.Refused refused=assertThrows(NativePhoneEntry.Refused.class,()->NativePhoneEntry.call(original,original.pauseRevision(),()->ClipboardProfileScope.require(invalid,"app.example",0),effects::incrementAndGet));
            assertTrue(refused.getCause() instanceof ClipboardProfileScope.Unavailable);
        }
        assertEquals(0,effects.get());NativePhoneEntry.call(original,original.pauseRevision(),()->ClipboardProfileScope.require(host,"app.example",0),effects::incrementAndGet);assertEquals(1,effects.get());original.cancel();
    }
    @Test public void profileChangeAfterClipboardBorrowRestoresBeforeReturningExplicitUnsupported()throws Exception {
        RunControl original=new RunControl();AtomicInteger effects=new AtomicInteger();AtomicReference<String> value=new AtomicReference<>("owner original"),record=new AtomicReference<>();
        ClipboardRestoration.Storage store=new ClipboardRestoration.Storage(){public String read(){return record.get();}public void write(String encoded){record.set(encoded);}public void clear(){record.set(null);}};
        ClipboardRestoration.Clipboard clipboard=new ClipboardRestoration.Clipboard(){public String snapshot(){return value.get();}public String replacement(String text){return "temporary:"+text;}public void install(String encoded){value.set(encoded);}};
        ClipboardRestoration recovery=new ClipboardRestoration(store,clipboard);
        ClipboardProfileScope.Unavailable refused=assertThrows(ClipboardProfileScope.Unavailable.class,()->recovery.paste("input",original,()->{
            assertEquals("temporary:input",value.get());
            return NativePhoneEntry.call(original,original.pauseRevision(),()->ClipboardProfileScope.require(new PhoneOwnerPackages(Set.of(new AppCopyKey("app.example",10)),true),"app.example",0),()->{effects.incrementAndGet();return true;});
        }));
        assertEquals("clipboard_profile_mismatch_or_ambiguous",refused.code);assertEquals(0,effects.get());assertEquals("owner original",value.get());assertFalse(recovery.pending());original.cancel();
    }
}
