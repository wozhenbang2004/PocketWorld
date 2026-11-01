package dev.pocketengine.phone.observation;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.PhoneCollectionFixtures.*;

public final class PhoneTreeCollectionTest {
    private PhoneTreeCollection.State cursor(String... values)throws Exception{return PhoneTreeCollection.start(start("first"),tree("first",values),"native-scope",1000);}
    @Test public void orderedDistinctOverlapCollectsThreePagesWithOriginalReceiptEvidence()throws Exception {
        PhoneTreeCollection.State c=cursor("A","B","C");JSONObject second=operation(c,"second");assertEquals(4,c.advance(tree("second","B","C","D"),second).getInt("rowCount"));JSONObject third=operation(c,"third");JSONObject out=c.advance(tree("third","C","D","E"),third);assertEquals(5,out.getInt("rowCount"));assertEquals("E",value(out,4));assertEquals(2,out.getInt("scrollOperations"));assertEquals(3,out.getJSONArray("sources").length());assertEquals(2,out.getJSONArray("overlapEvidence").getJSONObject(0).getInt("orderedRowsOverlapped"));assertFalse(out.getBoolean("wholeListComplete"));
    }
    @Test public void identicalItemsWithinAViewportSurviveOrderedOverlap()throws Exception {
        PhoneTreeCollection.State c=cursor("A","same","same","C");JSONObject out=c.advance(tree("next","same","C","D"),operation(c,"next"));assertEquals(5,out.getInt("rowCount"));assertEquals("same",value(out,1));assertEquals("same",value(out,2));
    }
    @Test public void singleOrRepeatedValueOverlapStopsWithUndeduplicatedEvidence()throws Exception {
        PhoneTreeCollection.State c=cursor("A","same");JSONObject out=c.advance(tree("next","same","B"),operation(c,"next"));assertEquals("ambiguous_overlap",out.getString("stoppedBecause"));assertEquals(2,out.getInt("rowCount"));assertTrue(out.getJSONObject("unmergedViewport").getBoolean("notMergedOrDeduplicated"));assertEquals(2,out.getJSONObject("unmergedViewport").getJSONArray("rows").length());assertFalse(out.has("nextScroll"));assertFalse(out.has("observation"));
    }
    @Test public void reorderedRowsDoNotSilentlyBecomeFreshItems()throws Exception {PhoneTreeCollection.State c=cursor("A","B","C");JSONObject out=c.advance(tree("next","B","A","D"),operation(c,"next"));assertEquals("order_changed_or_unproven_overlap",out.getString("stoppedBecause"));assertEquals(3,out.getInt("rowCount"));}
    @Test public void acceptedScrollWithSameTreeStopsAsNoProgressNotEndOfList()throws Exception {PhoneTreeCollection.State c=cursor("A","B");JSONObject out=c.advance(tree("next","A","B"),operation(c,"next"));assertEquals("no_progress_observed",out.getString("stoppedBecause"));assertTrue(out.getBoolean("partial"));assertFalse(out.getBoolean("wholeListComplete"));}
    @Test public void explicitBoundaryAndLimitsAreRealStopReasons()throws Exception {
        JSONObject args=start("first").put("stopSelector",j("text","boundary"));PhoneTreeCollection.State boundary=PhoneTreeCollection.start(args,tree("first","A","boundary"),"s",1000);assertEquals("explicit_boundary_observed",boundary.result().getString("stoppedBecause"));
        args=start("first").put("maxPages",1);assertEquals("page_limit",PhoneTreeCollection.start(args,tree("first","A"),"s",1000).result().getString("stoppedBecause"));args=start("first").put("maxRows",1);assertEquals("row_limit",PhoneTreeCollection.start(args,tree("first","A","B"),"s",1000).result().getString("stoppedBecause"));
    }
    @Test public void oldOrUncertainOrWrongTargetReceiptsCannotAdvance()throws Exception {
        PhoneTreeCollection.State c=cursor("A","B","C");JSONObject fake=operation(c,"next");fake.put("state","unknown");assertThrows(SecurityException.class,()->c.requireScroll(fake));JSONObject wrong=operation(c,"next");wrong.getJSONObject("receipt").getJSONObject("targetRef").put("windowId",10);assertThrows(SecurityException.class,()->c.requireScroll(wrong));JSONObject good=operation(c,"next");c.advance(tree("next","B","C","D"),good);assertThrows(SecurityException.class,()->c.requireScroll(good));
    }
    @Test public void scopePauseAndTimeNeverReviveOldCursorReferences()throws Exception {
        PhoneTreeCollection.State c=cursor("A");assertEquals("scope_or_pause_changed",c.scopeCheck("new-scope",1001).getString("stoppedBecause"));assertFalse(c.result().has("observation"));assertFalse(c.result().has("nextScroll"));PhoneTreeCollection.State expired=cursor("A");assertEquals("cursor_expired",expired.scopeCheck("native-scope",121001).getString("stoppedBecause"));
    }
    @Test public void refreshOnlyRenewsTheSameVisiblePageAndNeverAddsRowsWithoutAScroll()throws Exception {
        PhoneTreeCollection.State c=cursor("A","B");JSONObject out=c.refresh(tree("renewed","A","B"));assertEquals(1,out.getInt("pagesInspected"));assertEquals("renewed",out.getJSONObject("nextScroll").getString("observationId"));assertEquals(2,out.getInt("rowCount"));out=c.refresh(tree("changed","A","new"));assertEquals("scene_changed_without_admitted_scroll",out.getString("stoppedBecause"));assertEquals(2,out.getInt("rowCount"));
    }
    @Test public void backwardCollectionPreservesTheNativeSequence()throws Exception {
        PhoneTreeCollection.State c=PhoneTreeCollection.start(start("first").put("direction","backward"),tree("first","C","D","E"),"s",1000);JSONObject out=c.advance(tree("next","A","B","C","D"),operation(c,"next"));assertEquals(5,out.getInt("rowCount"));assertEquals("A",value(out,0));assertEquals("E",value(out,4));
    }
    @Test public void partialPageOrChangedContainerStopsWithoutPretendingNoMoreItems()throws Exception {
        JSONObject partial=tree("first","A").put("truncated",true);PhoneTreeCollection.State c=PhoneTreeCollection.start(start("first"),partial,"s",1000);assertEquals("partial_accessibility_page",c.result().getString("stoppedBecause"));c=cursor("A","B","C");JSONObject op=operation(c,"next"),next=tree("next","B","C","D");next.getJSONArray("nodes").getJSONObject(0).put("resourceId","another-list");assertEquals("container_changed",c.advance(next,op).getString("stoppedBecause"));
    }
}
