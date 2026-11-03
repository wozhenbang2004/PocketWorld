package dev.pocketengine.browser;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserSiteDecisionTest {
    private JSONObject identity()throws Exception{return new JSONObject().put("tabId",UUID.randomUUID().toString()).put("documentId",UUID.randomUUID().toString()).put("browserSessionId",UUID.randomUUID().toString()).put("navigation",7).put("sourceOrigin","https://media.example");}
    @Test public void platformApprovalCannotReplaceTheExactNativeOwnerDecision()throws Exception {
        AtomicInteger calls=new AtomicInteger();BrowserSiteDecision decision=new BrowserSiteDecision(identity(),()->{},allowed->calls.incrementAndGet(),()->100L);
        assertThrows(SecurityException.class,()->decision.platformResult(true));assertThrows(SecurityException.class,()->decision.chooseForOwner(UUID.randomUUID().toString(),true));assertEquals(0,calls.get());
        decision.chooseForOwner(decision.id(),true);assertEquals(0,calls.get());decision.platformResult(true);assertEquals(1,calls.get());assertFalse(decision.cancel());assertThrows(SecurityException.class,()->decision.platformResult(true));
    }
    @Test public void documentRevisionChangeAfterOwnerChoiceNeverGrantsTheOldCallback()throws Exception {
        AtomicInteger revision=new AtomicInteger(1),grants=new AtomicInteger(),denials=new AtomicInteger();BrowserSiteDecision decision=new BrowserSiteDecision(identity(),()->{if(revision.get()!=1)throw new SecurityException("changed");},allowed->{if(allowed)grants.incrementAndGet();else denials.incrementAndGet();},()->100L);
        decision.chooseForOwner(decision.id(),true);revision.incrementAndGet();assertThrows(SecurityException.class,()->decision.platformResult(true));decision.cancel();assertEquals(0,grants.get());assertEquals(1,denials.get());
    }
    @Test public void androidDenialConsumesOnlyTheOriginalRequest()throws Exception {
        AtomicInteger grants=new AtomicInteger(),denials=new AtomicInteger();BrowserSiteDecision decision=new BrowserSiteDecision(identity(),()->{},allowed->{if(allowed)grants.incrementAndGet();else denials.incrementAndGet();},()->100L);
        decision.chooseForOwner(decision.id(),true);decision.platformResult(false);assertEquals("denied",decision.snapshot().getString("status"));assertEquals(0,grants.get());assertEquals(1,denials.get());assertFalse(decision.cancel());
    }
    @Test public void expiredOrPlatformCancelledRequestNeverReusesTheHandle()throws Exception {
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();BrowserSiteDecision decision=new BrowserSiteDecision(identity(),()->{},allowed->calls.incrementAndGet(),clock::get);
        clock.set(BrowserSiteDecision.LIMIT_MS);assertThrows(SecurityException.class,()->decision.chooseForOwner(decision.id(),true));decision.platformCancelled();assertFalse(decision.cancel());assertEquals(0,calls.get());
    }
    @Test public void lostCallbackReceiptStaysUnknownAndIsNeverReplayed()throws Exception {
        AtomicInteger calls=new AtomicInteger();BrowserSiteDecision decision=new BrowserSiteDecision(identity(),()->{},allowed->{calls.incrementAndGet();throw new java.io.IOException("after dispatch");},()->100L);
        decision.chooseForOwner(decision.id(),true);assertThrows(java.io.IOException.class,()->decision.platformResult(true));assertEquals("unknown",decision.snapshot().getString("status"));assertFalse(decision.snapshot().getBoolean("automaticReplayAllowed"));assertFalse(decision.cancel());assertEquals(1,calls.get());
    }
    @Test public void resourceMappingNeverGrantsUnknownFutureTypesOrDuplicates() {
        assertArrayEquals(new String[]{"android.permission.CAMERA","android.permission.RECORD_AUDIO"},BrowserSitePermissions.media(new String[]{"android.webkit.resource.VIDEO_CAPTURE","android.webkit.resource.AUDIO_CAPTURE"}).androidPermissions);
        assertThrows(SecurityException.class,()->BrowserSitePermissions.media(new String[]{"android.webkit.resource.VIDEO_CAPTURE","android.webkit.resource.FUTURE_DEVICE"}));
        assertThrows(SecurityException.class,()->BrowserSitePermissions.media(new String[]{"android.webkit.resource.AUDIO_CAPTURE","android.webkit.resource.AUDIO_CAPTURE"}));
        assertArrayEquals(new String[]{"android.permission.ACCESS_COARSE_LOCATION"},BrowserSitePermissions.location().androidPermissions);
    }
}
