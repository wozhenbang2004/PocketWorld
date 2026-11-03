package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;

public final class ProjectFuzzySearchTest {
    private Path root;
    @Before public void create()throws Exception{root=Files.createTempDirectory("project-fuzzy-search-");}
    @After public void close()throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void file(String path,String text)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.write(target,text.getBytes(StandardCharsets.UTF_8));}
    private JSONObject query(String text)throws Exception{return new JSONObject().put("mode","text").put("syntax","fuzzy").put("query",text).put("maxEdits",1);}
    private JSONObject search(JSONObject input)throws Exception{return ProjectFileQueries.search(root.toFile(),"11111111-1111-1111-1111-111111111111",input,()->{});}

    @Test public void candidatesUseActualUnicodeLocationsAndNeverSupplyWriteAuthority()throws Exception{
        String content="header\n🙂 needlz tail\nneedle exact\n";file("src/a.txt",content);
        JSONObject result=search(query("needle").put("contextBefore",1).put("contextAfter",1));JSONArray matches=result.getJSONArray("matches");
        assertEquals(2,matches.length());JSONObject typo=matches.getJSONObject(0);
        assertEquals(2,typo.getInt("line"));assertEquals(4,typo.getInt("column"));assertEquals(10,typo.getInt("endColumn"));assertEquals(1,typo.getInt("editDistance"));
        assertEquals(ProjectSources.sha256(content.getBytes(StandardCharsets.UTF_8)),typo.getString("sha256"));assertTrue(typo.getBoolean("suggestionOnly"));assertFalse(result.getBoolean("writeAuthorityAdded"));
        assertEquals(0,matches.getJSONObject(1).getInt("editDistance"));assertEquals(ProjectFuzzyPattern.ENGINE,result.getString("fuzzyEngine"));assertEquals("unicode_code_points",result.getString("distanceUnit"));
        assertEquals(3,result.getJSONArray("contextBlocks").getJSONObject(0).getJSONArray("lines").length());
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(root.resolve("src/a.txt")));assertFalse(result.has("planRef"));
    }
    @Test public void candidatePagesBindDistanceSyntaxAndTheWholeSourceRevision()throws Exception{
        file("a.txt","needlz\nneedly\n");JSONObject input=query("needle").put("limit",1),page=search(input),cursor=page.getJSONObject("nextCursor");
        assertEquals(2,search(new JSONObject(input.toString()).put("cursor",cursor)).getJSONArray("matches").getJSONObject(0).getInt("line"));
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("maxEdits",2).put("cursor",cursor)));
        JSONObject literal=new JSONObject().put("mode","text").put("query","needle").put("limit",1).put("cursor",cursor);assertThrows(SecurityException.class,()->search(literal));
        file("a.txt","needlz\nchanged\n");assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("cursor",cursor)));
    }
    @Test public void originalScopeFiltersAndBinaryExclusionsStillApply()throws Exception{
        file("src/a.txt","needlz");file("src/skip.txt","needlz");file(".secret.txt","needlz");file("node_modules/x.js","needlz");Files.write(root.resolve("raw.bin"),new byte[]{0,1});
        JSONObject result=search(query("needle").put("include","src/**").put("exclude","src/skip.txt"));
        assertEquals(1,result.getJSONArray("matches").length());assertEquals("src/a.txt",result.getJSONArray("matches").getJSONObject(0).getString("path"));
        assertEquals(1,result.getInt("excludedByQuery"));assertEquals(1,result.getInt("binaryFilesSkippedForText"));
    }
    @Test public void bestCandidateAndCodePointEditsDoNotSplitSurrogates()throws Exception{
        assertArrayEquals(new int[]{12,18,0},new ProjectFuzzyPattern("needle",true,1).find("needlz then needle",()->{}));
        assertArrayEquals(new int[]{0,5,1},new ProjectFuzzyPattern("a🙂cd",true,1).find("a🙃cd",()->{}));
        assertArrayEquals(new int[]{0,4,0},new ProjectFuzzyPattern("Äbcd",false,1).find("äBCD",()->{}));
        assertNull(new ProjectFuzzyPattern("wxyz",true,1).find("abcd",()->{}));
    }
    @Test public void cancellationAndCostLimitsFailInsteadOfReturningSuccessfulEmptyCandidates()throws Exception{
        AtomicInteger checks=new AtomicInteger();assertThrows(InterruptedException.class,()->new ProjectFuzzyPattern("needle",true,1).find("x".repeat(100000),()->{if(checks.incrementAndGet()>2)throw new InterruptedException("original run cancelled");}));
        assertThrows(IllegalArgumentException.class,()->new ProjectFuzzyPattern("z".repeat(240),true,2).find("a".repeat(40000),()->{}));
        ProjectFuzzyPattern expired=new ProjectFuzzyPattern("needle",true,1);var clock=ProjectFuzzyPattern.class.getDeclaredField("started");clock.setAccessible(true);clock.setLong(expired,System.nanoTime()-(ProjectFuzzyPattern.MAX_MATCH_MILLIS+1)*1_000_000L);
        assertThrows(IllegalArgumentException.class,()->expired.find("",()->{}));assertThrows(IllegalArgumentException.class,()->expired.find("needle",()->{}));
    }
    @Test public void unsupportedRangesAndDistanceOptionsAreNotSilentlyIgnored()throws Exception{
        file("a.txt","needle");
        for(Object distance:new Object[]{0,3,1.5,"1",JSONObject.NULL})assertThrows(IllegalArgumentException.class,()->search(query("needle").put("maxEdits",distance)));
        assertThrows(IllegalArgumentException.class,()->search(query("abc")));assertThrows(IllegalArgumentException.class,()->search(query("needle").put("mode","path")));
        assertThrows(IllegalArgumentException.class,()->search(query("needle").put("syntax","literal")));assertThrows(IllegalArgumentException.class,()->search(query("6e6565646c65").put("mode","hex").put("path","a.txt")));
    }
}
