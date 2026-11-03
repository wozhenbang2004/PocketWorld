package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the registered model tool through original prepared-operation and exact approval. */
public final class BrowserArtifactApprovalTest {
    static JSONObject obj(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    static String id(){return UUID.randomUUID().toString();}
    static final class Driver implements BrowserTaskSpace.Driver {
        final BrowserTaskSpaceTest.Driver original=new BrowserTaskSpaceTest.Driver();
        final Map<String,String> documents=new HashMap<>();final String task;JSONObject source,lastArgs;int uploads;
        Driver(String task)throws Exception {this.task=task;source=BrowserArtifactSourceTest.workspace(task);}
        public JSONObject metadata(BrowserTaskSpace.Tab tab)throws Exception {return original.metadata(tab).put("documentId",documents.get(tab.tabId));}
        public void open(BrowserTaskSpace.Tab tab,String url,BrowserTaskSpace.Gate gate)throws Exception {original.open(tab,url,gate);documents.put(tab.tabId,id());}
        public void select(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {original.select(tab,gate);}
        public void execute(BrowserTaskSpace.Tab tab,String command,JSONObject args,BrowserTaskSpace.Gate gate,CompletableFuture<JSONObject> result)throws Exception {
            if(command.equals("observe")){
                select(tab,gate);gate.entered();JSONObject observed=original.observation(tab).put("documentId",documents.get(tab.tabId));
                observed.put("elements",new JSONArray().put(obj("ref","n0","nodeKey","k1","tag","INPUT","type","file","id","upload","name","report","formAction","https://example.com/submit","formMethod","post")));
                result.complete(observed);return;
            }
            if(command.equals("upload_artifact")){
                select(tab,gate);gate.check();gate.entered();uploads++;lastArgs=new JSONObject(args.toString());
                result.complete(obj("status","local_delivered","accepted",true,"verified",false,"siteState","unknown","automaticReplayAllowed",false));return;
            }
            original.execute(tab,command,args,gate,result);
        }
        public JSONObject uploadArtifactPin(BrowserTaskSpace.Tab tab,JSONObject args)throws Exception {metadata(tab);return BrowserArtifactSource.workspacePin(task,args.getJSONObject("artifact"),source);}
        public JSONObject download(BrowserTaskSpace.Tab tab,String url,String operation,BrowserTaskSpace.Gate gate)throws Exception {return original.download(tab,url,operation,gate);}
        public void adoptOwner(BrowserTaskSpace.Tab tab,BrowserTaskSpace.Gate gate)throws Exception {original.adoptOwner(tab,gate);}
        public void releaseToOwner(BrowserTaskSpace.Tab tab)throws Exception {original.releaseToOwner(tab);}
        public boolean closeOwned(BrowserTaskSpace.Tab tab)throws Exception {return original.closeOwned(tab);}
        public void abort(BrowserTaskSpace.Tab tab,CompletableFuture<JSONObject> operation){original.abort(tab,operation);}
    }
    static final class Fixture implements AutoCloseable {
        final BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue();final RunControl control=new RunControl();
        final ModelCapabilityAdapters.Binding binding;final BrowserTaskSpaceTest.HostAuthority authority=new BrowserTaskSpaceTest.HostAuthority(control);
        final BrowserTaskSpace space;final BrowserTaskCapabilityPort port;final Driver driver;JSONObject approvedPin;int exactApprovals;boolean approved=true;Runnable duringApproval;
        Fixture()throws Exception {
            String task=id(),run=id();control.bindHostRun(task,run,1);binding=new ModelCapabilityAdapters.Binding(task,run,1,control,true,"");driver=new Driver(task);
            BrowserProfileRegistry profiles=new BrowserProfileRegistry(new File(Files.createTempDirectory("artifact-approval-").toFile(),"profiles.json"));
            var profile=profiles.openSession(true);
            space=ui.call(()->new BrowserTaskSpace(binding,control,profile,driver,ui,null,new BrowserTaskSpace.NativeEvents(){
                public void takenOver(ModelCapabilityAdapters.Binding b,RunControl c,String s,String t){c.pause();}
                public void ended(ModelCapabilityAdapters.Binding b,RunControl c,String s,JSONObject receipt){}
            }));
            port=new BrowserTaskCapabilityPort(space,authority,()->{},new BrowserTaskCapabilityPort.ApprovalPort(){
                public JSONObject approve(ModelCapabilityAdapters.Binding b,RunControl original,String operation,String tool,JSONObject args,JSONObject pin)throws Exception {
                    assertSame(binding,b);assertSame(control,original);authority.requirePrepared(operation,tool,args);
                    if(tool.equals("browser_task_upload_artifact")){exactApprovals++;approvedPin=new JSONObject(pin.toString());if(duringApproval!=null)duringApproval.run();}
                    return obj("approved",approved,"approvalId",id());
                }
                public JSONObject admitReadScope(ModelCapabilityAdapters.Binding b,RunControl c,String op,String tool,JSONObject args,JSONObject pin){assertNotEquals("An upload must not enter a broad read grant", "browser_task_upload_artifact",tool);return null;}
                public JSONObject admitTaskScope(ModelCapabilityAdapters.Binding b,RunControl c,String op,String tool,JSONObject args,JSONObject pin){assertNotEquals("An upload must not enter a broad interaction grant", "browser_task_upload_artifact",tool);return null;}
            },ui,null);
        }
        JSONObject invoke(String command,JSONObject args)throws Exception {
            String tool="browser_task_"+command,operation=id();authority.prepared.put(operation,obj("tool",tool,"args",new JSONObject(args.toString())));
            return port.execute(tool,new JSONObject(args.toString()).put("_hostOperationId",operation),control);
        }
        JSONObject uploadArgs()throws Exception {
            String tab=invoke("open",obj("url","https://example.com/form")).getString("tabId");JSONObject observed=invoke("observe",obj("tabId",tab));
            return obj("tabId",tab,"observationId",observed.getString("observationId"),"ref","n0","artifact",BrowserArtifactSourceTest.selected(driver.source));
        }
        public void close()throws Exception {try{ui.call(()->space.endNative("test_end"));}finally{ui.close();}}
    }
    @Test public void registeredToolPinsExactSourceAndNativeInputForOriginalApproval()throws Exception {try(Fixture f=new Fixture()){
        assertTrue(f.port.supports("browser_task_upload_artifact"));JSONObject args=f.uploadArgs(),receipt=f.invoke("upload_artifact",args);
        assertEquals("local_delivered",receipt.getString("status"));assertEquals(1,f.exactApprovals);assertEquals(1,f.driver.uploads);
        JSONObject approved=f.approvedPin.getJSONObject("uploadArtifact");assertEquals(3,approved.getInt("sourceVersion"));
        assertEquals(f.driver.source.getString("sha256"),approved.getString("sha256"));assertEquals(f.binding.taskId,f.approvedPin.getString("taskId"));
        assertEquals("k1",f.driver.lastArgs.getJSONObject("_nativeTargetPreview").getString("nodeKey"));
        assertEquals(approved.toString(),f.driver.lastArgs.getJSONObject("_nativeUploadArtifact").toString());assertFalse(receipt.getBoolean("verified"));
    }}
    @Test public void denialOrRevisionChangeDuringApprovalCannotReachNativeChooser()throws Exception {
        try(Fixture f=new Fixture()){JSONObject args=f.uploadArgs();f.approved=false;JSONObject denied=f.invoke("upload_artifact",args);assertEquals("approval_required",denied.getString("code"));assertEquals(0,f.driver.uploads);}
        try(Fixture f=new Fixture()){JSONObject args=f.uploadArgs();f.duringApproval=()->{try{f.driver.source.put("sourceVersion",4);}catch(Exception malformed){throw new AssertionError(malformed);}};
            JSONObject changed=f.invoke("upload_artifact",args);assertFalse(changed.getBoolean("accepted"));assertEquals(0,f.driver.uploads);assertEquals(1,f.exactApprovals);}
    }
    @Test public void modelCannotInjectNativePinOrFilesystemSource()throws Exception {try(Fixture f=new Fixture()){
        JSONObject args=f.uploadArgs();args.put("_nativeUploadArtifact",obj("id",id()));assertThrows(Exception.class,()->f.invoke("upload_artifact",args));assertEquals(0,f.driver.uploads);
        args.remove("_nativeUploadArtifact");args.getJSONObject("artifact").put("path","/private/arbitrary-file");assertThrows(Exception.class,()->f.invoke("upload_artifact",args));assertEquals(0,f.driver.uploads);assertEquals(0,f.exactApprovals);
    }}
}
