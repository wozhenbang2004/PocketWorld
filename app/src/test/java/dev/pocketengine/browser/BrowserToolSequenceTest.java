package dev.pocketengine.browser;
import dev.pocketengine.core.NativeToolPlan;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class BrowserToolSequenceTest {
    static JSONObject target(String ref,String key)throws Exception{return new JSONObject().put("ref",ref).put("nodeKey",key).put("tag","BUTTON").put("type","button").put("id","toggle").put("text","展开");}
    static JSONObject observed(String ref,String key)throws Exception{return new JSONObject().put("observationId","fresh-observation").put("tabId","tab").put("navigation",4).put("url","https://read.example/").put("text","真实控件").put("runnerDelivered",true).put("presentedElementCount",1).put("elementPreviews",new JSONArray().put(target(ref,key)));}
    static JSONObject args()throws Exception{return new JSONObject().put("tabId","tab").put("steps",new JSONArray().put(new JSONObject().put("action","observe")).put(new JSONObject().put("action","click").put("ref","n0")));}
    @Test public void originalNodeMayGetANewRefButAReplacementWithSameLabelIsRejected()throws Exception {
        JSONObject args=args(),pin=new JSONObject().put("navigation",4).put("sequenceTargets",BrowserToolSequence.pinTargets(args,observed("n0","k1")));
        NativeToolPlan.Resolved resolved=BrowserToolSequence.resolve(args,pin,1,observed("n7","k1"),()->{});assertNotNull(resolved);
        assertThrows(SecurityException.class,()->BrowserToolSequence.resolve(args,pin,1,observed("n0","k2"),()->{}));
        assertThrows(SecurityException.class,()->BrowserToolSequence.resolve(args,pin,1,observed("n0","k1").put("navigation",5),()->{}));
    }
    @Test public void writeWithoutOriginalRunnerObservationAndUnboundedOperationsAreRejected()throws Exception {
        assertThrows(Exception.class,()->BrowserToolSequence.validate(new JSONObject().put("steps",new JSONArray().put(new JSONObject().put("action","click").put("ref","n0")))));
        for(String action:new String[]{"script","navigate","upload","network"})assertThrows(Exception.class,()->BrowserToolSequence.validate(new JSONObject().put("steps",new JSONArray().put(new JSONObject().put("action",action)))));
        assertThrows(Exception.class,()->BrowserToolSequence.pinTargets(args(),observed("n0","k1").put("readOnlyEvidence",true)));
    }
}
