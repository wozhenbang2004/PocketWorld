package dev.pocketengine.features.proxy;

import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.features.remote.RemoteTransport;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real original Tasks SQLite -> completeNative -> local model HTTP -> original Companion/drivers.
 * Only installed CLI peers and provider responses are fixtures. No real account/billing/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class NativeHarnessGatewayJourneyTest {
    private static final String PAIRED="fixture-only-paired-gateway-token-not-real-123456789012345",KEY="fixture-only-phone-provider-key-never-exported";
    private Process companion;private Path root;private JSONObject info,task,registered;private TaskStore tasks;private RunControl control;private NativeHarnessGatewayBridge bridge;private HttpServer peer;private ModelConfig model;
    private final AtomicInteger calls=new AtomicInteger();private final AtomicReference<Throwable> peerFailure=new AtomicReference<>();private final CountDownLatch releasePeer=new CountDownLatch(1),firstSemantic=new CountDownLatch(1);private boolean partial,frameBackpressure,lateFinal;
    @Before public void setUp()throws Exception {
        root=Files.createTempDirectory("native-harness-gateway-");String source=System.getProperty("pocket.gateway.source");assertNotNull("real source root required",source);
        companion=new ProcessBuilder("python","-B",source+"/companion/gateway_fixture_server.py",root.toString()).redirectError(root.resolve("companion.stderr").toFile()).start();
        info=new JSONObject(new BufferedReader(new InputStreamReader(companion.getInputStream(),StandardCharsets.UTF_8)).readLine());
        tasks=new TaskStore(RuntimeEnvironment.getApplication());task=tasks.startAttempt(tasks.createCapabilityTask("Controlled existing gateway task","").getString("id"),"capabilities",8);control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        peer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);peer.createContext("/",exchange->{try{
            calls.incrementAndGet();assertEquals("/v1/responses",exchange.getRequestURI().getPath());assertEquals("Bearer "+KEY,exchange.getRequestHeaders().getFirst("Authorization"));String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertFalse(request.contains(PAIRED));
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            String first=frame("response.created",new JSONObject().put("response",new JSONObject().put("id","fixture-response").put("status","in_progress")));if(partial)first+=frame("response.output_item.added",new JSONObject().put("output_index",0).put("item",new JSONObject().put("id","fixture-text").put("type","message").put("role","assistant").put("content",new JSONArray())))+frame("response.content_part.added",new JSONObject().put("output_index",0).put("content_index",0).put("part",new JSONObject().put("type","output_text").put("text","")))+frame("response.output_text.delta",new JSONObject().put("output_index",0).put("content_index",0).put("delta","actual semantic prefix"+"-".repeat(200)));
            exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();firstSemantic.countDown();
            if(partial){releasePeer.await(10,TimeUnit.SECONDS);return;}
            String text=lateFinal?"x".repeat(60_000):"实际原网关输出".repeat(5000);JSONObject result=new JSONObject().put("id","fixture-response").put("model","fixture-model").put("status","completed").put("output",new JSONArray().put(new JSONObject().put("id","fixture-text").put("status","completed").put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text",text))))).put("usage",new JSONObject().put("input_tokens",12).put("output_tokens",3));
            exchange.getResponseBody().write(frame("response.completed",new JSONObject().put("response",result)).getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
        }catch(Throwable failed){peerFailure.set(failed);}finally{exchange.close();}});peer.start();
        model=new ModelConfig("http://127.0.0.1:"+peer.getAddress().getPort()+"/v1",KEY,"fixture-model",false,8,15,"openai_responses",4096);
    }
    @After public void tearDown()throws Exception {releasePeer.countDown();if(bridge!=null)bridge.close();if(peer!=null)peer.stop(0);if(companion!=null){companion.destroy();if(!companion.waitFor(5,TimeUnit.SECONDS)){companion.destroyForcibly();companion.waitFor();}}if(tasks!=null)db().close();}
    private static String frame(String event,JSONObject value)throws Exception {value.put("type",event);return "event: "+event+"\ndata: "+value+"\n\n";}
    private JSONObject call(String path,JSONObject body)throws Exception{return RemoteTransport.request(info.getString("baseUrl"),PAIRED,path,body,15);}
    private void open(String family)throws Exception {
        bridge=NativeHarnessGatewayBridge.forTask(tasks,control,()->model,(path,body)->{if(path.equals("/v1/gateway/leases"))registered=new JSONObject(body.toString());if(frameBackpressure&&path.endsWith("/frames"))throw new RemoteTransport.HttpFailure(429,"controlled paired-frame window full");return call(path,body);},"fixture",info.getString("environmentBinding"),"fixture-"+family,"a".repeat(64),System.currentTimeMillis()+45000);bridge.start();
    }
    private JSONObject originalDriver(String family)throws Exception {
        open(family);JSONObject target=new JSONObject().put("environmentId","fixture").put("environmentBinding",info.getString("environmentBinding")).put("bindingId","fixture-"+family);
        JSONObject probe=call("/v1/harnesses/probe",target);assertTrue(probe.getJSONObject("capabilities").getBoolean("configWritable"));
        JSONObject planRequest=new JSONObject(target.toString()).put("operationId",UUID.randomUUID().toString()).put("identityDigest",probe.getString("identityDigest")).put("profileDigest","a".repeat(64)).put("endpointLeaseRef",bridge.leaseId()).put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch")).put("model","fixture-model");
        JSONObject plan=call("/v1/harness-config/plans",planRequest);assertFalse(plan.toString().contains(KEY));assertFalse(plan.toString().contains(registered.getString("accessToken")));
        JSONObject applied=call("/v1/harness-config/plans/"+plan.getString("planId")+"/apply",new JSONObject(target.toString()).put("operationId",planRequest.getString("operationId")).put("planDigest",plan.getString("planDigest")));assertEquals("COMMITTED",applied.getString("state"));
        JSONObject owner=new JSONObject();for(String key:List.of("taskId","runId","epoch","profileDigest","endpointLeaseRef"))owner.put(key,planRequest.get(key));
        JSONObject request=new JSONObject().put("operationId",UUID.randomUUID().toString()).put("environmentId","fixture").put("environmentBinding",info.getString("environmentBinding")).put("worker",family.equals("codex")?"codex_app_server":"claude").put("prompt","Controlled existing original task").put("model","fixture-model").put("timeoutSeconds",20).put("harnessConfigOperationId",planRequest.getString("operationId")).put("harnessOwnerBinding",owner);
        if(family.equals("codex"))request.put("readOnly",true);
        JSONObject job=call("/v1/jobs",request);Set<String> decided=new HashSet<>();long end=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<end){job=call("/v1/jobs/"+job.getString("id"),null);JSONArray approvals=job.optJSONArray("pendingApprovals");if(approvals!=null&&approvals.length()>0){JSONObject approval=approvals.getJSONObject(0);if(decided.add(approval.getString("requestId"))){assertTrue(approval.toString(),approval.getBoolean("canAccept"));call("/v1/jobs/"+job.getString("id")+"/approval",new JSONObject().put("requestId",approval.getString("requestId")).put("decision","accept"));}}
            if(Set.of("completed","failed","cancelled","blocked","unknown").contains(job.getString("status"))&&job.optBoolean("dispatchFenceFinal")&&(!job.getString("status").equals("completed")||job.optString("harnessEndpointLeaseState").equals("revoked_by_original_bridge")))break;Thread.sleep(20);}
        return job;
    }
    private SQLiteDatabase db()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private void awaitClaims(String state,int count)throws Exception {long end=System.currentTimeMillis()+5000;while(true){try(Cursor rows=db().rawQuery("SELECT count(*) FROM harness_gateway_claims WHERE lease_id=? AND state=?",new String[]{bridge.leaseId(),state})){assertTrue(rows.moveToFirst());if(rows.getInt(0)==count)return;}if(System.currentTimeMillis()>=end)fail("original native claim did not reach "+state);Thread.sleep(10);}}
    private void verifyOriginalLedger()throws Exception {
        awaitClaims("COMPLETE",1);assertNull(peerFailure.get());assertEquals(1,calls.get());JSONObject usage=tasks.inferenceUsage(task.getString("id"));JSONArray receipts=usage.getJSONArray("receipts");assertEquals(1,receipts.length());JSONObject receipt=receipts.getJSONObject(0);assertEquals("RELAY",receipt.getJSONObject("context").getString("purpose"));assertEquals(task.getString("runId"),receipt.getJSONObject("context").getString("runId"));assertEquals("COMPLETED",receipt.getString("outcome"));
        try(Cursor rows=db().rawQuery("SELECT state,request_digest FROM harness_gateway_claims WHERE lease_id=?",new String[]{bridge.leaseId()})){assertTrue(rows.moveToFirst());assertEquals("COMPLETE",rows.getString(0));assertTrue(rows.getString(1).matches("[a-f0-9]{64}"));assertFalse(rows.moveToNext());}
        assertEquals(7,db().getVersion());try(Cursor rows=db().rawQuery("SELECT count(*) FROM leases WHERE resource_id='phone.local'",null)){assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));}
    }
    @Test public void originalCodexDriverConfigAndGatewayProduceNativeRelayReceipt()throws Exception {JSONObject job=originalDriver("codex");assertEquals(job.toString(),"completed",job.getString("status"));assertEquals("revoked_by_original_bridge",job.getString("harnessEndpointLeaseState"));verifyOriginalLedger();}
    @Test public void originalClaudeDriverCrossProtocolGatewayAndControlledToolStayInOriginalLedger()throws Exception {JSONObject job=originalDriver("claude");assertEquals(job.toString(),"completed",job.getString("status"));assertTrue(Files.exists(Path.of(info.getString("workspace"),"result.txt")));verifyOriginalLedger();}
    @Test public void samePromptIsTwoExactNativeRequestsAndUnknownPriceDoesNotFreezeNewLogical()throws Exception {
        open("codex");
        for(int n=0;n<2;n++){HttpURLConnection client=(HttpURLConnection)new URL(info.getString("baseUrl")+"/v1/responses").openConnection();client.setRequestMethod("POST");client.setDoOutput(true);client.setReadTimeout(10000);client.setRequestProperty("Authorization","Bearer "+registered.getString("accessToken"));client.setRequestProperty("Content-Type","application/json");byte[] bytes="{\"model\":\"fixture-model\",\"input\":\"same legitimate prompt\",\"stream\":true}".getBytes(StandardCharsets.UTF_8);client.setFixedLengthStreamingMode(bytes.length);client.getOutputStream().write(bytes);assertEquals(200,client.getResponseCode());String raw=new String(client.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(raw.contains("response.completed"));client.disconnect();}
        awaitClaims("COMPLETE",2);assertEquals(2,calls.get());JSONArray receipts=tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts");assertEquals(2,receipts.length());assertNotEquals(receipts.getJSONObject(0).getString("callId"),receipts.getJSONObject(1).getString("callId"));assertTrue(bridge.running());
        try(Cursor rows=db().rawQuery("SELECT count(*),count(DISTINCT request_id),count(DISTINCT claim_id) FROM harness_gateway_claims WHERE lease_id=? AND state='COMPLETE'",new String[]{bridge.leaseId()})){assertTrue(rows.moveToFirst());assertEquals(2,rows.getInt(0));assertEquals(2,rows.getInt(1));assertEquals(2,rows.getInt(2));}
    }
    @Test public void partialInferenceCancellationRetainsOriginalUnknownAndCliRetryCannotRedispatch()throws Exception {
        partial=true;open("codex");ExecutorService client=Executors.newSingleThreadExecutor();CountDownLatch received=new CountDownLatch(1);AtomicReference<String> response=new AtomicReference<>("");
        Future<?> pending=client.submit(()->{try{HttpURLConnection connection=(HttpURLConnection)new URL(info.getString("baseUrl")+"/v1/responses").openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setReadTimeout(10000);connection.setRequestProperty("Authorization","Bearer "+registered.getString("accessToken"));connection.setRequestProperty("Content-Type","application/json");byte[] bytes="{\"model\":\"fixture-model\",\"input\":\"cancel original\",\"stream\":true}".getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);connection.getOutputStream().write(bytes);StringBuilder all=new StringBuilder();try(var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null){all.append(line).append('\n');if(line.contains("actual semantic prefix"))received.countDown();}}response.set(all.toString());connection.disconnect();}catch(Exception stopped){received.countDown();}});
        try{assertTrue("provider fixture wrote its first semantic frame",firstSemantic.await(5,TimeUnit.SECONDS));assertTrue("original client must receive actual semantic bytes before cancellation",received.await(5,TimeUnit.SECONDS));bridge.close();releasePeer.countDown();pending.get(5,TimeUnit.SECONDS);awaitClaims("UNKNOWN",1);assertEquals(1,calls.get());assertFalse(response.get().contains("response.completed"));
            try(Cursor rows=db().rawQuery("SELECT state FROM harness_gateway_claims WHERE lease_id=?",new String[]{bridge.leaseId()})){assertTrue(rows.moveToFirst());assertEquals("UNKNOWN",rows.getString(0));assertFalse(rows.moveToNext());}
            HttpURLConnection retry=(HttpURLConnection)new URL(info.getString("baseUrl")+"/v1/responses").openConnection();retry.setRequestMethod("POST");retry.setDoOutput(true);retry.setRequestProperty("Authorization","Bearer "+registered.getString("accessToken"));retry.setRequestProperty("Content-Type","application/json");byte[] bytes="{\"input\":\"CLI hidden retry\"}".getBytes(StandardCharsets.UTF_8);retry.setFixedLengthStreamingMode(bytes.length);retry.getOutputStream().write(bytes);assertTrue(retry.getResponseCode()>=400);retry.disconnect();assertEquals(1,calls.get());
            assertEquals(1,tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
        }finally{client.shutdownNow();}
    }
    @Test public void pairedFrameBackpressureConsumesOneAbsoluteInferenceDeadlineAndNeverReentersModel()throws Exception {
        frameBackpressure=true;model=new ModelConfig(model.baseUrl,KEY,"fixture-model",false,8,2,"openai_responses",4096);open("codex");
        long start=System.currentTimeMillis();HttpURLConnection client=(HttpURLConnection)new URL(info.getString("baseUrl")+"/v1/responses").openConnection();client.setRequestMethod("POST");client.setDoOutput(true);client.setReadTimeout(model.requestTimeoutSeconds*1000+5000);client.setRequestProperty("Authorization","Bearer "+registered.getString("accessToken"));client.setRequestProperty("Content-Type","application/json");byte[] bytes="{\"model\":\"fixture-model\",\"input\":\"bounded backpressure\",\"stream\":true}".getBytes(StandardCharsets.UTF_8);client.setFixedLengthStreamingMode(bytes.length);client.getOutputStream().write(bytes);assertTrue(client.getResponseCode()>=400);client.disconnect();awaitClaims("UNKNOWN",1);assertTrue("paired ACK waiting must not consume the longer 45-second lease",System.currentTimeMillis()-start<model.requestTimeoutSeconds*1000L+5000);assertEquals(1,calls.get());assertEquals(1,tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").length());
    }
    @Test public void lateSocketFailureAfterKnownModelCompletionFreezesOriginalSqlLeaseNotTheBill()throws Exception {
        lateFinal=true;open("codex");Socket client=new Socket();client.setReceiveBufferSize(1024);client.connect(new InetSocketAddress("127.0.0.1",new URI(info.getString("baseUrl")).getPort()));client.setSoTimeout(5000);
        try{byte[] body="{\"model\":\"fixture-model\",\"input\":\"late EOF original\",\"stream\":true}".getBytes(StandardCharsets.UTF_8);String headers="POST /v1/responses HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+registered.getString("accessToken")+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\n\r\n";client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(body);client.getOutputStream().flush();awaitClaims("COMPLETE",1);
            String request,digest;try(Cursor row=db().rawQuery("SELECT request_id,request_digest FROM harness_gateway_claims WHERE lease_id=?",new String[]{bridge.leaseId()})){assertTrue(row.moveToFirst());request=row.getString(0);digest=row.getString(1);}
            JSONObject observed=call("/v1/gateway/requests/"+request+"?leaseId="+bridge.leaseId()+"&generation="+registered.getString("generation")+"&requestDigest="+digest,null);assertEquals("FINISHING",observed.getString("state"));
            client.setSoLinger(true,0);client.close();long end=System.currentTimeMillis()+5000;String state="";while(System.currentTimeMillis()<end){try(Cursor row=db().rawQuery("SELECT state FROM harness_gateway_leases WHERE lease_id=?",new String[]{bridge.leaseId()})){assertTrue(row.moveToFirst());state=row.getString(0);}if(state.equals("REQUIRES_RECONCILE"))break;Thread.sleep(10);}assertEquals("REQUIRES_RECONCILE",state);
            assertThrows(SecurityException.class,()->NativeHarnessGatewayBridge.forTask(tasks,control,()->model,(path,value)->{throw new AssertionError("unknown stream cannot register replacement lease");},"fixture",info.getString("environmentBinding"),"fixture-codex","a".repeat(64),System.currentTimeMillis()+10000));
            assertEquals(1,calls.get());JSONObject receipt=tasks.inferenceUsage(task.getString("id")).getJSONArray("receipts").getJSONObject(0);assertEquals("COMPLETED",receipt.getString("outcome"));
        }finally{client.close();}
    }
    @Test public void bareControlCannotIssueAnyLeaseOrTask()throws Exception {int before=tasks.list().length();assertThrows(SecurityException.class,()->NativeHarnessGatewayBridge.forTask(tasks,new RunControl(),()->model,(path,body)->{throw new AssertionError("no network");},"fixture",info.getString("environmentBinding"),"fixture-codex","a".repeat(64),System.currentTimeMillis()+10000));assertEquals(before,tasks.list().length());assertEquals(0,calls.get());}
}
