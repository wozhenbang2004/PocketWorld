package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.*;
import dev.pocketengine.features.memory.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.*;
import dev.pocketengine.store.*;
import dev.pocketengine.testutil.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real original SQLite/Runner/Host/Plugin/MCP loopback HTTP and output Reader, not a live account.
 * Fixture AndroidKeyStore key is synthetic; original Vault encryption and native SQL still run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpOutputNativeRunnerJourneyTest {
    private static final String PROVIDER="SyntheticMcpOutputKeyStore",TOKEN="synthetic-mcp-output-token",MODEL_KEY="synthetic-model-output-key",NEEDLE="MCP_DIAGNOSTIC_NEEDLE_714";
    private static String id(){return UUID.randomUUID().toString();}
    private static String fingerprint(String actual)throws Exception{StringBuilder value=new StringBuilder("sha256:");for(byte b:MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8)))value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    @Before public void before(){
        Provider provider=new Provider(PROVIDER,1.0,"Known test key, not hardware-backed Android"){};
        provider.put("KeyStore.AndroidKeyStore",FixtureKeyStore.class.getName());Security.addProvider(provider);
        RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets",Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void after(){Security.removeProvider(PROVIDER);ContextBudgetPolicy.installHostSource(c->null);}
    private static final class Domain implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;
        final PluginsModule plugins;final FeatureHost features;
        Domain(McpFixture wire)throws Exception {
            android.deleteDatabase("pocket-world.db");File declarations=new File(android.getFilesDir(),"plugins.json");if(declarations.exists())assertTrue(declarations.delete());
            tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),e->{});
            plugins=new PluginsModule(context);features=new FeatureHost(e->{},tasks,()->false);features.register(plugins);
            JSONObject preview=plugins.handle("discoverMcp",new JSONObject().put("id","output-native").put("name","本机受控原MCP").put("endpoint",wire.endpoint()).put("token",TOKEN).put("protocolVersion",McpData.VERSION));
            JSONObject installed=plugins.handle("installMcp",new JSONObject().put("previewId",preview.getString("previewId")).put("expectedInstallationId",preview.getString("expectedInstallationId")).put("tools",new JSONArray().put("lookup")));
            NativeOwnerWorkflows owner=new NativeOwnerWorkflows(features,context,tasks,new MemorySourceBridge(features,tasks));
            owner.mcp("setToolGrantReviewed",new JSONObject().put("id",installed.getString("id")).put("expectedInstallationId",installed.getString("installationId")).put("expectedGrantsDigest",installed.getString("grantsDigest")).put("tool","lookup").put("granted",true));
            owner.mcp("setEnabledReviewed",new JSONObject().put("id",installed.getString("id")).put("expectedInstallationId",installed.getString("installationId")).put("enabled",true));
            assertEquals(0,wire.count("tools/call"));
        }
        long outputs(){try(Cursor c=db.rawQuery("SELECT count(*) FROM task_output_sources",null)){assertTrue(c.moveToFirst());return c.getLong(0);}}
        String retained(JSONObject ref)throws Exception {
            String output=ToolOutputStore.outputId(ref.getString("taskId"),ref.getString("runId"),ref.getString("sourceId"));StringBuilder bytes=new StringBuilder();
            try(Cursor rows=db.rawQuery("SELECT body FROM task_output_chunks WHERE output_id=? ORDER BY chunk_index",new String[]{output})){while(rows.moveToNext())bytes.append(rows.getString(0));}return bytes.toString();
        }
        @Override public void close(){try{features.close();}finally{db.close();}}
    }
    private static final class Peer implements AutoCloseable {
        final HttpServer server;final AtomicInteger requests=new AtomicInteger();final List<JSONObject> bodies=new CopyOnWriteArrayList<>();final List<Throwable> failures=new CopyOnWriteArrayList<>();
        final boolean archiveFailure;volatile boolean found;volatile JSONObject reference;volatile int actualRawReads;
        Peer(boolean archiveFailure)throws Exception {
            this.archiveFailure=archiveFailure;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));bodies.add(request);requests.incrementAndGet();
                JSONObject message=respond(request);byte[] response=new JSONObject().put("model","controlled-original-output-runner")
                    .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop")))
                    .put("usage",new JSONObject().put("prompt_tokens",23).put("completion_tokens",7).put("total_tokens",30)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }catch(Throwable failure){failures.add(failure);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        }
        JSONObject respond(JSONObject request)throws Exception {
            JSONObject capture=null,lastQuery=null,lastRead=null;boolean actualReceipt=false;
            JSONArray history=request.getJSONArray("messages");
            for(int i=0;i<history.length();i++){
                JSONObject message=history.getJSONObject(i);if(!"tool".equals(message.optString("role")))continue;
                JSONObject body;try{body=new JSONObject(message.getString("content"));}catch(Exception omitted){continue;}
                if(body.has("outputCapture")){capture=body.getJSONObject("outputCapture");actualReceipt="response_received".equals(body.optString("status"));}
                if("outputs".equals(body.optString("section")))lastQuery=body;
                if("native_tool_output".equals(body.optString("kind"))&&body.has("body"))lastRead=body;
            }
            if(lastRead!=null&&lastRead.getString("body").contains(NEEDLE)){found=true;return new JSONObject().put("role","assistant").put("content","已从原文ref实际分页找到中间诊断；这是工具返回的低信任证据，不是业务正确性保证。");}
            if(archiveFailure&&capture!=null&&actualReceipt){assertEquals("unavailable",capture.getString("status"));return new JSONObject().put("role","assistant").put("content","实际工具已返回，但原文留存失败，不能声称已找到中间诊断，也不重发原调用。");}
            if(capture!=null&&capture.getJSONArray("outputRefs").length()>0){
                reference=new JSONObject(capture.getJSONArray("outputRefs").getJSONObject(0).toString());
                if(lastQuery==null)return call("memory_raw_query",new JSONObject().put("section","outputs").put("query",NEEDLE).put("limit",1).put("maxChars",4000));
                assertEquals(1,lastQuery.getJSONArray("entries").length());
                JSONObject hit=lastQuery.getJSONArray("entries").getJSONObject(0);assertEquals(reference.getString("sourceId"),hit.getString("sourceId"));assertEquals(reference.getString("revision"),hit.getString("revision"));
                int offset=lastRead==null?0:lastRead.getInt("nextOffset");assertTrue("Original page must advance",offset>=0);actualRawReads++;
                return call("memory_raw_read",new JSONObject().put("sourceId",reference.getString("sourceId")).put("revision",reference.getString("revision")).put("offset",offset).put("maxChars",4000));
            }
            JSONArray tools=request.getJSONArray("tools");String plugin="";for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getJSONObject("function").getString("name");if(name.startsWith("plugin_"))plugin=name;}assertFalse(plugin.isEmpty());
            return call(plugin,new JSONObject().put("q","original-native-result"));
        }
        private JSONObject call(String name,JSONObject args)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",id()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));}
        ModelConfig model(){return new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1",MODEL_KEY,"controlled-original-output-runner",false,20,20,"openai_chat",512);}
        public void close(){server.stop(0);}
    }
    private static TaskStoreMemoryReader.RawBinding raw(Domain d,String task,String run,int epoch,RunControl original)throws Exception {
        MemoryHostFence fence=new MemoryHostFence(){public void check()throws Exception{d.tasks.checkExtensionRun(task,run,epoch,original);}public JSONObject admit(Admission action)throws Exception{synchronized(d.tasks){check();return action.run();}}};
        TaskStoreMemoryReader.RawBinding raw=new TaskStoreMemoryReader.RawBinding(task,run,epoch,original,fence,()->0);raw.bindOutputSources(d.tasks.toolOutputSources());d.tasks.bindRawModelExposure(raw);return raw;
    }
    private static RunOutcome run(Domain d,Peer peer,String task)throws Exception {
        JSONObject attempt=d.tasks.startAttempt(task,"capabilities",24);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(task,run,epoch);
        ToolOutputCapture.Factory factory=d.tasks.toolOutputFactory(task,run,epoch,original,text->ModelGateway.redact(text,MODEL_KEY));
        CapabilityPort port=new HostToolSupervisor(d.features,d.tasks,task,run,epoch,true,factory);
        TaskStoreMemoryReader.RawBinding raw=raw(d,task,run,epoch,original);port=new TaskStoreMemoryReader.RawCapability(port,raw,new TaskStoreMemoryReader.RawAccess(){
            public JSONObject query(TaskStoreMemoryReader.RawBinding b,JSONObject options)throws Exception{return d.tasks.queryMemoryRaw(b,options);}
            public JSONObject read(TaskStoreMemoryReader.RawBinding b,JSONObject citation)throws Exception{return d.tasks.readMemoryRaw(b,citation);}
        });port=new ToolOutputSourcePort(port,factory,original);
        JSONArray tools=port.tools(),names=new JSONArray();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));d.tasks.authorizeTools(task,run,names);
        ContextBudgetPolicy.installHostSource(c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
        try{
            RunOutcome outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource()).run(task,"调用主人已批的原MCP工具；用同原件ref分页定位中间诊断，留存失败或效果unknown时不重复提交。",peer.model(),original,
                event->{try{d.tasks.event(task,run,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},d.tasks.journal(task,run,epoch,original));
            d.tasks.endAttempt(task,run,"needs_confirmation".equals(outcome.status)?"awaiting_user":outcome.status,outcome.summary,outcome.evidence);return outcome;
        }finally{port.close();}
    }
    private static void approveExactOriginal(Domain d,String task)throws Exception {
        JSONArray approvals=d.tasks.get(task,false).getJSONArray("approvals");JSONObject pending=null;
        for(int i=0;i<approvals.length();i++)if("pending".equals(approvals.getJSONObject(i).optString("state")))pending=approvals.getJSONObject(i);
        assertNotNull("original exact pending operation approval",pending);assertTrue(pending.getString("tool").startsWith("plugin_"));d.tasks.decideCapabilityApproval(task,pending.getString("id"),true);
    }
    private static void setupWire(McpFixture wire)throws Exception {
        wire.definitions=new JSONArray().put(McpFixture.tool("lookup",new JSONObject().put("type","object").put("properties",new JSONObject().put("q",new JSONObject().put("type","string"))).put("required",new JSONArray().put("q")).put("additionalProperties",false)));
        wire.result=new JSONObject().put("resultType","complete").put("isError",true)
            .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","A".repeat(25000)+NEEDLE+"Z".repeat(35000)+TOKEN+MODEL_KEY)))
            .put("structuredContent",new JSONObject().put("payload","S".repeat(40000)).put("authorization",TOKEN).put("modelSecret",MODEL_KEY));
    }
    private static void actualMissingPreparedRefusesBeforeAnyMcpHttp(Domain d,McpFixture wire)throws Exception {
        JSONObject registered=d.plugins.toolRegistry().getJSONObject(0);String tool=registered.getString("name");
        String task=d.tasks.createCapabilityTask("真实Factory拒绝未登记的prepared身份","").getString("id");JSONObject attempt=d.tasks.startAttempt(task,"capabilities",4);
        String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(task,run,epoch);d.tasks.authorizeTools(task,run,new JSONArray().put(tool));
        String grantBefore;try(Cursor row=d.db.rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{task,run})){assertTrue(row.moveToFirst());grantBefore=row.getString(0);}
        int httpBefore=wire.received.size();JSONObject input=new JSONObject().put("id",registered.getString("pluginId")).put("tool",registered.getString("tool"))
            .put("expectedVersion",registered.getString("version")).put("expectedInstallationId",registered.getString("installationId"))
            .put("expectedExecutionIdentity",TaskStore.capabilityFingerprint(registered.getJSONObject("executionBinding")))
            .put("_taskId",task).put("_runId",run).put("_operationId",id()).put("arguments",new JSONObject().put("q","no-original-prepared-row"));
        JSONObject denied=d.plugins.invokeForHost(input,original,d.tasks.toolOutputFactory(task,run,epoch,original,text->ModelGateway.redact(text,MODEL_KEY)),tool);
        assertEquals("mcp_output_admission_denied",denied.getString("code"));assertEquals("failed",denied.getString("status"));assertEquals("not_dispatched",denied.getString("dispatchStatus"));
        assertTrue(denied.getBoolean("requiresPause"));assertFalse(denied.getBoolean("nativeDispatched"));assertFalse(denied.getBoolean("accepted"));assertEquals(httpBefore,wire.received.size());assertEquals(0,wire.count("tools/call"));assertEquals(0,d.outputs());
        try(Cursor row=d.db.rawQuery("SELECT grant_json FROM runs WHERE task_id=? AND run_id=?",new String[]{task,run})){assertTrue(row.moveToFirst());assertEquals(grantBefore,row.getString(0));}
        assertEquals(0,d.tasks.get(task,false).getJSONArray("operations").length());d.tasks.endAttempt(task,run,"cancelled","原SQL确定拒绝；没有MCP派发，也未退款或补满",new JSONObject());
    }
    @Test public void originalOwnerApprovalRunnerLargeMcpRefQueryAndPagedReadFindActualMiddleAndNativeAudit()throws Exception {
        try(McpFixture wire=new McpFixture();Peer peer=new Peer(false)){
            setupWire(wire);try(Domain d=new Domain(wire)){
                actualMissingPreparedRefusesBeforeAnyMcpHttp(d,wire);
                String task=d.tasks.createCapabilityTask("原任务大MCP证据回取","").getString("id");RunOutcome waiting=run(d,peer,task);assertEquals(waiting.summary,"awaiting_user",waiting.status);assertEquals(0,wire.count("tools/call"));
                approveExactOriginal(d,task);RunOutcome outcome=run(d,peer,task);assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertTrue(peer.failures.toString(),peer.failures.isEmpty());assertTrue(peer.found);assertTrue(peer.actualRawReads>4);assertEquals(1,wire.count("tools/call"));assertEquals(1,d.outputs());
                JSONObject ref=peer.reference;assertNotNull(ref);assertEquals("MCP_RESULT_JSON",ref.getString("captureKind"));assertTrue(ref.getBoolean("retainedNativeDataComplete"));assertTrue(ref.isNull("upstreamTruncated"));
                String exact=d.retained(ref);assertTrue(exact.contains(NEEDLE));assertFalse(exact.contains(TOKEN));assertFalse(exact.contains(MODEL_KEY));assertEquals(ref.getString("revision"),fingerprint(exact));
                JSONObject data=new JSONObject(exact);assertTrue(data.getJSONObject("result").getBoolean("isError"));assertEquals("supported_text_and_structured_content_fields",data.getString("sourceCoverage"));assertTrue(data.getJSONObject("provenance").getString("hostTool").startsWith("plugin_"));
                assertFalse(d.tasks.get(task,false).toString().contains(TOKEN));assertFalse(peer.bodies.toString().contains(TOKEN));assertFalse(peer.bodies.toString().contains(MODEL_KEY));
                JSONObject request=new JSONObject().put("taskId",task).put("runId",ref.getString("runId")).put("sourceId",ref.getString("sourceId")).put("revision",ref.getString("revision")).put("offset",0).put("maxChars",4000);
                JSONObject audit=d.tasks.readNativeOutputAudit(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),request,new RunControl());assertEquals("current",audit.getString("status"));assertTrue(audit.getBoolean("auditOnly"));assertFalse(audit.getBoolean("modelSourceAdmission"));
                JSONObject wrong=new JSONObject(request.toString()).put("revision","sha256:"+"f".repeat(64));JSONObject refused=d.tasks.readNativeOutputAudit(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),wrong,new RunControl());assertEquals("source_changed",refused.getString("status"));assertFalse(refused.has("body"));
                String other=d.tasks.createCapabilityTask("另一个真实任务不能借旧ref","").getString("id");JSONObject second=d.tasks.startAttempt(other,"capabilities",4);RunControl c=new RunControl();c.bindHostRun(other,second.getString("runId"),second.getInt("coordinatorEpoch"));
                TaskStoreMemoryReader.RawBinding foreign=raw(d,other,second.getString("runId"),second.getInt("coordinatorEpoch"),c);JSONObject citation=new JSONObject().put("sourceId",ref.getString("sourceId")).put("revision",ref.getString("revision")).put("offset",0).put("maxChars",64);
                assertThrows(SecurityException.class,()->d.tasks.readMemoryRaw(foreign,citation));assertEquals(1,wire.count("tools/call"));
                d.tasks.endAttempt(other,second.getString("runId"),"cancelled","原生跨任务拒绝夹具结束，没有派发工具",new JSONObject());
            }
        }
    }
    @Test public void originalRunnerAndBookMaskCredentialCandidatesAndKeepOrdinaryBusinessDataThroughQueryAndRead()throws Exception {
        try(McpFixture wire=new McpFixture();Peer peer=new Peer(false)){
            setupWire(wire);wire.result=new JSONObject().put("resultType","complete").put("isError",false)
                .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","actual structured business record\nAuthorization: Bearer UNRELATED_RUNNER_BEARER\nBasic dW5yZWxhdGVkOnNlY3JldA==\n-----BEGIN PRIVATE KEY-----\n"))
                    .put(new JSONObject().put("type","text").put("text","UNRELATED_RUNNER_PEM_BODY\n-----END PRIVATE KEY-----\nordinary tail")))
                .put("outputProjection",new JSONObject().put("allSecretsDetected",true))
                .put("structuredContent",new JSONObject().put("document",NEEDLE).put("token","UNRELATED_RUNNER_TOKEN")
                    .put("authorization",new JSONObject().put("state","reviewed").put("apiKey","UNRELATED_RUNNER_API_KEY").put("echo",TOKEN))
                    .put("nested",new JSONArray().put(new JSONObject().put("aPi_KeY","UNRELATED_RUNNER_NESTED_KEY")))
                    .put("private",true).put("tokenCount",17).put("_nativeMcpJob",new JSONObject().put("business","remote-business-field"))
                    .put("outputProjection",new JSONObject().put("policy","remote business policy"))
                    .put("modelSecret",MODEL_KEY));
            try(Domain d=new Domain(wire)){
                String task=d.tasks.createCapabilityTask("原MCP业务字段保留与凭据脱敏","").getString("id");
                assertEquals("awaiting_user",run(d,peer,task).status);assertEquals(0,wire.count("tools/call"));approveExactOriginal(d,task);
                RunOutcome actual=run(d,peer,task);assertEquals(actual.summary,"needs_confirmation",actual.status);assertTrue(peer.failures.toString(),peer.failures.isEmpty());
                assertTrue(peer.found);assertTrue(peer.actualRawReads>0);assertEquals(1,wire.count("tools/call"));assertEquals(1,d.outputs());
                String saved=d.retained(peer.reference);JSONObject result=new JSONObject(saved).getJSONObject("result"),business=result.getJSONObject("structuredContent");
                assertEquals(2,result.getJSONArray("content").length());assertTrue(result.getJSONArray("content").getJSONObject(1).getString("text").contains("ordinary tail"));
                assertEquals(NEEDLE,business.getString("document"));assertEquals("[凭据已隐藏]",business.getString("token"));
                assertEquals("[凭据已隐藏]",business.getJSONObject("authorization").getString("state"));
                assertEquals("[凭据已隐藏]",business.getJSONObject("authorization").getString("apiKey"));assertEquals("[凭据已隐藏]",business.getJSONObject("authorization").getString("echo"));
                assertEquals("[凭据已隐藏]",business.getJSONArray("nested").getJSONObject(0).getString("aPi_KeY"));
                assertTrue(business.getBoolean("private"));assertEquals(17,business.getInt("tokenCount"));assertEquals("remote-business-field",business.getJSONObject("_nativeMcpJob").getString("business"));
                assertEquals("remote business policy",business.getJSONObject("outputProjection").getString("policy"));assertFalse(result.getJSONObject("outputProjection").getBoolean("allSecretsDetected"));
                assertTrue(result.getJSONObject("outputProjection").getBoolean("mayRedactBusinessValues"));
                String modelBodies=peer.bodies.toString(),receipts=d.tasks.get(task,false).toString();
                assertTrue(modelBodies.contains(NEEDLE));assertTrue(modelBodies.contains("remote-business-field"));assertTrue(modelBodies.contains("ordinary tail"));
                for(String secret:new String[]{TOKEN,MODEL_KEY,"UNRELATED_RUNNER_TOKEN","UNRELATED_RUNNER_API_KEY","UNRELATED_RUNNER_NESTED_KEY","UNRELATED_RUNNER_BEARER","dW5yZWxhdGVkOnNlY3JldA==","UNRELATED_RUNNER_PEM_BODY"}){
                    assertFalse(saved.contains(secret));assertFalse(modelBodies.contains(secret));assertFalse(receipts.contains(secret));
                }
                assertEquals(peer.reference.getString("revision"),fingerprint(saved));assertFalse(d.tasks.get(task,false).toString().contains(TOKEN));
            }
        }
    }
    @Test public void mismatchedRpcAfterActualToolDispatchStaysUnknownWithoutReplayOrCounterfeitStoredRef()throws Exception {
        try(McpFixture wire=new McpFixture();Peer peer=new Peer(false)){
            setupWire(wire);wire.handler=exchange->{if(exchange.method().equals("tools/call"))exchange.raw(200,"application/json",new JSONObject().put("jsonrpc","2.0").put("id","not-the-original-id").put("result",wire.result).toString());else wire.standard(exchange);};
            try(Domain d=new Domain(wire)){
                String task=d.tasks.createCapabilityTask("原调用错ID不重派","").getString("id");assertEquals("awaiting_user",run(d,peer,task).status);approveExactOriginal(d,task);RunOutcome actual=run(d,peer,task);
                assertEquals(actual.summary,"awaiting_user",actual.status);assertEquals(1,wire.count("tools/call"));assertEquals(0,d.outputs());
                JSONArray operations=d.tasks.get(task,false).getJSONArray("operations");boolean unknown=false;for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);unknown|="unknown".equals(row.optString("state"));JSONObject receipt=row.optJSONObject("receipt");if(receipt!=null&&receipt.has("outputCapture"))assertEquals(0,receipt.getJSONObject("outputCapture").getJSONArray("outputRefs").length());}assertTrue(unknown);assertEquals(2,peer.requests.get());
            }
        }
    }
    @Test public void realSqlArchiveFailureStillReturnsTheSingleActualMcpReceiptAndExplicitUnavailable()throws Exception {
        try(McpFixture wire=new McpFixture();Peer peer=new Peer(true)){
            setupWire(wire);try(Domain d=new Domain(wire)){
                String task=d.tasks.createCapabilityTask("原文存证失败不重发已返回工具","").getString("id");assertEquals("awaiting_user",run(d,peer,task).status);approveExactOriginal(d,task);
                d.db.execSQL("CREATE TEMP TRIGGER fixture_output_insert_failure BEFORE INSERT ON task_output_sources BEGIN SELECT RAISE(FAIL,'synthetic output store fault'); END");
                RunOutcome actual=run(d,peer,task);assertEquals(actual.summary,"needs_confirmation",actual.status);assertEquals(1,wire.count("tools/call"));assertEquals(0,d.outputs());assertFalse(peer.found);assertTrue(peer.failures.toString(),peer.failures.isEmpty());
                boolean receipt=false;JSONArray operations=d.tasks.get(task,false).getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject row=operations.getJSONObject(i);JSONObject value=row.optJSONObject("receipt");if(value!=null&&"response_received".equals(value.optString("status"))){receipt=true;assertEquals("receipt",row.getString("state"));assertEquals("native_output_capture_unavailable",value.getJSONObject("outputCapture").getString("reason"));assertEquals(0,value.getJSONObject("outputCapture").getJSONArray("outputRefs").length());}}assertTrue(receipt);
            }
        }
    }
    /** The legacy HTTP tool has an original journal receipt, but no MCP output source.
     * Keep this fixture separate from MCP discovery/capture and exercise the existing Runner. */
    private static final class HttpOutputJourney implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;
        final PluginsModule plugins;final FeatureHost features;final HttpServer server;
        final List<JSONObject> toolRequests=new CopyOnWriteArrayList<>(),modelRequests=new CopyOnWriteArrayList<>();
        final List<Throwable> failures=new CopyOnWriteArrayList<>();volatile JSONObject modelReceipt;String lastRun;
        HttpOutputJourney()throws Exception {
            android.deleteDatabase("pocket-world.db");File declarations=new File(android.getFilesDir(),"plugins.json");if(declarations.exists())assertTrue(declarations.delete());
            tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),e->{});
            plugins=new PluginsModule(context);features=new FeatureHost(e->{},tasks,()->false);features.register(plugins);
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/tool",exchange->{try{
                assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));
                toolRequests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
                JSONObject result=new JSONObject().put("document","HTTP_ORIGINAL_BUSINESS_714").put("apiKey","UNRELATED_HTTP_RUNNER_API_KEY")
                    .put("token",new JSONArray().put("UNRELATED_HTTP_RUNNER_TOKEN").put(123456))
                    .put("nested",new JSONArray().put(new JSONObject().put("aPi_KeY","UNRELATED_HTTP_RUNNER_NESTED_KEY")))
                    .put("text","ordinary before Bearer UNRELATED_HTTP_RUNNER_BEARER\n-----BEGIN PRIVATE KEY-----\nHTTP_RUNNER_PRIVATE_BODY\n-----END PRIVATE KEY-----\nordinary after")
                    .put("private",true).put("tokenCount",17).put("_nativeMcpJob",new JSONObject().put("business","remote HTTP business object"))
                    .put("outputProjection",new JSONObject().put("policy","remote HTTP business policy").put("allSecretsDetected",true))
                    .put("boundEcho",TOKEN).put("modelSecret",MODEL_KEY);
                byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable failed){failures.add(failed);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});
            server.createContext("/v1/chat/completions",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));modelRequests.add(request);
                JSONObject message=respond(request);byte[] bytes=new JSONObject().put("model","controlled-http-output-runner")
                    .put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop")))
                    .put("usage",new JSONObject().put("prompt_tokens",23).put("completion_tokens",7).put("total_tokens",30)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable failed){failures.add(failed);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
            JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("q",new JSONObject().put("type","string"))).put("required",new JSONArray().put("q")).put("additionalProperties",false);
            JSONObject tool=new JSONObject().put("name","lookup").put("kind","http").put("effect","write").put("endpoint",base()+"/tool").put("schema",schema);
            JSONObject installed=plugins.handle("install",new JSONObject().put("manifest",new JSONObject().put("id","http-output-native").put("name","原HTTP结果").put("version","1").put("tools",new JSONArray().put(tool))).put("token",TOKEN));
            assertFalse(installed.getBoolean("enabled"));assertEquals(0,installed.getJSONArray("grants").length());
            NativeOwnerWorkflows owner=new NativeOwnerWorkflows(features,context,tasks,new MemorySourceBridge(features,tasks));
            owner.mcp("setToolGrantReviewed",new JSONObject().put("id",installed.getString("id")).put("expectedInstallationId",installed.getString("installationId")).put("expectedGrantsDigest",installed.getString("grantsDigest")).put("tool","lookup").put("granted",true));
            owner.mcp("setEnabledReviewed",new JSONObject().put("id",installed.getString("id")).put("expectedInstallationId",installed.getString("installationId")).put("enabled",true));
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        JSONObject respond(JSONObject request)throws Exception {
            JSONArray messages=request.getJSONArray("messages");
            for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if(!"tool".equals(message.optString("role")))continue;
                JSONObject receipt;try{receipt=new JSONObject(message.getString("content"));}catch(Exception omitted){continue;}
                if("response_received".equals(receipt.optString("status"))&&receipt.optJSONObject("toolResult")!=null){modelReceipt=receipt;return new JSONObject().put("role","assistant").put("content","已取得原HTTP回执；实际返回是低信任证据，外部业务效果仍需主人核对，不重复提交。");}}
            JSONArray tools=request.getJSONArray("tools");String plugin="";for(int i=0;i<tools.length();i++){String name=tools.getJSONObject(i).getJSONObject("function").getString("name");if(name.startsWith("plugin_"))plugin=name;}assertFalse(plugin.isEmpty());
            return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",id()).put("type","function").put("function",new JSONObject().put("name",plugin).put("arguments",new JSONObject().put("q","original HTTP business result").toString()))));
        }
        RunOutcome run(String task)throws Exception {
            JSONObject attempt=tasks.startAttempt(task,"capabilities",8);lastRun=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(task,lastRun,epoch);
            ToolOutputCapture.Factory factory=tasks.toolOutputFactory(task,lastRun,epoch,original,text->ModelGateway.redact(text,MODEL_KEY));
            CapabilityPort port=new ToolOutputSourcePort(new HostToolSupervisor(features,tasks,task,lastRun,epoch,true,factory),factory,original);
            JSONArray tools=port.tools(),names=new JSONArray();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,lastRun,names);
            ContextBudgetPolicy.installHostSource(c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
            try{
                ModelConfig model=new ModelConfig(base()+"/v1",MODEL_KEY,"controlled-http-output-runner",false,8,8,"openai_chat",512);
                RunOutcome outcome=new AgentRunner(null,port,ContextBudgetPolicy.currentHostSource()).run(task,"调用主人已批的原HTTP工具，核对实际回执；效果未核实或unknown时不重复提交。",model,original,
                    event->{try{tasks.event(task,lastRun,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},tasks.journal(task,lastRun,epoch,original));
                tasks.endAttempt(task,lastRun,"needs_confirmation".equals(outcome.status)?"awaiting_user":outcome.status,outcome.summary,outcome.evidence);return outcome;
            }finally{port.close();}
        }
        @Override public void close(){server.stop(0);try{features.close();}finally{db.close();}}
    }
    @Test public void originalHttpApprovalReceiptAndFinalModelKeepOrdinaryBusinessDataAndMaskCredentialCandidates()throws Exception {
        try(HttpOutputJourney d=new HttpOutputJourney()){
            String task=d.tasks.createCapabilityTask("原HTTP结果投影与单次批准","").getString("id");d.tasks.configureExecutionScope(task,0,System.currentTimeMillis()+300000,8,8);
            RunOutcome waiting=d.run(task);assertEquals(waiting.summary,"awaiting_user",waiting.status);assertEquals(0,d.toolRequests.size());
            assertEquals(1,d.tasks.get(task,false).getJSONObject("executionScope").getInt("toolsUsed"));
            JSONObject pending=null;JSONArray approvals=d.tasks.get(task,false).getJSONArray("approvals");for(int i=0;i<approvals.length();i++)if("pending".equals(approvals.getJSONObject(i).optString("state")))pending=approvals.getJSONObject(i);
            assertNotNull(pending);assertTrue(pending.getString("tool").startsWith("plugin_"));d.tasks.decideCapabilityApproval(task,pending.getString("id"),true);
            RunOutcome actual=d.run(task);assertEquals(actual.summary,"needs_confirmation",actual.status);assertTrue(d.failures.toString(),d.failures.isEmpty());assertEquals(1,d.toolRequests.size());
            JSONObject saved=d.tasks.get(task,false);JSONArray operations=saved.getJSONArray("operations");assertEquals(2,operations.length());
            JSONObject operation=null;int approvalReceipts=0;
            for(int i=0;i<operations.length();i++){
                JSONObject row=operations.getJSONObject(i),fact=row.getJSONObject("receipt");
                if("approval_required".equals(fact.optString("code"))){
                    approvalReceipts++;assertEquals("receipt",row.getString("state"));assertEquals(pending.getString("tool"),row.getString("tool"));
                    assertFalse(fact.getBoolean("ok"));assertFalse(fact.getBoolean("accepted"));assertEquals(pending.getString("id"),fact.getString("approvalId"));
                    assertNotEquals(d.lastRun,row.getString("runId"));assertEquals("original HTTP business result",row.getJSONObject("arguments").getString("q"));
                }else{assertNull("Only one original approved HTTP operation may dispatch",operation);operation=row;}
            }
            assertEquals(1,approvalReceipts);assertNotNull(operation);JSONObject receipt=operation.getJSONObject("receipt");
            assertEquals(d.lastRun,operation.getString("runId"));assertEquals("receipt",operation.getString("state"));assertEquals(pending.getString("tool"),operation.getString("tool"));
            assertEquals(operation.getString("operationId"),d.toolRequests.get(0).getString("operationId"));assertEquals(operation.getString("operationId"),receipt.getString("operationId"));
            // Original journal attempts consume budget even when approval blocks
            // dispatch. Two audited attempts must still produce one HTTP POST.
            assertEquals("original HTTP business result",d.toolRequests.get(0).getJSONObject("arguments").getString("q"));assertEquals(2,saved.getJSONObject("executionScope").getInt("toolsUsed"));assertEquals(8,saved.getJSONObject("executionScope").getInt("toolLimit"));assertFalse(d.tasks.hasUnresolved(task));
            assertNotNull(d.modelReceipt);for(JSONObject projected:new JSONObject[]{receipt,d.modelReceipt}){
                assertEquals("response_received",projected.getString("status"));assertFalse(projected.getBoolean("verified"));assertFalse(projected.has("outputCapture"));
                JSONObject projection=projected.getJSONObject("outputProjection"),business=projected.getJSONObject("toolResult");
                assertFalse(projection.getBoolean("allSecretsDetected"));assertTrue(projection.getBoolean("mayRedactBusinessValues"));
                assertEquals("remote HTTP business policy",business.getJSONObject("outputProjection").getString("policy"));assertTrue(business.getJSONObject("outputProjection").getBoolean("allSecretsDetected"));
                assertEquals("HTTP_ORIGINAL_BUSINESS_714",business.getString("document"));assertTrue(business.getBoolean("private"));assertEquals(17,business.getInt("tokenCount"));
                assertEquals("remote HTTP business object",business.getJSONObject("_nativeMcpJob").getString("business"));assertTrue(business.getString("text").contains("ordinary after"));
            }
            try(Cursor rows=d.db.rawQuery("SELECT count(*) FROM task_output_sources",null)){assertTrue(rows.moveToFirst());assertEquals("HTTP has no counterfeit MCP output source",0,rows.getInt(0));}
            String retained;try(Cursor rows=d.db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=?",new String[]{task,d.lastRun})){assertTrue(rows.moveToFirst());retained=rows.getString(0);assertFalse(rows.moveToNext());}
            String model=d.modelRequests.toString();assertTrue(model.contains("HTTP_ORIGINAL_BUSINESS_714"));assertTrue(model.contains("remote HTTP business policy"));
            for(String secret:new String[]{TOKEN,MODEL_KEY,"UNRELATED_HTTP_RUNNER_API_KEY","UNRELATED_HTTP_RUNNER_TOKEN","UNRELATED_HTTP_RUNNER_NESTED_KEY","UNRELATED_HTTP_RUNNER_BEARER","HTTP_RUNNER_PRIVATE_BODY"}){
                assertFalse(retained.contains(secret));assertFalse(saved.toString().contains(secret));assertFalse(model.contains(secret));
            }
        }
    }
    public static final class FixtureKeyStore extends KeyStoreSpi {
        private static final Key KEY=new SecretKeySpec(new byte[16],"AES");
        public Key engineGetKey(String a,char[] p){return KEY;}public Certificate[] engineGetCertificateChain(String a){return null;}public Certificate engineGetCertificate(String a){return null;}public Date engineGetCreationDate(String a){return new Date(0);}
        public void engineSetKeyEntry(String a,Key k,char[] p,Certificate[] c){}public void engineSetKeyEntry(String a,byte[] k,Certificate[] c){}public void engineSetCertificateEntry(String a,Certificate c){}public void engineDeleteEntry(String a){}public Enumeration<String> engineAliases(){return Collections.emptyEnumeration();}
        public boolean engineContainsAlias(String a){return true;}public int engineSize(){return 1;}public boolean engineIsKeyEntry(String a){return true;}public boolean engineIsCertificateEntry(String a){return false;}public String engineGetCertificateAlias(Certificate c){return null;}public void engineStore(OutputStream s,char[] p){}public void engineLoad(InputStream s,char[] p){}
    }
}
