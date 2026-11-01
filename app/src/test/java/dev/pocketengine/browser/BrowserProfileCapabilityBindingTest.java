package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.capabilities.CapabilityDescriptor;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual registry/session/lease; transport output is a named fixture, not a rendered WebView. */
public final class BrowserProfileCapabilityBindingTest {
    public static BrowserProfileRegistry.Session namedSession()throws Exception {
        var folder=Files.createTempDirectory("browser-native-capability");folder.toFile().deleteOnExit();BrowserProfileRegistry registry=new BrowserProfileRegistry(folder.resolve("profiles.json").toFile());
        JSONObject profile=registry.create("研究登录",registry.revision(),true);registry.select(profile.getString("id"),registry.revision(),true);return registry.openSession(true);
    }
    static final class Browser implements EngineModule {
        final BrowserLease lease=new BrowserLease();JSONObject input;
        public String id(){return "browser";}public JSONObject snapshot()throws Exception{return new JSONObject().put("lease",lease.snapshot());}
        public JSONObject handle(String command,JSONObject args){throw new AssertionError("must retain original control native call");}public void close(){}
    }
    @Test public void explicitlySelectedNamedProfileUsesNativeRevisionAndSession()throws Exception {
        Browser browser=new Browser();var session=namedSession();browser.lease.bindProfile(session);String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();RunControl control=new RunControl();control.bindHostRun(task,run,1);
        var binding=new ModelCapabilityAdapters.Binding(task,run,1,control,true,"");JSONObject acquired=browser.lease.acquire(session.identity().put("taskId",task).put("runId",run).put("principalId","owner").put("spaceId","private"));
        var nativeBinding=new ModelCapabilityAdapters.BrowserBinding(binding,browser,acquired,()->{},(command,input,original,admission)->{assertSame(control,original);admission.check();browser.lease.require(input);browser.input=input;return session.identity().put("observationId",UUID.randomUUID().toString()).put("navigation",1).put("expiresInMs",20000).put("text","fixture named profile body").put("elements",new JSONArray());});
        ModelCapabilityAdapters.Authority authority=new ModelCapabilityAdapters.Authority(){public void check(String tool,RunControl original)throws Exception{assertSame(control,original);original.check();}public JSONObject approve(CapabilityDescriptor tool,JSONObject args,JSONObject pin){throw new AssertionError("read fixture");}public void requirePrepared(String op,String tool,JSONObject args){throw new AssertionError("read fixture");}};
        var port=new ModelCapabilityAdapters(binding,authority,null,nativeBinding,null,null,null);JSONObject result=port.execute("browser_observe",new JSONObject(),control);assertTrue(result.getBoolean("ok"));assertEquals(session.profileId,result.getJSONObject("data").getString("profileId"));assertEquals(session.sessionId,result.getJSONObject("data").getString("browserSessionId"));
        assertEquals(session.profileId,browser.input.getString("profileId"));assertEquals(session.profileRevision,browser.input.getLong("profileRevision"));assertEquals(session.sessionId,browser.input.getString("browserSessionId"));
    }
    @Test public void ownerDefaultBindingStillRequiresAllNativeSessionFields()throws Exception {
        Browser browser=new Browser();String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();RunControl control=new RunControl();control.bindHostRun(task,run,1);var binding=new ModelCapabilityAdapters.Binding(task,run,1,control,true,"");
        JSONObject lease=browser.lease.acquire(new JSONObject().put("taskId",task).put("runId",run).put("principalId","owner").put("spaceId","private"));
        for(String field:new String[]{"profileRevision","browserSessionId","profileIsolation"}){JSONObject missing=new JSONObject(lease.toString());missing.remove(field);assertThrows(Exception.class,()->new ModelCapabilityAdapters.BrowserBinding(binding,browser,missing,()->{},(command,input,original,admission)->new JSONObject()));}
    }
}
