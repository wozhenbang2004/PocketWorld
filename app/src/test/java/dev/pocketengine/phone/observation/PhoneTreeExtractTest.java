package dev.pocketengine.phone.observation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.phone.observation.PhoneCollectionFixtures.*;

public final class PhoneTreeExtractTest {
    @Test public void fieldsRemainInsideTheirRealRowsAndRepeatedValuesAreNotCollapsed()throws Exception {
        JSONObject out=PhoneTreeExtract.extract(tree("o","same","same","other"),extract("o"));assertEquals(3,out.getJSONArray("rows").length());assertEquals("same",value(out,0));assertEquals("same",value(out,1));assertEquals("other",value(out,2));
        assertEquals("n2",out.getJSONArray("rows").getJSONObject(0).getJSONObject("fields").getJSONObject("title").getJSONArray("sourceRefs").getJSONObject(0).getString("nodeId"));assertFalse(out.getJSONObject("coverage").getBoolean("wholeListComplete"));
    }
    @Test public void missingAndAmbiguousFieldsAreExplicitNeverTakeASiblingOrFirstCandidate()throws Exception {
        JSONObject t=tree("o","a","b");t.getJSONArray("nodes").getJSONObject(2).put("resourceId","not-title");JSONObject more=node("duplicate","n3","app:id/title","extra",1,1);t.getJSONArray("nodes").put(more);t.getJSONArray("nodes").getJSONObject(3).getJSONArray("children").put("duplicate");
        JSONObject out=PhoneTreeExtract.extract(t,extract("o"));assertEquals("missing",out.getJSONArray("rows").getJSONObject(0).getJSONObject("fields").getJSONObject("title").getString("status"));assertEquals("ambiguous",out.getJSONArray("rows").getJSONObject(1).getJSONObject("fields").getJSONObject("title").getString("status"));assertTrue(out.getJSONArray("rows").getJSONObject(1).getJSONObject("fields").getJSONObject("title").isNull("value"));
    }
    @Test public void passwordFieldHasNoValueOrRefAndInvisibleParentDoesNotEraseVisibleChildren()throws Exception {
        JSONObject t=tree("o","private","visible");t.getJSONArray("nodes").getJSONObject(2).put("password",true);t.getJSONArray("nodes").getJSONObject(3).put("visible",false);
        JSONObject out=PhoneTreeExtract.extract(t,extract("o"));JSONObject secret=out.getJSONArray("rows").getJSONObject(0).getJSONObject("fields").getJSONObject("title");assertEquals("masked",secret.getString("status"));assertTrue(secret.isNull("value"));assertEquals(0,secret.getJSONArray("sourceRefs").length());assertEquals("visible",value(out,1));assertFalse(out.toString().contains("private"));
    }
    @Test public void otherWindowsAndNestedRowSelectionsCannotPolluteRowOwnership()throws Exception {
        JSONObject t=tree("o","local");t.getJSONArray("nodes").put(node("other",null,"app:id/title","foreign").put("windowId",10));assertEquals("local",value(PhoneTreeExtract.extract(t,extract("o")),0));
        JSONObject args=extract("o").put("rowSelector",j("role","text"));assertEquals("overlapping_row_containers",PhoneTreeExtract.extract(t,args).getString("reason"));
    }
    @Test public void clippingAndStructuralHolesCannotClaimCompleteDataOrWholeList()throws Exception {
        JSONObject t=tree("o","a");t.getJSONObject("treeCoverage").put("bodyTruncated",true);JSONObject out=PhoneTreeExtract.extract(t,extract("o"));assertTrue(out.getBoolean("partial"));assertTrue(out.getJSONObject("coverage").getBoolean("textMayBeClipped"));assertFalse(out.getJSONObject("coverage").getBoolean("wholeListComplete"));
        t.put("truncated",true);assertFalse(PhoneTreeExtract.extract(t,extract("o")).getJSONObject("coverage").getBoolean("structuralComplete"));
    }
    @Test public void staleObservationsLimitsAndSchemaInjectionAreRejected()throws Exception {
        assertEquals("stale_observation",PhoneTreeExtract.extract(tree("new","a"),extract("old")).getString("reason"));assertThrows(IllegalArgumentException.class,()->PhoneTreeExtract.validate(extract("o").put("taskId","model")));assertThrows(IllegalArgumentException.class,()->PhoneTreeExtract.validate(extract("o").put("limit",81)));
        JSONObject args=extract("o").put("limit",1);JSONObject out=PhoneTreeExtract.extract(tree("o","a","b"),args);assertEquals(1,out.getInt("returnedRows"));assertTrue(out.getBoolean("truncated"));
    }
    @Test public void emptyFieldSelectorMeansOnlyTheRowItselfAndMultipleRetainsOrder()throws Exception {
        JSONObject t=tree("o","a","b");t.getJSONArray("nodes").getJSONObject(1).put("text","row-label");JSONObject args=extract("o");args.getJSONArray("fields").put(0,j("name","title","selector",new JSONObject()));assertEquals("row-label",value(PhoneTreeExtract.extract(t,args),0));
        args.getJSONArray("fields").put(0,j("name","title","selector",j("role","text"),"multiple",true));JSONArray values=PhoneTreeExtract.extract(t,args).getJSONArray("rows").getJSONObject(0).getJSONObject("fields").getJSONObject("title").getJSONArray("value");assertEquals("row-label",values.getString(0));assertEquals("a",values.getString(1));
    }
}
