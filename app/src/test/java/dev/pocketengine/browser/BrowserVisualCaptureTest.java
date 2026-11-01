package dev.pocketengine.browser;

import dev.pocketengine.core.NativeToolObservation;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.j;

/** Pure capture budgets/privacy-result contracts, not actual PixelCopy or rendered image evidence. */
public final class BrowserVisualCaptureTest {
    @Test public void exactVisibleCropCannotGrowIntoAnotherWindow()throws Exception {NativeToolObservation.Crop c=BrowserVisualCapture.crop(j("x",10,"y",20,"width",300,"height",200),400,500);assertEquals(10,c.x());assertEquals(200,c.height());assertThrows(Exception.class,()->BrowserVisualCapture.crop(j("x",10,"y",20,"width",400,"height",200),400,500));}
    @Test public void boundedResizePreservesAspectWithoutUpscaling()throws Exception {int[] s=BrowserVisualCapture.imageSize(BrowserVisualCapture.crop(null,2000,2000));assertEquals(1000,s[0]);assertEquals(1000,s[1]);int[] small=BrowserVisualCapture.imageSize(BrowserVisualCapture.crop(null,100,80));assertEquals(100,small[0]);assertEquals(80,small[1]);assertTrue((long)s[0]*s[1]<=BrowserVisualCapture.MAX_PIXELS);}
    @Test public void viewportCarriesNativePageGeometryAndActualImageSize()throws Exception {JSONObject v=BrowserVisualCapture.viewport(400,600,0,800,2.5,j("x",2,"y",4,"width",200,"height",300));NativeToolObservation.Viewport n=BrowserVisualCapture.viewport(v);assertEquals(800,n.scrollYPx());assertEquals(2.5,n.scale(),0d);assertEquals(300,n.imageHeightPx());}
    @Test public void fractionalUnknownAndOutOfViewportCoordinatesAreRejected()throws Exception {for(JSONObject c:new JSONObject[]{j("x",0.5,"y",0,"width",1,"height",1),j("x",0,"y",0,"width",1,"height",1,"other",true),j("x",0,"y",0,"width",0,"height",1),j("x",-1,"y",0,"width",1,"height",1)})assertThrows(Exception.class,()->BrowserVisualCapture.crop(c,400,600));}
    static JSONObject probe()throws Exception {return j("visualProbe",true,"status","observed","mutationRevision",4,"privateControlsVisible",false,"visibleChildFrames",0,"scope","bounded_main_document_known_controls_no_value_read","completeSecretClassification",false);}
    @Test public void knownPrivateControlsUnscannedFramesAndIncompletePrivacyProbeDoNotProduceAnImage()throws Exception {for(JSONObject p:new JSONObject[]{probe().put("privateControlsVisible",true),probe().put("visibleChildFrames",1),j("visualProbe",true,"status","unavailable","code","visual_privacy_scan_budget"),probe().put("completeSecretClassification",true)})assertThrows(Exception.class,()->BrowserVisualCapture.privacy(p));assertEquals(4,BrowserVisualCapture.privacy(probe()).getInt("mutationRevision"));}
    @Test public void fixedProbeNeverReadsInputValuesOrChangesThePage()throws Exception {String s=BrowserVisualCapture.privacyScript("native_immutable_key");for(String forbidden:new String[]{".value","innerHTML","innerText","fetch(",".click(",".submit(",".focus(","navigator."})assertFalse(forbidden,s.contains(forbidden));assertTrue(s.contains("e.shadowRoot.children"));assertTrue(s.contains("assignedElements"));assertTrue(s.contains("privateControlsVisible"));assertTrue(s.contains("completeSecretClassification:false"));}
}
