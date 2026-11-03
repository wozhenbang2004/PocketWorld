package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real bounded production store, supplied CDP event transcripts; not a device/provider claim. */
public final class BrowserNativeNetworkRecordsTest {
    static JSONObject obj(Object... pairs)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    static BrowserNetworkRecords records()throws Exception {return new BrowserNetworkRecords(obj("taskId",UUID.randomUUID().toString(),"tabId",UUID.randomUUID().toString(),"documentId",UUID.randomUUID().toString(),"epoch",3),"frame-a","loader-a",0);}
    static void request(BrowserNetworkRecords r,String id,String url,long at)throws Exception {r.event(obj("method","Network.requestWillBeSent","params",obj("requestId",id,"frameId","frame-a","loaderId","loader-a","type","Fetch","request",obj("url",url,"method","GET","headers",obj("Cookie","COOKIE_CANARY","Authorization","Bearer AUTH_CANARY"),"postData","POST_CANARY"))),"",at);}
    static void response(BrowserNetworkRecords r,String id,int status,String mime,long at)throws Exception {r.event(obj("method","Network.responseReceived","params",obj("requestId",id,"frameId","frame-a","loaderId","loader-a","response",obj("status",status,"mimeType",mime,"headers",obj("Set-Cookie","COOKIE_CANARY","Authorization","AUTH_CANARY","Retry-After","700")))),"",at);}
    static void finished(BrowserNetworkRecords r,String id,long at)throws Exception {r.event(obj("method","Network.loadingFinished","params",obj("requestId",id,"encodedDataLength",120)),"",at);}
    static void complete(BrowserNetworkRecords r,String id)throws Exception {request(r,id,"https://example.test/api/items",1);response(r,id,200,"application/json",2);finished(r,id,3);}
    static JSONObject shown(BrowserNetworkRecords r)throws Exception {JSONObject out=r.snapshot(10);r.delivered(out);return out;}
    static JSONObject bodyArgs(BrowserNetworkRecords r,JSONObject row,int max)throws Exception {return obj("captureId",r.captureId,"requestRef",row.getString("requestRef"),"maxBytes",max);}

    @Test public void realProtocolMetadataNeverRetainsCredentialHeadersQueryOrPostBody()throws Exception {
        BrowserNetworkRecords r=records();request(r,"r1","https://user:PW_CANARY@example.test/api/token/PATH_CANARY?auth=QUERY_CANARY#FRAGMENT_CANARY",1);response(r,"r1",429,"application/json",2);finished(r,"r1",3);
        JSONObject out=r.snapshot(10),row=out.getJSONArray("requests").getJSONObject(0);String text=out.toString();
        for(String secret:new String[]{"COOKIE_CANARY","AUTH_CANARY","POST_CANARY","PW_CANARY","PATH_CANARY","QUERY_CANARY","FRAGMENT_CANARY"})assertFalse(secret,text.contains(secret));
        assertEquals(300000,row.getLong("retryAfterMs"));assertFalse(row.getBoolean("bodyEligible"));assertFalse(out.getBoolean("networkIdleProven"));assertFalse(out.getBoolean("automaticRequestsPerformed"));
    }
    @Test public void onlyOriginalMainLoaderRequestsStartedInsideCaptureAreKnown()throws Exception {
        BrowserNetworkRecords r=records();JSONObject request=obj("method","Network.requestWillBeSent","params",obj("requestId","other","frameId","frame-child","loaderId","loader-a","request",obj("url","https://example.test/child","method","GET")));
        r.event(request,"",1);request.getJSONObject("params").put("frameId","frame-a").put("loaderId","old-loader");r.event(request,"",2);response(r,"before-enable",200,"application/json",3);
        assertEquals(0,r.snapshot(20).getJSONArray("requests").length());JSONObject quiet=r.quiet(30);assertTrue(quiet.getBoolean("available"));assertFalse(quiet.getBoolean("preexistingRequestsKnown"));assertFalse(quiet.getBoolean("networkIdleProven"));
    }
    @Test public void attachTimeBeforeNetworkEnableDoesNotCountAsObservedQuietTime()throws Exception {BrowserNetworkRecords r=records();r.networkEnabled(1400);assertEquals(0,r.quiet(1400).getLong("quietForMs"));assertEquals(500,r.quiet(1900).getLong("quietForMs"));assertEquals(1400,r.snapshot(1900).getLong("captureEnabledAtElapsedMs"));}
    @Test public void rawSnapshotDoesNotMakeAnUnshownBodyAnApprovalTarget()throws Exception {
        BrowserNetworkRecords r=records();complete(r,"r1");JSONObject offered=r.snapshot(5),args=bodyArgs(r,offered.getJSONArray("requests").getJSONObject(0),256);
        assertThrows(SecurityException.class,()->r.bodyPin(args));r.delivered(offered);assertEquals(256,r.bodyPin(args).getInt("maxDisclosureBytes"));assertTrue(r.bodyPin(args).getBoolean("requiresSeparateApproval"));
    }
    @Test public void finalProjectionOnlyAdmitsActuallyShownResponseVersions()throws Exception {
        BrowserNetworkRecords r=records();complete(r,"a");complete(r,"b");JSONObject offered=r.snapshot(5);JSONArray rows=offered.getJSONArray("requests");JSONObject hidden=bodyArgs(r,rows.getJSONObject(1),256),visible=bodyArgs(r,rows.getJSONObject(0),256);
        JSONObject projected=new JSONObject(offered.toString()).put("requests",new JSONArray().put(rows.getJSONObject(0)));r.delivered(projected);assertThrows(SecurityException.class,()->r.bodyPin(hidden));assertNotNull(r.bodyPin(visible));
        BrowserNetworkRecords pending=records();request(pending,"pending","https://example.test/data",1);JSONObject before=pending.snapshot(2);response(pending,"pending",200,"application/json",3);finished(pending,"pending",4);pending.delivered(before);assertThrows(SecurityException.class,()->pending.bodyPin(bodyArgs(pending,before.getJSONArray("requests").getJSONObject(0),256)));
    }
    @Test public void inventedOrChangedProjectionCannotAuthorizeResponseDisclosure()throws Exception {
        BrowserNetworkRecords r=records();complete(r,"a");JSONObject offered=r.snapshot(5);JSONObject changed=new JSONObject(offered.toString());changed.getJSONArray("requests").getJSONObject(0).put("url","https://other.test/");assertThrows(SecurityException.class,()->r.delivered(changed));
        JSONObject foreign=new JSONObject(offered.toString()).put("documentId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->r.delivered(foreign));r.delivered(offered);
        assertThrows(SecurityException.class,()->r.bodyPin(obj("captureId",UUID.randomUUID().toString(),"requestRef",offered.getJSONArray("requests").getJSONObject(0).getString("requestRef"))));
    }
    @Test public void approvedSingleBodyIsJsonOnlyRedactedBoundedAndCannotBeReadAgain()throws Exception {
        BrowserNetworkRecords r=records();complete(r,"a");JSONObject out=shown(r),args=bodyArgs(r,out.getJSONArray("requests").getJSONObject(0),256);BrowserNetworkRecords.BodyPermit permit=r.reserveBody(args);
        JSONObject raw=obj("password","PASSWORD_CANARY","access_token","TOKEN_CANARY","authorization","AUTH_CANARY","link","https://example.test/view?token=URL_CANARY","data","public result ".repeat(80),"rows",new JSONArray().put(obj("title","Visible item")));
        JSONObject body=r.body(permit,obj("body",raw.toString(),"base64Encoded",false));assertEquals("body_observed",body.getString("status"));assertTrue(body.getBoolean("bodyTruncated"));assertTrue(body.getInt("bytesDisclosed")<=256);assertFalse(body.getBoolean("bodyRefetched"));
        for(String secret:new String[]{"PASSWORD_CANARY","TOKEN_CANARY","AUTH_CANARY","URL_CANARY"})assertFalse(body.toString().contains(secret));
        assertThrows(SecurityException.class,()->r.reserveBody(args));assertThrows(SecurityException.class,()->r.body(permit,obj("body","{}")));
    }
    @Test public void nonJsonSensitiveEndpointAndFailedHttpResponseCannotRevealBody()throws Exception {
        for(String[] sample:new String[][]{{"https://example.test/login","application/json","200"},{"https://example.test/data","text/html","200"},{"https://example.test/data","application/json","401"},{"https://example.test/%74oken/value","application/json","200"}}){BrowserNetworkRecords r=records();request(r,"a",sample[0],1);response(r,"a",Integer.parseInt(sample[2]),sample[1],2);finished(r,"a",3);JSONObject out=shown(r);assertFalse(out.getJSONArray("requests").getJSONObject(0).getBoolean("bodyEligible"));assertThrows(SecurityException.class,()->r.reserveBody(bodyArgs(r,out.getJSONArray("requests").getJSONObject(0),256)));}
    }
    @Test public void CredentialNamesAndEmbeddedAssignmentsAreNotPreservedAsJsonKeysOrStrings()throws Exception {
        BrowserNetworkRecords r=records();complete(r,"a");JSONObject out=shown(r);JSONObject raw=obj("cookie:KEY_CANARY","x","message","token=VALUE_CANARY","entry","Basic CREDENTIAL_CANARY","nested",obj("samlAssertion","SAML_CANARY"),"title","Public result");
        JSONObject body=r.body(r.reserveBody(bodyArgs(r,out.getJSONArray("requests").getJSONObject(0),4096)),obj("body",raw.toString()));String serialized=body.toString();for(String secret:new String[]{"KEY_CANARY","VALUE_CANARY","CREDENTIAL_CANARY","SAML_CANARY"})assertFalse(serialized.contains(secret));assertTrue(serialized.contains("Public result"));assertFalse(body.getBoolean("bodyTruncated"));
    }
    @Test public void separateCaptureBodyByteAndObjectBudgetsAreConservative()throws Exception {
        BrowserNetworkRecords bytes=records();for(int i=0;i<5;i++){complete(bytes,"r"+i);JSONObject out=shown(bytes),args=bodyArgs(bytes,out.getJSONArray("requests").getJSONObject(0),16384);if(i<4)bytes.reserveBody(args);else assertThrows(SecurityException.class,()->bytes.reserveBody(args));}
        BrowserNetworkRecords count=records();for(int i=0;i<9;i++){complete(count,"r"+i);JSONObject out=shown(count),args=bodyArgs(count,out.getJSONArray("requests").getJSONObject(0),256);if(i<8)count.reserveBody(args);else assertThrows(SecurityException.class,()->count.reserveBody(args));}
        assertEquals(0,bytes.snapshot(20).getInt("bodyBytesRemaining"));assertEquals(0,count.snapshot(20).getInt("bodyReadsRemaining"));
    }
    @Test public void redirectsHaveDistinctReferencesAndOldResponseIsNeverRebound()throws Exception {
        BrowserNetworkRecords r=records();request(r,"redirect","https://example.test/old",1);JSONObject old=r.snapshot(2).getJSONArray("requests").getJSONObject(0);
        r.event(obj("method","Network.requestWillBeSent","params",obj("requestId","redirect","frameId","frame-a","loaderId","loader-a","redirectResponse",obj("status",302),"request",obj("url","https://example.test/new?secret=CANARY","method","GET"))),"",3);response(r,"redirect",200,"application/json",4);finished(r,"redirect",5);JSONObject out=shown(r);assertNotEquals(old.getString("requestRef"),out.getJSONArray("requests").getJSONObject(0).getString("requestRef"));assertThrows(SecurityException.class,()->r.bodyPin(bodyArgs(r,old,256)));assertEquals("redirected",out.getJSONArray("requests").getJSONObject(1).getString("phase"));
    }
    @Test public void lostInflightCoverageCannotBecomeAQuietProofAndCancelErasesRefs()throws Exception {
        BrowserNetworkRecords r=records();for(int i=0;i<129;i++)request(r,"r"+i,"https://example.test/data",i);assertFalse(r.quiet(9999).getBoolean("available"));assertTrue(r.snapshot(9999).getBoolean("coverageLost"));
        BrowserNetworkRecords current=records();complete(current,"a");JSONObject out=shown(current);BrowserNetworkRecords.BodyPermit permit=current.reserveBody(bodyArgs(current,out.getJSONArray("requests").getJSONObject(0),256));current.invalidate("navigation_changed");assertThrows(SecurityException.class,()->current.body(permit,obj("body","{\"secret\":\"CANCELLED_CANARY\"}")));assertFalse(current.quiet(50).getBoolean("available"));assertEquals(0,current.snapshot(50).getJSONArray("requests").length());
    }
    @Test public void malformedEvictedOrOversizedBodyIsExplicitUnavailableWithoutRefetch()throws Exception {
        for(JSONObject raw:new JSONObject[]{obj("body","{broken"),obj("body","<html>private</html>"),obj("body","!bad-base64!","base64Encoded",true),obj("body","x".repeat(65537)),obj("body","[".repeat(5000)+"0"+"]".repeat(5000))}){BrowserNetworkRecords r=records();complete(r,"a");JSONObject out=shown(r);JSONObject body=r.body(r.reserveBody(bodyArgs(r,out.getJSONArray("requests").getJSONObject(0),256)),raw);assertEquals("unavailable",body.getString("status"));assertFalse(body.getBoolean("bodyRefetched"));assertFalse(body.has("body"));}
        assertEquals("中",BrowserNetworkRecords.clipBytes("中文",4));assertTrue(BrowserNetworkRecords.clipBytes("😀中文",6).getBytes(StandardCharsets.UTF_8).length<=6);
    }
}
