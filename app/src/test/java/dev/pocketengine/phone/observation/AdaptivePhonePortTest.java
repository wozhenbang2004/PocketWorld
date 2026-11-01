package dev.pocketengine.phone.observation;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.AdaptiveObservationPolicy.Need;

public class AdaptivePhonePortTest {
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private long now = 1000;
    private RunControl control() { RunControl c = new RunControl(); c.bindHostRun("task", "run", 2); return c; }
    private AdaptivePhonePort port(Fixture source, boolean images, boolean vision) {
        return new AdaptivePhonePort(source, new AdaptivePhonePort.Scope("task", "run", 2, "session", 5), images, vision, () -> now);
    }
    static JSONObject observation(String id, int count, boolean image) throws Exception {
        JSONArray nodes = new JSONArray();
        for (int i=0;i<count;i++) nodes.put(new JSONObject().put("id","n"+i).put("text","继续").put("clickable",true).put("enabled",true));
        JSONObject out = new JSONObject().put("ok",true).put("observationId",id).put("displayId",5).put("sessionId","session")
                .put("observedAtUptimeMs",1000).put("pauseRevision",0).put("nodes",nodes).put("truncated",false);
        if(image) out.put("imageBase64",PNG).put("imageMimeType","image/png").put("imageWidth",1).put("imageHeight",1);
        return ObservationReport.attach(out,true,"accessibility_tree",image,image?"native_frame":"not_requested");
    }
    private static JSONObject args(String id) throws Exception { return new JSONObject().put("observationId",id).put("nodeId","n0").put("x",200).put("y",300); }
    private static void error(JSONObject result, String code) { assertFalse(result.toString(),result.optBoolean("ok")); assertEquals(code,result.optString("error")); }
    @Test public void completeTreeDoesNotCaptureOrSendImages() throws Exception {
        Fixture f=new Fixture(observation("nodes",2,false)); AdaptivePhonePort p=port(f,true,true);
        JSONObject result=p.observe(true,control());
        assertEquals(Arrays.asList(false),f.includes);assertFalse(result.has("imageBase64"));
        assertEquals("nodes",result.getJSONObject("observationDecision").getString("request"));
        assertEquals(2,result.getJSONObject("observationBinding").getInt("epoch"));
        assertEquals("run",result.getJSONObject("observationBinding").getString("runId"));
    }
    @Test public void imagePageUpgradesOnceAndUsesNewObservation() throws Exception {
        Fixture f=new Fixture(observation("node-pass",0,false),observation("pixels",0,true));AdaptivePhonePort p=port(f,true,true);RunControl c=control();
        JSONObject image=p.observe(true,c);
        assertEquals(Arrays.asList(false,true),f.includes);assertEquals("pixels",image.getString("observationId"));
        assertEquals("vision",image.getJSONObject("observationDecision").getString("request"));assertTrue(ObservationReport.hasImage(image));
        error(p.execute("phone_tap",args("node-pass"),c),"stale_observation");assertEquals(0,f.executions);
        JSONObject receipt=p.execute("phone_tap",args("pixels"),c);assertTrue(receipt.getBoolean("accepted"));
        assertEquals("pixels",receipt.getJSONObject("observationBinding").getString("observationId"));
        assertEquals("session",receipt.getJSONObject("observationScope").getString("sessionId"));assertEquals(1,f.executions);
    }
    @Test public void unlabeledClickablePageRequestsFreshPixelsInsteadOfKeepingNodes() throws Exception {
        JSONObject unlabeled=observation("unlabeled",1,false);
        unlabeled.getJSONArray("nodes").getJSONObject(0).remove("text");
        Fixture f=new Fixture(unlabeled,observation("pixels",0,true));AdaptivePhonePort p=port(f,true,true);
        JSONObject result=p.observe(true,control());
        assertEquals(Arrays.asList(false,true),f.includes);assertEquals("pixels",result.getString("observationId"));
        assertEquals("vision",result.getJSONObject("observationDecision").getString("request"));
        assertEquals("no_useful_nodes",result.getJSONObject("observationDecision").getString("reason"));
        assertEquals(0,f.executions);
    }
    @Test public void screenshotSettingAndExplicitNodeOnlyRequestAreBothCeilings() throws Exception {
        for(boolean configured:new boolean[]{false,true}){
            Fixture f=new Fixture(observation("nodes",0,false));AdaptivePhonePort p=port(f,configured,true);
            JSONObject out=p.observe(false,control());error(out,"screenshots_disabled");assertTrue(out.getBoolean("requiresPause"));
            assertEquals(Arrays.asList(false),f.includes);assertFalse(out.has("imageBase64"));
        }
    }
    @Test public void disabledScreenshotsRemainDisabledThroughPhoneObserveTool() throws Exception {
        Fixture f=new Fixture(observation("first",1,false),observation("second",1,false));AdaptivePhonePort p=port(f,false,true);RunControl c=control();
        p.observe(false,c);JSONObject second=p.execute("phone_observe",new JSONObject(),c);
        assertFalse(second.has("imageBase64"));assertEquals(Arrays.asList(false,false),f.includes);
    }
    @Test public void missingVisualEngineIsHonestAndDoesNotCapture() throws Exception {
        Fixture f=new Fixture(observation("nodes",0,false));JSONObject out=port(f,true,false).observe(true,control());
        error(out,"visual_engines_unavailable");assertEquals(Arrays.asList(false),f.includes);
    }
    @Test public void actualTreeAbsenceCanUsePixelsWithoutInventingNodes() throws Exception {
        JSONObject a=observation("missing",0,false),b=observation("pixels",0,true);
        a.getJSONObject("observationSources").put("treeAvailable",false);b.getJSONObject("observationSources").put("treeAvailable",false);
        Fixture f=new Fixture(a,b);JSONObject out=port(f,true,true).observe(true,control());
        assertEquals(0,out.getJSONArray("nodes").length());assertTrue(ObservationReport.hasImage(out));
        assertFalse(out.getJSONObject("observationSources").getBoolean("ocrAvailable"));
    }
    @Test public void knownRejectedActionUpgradesObservationButDoesNotRepeatAction() throws Exception {
        for(boolean coded:new boolean[]{true,false}) {
        Fixture f=new Fixture(observation("first",1,false),observation("second",1,false),observation("pixels",1,true));
        AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        f.receipt=new JSONObject().put("ok",false).put("accepted",false);
        if(coded)f.receipt.put("error","not_clickable");
        p.execute("phone_click_node",args("first"),c);JSONObject upgraded=p.observe(true,c);
        assertEquals(1,f.executions);assertEquals(Arrays.asList(false,false,true),f.includes);
        assertEquals("action_rejected",upgraded.getJSONObject("observationDecision").getString("reason"));
        }
    }
    @Test public void acceptedRequestDoesNotClaimEffectOrTriggerActionReplay() throws Exception {
        Fixture f=new Fixture(observation("first",1,false),observation("after",1,false));AdaptivePhonePort p=port(f,true,true);RunControl c=control();
        p.observe(true,c);JSONObject receipt=p.execute("phone_click_node",args("first"),c);assertFalse(receipt.getBoolean("verified"));
        p.observe(true,c);assertEquals(1,f.executions);assertEquals(Arrays.asList(false,false),f.includes);
    }
    @Test public void unknownEffectSurvivesClearReferencesAndPreventsAllNewDispatch() throws Exception {
        Fixture f=new Fixture(observation("first",1,false));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        f.receipt=new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","action_unknown");
        JSONObject unknown=p.execute("phone_click_node",args("first"),c);assertEquals("reconcile",unknown.getString("nextObservationAction"));
        p.clearReferences();error(p.observe(true,c),"reconciliation_required");error(p.execute("phone_launch_app",new JSONObject(),c),"reconciliation_required");
        error(p.requestUpgrade("first",Need.TARGET_MISSING,c),"reconciliation_required");assertEquals(1,f.executions);assertEquals(1,f.includes.size());
    }
    @Test public void timeoutExceptionAfterDispatchIsUnknownAndNotRetried() throws Exception {
        Fixture f=new Fixture(observation("first",1,false));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);f.throwOnExecute=true;
        JSONObject result=p.execute("phone_click_node",args("first"),c);assertTrue(result.isNull("accepted"));assertTrue(result.getBoolean("requiresPause"));
        error(p.observe(true,c),"reconciliation_required");assertEquals(1,f.executions);
    }
    @Test public void missingTargetUpgradeIsBoundToCurrentObservation() throws Exception {
        Fixture f=new Fixture(observation("first",1,false),observation("second",1,false),observation("pixels",1,true));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        error(p.requestUpgrade("old",Need.TARGET_MISSING,c),"stale_observation");assertEquals(1,f.includes.size());
        JSONObject out=p.requestUpgrade("first",Need.TARGET_MISSING,c);assertEquals("pixels",out.getString("observationId"));assertTrue(ObservationReport.hasImage(out));assertEquals(0,f.executions);
    }
    @Test public void coordinatesFromNodeOnlyObservationAreRejectedBeforeDispatch() throws Exception {
        Fixture f=new Fixture(observation("first",1,false),observation("second",1,false),observation("pixels",1,true));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        error(p.execute("phone_tap",args("first"),c),"coordinate_unverifiable");assertEquals(0,f.executions);
        assertEquals("vision",p.observe(true,c).getJSONObject("observationDecision").getString("request"));
    }
    @Test public void oldSessionOrWrongDisplayIsPausedWithoutImageCapture() throws Exception {
        for(boolean wrongDisplay:new boolean[]{false,true}){
            JSONObject out=observation("wrong",1,false);if(wrongDisplay)out.put("displayId",0);else out.put("sessionId","replaced");
            Fixture f=new Fixture(out);JSONObject result=port(f,true,true).observe(true,control());error(result,"identity_mismatch");assertTrue(result.getBoolean("requiresPause"));assertEquals(1,f.includes.size());
        }
    }
    @Test public void lockPermissionAndFocusErrorsPauseWithActualCause() throws Exception {
        for(String code:new String[]{"screen_locked","permission_denied","focus_isolation_lost","display_lost","service_disconnected"}){
            Fixture f=new Fixture(new JSONObject().put("ok",false).put("error",code));JSONObject result=port(f,true,true).observe(true,control());
            error(result,code);assertTrue(result.getBoolean("requiresPause"));assertEquals(1,f.includes.size());
        }
    }
    @Test public void transientPermissionRecoveryCannotResumeAfterSafetyFailure() throws Exception {
        Fixture f=new Fixture(observation("first",1,false),observation("recovered",1,false));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        f.receipt=new JSONObject().put("ok",false).put("accepted",false).put("error","focus_isolation_lost");
        assertTrue(p.execute("phone_click_node",args("first"),c).getBoolean("requiresPause"));p.clearReferences();
        error(p.observe(true,c),"focus_isolation_lost");error(p.execute("phone_launch_app",new JSONObject(),c),"focus_isolation_lost");
        assertEquals(1,f.includes.size());assertEquals(1,f.executions);
    }
    @Test public void pauseResumeAndExpiredClockInvalidateActions() throws Exception {
        Fixture f=new Fixture(observation("first",1,false));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        c.pause();error(p.execute("phone_click_node",args("first"),c),"task_paused");c.resume();error(p.execute("phone_click_node",args("first"),c),"stale_observation");assertEquals(0,f.executions);
        Fixture f2=new Fixture(observation("first",1,false));AdaptivePhonePort p2=port(f2,true,true);RunControl c2=control();p2.observe(true,c2);now=21001;
        error(p2.execute("phone_click_node",args("first"),c2),"stale_observation");assertEquals(0,f2.executions);
    }
    @Test public void returnedObservationFromBeforePauseIsDiscarded() throws Exception {
        Fixture f=new Fixture(observation("old",1,false));RunControl c=control();c.pause();c.resume();
        JSONObject out=port(f,true,true).observe(true,c);error(out,"stale_observation");assertEquals(1,f.includes.size());
    }
    @Test public void differentHostRunEpochOrControlInstanceCannotUseReferences() throws Exception {
        Fixture f=new Fixture(observation("first",1,false));AdaptivePhonePort p=port(f,true,true);RunControl c=control();p.observe(true,c);
        error(p.execute("phone_click_node",args("first"),control()),"identity_mismatch");
        RunControl foreign=new RunControl();foreign.bindHostRun("task","run",3);error(p.observe(true,foreign),"identity_mismatch");assertEquals(1,f.includes.size());assertEquals(0,f.executions);
        Fixture unbound=new Fixture();error(port(unbound,true,true).observe(true,new RunControl()),"identity_mismatch");assertEquals(0,unbound.includes.size());
    }
    @Test public void cancellationStopsBeforeObservationOrAction() throws Exception {
        Fixture f=new Fixture();RunControl c=control();c.cancel();
        try{port(f,true,true).observe(true,c);fail();}catch(InterruptedException expected){}
        assertEquals(0,f.includes.size());assertEquals(0,f.executions);
    }
    @Test public void emptyDisplayOnlyAllowsListingAndLaunch() throws Exception {
        JSONObject empty=observation("empty",0,false).put("screenState","display_has_no_frame");Fixture f=new Fixture(empty);AdaptivePhonePort p=port(f,false,false);RunControl c=control();
        assertEquals("empty_display",p.observe(false,c).getJSONObject("observationDecision").getString("request"));
        error(p.execute("phone_back",args("empty"),c),"coordinate_unverifiable");assertEquals(0,f.executions);
        assertTrue(p.execute("phone_launch_app",new JSONObject().put("packageName","test.app"),c).getBoolean("accepted"));assertEquals(1,f.executions);
    }
    @Test public void malformedImageOrScreenshotIdNeverBecomesVision() throws Exception {
        JSONObject fake=observation("fake",0,false).put("screenshotId","not-pixels").put("imageBase64","AAAA").put("imageMimeType","image/png").put("imageWidth",1).put("imageHeight",1);
        fake.getJSONObject("observationSources").put("imageIncluded",true);
        Fixture f=new Fixture(observation("nodes",0,false),fake);JSONObject out=port(f,true,true).observe(true,control());
        error(out,"image_unavailable");assertFalse(out.has("imageBase64"));assertEquals(2,f.includes.size());
    }
    @Test public void sourceCannotInjectImageIntoNodeOnlyPassAndMissingMetadataFailsClosed() throws Exception {
        Fixture f=new Fixture(observation("first",1,true));JSONObject result=port(f,true,true).observe(true,control());assertFalse(result.has("imageBase64"));
        JSONObject missing=observation("missing",1,false);missing.remove("observationSources");Fixture f2=new Fixture(missing);
        error(port(f2,true,true).observe(true,control()),"observation_metadata_missing");assertEquals(1,f2.includes.size());
    }
    private static final class Fixture implements PhonePort {
        final ArrayDeque<JSONObject> observations=new ArrayDeque<>();final List<Boolean> includes=new ArrayList<>();int executions;boolean throwOnExecute;
        JSONObject receipt;
        Fixture(JSONObject... observations)throws Exception {this.observations.addAll(Arrays.asList(observations));receipt=new JSONObject().put("ok",true).put("accepted",true).put("verified",false);}
        @Override public boolean isConnected(){return true;}
        @Override public JSONObject observe(boolean includeImage,RunControl control)throws Exception{control.check();includes.add(includeImage);if(observations.isEmpty())throw new AssertionError("Unexpected extra observation");return new JSONObject(observations.remove().toString());}
        @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();executions++;if(throwOnExecute)throw new java.io.IOException("lost receipt");return new JSONObject(receipt.toString());}
        @Override public void clearReferences(){}
    }
}
