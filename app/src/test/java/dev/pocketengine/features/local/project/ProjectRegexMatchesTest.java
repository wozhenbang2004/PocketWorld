package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import org.json.*;
import org.junit.*;

/** Actual bounded source bytes and the existing RE2/J adapter, not a parser or fake file backend. */
public final class ProjectRegexMatchesTest {
    private Path root;
    @Before public void create()throws Exception{root=Files.createTempDirectory("project-regex-matches-");}
    @After public void close()throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void file(String path,String text)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.write(target,text.getBytes(StandardCharsets.UTF_8));}
    private JSONObject query(String expression)throws Exception{return new JSONObject().put("mode","regex_matches").put("query",expression);}
    private JSONObject search(JSONObject input)throws Exception{return ProjectFileQueries.search(root.toFile(),"11111111-1111-1111-1111-111111111111",input,()->{});}
    private List<JSONObject> all(JSONObject input)throws Exception{
        List<JSONObject> result=new ArrayList<>();JSONObject request=new JSONObject(input.toString());
        for(int page=0;page<100;page++){
            JSONObject actual=search(request);assertTrue(actual.toString().getBytes(StandardCharsets.UTF_8).length<=ProjectFileQueries.MAX_CONTEXT_BYTES);
            JSONArray rows=actual.getJSONArray("matches");for(int i=0;i<rows.length();i++)result.add(rows.getJSONObject(i));
            if(!actual.getBoolean("hasMore")){assertTrue(actual.isNull("nextCursor"));return result;}
            assertTrue("A continuation page must make real matching progress",rows.length()>0);request.put("cursor",actual.getJSONObject("nextCursor"));
        }
        throw new AssertionError("matching cursor never finished");
    }

    @Test public void everyMatchOnOneLineRetainsOptionalNullAndMatchedEmptyCapture()throws Exception{
        file("src/a.txt","🙂ab a ab");JSONObject actual=search(query("(a)(b)?()"));JSONArray rows=actual.getJSONArray("matches");assertEquals(3,rows.length());
        assertEquals("regex_re2j_file",actual.getString("searchSyntax"));assertFalse(actual.getBoolean("writeAuthorityAdded"));assertTrue(actual.getBoolean("complete"));
        JSONObject first=rows.getJSONObject(0);assertEquals(2,first.getInt("startOffset"));assertEquals(4,first.getInt("endOffset"));assertEquals(3,first.getInt("column"));assertEquals(5,first.getInt("endColumn"));assertEquals("ab",first.getString("text"));
        JSONObject absent=rows.getJSONObject(1).getJSONArray("groups").getJSONObject(1);assertFalse(absent.getBoolean("matched"));assertTrue(absent.isNull("text"));assertTrue(absent.isNull("startOffset"));assertTrue(absent.isNull("endOffset"));
        JSONObject empty=rows.getJSONObject(1).getJSONArray("groups").getJSONObject(2);assertTrue(empty.getBoolean("matched"));assertEquals("",empty.getString("text"));assertEquals(empty.getInt("startOffset"),empty.getInt("endOffset"));assertTrue(empty.getBoolean("textComplete"));
        assertEquals(ProjectSources.sha256(Files.readAllBytes(root.resolve("src/a.txt"))),first.getString("sha256"));
    }
    @Test public void crossLineMatchesUseOriginalCrLfBytesAndUtf16EndExclusivePositions()throws Exception{
        file("a.txt","🙂start\r\n中文 END\nlast");JSONObject actual=search(query("(start\r?\n)(中文)"));JSONObject row=actual.getJSONArray("matches").getJSONObject(0);
        assertEquals("start\r\n中文",row.getString("text"));assertEquals(2,row.getInt("startOffset"));assertEquals(11,row.getInt("endOffset"));assertEquals(1,row.getInt("line"));assertEquals(3,row.getInt("column"));assertEquals(2,row.getInt("endLine"));assertEquals(3,row.getInt("endColumn"));
        assertEquals(9,row.getJSONArray("groups").getJSONObject(1).getInt("startOffset"));
        JSONObject dotAll=search(query("(?s)start.*END"));assertEquals("start\r\n中文 END",dotAll.getJSONArray("matches").getJSONObject(0).getString("text"));
    }
    @Test public void oneMatchPagesContinueWithinTheSameLineThenAcrossSortedFiles()throws Exception{
        file("b.txt","hit");file("a.txt","hit hit hit");List<JSONObject> rows=all(query("hit").put("limit",1));assertEquals(4,rows.size());
        assertEquals(List.of("a.txt:0","a.txt:4","a.txt:8","b.txt:0"),rows.stream().map(row->row.optString("path")+":"+row.optInt("startOffset")).toList());
    }
    @Test public void zeroWidthPagesAdvanceWholeUnicodeCodePointsAndIncludeEofOnce()throws Exception{
        file("a.txt","🙂x");file("b.txt","");List<JSONObject> rows=all(query("()").put("limit",1));assertEquals(4,rows.size());
        assertEquals(List.of("a.txt:0","a.txt:2","a.txt:3","b.txt:0"),rows.stream().map(row->row.optString("path")+":"+row.optInt("startOffset")).toList());
        for(JSONObject row:rows){assertTrue(row.getBoolean("zeroWidth"));assertEquals(row.getInt("startOffset"),row.getInt("endOffset"));assertEquals("",row.getString("text"));}
        JSONObject first=search(query("()").put("limit",1));JSONObject broken=new JSONObject(first.getJSONObject("nextCursor").toString()).put("offset",1);assertThrows(SecurityException.class,()->search(query("()").put("limit",1).put("cursor",broken)));
    }
    @Test public void resumedMatchingKeepsWholeInputAnchorsInsteadOfAnchoringASubstring()throws Exception{
        file("a.txt","abc");List<JSONObject> rows=all(query("^|$").put("limit",1));assertEquals(2,rows.size());assertEquals(0,rows.get(0).getInt("startOffset"));assertEquals(3,rows.get(1).getInt("startOffset"));
        file("a.txt","a\nb\n");List<JSONObject> lines=all(query("(?m)^").put("limit",1));assertEquals(List.of(0,2,4),lines.stream().map(row->row.optInt("startOffset")).toList());
    }
    @Test public void cursorRejectsChangedSourceQueryScopeAndMalformedPositions()throws Exception{
        file("a.txt","hit hit");file("b.txt","hit");JSONObject input=query("hit").put("limit",1),cursor=search(input).getJSONObject("nextCursor");
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("query","h.t").put("cursor",cursor)));
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("exclude","b.txt").put("cursor",cursor)));
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("caseSensitive",false).put("cursor",cursor)));
        for(Object offset:new Object[]{-1,9999,"4",1.5})assertThrows(Exception.class,()->search(new JSONObject(input.toString()).put("cursor",new JSONObject(cursor.toString()).put("offset",offset))));
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("cursor",new JSONObject(cursor.toString()).put("extra",true))));
        assertThrows(IllegalArgumentException.class,()->search(new JSONObject(input.toString()).put("cursor","not an object")));
        file("b.txt","changed source outside this page");assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("cursor",cursor)));
    }
    @Test public void largeIndividualMatchesHaveBoundedPreviewsWithoutChangingTheirActualRanges()throws Exception{
        String text="🙂".repeat(12000)+"!";file("a.txt",text);JSONObject result=search(query("(?s)(.*)!()"));JSONObject match=result.getJSONArray("matches").getJSONObject(0);
        assertFalse(result.getBoolean("contentComplete"));assertFalse(result.getBoolean("complete"));assertTrue(result.getBoolean("matchesComplete"));assertFalse(result.getBoolean("hasMore"));assertEquals(text.length(),match.getInt("endOffset"));assertEquals(text.length()-1,match.getJSONArray("groups").getJSONObject(0).getInt("endOffset"));
        assertFalse(match.getBoolean("textComplete"));assertTrue(match.getInt("previewChars")<=512);String preview=match.getString("text");assertFalse(Character.isHighSurrogate(preview.charAt(preview.length()-1)));assertTrue(result.toString().getBytes(StandardCharsets.UTF_8).length<=12000);
        JSONObject empty=match.getJSONArray("groups").getJSONObject(1);assertTrue(empty.getBoolean("matched"));assertEquals("",empty.getString("text"));assertTrue(empty.getBoolean("textComplete"));
    }
    @Test public void outputByteBudgetNeverSkipsAnUnreturnedMatchOrCapture()throws Exception{
        file("a.txt",("中".repeat(150)+";").repeat(35));JSONObject input=query("((中+)(中)(中));").put("limit",40);JSONObject first=search(input);assertTrue(first.getBoolean("hasMore"));assertTrue(first.getBoolean("resultBudgetExhausted"));
        List<JSONObject> rows=all(input);assertEquals(35,rows.size());for(int i=0;i<rows.size();i++){JSONObject row=rows.get(i);assertEquals(151*i,row.getInt("startOffset"));assertEquals(151*(i+1),row.getInt("endOffset"));assertEquals(4,row.getJSONArray("groups").length());}
    }
    @Test public void originalScopeFiltersBinaryAndOversizeAreExplicitAndNeverExpandedByRegex()throws Exception{
        file("a.txt","hit");file("logs/log.txt","hit");file("node_modules/cache.txt","hit");file(".hidden.txt","hit");Files.write(root.resolve("binary.bin"),new byte[]{0,104,105,116});Files.write(root.resolve("large.txt"),new byte[(int)ProjectSources.MAX_FILE_BYTES+1]);
        JSONObject actual=search(query("hit").put("exclude","logs/**"));assertEquals(1,actual.getJSONArray("matches").length());assertEquals("a.txt",actual.getJSONArray("matches").getJSONObject(0).getString("path"));assertEquals(1,actual.getInt("excludedByQuery"));assertEquals(1,actual.getInt("binaryFilesSkippedForText"));assertFalse(actual.getBoolean("coverageComplete"));assertFalse(actual.getBoolean("complete"));assertTrue(actual.getLong("skippedCount")>0);
    }
    @Test public void sourceChangeDuringActualMatchingAndOriginalCancellationDoNotReturnSuccessfulPages()throws Exception{
        file("a.txt","hit hit");java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(SecurityException.class,()->ProjectFileQueries.search(root.toFile(),"original",query("hit"),()->{for(StackTraceElement frame:Thread.currentThread().getStackTrace())if(frame.getMethodName().equals("findGroups")&&changed.compareAndSet(false,true))file("a.txt","owner new source");}));assertTrue(changed.get());
        ProjectSearchPattern pattern=new ProjectSearchPattern("(a+)+$",true);java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(InterruptedException.class,()->pattern.findGroups("a".repeat(180000)+"!",0,()->{if(checks.incrementAndGet()>2)throw new InterruptedException("original run cancelled");}));assertTrue(checks.get()>2);
    }
    @Test public void groupAndMixedOptionLimitsAreExplicitAndLegacySearchSemanticsStayUnchanged()throws Exception{
        file("a.txt","hit hit\na+b\n");JSONObject legacy=search(new JSONObject().put("mode","text").put("syntax","regex").put("query","hit"));assertEquals(1,legacy.getJSONArray("matches").length());assertEquals("first_match_per_line",legacy.getString("matchSemantics"));assertEquals(2,search(query("hit")).getJSONArray("matches").length());
        assertEquals(1,search(new JSONObject().put("mode","text").put("query","a+b")).getJSONArray("matches").length());
        for(String option:new String[]{"maxEdits","contextBefore","contextAfter"})assertThrows(IllegalArgumentException.class,()->search(query("hit").put(option,1)));
        assertThrows(IllegalArgumentException.class,()->search(query("hit").put("syntax","literal")));assertThrows(IllegalArgumentException.class,()->search(query("hit").put("limit",1.5)));
        assertThrows(IllegalArgumentException.class,()->search(query("()".repeat(33))));assertEquals(32,search(query("()".repeat(32))).getInt("captureGroups"));
        for(String unsupported:new String[]{"(a)\\1","a(?=b)","[","(?:a{1000}){1000}"})assertThrows(IllegalArgumentException.class,()->search(query(unsupported)));
    }
}
