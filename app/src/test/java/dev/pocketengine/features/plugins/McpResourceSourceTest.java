package dev.pocketengine.features.plugins;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.skills.SkillBook;
import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual resource RPC and SkillBook files. Installation authority is a test seam, not an app mount claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class McpResourceSourceTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private static final String URI="skill://source-check";
    private static String text(String value){return "---\nname: source-check\ndescription: Original remote source\n---\n"+value+"\n";}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static void resources(McpFixture fixture,AtomicReference<String> body){
        fixture.handler=e->{
            if(e.method().equals("server/discover")){JSONObject d=fixture.discovery();d.getJSONObject("capabilities").put("resources",new JSONObject());e.json(d);}
            else if(e.method().equals("resources/list"))e.json(new JSONObject().put("resultType","complete").put("resources",new JSONArray().put(new JSONObject().put("uri",URI).put("name","Original"))));
            else if(e.method().equals("resources/read"))e.json(new JSONObject().put("resultType","complete").put("contents",new JSONArray().put(new JSONObject().put("uri",URI).put("mimeType","text/markdown").put("text",body.get()))));
            else fixture.standard(e);
        };
    }
    private static JSONObject binding(McpClient client,String text)throws Exception{return new JSONObject().put("pluginId","remote").put("installationId","original-installation").put("stateRevision",1).put("serverDigest",client.serverDigest()).put("resourceURI",URI).put("contentDigest",McpSkillSource.sha256(text)).put("retrievedAt",1000).put("relativeResourcePolicy","unavailable_no_local_root").put("hooksAllowed",false);}
    @Test public void pagedResourceDiscoveryAndExactUtf8ReadNeverInvokeTools()throws Exception {
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            AtomicReference<String> body=new AtomicReference<>(text("你好🙂 $(no-shell)"));resources(f,body);McpFixture.Handler actual=f.handler;
            f.handler=e->{if(e.method().equals("resources/list")){boolean second=e.body.getJSONObject("params").has("cursor");JSONObject page=new JSONObject().put("resultType","complete").put("resources",new JSONArray().put(new JSONObject().put("uri",second?URI:"skill://another")));if(!second)page.put("nextCursor","next");e.json(page);}else actual.handle(e);};
            client.discover(new RunControl());assertTrue(client.supportsResources());String read=client.readSkillResource(URI,new RunControl());assertEquals(body.get(),read);assertEquals(64,McpSkillSource.sha256(read).length());assertEquals(2,f.count("resources/list"));assertEquals(1,f.count("resources/read"));assertEquals(0,f.count("tools/call"));
        }
    }
    @Test public void originalBookReviewsPersistBytesAndObservedChangeRevokesOldApproval()throws Exception {
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            AtomicReference<String> body=new AtomicReference<>(text("first"));resources(f,body);client.discover(new RunControl());File root=temp.newFolder();SkillBook book=new SkillBook(root);
            AtomicReference<String> currentHash=new AtomicReference<>(McpSkillSource.sha256(body.get()));McpSkillSource.Validator current=source->{if(!currentHash.get().equals(source.getString("contentDigest")))throw new SecurityException("source_changed");};book.mountMcpSourceValidation(current);
            String firstText=client.readSkillResource(URI,new RunControl());McpSkillSource.Fetched firstFetched=new McpSkillSource.Fetched(binding(client,firstText),firstText,current);SkillBook.SourceReview review=book.inspectMcpSource(scope(),null,firstFetched,1000);
            assertEquals(0,book.list(scope(),1000).length());assertThrows(SkillBook.SourceUnavailable.class,()->book.applySourceReview(new JSONObject().put("ownerId","other").put("spaceId","private"),review,SkillBook.SourceChoice.REPLACE,false,1100));
            JSONObject first=book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,false,1100);assertFalse(first.getBoolean("enabled"));assertEquals(currentHash.get(),first.getJSONObject("mcpSource").getString("contentDigest"));
            book.setEnabled(scope(),first.getString("id"),true,first.getString("digest"),true,1200);assertEquals(firstText,book.readForHost(scope(),first.getString("id"),"SKILL.md",0,12000,1,first.getString("digest"),Set.of(),1300).getString("content"));
            body.set(text("second"));String next=client.readSkillResource(URI,new RunControl());currentHash.set(McpSkillSource.sha256(next));SkillBook.SourceReview update=book.inspectMcpSource(scope(),first.getString("id"),new McpSkillSource.Fetched(binding(client,next),next,current),1400);
            assertEquals("upstream_changed",update.view().getString("classification"));assertFalse(book.get(scope(),first.getString("id"),1400).getBoolean("enabled"));assertThrows(SecurityException.class,()->book.readForHost(scope(),first.getString("id"),"SKILL.md",0,12000,1,first.getString("digest"),Set.of(),1400));
            JSONObject second=book.applySourceReview(scope(),update,SkillBook.SourceChoice.REPLACE,false,1500);assertEquals(2,second.getInt("revision"));assertFalse(second.getBoolean("enabled"));assertEquals(firstText,Files.readString(new File(root,first.getString("id")+"/1/source-check/SKILL.md").toPath()));
            assertThrows(SkillBook.SourceUnavailable.class,()->book.applySourceReview(scope(),update,SkillBook.SourceChoice.REPLACE,false,1600));assertEquals(0,f.count("tools/call"));
        }
    }
    @Test public void absentResourcesCrossUriMultipleBlocksAndCursorLoopsAreExplicitFailures()throws Exception {
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){client.discover(new RunControl());assertFalse(client.supportsResources());assertThrows(Exception.class,()->client.readSkillResource(URI,new RunControl()));assertEquals(0,f.count("resources/read"));}
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            resources(f,new AtomicReference<>(text("data")));McpFixture.Handler actual=f.handler;f.handler=e->{if(e.method().equals("resources/read"))e.json(new JSONObject().put("resultType","complete").put("contents",new JSONArray().put(new JSONObject().put("uri","file:///private").put("text","trap"))));else actual.handle(e);};
            client.discover(new RunControl());assertThrows(Exception.class,()->client.readSkillResource(URI,new RunControl()));assertEquals(1,f.count("resources/read"));assertEquals(0,f.count("tools/call"));
        }
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            resources(f,new AtomicReference<>(text("data")));McpFixture.Handler actual=f.handler;f.handler=e->{if(e.method().equals("resources/list"))e.json(new JSONObject().put("resultType","complete").put("resources",new JSONArray()).put("nextCursor","cycle"));else actual.handle(e);};
            client.discover(new RunControl());assertThrows(Exception.class,()->client.readSkillResource(URI,new RunControl()));assertEquals(2,f.count("resources/list"));assertEquals(0,f.count("resources/read"));
        }
        for(String uri:new String[]{"file:///private","skill://name/../secret","skill://name/%2e%2e/secret","skill://owner@name/path"})assertThrows(Exception.class,()->McpSkillSource.validateUri(uri));
    }
    @Test public void badFrontmatterAndRemoteHooksNeverBecomePackages()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());McpSkillSource.Validator current=ignored->{};book.mountMcpSourceValidation(current);
        for(String body:new String[]{"not frontmatter", "---\nname: source-check\ndescription: Remote\nhooks: never-run\n---\n$(never-run)"}){
            JSONObject binding=new JSONObject().put("installationId","original").put("resourceURI",URI).put("contentDigest",McpSkillSource.sha256(body));McpSkillSource.Fetched fetched=new McpSkillSource.Fetched(binding,body,current);assertThrows(IllegalArgumentException.class,()->book.inspectMcpSource(scope(),null,fetched,1000));
        }
        assertEquals(0,book.list(scope(),1000).length());
    }
    @Test public void mixedBinaryOversizeAndCredentialReflectionAreRejectedWithoutDroppingBlocks()throws Exception {
        JSONArray[] replies={
                new JSONArray().put(new JSONObject().put("uri",URI).put("text",text("one"))).put(new JSONObject().put("uri",URI).put("text","extra")),
                new JSONArray().put(new JSONObject().put("uri",URI).put("mimeType","application/octet-stream").put("blob","Ynl0ZXM=")),
                new JSONArray().put(new JSONObject().put("uri",URI).put("text","x".repeat(64001))),
                new JSONArray().put(new JSONObject().put("uri",URI).put("text",text("credential-value")))
        };
        for(JSONArray contents:replies)try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"credential-value")){
            resources(f,new AtomicReference<>(text("safe")));McpFixture.Handler actual=f.handler;
            f.handler=e->{if(e.method().equals("resources/read"))e.json(new JSONObject().put("resultType","complete").put("contents",contents));else actual.handle(e);};
            client.discover(new RunControl());Exception failure=assertThrows(Exception.class,()->client.readSkillResource(URI,new RunControl()));assertFalse(failure.getMessage().contains("credential-value"));assertEquals(1,f.count("resources/read"));assertEquals(0,f.count("tools/call"));
        }
    }
    @Test public void rejectedSourceCannotRestoreOldApprovalWhenIdenticalBytesReturn()throws Exception {
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            AtomicReference<String> body=new AtomicReference<>(text("same bytes"));resources(f,body);client.discover(new RunControl());String actual=client.readSkillResource(URI,new RunControl()),hash=McpSkillSource.sha256(actual);
            AtomicReference<String> currentHash=new AtomicReference<>(hash);McpSkillSource.Validator current=source->{if(!source.getString("contentDigest").equals(currentHash.get()))throw new SecurityException("source_missing");};
            SkillBook book=new SkillBook(temp.newFolder());book.mountMcpSourceValidation(current);SkillBook.SourceReview review=book.inspectMcpSource(scope(),null,new McpSkillSource.Fetched(binding(client,actual),actual,current),1000);
            JSONObject original=book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,false,1100);book.setEnabled(scope(),original.getString("id"),true,original.getString("digest"),true,1200);
            currentHash.set("missing");assertThrows(SecurityException.class,()->book.readForHost(scope(),original.getString("id"),"SKILL.md",0,12000,1,original.getString("digest"),Set.of(),1300));
            currentHash.set(hash);assertThrows(SecurityException.class,()->book.readForHost(scope(),original.getString("id"),"SKILL.md",0,12000,1,original.getString("digest"),Set.of(),1400));
            JSONObject currentRecord=book.get(scope(),original.getString("id"),1400);assertFalse(currentRecord.getBoolean("enabled"));assertEquals("",currentRecord.getString("approvedDigest"));
        }
    }
    @Test public void escapedUnpairedUnicodeIsRejectedAtTheActualResourceWireBoundary()throws Exception {
        for(String escape:new String[]{String.valueOf((char)92)+"ud800",String.valueOf((char)92)+"udc00"})try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            resources(f,new AtomicReference<>(text("MARKER")));McpFixture.Handler original=f.handler;
            f.handler=e->{if(e.method().equals("resources/read")){
                String json=e.envelope(new JSONObject().put("resultType","complete").put("contents",new JSONArray().put(new JSONObject().put("uri",URI).put("text",text("MARKER"))))).toString();
                e.raw(200,"application/json",json.replace("MARKER",escape));
            }else original.handle(e);};
            client.discover(new RunControl());assertThrows(Exception.class,()->client.readSkillResource(URI,new RunControl()));assertEquals(1,f.count("resources/read"));assertEquals(0,f.count("tools/call"));
        }
    }
    @Test public void sourceDigestRejectsUnpairedCodeUnitsAndPreservesValidEmojiAndCombiningText()throws Exception {
        for(char unit:new char[]{(char)0xd800,(char)0xd801,(char)0xdc00})assertThrows(Exception.class,()->McpSkillSource.sha256(String.valueOf(unit)));
        String unicode=text("e\u0301 / 你好 / 🙂");assertEquals(McpSkillSource.sha256(unicode),McpSkillSource.sha256(new String(unicode.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8)));
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            resources(f,new AtomicReference<>(unicode));client.discover(new RunControl());assertEquals(unicode,client.readSkillResource(URI,new RunControl()));
        }
    }
}
