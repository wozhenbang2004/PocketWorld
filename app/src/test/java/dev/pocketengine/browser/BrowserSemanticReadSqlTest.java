package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;

/** Original SQLite/grant/prepared/journal/Runner. DOM driver data is explicitly an interface fixture, not WebView. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserSemanticReadSqlTest {
    static final class Driver implements BrowserTaskSpace.Driver {
        final BrowserTaskSpaceTest.Driver original=new BrowserTaskSpaceTest.Driver();final String region=UUID.randomUUID()+":r1";final java.util.Map<String,String> contents=new java.util.HashMap<>(),regions=new java.util.HashMap<>();BrowserDownloads realDownloads;BrowserProfileRegistry.Session downloadProfile;JSONArray structuredRows;int reads,visualCopies,repairsOffered;boolean visualFixture,deltaFixture;final BrowserReadDelta deltas=new BrowserReadDelta();final java.util.Map<String,JSONObject> deltaRequests=new java.util.HashMap<>();String visualDocument=UUID.randomUUID().toString();
        Driver()throws Exception { }
        public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception{return original.metadata(tab);}
        public void open(BrowserTaskSpace.Tab tab,String url,BrowserTaskSpace.Gate gate)throws Exception{original.open(tab,url,gate);}
        public void select(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception{original.select(tab,gate);}
        public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
            if(!command.equals("observe")||!args.has("read")){original.execute(tab,command,args,gate,result);return;}select(tab,gate);gate.check();gate.entered();reads++;
            String mode=args.getJSONObject("read").getString("mode"),value=contents.getOrDefault(tab.tabId,"actual revenue fixture 42"),selectedRegion=regions.computeIfAbsent(tab.tabId,id->UUID.randomUUID()+":r1");JSONObject raw=raw(mode).put("text",value);raw.put("url",metadata(tab).getString("url")).put("navigation",metadata(tab).getLong("navigation")).put("observationId",UUID.randomUUID().toString());
            JSONObject read=raw.getJSONObject("read");if(mode.equals("outline"))read.put("data",new JSONArray().put(j("regionRef",selectedRegion,"role","table","name","Revenue","preview",value,"readOnlyEvidence",true)));
            else if(deltaFixture&&mode.equals("text")){JSONArray rows=new JSONArray();for(String row:value.split("\\|",-1))rows.put(row);read.put("data",rows);read.getJSONObject("facts").put("to",rows.length()).put("totalCaptured",rows.length()).put("returnedItems",rows.length());raw.put("documentId",visualDocument);deltaRequests.put(raw.getString("observationId"),new JSONObject(args.getJSONObject("read").toString()));}
            else read.put("data",new JSONArray().put(j("index",0,"cells",new JSONArray().put(j("column",0,"role","cell","text",value,"links",new JSONArray(),"rowSpan",1,"colSpan",1,"partial",false)))));
            if(structuredRows!=null&&mode.equals("table")){read.put("data",new JSONArray(structuredRows.toString()));read.getJSONObject("facts").put("to",structuredRows.length()).put("totalCaptured",structuredRows.length()).put("returnedItems",structuredRows.length());}
            read.getJSONObject("facts").put("regionRef",mode.equals("outline")?"":selectedRegion).put("textCharsScanned",value.length());JSONObject bound=tab.identity();for(java.util.Iterator<String> keys=raw.keys();keys.hasNext();){String key=keys.next();bound.put(key,raw.get(key));}result.complete(BrowserSemanticRead.result(bound));
        }
        // A controlled physical DOM fixture uses the actual production delta/KnownSet helper.
        public JSONObject presentedObservation(BrowserTaskSpace.Tab tab,JSONObject delivered)throws Exception {if(!deltaFixture)return new JSONObject(delivered.toString());JSONObject request=deltaRequests.remove(delivered.getString("observationId"));return deltas.offer(delivered,request,android.os.SystemClock.elapsedRealtime());}
        public JSONObject deliveredObservation(BrowserTaskSpace.Tab tab,JSONObject actual)throws Exception {return deltaFixture?deltas.delivered(actual,android.os.SystemClock.elapsedRealtime()):new JSONObject(actual.toString());}
        public java.util.List<BrowserReadDelta.FullResync> readContextRepairs(BrowserTaskSpace.Tab tab,JSONArray messages)throws Exception {java.util.List<BrowserReadDelta.FullResync> found=deltaFixture?deltas.repairs(messages,android.os.SystemClock.elapsedRealtime(),tab.tabId):java.util.List.of();repairsOffered+=found.size();return found;}
        public void beforeModelRequest(BrowserTaskSpace.Tab tab,String id,JSONArray messages)throws Exception {if(deltaFixture)deltas.beforeModelRequest(id,messages);}
        public void afterModelResponse(BrowserTaskSpace.Tab tab,String id){if(deltaFixture)deltas.afterModelResponse(id);}
        public void modelRequestAbandoned(BrowserTaskSpace.Tab tab,String id){if(deltaFixture)deltas.abandoned(id);}
        public boolean visualAvailable(){return visualFixture;}
        public JSONObject visualPin(BrowserTaskSpace.Tab tab,JSONObject args)throws Exception {return j("documentId",visualDocument,"frameId","main","viewport",BrowserVisualCapture.viewport(64,64,0,0,1d,args.optJSONObject("crop")),"windowX",0,"windowY",0);}
        public void visualCapture(BrowserTaskSpace.Tab tab,JSONObject args,JSONObject pin,BrowserTaskSpace.Gate gate,CompletableFuture<BrowserVisualCapture.Frame> result)throws Exception {
            gate.check();tab.check();assertEquals(visualPin(tab,args).toString(),pin.toString());gate.entered();visualCopies++;JSONObject nativeId=tab.identity();String document=visualDocument;long navigation=metadata(tab).getLong("navigation");dev.pocketengine.core.NativeToolObservation.Viewport viewport=BrowserVisualCapture.viewport(pin.getJSONObject("viewport"));android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(viewport.imageWidthPx(),viewport.imageHeightPx(),android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.GREEN);
            result.complete(new BrowserVisualCapture.Frame(new dev.pocketengine.core.NativeToolObservation.Identity(nativeId.getString("taskId"),nativeId.getString("runId"),nativeId.getInt("epoch"),nativeId.getString("profileId"),nativeId.getLong("profileRevision"),nativeId.getString("profileIsolation"),nativeId.getString("browserSessionId"),nativeId.getString("browserSpaceId"),tab.tabId,tab.generation,navigation,document,UUID.randomUUID().toString(),System.currentTimeMillis(),viewport),bitmap,()->{tab.check();if(!document.equals(visualDocument)||navigation!=metadata(tab).getLong("navigation"))throw new SecurityException("controlled physical visual source changed");}));
        }
        public JSONObject download(BrowserTaskSpace.Tab tab,String url,String operation,BrowserTaskSpace.Gate gate)throws Exception{
            if(realDownloads==null)return original.download(tab,url,operation,gate);gate.check();tab.check();gate.entered();long navigation=metadata(tab).getLong("navigation");BrowserTransferFence fence=new BrowserTransferFence(){public boolean current(){try{return tab.current()&&original.owned.get(tab.tabId)==tab&&metadata(tab).getLong("navigation")==navigation;}catch(Exception stale){return false;}}public void check()throws Exception{tab.check();gate.liveCheck();if(!current())throw new SecurityException("fixture original page changed");}public AutoCloseable watch(Runnable close){return tab.watch(close);}};return realDownloads.start(url,"Explicit local fixture",target->null,"规格.txt","text/plain",tab.identity().put("operationId",operation),downloadProfile,fence,null);
        }
        public JSONObject downloadStatus(String id,BrowserTaskSpace.Gate gate)throws Exception{gate.check();return realDownloads==null?original.downloadStatus(id,gate):realDownloads.status(id,downloadProfile.profileId);}
        public void adoptOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception{original.adoptOwner(tab,gate);}
        public JSONObject ownerMetadata(String tab)throws Exception{return original.ownerMetadata(tab);}
        public void releaseToOwner(BrowserTaskSpace.Tab tab){original.releaseToOwner(tab);}
        public boolean closeOwned(BrowserTaskSpace.Tab tab){return original.closeOwned(tab);}
        public void abort(BrowserTaskSpace.Tab tab,CompletableFuture<JSONObject> op){original.abort(tab,op);}
    }
    static final class F implements AutoCloseable {
        final BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture(true);final BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue();final Driver driver=new Driver();final BrowserTaskApprovalPort approvals;final BrowserTaskSpace space;final BrowserTaskCapabilityPort port;final String tab;
        F()throws Exception {this(false,null,false);}
        F(boolean captureOutput,BrowserReadRecipeCatalog recipes)throws Exception {this(captureOutput,recipes,false);}
        F(boolean captureOutput,BrowserReadRecipeCatalog recipes,boolean visualFixture)throws Exception {
            driver.visualFixture=visualFixture;
            f.tasks.authorizeTools(f.binding.taskId,f.binding.runId,BrowserTaskCapabilityPort.taskToolNames(true));
            ModelCapabilityAdapters.ResourceCheck resource=()->{assertFalse("native callback under TaskStore monitor",Thread.holdsLock(f.tasks));if(!f.resource.get())throw new SecurityException("original browser reservation revoked");};
            dev.pocketengine.features.memory.ToolOutputCapture.Factory capture=captureOutput?f.tasks.toolOutputFactory(f.binding.taskId,f.binding.runId,f.binding.epoch,f.control,text->text):null;
            BrowserTaskSourcePort source=new BrowserTaskSourcePort(f.tasks,f.binding,f.control,f.authority,resource,f.events::add,capture);approvals=new BrowserTaskApprovalPort(f.tasks,f.binding,f.control,f.authority,resource,f.events::add);
            space=ui.call(()->new BrowserTaskSpace(f.binding,f.control,BrowserProfileCapabilityBindingTest.namedSession(),driver,ui,source,new BrowserTaskSpace.NativeEvents(){public void takenOver(ModelCapabilityAdapters.Binding b,dev.pocketengine.core.RunControl c,String space,String tab){}public void ended(ModelCapabilityAdapters.Binding b,dev.pocketengine.core.RunControl c,String space,JSONObject receipt){}public BrowserReadRecipeCatalog readRecipes(){return recipes;}public boolean pageManifest(ModelCapabilityAdapters.Binding b,dev.pocketengine.core.RunControl c,JSONObject manifest){try{f.tasks.event(b.taskId,b.runId,b.epoch,new JSONObject().put("type",BrowserResearchPages.EVENT).put("manifest",new JSONObject(manifest.toString())).put("manifestFingerprint",TaskStore.capabilityFingerprint(manifest)));return true;}catch(Exception unavailable){throw new IllegalStateException(unavailable);}}}));port=new BrowserTaskCapabilityPort(space,f.authority,resource,approvals,ui,null);tab=driver.original.ownerId;
            ui.call(()->{space.adoptOwnerNative(tab,()->f.control.check());return null;});JSONObject identity=ui.call(()->space.identity().put("pauseRevision",f.control.pauseRevision()));f.tasks.grantBrowserReadScope(f.binding.taskId,f.binding.runId,f.binding.epoch,f.control,identity,BrowserReadScope.forOwner(true,true,""));
        }
        JSONObject args(String mode)throws Exception{return j("tabId",tab,"read",j("mode",mode));}
        JSONObject invoke(String command,JSONObject args)throws Exception {String name="browser_task_"+command,operation=f.journal.beginOperation(name,args);JSONObject receipt=port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",operation),f.control);f.journal.finishOperation(operation,receipt);if(name.equals("browser_task_observe"))port.afterTool(name,args,receipt,f.control);return receipt;}
        public void close()throws Exception {approvals.close();try{ui.call(()->{space.endNative("fixture_end");return null;});}finally{f.close();ui.close();}}
    }
    @Test public void scopedReadUsesOriginalPermitAndSemanticSourceSurvivesSQLiteReopenWithoutReplacingLegacyRefs()throws Exception {try(F x=new F()){
        JSONObject old=x.invoke("observe",j("tabId",x.tab));JSONObject read=x.invoke("observe",x.args("text"));assertTrue(read.toString(),read.getBoolean("ok"));assertEquals("native_read_scope",read.getJSONObject("readAuthorization").getString("mode"));
        JSONObject pin=x.ui.call(()->x.space.pin(j("tabId",x.tab,"observationId",old.getString("observationId"),"ref","n0")));assertEquals("n0",pin.getJSONObject("targetPreview").getString("ref"));
        JSONObject saved=x.invoke("retain_source",j("tabId",x.tab,"observationId",read.getString("observationId"),"excerpt",read.getString("text")));assertTrue(saved.toString(),saved.getBoolean("sourceRetained"));
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONObject source=BrowserTaskSourcePort.sourcesForOwner(reopened,x.f.binding.taskId).getJSONObject(0);assertEquals(BrowserSemanticRead.KIND,source.getString("kind"));assertEquals("rendered_semantic_dom",source.getString("acquisitionMethod"));assertEquals(read.getString("text"),source.getString("excerpt"));assertEquals(read.getString("documentId"),source.getString("documentId"));assertEquals("page_world_fallback",source.getJSONObject("frameCoverage").getString("execution"));assertFalse(source.getJSONObject("semantic").getBoolean("wholePageComplete"));assertEquals(read.getJSONObject("read").getString("projectionSha256"),source.getJSONObject("semantic").getString("projectionSha256"));}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
        JSONArray operations=x.f.tasks.get(x.f.binding.taskId,false).getJSONArray("operations");assertEquals(3,operations.length());for(int i=0;i<operations.length();i++)assertEquals("receipt",operations.getJSONObject(i).getString("state"));
    }}
    @Test public void semanticEvidenceCannotBecomeActionRefAndNativeSourceValidatorRejectsCoverageUpgrades()throws Exception {try(F x=new F()){
        JSONObject read=x.invoke("observe",x.args("text"));String observation=read.getString("observationId");
        assertThrows(Exception.class,()->x.ui.call(()->x.space.pin(j("tabId",x.tab,"observationId",observation,"ref","n0"))));
        assertThrows(Exception.class,()->x.ui.call(()->x.space.dispatch("scroll",j("tabId",x.tab,"observationId",observation,"dy",200),UUID.randomUUID().toString(),()->x.f.control.check())));assertEquals(0,x.driver.original.mutableDispatches);
        JSONObject args=j("tabId",x.tab,"observationId",observation,"excerpt",read.getString("text"));String operation=x.f.journal.beginOperation("browser_task_retain_source",args);JSONObject source=x.ui.call(()->x.space.sourceFor(args));String id=source.getString("sourceId");
        JSONObject forged=new JSONObject(source.toString());forged.getJSONObject("semantic").put("wholePageComplete",true);assertThrows(SecurityException.class,()->x.f.sources.retain(x.f.binding,x.f.control,operation,id,forged));
        JSONObject frame=new JSONObject(source.toString());frame.getJSONObject("frameCoverage").put("frameTreeComplete",true);assertThrows(SecurityException.class,()->x.f.sources.retain(x.f.binding,x.f.control,operation,id,frame));
        JSONObject wrongKind=new JSONObject(source.toString()).put("kind","browser_observation");assertThrows(SecurityException.class,()->x.f.sources.retain(x.f.binding,x.f.control,operation,id,wrongKind));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());x.f.journal.finishOperation(operation,j("accepted",false,"verified",false));
    }}
    @Test public void originalRunnerFindsSpecifiedTableExtractsThenRetainsOriginalSourceWithoutAnotherModelLoop()throws Exception {try(F x=new F()){
        AtomicReference<Throwable> failed=new AtomicReference<>();JSONObject[] last={null};int[] step={0};HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));JSONArray messages=request.getJSONArray("messages");JSONObject receipt=null;for(int i=messages.length()-1;i>=0;i--)if(messages.getJSONObject(i).optString("role").equals("tool")){receipt=new JSONObject(messages.getJSONObject(i).getString("content"));break;}if(receipt!=null)assertTrue(receipt.toString(),receipt.optBoolean("ok"));JSONObject answer,args;String tool;
            switch(step[0]++){
                case 0:tool="browser_task_observe";args=x.args("outline");args.getJSONObject("read").put("query","Revenue");break;
                case 1:tool="browser_task_observe";args=x.args("table");args.getJSONObject("read").put("regionRef",receipt.getJSONObject("read").getJSONArray("data").getJSONObject(0).getString("regionRef"));break;
                case 2:last[0]=receipt;tool="browser_task_retain_source";args=j("tabId",x.tab,"observationId",receipt.getString("observationId"),"excerpt",receipt.getString("text"));break;
                default:assertTrue(receipt.getBoolean("sourceRetained"));answer=j("role","assistant","content","在指定表中读取到42并保存原来源；未声称完整网页或业务完成。");byte[] body=j("choices",new JSONArray().put(j("message",answer,"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);return;
            }
            answer=j("role","assistant","content",JSONObject.NULL,"tool_calls",new JSONArray().put(j("id",UUID.randomUUID().toString(),"type","function","function",j("name",tool,"arguments",args.toString()))));byte[] body=j("choices",new JSONArray().put(j("message",answer,"finish_reason","tool_calls"))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Throwable failure){failed.compareAndSet(null,failure);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});server.start();ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-local-only","semantic-read-original-runner",false,30,8);
            RunOutcome outcome=worker.submit(()->new AgentRunner(null,x.port).run(x.f.binding.taskId,"读取原任务页Revenue表并保留真实来源",config,x.f.control,event->{try{x.f.tasks.event(x.f.binding.taskId,x.f.binding.runId,x.f.binding.epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},x.f.journal)).get(25,TimeUnit.SECONDS);
            if(failed.get()!=null)throw new AssertionError("local model fixture",failed.get());assertTrue(outcome.summary,outcome.status.matches("completed|needs_confirmation"));assertEquals(4,step[0]);assertEquals(2,x.driver.reads);assertEquals(0,x.driver.original.mutableDispatches);assertNotNull(last[0]);JSONObject task=x.f.tasks.get(x.f.binding.taskId,false);assertEquals(3,task.getJSONArray("operations").length());assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());assertTrue(x.f.journal.restore().getJSONArray("messages").toString().contains("projectionSha256"));assertEquals(x.f.binding.runId,task.getString("runId"));
        }finally{worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);server.stop(0);}
    }}
}
