package dev.pocketengine.runtime.eval;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.eval.TaskEvaluation.*;

/** Loopback fixture only. The real SQL/RunControl/Gateway/Runner path is not replaced. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProductionTaskEvaluationTest {
 private static final class Fixture implements AutoCloseable {
  final TaskStore tasks;final HttpServer server;final AtomicInteger posts=new AtomicInteger();
  Fixture()throws Exception {Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{posts.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"PW-EVAL_SUCCESS material-572\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();}
  JSONObject run(boolean achieved,Set<String> environment)throws Exception {Case c=new Case("marked-read","marked-family",Partition.TUNE,"PW-EVAL-READ",Expected.VERIFIED_SUCCESS,Set.of("fixture_page"),List.of("material-572"));JSONObject task=tasks.create(c.marker()+" 读取测试页，不执行写入");String id=task.getString("id");JSONObject attempt=tasks.startAttempt(id,"main_screen",4);RunControl original=new RunControl();original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject observe(boolean image,RunControl control)throws Exception{control.check();return new JSONObject().put("ok",true).put("observationId",UUID.randomUUID().toString()).put("packageName","dev.pocketengine.eval.fixture").put("text","material-572").put("nodes",new JSONArray());}public JSONObject execute(String tool,JSONObject args,RunControl control){throw new AssertionError("read-only evaluation may not dispatch a device effect");}};
   ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-only-not-a-real-key","fixture-model",false,4,10);
   return ProductionTaskEvaluation.runPrepared(c,environment,tasks,id,config,original,new AgentRunner(phone),saved->{JSONArray events=saved.getJSONArray("events");List<String> evidence=new ArrayList<>();for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.optString("type").equals("observation")&&event.optJSONObject("receipt")!=null)evidence.add(event.getJSONObject("receipt").optString("text"));}return new CheckedEffects(!evidence.isEmpty(),achieved,true,evidence,"verified");});
  }
  public void close()throws Exception {server.stop(0);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
 }
 @Test public void productionOriginalTaskHasIndependentReadAndSamePhysicalUsage()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(true,Set.of("fixture_page"));assertTrue(result.toString(),result.getBoolean("passed"));assertEquals("verified_success",result.getString("classification"));assertEquals(1,f.posts.get());assertEquals(1,result.getJSONObject("usage").getInt("attempts"));assertEquals("needs_confirmation",result.getString("productionStatus"));}}
 @Test public void productionModelFalseSuccessIsSeparateFromActualGoalEvidence()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(false,Set.of("fixture_page"));assertEquals("false_success",result.getString("classification"));assertFalse(result.getBoolean("passed"));assertEquals(1,f.posts.get());}}
 @Test public void missingEnvironmentSkipsWithoutAnyModelOrToolDispatch()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(false,Set.of());assertEquals("skipped",result.getString("classification"));assertFalse(result.getBoolean("passed"));assertEquals(0,f.posts.get());}}
}
