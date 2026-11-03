package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the actual capability port and task space; the fake driver is explicitly not Android input. */
public final class BrowserNativeInputTaskPortTest {
    private static JSONObject obj(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static final class Driver implements BrowserTaskSpace.Driver {
        final Map<String,BrowserTaskSpace.Tab> tabs=new LinkedHashMap<>();final Map<String,JSONObject> pages=new LinkedHashMap<>();JSONObject viewport=obj("documentId","document-one","frameId","main","width",400,"height",600,"scrollX",0,"scrollY",0,"scale",1);JSONObject observedFrame;JSONObject lastArguments;boolean unknown;int inputs;
        Driver()throws Exception { }
        public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception {if(tabs.get(tab.tabId)!=tab)throw new SecurityException("wrong tab");return new JSONObject(pages.get(tab.tabId).toString());}
        public JSONObject inputPin(BrowserTaskSpace.Tab tab)throws Exception {metadata(tab);return new JSONObject(viewport.toString());}
        public void open(BrowserTaskSpace.Tab tab,String url,BrowserTaskSpace.Gate gate)throws Exception {gate.check();gate.entered();tabs.put(tab.tabId,tab);pages.put(tab.tabId,obj("url",url,"title","Page","documentId","document-one","navigation",1,"loading",false,"navigationHistory",obj("index",0,"size",1,"current",obj("url",url),"back",JSONObject.NULL,"forward",JSONObject.NULL)));}
        public void select(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {gate.check();metadata(tab);}
        public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
            gate.check();metadata(tab);lastArguments=new JSONObject(args.toString());
            if(command.equals("observe")){JSONObject page=metadata(tab);JSONObject observed=tab.identity().put("url",page.getString("url")).put("title","page").put("navigation",1).put("observationId",UUID.randomUUID().toString()).put("text","source and target").put("_nativeInputViewport",inputPin(tab)).put("elements",new JSONArray().put(obj("ref","n0","nodeKey","k1","tag","CANVAS","type","","text","source")).put(obj("ref","n1","nodeKey","k2","tag","BUTTON","type","button","text","destination")));if(observedFrame!=null)observed.put("actionFrame",observedFrame);result.complete(observed);return;}
            if(command.equals("native_input")){gate.entered();inputs++;result.complete(unknown?BrowserNativeInput.unknown("fixture_lost_after_native_entry"):obj("accepted",true,"status","native_input_read_back","verified",false));return;}
            if(command.matches("back|forward|reload")){result.complete(obj("accepted",true,"noOp",true,"status","no_change","verified",false));return;}throw new AssertionError("unexpected command "+command);
        }
        public JSONObject readFramePin(BrowserTaskSpace.Tab tab,String ref)throws Exception {return new JSONObject(observedFrame.toString());}
        public JSONObject download(BrowserTaskSpace.Tab tab,String url,String operation,BrowserTaskSpace.Gate gate){throw new AssertionError();}
        public void adoptOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate){throw new AssertionError();}
        public void releaseToOwner(BrowserTaskSpace.Tab tab){tabs.remove(tab.tabId);}
        public boolean closeOwned(BrowserTaskSpace.Tab tab){pages.remove(tab.tabId);return tabs.remove(tab.tabId)!=null;}
        public void abort(BrowserTaskSpace.Tab tab,CompletableFuture<JSONObject> result){if(result!=null)result.cancel(false);}
    }
    static final class Harness implements AutoCloseable {
        final BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue();final RunControl control=new RunControl();final ModelCapabilityAdapters.Binding binding;final BrowserTaskSpaceTest.HostAuthority authority=new BrowserTaskSpaceTest.HostAuthority(control);final Driver driver=new Driver();final BrowserTaskSpace space;final BrowserTaskCapabilityPort port;final AtomicInteger approvals=new AtomicInteger();JSONObject lastPin;
        Harness()throws Exception {String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();control.bindHostRun(task,run,1);binding=new ModelCapabilityAdapters.Binding(task,run,1,control,true,"");BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(Files.createTempDirectory("native-input-task").toFile(),"profiles.json"));space=new BrowserTaskSpace(binding,control,profiles.openSession(true),driver,ui,null,new BrowserTaskSpace.NativeEvents(){public void takenOver(ModelCapabilityAdapters.Binding b,RunControl c,String s,String t){c.pause();}public void ended(ModelCapabilityAdapters.Binding b,RunControl c,String s,JSONObject receipt){}});port=new BrowserTaskCapabilityPort(space,authority,()->{},(b,c,id,name,args,pin)->{assertSame(control,c);authority.requirePrepared(id,name,args);lastPin=new JSONObject(pin.toString());approvals.incrementAndGet();if(authority.duringApproval!=null){Runnable hook=authority.duringApproval;authority.duringApproval=null;hook.run();}return obj("approved",true,"approvalId",UUID.randomUUID().toString());},ui,null);}
        JSONObject invoke(String command,JSONObject args)throws Exception {String name="browser_task_"+command,id=UUID.randomUUID().toString();authority.prepared.put(id,obj("tool",name,"args",new JSONObject(args.toString())));return execute(name,args,id);}
        JSONObject execute(String name,JSONObject args,String id)throws Exception {return port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",id),control);}
        JSONObject observe()throws Exception {String tab=invoke("open",obj("url","https://example.test/one")).getString("tabId");return invoke("observe",obj("tabId",tab));}
        JSONObject input(JSONObject observed,String action)throws Exception {JSONObject args=obj("tabId",observed.getString("tabId"),"observationId",observed.getString("observationId"),"action",action,"ref","n0");if(action.equals("drag"))args.put("toRef","n1");return args;}
        public void close()throws Exception {try{ui.call(()->space.endNative("test_end"));}finally{ui.close();}}
    }
    @Test public void actualToolSchemasAndGrantNamesExposeFiniteInputAndHistory()throws Exception {try(Harness h=new Harness()){
        String names=BrowserTaskCapabilityPort.taskToolNames(false).toString();for(String command:new String[]{"native_input","site_mode","resize","back","forward","reload"}){assertTrue(h.port.supports("browser_task_"+command));assertTrue(names.contains("browser_task_"+command));}
        JSONObject observed=h.observe();JSONObject args=h.input(observed,"tap");assertFalse(observed.toString().contains("_nativeInputViewport"));assertThrows(Exception.class,()->h.port.validate("browser_task_native_input",new JSONObject(args.toString()).put("_nativeInputViewport",new JSONObject())));JSONObject incomplete=h.input(observed,"drag");incomplete.remove("toRef");assertThrows(Exception.class,()->h.port.validate("browser_task_native_input",incomplete));
    }}
    @Test public void nativeInputSettingsResizeAndHistoryNeverInheritReadOrRoutineActionScope()throws Exception {
        JSONObject policy=BrowserActionScope.forOwner(true,"https://example.test",100,true,true,true,true,"按钮").review();JSONObject pin=obj("tabId","tab","pageUrl","https://example.test/one","targetPreview",obj("tag","BUTTON","type","button","text","按钮"));JSONObject args=obj("tabId","tab","action","tap","ref","n0");
        for(String command:new String[]{"native_input","site_mode","resize","back","forward","reload"}){String tool="browser_task_"+command;assertFalse(tool,BrowserActionScope.eligible(policy,tool,args,pin));assertFalse(tool,BrowserReadScope.readTool(tool));assertFalse(tool,BrowserReadScope.eligible(new JSONObject(),tool,args,pin));}
    }
    @Test public void approvedNativeInputCarriesBothExactTargetsAndConsumesOldObservation()throws Exception {try(Harness h=new Harness()){
        JSONObject observed=h.observe(),args=h.input(observed,"drag"),receipt=h.invoke("native_input",args);assertTrue(receipt.toString(),receipt.getBoolean("ok"));assertEquals(1,h.driver.inputs);assertEquals("k1",h.driver.lastArguments.getJSONObject("_nativeTargetPreview").getString("nodeKey"));assertEquals("k2",h.driver.lastArguments.getJSONObject("_nativeToTargetPreview").getString("nodeKey"));assertEquals(400,h.lastPin.getJSONObject("nativeInputViewport").getInt("width"));assertEquals("android_webview_input",receipt.getJSONObject("actionReceipt").getString("mechanism"));JSONObject stale=h.invoke("native_input",args);assertFalse(stale.getBoolean("accepted"));assertEquals(1,h.driver.inputs);
    }}
    @Test public void viewportChangedWhileExactApprovalWaitsCannotReachInputDriver()throws Exception {try(Harness h=new Harness()){
        JSONObject args=h.input(h.observe(),"tap");h.authority.duringApproval=()->{try{h.ui.call(()->h.driver.viewport.put("width",399));}catch(Exception e){throw new RuntimeException(e);}};JSONObject stopped=h.invoke("native_input",args);assertFalse(stopped.toString(),stopped.getBoolean("accepted"));assertEquals(0,h.driver.inputs);
    }}
    @Test public void foreignFrameOrUnpresentedDragDestinationNeverGrantsCoordinates()throws Exception {try(Harness h=new Harness()){
        JSONObject observed=h.observe();JSONObject absent=h.input(observed,"drag").put("toRef","n249");assertFalse(h.invoke("native_input",absent).getBoolean("accepted"));assertEquals(0,h.driver.inputs);
        h.driver.observedFrame=obj("frameId",UUID.randomUUID().toString(),"documentId","child","sourceOrigin","https://child.test","topDocumentId","document-one");JSONObject frame=h.invoke("observe",obj("tabId",observed.getString("tabId")));assertFalse(h.invoke("native_input",h.input(frame,"tap")).getBoolean("accepted"));assertEquals(0,h.driver.inputs);
    }}
    @Test public void ownerTakeoverDuringApprovalDiscardsPreparedInput()throws Exception {try(Harness h=new Harness()){
        JSONObject observed=h.observe(),args=h.input(observed,"tap");h.authority.duringApproval=()->{try{h.ui.call(()->{h.space.takeOverNative(observed.getString("tabId"));return null;});}catch(Exception e){throw new RuntimeException(e);}};JSONObject stopped=h.invoke("native_input",args);assertEquals("owner_control",stopped.getString("status"));assertFalse(stopped.getBoolean("accepted"));assertEquals(0,h.driver.inputs);
    }}
    @Test public void duplicateOriginalOperationAndUnknownReceiptNeverReplayInput()throws Exception {try(Harness h=new Harness()){
        JSONObject args=h.input(h.observe(),"tap");String id=UUID.randomUUID().toString(),name="browser_task_native_input";h.authority.prepared.put(id,obj("tool",name,"args",args));h.driver.unknown=true;assertEquals("unknown",h.execute(name,args,id).getString("status"));assertEquals("unknown",h.execute(name,args,id).getString("status"));assertEquals("unknown",h.invoke("native_input",args).getString("status"));assertEquals(1,h.driver.inputs);
    }}
    @Test public void noHistoryIsARealNoOpWithExactHistoryInOriginalApproval()throws Exception {try(Harness h=new Harness()){
        JSONObject observed=h.observe(),receipt=h.invoke("back",obj("tabId",observed.getString("tabId")));assertEquals("no_change",receipt.getString("status"));assertEquals("not_needed",receipt.getJSONObject("actionReceipt").getJSONObject("dispatch").getString("state"));assertEquals(h.lastPin.getJSONObject("navigationHistory").toString(),h.driver.lastArguments.getJSONObject("_nativeNavigationHistory").toString());assertEquals(0,h.driver.inputs);
    }}
}
