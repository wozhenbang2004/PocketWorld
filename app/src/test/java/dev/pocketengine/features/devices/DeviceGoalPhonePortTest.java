package dev.pocketengine.features.devices;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Scope boundary tests against the production adapter; the physical screen is an explicit fixture. */
public class DeviceGoalPhonePortTest {
    private static final String APP="dev.example.allowed",OTHER="dev.example.private";
    private static final class Physical implements PhonePort {
        JSONObject page;boolean requestedImage;int writes,clears;
        Physical()throws Exception{page=page("s1",7,true,0);}
        public boolean isConnected(){return true;}
        public JSONObject observe(boolean image,RunControl control)throws Exception{requestedImage=image;return new JSONObject(page.toString());}
        public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{writes++;return new JSONObject().put("ok",true).put("accepted",true);}
        public void clearReferences(){clears++;}
    }
    private static JSONObject page(String session,int display,boolean verified,int user)throws Exception {
        JSONObject value=new JSONObject().put("ok",true).put("sessionId",session).put("displayId",display).put("observationId","obs1").put("treeFingerprint","tree1").put("packageName",APP)
            .put("imageBase64","cHJpdmF0ZQ==").put("imageMimeType","image/png")
            .put("windows",new JSONArray().put(new JSONObject().put("windowId",1).put("packageName",APP)).put(new JSONObject().put("windowId",2).put("packageName",OTHER)))
            .put("nodes",new JSONArray().put(new JSONObject().put("id","visible").put("windowId",1).put("packageName",APP)).put(new JSONObject().put("id","hidden").put("windowId",2).put("packageName",OTHER)));
        if(verified)value.put("actualUserVerified",true).put("androidUserId",user);return value;
    }
    private static RunControl control(){RunControl original=new RunControl();original.bindHostRun("task","run",1);return original;}
    private static DeviceGoalPhonePort scoped(Physical physical,RunControl original,DeviceGoalPhonePort.Current current)throws Exception {
        JSONObject binding=new JSONObject().put("screenMode","background").put("androidUserId",0).put("packageNames",new JSONArray().put(APP)).put("allowWholeDisplayCapture",false).put("allowWholeDisplayInput",false);
        return new DeviceGoalPhonePort(physical,binding,new JSONArray().put("phone_observe").put("phone_launch_app").put("phone_click_node").put("phone_type").put("phone_tap"),original,current);
    }
    @Test public void filtersOtherWindowsNodesAndDoesNotCaptureWithoutGrant()throws Exception {
        Physical physical=new Physical();RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});JSONObject observation=port.observe(true,original);
        assertFalse(physical.requestedImage);assertFalse(observation.has("imageBase64"));assertEquals(1,observation.getJSONArray("windows").length());assertEquals("visible",observation.getJSONArray("nodes").getJSONObject(0).getString("id"));
    }
    @Test public void unknownAndroidUserNeverBecomesDefaultUserZero()throws Exception {
        Physical physical=new Physical();physical.page=page("s1",7,false,0);RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});JSONObject observation=port.observe(false,original);
        assertEquals(0,observation.getJSONArray("nodes").length());assertEquals("",observation.getString("packageName"));assertFalse(observation.optBoolean("actualUserVerified"));
        assertThrows(SecurityException.class,()->port.execute("phone_click_node",new JSONObject().put("observationId","obs1").put("nodeId","visible"),original));assertEquals(0,physical.writes);
    }
    @Test public void otherProfileIsRejectedBeforeAnyAction()throws Exception {
        Physical physical=new Physical();physical.page=page("s1",7,true,10);RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});
        assertThrows(SecurityException.class,()->port.observe(false,original));assertEquals(0,physical.writes);
    }
    @Test public void guessedPrivateNodeAndFocusFallbackCannotDispatch()throws Exception {
        Physical physical=new Physical();RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});port.observe(false,original);
        assertThrows(SecurityException.class,()->port.execute("phone_click_node",new JSONObject().put("observationId","obs1").put("nodeId","hidden"),original));
        assertThrows(SecurityException.class,()->port.execute("phone_type",new JSONObject().put("observationId","obs1").put("text","sensitive"),original));assertEquals(0,physical.writes);
    }
    @Test public void actionConsumesObservationAndCannotBeReplayed()throws Exception {
        Physical physical=new Physical();RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});port.observe(false,original);JSONObject args=new JSONObject().put("observationId","obs1").put("nodeId","visible");
        assertTrue(port.execute("phone_click_node",args,original).getBoolean("accepted"));assertThrows(SecurityException.class,()->port.execute("phone_click_node",args,original));assertEquals(1,physical.writes);
    }
    @Test public void coordinatesNeedSeparateWholeDisplayGrant()throws Exception {
        Physical physical=new Physical();RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});port.observe(false,original);
        assertThrows(SecurityException.class,()->port.execute("phone_tap",new JSONObject().put("observationId","obs1").put("x",500).put("y",500),original));assertEquals(0,physical.writes);
    }
    @Test public void displayAndSessionCannotChangeBetweenObservations()throws Exception {
        Physical physical=new Physical();RunControl original=control();DeviceGoalPhonePort port=scoped(physical,original,()->{});port.observe(false,original);physical.page=page("s2",8,true,0);
        assertThrows(SecurityException.class,()->port.observe(false,original));assertEquals(0,physical.writes);
    }
    @Test public void revokedNativeParentAndDifferentControllerCannotDispatch()throws Exception {
        Physical physical=new Physical();RunControl original=control();boolean[] revoked={false};DeviceGoalPhonePort port=scoped(physical,original,()->{if(revoked[0])throw new SecurityException("owner revoked");});port.observe(false,original);revoked[0]=true;
        assertThrows(SecurityException.class,()->port.execute("phone_click_node",new JSONObject().put("observationId","obs1").put("nodeId","visible"),original));
        assertThrows(SecurityException.class,()->port.observe(false,control()));assertEquals(0,physical.writes);
    }
}
