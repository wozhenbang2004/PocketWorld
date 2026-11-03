package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original Port/TaskSpace/RunControl with a transcript-backed Driver fixture, not an Android CDP test. */
public final class BrowserNativeNetworkPortTest {
    private static JSONObject obj(Object... pairs)throws Exception{return BrowserNativeNetworkRecordsTest.obj(pairs);}
    private static final class Driver implements BrowserTaskSpace.Driver {
        final BrowserTaskSpaceTest.Driver pages=new BrowserTaskSpaceTest.Driver();BrowserNetworkRecords records;String document;long navigation;int bodies;
        Driver()throws Exception { }
        public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception{return pages.metadata(tab);}
        public void open(BrowserTaskSpace.Tab tab,String url,BrowserTaskSpace.Gate gate)throws Exception{pages.open(tab,url,gate);pages.pages.get(tab.tabId).put("documentId",UUID.randomUUID().toString());}
        public void select(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception{pages.select(tab,gate);}
        private void current(BrowserTaskSpace.Tab tab,String capture)throws Exception{tab.check();JSONObject page=metadata(tab);if(records==null||!records.active()||!records.captureId.equals(capture)||!document.equals(page.getString("documentId"))||navigation!=page.getLong("navigation"))throw new BrowserTaskSpace.StalePage("fixture original capture replaced");}
        public JSONObject networkCapturePin(BrowserTaskSpace.Tab tab,String capture)throws Exception{current(tab,capture);return tab.identity().put("captureId",capture).put("documentId",document).put("navigation",navigation);}
        public JSONObject networkBodyPin(BrowserTaskSpace.Tab tab,JSONObject args)throws Exception{current(tab,args.getString("captureId"));return records.bodyPin(args);}
        public void deliveredNetwork(BrowserTaskSpace.Tab tab,JSONObject actual)throws Exception{current(tab,actual.getString("captureId"));records.delivered(actual);}
        public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception{
            if(!command.equals("network")&&!command.equals("network_body")){pages.execute(tab,command,args,gate,result);return;}
            select(tab,gate);gate.check();gate.entered();
            if(command.equals("network")&&args.getString("action").equals("start")){
                JSONObject page=metadata(tab);document=page.getString("documentId");navigation=page.getLong("navigation");records=new BrowserNetworkRecords(tab.identity().put("documentId",document).put("navigation",navigation),"frame-a","loader-a",0);BrowserNetworkRecords original=records;tab.watch(()->original.invalidate("fixture_original_task_stopped"));BrowserNativeNetworkRecordsTest.complete(records,"original-response");result.complete(records.snapshot(5));return;
            }
            current(tab,args.getString("captureId"));
            if(command.equals("network_body")){BrowserNetworkRecords.BodyPermit permit=records.reserveBody(args);bodies++;result.complete(records.body(permit,obj("body","{\"rows\":[{\"value\":7}],\"token\":\"PRIVATE_TOKEN_CANARY\"}")));}
            else if(args.getString("action").equals("read"))result.complete(records.snapshot(6));else{records.invalidate("stopped");result.complete(obj("accepted",true,"status","stopped"));}
        }
        public JSONObject download(BrowserTaskSpace.Tab tab,String url,String operation,BrowserTaskSpace.Gate gate)throws Exception{return pages.download(tab,url,operation,gate);}
        public void adoptOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception{pages.adoptOwner(tab,gate);}
        public void releaseToOwner(BrowserTaskSpace.Tab tab){if(records!=null)records.invalidate("owner");pages.releaseToOwner(tab);}
        public boolean closeOwned(BrowserTaskSpace.Tab tab){if(records!=null)records.invalidate("closed");return pages.closeOwned(tab);}
        public void abort(BrowserTaskSpace.Tab tab,CompletableFuture<JSONObject> operation){if(records!=null)records.invalidate("aborted");pages.abort(tab,operation);}
    }
    private static final class Harness implements AutoCloseable {
        final BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue();final RunControl control=new RunControl();final BrowserTaskSpaceTest.HostAuthority authority=new BrowserTaskSpaceTest.HostAuthority(control);final Driver driver=new Driver();final BrowserTaskSpace space;final BrowserTaskCapabilityPort port;
        boolean approveBody=true;int approvals;JSONObject reviewed;Runnable duringBodyApproval;
        Harness()throws Exception {
            String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();control.bindHostRun(task,run,1);ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(task,run,1,control,true,"");BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(Files.createTempDirectory("native-network-port-").toFile(),"profiles.json"));var profile=profiles.openSession(true);
            space=ui.call(()->new BrowserTaskSpace(binding,control,profile,driver,ui,null,null));
            port=new BrowserTaskCapabilityPort(space,authority,()->{},(actual,supplied,op,name,args,pin)->{assertSame(binding,actual);assertSame(control,supplied);authority.requirePrepared(op,name,args);approvals++;reviewed=new JSONObject(pin.toString());if(name.equals(BrowserNativeNetwork.BODY_TOOL)&&duringBodyApproval!=null){Runnable hook=duringBodyApproval;duringBodyApproval=null;hook.run();}return obj("approved",!name.equals(BrowserNativeNetwork.BODY_TOOL)||approveBody,"approvalId",UUID.randomUUID().toString());},ui,null);
        }
        JSONObject invoke(String command,JSONObject args)throws Exception {String name="browser_task_"+command,id=UUID.randomUUID().toString();authority.prepared.put(id,obj("tool",name,"args",new JSONObject(args.toString())));return port.execute(name,new JSONObject(args.toString()).put("_hostOperationId",id),control);}
        String open()throws Exception{return invoke("open",obj("url","https://example.test/data")).getString("tabId");}
        JSONObject start(String tab)throws Exception{return invoke("network",obj("tabId",tab,"backend","native","action","start"));}
        JSONObject bodyArgs(String tab,JSONObject snapshot)throws Exception{return obj("tabId",tab,"captureId",snapshot.getString("captureId"),"requestRef",snapshot.getJSONArray("requests").getJSONObject(0).getString("requestRef"),"maxBytes",256);}
        void deliverStart(String tab,JSONObject snapshot)throws Exception{port.afterTool("browser_task_network",obj("tabId",tab,"backend","native","action","start"),snapshot,control);}
        public void close()throws Exception{try{ui.call(()->space.endNative("test_end"));}finally{ui.close();}}
    }
    @Test public void originalPortRequiresActualDeliveryThenIndependentExactBodyApproval()throws Exception {
        try(Harness h=new Harness()){
            String tab=h.open();JSONObject snapshot=h.start(tab),args=h.bodyArgs(tab,snapshot);assertTrue(snapshot.toString(),snapshot.getBoolean("ok"));int previous=h.approvals;
            JSONObject hidden=h.invoke("network_body",args);assertFalse(hidden.getBoolean("accepted"));assertEquals(previous,h.approvals);assertEquals(0,h.driver.bodies);
            h.deliverStart(tab,snapshot);h.approveBody=false;JSONObject denied=h.invoke("network_body",args);assertEquals("approval_required",denied.getString("code"));assertEquals(0,h.driver.bodies);assertEquals(256,h.reviewed.getJSONObject("networkResponse").getInt("maxDisclosureBytes"));assertEquals(args.getString("requestRef"),h.reviewed.getJSONObject("networkResponse").getString("requestRef"));
            h.approveBody=true;JSONObject body=h.invoke("network_body",args);assertEquals(body.toString(),"body_observed",body.getString("status"));assertTrue(body.getBoolean("ok"));assertFalse(body.toString().contains("PRIVATE_TOKEN_CANARY"));assertEquals(1,h.driver.bodies);assertEquals("native_cdp_original_response_read",body.getJSONObject("actionReceipt").getString("mechanism"));
            JSONObject replay=h.invoke("network_body",args);assertFalse(replay.getBoolean("accepted"));assertEquals(1,h.driver.bodies);
        }
    }
    @Test public void navigationDuringExactApprovalNeverRebindsOrFetchesBody()throws Exception {
        try(Harness h=new Harness()){
            String tab=h.open();JSONObject snapshot=h.start(tab);h.deliverStart(tab,snapshot);
            h.duringBodyApproval=()->{try{h.ui.call(()->{h.driver.pages.pages.get(tab).put("navigation",2L).put("documentId",UUID.randomUUID().toString());return null;});}catch(Exception failure){throw new RuntimeException(failure);}};
            JSONObject stale=h.invoke("network_body",h.bodyArgs(tab,snapshot));assertFalse(stale.getBoolean("accepted"));assertEquals(0,h.driver.bodies);
        }
    }
    @Test public void originalCancellationAndForeignCaptureCannotDeliverBody()throws Exception {
        try(Harness h=new Harness()){
            String tab=h.open();JSONObject snapshot=h.start(tab);h.deliverStart(tab,snapshot);JSONObject foreign=h.bodyArgs(tab,snapshot).put("captureId",UUID.randomUUID().toString());assertFalse(h.invoke("network_body",foreign).getBoolean("accepted"));assertEquals(0,h.driver.bodies);
            h.control.cancel();h.ui.call(()->null);assertFalse(h.driver.records.active());assertEquals(0,h.driver.bodies);
        }
    }
    @Test public void modelSchemaKeepsWitnessCompatibleAndForbidsRemoteEndpointsAndMissingCapture()throws Exception {
        try(Harness h=new Harness()){
            String tab=h.open(),capture=UUID.randomUUID().toString();h.port.validate("browser_task_network",obj("tabId",tab,"action","read"));h.port.validate("browser_task_wait",obj("tabId",tab,"condition","barrier","barrier",obj("kind","dom"),"timeoutMs",3000));
            assertThrows(Exception.class,()->h.port.validate("browser_task_network",obj("tabId",tab,"action","read","backend","native")));
            assertThrows(Exception.class,()->h.port.validate("browser_task_network",obj("tabId",tab,"action","start","backend","native","captureId",capture)));
            assertThrows(Exception.class,()->h.port.validate("browser_task_network",obj("tabId",tab,"action","start","backend","native","endpoint","ws://remote.example/")));
            assertThrows(Exception.class,()->h.port.validate("browser_task_wait",obj("tabId",tab,"condition","barrier","barrier",obj("kind","network"))));
            assertThrows(Exception.class,()->h.port.validate("browser_task_wait",obj("tabId",tab,"condition","load","barrier",obj("kind","dom"))));
        }
    }
}
