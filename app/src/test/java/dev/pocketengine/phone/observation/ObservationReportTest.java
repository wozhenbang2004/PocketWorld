package dev.pocketengine.phone.observation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObservationReportTest {
    @Test public void unlabeledClickableImageOrCanvasIsNotASemanticTarget() throws Exception {
        JSONObject observation=new JSONObject().put("nodes",new JSONArray()
                .put(new JSONObject().put("clickable",true).put("enabled",true).put("className","android.widget.ImageView"))
                .put(new JSONObject().put("clickable",true).put("text","  ").put("description","\t")));
        ObservationReport.attach(observation,true,"accessibility_tree",false,"not_requested");
        assertEquals(0,observation.getJSONObject("observationSources").getInt("usefulNodes"));
    }
    @Test public void onlyUsefulNonSecretEnabledNodesCount() throws Exception {
        JSONObject observation=new JSONObject().put("nodes",new JSONArray()
                .put(new JSONObject().put("text","有用文字"))
                .put(new JSONObject().put("clickable",true))
                .put(new JSONObject().put("editable",true))
                .put(new JSONObject().put("description","保存"))
                .put(new JSONObject().put("editable",true).put("password",true).put("text","[password]"))
                .put(new JSONObject().put("clickable",true).put("enabled",false))
                .put(new JSONObject().put("text","  ")));
        ObservationReport.attach(observation,true,"accessibility_tree",false,"not_requested");
        JSONObject sources=observation.getJSONObject("observationSources");
        assertEquals(3,sources.getInt("usefulNodes"));assertFalse(sources.getBoolean("ocrAvailable"));
        assertEquals("engine_not_installed",sources.getString("ocrReason"));assertEquals("untrusted_screen_content",sources.getString("contentTrust"));
        ObservationReport.attach(observation,false,"no_accessible_root",false,"not_requested");
        assertEquals(0,observation.getJSONObject("observationSources").getInt("usefulNodes"));
    }
    @Test public void realPngBytesCountOnlyWhenExplicitlyIncluded() throws Exception {
        JSONObject image=AdaptivePhonePortTest.observation("png",0,true);assertTrue(ObservationReport.hasImage(image));
        ObservationReport.attach(image,true,"accessibility_tree",false,"not_requested");
        assertFalse(image.getJSONObject("observationSources").getBoolean("imageIncluded"));
    }
    @Test public void idsMalformedBytesWrongDimensionsOrMimeDoNotCountAsImages() throws Exception {
        JSONObject id=new JSONObject().put("screenshotId","screen-42").put("imageWidth",1).put("imageHeight",1).put("imageMimeType","image/png");
        assertFalse(ObservationReport.hasImage(id));id.put("imageBase64","AAAA");assertFalse(ObservationReport.hasImage(id));
        id.put("imageBase64",AdaptivePhonePortTest.PNG).put("imageWidth",2);assertFalse(ObservationReport.hasImage(id));
        id.put("imageWidth",1).put("imageMimeType","text/plain");assertFalse(ObservationReport.hasImage(id));
        id.put("imageMimeType","image/png").put("imageBase64","https://example.invalid/image");assertFalse(ObservationReport.hasImage(id));
    }
    @Test public void unknownAcceptanceAndCancelledGesturesRequireReconciliation() throws Exception {
        for(String code:new String[]{"dispatch_unknown","action_unknown","gesture_completion_unknown","gesture_cancelled","reconciliation_required"}) {
            JSONObject result=ObservationReport.annotateFailure(new JSONObject().put("ok",false).put("error",code).put("accepted",true));
            assertTrue(result.getBoolean("requiresPause"));assertEquals("reconcile",result.getString("nextObservationAction"));assertFalse(result.getBoolean("automaticReplayAllowed"));
        }
        JSONObject empty=ObservationReport.annotateFailure(new JSONObject().put("error","transport_failed").put("accepted",JSONObject.NULL));
        assertEquals("reconcile",empty.getString("nextObservationAction"));
    }
    @Test public void healthFailuresPauseAndStaleReferencesOnlyAskToReobserve() throws Exception {
        for(String code:new String[]{"permission_denied","screen_locked","display_lost","wrong_display","focus_isolation_lost","task_paused"}) {
            JSONObject result=ObservationReport.annotateFailure(new JSONObject().put("error",code).put("accepted",false));
            assertTrue(result.getBoolean("requiresPause"));assertEquals("pause",result.getString("nextObservationAction"));
        }
        JSONObject stale=ObservationReport.annotateFailure(new JSONObject().put("error","stale_observation").put("accepted",false));
        assertFalse(stale.getBoolean("requiresPause"));assertEquals("reobserve",stale.getString("nextObservationAction"));assertFalse(stale.getBoolean("automaticReplayAllowed"));
    }
}
