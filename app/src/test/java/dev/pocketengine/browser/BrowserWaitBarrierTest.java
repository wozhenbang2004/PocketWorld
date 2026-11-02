package dev.pocketengine.browser;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class BrowserWaitBarrierTest {
    static JSONObject target()throws Exception{return new JSONObject().put("regionRef",UUID.randomUUID()+":r1").put("state","present");}
    static JSONObject actual(boolean met,String instance)throws Exception{return new JSONObject().put("status","observed").put("code","target_state_read").put("matched",met).put("actual",new JSONObject().put("instance",instance));}
    @Test public void allConditionsAndTheirStableInstancesMustHoldTogether()throws Exception {
        var tracker=new BrowserWaitBarrier.Tracker(new JSONObject().put("targets",new JSONArray().put(target()).put(target())).put("stableMs",400));
        assertFalse(tracker.sample(new JSONArray().put(actual(true,"1")).put(actual(false,"2")),0));assertFalse(tracker.sample(new JSONArray().put(actual(true,"1")).put(actual(true,"2")),100));assertFalse(tracker.sample(new JSONArray().put(actual(true,"1")).put(actual(true,"3")),500));assertTrue(tracker.sample(new JSONArray().put(actual(true,"1")).put(actual(true,"3")),900));assertFalse(tracker.receipt(true,900,3000).getBoolean("networkIdleProven"));
    }
    @Test public void unknownIsNotAnAbsentOrMatchedConditionAndNoFrameIsSilentlyAdmitted()throws Exception {
        var tracker=new BrowserWaitBarrier.Tracker(new JSONObject().put("targets",new JSONArray().put(target())).put("mode","any"));assertFalse(tracker.sample(new JSONArray().put(new JSONObject().put("matched",JSONObject.NULL)),0));
        assertThrows(Exception.class,()->BrowserWaitBarrier.validate(new JSONObject().put("targets",new JSONArray().put(target().put("frameRef",UUID.randomUUID().toString())))));
    }
}
