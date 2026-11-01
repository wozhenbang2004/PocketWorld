package dev.pocketengine.browser;

import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Real space/port with explicit authority/driver/source-interface fixtures; SQLite/Runner tested separately. */
public final class BrowserStructuredDeliveryTest {
    static final class Driver implements BrowserTaskSpace.Driver {
        final BrowserTaskSpaceTest.Driver base=new BrowserTaskSpaceTest.Driver();boolean wide;
        Driver()throws Exception { }
        public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception{return base.metadata(tab);}public void open(BrowserTaskSpace.Tab t,String u,BrowserTaskSpace.Gate g)throws Exception{base.open(t,u,g);}public void select(BrowserTaskSpace.Tab t,BrowserTaskSpace.Gate g)throws Exception{base.select(t,g);}
        public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
            if(!command.equals("observe")||!args.has("read")){base.execute(tab,command,args,gate,result);return;}
            gate.check();gate.entered();JSONObject raw=BrowserStructuredSourceTest.delivered("table").put("text","RAW_SECRET");raw.getJSONObject("read").getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(1).put("text","RAW_SECRET");
            if(wide){JSONArray cells=new JSONArray();for(int c=0;c<24;c++)cells.put(BrowserStructuredSourceTest.cell(c,"cell","x".repeat(1000)));raw.getJSONObject("read").getJSONArray("data").getJSONObject(0).put("cells",cells);}
            raw.put("url",metadata(tab).getString("url")).put("navigation",metadata(tab).getLong("navigation")).put("observationId",UUID.randomUUID().toString());JSONObject bound=tab.identity();for(java.util.Iterator<String> it=raw.keys();it.hasNext();){String k=it.next();bound.put(k,raw.get(k));}result.complete(BrowserSemanticRead.result(bound));
        }
        public JSONObject download(BrowserTaskSpace.Tab t,String u,String o,BrowserTaskSpace.Gate g)throws Exception{return base.download(t,u,o,g);}public void adoptOwner(BrowserTaskSpace.Tab t,BrowserTaskSpace.Gate g)throws Exception{base.adoptOwner(t,g);}public void releaseToOwner(BrowserTaskSpace.Tab t){base.releaseToOwner(t);}public boolean closeOwned(BrowserTaskSpace.Tab t)throws Exception{return base.closeOwned(t);}public void abort(BrowserTaskSpace.Tab t,CompletableFuture<JSONObject> p){base.abort(t,p);}
    }
    static final class F implements AutoCloseable {
        final BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue();final RunControl control=new RunControl();final ModelCapabilityAdapters.Binding binding;final BrowserTaskSpaceTest.HostAuthority authority;final BrowserTaskSpaceTest.Sources sources=new BrowserTaskSpaceTest.Sources();final Driver driver=new Driver();final BrowserTaskSpace space;final BrowserTaskCapabilityPort port;int delegated;boolean mutate;
        F()throws Exception {String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();control.bindHostRun(task,run,1);binding=new ModelCapabilityAdapters.Binding(task,run,1,control,true,"");authority=new BrowserTaskSpaceTest.HostAuthority(control);
            CapabilityPort existing=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String n){return false;}public boolean sideEffecting(String n){return false;}public void validate(String n,JSONObject a){throw new AssertionError("not an existing tool");}public JSONObject execute(String n,JSONObject a,RunControl c){throw new AssertionError("not an existing tool");}public void afterTool(String n,JSONObject a,JSONObject r,RunControl c)throws Exception{delegated++;if(mutate)r.getJSONObject("read").getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(1).put("text","LATE_HOOK_MUTATION");}};
            space=ui.call(()->new BrowserTaskSpace(binding,control,BrowserProfileCapabilityBindingTest.namedSession(),driver,ui,sources,null));port=new BrowserTaskCapabilityPort(space,authority,()->control.check(),(b,c,o,n,a,p)->j("approved",true,"approvalId",UUID.randomUUID().toString()),ui,existing);ui.call(()->{space.adoptOwnerNative(driver.base.ownerId,()->control.check());return null;});
        }
        JSONObject readArgs()throws Exception{return j("tabId",driver.base.ownerId,"read",j("mode","table"));}
        JSONObject invoke(String command,JSONObject args)throws Exception {String name="browser_task_"+command,op=UUID.randomUUID().toString();authority.prepared.put(op,j("tool",name,"args",new JSONObject(args.toString())));return port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",op),control);}
        JSONObject read()throws Exception{return invoke("observe",readArgs());}
        JSONObject retain(JSONObject read,String excerpt)throws Exception {JSONObject args=j("tabId",driver.base.ownerId,"observationId",read.getString("observationId"));if(excerpt!=null)args.put("excerpt",excerpt);return invoke("retain_source",args);}
        JSONObject safe(JSONObject raw)throws Exception {JSONObject safe=new JSONObject(raw.toString()).put("text","delivered_text");safe.getJSONObject("read").getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(1).put("text","DELIVERED_SAFE_VALUE");return safe;}
        public void close()throws Exception{try{ui.call(()->space.endNative("test_end"));}finally{ui.close();}}
    }
    @Test public void rawObserveAloneCannotEnableStructuredSourceRetention()throws Exception {try(F f=new F()){JSONObject raw=f.read();assertFalse(f.retain(raw,null).getBoolean("accepted"));assertEquals(0,f.sources.writes.get());f.port.afterTool("browser_task_observe",f.readArgs(),f.safe(raw),f.control);assertTrue(f.retain(raw,null).getBoolean("sourceRetained"));assertEquals(1,f.sources.writes.get());}}
    @Test public void actualSafeSnapshotSurvivesExactlyOneExistingHookWithoutRawOrLateMutation()throws Exception {try(F f=new F()){JSONObject raw=f.read(),safe=f.safe(raw);f.mutate=true;f.port.afterTool("browser_task_observe",f.readArgs(),safe,f.control);assertEquals(1,f.delegated);assertTrue(f.retain(raw,null).getBoolean("sourceRetained"));String saved=f.sources.lastSource.toString();assertTrue(saved.contains("DELIVERED_SAFE_VALUE"));assertFalse(saved.contains("RAW_SECRET"));assertFalse(saved.contains("LATE_HOOK_MUTATION"));assertTrue(f.sources.lastSource.has("structuredProjection"));}}
    @Test public void foreignTaskSessionDocumentAndOperationCannotRegisterARealObservation()throws Exception {
        for(String key:new String[]{"taskId","runId","browserSessionId","documentId","observationId","operationId"})try(F f=new F()){JSONObject raw=f.read(),safe=f.safe(raw);safe.put(key,UUID.randomUUID().toString());f.port.afterTool("browser_task_observe",f.readArgs(),safe,f.control);assertFalse(key,f.retain(raw,null).getBoolean("accepted"));assertEquals(0,f.sources.writes.get());}
    }
    @Test public void otherToolsAndReplacementControlDoNotMintDeliveryProof()throws Exception {for(boolean foreignControl:new boolean[]{false,true})try(F f=new F()){JSONObject raw=f.read();RunControl supplied=f.control;if(foreignControl){supplied=new RunControl();supplied.bindHostRun(f.binding.taskId,f.binding.runId,1);}f.port.afterTool(foreignControl?"browser_task_observe":"browser_task_wait",f.readArgs(),f.safe(raw),supplied);assertEquals(1,f.delegated);assertFalse(f.retain(raw,null).getBoolean("accepted"));assertEquals(0,f.sources.writes.get());}}
    @Test public void explicitExcerptOnlySavesActuallyDeliveredTextAndDoesNotImplicitlySaveWholeTable()throws Exception {try(F f=new F()){JSONObject raw=f.read();f.port.afterTool("browser_task_observe",f.readArgs(),f.safe(raw),f.control);assertFalse(f.retain(raw,"RAW_SECRET").getBoolean("accepted"));assertEquals(0,f.sources.writes.get());assertTrue(f.retain(raw,"delivered_text").getBoolean("sourceRetained"));assertEquals("delivered_text",f.sources.lastSource.getString("excerpt"));assertFalse(f.sources.lastSource.has("structuredProjection"));}}
    @Test public void oversizedWholeRowRefusesStructuredSaveButKeepsExplicitDeliveredTextExcerpt()throws Exception {try(F f=new F()){f.driver.wide=true;JSONObject raw=f.read();assertEquals("observed",raw.getString("status"));f.port.afterTool("browser_task_observe",f.readArgs(),f.safe(raw),f.control);assertFalse(f.retain(raw,null).getBoolean("accepted"));assertTrue(f.retain(raw,"delivered_text").getBoolean("sourceRetained"));assertFalse(f.sources.lastSource.has("structuredProjection"));}}
}
