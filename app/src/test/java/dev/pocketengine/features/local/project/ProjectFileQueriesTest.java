package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;

public final class ProjectFileQueriesTest {
    private Path root;private static final String CHECKOUT="11111111-1111-1111-1111-111111111111";
    @Before public void create()throws Exception{root=Files.createTempDirectory("c2-native-file-query-");}
    @After public void close()throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void file(String path,String text)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());Files.write(target,text.getBytes(StandardCharsets.UTF_8));}
    private JSONObject read(JSONObject input)throws Exception{return ProjectFileQueries.read(root.toFile(),CHECKOUT,input,()->{});}
    private JSONObject search(JSONObject input)throws Exception{return ProjectFileQueries.search(root.toFile(),CHECKOUT,input,()->{});}
    @Test public void rangedReadKeepsWholeFileShaWithoutLoadingWholeBodyIntoResult()throws Exception{
        String content="中文🙂 first\n"+"x".repeat(100000)+"\nlast\n";file("src/a.txt",content);
        JSONObject page=read(new JSONObject().put("path","src/a.txt").put("maxChars",12));assertEquals(content.substring(0,12),page.getString("content"));assertEquals(ProjectSources.sha256(content.getBytes(StandardCharsets.UTF_8)),page.getString("sha256"));assertEquals(content.length(),page.getLong("totalChars"));assertTrue(page.toString().length()<1000);assertFalse(page.getBoolean("complete"));
        JSONObject last=read(new JSONObject().put("path","src/a.txt").put("rangeMode","lines").put("startLine",3).put("maxLines",1).put("expectedHash",page.getString("sha256")));assertEquals("last\n",last.getString("content"));assertTrue(last.getBoolean("complete"));
    }
    @Test public void unicodeRangesNeverReturnHalfSurrogateOrPretendSplitUtf8IsText()throws Exception{
        file("unicode.txt","a🙂中b");JSONObject page=read(new JSONObject().put("path","unicode.txt").put("maxChars",2));assertEquals("a",page.getString("content"));assertEquals(1,page.getInt("nextOffset"));
        assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","unicode.txt").put("offset",2)));
        assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","unicode.txt").put("rangeMode","bytes").put("offset",1).put("maxBytes",2)));
        assertEquals("🙂",read(new JSONObject().put("path","unicode.txt").put("rangeMode","bytes").put("offset",1).put("maxBytes",4)).getString("content"));
    }
    @Test public void binaryMetadataDoesNotMasqueradeAsEmptyText()throws Exception{
        Files.write(root.resolve("binary.dat"),new byte[]{0,(byte)255,1});JSONObject result=read(new JSONObject().put("path","binary.dat"));assertTrue(result.getBoolean("binary"));assertTrue(result.getBoolean("contentOmitted"));assertFalse(result.has("content"));assertEquals(3,result.getLong("bytes"));assertFalse(result.getBoolean("complete"));
    }
    @Test public void shaPinnedPaginationRejectsChangedFile()throws Exception{
        file("a.txt","before\n");JSONObject before=read(new JSONObject().put("path","a.txt"));file("a.txt","after\n");assertThrows(SecurityException.class,()->read(new JSONObject().put("path","a.txt").put("expectedHash",before.getString("sha256"))));
    }
    @Test public void literalAndPathGlobUseNativeLocationsAndCursorVersion()throws Exception{
        file("src/a.js","const first = 'needle';\nconst second = 'needle';\n");file("src/b.js","NEEDLE tail\n");file("src/nested/c.js","needle nested\n");file("README.md","needle outside include\n");
        JSONObject query=new JSONObject().put("mode","text").put("query","needle").put("include","src/**/*.js").put("caseSensitive",false).put("limit",1);
        JSONObject page=search(query);assertEquals("src/a.js",page.getJSONArray("matches").getJSONObject(0).getString("path"));assertEquals(1,page.getJSONArray("matches").getJSONObject(0).getInt("line"));assertTrue(page.getBoolean("hasMore"));
        JSONObject next=search(new JSONObject(query.toString()).put("cursor",page.getJSONObject("nextCursor")));assertEquals(2,next.getJSONArray("matches").getJSONObject(0).getInt("line"));
        file("src/b.js","changed needle\n");assertThrows(SecurityException.class,()->search(new JSONObject(query.toString()).put("cursor",page.getJSONObject("nextCursor"))));
        JSONObject paths=search(new JSONObject().put("mode","path").put("query","src/**/*.js"));assertEquals(3,paths.getJSONArray("matches").length());assertTrue(paths.getBoolean("coverageComplete"));
    }
    @Test public void hiddenCacheBinaryAndSymlinkScopesStayExplicit()throws Exception{
        file(".hidden.txt","secret needle\n");file("node_modules/dependency.js","needle\n");file("dist/out.js","needle\n");file("visible.txt","needle\n");Files.write(root.resolve("bytes.dat"),new byte[]{0,1});Files.createSymbolicLink(root.resolve("link.txt"),root.resolve("visible.txt"));
        JSONObject result=search(new JSONObject().put("mode","text").put("query","needle"));assertEquals(1,result.getJSONArray("matches").length());assertEquals(1,result.getInt("binaryFilesSkippedForText"));assertFalse(result.getBoolean("coverageComplete"));assertEquals(1,result.getInt("skippedCount"));
        assertEquals(2,search(new JSONObject().put("mode","text").put("query","needle").put("hidden",true)).getJSONArray("matches").length());
        assertThrows(SecurityException.class,()->read(new JSONObject().put("path","link.txt")));assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","../outside")));
    }
    @Test public void cancellationStopsNativeIoAndDoesNotReturnSuccessfulEmptySearch()throws Exception{
        file("a.txt","needle\n".repeat(1000));AtomicInteger checks=new AtomicInteger();assertThrows(Exception.class,()->ProjectFileQueries.search(root.toFile(),CHECKOUT,new JSONObject().put("mode","text").put("query","needle"),()->{if(checks.incrementAndGet()>2)throw new InterruptedException("original task cancelled");}));
    }
    @Test public void longLineIsLocatedAtItsRealColumnAndResultStaysBounded()throws Exception{
        file("long.txt","a".repeat(80000)+"needle"+"b".repeat(80000));JSONObject result=search(new JSONObject().put("mode","text").put("query","needle"));JSONObject match=result.getJSONArray("matches").getJSONObject(0);assertEquals(80001,match.getInt("column"));assertTrue(match.getBoolean("linePreviewTruncated"));assertTrue(match.getString("text").length()<500);assertTrue(result.toString().length()<3000);
    }
    @Test public void boundedGlobHandlesZeroAndMultipleDirectoriesWithoutSingleStarCrossingSlash()throws Exception{
        String[][] yes={{"**/*.js","a.js"},{"**/*.js","src/a.js"},{"src/**/*.js","src/a.js"},{"src/**/*.js","src/one/two/a.js"},{"a/**/b","a/b"},{"a/**/b","a/x/y/b"},{"a/*/b","a/x/b"},{"a/**","a/x/y"}};
        String[][] no={{"*.js","src/a.js"},{"src/*.js","src/nested/a.js"},{"a/**/b","a/xb"},{"a/*/b","a/x/y/b"},{"**/b","ab"}};
        for(String[] example:yes)assertTrue(java.util.Arrays.toString(example),ProjectFileQueries.glob(example[0]).matches(example[1]));for(String[] example:no)assertFalse(java.util.Arrays.toString(example),ProjectFileQueries.glob(example[0]).matches(example[1]));
    }
    @Test public void globQuestionUsesOneUnicodeCodePointAndMetacharactersStayLiteral()throws Exception{
        assertTrue(ProjectFileQueries.glob("?.txt").matches("🙂.txt"));assertFalse(ProjectFileQueries.glob("??.txt").matches("🙂.txt"));assertTrue(ProjectFileQueries.glob("[real].txt").matches("[real].txt"));assertFalse(ProjectFileQueries.glob("[real].txt").matches("r.txt"));assertThrows(IllegalArgumentException.class,()->ProjectFileQueries.glob("a".repeat(241)));
    }
    @Test(timeout=2000) public void adversarialGlobUsesBoundedDynamicProgrammingRatherThanRegexBacktracking()throws Exception{
        assertFalse(ProjectFileQueries.glob("*a".repeat(100)+"b").matches("a".repeat(200)+"c"));assertTrue(ProjectFileQueries.glob("**a".repeat(70)+"*").matches("a".repeat(220)));file("small.js","needle");assertEquals("bounded_dynamic_programming",search(new JSONObject().put("mode","path").put("query","**/*.js")).getString("pathGlobEngine"));
    }
    @Test public void singleUtf16UnitPageRejectsZeroProgressInsteadOfRepeatingEmojiCursor()throws Exception{
        file("emoji.txt","🙂x");assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","emoji.txt").put("maxChars",1)));JSONObject page=read(new JSONObject().put("path","emoji.txt").put("maxChars",2));assertEquals("🙂",page.getString("content"));assertEquals(2,page.getInt("nextOffset"));assertEquals("x",read(new JSONObject().put("path","emoji.txt").put("offset",2)).getString("content"));assertThrows(IllegalArgumentException.class,()->read(new JSONObject().put("path","emoji.txt").put("rangeMode","bytes").put("maxBytes",0)));
    }
    @Test public void multilineLiteralIsExplicitlyRejectedAndEmptyIncludeMeansNoExtraFilter()throws Exception{
        file("src/NAME.js","first needle\nsecond\n");assertThrows(IllegalArgumentException.class,()->search(new JSONObject().put("mode","text").put("query","needle\nsecond")));assertEquals(1,search(new JSONObject().put("mode","text").put("query","needle").put("include","")).getJSONArray("matches").length());assertEquals(1,search(new JSONObject().put("mode","path").put("query","**/name.js").put("caseSensitive",false)).getJSONArray("matches").length());
    }

}
