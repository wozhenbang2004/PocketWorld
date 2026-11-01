package dev.pocketengine.phone.observation;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveTreeReadTest {
    private final List<Boolean> images=new ArrayList<>();private int clears,actions;
    private JSONObject next=PhoneTreeQueryTest.tree("first");
    public AdaptiveTreeReadTest()throws Exception{}
    private final PhonePort source=new PhonePort(){
        public boolean isConnected(){return true;}public void clearReferences(){clears++;}
        public JSONObject observe(boolean image,RunControl control)throws Exception{images.add(image);return new JSONObject(next.toString());}
        public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{actions++;return new JSONObject().put("ok",true).put("accepted",true);}
    };
    private RunControl control(){RunControl c=new RunControl();c.bindHostRun("t","r",1);return c;}
    private AdaptivePhonePort port(){return new AdaptivePhonePort(source,new AdaptivePhonePort.Scope("t","r",1,"s",5),true,true,()->1000);}
    @Test public void queryUsesExactCurrentObservationWithoutRecaptureOrAction()throws Exception{
        AdaptivePhonePort p=port();RunControl c=control();p.observe(true,c);
        JSONObject args=new JSONObject().put("observationId","first").put("selector",new JSONObject().put("parentId","n3").put("text","编辑"));
        JSONObject out=p.queryTree(args,c);assertEquals(1,out.getInt("total"));assertEquals(1,images.size());assertFalse(images.get(0));assertEquals(0,actions);
        args.put("observationId","old");assertEquals("stale_observation",p.queryTree(args,c).getString("error"));
    }
    @Test public void conditionUsesOnlyNodesAndReplacesOldRefWithFreshOne()throws Exception{
        AdaptivePhonePort p=port();RunControl c=control();p.observe(true,c);next=PhoneTreeQueryTest.tree("fresh");
        JSONObject out=p.conditionWait(new JSONObject().put("kind","present").put("selector",new JSONObject().put("text","编辑")).put("timeoutMs",100),c);
        assertEquals("matched",out.getString("status"));assertEquals("fresh",out.getJSONObject("observation").getString("observationId"));
        assertEquals(2,images.size());assertFalse(images.contains(true));assertEquals(0,actions);
        assertEquals("stale_observation",p.queryTree(new JSONObject().put("observationId","first").put("selector",new JSONObject()),c).getString("error"));
    }
    @Test public void wrongSessionIsRejectedWithoutMainDisplayFallback()throws Exception{
        AdaptivePhonePort p=port();RunControl c=control();next.put("sessionId","other").put("displayId",0);
        JSONObject out=p.conditionWait(new JSONObject().put("kind","present").put("selector",new JSONObject()).put("timeoutMs",100),c);
        assertEquals("unavailable",out.getString("status"));assertFalse(out.has("observation"));assertEquals(0,actions);assertEquals(1,images.size());
    }
    @Test public void epochAndPauseAreStillNativeNotModelSupplied()throws Exception{
        AdaptivePhonePort p=port();RunControl c=control();p.observe(true,c);RunControl other=new RunControl();other.bindHostRun("t","r",2);
        assertEquals("identity_mismatch",p.queryTree(new JSONObject().put("observationId","first").put("selector",new JSONObject()),other).getString("error"));
        c.pause();JSONObject out=p.conditionWait(new JSONObject().put("kind","present").put("selector",new JSONObject()).put("timeoutMs",100),c);
        assertEquals("task_paused",out.getString("error"));assertEquals(1,images.size());
    }
}
