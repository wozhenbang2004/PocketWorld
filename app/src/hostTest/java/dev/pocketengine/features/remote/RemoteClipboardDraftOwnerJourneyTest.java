package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.ClipData;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Original native owner/SQL/HTTP/Popen, provided synthetic ClipData, no real vendor/account.
 * Physical Android focus/clipboard helper is exercised by the mounted UI journey separately. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class RemoteClipboardDraftOwnerJourneyTest {
    static JSONObject target(RemotePiOwnerJourneyTest.Fixture f,JSONObject view,String draft,long revision)throws Exception{return f.action(view).put("draftId",draft).put("draftRevision",revision).put("mode","next_turn");}
    static RemoteOwnerWorkflows.ClipboardDraftReader reader(RemotePiOwnerJourneyTest.Fixture f,AtomicInteger calls,AtomicReference<RemoteOwnerWorkflows.ClipboardDraftReadAccess> captured){return access->{assertFalse(Thread.holdsLock(f.owner));assertFalse(Thread.holdsLock(f.tasks));access.claimNativeRead(f.nativeOwner);calls.incrementAndGet();if(captured!=null)captured.set(access);return ClipData.newPlainText("owned fixture","PRIVATE_SAMPLE_中文\nline 2");};}
    @Test public void explicitReviewReadsOnceLocallyWithNoDraftRunHttpOrBudgetEffect()throws Exception{
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject view=f.open(),input=target(f,view,UUID.randomUUID().toString(),0),before=f.source();AtomicInteger calls=new AtomicInteger();AtomicReference<RemoteOwnerWorkflows.ClipboardDraftReadAccess> held=new AtomicReference<>();
            assertEquals("PRIVATE_SAMPLE_中文\nline 2",f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,held)));assertEquals(1,calls.get());
            assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,null)));assertEquals(1,calls.get());assertThrows(Exception.class,()->held.get().checkCurrent());
            assertEquals(0,f.tasks.originalRemoteDrafts(f.task).length());assertEquals(before.getString("runId"),f.source().getString("runId"));assertEquals(before.getInt("coordinatorEpoch"),f.source().getInt("coordinatorEpoch"));assertEquals(1,f.count("submit-count"));assertEquals("1",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-prompt-count")));
        }
    }
    @Test public void wrongOwnerMalformedCursorAndBackgroundCannotEvenCallTheReader()throws Exception{
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject view=f.open(),input=target(f,view,UUID.randomUUID().toString(),0);AtomicInteger calls=new AtomicInteger();
            assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(new Object(),input,reader(f,calls,null)));
            for(JSONObject invalid:new JSONObject[]{new JSONObject(input.toString()).put("source","not an authority"),new JSONObject(input.toString()).put("draftRevision",1),new JSONObject(input.toString()).put("draftRevision",0.5),new JSONObject(input.toString()).put("taskId",UUID.randomUUID().toString())})assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,invalid,reader(f,calls,null)));
            assertEquals(0,calls.get());f.owner.setForeground(f.nativeOwner,false);assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,null)));assertEquals(0,calls.get());
        }
    }
    @Test public void changedProfileAndOriginalStopRefusePrivateReadBeforeSourceAccess()throws Exception{
        for(boolean stop:new boolean[]{false,true})try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject view=f.open(),input=target(f,view,UUID.randomUUID().toString(),0);AtomicInteger calls=new AtomicInteger();
            if(stop)f.tasks.requestCancel(f.task,f.source().getString("runId"));else f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","explicit changed native generation").put("baseUrl",f.base));
            assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,null)));assertEquals(0,calls.get());assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void lifecycleChangeDuringAnExternalReadDiscardsTheLateSample()throws Exception{
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject input=target(f,f.open(),UUID.randomUUID().toString(),0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();ExecutorService thread=Executors.newSingleThreadExecutor();
            try{
                Future<Throwable> result=thread.submit(()->{try{f.owner.clipboardDraftText(f.nativeOwner,input,access->{assertFalse(Thread.holdsLock(f.owner));assertFalse(Thread.holdsLock(f.tasks));access.claimNativeRead(f.nativeOwner);calls.incrementAndGet();entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");return ClipData.newPlainText("fixture","discard late private source");});return null;}catch(Throwable rejected){return rejected;}});
                assertTrue(entered.await(3,TimeUnit.SECONDS));f.owner.setForeground(f.nativeOwner,false);release.countDown();assertTrue(result.get(5,TimeUnit.SECONDS) instanceof RemoteDraftClipboard.Failure);assertEquals(1,calls.get());assertEquals(0,f.tasks.originalRemoteDrafts(f.task).length());assertEquals(1,f.count("submit-count"));
            }finally{release.countDown();thread.shutdownNow();assertTrue(thread.awaitTermination(3,TimeUnit.SECONDS));}
        }
    }
    @Test public void concurrentOriginalDraftCasChangeDiscardsTheSampleWithoutImportingIt()throws Exception{
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject first=f.open();String draft=UUID.randomUUID().toString();f.owner.handle(f.nativeOwner,"saveDraft",target(f,first,draft,0).put("text","old owner text").put("queued",false));JSONObject view=f.open(),input=target(f,view,draft,1);AtomicInteger calls=new AtomicInteger();
            assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,access->{access.claimNativeRead(f.nativeOwner);calls.incrementAndGet();f.tasks.saveRemoteDraft(view.getJSONObject("source"),view.getString("profileBindingHash"),draft,1,"next_turn","new separately saved owner text",false);return ClipData.newPlainText("fixture","PRIVATE_NOT_IMPORTED");}));
            assertEquals(1,calls.get());JSONObject saved=f.tasks.originalRemoteDrafts(f.task).getJSONObject(0);assertEquals(2,saved.getLong("revision"));assertEquals("new separately saved owner text",saved.getString("text"));assertFalse(saved.toString().contains("PRIVATE_NOT_IMPORTED"));assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void unclaimedOrTwiceClaimedPermitCannotBecomeAnotherNativeRead()throws Exception{
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject input=target(f,f.open(),UUID.randomUUID().toString(),0);AtomicInteger calls=new AtomicInteger();
            assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,access->{calls.incrementAndGet();return ClipData.newPlainText("fixture","not claimed");}));
            assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,null)));assertEquals(1,calls.get());
            JSONObject next=target(f,f.open(),UUID.randomUUID().toString(),0);assertEquals("once",f.owner.clipboardDraftText(f.nativeOwner,next,access->{access.claimNativeRead(f.nativeOwner);assertThrows(Exception.class,()->access.claimNativeRead(f.nativeOwner));return ClipData.newPlainText("fixture","once");}));
        }
    }
    @Test public void callersHoldingEitherOriginalMonitorAreRejectedWithoutPrivateSourceWork()throws Exception{
        try(var f=new RemotePiOwnerJourneyTest.Fixture("success")){
            JSONObject input=target(f,f.open(),UUID.randomUUID().toString(),0);AtomicInteger calls=new AtomicInteger();synchronized(f.owner){assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,null)));}synchronized(f.tasks){assertThrows(RemoteDraftClipboard.Failure.class,()->f.owner.clipboardDraftText(f.nativeOwner,input,reader(f,calls,null)));}assertEquals(0,calls.get());
        }
    }
}
