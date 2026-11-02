package dev.pocketengine.browser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual native metadata projection/buffer/paging. No listener, transport or WebView is simulated as verified. */
public final class BrowserNetworkWitnessTest {
    private static BrowserNetworkWitness witness()throws Exception {
        BrowserNetworkWitness witness=new BrowserNetworkWitness(null);
        for(String key:new String[]{"enabled","document"}){Field field=BrowserNetworkWitness.class.getDeclaredField(key);field.setAccessible(true);field.set(witness,key.equals("enabled")?true:"original-document");}
        return witness;
    }
    private static void receive(BrowserNetworkWitness witness,long request,String phase,String method,String url,int status)throws Exception {
        JSONObject event=new JSONObject().put("sequence",request).put("phase",phase).put("mechanism","fetch").put("method",method).put("url",url).put("status",status).put("contentType","text/plain").put("retryAfterMs",999999).put("body","private-body-canary").put("Cookie","private-cookie-canary");
        Method append=BrowserNetworkWitness.class.getDeclaredMethod("appendNativeEvent",JSONObject.class,String.class);append.setAccessible(true);append.invoke(witness,event,"original-document");
    }
    @Test public void pagedRequestAndResponseKeepSeparateNativeCursorsWithoutDroppingTheResponse()throws Exception {try(BrowserNetworkWitness witness=witness()){
        receive(witness,10,"request","GET","https://example.com/data?query=secret",0);receive(witness,10,"response","GET","https://example.com/data?query=secret",200);receive(witness,11,"request","POST","https://example.com/submit",0);
        JSONObject first=witness.snapshot(new JSONObject().put("maxEvents",1));assertEquals(1,first.getLong("nextAfterSequence"));assertEquals(3,first.getLong("lastSeenSequence"));assertTrue(first.getBoolean("eventsOmitted"));assertEquals(10,first.getJSONArray("events").getJSONObject(0).getLong("requestSequence"));
        JSONObject second=witness.snapshot(new JSONObject().put("maxEvents",1).put("afterSequence",first.getLong("nextAfterSequence")));assertEquals(2,second.getLong("nextAfterSequence"));assertEquals("response",second.getJSONArray("events").getJSONObject(0).getString("phase"));assertEquals(10,second.getJSONArray("events").getJSONObject(0).getLong("requestSequence"));
        JSONObject third=witness.snapshot(new JSONObject().put("maxEvents",1).put("afterSequence",second.getLong("nextAfterSequence")));assertEquals("POST",third.getJSONArray("events").getJSONObject(0).getString("method"));assertFalse(third.getBoolean("eventsOmitted"));assertEquals(3,third.getLong("nextAfterSequence"));
    }}
    @Test public void filtersProjectOnlyBoundedRedactedMetadataAndNeverCaptureCredentialsOrBody()throws Exception {try(BrowserNetworkWitness witness=witness()){
        receive(witness,1,"response","GET","https://other.example/private",200);receive(witness,2,"response","POST","https://example.com/data?query=secret#private",503);
        JSONObject result=witness.snapshot(new JSONObject().put("method","POST").put("origin","https://example.com"));JSONObject row=result.getJSONArray("events").getJSONObject(0);assertEquals("https://example.com/data",row.getString("url"));assertEquals(2,result.getLong("nextAfterSequence"));assertEquals(300000,result.getJSONObject("backoff").getLong("recommendedDelayMs"));assertFalse(result.getBoolean("networkIdleProven"));assertFalse(result.getBoolean("bodiesCaptured"));assertFalse(result.getBoolean("credentialHeadersCaptured"));assertFalse(result.toString().contains("secret"));assertFalse(result.toString().contains("canary"));
    }}
    @Test public void boundedEvictionIsExplicitAndClosingErasesRowsAndTheOldDocumentCursor()throws Exception {
        BrowserNetworkWitness witness=witness();for(int i=0;i<151;i++)receive(witness,i,"request","GET","https://example.com/data",0);JSONObject result=witness.snapshot();assertEquals(150,result.getJSONArray("events").length());assertEquals(2,result.getJSONArray("events").getJSONObject(0).getLong("sequence"));assertTrue(result.getBoolean("bufferTruncated"));witness.close();result=witness.snapshot();assertEquals("unavailable",result.getString("status"));assertEquals(0,result.getJSONArray("events").length());assertEquals(0,result.getLong("nextAfterSequence"));assertEquals("",result.getString("documentId"));
    }
}
