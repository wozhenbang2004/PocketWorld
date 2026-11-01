package dev.pocketengine.browser;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original TaskStore/SQLite/journal/authority, with explicit HTTP body and native page-interface fixtures; no public-network/device claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public final class BrowserPublicHttpPersistenceTest {
    static JSONObject args()throws Exception{return PublicHttpReaderTest.args();}
    static PublicHttpReader.Result fetched(JSONObject args,RunControl original)throws Exception {
        PublicHttpReaderTest.Body body=new PublicHttpReaderTest.Body("actual server response fixture".getBytes(StandardCharsets.UTF_8),"text/plain",true);
        try(Response response=PublicHttpReaderTest.response(body,"text/plain",200)){return PublicHttpReaderTest.consume(response,args,new PublicHttpReader.Budget(original));}
    }
    static JSONObject source(BrowserTaskPersistenceTest.Fixture f,PublicHttpReader.Result result)throws Exception {
        JSONObject facts=result.value.getJSONObject("http");return f.source().put("tabId",JSONObject.NULL).put("tabGeneration",JSONObject.NULL).put("navigation",JSONObject.NULL).put("frameId","not_rendered")
                .put("openedBy","task_public_http").put("url","https://public.example/article").put("privateUrl",facts.getString("finalUrl")).put("urlScope","owner_private_task").put("urlQueryOmitted",true)
                .put("observationId",result.value.getString("observationId")).put("observedAt",facts.getLong("retrievedAt")).put("excerpt",result.value.getString("text"))
                .put("kind","public_http_observation").put("acquisitionMethod","public_http").put("dataScope","public_anonymous_response").put("http",new JSONObject(facts.toString()));
    }
    @Test public void productionProducerRetainsAndRecallsThePublicBodyInTheOriginalTaskWithoutFakeTabRefs()throws Exception {
        try(BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture(true);BrowserTaskSpaceTest.Queue ui=new BrowserTaskSpaceTest.Queue()){
            BrowserTaskSpace space=ui.call(()->new BrowserTaskSpace(f.binding,f.control,BrowserProfileCapabilityBindingTest.namedSession(),new BrowserTaskSpaceTest.Driver(),ui,f.sources,null));
            JSONObject args=args();String operation=f.journal.beginOperation(PublicHttpReader.TOOL,args);PublicHttpReader.Result actual=fetched(args,f.control);
            BrowserTaskSpace.Gate original=()->{f.authority.check(PublicHttpReader.TOOL,f.control);f.authority.requirePrepared(operation,PublicHttpReader.TOOL,args);};
            JSONObject receipt=space.retainPublicSource(actual,operation,original);assertTrue(receipt.toString(),receipt.getBoolean("sourceRetained"));String id=receipt.getString("sourceId");
            assertEquals(id,space.retainPublicSource(actual,operation,original).getString("sourceId"));assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());f.journal.finishOperation(operation,receipt);
            TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONObject source=BrowserTaskSourcePort.sourcesForOwner(reopened,f.binding.taskId).getJSONObject(0);assertEquals("public_http_observation",source.getString("kind"));assertEquals("public_http",source.getString("acquisitionMethod"));assertEquals("public_anonymous_response",source.getString("dataScope"));assertTrue(source.isNull("tabId"));assertTrue(source.isNull("tabGeneration"));assertTrue(source.isNull("navigation"));assertEquals("not_rendered",source.getString("frameId"));assertEquals("actual server response fixture",source.getString("excerpt"));assertEquals(args.getString("url"),source.getString("privateUrl"));assertFalse(source.getJSONObject("http").getBoolean("profileCredentialsUsed"));assertFalse(source.getBoolean("verified"));assertTrue(source.getBoolean("untrustedPageContent"));}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
            JSONObject reference=BrowserTaskSourcePort.projectReferenceForOwner(f.tasks,f.binding.taskId,id);assertEquals("https://public.example/article",reference.getString("redactedUrl"));assertFalse(reference.toString().contains("edition=2026"));assertFalse(reference.getBoolean("privateUrlIncluded"));assertTrue(reference.getBoolean("untrusted"));
            f.control.cancel();assertEquals("retained",f.sources.lookup(f.binding,id).getString("status"));ui.call(()->{space.endNative("fixture_end");return null;});
        }
    }
    @Test public void sourceValidatorRejectsCredentialFlagsFakeTabsAndUnapprovedRedirects()throws Exception {try(BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture(true)){
        JSONObject args=args();String operation=f.journal.beginOperation(PublicHttpReader.TOOL,args);PublicHttpReader.Result read=fetched(args,f.control);JSONObject source=source(f,read);String id=source.getString("sourceId");
        JSONObject credentials=new JSONObject(source.toString());credentials.getJSONObject("http").put("profileCredentialsUsed",true);assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,operation,id,credentials,read.budget));
        assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,operation,id,new JSONObject(source.toString()).put("tabId",UUID.randomUUID().toString()),read.budget));
        JSONObject redirected=new JSONObject(source.toString()).put("url","https://outside.example/article").put("privateUrl","https://outside.example/article").put("urlQueryOmitted",false);redirected.getJSONObject("http").put("finalUrl","https://outside.example/article").put("redirects",new JSONArray().put(PublicHttpReaderTest.obj("fromOrigin","https://public.example","toOrigin","https://outside.example","statusCode",302)));assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,operation,id,redirected,read.budget));
        JSONObject fields=new JSONObject(source.toString());fields.getJSONObject("http").put("unexpectedHeader","no");assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,operation,id,fields,read.budget));
        assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());f.journal.finishOperation(operation,PublicHttpReaderTest.obj("accepted",false,"verified",false));
    }}
    @Test public void publicKindRequiresTheSameOriginalBudgetPreparedUrlAndOriginalGrant()throws Exception {try(BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture(true)){
        PublicHttpReader.Result read=fetched(args(),f.control);JSONObject source=source(f,read);String id=source.getString("sourceId");String operation=f.journal.beginOperation(PublicHttpReader.TOOL,args().put("url","https://another.example/article"));
        assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,operation,id,source,read.budget));f.journal.finishOperation(operation,PublicHttpReaderTest.obj("accepted",false,"verified",false));
        String original=f.journal.beginOperation(PublicHttpReader.TOOL,args());assertThrows(SecurityException.class,()->f.sources.retain(f.binding,f.control,original,id,source));assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,original,id,source,new PublicHttpReader.Budget(new RunControl())));
        f.resource.set(false);assertThrows(SecurityException.class,()->f.sources.retainPublic(f.binding,f.control,original,id,source,read.budget));f.resource.set(true);assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());f.journal.finishOperation(original,PublicHttpReaderTest.obj("accepted",false,"verified",false));
    }try(BrowserTaskPersistenceTest.Fixture noGrant=new BrowserTaskPersistenceTest.Fixture()){
        PublicHttpReader.Result read=fetched(args(),noGrant.control);JSONObject source=source(noGrant,read);assertThrows(SecurityException.class,()->noGrant.journal.beginOperation(PublicHttpReader.TOOL,args()));assertThrows(SecurityException.class,()->noGrant.sources.retainPublic(noGrant.binding,noGrant.control,UUID.randomUUID().toString(),source.getString("sourceId"),source,read.budget));assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(noGrant.tasks,noGrant.binding.taskId).length());
    }}
    @Test public void expiryDuringNativeReservationCheckCannotStartALateSQLiteSourceWrite()throws Exception {try(BrowserTaskPersistenceTest.Fixture f=new BrowserTaskPersistenceTest.Fixture(true)){
        PublicHttpReader.Result read=fetched(args(),f.control);JSONObject source=source(f,read);String operation=f.journal.beginOperation(PublicHttpReader.TOOL,args());AtomicBoolean reservationEntered=new AtomicBoolean();BrowserTaskSourcePort delayed=new BrowserTaskSourcePort(f.tasks,f.binding,f.control,new TaskStoreCapabilityAuthority(f.tasks,f.binding,f.events::add),()->{reservationEntered.set(true);Thread.sleep(550);},f.events::add);PublicHttpReader.Budget budget=new PublicHttpReader.Budget(f.control,500);
        assertThrows(TimeoutException.class,()->delayed.retainPublic(f.binding,f.control,operation,source.getString("sourceId"),source,budget));assertTrue(reservationEntered.get());assertEquals(0,BrowserTaskSourcePort.sourcesForOwner(f.tasks,f.binding.taskId).length());
    }}
}
