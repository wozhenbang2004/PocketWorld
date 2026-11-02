package dev.pocketengine.store;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ModelProbeNativeJourneyTest {
 private static final class Fixture implements AutoCloseable {
  final TaskStore tasks;final SettingsStore settings;final ModelConnectionService service;final HttpServer server;final AtomicInteger posts=new AtomicInteger();final AtomicBoolean change=new AtomicBoolean();
  Fixture()throws Exception {Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.getSharedPreferences("private_settings",0).edit().clear().commit();settings=new SettingsStore(app.getSharedPreferences("private_settings",0),new SettingsStore.ApiSecrets(){final java.util.Map<String,String> secrets=new java.util.HashMap<>();public String get(String slot){return secrets.getOrDefault(slot,"");}public void put(String slot,String value){secrets.put(slot,value);}});tasks=new TaskStore(app);
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{posts.incrementAndGet();JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String prompt=input.getJSONArray("messages").getJSONObject(0).getString("content"),text=input.has("tools")?"Plain text only":prompt.substring(prompt.lastIndexOf(' ')+1);if(change.getAndSet(false))settings.save(new JSONObject().put("model","different-model"));JSONObject response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",text)))).put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1).put("total_tokens",3));byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception problem){throw new RuntimeException(problem);}finally{exchange.close();}});server.start();settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("model","fixture-model").put("sendScreenshots",false));service=new ModelConnectionService(settings,Files.createTempDirectory("probe-catalog").resolve("catalog.json"),Clock.systemUTC());service.install();service.attachOwnerStore(tasks);service.connections();
  }
  ModelBinding binding()throws Exception{return settings.apiConfigForCatalog().capabilityBinding;}
  public void close()throws Exception{server.stop(0);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(tasks)).close();}
 }
 @Test public void realTextEndpointIgnoringToolsHasIndependentFailedToolEvidenceAndOriginalUsage()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.service.probe(f.binding(),false,new RunControl());assertEquals("passed",result.getString("text"));assertEquals("failed",result.getString("tools"));assertEquals("not_requested",result.getString("image"));assertFalse(result.getBoolean("deviceToolsExecuted"));assertFalse(result.getBoolean("capabilityMetadataChanged"));String task=result.getJSONObject("inferenceReference").getString("taskId");assertEquals(2,f.posts.get());assertEquals(2,f.tasks.inferenceUsage(task).getInt("attempts"));assertEquals(0,f.tasks.get(task,false).getJSONArray("operations").length());assertNotNull(f.service.snapshot().optJSONObject("capabilityProbe"));f.settings.save(new JSONObject().put("model","changed-model"));assertNull(f.service.snapshot().optJSONObject("capabilityProbe"));}}
 @Test public void changedConfigStopsBeforeNextProbeAndDoesNotPublishOldEvidence()throws Exception {try(Fixture f=new Fixture()){ModelBinding binding=f.binding();f.change.set(true);assertThrows(SecurityException.class,()->f.service.probe(binding,false,new RunControl()));assertEquals(1,f.posts.get());assertNull(f.service.snapshot().optJSONObject("capabilityProbe"));}}
 @Test public void typedProbeKeepsItsDeclaredBoundAndOrdinaryConnectionTestsStayAtOneCall()throws Exception {try(Fixture f=new Fixture()){for(int limit:new int[]{1,2,3}){RunControl control=new RunControl();String source=java.util.UUID.randomUUID().toString();try(var session=limit==1?f.tasks.beginNativeInference(TaskStore.NativeInferenceKind.CONNECTION_TEST,source,control):f.tasks.beginModelCapabilityProbe(source,control,limit==3)){var target=session.target();var config=new dev.pocketengine.core.ModelConfig("https://fixture.invalid/v1","fixture-only-key","fixture-model",false,4,10);for(int i=0;i<limit;i++){var capture=new dev.pocketengine.runtime.usage.InferenceCapture(config,control,target);capture.dispatch();capture.finish(dev.pocketengine.runtime.usage.InferenceReceipt.Outcome.COMPLETED);}var extra=new dev.pocketengine.runtime.usage.InferenceCapture(config,control,target);assertThrows(SecurityException.class,extra::dispatch);String id=session.reference().getString("taskId");assertEquals(limit,f.tasks.inferenceUsage(id).getInt("attempts"));assertEquals(limit,f.tasks.get(id,false).getJSONObject("nativeInference").getInt("maxCalls"));assertEquals(0,f.tasks.get(id,false).getJSONArray("operations").length());session.finish("completed");}}assertEquals(0,f.posts.get());}}
}
