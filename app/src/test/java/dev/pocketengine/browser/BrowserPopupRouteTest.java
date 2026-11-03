package dev.pocketengine.browser;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserPopupRouteTest {
    private JSONObject parent()throws Exception{return new JSONObject().put("tabId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString()).put("browserSessionId",UUID.randomUUID().toString()).put("navigation",3).put("sourceOrigin","https://parent.example");}
    @Test public void eachActualOriginNeedsOneOwnerChoiceAndSecretsStayOutOfPreview()throws Exception {
        try(BrowserPopupRoute route=new BrowserPopupRoute(parent(),()->{})){
            var first=route.request("https://login.example/oauth?code=private-code#token");assertFalse(first.preview().toString().contains("private-code"));assertTrue(first.preview().getString("sourceOriginCoverage").contains("subframe_origin_unavailable"));assertFalse(route.originAdmitted("https://login.example/"));
            assertEquals("https://login.example/oauth?code=private-code#token",first.approveForOwner());assertTrue(route.originAdmitted("https://login.example/next"));assertFalse(route.originAdmitted("https://different.example/"));assertThrows(SecurityException.class,first::approveForOwner);
        }
    }
    @Test public void parentNavigationAndCloseRejectOldApprovalsAndTransport()throws Exception {
        AtomicBoolean live=new AtomicBoolean(true);BrowserPopupRoute route=new BrowserPopupRoute(parent(),()->{if(!live.get())throw new SecurityException("old document");});var request=route.request("https://login.example/");live.set(false);
        assertThrows(SecurityException.class,request::approveForOwner);assertThrows(SecurityException.class,()->route.reply(()->fail("stale transport")));route.close();assertFalse(route.originAdmitted("https://login.example/"));
    }
    @Test public void consumedOrLostTransportIsNeverSentTwice()throws Exception {
        AtomicInteger replies=new AtomicInteger();try(BrowserPopupRoute route=new BrowserPopupRoute(parent(),()->{})){
            assertThrows(java.io.IOException.class,()->route.reply(()->{replies.incrementAndGet();throw new java.io.IOException("unknown");}));assertTrue(route.transportConsumed());assertFalse(route.cancelReply(()->replies.incrementAndGet()));assertThrows(SecurityException.class,()->route.reply(()->replies.incrementAndGet()));assertEquals(1,replies.get());
        }
    }
    @Test public void rejectsCredentialDestinationsAndSerializedExternalIntents()throws Exception {
        try(BrowserPopupRoute route=new BrowserPopupRoute(parent(),()->{})){assertThrows(SecurityException.class,()->route.request("https://user:secret@login.example/"));}
        for(String unsafe:new String[]{"intent://scan/#Intent;component=private.Component;end","content://private/file","file:///data/data/app","javascript:alert(1)","data:text/plain,a","https://example.com/","custom://user:secret@host","mailto:test@example.com\n"})assertThrows(unsafe,Exception.class,()->BrowserExternalNavigation.parse(unsafe));
        BrowserExternalNavigation target=BrowserExternalNavigation.parse("mailto:owner@example.com?subject=Hello");assertEquals("mailto",target.scheme);assertEquals("",target.host);assertEquals("app.example",BrowserExternalNavigation.parse("my-app://app.example/return?code=secret").host);
    }
}
