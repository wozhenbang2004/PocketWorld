package dev.pocketengine.browser;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class BrowserPopupRouteTest {
    static JSONObject identity()throws Exception{return new JSONObject().put("tabId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString()).put("browserSessionId",UUID.randomUUID().toString()).put("sourceOrigin","https://source.example").put("navigation",2);}
    @Test public void nativeTransportAndOwnerDestinationConfirmationAreOneUse()throws Exception {
        AtomicInteger replies=new AtomicInteger();try(var route=new BrowserPopupRoute(identity(),()->{})){route.reply(replies::incrementAndGet);assertThrows(SecurityException.class,()->route.reply(replies::incrementAndGet));assertEquals(1,replies.get());
            var request=route.request("https://login.example/authorize?private=code");assertFalse(route.admitted("https://login.example/other"));assertEquals("https://login.example",request.preview().getString("destinationOrigin"));assertFalse(request.preview().toString().contains("private=code"));assertEquals("https://login.example/authorize?private=code",request.approveForOwner());assertTrue(route.admitted("https://login.example/other"));assertThrows(SecurityException.class,request::approveForOwner);
        }
    }
    @Test public void parentNavigationRevocationAndCredentialUrlsCannotKeepAPopupAuthority()throws Exception {
        AtomicBoolean live=new AtomicBoolean(true);try(var route=new BrowserPopupRoute(identity(),()->{if(!live.get())throw new SecurityException();})){var request=route.request("https://login.example/");assertThrows(Exception.class,()->route.request("https://owner:secret@login.example/"));live.set(false);assertThrows(SecurityException.class,request::approveForOwner);assertThrows(SecurityException.class,()->route.reply(()->{}));}
    }
}
