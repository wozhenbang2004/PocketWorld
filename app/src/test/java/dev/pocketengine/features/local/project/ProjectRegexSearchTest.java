package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import java.nio.file.*;
import org.json.*;
import org.junit.*;

public final class ProjectRegexSearchTest {
    private Path root;
    @Before public void create()throws Exception{root=Files.createTempDirectory("project-regex-search-");}
    @After public void close()throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private void file(String path,String text)throws Exception{Path target=root.resolve(path);Files.createDirectories(target.getParent());writeUtf8(target,text);}
    private JSONObject query(String expression)throws Exception{return new JSONObject().put("mode","text").put("syntax","regex").put("query",expression);}
    private JSONObject search(JSONObject input)throws Exception{return ProjectFileQueries.search(root.toFile(),"11111111-1111-1111-1111-111111111111",input,()->{});}

    @Test public void unicodeRegexFindsRealLineAndUtf16MatchRange()throws Exception{
        file("src/a.txt","outside\n🙂中文 user_42 END\nother\n");JSONObject result=search(query("中文\\s+user_[0-9]+"));
        assertEquals("regex_re2j_line",result.getString("searchSyntax"));assertEquals("RE2J_1.8",result.getString("regexEngine"));
        JSONObject match=result.getJSONArray("matches").getJSONObject(0);assertEquals(2,match.getInt("line"));assertEquals(3,match.getInt("column"));assertEquals(13,match.getInt("endColumn"));assertEquals("UTF16_code_units",match.getString("columnUnit"));
        assertEquals("first_match_per_line",result.getString("matchSemantics"));
    }
    @Test public void literalDefaultsDoNotInterpretRegularExpressionMetacharacters()throws Exception{
        file("a.txt","aaab\na+b\n");JSONObject plain=search(new JSONObject().put("mode","text").put("query","a+b"));assertEquals(1,plain.getJSONArray("matches").length());assertEquals(2,plain.getJSONArray("matches").getJSONObject(0).getInt("line"));
        assertEquals(1,search(query("a+b")).getJSONArray("matches").getJSONObject(0).getInt("line"));
    }
    @Test public void unsupportedOrInvalidRegexFailsRatherThanFallingBackToJava()throws Exception{
        file("a.txt","aabb\n");for(String expression:new String[]{"(a)\\1","a(?=b)","(?<=a)b","[","(?:a{1000}){1000}"})assertThrows(expression,IllegalArgumentException.class,()->search(query(expression)));
        assertThrows(IllegalArgumentException.class,()->search(query("aa").put("syntax","java")));
        assertThrows(IllegalArgumentException.class,()->search(query("**").put("mode","path")));
        assertThrows(IllegalArgumentException.class,()->search(query("first\nsecond")));
    }
    @Test public void regexCursorBindsSyntaxAndKeepsContextFromTheSameSha()throws Exception{
        file("a.txt","before\nneedle1\nmiddle\nneedle2\nafter\n");JSONObject input=query("needle[0-9]").put("contextBefore",1).put("contextAfter",1).put("limit",1),first=search(input);
        assertTrue(first.getBoolean("hasMore"));assertEquals(3,first.getJSONArray("contextBlocks").getJSONObject(0).getJSONArray("lines").length());
        assertEquals(4,search(new JSONObject(input.toString()).put("cursor",first.getJSONObject("nextCursor"))).getJSONArray("matches").getJSONObject(0).getInt("line"));
        assertThrows(SecurityException.class,()->search(new JSONObject(input.toString()).put("syntax","literal").put("cursor",first.getJSONObject("nextCursor"))));
    }
    @Test(timeout=2000) public void adversarialNestedQuantifierDoesNotUseBacktracking()throws Exception{
        file("a.txt","a".repeat(180000)+"!\n");JSONObject result=search(query("(a+)+$"));assertEquals(0,result.getJSONArray("matches").length());assertEquals("RE2J_1.8",result.getString("regexEngine"));
    }
    @Test public void zeroLengthMatchesAndUnicodeCaseFoldingRemainBounded()throws Exception{
        file("a.txt","\nÄBC\n尾行\n");JSONObject empty=search(query("^$"));assertEquals(1,empty.getJSONArray("matches").length());assertEquals(1,empty.getJSONArray("matches").getJSONObject(0).getInt("endColumn"));
        assertEquals(2,search(query("äbc").put("caseSensitive",false)).getJSONArray("matches").getJSONObject(0).getInt("line"));assertEquals(0,search(query("äbc")).getJSONArray("matches").length());
    }
    @Test public void originalCancellationIsCheckedInsideLongRegexMatching()throws Exception{
        ProjectSearchPattern pattern=new ProjectSearchPattern("(a+)+$",true);java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(InterruptedException.class,()->pattern.find("a".repeat(180000)+"!",()->{if(checks.incrementAndGet()>2)throw new InterruptedException("original run cancelled inside regex");}));
        assertTrue(checks.get()>2);
    }
    @Test public void countedRepeatBudgetDoesNotInterpretEscapedOrQuotedBraces()throws Exception{
        file("a.txt","aaa\n{1000}{1000}\n");assertEquals(1,search(query("^a{3}$")).getJSONArray("matches").length());
        assertEquals(2,search(query("\\Q{1000}{1000}\\E")).getJSONArray("matches").getJSONObject(0).getInt("line"));
        assertEquals(2,search(query("\\{1000\\}\\{1000\\}")).getJSONArray("matches").getJSONObject(0).getInt("line"));
        assertThrows(IllegalArgumentException.class,()->new ProjectSearchPattern("(?:a{1000}){1000}",true));
    }
    private static java.nio.file.Path writeUtf8(java.nio.file.Path path,String text)throws java.io.IOException{return Files.write(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}

}
