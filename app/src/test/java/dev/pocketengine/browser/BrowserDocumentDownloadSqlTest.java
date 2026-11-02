package dev.pocketengine.browser;

import android.os.Looper;
import android.webkit.WebView;
import dev.pocketengine.BrowserActivity;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.attachments.AttachmentRepository;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Original document stream/worker, Activity driver, journal, AttachmentBook and reopened bytes.
 * JS bridge replies are explicit interface fixtures; actual blob/data JS is tested separately. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class BrowserDocumentDownloadSqlTest {
    static JSONObject j(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static WebView web(BrowserActivity activity)throws Exception {Field field=BrowserActivity.class.getDeclaredField("web");field.setAccessible(true);return (WebView)field.get(activity);}
    static final class Bridge {
        final byte[] bytes="列,值\n真实字节,17\n".repeat(4096).getBytes(StandardCharsets.UTF_8);final boolean cancel;int chunks;boolean queuedDelivered;
        Bridge(boolean cancel){this.cancel=cancel;}
        void pump(BrowserTaskActivityTest.Fixture h)throws Exception {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));WebView view=web(h.browser);if(view==null)return;view.getWebViewClient().onPageFinished(view,view.getUrl());var shadow=Shadows.shadowOf(view);var callback=shadow.getLastEvaluatedJavascriptCallback();String script=shadow.getLastEvaluatedJavascript();if(callback==null||script==null||h.callbacks.contains(callback)||!queuedDelivered&&script.contains("status:'chunk',offset,size:s.bytes.length"))return;h.callbacks.add(callback);JSONObject result;
            if(script.contains("elements:nodes.map"))result=j("url",view.getUrl(),"title","document export","text","导出当前文档","elements",new JSONArray().put(j("ref","n0","nodeKey","k1","tag","A","type","","text","下载CSV","href","blob:https://example.com/"+UUID.randomUUID())));
            else if(script.contains("const owned={token,element,href"))result=j("status","reading");
            else if(script.contains("status:'ready',size:s.bytes.length"))result=j("status","ready","size",bytes.length,"mime","text/csv");
            else if(script.contains("status:'chunk',offset,size:s.bytes.length")){Matcher offset=Pattern.compile("const offset=(\\d+);").matcher(script);assertTrue(offset.find());int start=Integer.parseInt(offset.group(1));byte[] chunk=Arrays.copyOfRange(bytes,start,Math.min(bytes.length,start+BrowserDocumentInput.CHUNK));chunks++;result=j("status","chunk","offset",start,"size",bytes.length,"data",Base64.getEncoder().encodeToString(chunk));}
            else throw new AssertionError("Unexpected document bridge JS: "+script.substring(0,Math.min(script.length(),100)));
            callback.onReceiveValue(JSONObject.quote(result.toString()));if(cancel&&chunks==1)h.current.control.cancel();
        }
        JSONObject call(BrowserTaskActivityTest.Fixture h,String command,JSONObject args)throws Exception {
            String tool="browser_task_"+command,operation=h.current.journal.beginOperation(tool,args);Future<JSONObject> future=h.worker.submit(()->h.current.port.execute(tool,new JSONObject(args.toString()).put("_hostOperationId",operation),h.current.control));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!future.isDone()&&System.nanoTime()<deadline){pump(h);Thread.sleep(3);}JSONObject receipt=future.get(2,TimeUnit.SECONDS);h.current.journal.finishOperation(operation,receipt);return receipt;
        }
    }
    void journey(boolean cancel)throws Exception {
        BrowserActivity.setOwnerPanelInstaller(null);BrowserTaskActivityTest original=new BrowserTaskActivityTest();try(BrowserTaskActivityTest.Fixture h=original.new Fixture()){
            h.tasks.authorizeTools(h.taskId,h.current.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));String tab=h.open("document-export"),run=h.current.binding.runId;Bridge bridge=new Bridge(cancel);JSONObject observed=bridge.call(h,"observe",j("tabId",tab));JSONObject queued=bridge.call(h,"download_document",j("tabId",tab,"observationId",observed.getString("observationId"),"ref","n0","name","result.csv"));assertEquals("queued",queued.getString("status"));assertFalse(queued.has("attachment"));bridge.queuedDelivered=true;BrowserDownloads downloads=BrowserDownloads.get(h.browser);JSONObject done=queued;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(done.getString("status").matches("queued|running|stopping")&&System.nanoTime()<deadline){bridge.pump(h);Thread.sleep(3);done=downloads.status(queued.getString("id"));}AttachmentRepository repository=AttachmentRepository.get(h.browser);
            assertEquals(h.taskId,done.getString("taskId"));assertEquals(run,done.getString("runId"));assertEquals(tab,done.getString("tabId"));assertEquals(queued.getString("operationId"),done.getString("operationId"));assertFalse(done.getBoolean("verified"));
            if(cancel){assertEquals(done.toString(),"cancelled",done.getString("status"));assertEquals(1,bridge.chunks);assertEquals(0,repository.book.list().length());assertFalse(done.has("attachment"));}
            else{assertEquals(done.toString(),"downloaded",done.getString("status"));assertTrue(bridge.chunks>1);JSONObject attachment=done.getJSONObject("attachment"),source=attachment.getJSONObject("source");byte[] reopened=Files.readAllBytes(repository.book.file(attachment.getString("id")).toPath());assertArrayEquals(bridge.bytes,reopened);assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reopened)),attachment.getString("sha256"));assertEquals(bridge.bytes.length,attachment.getLong("size"));assertEquals("browser_document_download",source.getString("kind"));assertEquals(h.taskId,source.getString("taskId"));assertEquals(run,source.getString("runId"));assertEquals(queued.getString("documentId"),source.getString("documentId"));assertEquals(queued.getString("operationId"),source.getString("operationId"));}
            JSONArray operations=h.tasks.get(h.taskId,false).getJSONArray("operations");int starts=0;for(int i=0;i<operations.length();i++)if("browser_task_download_document".equals(operations.getJSONObject(i).getString("tool"))){starts++;assertEquals("receipt",operations.getJSONObject(i).getString("state"));assertEquals("queued",operations.getJSONObject(i).getJSONObject("receipt").getString("status"));}assertEquals(1,starts);assertEquals(1,h.tasks.list().length());
        }
    }
    @Test public void originalDocumentChunksBecomeOneRealReopenedAttachmentWithExactDigest()throws Exception {journey(false);}
    @Test public void originalRunCancellationAfterOneChunkLeavesNoCommittedAttachmentOrAutomaticRestart()throws Exception {journey(true);}
}
