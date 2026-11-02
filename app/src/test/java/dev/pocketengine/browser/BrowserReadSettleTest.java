package dev.pocketengine.browser;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BrowserReadSettleTest {
    private static JSONObject j(Object... v)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<v.length;i+=2)out.put((String)v[i],v[i+1]);return out;}
    private final String document=UUID.randomUUID().toString(),region=UUID.randomUUID()+":r1";
    private JSONObject sample(String digest,int mutation,boolean busy,int items)throws Exception {
        return j("status","observed","documentId",document,"frameId","main","read",j("projectionSha256",digest,"facts",j("regionRef",region),"dynamic",dynamic(mutation,busy,items)));
    }
    private JSONObject dynamic(int mutation,boolean busy,int items)throws Exception{return j("scope","selected_region_and_nearest_container","busy",busy,"contentItems",items,"observerActive",true,"mutationVersion",mutation,"pagination",new JSONArray(),"paginationPartial",false,"nodesVisited",4,"networkIdleProven",false,"trust","untrusted_rendered_dom");}
    @Test public void stableRequiresActualContinuousWindowAndMutationResetsEvenIfTextReturns()throws Exception {
        BrowserReadSettle.Tracker t=new BrowserReadSettle.Tracker(j("stableMs",600));JSONObject a=sample("a".repeat(64),0,false,2);
        assertFalse(t.sample(a,0));assertFalse(t.sample(a,400));assertFalse(t.sample(sample("a".repeat(64),2,false,2),600));assertFalse(t.sample(sample("a".repeat(64),2,false,2),1000));assertTrue(t.sample(sample("a".repeat(64),2,false,2),1200));BrowserReadSettle.validateEvidence(t.evidence(true,1200));
    }
    @Test public void headerOnlyBusyIncompleteAndUnsupportedObserverCannotClaimStable()throws Exception {
        for(String issue:new String[]{"empty","busy","partial","observer"}){BrowserReadSettle.Tracker t=new BrowserReadSettle.Tracker(j());JSONObject a=sample("a".repeat(64),0,issue.equals("busy"),issue.equals("empty")?0:2);JSONObject d=a.getJSONObject("read").getJSONObject("dynamic");if(issue.equals("partial"))d.put("paginationPartial",true);if(issue.equals("observer"))d.put("observerActive",false);assertFalse(issue,t.sample(a,0));assertFalse(issue,t.sample(a,2000));assertEquals("timeout",t.evidence(false,4000).getString("status"));}
    }
    @Test public void paginationAfterDigestDoesNotTreatOldRowsAsNewPage()throws Exception {
        String before="a".repeat(64),after="b".repeat(64);BrowserReadSettle.Tracker t=new BrowserReadSettle.Tracker(j("afterProjectionSha256",before));assertFalse(t.sample(sample(before,0,false,2),0));assertFalse(t.sample(sample(before,0,false,2),2000));assertFalse(t.sample(sample(after,1,false,2),2200));assertTrue(t.sample(sample(after,1,false,2),2800));assertTrue(t.evidence(true,2800).getBoolean("projectionChanged"));
    }
    @Test public void instanceChangeIsNotSilentlyRetargeted()throws Exception {
        BrowserReadSettle.Tracker t=new BrowserReadSettle.Tracker(j());t.sample(sample("a".repeat(64),0,false,1),0);JSONObject replaced=sample("b".repeat(64),0,false,1);replaced.getJSONObject("read").getJSONObject("facts").put("regionRef",UUID.randomUUID()+":r2");assertThrows(BrowserTaskSpace.StalePage.class,()->t.sample(replaced,600));
    }
    @Test public void settleCannotAddActionsOrUnboundedWaitOrCursor()throws Exception {
        dev.pocketengine.features.plugins.PluginRules.validateArguments(j("mode","table","settle",j("timeoutMs",4000,"stableMs",600,"minItems",1,"afterProjectionSha256","a".repeat(64))),BrowserSemanticRead.schema());
        BrowserSemanticRead.validate(j("mode","table","settle",j()));assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","outline","settle",j())));assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","table","settle",j("timeoutMs",6001))));assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","table","settle",j("minItems",1.5))));assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","text","cursor",UUID.randomUUID()+":c0","settle",j())));assertThrows(IllegalArgumentException.class,()->BrowserSemanticRead.validate(j("mode","text","settle",j("script","click()"))));
    }
    @Test public void sourceRetainsDynamicAndNativeSettleWithoutUpgradingTrust()throws Exception {
        JSONObject raw=BrowserSemanticReadTest.raw("text");raw.getJSONObject("read").put("dynamic",dynamic(2,false,1));BrowserReadSettle.Tracker t=new BrowserReadSettle.Tracker(j());JSONObject a=sample("a".repeat(64),2,false,1);t.sample(a,0);t.sample(a,600);raw.getJSONObject("read").put("settle",t.evidence(true,600));JSONObject out=BrowserSemanticRead.result(raw),facts=BrowserSemanticRead.sourceFacts(out.getJSONObject("read"));BrowserSemanticRead.validateSourceFacts(facts);assertTrue(facts.getJSONObject("settle").getBoolean("conditionMet"));assertFalse(facts.getJSONObject("dynamic").getBoolean("networkIdleProven"));assertThrows(SecurityException.class,()->BrowserSemanticRead.validateSourceFacts(new JSONObject(facts.toString()).put("dynamic",dynamic(2,false,1).put("networkIdleProven",true))));
    }
}
