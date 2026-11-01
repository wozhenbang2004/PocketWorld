package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.usage.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real native SQLite/TaskStore/Runner/HTTP, fixture model and credential seam; not Android/provider acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class InferencePricingNativeTest {
    private static final class Secrets implements SettingsStore.ApiSecrets {final Map<String,String> values=new HashMap<>();boolean failRead;public String get(String slot){if(failRead)throw new AssertionError("alternate estimate must not decrypt a key");return values.getOrDefault(slot,"");}public void put(String slot,String value){values.put(slot,value);}}
    private HttpServer server;private SettingsStore settings;private Secrets secrets;private ModelConnectionService models;private TaskStore tasks;private String id,run;private int epoch;private RunControl original;private RunJournal journal;private ModelConfig config;private final AtomicInteger requests=new AtomicInteger();
    @Before public void open()throws Exception{
        var context=RuntimeEnvironment.getApplication();var prefs=context.getSharedPreferences("private_settings",0);prefs.edit().clear().commit();secrets=new Secrets();settings=new SettingsStore(prefs,secrets);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{requests.incrementAndGet();try{exchange.getRequestBody().readAllBytes();byte[] body="{\"model\":\"served-model\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"actual fixture response\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":0}}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        models=new ModelConnectionService(settings,Files.createTempDirectory("native-price-catalog").resolve("cache"),Clock.systemUTC());models.install();models.connections();JSONObject profile=settings.saveApiConnection("",0,"fixture API",new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("model","actual-model").put("apiKey","fixture-nonreal-secret"));config=settings.selectApiConnection(profile.getString("id"),1);
        models.price(config.capabilityBinding,config.model,0,price("2","4"),false);
        tasks=new TaskStore(context);JSONObject task=tasks.startAttempt(tasks.createCapabilityTask("original price task","conversation").getString("id"),"capabilities",8);id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(id,run,epoch);journal=tasks.journal(id,run,epoch,original);
    }
    @After public void close(){if(server!=null)server.stop(0);}
    private static JSONObject price(String input,String output)throws Exception{return new JSONObject().put("currency","USD").put("sourceRef","https://prices.fixture/actual").put("sourceVersion","fixture-prices-not-live").put("rates",new JSONObject().put("INPUT",input).put("OUTPUT",output).put("CACHE_READ",input).put("CACHE_WRITE",input));}
    private JSONObject runOriginal()throws Exception{
        CapabilityPort tools=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("no tool offered");}};
        RunOutcome outcome=new AgentRunner(null,tools).run(id,"original price task",config,original,event->{try{tasks.event(id,run,epoch,event);}catch(Exception failure){throw new RuntimeException(failure);}},journal);assertNotEquals(outcome.summary,"failed",outcome.status);return tasks.inferenceUsage(id).getJSONArray("receipts").getJSONObject(0);
    }
    @Test public void originalRunnerStoresFrozenQuoteAndAlternateCurrentPriceDoesNotChangeReceiptOrDispatch()throws Exception{
        JSONObject receipt=runOriginal();assertEquals(1,requests.get());String call=receipt.getString("callId"),version=receipt.getJSONObject("usage").getString("priceVersion");assertEquals(0,new java.math.BigDecimal("0.000028").compareTo(new java.math.BigDecimal(receipt.getJSONObject("usage").getJSONObject("estimate").getString("estimatedTokenCost"))));
        assertEquals(version,tasks.inferenceReceiptForEstimate(id,call).getJSONObject("receipt").getJSONObject("usage").getString("priceVersion"));String before=tasks.get(id,false).toString();models.price(config.capabilityBinding,config.model,1,price("10","20"),false);secrets.failRead=true;
        JSONObject alternate=models.reestimate(tasks.inferenceReceiptForEstimate(id,call).getJSONObject("receipt"));assertTrue(alternate.getBoolean("known"));assertTrue(alternate.getBoolean("originalReceiptUnchanged"));assertFalse(alternate.getBoolean("newInference"));assertNotEquals(version,alternate.getJSONObject("currentPrice").getString("priceVersion"));assertEquals(0,new java.math.BigDecimal("0.00014").compareTo(new java.math.BigDecimal(alternate.getJSONObject("estimate").getString("estimatedTokenCost"))));assertEquals(before,tasks.get(id,false).toString());assertEquals(1,requests.get());
        TaskStore restarted=new TaskStore(RuntimeEnvironment.getApplication());assertEquals(version,restarted.inferenceReceiptForEstimate(id,call).getJSONObject("receipt").getJSONObject("usage").getString("priceVersion"));assertEquals(1,restarted.inferenceUsage(id).getJSONObject("money").getJSONArray("currencies").getJSONObject(0).getInt("calls"));
    }
    @Test public void pauseOrDisabledProfileCannotCreateNewEstimateCallAndOldEvidenceRemainsReadable()throws Exception{
        JSONObject receipt=runOriginal();settings.setApiConnectionEnabled(config.capabilityBinding.connectionId(),1,false);JSONObject alternate=models.reestimate(receipt);assertFalse(alternate.getBoolean("known"));assertEquals("current_connection_not_available",alternate.getString("reason"));assertEquals(1,requests.get());assertTrue(tasks.inferenceReceiptForEstimate(id,receipt.getString("callId")).getBoolean("available"));assertFalse(tasks.inferenceUsage(id).getJSONObject("money").getBoolean("moneyLimitEnforced"));
    }
    @Test public void oversizedOrForeignLegacyAuditIsRejectedBeforeOwnerBodyMaterialization()throws Exception{
        java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);String big=UUID.randomUUID().toString(),foreign=UUID.randomUUID().toString();
        // Deliberately malformed fixture rows test read refusal, never native inference evidence admission.
        TaskAuditStore.append(db,id,new JSONObject().put("type","inference_receipt").put("callId",big).put("padding","x".repeat(70000)).put("context",new JSONObject().put("taskId",id)));
        JSONObject limited=tasks.inferenceReceiptForEstimate(id,big);assertFalse(limited.getBoolean("available"));assertEquals("receipt_read_limited",limited.getString("reason"));assertFalse(limited.has("receipt"));assertEquals(65536,limited.getInt("maxBytes"));
        TaskAuditStore.append(db,id,new JSONObject().put("type","inference_receipt").put("callId",foreign).put("context",new JSONObject().put("taskId",UUID.randomUUID().toString())));assertThrows(SecurityException.class,()->tasks.inferenceReceiptForEstimate(id,foreign));assertEquals("receipt_not_found",tasks.inferenceReceiptForEstimate(id,UUID.randomUUID().toString()).getString("reason"));assertEquals(0,requests.get());
    }
}
