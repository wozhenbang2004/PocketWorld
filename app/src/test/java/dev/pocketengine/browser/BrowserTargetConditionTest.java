package dev.pocketengine.browser;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure predicate/evidence/schema contracts. Not rendered DOM or device acceptance. */
public final class BrowserTargetConditionTest {
    static JSONObject j(Object... values)throws Exception {JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    final String document=UUID.randomUUID().toString(),region=document+":r1";
    JSONObject target(String state)throws Exception {JSONObject out=j("regionRef",region,"state",state);if(state.equals("text")||state.equals("value"))out.put("value","已保存");if(state.equals("checked")||state.equals("enabled"))out.put("expected",true);return out;}
    JSONObject probe(Object matched,String instance,String value)throws Exception {return j("status",matched==JSONObject.NULL?"unavailable":"observed","code",matched==JSONObject.NULL?"target_scope_incomplete":"target_state_read","matched",matched,"actual",j("instance",instance,"value",value));}
    JSONObject raw(Object matched)throws Exception {JSONObject value=probe(matched,"1","已保存");value.getJSONObject("actual").put("present",true).put("valueLength",3).put("valueTruncated",false);return value.put("targetProbe",true).put("coverage",j("nodesVisited",3,"textCharsVisited",6,"queuedPeak",1,"layoutChecks",1,"partial",false,"reasons",new JSONArray()));}
    @Test public void sixRestrictedStatesAndExplicitUniqueChildSelectorValidate()throws Exception {
        for(String state:new String[]{"present","absent","text","value","checked","enabled"})BrowserTargetCondition.validate(target(state));BrowserTargetCondition.validate(target("value").put("selector",j("role","textbox","name","方案")));BrowserTargetCondition.validate(target("value").put("value",""));
    }
    @Test public void noScriptsCssIdentityGrantOrEmptySelectorsEnterTheProtocol()throws Exception {
        for(JSONObject extra:new JSONObject[]{j("script","fetch('/send')"),j("css","button"),j("taskId",UUID.randomUUID().toString()),j("grant",true)}){JSONObject input=target("present");for(var it=extra.keys();it.hasNext();){String key=it.next();input.put(key,extra.get(key));}assertThrows(Exception.class,()->BrowserTargetCondition.validate(input));}
        assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("present").put("selector",j())));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("present").put("selector",j("role","unknown-role"))));
    }
    @Test public void ComparisonFieldsHaveExactTypesAndCannotBeMixedWithPresence()throws Exception {
        assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("present").put("expected",true)));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("checked").put("expected","true")));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("text").put("value","")));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("value").put("stableMs",1.5)));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("value").put("stableMs",1001)));
    }
    @Test public void scriptNeedsTheNativeDocumentNamespaceAndContainsNoActionImplementation()throws Exception {
        String code=BrowserTargetCondition.script(target("text"),"private-native-key","original-run-page-pause",document);for(String token:new String[]{".click(",".dispatchEvent(",".focus(",".scrollBy(",".loadUrl(",".innerText","fetch("})assertFalse(token,code.contains(token));assertThrows(SecurityException.class,()->BrowserTargetCondition.script(target("text"),"key","epoch",UUID.randomUUID().toString()));
    }
    @Test public void projectionContainsOnlyActualBoundedEvidenceNotExtraPageAuthority()throws Exception {
        JSONObject raw=raw(true);raw.getJSONObject("actual").put("grant","forged");raw.put("approvalId","forged");JSONObject projected=BrowserTargetCondition.project(raw,target("value"),document);assertFalse(projected.toString().contains("forged"));assertEquals(region,projected.getString("regionRef"));assertFalse(projected.getBoolean("verifiedBusinessSuccess"));assertTrue(projected.getString("evidenceSha256").matches("[a-f0-9]{64}"));assertFalse(projected.getJSONObject("coverage").getBoolean("wholePageComplete"));assertEquals("bounded_whitespace_normalized_dom_text",projected.getJSONObject("coverage").getString("textProjection"));
    }
    @Test public void unknownOrPartialCannotBeInventedAsAFalseOrFullCondition()throws Exception {
        JSONObject missing=raw(JSONObject.NULL);missing.getJSONObject("coverage").put("partial",true).put("reasons",new JSONArray().put("node_budget"));JSONObject out=BrowserTargetCondition.project(missing,target("value"),document);assertTrue(out.isNull("matched"));assertTrue(out.getJSONObject("coverage").getBoolean("partial"));assertThrows(Exception.class,()->BrowserTargetCondition.project(raw(false).put("status","unavailable"),target("value"),document));assertThrows(Exception.class,()->BrowserTargetCondition.project(raw("true"),target("value"),document));
    }
    @Test public void budgetOverrunsAndOversizedValuesAreRejectedRatherThanClippedToInventEvidence()throws Exception {
        JSONObject over=raw(true);over.getJSONObject("coverage").put("nodesVisited",601);assertThrows(Exception.class,()->BrowserTargetCondition.project(over,target("value"),document));JSONObject value=raw(true);value.getJSONObject("actual").put("value","x".repeat(2049));assertThrows(Exception.class,()->BrowserTargetCondition.project(value,target("value"),document));
    }
    @Test public void contradictoryMatchedFlagAndBrokenUnicodeCannotBecomeNativeEvidence()throws Exception {
        JSONObject wrong=raw(true);wrong.getJSONObject("actual").put("value","保存中");assertThrows(Exception.class,()->BrowserTargetCondition.project(wrong,target("value"),document));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("value").put("value", "\ud83d")));assertThrows(Exception.class,()->BrowserTargetCondition.validate(target("present").put("selector",j("name","\u2003"))));BrowserTargetCondition.validate(target("value").put("value","方案🙂"));
    }
    @Test public void noTargetNeverSatisfiesAStablePositiveWait()throws Exception {
        BrowserTargetCondition.Tracker tracker=new BrowserTargetCondition.Tracker(target("present").put("stableMs",200));assertFalse(tracker.accept(probe(false,"",""),1000));assertFalse(tracker.accept(probe(false,"",""),2000));assertFalse(tracker.receipt(false,true,2000).getBoolean("conditionMet"));
    }
    @Test public void stableWindowTracksTheActualTargetInstanceAndReadStateNotThePageClock()throws Exception {
        BrowserTargetCondition.Tracker tracker=new BrowserTargetCondition.Tracker(target("value").put("stableMs",200));assertFalse(tracker.accept(probe(true,"a","41"),1000));assertFalse(tracker.accept(probe(true,"b","41"),1200));assertFalse(tracker.accept(probe(true,"b","42"),1400));assertTrue(tracker.accept(probe(true,"b","42"),1600));JSONObject receipt=tracker.receipt(true,false,1600);assertTrue(receipt.getBoolean("conditionMet"));assertEquals(4,receipt.getInt("samples"));assertFalse(receipt.getBoolean("verifiedBusinessSuccess"));assertFalse(receipt.getBoolean("inputDispatched"));
    }
    @Test public void unrelatedCoverageCountersDoNotResetAnUnchangedTargetCondition()throws Exception {
        BrowserTargetCondition.Tracker tracker=new BrowserTargetCondition.Tracker(target("checked").put("stableMs",200));JSONObject first=probe(true,"same","on").put("unrelatedPageClock",100),second=probe(true,"same","on").put("unrelatedPageClock",200);assertFalse(tracker.accept(first,1000));assertTrue(tracker.accept(second,1200));
    }
    @Test public void falseUnknownAndClockRegressionCannotAccumulateAStableWindow()throws Exception {
        BrowserTargetCondition.Tracker tracker=new BrowserTargetCondition.Tracker(target("value").put("stableMs",200));assertFalse(tracker.accept(probe(true,"same","41"),1000));assertFalse(tracker.accept(probe(JSONObject.NULL,"same","41"),1100));assertFalse(tracker.accept(probe(true,"same","41"),1200));assertFalse(tracker.accept(probe(true,"same","41"),900));assertFalse(tracker.accept(probe(false,"same","41"),1300));assertFalse(tracker.accept(probe(true,"same","41"),1400));
    }
    @Test public void unknownAndTimeoutAreDifferentAndNeitherPermitsReplayingWrites()throws Exception {
        BrowserTargetCondition.Tracker missing=new BrowserTargetCondition.Tracker(target("value"));missing.accept(probe(JSONObject.NULL,"",""),1000);JSONObject unknown=missing.receipt(false,true,2000);assertTrue(unknown.isNull("conditionMet"));assertEquals("unavailable",unknown.getString("status"));assertFalse(unknown.getBoolean("automaticReplayAllowed"));BrowserTargetCondition.Tracker absent=new BrowserTargetCondition.Tracker(target("value"));absent.accept(probe(false,"same","old"),1000);JSONObject timeout=absent.receipt(false,true,2000);assertFalse(timeout.getBoolean("conditionMet"));assertEquals("condition_timeout",timeout.getString("code"));
    }
    @Test public void nativeProbeBudgetCannotTurnIntoAnUnboundedConditionLoop()throws Exception {
        BrowserTargetCondition.Tracker tracker=new BrowserTargetCondition.Tracker(target("present"));for(int i=0;i<BrowserTargetCondition.MAX_PROBES;i++)assertFalse(tracker.accept(probe(false,"",""),i*200L));assertTrue(tracker.exhausted());assertThrows(Exception.class,()->tracker.accept(probe(false,"",""),7000));assertTrue(tracker.receipt(false,false,7000).isNull("conditionMet"));assertEquals("target_wait_probe_budget",tracker.receipt(false,false,7000).getString("code"));
    }
    @Test public void originalWaitNameOldConditionsAndPreparedForwardingStayCompatible()throws Exception {
        try(BrowserTaskSpaceTest.Harness h=new BrowserTaskSpaceTest.Harness(true)){
            String tab=h.open("async-form");h.port.validate("browser_task_wait",j("tabId",tab,"condition","load"));h.port.validate("browser_task_wait",j("tabId",tab,"condition","url","value","https://example.com/done"));h.port.validate("browser_task_wait",j("tabId",tab,"condition","text","value","done"));
            JSONObject args=j("tabId",tab,"condition","target","target",target("checked"));h.port.validate("browser_task_wait",args);h.invoke("wait",args);assertEquals("wait",h.driver.lastExecuteCommand);assertEquals(region,h.driver.lastExecuteArgs.getJSONObject("target").getString("regionRef"));assertEquals(0,h.driver.mutableDispatches);
            assertThrows(Exception.class,()->h.port.validate("browser_task_wait",j("tabId",tab,"condition","target","target",target("value"),"value","outside")));assertThrows(Exception.class,()->h.port.validate("browser_task_wait",j("tabId",tab,"condition","load","target",target("value"))));
        }
    }
}
