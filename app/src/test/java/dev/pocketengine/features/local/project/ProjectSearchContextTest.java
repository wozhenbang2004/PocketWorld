package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;
import org.junit.*;

/** Actual selected-tree bytes; no synthetic search service or shell grep. */
public final class ProjectSearchContextTest {
    private Path root;
    private static final String CHECKOUT="11111111-1111-1111-1111-111111111111";
    @Before public void create()throws Exception{root=Files.createTempDirectory("project-search-context-");}
    @After public void close()throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void file(String path,String text)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.writeString(target,text);}
    private JSONObject query(){return new JSONObject().put("mode","text").put("query","needle").put("contextBefore",1).put("contextAfter",1);}
    private JSONObject search(JSONObject input)throws Exception{return ProjectFileQueries.search(root.toFile(),CHECKOUT,input,()->{});}

    @Test public void overlappingWindowsMergeWithoutDroppingOriginalMatches()throws Exception{
        String source="head\nneedle 中文🙂\nmiddle\nneedle second\ntail\noutside\n";file("src/a.txt",source);
        JSONObject result=search(query());assertEquals(2,result.getJSONArray("matches").length());
        JSONArray blocks=result.getJSONArray("contextBlocks");assertEquals(1,blocks.length());JSONObject block=blocks.getJSONObject(0);
        assertEquals("src/a.txt",block.getString("path"));assertEquals(ProjectSources.sha256(source.getBytes(StandardCharsets.UTF_8)),block.getString("sha256"));
        assertEquals(1,block.getInt("startLine"));assertEquals(5,block.getInt("endLine"));assertEquals(5,block.getJSONArray("lines").length());
        assertEquals("needle 中文🙂",block.getJSONArray("lines").getJSONObject(1).getString("text"));
        assertEquals("[2,4]",block.getJSONArray("matchedLines").toString());assertTrue(result.getBoolean("contextComplete"));
        assertEquals(1,block.getJSONArray("lines").getJSONObject(0).getInt("line"));assertFalse(block.getJSONArray("lines").getJSONObject(0).getBoolean("truncated"));
    }
    @Test public void eofAndDifferentFilesKeepAccurateSeparateWindows()throws Exception{
        file("a.txt","needle first\nlast");file("b.txt","before\nneedle final");
        JSONObject result=search(query().put("contextBefore",20).put("contextAfter",20));JSONArray blocks=result.getJSONArray("contextBlocks");
        assertEquals(2,blocks.length());for(int i=0;i<2;i++){JSONObject block=blocks.getJSONObject(i);assertEquals(1,block.getInt("startLine"));assertEquals(2,block.getInt("endLine"));assertEquals(2,block.getJSONArray("lines").length());}
        assertTrue(result.getBoolean("contextComplete"));assertEquals(4,result.getInt("contextLinesReturned"));
    }
    @Test public void cursorBindsContextShapeAndDoesNotSkipNearbyNextPageHit()throws Exception{
        file("a.txt","before\nneedle one\nneedle two\nafter\n");JSONObject input=query().put("limit",1),first=search(input);
        assertEquals(1,first.getJSONArray("matches").length());assertTrue(first.getBoolean("hasMore"));
        JSONObject second=search(new JSONObject(input.toString()).put("cursor",first.getJSONObject("nextCursor")));
        assertEquals(3,second.getJSONArray("matches").getJSONObject(0).getInt("line"));assertFalse(second.getBoolean("hasMore"));
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("contextAfter",2).put("cursor",first.getJSONObject("nextCursor"))));
        file("a.txt","before\nneedle changed\nneedle two\nafter\n");
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("cursor",first.getJSONObject("nextCursor"))));
    }
    @Test public void contextBudgetAndLongUnicodeLinesDiscloseLossInsteadOfClaimingComplete()throws Exception{
        StringBuilder source=new StringBuilder();for(int i=0;i<100;i++)source.append(i%10==0?"needle ":"context ").append("🙂中".repeat(1000)).append('\n');file("long.txt",source.toString());
        JSONObject result=search(query().put("contextBefore",20).put("contextAfter",20));
        assertFalse(result.getBoolean("contextComplete"));assertTrue(result.getBoolean("contextTruncated"));assertTrue(result.getInt("contextResultBytes")<=12000);
        assertEquals(result.getJSONArray("contextBlocks").toString().getBytes(StandardCharsets.UTF_8).length,result.getInt("contextResultBytes"));
        assertTrue(result.toString().getBytes(StandardCharsets.UTF_8).length<30000);
        for(Object raw:result.getJSONArray("contextBlocks"))for(Object row:((JSONObject)raw).getJSONArray("lines")){
            String value=((JSONObject)row).getString("text");assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(value));
        }
    }
    @Test public void cancellationDuringAdditionalContextReadStillPropagates()throws Exception{
        file("a.txt","before\nneedle\nafter\n");java.util.concurrent.atomic.AtomicInteger baseline=new java.util.concurrent.atomic.AtomicInteger();
        ProjectFileQueries.search(root.toFile(),CHECKOUT,new JSONObject().put("mode","text").put("query","needle"),()->baseline.incrementAndGet());
        java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(InterruptedException.class,()->ProjectFileQueries.search(root.toFile(),CHECKOUT,query(),()->{if(checks.incrementAndGet()>baseline.get())throw new InterruptedException("original cancellation");}));
    }
    @Test public void invalidOrInapplicableContextIsRejectedNotSilentlyIgnored()throws Exception{
        file("a.txt","needle");
        assertThrows(IllegalArgumentException.class,()->search(query().put("contextBefore",-1)));
        assertThrows(IllegalArgumentException.class,()->search(query().put("contextAfter",21)));
        assertThrows(IllegalArgumentException.class,()->search(query().put("contextBefore",1.5)));
        assertThrows(IllegalArgumentException.class,()->search(query().put("contextBefore","1")));
        assertThrows(IllegalArgumentException.class,()->search(query().put("mode","path").put("query","**")));
    }
    @Test public void noHitsAndDisabledContextHaveDistinctExplicitResults()throws Exception{
        file("a.txt","other\n");JSONObject none=search(query());assertEquals(0,none.getJSONArray("contextBlocks").length());assertTrue(none.getBoolean("contextComplete"));
        JSONObject plain=search(new JSONObject().put("mode","text").put("query","other"));assertFalse(plain.has("contextBlocks"));
    }
}
