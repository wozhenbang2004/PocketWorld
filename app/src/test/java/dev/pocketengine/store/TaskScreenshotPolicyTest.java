package dev.pocketengine.store;

import static org.junit.Assert.*;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;

public final class TaskScreenshotPolicyTest {
    private interface Attempt{void run()throws Exception;}
    private static void refused(Attempt attempt)throws Exception {try{attempt.run();fail("Expected rejection");}catch(AssertionError failure){throw failure;}catch(Exception expected){}}
    @Test public void defaultAppliesOnlyToNewImagesAndOwnerSettingsUseRevisionCas()throws Exception {
        JSONObject initial=TaskScreenshotPolicy.initial();assertEquals(TaskScreenshotPolicy.DAY,initial.getLong("retentionMillis"));
        assertEquals("future_results",initial.getString("appliesTo"));assertEquals("keep_current_until_owner_deletes_or_replaces",initial.getString("legacyPolicy"));
        JSONObject changed=TaskScreenshotPolicy.change(initial,1,0,3);assertEquals(2,changed.getLong("revision"));assertFalse(TaskScreenshotPolicy.publicView(changed).getBoolean("newCapturesEnabled"));
        refused(()->TaskScreenshotPolicy.change(changed,1,TaskScreenshotPolicy.DAY,4));
        refused(()->TaskScreenshotPolicy.change(changed,2,123,4));
        refused(()->TaskScreenshotPolicy.change(new JSONObject(initial.toString()).put("revision",Long.MAX_VALUE),Long.MAX_VALUE,0,4));
    }
    @Test public void exactExpiryAndLegacyRetentionStayDistinct()throws Exception {
        JSONObject newImage=TaskScreenshotPolicy.record("task","run",1,UUID.randomUUID().toString(),10,20,8,"image/jpeg",false);
        assertFalse(TaskScreenshotPolicy.expired(newImage,19));assertTrue(TaskScreenshotPolicy.expired(newImage,20));
        JSONObject legacy=TaskScreenshotPolicy.record("task","",0,UUID.randomUUID().toString(),10,0,8,"image/jpeg",true);
        assertFalse(TaskScreenshotPolicy.expired(legacy,Long.MAX_VALUE));
        refused(()->TaskScreenshotPolicy.aad(new JSONObject(newImage.toString()).put("bytes","8")));
        refused(()->TaskScreenshotPolicy.aad(new JSONObject(newImage.toString()).put("encrypted",false)));
        refused(()->TaskScreenshotPolicy.record("task","run",1,UUID.randomUUID().toString(),10,9,8,"image/jpeg",false));
    }
    @Test public void mimeComesFromActualBytes()throws Exception {
        assertEquals("image/jpeg",TaskScreenshotPolicy.mimeType(new byte[]{(byte)255,(byte)216,(byte)255}));
        assertEquals("image/png",TaskScreenshotPolicy.mimeType(new byte[]{(byte)137,80,78,71,13,10,26,10}));
        refused(()->TaskScreenshotPolicy.mimeType("not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
