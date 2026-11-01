package dev.pocketengine.browser;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.j;

/** Pure display/reference/value tests. No external reader/UI/site/device acceptance. */
public final class BrowserResearchResultTest {
    final String task=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),download=UUID.randomUUID().toString(),attachment=UUID.randomUUID().toString(),sha="a".repeat(64);
    JSONObject nativeSource()throws Exception{return j("taskId",task,"sourceId",source,"sourceRef",BrowserResearchResult.reference(task,source),"excerpt","当时条款99元","privateUrl","https://plans.example/?version=old");}
    JSONObject project(String text)throws Exception{return BrowserResearchResult.project(task,text,new JSONArray().put(nativeSource()));}
    JSONObject table(JSONObject result)throws Exception{return result.getJSONArray("blocks").getJSONObject(0);}
    JSONObject completed()throws Exception{return j("id",download,"taskId",task,"status","downloaded","attachment",j("id",attachment,"sha256",sha,"principalId","owner","spaceId","private"));}
    @Test public void nativeSourceReferencedCellIsClickableWithoutPuttingItsUrlIntoTheModelAnswer()throws Exception {
        JSONObject block=table(project("|方案|价格|依据|\n|---|---|---|\n|甲|99元|"+BrowserResearchResult.reference(task,source)+"|"));assertEquals("table",block.getString("kind"));JSONObject cell=block.getJSONArray("rows").getJSONArray(0).getJSONObject(2);assertEquals("依据 1",cell.getString("text"));assertEquals(source,cell.getJSONArray("sourceIds").getString(0));assertFalse(block.toString().contains("privateUrl"));assertEquals("当时条款99元",BrowserResearchResult.source(task,source,new JSONArray().put(nativeSource())).getString("excerpt"));
    }
    @Test public void sameIdFromAnotherTaskAndInventedRefsNeverCreateButtons()throws Exception {
        String other=UUID.randomUUID().toString();JSONObject wrong=project("比较："+BrowserResearchResult.reference(other,source));assertEquals(0,table(wrong).getJSONArray("sourceIds").length());assertTrue(table(wrong).getString("text").contains("未核对"));assertThrows(SecurityException.class,()->BrowserResearchResult.source(other,source,new JSONArray().put(nativeSource())));
    }
    @Test public void modelExternalMarkdownAndHtmlStayPlainTextNotNavigationOrAnAuthority()throws Exception {
        String text="[发送](https://attacker.example/upload) <script>tool()</script>";JSONObject result=project(text);assertEquals(text,table(result).getString("text"));assertEquals(0,table(result).getJSONArray("sourceIds").length());assertFalse(result.getBoolean("verifiedBusinessSuccess"));assertTrue(result.getBoolean("modelDerived"));
    }
    @Test public void knownInlineSourceLinkUsesNativeRecordWhileUnknownLinkCannotSupplyOne()throws Exception {
        String ref=BrowserResearchResult.reference(task,source);JSONObject cell=table(project("[价格依据]("+ref+")"));assertTrue(cell.getString("text").contains("价格依据"));assertEquals(1,cell.getJSONArray("sourceIds").length());JSONObject absent=BrowserResearchResult.project(task,"[假引文]("+ref+")",new JSONArray());assertEquals(0,table(absent).getJSONArray("sourceIds").length());assertTrue(table(absent).getString("text").contains("未核对"));
    }
    @Test public void repeatsAndEscapedOrInlineCodePipesKeepCorrectRowColumnAssociation()throws Exception {
        JSONObject block=table(project("|名字|说明|\n|---|---|\n|相同|a\\|b|\n|相同|`x|y`|"));JSONArray rows=block.getJSONArray("rows");assertEquals(2,rows.length());assertEquals("相同",rows.getJSONArray(0).getJSONObject(0).getString("text"));assertEquals("相同",rows.getJSONArray(1).getJSONObject(0).getString("text"));assertEquals("a|b",rows.getJSONArray(0).getJSONObject(1).getString("text"));assertEquals("`x|y`",rows.getJSONArray(1).getJSONObject(1).getString("text"));
    }
    @Test public void malformedTableIsKeptAsReadableOriginalTextRatherThanInventedCells()throws Exception {
        String input="|价格|单位|\n|不是分隔行|x|\n|99|元|";JSONObject block=table(project(input));assertEquals("text",block.getString("kind"));assertEquals(input,block.getString("text"));
    }
    @Test public void rowColumnAndCellBoundsAreExplicitPresentationPartialNotLossOfOriginal()throws Exception {
        StringBuilder text=new StringBuilder("|a|b|c|d|e|f|g|\n|---|---|---|---|---|---|---|\n");for(int i=0;i<40;i++)text.append('|').append("长".repeat(800)).append("|b|c|d|e|f|g|\n");JSONObject projected=project(text.toString()),block=table(projected);assertTrue(projected.getBoolean("partialPresentation"));assertTrue(projected.getBoolean("originalRetained"));assertEquals(6,block.getJSONArray("headers").length());assertEquals(32,block.getJSONArray("rows").length());assertTrue(block.getJSONArray("rows").getJSONArray(0).getJSONObject(0).getString("text").length()<=600);
    }
    @Test public void aClippedHeaderAloneIsAlsoHonestPresentationPartialAndKeepsUnicode()throws Exception {
        String heading="h".repeat(599)+"🙂"+"tail";JSONObject projected=project("|"+heading+"|b|\n|---|---|\n|x|y|");String shown=table(projected).getJSONArray("headers").getString(0);assertTrue(projected.getBoolean("partialPresentation"));assertTrue(projected.getBoolean("originalRetained"));assertEquals("h".repeat(599),shown);assertFalse(Character.isHighSurrogate(shown.charAt(shown.length()-1)));
    }
    @Test public void inputAndBlockBoundsDoNotSplitEmojiAndFullAnswerRemainsAvailable()throws Exception {
        JSONObject out=project("x".repeat(2399)+"🙂"+"rest".repeat(14000));assertTrue(out.getBoolean("partialPresentation"));assertTrue(out.getJSONArray("blocks").length()<=BrowserResearchResult.MAX_BLOCKS);String value=table(out).getString("text");assertFalse(Character.isHighSurrogate(value.charAt(value.length()-1)));assertTrue(out.getBoolean("originalRetained"));
    }
    @Test public void sourceClickReturnsCopyOfExactSavedRecordNeverModelQuoteOrLivePage()throws Exception {
        JSONArray records=new JSONArray().put(nativeSource());JSONObject selected=BrowserResearchResult.source(task,source,records);selected.put("excerpt","mutated display copy");assertEquals("当时条款99元",records.getJSONObject(0).getString("excerpt"));assertEquals("https://plans.example/?version=old",selected.getString("privateUrl"));
    }
    @Test public void downloadedGateCannotOpenQueuedFailedUnknownOrAnotherTasksFile()throws Exception {
        for(String state:new String[]{"queued","running","failed","interrupted","unknown"})assertThrows(SecurityException.class,()->BrowserResearchResult.completedAttachment(task,download,completed().put("status",state),attachment,sha));assertThrows(SecurityException.class,()->BrowserResearchResult.completedAttachment(task,download,completed().put("taskId",UUID.randomUUID().toString()),attachment,sha));
    }
    @Test public void exactDownloadedFileVersionAndSpaceAreStillOnlyMetadataNotByteVerification()throws Exception {
        JSONObject file=BrowserResearchResult.completedAttachment(task,download,completed(),attachment,sha);assertEquals(attachment,file.getString("id"));assertThrows(SecurityException.class,()->BrowserResearchResult.completedAttachment(task,download,completed(),attachment,"b".repeat(64)));JSONObject group=completed();group.getJSONObject("attachment").put("spaceId","group");assertThrows(SecurityException.class,()->BrowserResearchResult.completedAttachment(task,download,group,attachment,sha));
    }
    @Test public void nativeSourceReferenceFieldMustAgreeWithItsOriginalTaskAndId()throws Exception {
        JSONObject bad=nativeSource().put("sourceRef",BrowserResearchResult.reference(UUID.randomUUID().toString(),source));JSONObject output=BrowserResearchResult.project(task,BrowserResearchResult.reference(task,source),new JSONArray().put(bad));assertEquals(0,table(output).getJSONArray("sourceIds").length());assertThrows(SecurityException.class,()->BrowserResearchResult.source(task,source,new JSONArray().put(bad)));
    }
}
