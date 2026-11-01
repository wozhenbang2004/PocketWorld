package dev.pocketengine.browser;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserUploadAttemptSqlTest {
    static JSONObject j(Object... v)throws Exception{return BrowserTaskPersistenceTest.obj(v);}
    JSONObject identity(BrowserTaskPersistenceTest.Fixture f)throws Exception {JSONObject p=f.source().put("documentId",UUID.randomUUID().toString()).put("chooserId",UUID.randomUUID().toString());JSONObject url=BrowserUploads.destinationIdentity("https://example.test/upload?opaque=private");for(var k=url.keys();k.hasNext();){String key=k.next();p.put(key,url.get(key));}return p;}
    JSONArray files(String hash)throws Exception{return new JSONArray().put(j("id",UUID.randomUUID().toString(),"name","same-name.txt","sha256",hash.repeat(64),"size",3));}
    @Test public void exactChosenVersionSurvivesRestartAndUnknownCannotBeDispatchedAgain()throws Exception {try(var f=new BrowserTaskPersistenceTest.Fixture()){
        JSONObject pin=identity(f),a=f.tasks.beginBrowserUpload(pin,files("a"));String id=a.getString("attemptId"),fp=a.getString("fingerprint");assertEquals("a".repeat(64),a.getJSONObject("pin").getJSONArray("attachments").getJSONObject(0).getString("sha256"));
        f.tasks.updateBrowserUpload(f.binding.taskId,id,fp,"delivery_entered",j("source","native_webview_callback"));assertThrows(SecurityException.class,()->f.tasks.beginBrowserUpload(pin,files("b")));
        TaskStore reopened=new TaskStore(RuntimeEnvironment.getApplication());try{JSONObject saved=reopened.browserUploadAttempt(f.binding.taskId,id,fp);assertEquals("delivery_unknown",saved.getString("deliveryState"));assertEquals("unknown",saved.getString("siteState"));assertFalse(saved.getBoolean("automaticReplayAllowed"));assertThrows(SecurityException.class,()->reopened.updateBrowserUpload(f.binding.taskId,id,fp,"delivery_entered",j()));assertThrows(SecurityException.class,()->reopened.browserUploadAttempt(f.binding.taskId,id,"b".repeat(64)));}finally{BrowserTaskPersistenceTest.closeDatabase(reopened);}
    }}
    @Test public void siteAcceptanceRejectionTimeoutAndCancellationStayDistinct()throws Exception {try(var f=new BrowserTaskPersistenceTest.Fixture()){
        for(String outcome:new String[]{"accepted","rejected","unknown"}){JSONObject a=f.tasks.beginBrowserUpload(identity(f),files("a"));String id=a.getString("attemptId"),fp=a.getString("fingerprint");f.tasks.updateBrowserUpload(f.binding.taskId,id,fp,"delivery_entered",j());f.tasks.updateBrowserUpload(f.binding.taskId,id,fp,"local_delivered",j());JSONObject result=f.tasks.updateBrowserUpload(f.binding.taskId,id,fp,"site_"+outcome,j("source",outcome.equals("unknown")?"bounded_timeout":"owner_confirmation","note","原页实际结果"));assertEquals(outcome,result.getString("siteState"));JSONObject cancel=f.tasks.updateBrowserUpload(f.binding.taskId,id,fp,"cancel_requested",j("platformAbortConfirmed",false));assertFalse(cancel.getBoolean("abortConfirmed"));assertEquals("local_delivered",cancel.getString("deliveryState"));assertEquals(outcome,f.tasks.updateBrowserUpload(f.binding.taskId,id,fp,"site_unknown",j("source","bounded_timeout")).getString("siteState"));}
        JSONObject a=f.tasks.beginBrowserUpload(identity(f),files("b"));JSONObject cancelled=f.tasks.updateBrowserUpload(f.binding.taskId,a.getString("attemptId"),a.getString("fingerprint"),"cancelled_before_delivery",j());assertEquals("not_delivered",cancelled.getString("deliveryState"));assertThrows(SecurityException.class,()->f.tasks.updateBrowserUpload(f.binding.taskId,a.getString("attemptId"),a.getString("fingerprint"),"delivery_entered",j()));
    }}
}
