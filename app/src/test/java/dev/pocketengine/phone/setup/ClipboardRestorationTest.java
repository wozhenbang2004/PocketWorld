package dev.pocketengine.phone.setup;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual recovery/serialization logic; clipboard and durable storage are controlled interfaces. */
public final class ClipboardRestorationTest {
    private static final class Memory implements ClipboardRestoration.Storage,ClipboardRestoration.Clipboard {
        String record,value="owner original";boolean readable=true,restoreFails;
        public synchronized String read(){return record;}
        public synchronized void write(String encoded){record=encoded;}
        public synchronized void clear(){record=null;}
        public synchronized String snapshot(){return readable?value:null;}
        public String replacement(String text){return "temporary:"+text;}
        public synchronized void install(String encoded){if(restoreFails&&encoded.equals("owner original"))throw new IllegalStateException("controlled restore outage");value=encoded;}
        ClipboardRestoration subject(){return new ClipboardRestoration(this,this);}
    }
    @Test public void acceptedInputRestoresTheOriginalWithoutReturningEitherClipboardValue()throws Exception {
        Memory memory=new Memory();AtomicInteger effects=new AtomicInteger();
        JSONObject result=memory.subject().paste("requested input",new RunControl(),()->{assertEquals("temporary:requested input",memory.value);effects.incrementAndGet();return true;});
        assertEquals(1,effects.get());assertTrue(result.getBoolean("accepted"));assertTrue(result.getBoolean("clipboardRestored"));
        assertEquals("owner original",memory.value);assertNull(memory.record);
        assertFalse(result.toString().contains("requested input"));assertFalse(result.toString().contains("owner original"));
    }
    @Test public void cancelInsideInputRestoresBeforePropagatingTheOriginalStop()throws Exception {
        Memory memory=new Memory();RunControl control=new RunControl();AtomicInteger effects=new AtomicInteger();
        assertThrows(InterruptedException.class,()->memory.subject().paste("input",control,()->{effects.incrementAndGet();control.cancel();control.check();return true;}));
        assertEquals(1,effects.get());assertEquals("owner original",memory.value);assertNull(memory.record);
    }
    @Test public void unknownNativeResultRestoresButNeverRetriesTheInput()throws Exception {
        Memory memory=new Memory();AtomicInteger effects=new AtomicInteger();
        JSONObject result=memory.subject().paste("input",new RunControl(),()->{effects.incrementAndGet();throw new IllegalStateException("native call has no receipt");});
        assertEquals(1,effects.get());assertTrue(result.isNull("accepted"));assertTrue(result.getBoolean("requiresReconciliation"));assertFalse(result.getBoolean("automaticReplayAllowed"));
        assertEquals("owner original",memory.value);assertNull(memory.record);
    }
    @Test public void failedRestoreRetainsRecoveryAndBlocksTheNextInputUntilRecovered()throws Exception {
        Memory memory=new Memory();memory.restoreFails=true;AtomicInteger effects=new AtomicInteger();ClipboardRestoration subject=memory.subject();
        JSONObject result=subject.paste("input",new RunControl(),()->{effects.incrementAndGet();return true;});
        assertTrue(result.getBoolean("accepted"));assertFalse(result.getBoolean("clipboardRestored"));assertTrue(subject.pending());
        JSONObject next=subject.paste("second",new RunControl(),()->{effects.incrementAndGet();return true;});
        assertEquals("clipboard_recovery_pending",next.getString("error"));assertEquals(1,effects.get());
        memory.restoreFails=false;assertTrue(subject.recover());assertEquals("owner original",memory.value);assertFalse(subject.pending());assertEquals(1,effects.get());
    }
    @Test public void interveningOwnerClipboardChangeSurvivesCleanup()throws Exception {
        Memory memory=new Memory();JSONObject result=memory.subject().paste("input",new RunControl(),()->{memory.value="owner copied later";return true;});
        assertTrue(result.getBoolean("clipboardRestored"));assertEquals("owner copied later",memory.value);assertNull(memory.record);
    }
    @Test public void unreadableOriginalRefusesWithoutDispatchOrClipboardReplacement()throws Exception {
        Memory memory=new Memory();memory.readable=false;AtomicInteger effects=new AtomicInteger();
        JSONObject result=memory.subject().paste("input",new RunControl(),()->{effects.incrementAndGet();return true;});
        assertFalse(result.getBoolean("accepted"));assertEquals(0,effects.get());assertEquals("owner original",memory.value);assertNull(memory.record);
    }
    @Test public void waitingTaskCanCancelWithoutStealingOrWaitingForAnotherTasksClipboard()throws Exception {
        Memory first=new Memory(),second=new Memory();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);RunControl waiting=new RunControl();
        CompletableFuture<JSONObject> holder=CompletableFuture.supplyAsync(()->{try{return first.subject().paste("first",new RunControl(),()->{entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("fixture release expired");return true;});}catch(Exception failed){throw new RuntimeException(failed);}});
        assertTrue(entered.await(1,TimeUnit.SECONDS));AtomicInteger effects=new AtomicInteger();CompletableFuture<Throwable> blocked=CompletableFuture.supplyAsync(()->{try{second.subject().paste("second",waiting,()->{effects.incrementAndGet();return true;});return null;}catch(Throwable stopped){return stopped;}});
        try{waiting.cancel();assertTrue(blocked.get(1,TimeUnit.SECONDS) instanceof InterruptedException);assertEquals(0,effects.get());assertEquals("temporary:first",first.value);assertEquals("owner original",second.value);}
        finally{release.countDown();holder.get(1,TimeUnit.SECONDS);}
        assertEquals("owner original",first.value);assertNull(first.record);assertNull(second.record);
    }
}
