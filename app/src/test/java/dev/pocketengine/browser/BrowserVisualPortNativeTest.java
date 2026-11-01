package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.RunControl;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.j;

/** Original Port/prepared/approval/journal/carrier. Bitmap is explicit native-producer fixture, NOT PixelCopy/WebView pixels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserVisualPortNativeTest {
    static JSONObject decide(BrowserSemanticReadSqlTest.F x,boolean allow)throws Exception {JSONObject approval=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<until){JSONObject next=x.f.events.poll(50,TimeUnit.MILLISECONDS);if(next!=null&&next.optString("type").equals("capability_approval")){approval=next;break;}}assertNotNull("native pixel capture always asks even with plain read scope",approval);assertEquals(BrowserVisualCapture.TOOL,approval.getString("tool"));String id=approval.getString("approvalId");x.f.tasks.decideCapabilityApproval(x.f.binding.taskId,id,allow);x.approvals.nativeDecisionChanged(x.f.binding.taskId,id,x.f.control);return approval;}
    @Test public void preciseApprovalDeliversOnceAfterOriginalReceiptWithoutEmbeddingPixelsInJson()throws Exception {
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(false,null,true)){
            assertEquals(NativeToolObservation.Requirement.IMAGE,x.port.observationRequirement(BrowserVisualCapture.TOOL,j("tabId",x.tab),x.f.control));ExecutorService worker=Executors.newSingleThreadExecutor();
            try{Future<JSONObject> call=worker.submit(()->x.invoke("observe_visual",j("tabId",x.tab)));JSONObject approval=decide(x,true),receipt=call.get(4,TimeUnit.SECONDS);assertTrue(receipt.toString(),receipt.getBoolean("ok"));assertEquals(1,x.driver.visualCopies);assertFalse(receipt.has("imageBase64"));assertFalse(receipt.toString().contains("data:image"));assertFalse(receipt.getBoolean("imageRetained"));assertFalse(receipt.getBoolean("coordinateAuthority"));String operation=receipt.getString("operationId");assertEquals(operation,approval.getString("operationId"));assertEquals("receipt",x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations").getJSONObject(0).getString("state"));assertThrows(SecurityException.class,()->x.port.takeToolObservation(BrowserVisualCapture.TOOL,UUID.randomUUID().toString(),x.f.control));
                try(NativeToolObservation carrier=x.port.takeToolObservation(BrowserVisualCapture.TOOL,operation,x.f.control);NativeToolObservation.Image image=carrier.consume(BrowserVisualCapture.TOOL,operation,x.f.control)){
                    assertEquals(x.driver.visualDocument,image.metadata().getString("documentId"));assertEquals("main",image.metadata().getString("frameId"));assertEquals(x.f.binding.runId,image.metadata().getString("runId"));assertFalse(image.base64(x.f.control).isEmpty());assertEquals("image/png",image.mimeType());assertThrows(SecurityException.class,()->carrier.consume(BrowserVisualCapture.TOOL,operation,x.f.control));
                }assertNull(x.port.takeToolObservation(BrowserVisualCapture.TOOL,operation,x.f.control));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());
            }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
        }
    }
    @Test public void deniedCaptureNeverCallsNativeProducerOrInheritsReadScope()throws Exception {
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(false,null,true)){ExecutorService worker=Executors.newSingleThreadExecutor();try{Future<JSONObject> call=worker.submit(()->x.invoke("observe_visual",j("tabId",x.tab)));JSONObject approval=decide(x,false),receipt=call.get(4,TimeUnit.SECONDS);assertFalse(receipt.toString(),receipt.getBoolean("accepted"));assertEquals(0,x.driver.visualCopies);String operation=approval.getString("operationId");JSONObject row=x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations").getJSONObject(0);assertEquals(operation,row.getString("operationId"));assertEquals("receipt",row.getString("state"));assertNull(x.port.takeToolObservation(BrowserVisualCapture.TOOL,operation,x.f.control));}finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}}
    }
    @Test public void laterDocumentChangeCannotSendCapturedPixelsOrReplaceOriginalControl()throws Exception {
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(false,null,true)){ExecutorService worker=Executors.newSingleThreadExecutor();try{Future<JSONObject> call=worker.submit(()->x.invoke("observe_visual",j("tabId",x.tab)));decide(x,true);JSONObject receipt=call.get(4,TimeUnit.SECONDS);String op=receipt.getString("operationId");RunControl replacement=new RunControl();replacement.bindHostRun(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch);assertThrows(SecurityException.class,()->x.port.takeToolObservation(BrowserVisualCapture.TOOL,op,replacement));try(NativeToolObservation carrier=x.port.takeToolObservation(BrowserVisualCapture.TOOL,op,x.f.control)){x.driver.visualDocument=UUID.randomUUID().toString();assertThrows(Exception.class,()->carrier.consume(BrowserVisualCapture.TOOL,op,x.f.control));}assertEquals(1,x.driver.visualCopies);}finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}}
    }
}
