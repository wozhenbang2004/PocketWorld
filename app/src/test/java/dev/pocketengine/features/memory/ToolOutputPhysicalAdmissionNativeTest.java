package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual original SQL Target, Book removal and Gateway dispatch; not a custom substitute admission. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ToolOutputPhysicalAdmissionNativeTest {
    @Test public void mainSqlAdmissionRechecksAfterStartedAndKeepsOriginalAccounting()throws Exception{check(InferenceReceipt.Purpose.MAIN);}
    @Test public void summarySqlAdmissionRechecksAfterStartedAndKeepsOriginalAccounting()throws Exception{check(InferenceReceipt.Purpose.SUMMARY);}
    private static void check(InferenceReceipt.Purpose purpose)throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);RunControl control=new RunControl();AtomicInteger posts=new AtomicInteger(),starts=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{posts.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
        try{
            JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("真实SQL物理派发来源门","").getString("id"),"capabilities",8);String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);tasks.authorizeTools(id,run,new JSONArray().put("attachments_read_document"));
            var book=AttachmentRepository.get(context).book;JSONObject source=book.importBytes(new ByteArrayInputStream("实际附件来源".getBytes(StandardCharsets.UTF_8)),"physical.txt","text/plain",new JSONObject().put("kind","owner_fixture"));
            tasks.toolOutputFactory(id,run,epoch,control,text->text).begin("attachments_read_document","",ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,control).record(new JSONObject().put("attachmentId",source.getString("id")).put("sha256",source.getString("sha256")).put("text","真实返回原件"),false);
            InferenceCapture.Target actual=tasks.journal(id,run,epoch,control).inferenceTarget(purpose,"");
            InferenceCapture.Target observed=InferenceCapture.observe(actual,new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt)throws Exception{assertFalse("Book mutation must occur after original SQL monitor has been released",Thread.holdsLock(tasks));assertEquals(purpose,receipt.context.purpose);starts.incrementAndGet();book.remove(source.getString("id"));}
                public void finished(InferenceReceipt receipt){}
            });
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-key","fixture",false,8,10,"openai_chat",500);JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","固定原来源的真实派发门"));
            assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->ModelGateway.completeStreaming(config,messages,null,control,event->{},observed));
            assertEquals(1,starts.get());assertEquals(0,posts.get());assertFalse(control.isCancelled());JSONObject usage=tasks.inferenceUsage(id);assertEquals(1,usage.getInt("attempts"));assertEquals(0,usage.getInt("completed"));assertEquals(1,usage.getInt("failed"));assertEquals(purpose.name(),usage.getJSONArray("receipts").getJSONObject(0).getJSONObject("context").getString("purpose"));assertTrue(usage.isNull("actualCost"));
        }finally{server.stop(0);db.close();}
    }
}
