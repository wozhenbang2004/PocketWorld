package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.skills.SkillBook;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real loopback RPC, original module/vault, and actual SkillBook files; no tool or script dispatch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class McpResourceSkillJourneyTest {
    private static final String URI="skill://source-check";
    private static String manifest(String version){return "---\nname: source-check\ndescription: Remote source review\nmetadata:\n  version: "+version+"\n---\nOriginal instructions "+version+" $(do-not-execute)\n";}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject foreign()throws Exception{return new JSONObject().put("ownerId","another-owner").put("spaceId","private");}
    private static final class Journey implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();
        final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),e->{});
        final PluginsModule plugins=new PluginsModule(context);
        final SkillsModule skills=new SkillsModule(context);
        final McpFixture server=new McpFixture();
        final AtomicReference<String> text=new AtomicReference<>(manifest("1"));
        JSONObject installation;
        Journey()throws Exception{
            skills.mountMcpSources(plugins);
            server.handler=e->{
                if(e.method().equals("server/discover")||e.method().equals("initialize")){
                    if(!server.modern)e.headers.put("MCP-Session-Id",server.session);
                    JSONObject discovery=server.discovery();discovery.getJSONObject("capabilities").put("resources",new JSONObject());e.json(discovery);
                }else if(e.method().equals("resources/list"))e.json(new JSONObject().put("resultType","complete").put("resources",new JSONArray().put(new JSONObject().put("uri",URI).put("name","Source check").put("mimeType","text/markdown"))));
                else if(e.method().equals("resources/read"))e.json(new JSONObject().put("resultType","complete").put("contents",new JSONArray().put(new JSONObject().put("uri",URI).put("mimeType","text/markdown").put("text",text.get()))));
                else server.standard(e);
            };
            JSONObject preview=plugins.handle("discoverMcp",new JSONObject().put("id","remote").put("name","Original MCP").put("endpoint",server.endpoint()));
            installation=plugins.handle("installMcp",new JSONObject().put("previewId",preview.getString("previewId")).put("tools",new JSONArray().put("lookup")));
        }
        SkillBook.SourceReview inspect(String id)throws Exception{return skills.inspectMcpSource(scope(),id,"remote",installation.getString("installationId"),installation.getLong("stateRevision"),URI,new RunControl());}
        JSONObject apply(SkillBook.SourceReview review)throws Exception{return skills.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,false).getJSONObject("skill");}
        JSONObject first()throws Exception{return apply(inspect(null));}
        void approve(JSONObject skill)throws Exception{skills.handle("setEnabled",new JSONObject().put("scope",scope()).put("id",skill.getString("id")).put("enabled",true).put("confirmed",true).put("acceptedDigest",skill.getString("digest")));}
        @Override public void close()throws Exception{plugins.close();server.close();}
    }
    @Test public void actualResourcesBecomeDisabledScopedReviewAndExactBytesWithoutToolAuthority()throws Exception {
        try(Journey j=new Journey()){
            JSONObject listing=j.skills.listMcpSkillResources("remote",j.installation.getString("installationId"),j.installation.getLong("stateRevision"),new RunControl());
            assertEquals(URI,listing.getJSONArray("resources").getJSONObject(0).getString("uri"));assertTrue(listing.getBoolean("metadataOnly"));
            SkillBook.SourceReview review=j.inspect(null);assertEquals("mcp_resource_update",review.view().getString("kind"));
            assertEquals(McpSkillSource.sha256(j.text.get()),review.view().getJSONObject("source").getString("contentDigest"));
            assertEquals(0,j.skills.snapshot().getJSONArray("skills").length());
            assertThrows(SkillBook.SourceUnavailable.class,()->j.skills.applySourceReview(foreign(),review,SkillBook.SourceChoice.REPLACE,false));
            JSONObject skill=j.apply(review);assertFalse(skill.getBoolean("enabled"));assertEquals("",skill.getString("approvedDigest"));
            assertEquals(j.installation.getString("installationId"),skill.getJSONObject("mcpSource").getString("installationId"));
            assertThrows(SecurityException.class,()->j.skills.readForHost(scope(),skill.getString("id"),"SKILL.md",0,12000,1,skill.getString("digest"),Set.of()));
            j.approve(skill);assertEquals(j.text.get(),j.skills.readForHost(scope(),skill.getString("id"),"SKILL.md",0,12000,1,skill.getString("digest"),Set.of()).getString("content"));
            assertThrows(IllegalArgumentException.class,()->j.skills.readForHost(scope(),skill.getString("id"),"references/secret.txt",0,20,1,skill.getString("digest"),Set.of()));
            assertThrows(SkillBook.SourceUnavailable.class,()->j.apply(review));assertEquals(0,j.server.count("tools/call"));assertEquals(0,j.plugins.toolRegistry().length());
        }
    }
    @Test public void observedRemoteChangeRevokesApprovalAndOldReviewCannotInstallNewBytes()throws Exception {
        try(Journey j=new Journey()){
            JSONObject first=j.first();j.approve(first);SkillBook.SourceReview stale=j.inspect(first.getString("id"));j.text.set(manifest("2"));
            SkillBook.SourceReview changed=j.inspect(first.getString("id"));assertEquals("upstream_changed",changed.view().getString("classification"));
            assertFalse(j.skills.handle("get",new JSONObject().put("id",first.getString("id"))).getJSONObject("skill").getBoolean("enabled"));
            assertThrows(SecurityException.class,()->j.skills.readForHost(scope(),first.getString("id"),"SKILL.md",0,12000,1,first.getString("digest"),Set.of()));
            assertThrows(Exception.class,()->j.apply(stale));JSONObject second=j.apply(changed);assertEquals(2,second.getInt("revision"));assertFalse(second.getBoolean("enabled"));
            assertEquals(McpSkillSource.sha256(j.text.get()),second.getJSONObject("mcpSource").getString("contentDigest"));assertEquals(0,j.server.count("tools/call"));
        }
    }
    @Test public void invalidRemoteHooksStillRevokeOldApprovalAndNeverExecute()throws Exception {
        try(Journey j=new Journey()){
            JSONObject first=j.first();j.approve(first);j.text.set("---\nname: source-check\ndescription: Invalid remote hook\nhooks: run-something\n---\nDo not run\n");
            assertThrows(IllegalArgumentException.class,()->j.inspect(first.getString("id")));
            JSONObject current=j.skills.handle("get",new JSONObject().put("id",first.getString("id"))).getJSONObject("skill");assertFalse(current.getBoolean("enabled"));assertEquals("",current.getString("approvedDigest"));assertEquals(1,current.getInt("revision"));
            assertEquals(0,j.server.count("tools/call"));
        }
    }
    @Test public void installationRemovalBlocksSourceReadsButPreservesLocalEvidence()throws Exception {
        try(Journey j=new Journey()){
            JSONObject first=j.first();j.approve(first);File bytes=new File(j.android.getFilesDir(),"skills/"+first.getString("id")+"/1/source-check/SKILL.md");String original=new String(Files.readAllBytes(bytes.toPath()),StandardCharsets.UTF_8);
            j.plugins.handle("remove",new JSONObject().put("id","remote"));
            assertThrows(SecurityException.class,()->j.skills.readForHost(scope(),first.getString("id"),"SKILL.md",0,12000,1,first.getString("digest"),Set.of()));
            assertEquals(1,j.skills.reconcileMcpSources(scope()).getInt("revoked"));assertEquals(original,new String(Files.readAllBytes(bytes.toPath()),StandardCharsets.UTF_8));assertTrue(new File(bytes.getParentFile().getParentFile(),"retained.json").isFile());
        }
    }
    @Test public void absentCapabilityPaginationLoopAndCrossUriAreBoundedWithoutReads()throws Exception {
        try(McpFixture f=new McpFixture();McpClient client=new McpClient(f.endpoint(),"")){
            client.discover(new RunControl());assertFalse(client.supportsResources());assertThrows(Exception.class,()->client.readSkillResource(URI,new RunControl()));assertEquals(0,f.count("resources/read"));
        }
        try(Journey j=new Journey()){
            j.server.handler=e->{if(e.method().equals("resources/list"))e.json(new JSONObject().put("resultType","complete").put("resources",new JSONArray()).put("nextCursor","loop"));else if(e.method().equals("server/discover")){JSONObject d=j.server.discovery();d.getJSONObject("capabilities").put("resources",new JSONObject());e.json(d);}else j.server.standard(e);};
            assertThrows(Exception.class,()->j.inspect(null));assertEquals(2,j.server.count("resources/list"));assertEquals(0,j.server.count("resources/read"));
        }
        for(String uri:new String[]{"file:///etc/passwd","https://example.invalid/skill","skill://check/../secret","skill://check/%2e%2e/secret","skill://owner@check/name"})assertThrows(Exception.class,()->McpSkillSource.validateUri(uri));
    }
    @Test public void listingDeletionAndSameShaReturnAlwaysRequireNewApproval()throws Exception {
        try(Journey j=new Journey()){
            JSONObject first=j.first();j.approve(first);McpFixture.Handler original=j.server.handler;
            j.server.handler=e->{if(e.method().equals("resources/list"))e.json(new JSONObject().put("resultType","complete").put("resources",new JSONArray()));else original.handle(e);};
            j.skills.listMcpSkillResources("remote",j.installation.getString("installationId"),j.installation.getLong("stateRevision"),new RunControl());
            JSONObject revoked=j.skills.handle("get",new JSONObject().put("id",first.getString("id"))).getJSONObject("skill");assertFalse(revoked.getBoolean("enabled"));assertEquals("",revoked.getString("approvedDigest"));
            j.server.handler=original;j.inspect(first.getString("id"));assertThrows(SecurityException.class,()->j.skills.readForHost(scope(),first.getString("id"),"SKILL.md",0,12000,1,first.getString("digest"),Set.of()));
        }
    }
    @Test public void listingFailureCannotPreserveApprovalForLaterSameShaRecovery()throws Exception {
        try(Journey j=new Journey()){
            JSONObject first=j.first();j.approve(first);McpFixture.Handler original=j.server.handler;
            j.server.handler=e->{if(e.method().equals("resources/list"))e.empty(503);else original.handle(e);};
            assertThrows(Exception.class,()->j.skills.listMcpSkillResources("remote",j.installation.getString("installationId"),j.installation.getLong("stateRevision"),new RunControl()));
            JSONObject revoked=j.skills.handle("get",new JSONObject().put("id",first.getString("id"))).getJSONObject("skill");assertFalse(revoked.getBoolean("enabled"));assertEquals("",revoked.getString("approvedDigest"));
            j.server.handler=original;j.inspect(first.getString("id"));assertThrows(SecurityException.class,()->j.skills.readForHost(scope(),first.getString("id"),"SKILL.md",0,12000,1,first.getString("digest"),Set.of()));
        }
    }
}
