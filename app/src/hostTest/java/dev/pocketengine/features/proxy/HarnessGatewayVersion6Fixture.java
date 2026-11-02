package dev.pocketengine.features.proxy;
import dev.pocketengine.store.TaskStore;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.proxy.*;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.providers.ProtocolCodec;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class HarnessGatewayVersion6Fixture {
 @Test public void writeActualFrozenVersion6TaskAndNativePhysicalReceipt()throws Exception {
  var context=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(context);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);assertEquals("must actually run original v6 TaskStore, not candidate with lowered pragma",6,db.getVersion());
  JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("Real original v6 gateway migration fixture","").getString("id"),"capabilities",2);RunControl control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
  HttpServer peer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);peer.createContext("/",e->{try{e.getRequestBody().readAllBytes();byte[] body="{\"id\":\"fixture-response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"original-v6 actual inference\"}]}],\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);}finally{e.close();}});peer.start();
  try{ModelConfig model=new ModelConfig("http://127.0.0.1:"+peer.getAddress().getPort()+"/v1","fixture-only-v6-provider-key","fixture-model",false,2,10,"openai_responses",256);var prepared=NativeRelayPolicy.prepare(model,"/v1/responses",new JSONObject().put("model","fixture-model").put("input","original v6 controlled request").put("stream",false));ModelGateway.completeNative(model,prepared.gateway,control,tasks.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control).inferenceTarget(InferenceReceipt.Purpose.RELAY,""),null,(event,data)->{});}finally{peer.stop(0);}
  JSONObject receipt=tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").getJSONObject(0);assertEquals("COMPLETED",receipt.getString("outcome"));Path out=Path.of(System.getProperty("pocket.gateway.evidence"));Files.writeString(out.resolve("original-v6.json"),new JSONObject().put("taskId",task.getString("id")).put("callId",receipt.getString("callId")).put("receipt",receipt).toString());String path=db.getPath();db.close();Files.copy(Path.of(path),out.resolve("original-v6.db"),StandardCopyOption.REPLACE_EXISTING);
 }
}
