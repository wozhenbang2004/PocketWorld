package dev.pocketengine.browser;

import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.*;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** New implementation contracts; no inherited pre-outage results or real DOM claim. */
public final class BrowserStructuredSourceTest {
    @Test public void bareFieldMarkerCannotDowngradeToKnownWholeSourceInTextOrLink()throws Exception {
        String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),base=BrowserResearchResult.reference(task,id);JSONObject source=source(task,id,BrowserStructuredSource.fromDelivered(delivered("table"))).put("sourceRef",base);
        for(String text:new String[]{base+"#","[当时字段]("+base+"#)"}){
            JSONObject view=BrowserResearchResult.project(task,text,new JSONArray().put(source)),block=view.getJSONArray("blocks").getJSONObject(0);assertEquals(0,block.getJSONArray("sourceIds").length());assertEquals(0,block.getJSONArray("fieldRefs").length());assertTrue(block.getString("text"),block.getString("text").contains("字段依据未核对"));
        }
        JSONObject valid=BrowserResearchResult.project(task,base,new JSONArray().put(source));assertEquals(id,valid.getJSONArray("blocks").getJSONObject(0).getJSONArray("sourceIds").getString(0));
    }
    @Test public void knownMarkdownFieldKeepsItsExactBoundaryBeforeFollowingChineseProse()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        JSONObject nativeSource=BrowserStructuredSource.addReferences(source(task,id,p).put("sourceRef",BrowserResearchResult.reference(task,id)),source(task,id,p));String ref=nativeSource.getJSONArray("fieldRefs").getString(3);
        String tail="；原网页业务保存状态未核实。";JSONObject block=BrowserResearchResult.project(task,"费用依据：[¥42]("+ref+")"+tail,new JSONArray().put(nativeSource)).getJSONArray("blocks").getJSONObject(0);
        assertEquals(1,block.getJSONArray("fieldRefs").length());assertEquals(ref,block.getJSONArray("fieldRefs").getString(0));assertEquals(0,block.getJSONArray("sourceIds").length());assertTrue(block.getString("text"),block.getString("text").contains(tail));assertFalse(block.getString("text").contains("字段依据未核对"));
        JSONObject unknown=BrowserResearchResult.project(task,"[费用]("+ref+"unknown)"+tail,new JSONArray().put(nativeSource)).getJSONArray("blocks").getJSONObject(0);
        assertEquals(0,unknown.getJSONArray("fieldRefs").length());assertEquals(0,unknown.getJSONArray("sourceIds").length());assertTrue(unknown.getString("text").contains("字段依据未核对"));
    }
    static JSONObject cell(int column,String role,String text)throws Exception{return j("column",column,"role",role,"text",text,"links",new JSONArray(),"rowSpan",1,"colSpan",1,"partial",false);}
    static JSONObject delivered(String mode)throws Exception {
        JSONObject raw=raw(mode).put("ok",true);JSONArray data=new JSONArray();
        if(mode.equals("table"))data.put(j("index",0,"cells",new JSONArray().put(cell(0,"columnheader","项目")).put(cell(1,"columnheader","费用")))).put(j("index",1,"cells",new JSONArray().put(cell(0,"cell","同名项目")).put(cell(1,"cell","¥42 😀"))));
        else data.put(j("index",0,"kind","listitem","text","重复项","links",new JSONArray(),"partial",false)).put(j("index",1,"kind","listitem","text","重复项","links",new JSONArray(),"partial",false));
        raw.getJSONObject("read").put("data",data);raw.getJSONObject("read").getJSONObject("facts").put("to",2).put("totalCaptured",2).put("returnedItems",2);return BrowserSemanticRead.result(raw);
    }
    static JSONObject source(String task,String id,JSONObject p)throws Exception{return j("taskId",task,"sourceId",id,"title","已保存表格","url","https://source.example/report","structuredProjection",p);}
    static void revision(JSONObject p)throws Exception{p.remove("revision");p.put("revision",TaskStore.capabilityFingerprint(p));}
    @Test public void actualSavedCellAndHeadersArePresentRatherThanJustAnAnchorHash()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();JSONObject source=source(task,id,p);
        String ref=BrowserStructuredSource.references(task,id,p).getString(3);JSONObject field=BrowserStructuredSource.field(task,ref,source);
        assertEquals("¥42 😀",field.getString("text"));assertEquals(1,field.getJSONObject("row").getInt("index"));assertEquals(1,field.getJSONObject("field").getInt("column"));assertEquals(2,field.getJSONArray("headers").length());assertEquals("费用",field.getJSONArray("headers").getJSONObject(1).getString("text"));assertFalse(field.getBoolean("verifiedBusinessSuccess"));
    }
    @Test public void savesTheActualSafeDeliveryValuesNotTheEarlierNativeRawStrings()throws Exception {
        JSONObject actual=delivered("table"),safe=new JSONObject(actual.toString());safe.getJSONObject("read").getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(1).put("text","[removed in actual delivery]");
        JSONObject p=BrowserStructuredSource.fromDelivered(safe);assertTrue(p.toString().contains("[removed in actual delivery]"));assertFalse(p.toString().contains("¥42"));assertNotEquals(BrowserStructuredSource.fromDelivered(actual).getString("revision"),p.getString("revision"));
    }
    @Test public void duplicateListItemsKeepTheirOrderAndDistinctSavedPositions()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("list"));String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();JSONArray refs=BrowserStructuredSource.references(task,id,p);
        assertEquals(2,refs.length());assertNotEquals(refs.getString(0),refs.getString(1));assertTrue(refs.getString(0).endsWith(":i0"));assertTrue(refs.getString(1).endsWith(":i1"));assertEquals("重复项",BrowserStructuredSource.field(task,refs.getString(1),source(task,id,p)).getString("text"));
    }
    @Test public void rejectsDifferentTaskSourceRevisionAndUnsavedFieldWithoutPageFallback()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),ref=BrowserStructuredSource.references(task,id,p).getString(0);JSONObject source=source(task,id,p);
        assertThrows(SecurityException.class,()->BrowserStructuredSource.field(UUID.randomUUID().toString(),ref,source));assertThrows(SecurityException.class,()->BrowserStructuredSource.field(task,ref,source(task,UUID.randomUUID().toString(),p)));
        assertThrows(SecurityException.class,()->BrowserStructuredSource.field(task,ref.replace(p.getString("revision"),"b".repeat(64)),source));assertThrows(SecurityException.class,()->BrowserStructuredSource.field(task,ref.replace(":r0c0",":r159c23"),source));
    }
    @Test public void storedBodyMutationIsNotCoveredByItsOldRevision()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));p.getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(1).put("text","new page replacement");assertThrows(SecurityException.class,()->BrowserStructuredSource.validate(p));
    }
    @Test public void validatorRejectsExtraAuthorityFieldsUnknownRolesAndDuplicateLocations()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));JSONObject extra=new JSONObject(p.toString()).put("ownerApproved",true);assertThrows(SecurityException.class,()->BrowserStructuredSource.validate(extra));
        JSONObject unknown=new JSONObject(p.toString());unknown.getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(0).put("role","native_authority");revision(unknown);assertThrows(SecurityException.class,()->BrowserStructuredSource.validate(unknown));
        JSONObject duplicate=new JSONObject(p.toString());duplicate.getJSONArray("data").getJSONObject(1).put("index",0);revision(duplicate);assertThrows(SecurityException.class,()->BrowserStructuredSource.validate(duplicate));
    }
    @Test public void incompleteCellCannotBeUpgradedToCompleteEvenWithANewDigest()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));p.getJSONArray("data").getJSONObject(1).getJSONArray("cells").getJSONObject(0).put("partial",true);p.put("partial",false);revision(p);assertThrows(SecurityException.class,()->BrowserStructuredSource.validate(p));
    }
    @Test public void byteBudgetKeepsWholeDeliveredRowsAndReportsTheirOmission()throws Exception {
        JSONObject actual=delivered("table");for(int i=0;i<2;i++){JSONArray cells=new JSONArray();for(int c=0;c<8;c++)cells.put(cell(c,"cell","x".repeat(1000)));actual.getJSONObject("read").getJSONArray("data").getJSONObject(i).put("cells",cells);}
        JSONObject p=BrowserStructuredSource.fromDelivered(actual);assertTrue(p.toString().getBytes(StandardCharsets.UTF_8).length<=BrowserStructuredSource.MAX_BYTES);assertEquals(1,p.getJSONArray("data").length());assertEquals(1,p.getInt("omittedDeliveredRows"));assertTrue(p.getBoolean("partial"));assertEquals(1000,p.getJSONArray("data").getJSONObject(0).getJSONArray("cells").getJSONObject(7).getString("text").length());
    }
    @Test public void oversizedFirstRowIsNotSilentlySavedAsGuessedPartialCells()throws Exception {
        JSONObject actual=delivered("table");JSONArray cells=new JSONArray();for(int c=0;c<24;c++)cells.put(cell(c,"cell","x".repeat(1500)));actual.getJSONObject("read").getJSONArray("data").getJSONObject(0).put("cells",cells);assertThrows(IllegalStateException.class,()->BrowserStructuredSource.fromDelivered(actual));
    }
    @Test public void modelReceiptReferencesAreBoundedWhileAllStoredCellsRemainReadable()throws Exception {
        JSONObject actual=delivered("table");for(int i=0;i<2;i++){JSONArray cells=new JSONArray();for(int c=0;c<16;c++)cells.put(cell(c,"cell","v"+c));actual.getJSONObject("read").getJSONArray("data").getJSONObject(i).put("cells",cells);}
        JSONObject p=BrowserStructuredSource.fromDelivered(actual);String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();JSONObject source=source(task,id,p),receipt=BrowserStructuredSource.addReferences(new JSONObject(),source);assertEquals(24,receipt.getJSONArray("fieldRefs").length());assertEquals(8,receipt.getInt("fieldRefsOmitted"));String last=BrowserResearchResult.reference(task,id)+"#field:"+p.getString("revision")+":r1c15";assertEquals("v15",BrowserStructuredSource.field(task,last,source).getString("text"));
    }
    @Test public void derivedExcerptDoesNotSplitAnEmojiAtItsBudgetBoundary()throws Exception {
        JSONObject actual=delivered("table");JSONArray cells=new JSONArray();for(int c=0;c<3;c++)cells.put(cell(c,"cell","a".repeat(1498)));cells.put(cell(3,"cell","a".repeat(1496)+"😀"));actual.getJSONObject("read").getJSONArray("data").getJSONObject(0).put("cells",cells);String excerpt=BrowserStructuredSource.excerpt(BrowserStructuredSource.fromDelivered(actual));assertEquals(5999,excerpt.length());assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length()-1)));
    }
    @Test public void nonDeliveredOrNonStructuredResultsCannotMintStructuredSourceBodies()throws Exception {
        assertThrows(SecurityException.class,()->BrowserStructuredSource.fromDelivered(delivered("table").put("ok",false)));assertThrows(SecurityException.class,()->BrowserStructuredSource.fromDelivered(delivered("table").put("readOnlyEvidence",false)));assertThrows(IllegalArgumentException.class,()->BrowserStructuredSource.fromDelivered(BrowserSemanticRead.result(raw("text")).put("ok",true)));
    }
    @Test public void resultCellLinksOnlyUseNativeSavedFieldRecordsAndMalformedSuffixDoesNotBecomeBaseLink()throws Exception {
        JSONObject p=BrowserStructuredSource.fromDelivered(delivered("table"));String task=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();JSONObject nativeSource=BrowserStructuredSource.addReferences(source(task,id,p).put("sourceRef",BrowserResearchResult.reference(task,id)),source(task,id,p));String ref=nativeSource.getJSONArray("fieldRefs").getString(3);
        JSONObject cell=BrowserResearchResult.project(task,"[费用]("+ref+")",new JSONArray().put(nativeSource)).getJSONArray("blocks").getJSONObject(0);assertEquals(1,cell.getJSONArray("fieldRefs").length());assertEquals(0,cell.getJSONArray("sourceIds").length());
        JSONObject bad=BrowserResearchResult.project(task,"[费用]("+ref.replace(":r1c1",":r1c9")+")",new JSONArray().put(nativeSource)).getJSONArray("blocks").getJSONObject(0);assertTrue(bad.getString("text").contains("字段依据未核对"));assertEquals(0,bad.getJSONArray("sourceIds").length());assertEquals(0,bad.getJSONArray("fieldRefs").length());
    }
}
