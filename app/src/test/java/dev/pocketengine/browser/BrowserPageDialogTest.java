package dev.pocketengine.browser;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual modal state/identity contract; native JsResult and physical page rendering are not faked as verified. */
public final class BrowserPageDialogTest {
    private static JSONObject identity()throws Exception {
        return new JSONObject().put("profileId","owner-profile").put("profileRevision",2).put("profileIsolation","androidx-multi-profile")
                .put("browserSessionId",UUID.randomUUID().toString()).put("tabId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString()).put("navigation",3)
                .put("taskId",UUID.randomUUID().toString()).put("runId",UUID.randomUUID().toString()).put("epoch",1).put("browserSpaceId",UUID.randomUUID().toString()).put("tabGeneration",1);
    }
    private static final class F {
        final AtomicLong now=new AtomicLong(1000);final AtomicBoolean current=new AtomicBoolean(true);int confirmed,cancelled;String actualPrompt;boolean throwReply;
        final BrowserPageDialog dialog;
        F(BrowserPageDialog.Kind kind)throws Exception {dialog=new BrowserPageDialog(kind,identity(),UUID.randomUUID().toString(),now::get,()->{if(!current.get())throw new SecurityException("old native document");},new BrowserPageDialog.Reply(){public void confirm(String text)throws Exception{confirmed++;actualPrompt=text;if(throwReply)throw new Exception("native handle gone");}public void cancel()throws Exception{cancelled++;if(throwReply)throw new Exception("native handle gone");}});}
    }
    @Test public void receivingDialogNeverConfirmsOrCancelsForTheModel()throws Exception {F f=new F(BrowserPageDialog.Kind.CONFIRM);assertTrue(f.dialog.pending());assertEquals(0,f.confirmed+f.cancelled);assertTrue(f.dialog.snapshot().getBoolean("ownerDecisionOnly"));assertFalse(f.dialog.snapshot().getBoolean("verifiedBusinessSuccess"));}
    @Test public void wrongOrReusedNativeIdCannotAnswerTheNewRequest()throws Exception {F f=new F(BrowserPageDialog.Kind.CONFIRM);assertFalse(f.dialog.decide(UUID.randomUUID().toString(),true,null));assertTrue(f.dialog.decide(f.dialog.id(),true,null));assertFalse(f.dialog.decide(f.dialog.id(),true,null));assertFalse(f.dialog.cancel(BrowserPageDialog.Reason.PAGE_CLOSED));assertEquals(1,f.confirmed);assertEquals(0,f.cancelled);}
    @Test public void cancellingConfirmAndPromptReturnsOnlyToTheirOriginalCallback()throws Exception {for(BrowserPageDialog.Kind k:BrowserPageDialog.Kind.values()){F f=new F(k);assertTrue(f.dialog.decide(f.dialog.id(),false,"ignored"));assertEquals(0,f.confirmed);assertEquals(1,f.cancelled);assertEquals("owner_cancelled",f.dialog.snapshot().getString("reason"));}}
    @Test public void explicitPromptValueIsDeliveredButNotPublishedOrRetainedInReceipt()throws Exception {F f=new F(BrowserPageDialog.Kind.PROMPT);String privateText="owner-private-prompt-example";assertTrue(f.dialog.decide(f.dialog.id(),true,privateText));assertEquals(privateText,f.actualPrompt);assertFalse(f.dialog.snapshot().toString().contains(privateText));assertFalse(f.dialog.snapshot().getBoolean("promptValueIncluded"));}
    @Test public void invalidPromptInputDoesNotSilentlyTruncateOrConsumeTheDecision()throws Exception {F f=new F(BrowserPageDialog.Kind.PROMPT);assertThrows(IllegalArgumentException.class,()->f.dialog.decide(f.dialog.id(),true,"x".repeat(2049)));assertTrue(f.dialog.pending());assertEquals(0,f.confirmed+f.cancelled);assertTrue(f.dialog.decide(f.dialog.id(),false,null));}
    @Test public void oldDocProfileSessionOrPauseFenceRefusesConfirmationAndCancelsOldHandleOnly()throws Exception {F f=new F(BrowserPageDialog.Kind.CONFIRM);f.current.set(false);assertFalse(f.dialog.decide(f.dialog.id(),true,null));assertEquals(0,f.confirmed);assertEquals(1,f.cancelled);assertEquals("context_changed",f.dialog.snapshot().getString("reason"));}
    @Test public void finiteOwnerTimeoutNeverBecomesAnAutomaticConfirmation()throws Exception {F f=new F(BrowserPageDialog.Kind.CONFIRM);f.now.addAndGet(BrowserPageDialog.OWNER_LIMIT_MS);assertEquals(0,f.dialog.remainingMillis());assertFalse(f.dialog.decide(f.dialog.id(),true,null));assertEquals(0,f.confirmed);assertEquals(1,f.cancelled);assertEquals("timed_out",f.dialog.snapshot().getString("reason"));}
    @Test public void waitExtensionCountsOnlyThisActualModalIntervalAndStopsAtTheDecision()throws Exception {F f=new F(BrowserPageDialog.Kind.ALERT);f.now.addAndGet(5000);assertEquals(5000,f.dialog.waitExtensionMillis());f.dialog.decide(f.dialog.id(),true,null);f.now.addAndGet(20000);assertEquals(5000,f.dialog.waitExtensionMillis());}
    @Test public void boundedExtensionDoesNotGrantAnUnlimitedNativeOrRunBudget()throws Exception {F f=new F(BrowserPageDialog.Kind.ALERT);f.now.addAndGet(600000);assertEquals(BrowserPageDialog.OWNER_LIMIT_MS,f.dialog.waitExtensionMillis());assertEquals(0,f.dialog.remainingMillis());}
    @Test public void failedNativeCallbackHasUnknownDeliveryAndCannotBeRetried()throws Exception {F f=new F(BrowserPageDialog.Kind.CONFIRM);f.throwReply=true;assertThrows(Exception.class,()->f.dialog.decide(f.dialog.id(),true,null));assertEquals(1,f.confirmed);assertFalse(f.dialog.snapshot().getBoolean("decisionDelivered"));assertEquals("callback_failed",f.dialog.snapshot().getString("reason"));assertFalse(f.dialog.cancel(BrowserPageDialog.Reason.PAGE_CLOSED));assertEquals(0,f.cancelled);}
    @Test public void snapshotCannotLeakPageSuppliedTextOrBeUsedToChangeTheNativePin()throws Exception {JSONObject pin=identity().put("message","untrusted page content");BrowserPageDialog d=new BrowserPageDialog(BrowserPageDialog.Kind.ALERT,pin,"",()->1,()->{},new BrowserPageDialog.Reply(){public void confirm(String text){}public void cancel(){}});JSONObject view=d.snapshot();assertFalse(view.has("message"));view.put("taskId",UUID.randomUUID().toString());assertEquals(pin.getString("taskId"),d.snapshot().getString("taskId"));}
    private static BrowserPageDialog modal(String op,AtomicLong now,long limit)throws Exception {return new BrowserPageDialog(BrowserPageDialog.Kind.CONFIRM,identity(),op,now::get,()->{},new BrowserPageDialog.Reply(){public void confirm(String text){}public void cancel(){}},limit);}
    @Test public void sequentialDialogsShareOneOriginalOperationAndCumulativeOwnerWaitBudget()throws Exception {
        String op=UUID.randomUUID().toString();AtomicLong now=new AtomicLong(100);BrowserPageDialog.Call call=new BrowserPageDialog.Call(op);
        BrowserPageDialog first=modal(op,now,call.remainingMillis());call.add(first);now.addAndGet(70_000);assertTrue(first.decide(first.id(),true,null));assertEquals(50_000,call.remainingMillis());
        now.addAndGet(1000);BrowserPageDialog second=modal(op,now,call.remainingMillis());call.add(second);now.addAndGet(20_000);assertEquals(90_000,call.waitExtensionMillis());assertTrue(second.decide(second.id(),false,null));now.addAndGet(500_000);assertEquals(90_000,call.waitExtensionMillis());assertEquals(30_000,call.remainingMillis());assertSame(second,call.latest());
    }
    @Test public void originalCallRefusesForeignConcurrentOrExcessDialogs()throws Exception {
        String op=UUID.randomUUID().toString();AtomicLong now=new AtomicLong(10);BrowserPageDialog.Call call=new BrowserPageDialog.Call(op);BrowserPageDialog first=modal(op,now,call.remainingMillis());call.add(first);
        assertThrows(SecurityException.class,()->call.add(modal(op,now,call.remainingMillis())));assertThrows(SecurityException.class,()->call.add(modal(UUID.randomUUID().toString(),now,call.remainingMillis())));first.decide(first.id(),true,null);
        for(int i=1;i<BrowserPageDialog.Call.MAX_DIALOGS;i++){BrowserPageDialog next=modal(op,now,call.remainingMillis());call.add(next);next.decide(next.id(),true,null);}assertThrows(SecurityException.class,()->call.add(modal(op,now,call.remainingMillis())));
    }
    @Test public void nextModalExpiresAtRemainingOriginalBudgetNotANewTwoMinutes()throws Exception {
        String op=UUID.randomUUID().toString();AtomicLong now=new AtomicLong(10);BrowserPageDialog.Call call=new BrowserPageDialog.Call(op);BrowserPageDialog first=modal(op,now,call.remainingMillis());call.add(first);now.addAndGet(119_000);first.decide(first.id(),true,null);
        BrowserPageDialog second=modal(op,now,call.remainingMillis());call.add(second);assertEquals(1000,second.snapshot().getLong("ownerLimitMs"));now.addAndGet(1000);assertFalse(second.decide(second.id(),true,null));assertEquals("timed_out",second.snapshot().getString("reason"));assertEquals(BrowserPageDialog.OWNER_LIMIT_MS,call.waitExtensionMillis());assertEquals(0,call.remainingMillis());assertThrows(SecurityException.class,()->modal(op,now,call.remainingMillis()));
    }
}
