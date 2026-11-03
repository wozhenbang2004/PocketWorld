package dev.pocketengine.browser;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import dev.pocketengine.core.RunControl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real lifecycle/RunControl and upload-state transitions; the WebView callback is a native seam. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public final class BrowserArtifactUploadTest {
    static JSONObject obj(Object... values)throws Exception {
        JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;
    }
    static String id(){return UUID.randomUUID().toString();}
    static JSONObject identity()throws Exception {
        JSONObject value=obj("taskId",id(),"runId",id(),"epoch",1,"profileId",id(),"profileRevision",1L,
                "browserSessionId",id(),"browserSpaceId",id(),"tabId",id(),"tabGeneration",1L,
                "navigation",1L,"documentId",id(),"chooserId",id(),"operationId",id(),
                "fileInput",obj("ref","n0","nodeKey","k1","tag","INPUT","type","file"));
        JSONObject destination=BrowserUploads.destinationIdentity("https://upload.example/form?private=original");
        for(var keys=destination.keys();keys.hasNext();){String key=keys.next();value.put(key,destination.get(key));}return value;
    }
    static JSONObject artifact()throws Exception {
        return obj("store","workspace","id",id(),"projectId",id(),"sourceVersion",7,"sourceRevisionId",id(),
                "sha256","a".repeat(64),"name","report.txt","mimeType","text/plain","size",4L,"createdAt",1L);
    }
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> items=new ArrayDeque<>();
        public void execute(Runnable work){items.addLast(work);}
        void one(){assertFalse("Expected a queued native/worker step",items.isEmpty());items.removeFirst().run();}
    }
    static final class Pick implements ValueCallback<Uri[]> {
        int calls;Uri[] files;
        public void onReceiveValue(Uri[] received){calls++;files=received;}
    }
    static final class Fixture {
        final Queue worker=new Queue(),ui=new Queue();final RunControl original=new RunControl();
        final JSONObject pin=identity(),artifact=artifact();final CompletableFuture<JSONObject> result=new CompletableFuture<>();
        final List<String> events=new ArrayList<>();final List<Runnable> deadlines=new ArrayList<>();
        final Uri staged=Uri.parse("content://dev.pocketengine.attachments/view/"+id());
        JSONObject row;int opened,verifications,discarded,delivered;boolean sourceCurrent=true,inputCurrent=true,hostCurrent=true;
        Runnable duringBegin;Consumer<Boolean> deferredVerification;boolean deferVerification;
        final BrowserArtifactUpload upload;
        Fixture()throws Exception {
            original.bindHostRun(pin.getString("taskId"),pin.getString("runId"),1);
            upload=new BrowserArtifactUpload(original,pin,artifact,worker,new BrowserArtifactUpload.Host(){
                public void post(Runnable work){ui.execute(work);}
                public void later(Runnable work,long delay){deadlines.add(work);}
                public void check(){if(!hostCurrent)throw new SecurityException("original page changed");}
                public void openInput(BrowserArtifactUpload delivery)throws Exception {opened++;delivery.activateInput(()->{});}
                public void verifyInput(Consumer<Boolean> reply){verifications++;if(deferVerification)deferredVerification=reply;else reply.accept(inputCurrent);}
                public void delivered(JSONObject attempt){delivered++;}
            },new BrowserArtifactUpload.Files(){
                public Uri stage(){return staged;}
                public void check(){if(!sourceCurrent)throw new SecurityException("source revision revoked");}
                public void verify(){check();}
                public void discard(Uri value){assertEquals(staged,value);discarded++;}
            },new BrowserArtifactUpload.Ledger(){
                public JSONObject begin(JSONObject identity,JSONObject source)throws Exception {
                    BrowserUploadAttempt.requireNewArtifactAttempt(row==null?new JSONArray():new JSONArray().put(row),identity,source);
                    row=BrowserUploadAttempt.create(identity,new JSONArray().put(source));if(duringBegin!=null)duringBegin.run();return new JSONObject(row.toString());
                }
                public void event(JSONObject attempt,String state,JSONObject evidence)throws Exception {
                    assertEquals(row.getString("fingerprint"),attempt.getString("fingerprint"));
                    row=BrowserUploadAttempt.transition(row,state,evidence);events.add(state);
                }
            },result);
        }
        void drain(){for(int i=0;!worker.items.isEmpty()||!ui.items.isEmpty();i++){assertTrue("Lifecycle queue did not settle",i<100);if(!worker.items.isEmpty())worker.one();if(!ui.items.isEmpty())ui.one();}}
        void open(){upload.start();drain();assertEquals(1,opened);assertTrue(upload.inputRequested());assertFalse(result.isDone());}
        Pick choose(){Pick callback=new Pick();upload.chooser(callback,WebChromeClient.FileChooserParams.MODE_OPEN,new String[]{"text/plain"});return callback;}
    }

    @Test public void exactVersionDeliveredOnceAndSiteRemainsUnknown()throws Exception {
        Fixture f=new Fixture();f.open();Pick callback=f.choose();f.drain();
        assertEquals(1,callback.calls);assertArrayEquals(new Uri[]{f.staged},callback.files);assertEquals(2,f.verifications);
        assertEquals("local_delivered",f.row.getString("deliveryState"));assertEquals("unknown",f.row.getString("siteState"));
        assertEquals(List.of("delivery_entered","local_delivered"),f.events);assertEquals(1,f.delivered);assertEquals(0,f.discarded);
        JSONObject receipt=f.result.get();assertEquals("local_delivered",receipt.getString("status"));assertFalse(receipt.getBoolean("verified"));
        assertFalse(receipt.getBoolean("siteConfirmed"));assertTrue(receipt.getBoolean("requiresPause"));assertFalse(receipt.getBoolean("automaticReplayAllowed"));
        f.upload.chooser(callback,0,new String[]{"text/plain"});assertEquals("The same native callback must not be called twice",1,callback.calls);
        Pick other=f.choose();assertEquals(1,other.calls);assertNull(other.files);for(Runnable deadline:f.deadlines)deadline.run();f.drain();assertEquals(1,f.delivered);
    }

    @Test public void originalCancellationClearsUnsentCopyAndRejectsLateChooser()throws Exception {
        Fixture f=new Fixture();f.open();f.original.cancel();f.drain();Pick late=f.choose();
        assertEquals(1,late.calls);assertNull(late.files);assertEquals(1,f.discarded);assertEquals("not_delivered",f.row.getString("deliveryState"));
        assertEquals(List.of("cancelled_before_delivery"),f.events);assertFalse(f.result.get().getBoolean("accepted"));
    }

    @Test public void sourceRevokedAfterChooserNeverEntersDeliveryBoundary()throws Exception {
        Fixture f=new Fixture();f.open();Pick callback=f.choose();f.sourceCurrent=false;f.drain();
        assertEquals(1,callback.calls);assertNull(callback.files);assertEquals("not_delivered",f.row.getString("deliveryState"));
        assertEquals(List.of("cancelled_before_delivery"),f.events);assertEquals(1,f.discarded);
    }

    @Test public void inputIsRecheckedAfterByteVerificationAndDurableReservation()throws Exception {
        Fixture f=new Fixture();f.open();Pick callback=f.choose();f.worker.one();
        assertEquals("delivery_unknown",f.row.getString("deliveryState"));f.inputCurrent=false;f.drain();
        assertEquals(2,f.verifications);assertEquals(1,callback.calls);assertNull(callback.files);assertEquals(0,f.delivered);
        assertEquals("unknown",f.result.get().getString("status"));assertEquals("delivery_unknown",f.row.getString("deliveryState"));assertEquals(1,f.discarded);
    }

    @Test public void pauseResumeAfterBoundaryCannotUseTheOldNativeDecision()throws Exception {
        Fixture f=new Fixture();f.open();Pick callback=f.choose();f.worker.one();f.original.pause();f.original.resume();f.drain();
        assertEquals(1,callback.calls);assertNull(callback.files);assertEquals("unknown",f.result.get().getString("status"));
        assertEquals("delivery_unknown",f.row.getString("deliveryState"));assertEquals(0,f.delivered);
    }

    @Test public void oldVerificationCallbackCannotDeliverAfterExternalUnknownReceipt()throws Exception {
        Fixture f=new Fixture();f.open();f.deferVerification=true;Pick callback=f.choose();Consumer<Boolean> old=f.deferredVerification;
        f.result.complete(obj("status","unknown","accepted",JSONObject.NULL));f.drain();old.accept(true);f.drain();
        assertEquals(1,callback.calls);assertNull(callback.files);assertEquals(0,f.delivered);assertEquals("not_delivered",f.row.getString("deliveryState"));
    }

    @Test public void cancellationDuringReservationRecordsOneDefiniteNonDelivery()throws Exception {
        Fixture f=new Fixture();f.duringBegin=f.original::cancel;f.upload.start();f.drain();
        assertEquals(0,f.opened);assertEquals(List.of("cancelled_before_delivery"),f.events);assertEquals("not_delivered",f.row.getString("deliveryState"));
        assertEquals("unavailable",f.result.get().getString("status"));assertEquals(1,f.discarded);
    }

    @Test public void incompatibleAcceptTypeDoesNotDeliverOrFallBackToAnotherFile()throws Exception {
        Fixture f=new Fixture();f.open();Pick callback=new Pick();f.upload.chooser(callback,0,new String[]{"image/*"});f.drain();
        assertEquals(1,callback.calls);assertNull(callback.files);assertEquals("not_delivered",f.row.getString("deliveryState"));assertEquals(0,f.verifications);
        assertTrue(BrowserArtifactUpload.mimeAccepted(new String[]{".TXT"},"text/plain","report.txt"));
        assertFalse(BrowserArtifactUpload.mimeAccepted(new String[]{"image/*"},"text/plain","report.txt"));
    }
}
