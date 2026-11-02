package dev.pocketengine.features.skills;

import dev.pocketengine.testutil.HttpServer.Exchange;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real OkHttp GET/JSON/base64/byte-hash protocol, not a production GitHub or Android fixture. */
public final class SkillGitProtocolTest {
    static final String COMMIT="a".repeat(40),ROOT="b".repeat(40),SUBTREE="c".repeat(40);
    static String manifest(String version){return "---\nname: source-check\ndescription: 检查固定版本\nmetadata:\n  version: '"+version+"'\n---\n未经信任的参考，不授予工具权限。\n";}
    static JSONObject files(String version)throws Exception{return new JSONObject().put("SKILL.md",manifest(version)).put("reference.txt","准确文本🙂\r\n"+version);}
    static final class Server implements AutoCloseable {
        final HttpServer server;final Map<String,String> responses=new LinkedHashMap<>();final AtomicInteger requests=new AtomicInteger();
        final Map<String,AtomicInteger> paths=new java.util.concurrent.ConcurrentHashMap<>();
        int updates;volatile String commit=COMMIT;
        volatile int status=200;volatile String redirect;volatile Runnable beforeTree;volatile boolean rateLimited;
        Server(JSONObject files)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);setFiles(files);
            server.createContext("/",this::handle);server.start();
        }
        synchronized void setFiles(JSONObject files)throws Exception {
            commit=updates++==0?COMMIT:SkillGitClient.blobHash(files.toString().getBytes(StandardCharsets.UTF_8));
            responses.clear();responses.put("/repos/demo/skills/commits",new JSONArray().put(new JSONObject().put("sha",commit).put("commit",new JSONObject().put("tree",new JSONObject().put("sha",ROOT)))).toString());
            JSONArray entries=new JSONArray(),names=SkillBook.resourceNames(files);for(int at=0;at<names.length();at++) {
                String name=names.getString(at);
                byte[] bytes=files.getString(name).getBytes(StandardCharsets.UTF_8);String sha=SkillGitClient.blobHash(bytes);
                entries.put(new JSONObject().put("path",name).put("type","blob").put("mode","100644").put("sha",sha).put("size",bytes.length));
                responses.put("/repos/demo/skills/git/blobs/"+sha,new JSONObject().put("sha",sha).put("encoding","base64").put("size",bytes.length).put("content",Base64.getMimeEncoder().encodeToString(bytes)).toString());
            }
            responses.put("/repos/demo/skills/git/trees/"+SUBTREE,new JSONObject().put("sha",SUBTREE).put("truncated",false).put("tree",entries).toString());
            responses.put("/repos/demo/skills/git/trees/"+ROOT,new JSONObject().put("sha",ROOT).put("truncated",false).put("tree",new JSONArray().put(new JSONObject().put("path","selected").put("type","tree").put("mode","040000").put("sha",SUBTREE))).toString());
        }
        void handle(Exchange exchange)throws IOException {
            requests.incrementAndGet();String path=exchange.getRequestURI().getPath();paths.computeIfAbsent(path,k->new AtomicInteger()).incrementAndGet();
            try {
                if(path.contains("/git/trees/")&&beforeTree!=null){Runnable call=beforeTree;beforeTree=null;call.run();}
                String response; synchronized(this){response=responses.get(path);}
                int code=status;if(response==null)code=404;if(redirect!=null)exchange.getResponseHeaders().add("Location",redirect);if(rateLimited)exchange.getResponseHeaders().add("X-RateLimit-Remaining","0");
                byte[] body=(response==null?"{}":response).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        }
        SkillGitClient client(){return new SkillGitClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Clock.fixed(Instant.ofEpochMilli(2000),ZoneOffset.UTC));}
        @Override public void close(){server.stop(0);}
    }
    static SkillGitSource source(){return new SkillGitSource("https://github.com/demo/skills.git","main","selected");}
    @Test public void actualSubtreeReadPinsCommitAndExactContentWithoutFetchingUnselectedTree()throws Exception {
        try(Server server=new Server(files("1"))) {
            SkillGitSource.Fetched result=server.client().fetch(source(),new RunControl());
            assertEquals(COMMIT,result.commit);assertEquals(2000,result.observedAt);assertEquals(SkillBook.digest(files("1")),result.digest);assertEquals("准确文本🙂\r\n1",result.files().getString("reference.txt"));
            assertEquals(5,server.requests.get());assertEquals(1,server.paths.get("/repos/demo/skills/git/trees/"+ROOT).get());
            JSONObject view=result.view();assertFalse(view.getBoolean("bodyIncluded"));assertFalse(view.getBoolean("executionAuthorized"));assertFalse(view.toString().contains("未经信任"));
            JSONObject copy=result.files();copy.put("reference.txt","different");assertNotEquals("different",result.files().getString("reference.txt"));
        }
    }
    @Test public void sourceIdentityRejectsCredentialsRedirectOriginsAndImplicitRefs()throws Exception {
        for(String repository:new String[]{"http://github.com/demo/skills","https://user:secret@github.com/demo/skills","https://github.com/demo/skills?token=secret","https://example.com/demo/skills","https://github.com/demo/skills/tree/main","https://github.com:443/demo/skills"})assertThrows(repository,IllegalArgumentException.class,()->new SkillGitSource(repository,"main","selected"));
        for(String ref:new String[]{"","../main","x..y","main/","a//b","a.lock","x y"})assertThrows(ref,IllegalArgumentException.class,()->new SkillGitSource("https://github.com/demo/skills",ref,"selected"));
        assertThrows(IllegalArgumentException.class,()->new SkillGitSource("https://github.com/demo/skills","main","../outside"));
        JSONObject forged=source().toJson().put("token","not-allowed");assertThrows(IllegalArgumentException.class,()->SkillGitSource.fromJson(forged));
    }
    @Test public void onlyResolvedMissingPathIsGoneNotHttp404OrPermissions()throws Exception {
        try(Server server=new Server(files("1"))) {
            SkillGitClient.Unavailable missing=assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(new SkillGitSource("https://github.com/demo/skills","main","missing"),new RunControl()));assertEquals("gone",missing.code);
            server.status=404;SkillGitClient.Unavailable unknown=assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl()));assertEquals("source_unavailable",unknown.code);assertEquals(404,unknown.httpStatus);
        }
    }
    @Test public void rateLimitedAndRedirectedReadsNeverReturnPartialPackages()throws Exception {
        try(Server server=new Server(files("1"))) {
            server.status=403;server.rateLimited=true;assertEquals("rate_limited",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);
            server.status=302;server.redirect="http://127.0.0.1:1/never-follow";assertEquals("source_unavailable",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);assertEquals(2,server.requests.get());
        }
    }
    @Test public void malformedOversizedTreeOrTrailingJsonCannotLookComplete()throws Exception {
        try(Server server=new Server(files("1"))) {
            String path="/repos/demo/skills/git/trees/"+ROOT,original=server.responses.get(path);
            server.responses.put(path,new JSONObject(original).put("truncated",true).toString());assertEquals("read_limited",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);
            server.responses.put(path,original+" trailing");assertEquals("source_unavailable",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);
            server.responses.put(path," ".repeat(SkillGitClient.MAX_JSON_BYTES+1));assertEquals("read_limited",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);
        }
    }
    @Test public void linksSubmodulesAndTooManyFilesAreExplicitlyRefused()throws Exception {
        for(String mode:new String[]{"120000","160000"})try(Server server=new Server(files("1"))) {
            String path="/repos/demo/skills/git/trees/"+SUBTREE;JSONObject tree=new JSONObject(server.responses.get(path));tree.getJSONArray("tree").getJSONObject(0).put("mode",mode);server.responses.put(path,tree.toString());
            assertEquals("unsupported_entry",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);
        }
        JSONObject many=files("1");for(int i=0;i<40;i++)many.put("r"+i+".txt","text");
        try(Server server=new Server(many)){assertEquals("read_limited",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);}
    }
    @Test public void blobHashMismatchCannotBecomeAnInstalledSnapshot()throws Exception {
        try(Server server=new Server(files("1"))) {
            String path="/repos/demo/skills/git/blobs/"+SkillGitClient.blobHash(manifest("1").getBytes(StandardCharsets.UTF_8));JSONObject blob=new JSONObject(server.responses.get(path));
            byte[] corrupt=manifest("1").getBytes(StandardCharsets.UTF_8);corrupt[corrupt.length-2]='!';blob.put("content",Base64.getEncoder().encodeToString(corrupt));server.responses.put(path,blob.toString());
            assertEquals("source_unavailable",assertThrows(SkillGitClient.Unavailable.class,()->server.client().fetch(source(),new RunControl())).code);
        }
    }
    @Test public void cancellationBeforeFetchIsZeroHttpAndInFlightCancellationIsNotOffline()throws Exception {
        try(Server server=new Server(files("1"))) {
            RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->server.client().fetch(source(),cancelled));assertEquals(0,server.requests.get());
            RunControl original=new RunControl();server.beforeTree=original::cancel;assertThrows(InterruptedException.class,()->server.client().fetch(source(),original));assertTrue(original.isCancelled());
        }
    }
}
