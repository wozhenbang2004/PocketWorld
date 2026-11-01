package dev.pocketengine.media.voice.input;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class VoiceDraftInputTest {
    private static final class Audio implements VoiceDraftInput.Audio {
        final CompletableFuture<String> acquired=new CompletableFuture<>(),finalText=new CompletableFuture<>();
        final CompletableFuture<Void> released=new CompletableFuture<>();int listens,stops,releases;String owner;boolean releaseConfirmed,unknown;
        public CompletableFuture<String> acquire(String id){owner=id;return acquired;}
        public CompletableFuture<String> listen(String id){assertEquals(owner,id);listens++;return finalText;}
        public CompletableFuture<Void> stop(String id){assertEquals(owner,id);stops++;return CompletableFuture.completedFuture(null);}
        public CompletableFuture<Void> release(String id,boolean confirmed){assertEquals(owner,id);releases++;releaseConfirmed=confirmed;return released;}
        public boolean releaseUnknown(){return unknown;}
        void acquire(){acquired.complete(owner);}
    }
    private static VoiceDraftInput.Target target(String conversation,long epoch)throws Exception{return new VoiceDraftInput.Target(conversation,epoch,false,new JSONObject().put("taskId",UUID.randomUUID().toString()).put("runId",UUID.randomUUID().toString()).put("epoch",1).put("notCopiedSecret","must not enter projection"));}
    @Test public void finalIsEditableDataOnlyAfterActualAudioReleaseAndUsesOriginalInputIdentityOnce()throws Exception{
        Audio audio=new Audio();VoiceDraftInput input=new VoiceDraftInput(()->{});VoiceDraftInput.Target original=target("original-conversation",1);input.start(original,()->{},audio);audio.acquire();audio.finalText.complete("把日期写成周一");
        assertEquals("stopping",input.snapshot().getString("phase"));assertFalse(input.snapshot().getBoolean("reviewAvailable"));assertTrue(input.leaseHeld());assertEquals(1,audio.stops);assertEquals(1,audio.releases);
        audio.released.complete(null);JSONObject ready=input.snapshot();assertTrue(ready.getBoolean("reviewAvailable"));assertFalse(ready.getBoolean("automaticSend"));assertFalse(ready.toString().contains("notCopiedSecret"));String id=ready.getString("inputId");
        JSONObject accepted=input.acceptDraft(id,"把日期核对为周二",target("original-conversation",2),()->{});assertTrue(accepted.getBoolean("ownerEdited"));assertEquals("把日期写成周一",accepted.getString("asrFinalText"));assertEquals("把日期核对为周二",accepted.getString("text"));
        assertNull(input.claimMessageId(target("other-conversation",3),()->{}));assertEquals(id,input.claimMessageId(target("original-conversation",2),()->{}));assertNull(input.claimMessageId(original,()->{}));assertEquals(id,input.snapshot().getString("submittedClientMessageId"));input.close();
    }
    @Test public void cancelledBeforeLateAcquireNeverListensAndStillWaitsForReleaseAcknowledgement()throws Exception{
        Audio audio=new Audio();VoiceDraftInput input=new VoiceDraftInput(()->{});input.start(target("c",1),()->{},audio);String id=input.snapshot().getString("inputId");input.cancel(id);audio.acquire();assertEquals(0,audio.listens);assertEquals(1,audio.stops);assertEquals(1,audio.releases);assertTrue(input.active());audio.released.complete(null);assertFalse(input.active());assertFalse(input.leaseHeld());assertFalse(input.snapshot().getBoolean("reviewAvailable"));assertEquals("cancelled",input.snapshot().getString("phase"));input.close();
    }
    @Test public void failedReleaseStaysUnknownAndCannotBeClearedByDiscardOrNewCapture()throws Exception{
        Audio audio=new Audio();VoiceDraftInput input=new VoiceDraftInput(()->{});VoiceDraftInput.Target target=target("c",1);input.start(target,()->{},audio);audio.acquire();audio.finalText.complete("最终文字");audio.released.completeExceptionally(new IllegalStateException("fixture release unavailable"));
        assertTrue(input.snapshot().getBoolean("resourceReleaseUnknown"));assertTrue(input.leaseHeld());assertFalse(input.snapshot().getBoolean("reviewAvailable"));input.discardDraft(input.snapshot().getString("inputId"));assertTrue(input.leaseHeld());assertThrows(IllegalStateException.class,()->input.start(target,()->{},new Audio()));input.close();
    }
    @Test public void oldFinalCannotReplaceAnotherCaptureOrDraft()throws Exception{
        VoiceDraftInput input=new VoiceDraftInput(()->{});Audio old=new Audio();input.start(target("first",1),()->{},old);old.acquire();input.cancel(input.snapshot().getString("inputId"));old.released.complete(null);
        Audio next=new Audio();input.start(target("second",2),()->{},next);String id=input.snapshot().getString("inputId");next.acquire();old.finalText.complete("晚到的旧原话");assertEquals(id,input.snapshot().getString("inputId"));assertEquals("",input.snapshot().getString("finalText"));next.finalText.complete("当前原话");next.released.complete(null);assertEquals("当前原话",input.snapshot().getString("finalText"));assertEquals("second",input.snapshot().getJSONObject("target").getString("conversationId"));input.close();
    }
    @Test public void anotherConversationAndAnotherUnsavedViewCannotAcceptOriginalFinal()throws Exception{
        VoiceDraftInput input=new VoiceDraftInput(()->{});Audio audio=new Audio();VoiceDraftInput.Target original=target("",1);input.start(original,()->{},audio);audio.acquire();audio.finalText.complete("未发送的新对话原话");audio.released.complete(null);String id=input.snapshot().getString("inputId");
        assertThrows(SecurityException.class,()->input.acceptDraft(id,"最终文字",target("",2),()->{}));assertThrows(SecurityException.class,()->input.acceptDraft(id,"最终文字",target("other",1),()->{}));assertTrue(input.snapshot().getBoolean("reviewAvailable"));
        assertThrows(SecurityException.class,()->input.acceptDraft(id,"最终文字",original,()->{throw new SecurityException("native owner unavailable");}));assertEquals("最终文字",input.acceptDraft(id,"最终文字",original,()->{}).getString("text"));input.close();
    }
    @Test public void recognitionFailureDoesNotChooseAnotherServiceAndUnreviewedDraftIsBoundedToOne()throws Exception{
        VoiceDraftInput input=new VoiceDraftInput(()->{});Audio failed=new Audio();VoiceDraftInput.Target original=target("c",1);input.start(original,()->{},failed);failed.acquire();failed.finalText.completeExceptionally(new IllegalStateException("fixture recognition failed"));failed.released.complete(null);assertEquals(1,failed.listens);assertFalse(input.snapshot().getBoolean("automaticFallback"));assertFalse(input.snapshot().getBoolean("reviewAvailable"));
        Audio ready=new Audio();input.start(original,()->{},ready);ready.acquire();ready.finalText.complete("一份原草稿");ready.released.complete(null);assertThrows(IllegalStateException.class,()->input.start(original,()->{},new Audio()));input.discardDraft(input.snapshot().getString("inputId"));assertFalse(input.snapshot().getBoolean("reviewAvailable"));input.close();
    }
}
