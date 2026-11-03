package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real native document reads with injected fence contracts; no mounted TaskStore acceptance claim. */
public final class TeamRoleCatalogReadTest {
    private static TeamRoleCatalogRead.Snapshot snapshot(JSONObject document)throws Exception {
        return new TeamRoleCatalogRead.Snapshot(document,TeamTemplateApplicationTest.capabilities(),TeamTemplateApplicationTest.reads());
    }
    private static JSONObject roleRequest(JSONObject document)throws Exception {
        JSONObject role=TeamRoleCatalog.snapshot(document,TeamTemplateApplicationTest.capabilities(),TeamTemplateApplicationTest.reads()).getJSONArray("roles").getJSONObject(0);
        return new JSONObject().put("kind","role").put("id",role.getString("id")).put("version",role.getInt("version")).put("hash",role.getString("hash"));
    }
    @Test public void realReopenedDocumentReturnsBoundedIndexAndExactTemplateConditionsWithoutAuthority()throws Exception {
        File file=new File(Files.createTempDirectory("team-role-read-").toFile(),"definitions.json");JSONObject document=TeamTemplateApplicationTest.document();JsonFiles.write(file,document);
        AtomicInteger reads=new AtomicInteger(),checks=new AtomicInteger();TeamRoleCatalogRead.CurrentSource source=()->{reads.incrementAndGet();return snapshot(JsonFiles.read(file));};
        JSONObject first=TeamRoleCatalogRead.read(source,checks::incrementAndGet,new JSONObject().put("kind","catalog").put("limit",1));
        assertEquals(1,first.getJSONArray("entries").length());assertFalse(first.getBoolean("complete"));assertEquals(2,reads.get());assertEquals(3,checks.get());
        assertFalse(first.getBoolean("executionAuthorized"));assertTrue(first.getBoolean("queryOnly"));assertFalse(first.toString().contains("unrelatedRoot"));assertFalse(first.toString().contains("instruction"));
        JSONObject later=new JSONObject().put("kind","catalog").put("after",first.getInt("next")).put("expectedCatalogRevision",first.getLong("catalogRevision")).put("expectedSnapshotHash",first.getString("catalogSnapshotHash"));
        JSONObject second=TeamRoleCatalogRead.read(source,checks::incrementAndGet,later);
        assertTrue(second.getBoolean("complete"));JSONObject template=second.getJSONArray("entries").getJSONObject(0);assertEquals("template",template.getString("kind"));assertTrue(template.getJSONObject("availability").getBoolean("available"));
        assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(()->new TeamRoleCatalogRead.Snapshot(document,new JSONArray(),TeamTemplateApplicationTest.reads()),checks::incrementAndGet,later));
        assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(source,checks::incrementAndGet,new JSONObject(later.toString()).put("expectedSnapshotHash","0".repeat(64))));
        JSONObject exact=TeamRoleCatalogRead.read(source,checks::incrementAndGet,new JSONObject().put("kind","template").put("id",template.getString("id")).put("version",template.getInt("version")).put("hash",template.getString("hash")));
        JSONObject body=new JSONObject(exact.getString("text"));assertEquals("research",body.getJSONArray("members").getJSONObject(0).getJSONObject("role").getString("id"));assertFalse(body.getBoolean("executionAuthorized"));
    }
    @Test public void exactRolePagesPreserveUnicodeAndRejectMixedCatalogOrCapabilityVersions()throws Exception {
        JSONObject doc=TeamTemplateApplicationTest.change(TeamTemplateApplicationTest.document(),"saveRole",new JSONObject().put("role",TeamTemplateApplicationTest.role("research")
            .put("instruction","🙂".repeat(1000)).put("description","证".repeat(1200)).put("inputContract","入".repeat(1200)).put("outputContract","出".repeat(1200))));
        AtomicReference<JSONObject> current=new AtomicReference<>(doc);AtomicReference<JSONArray> capabilities=new AtomicReference<>(TeamTemplateApplicationTest.capabilities());
        TeamRoleCatalogRead.CurrentSource source=()->new TeamRoleCatalogRead.Snapshot(current.get(),capabilities.get(),TeamTemplateApplicationTest.reads());
        JSONObject request=roleRequest(doc),page=TeamRoleCatalogRead.read(source,()->{},new JSONObject(request.toString()).put("length",503)),first=page;
        StringBuilder complete=new StringBuilder(page.getString("text"));int pages=1;
        while(!page.getBoolean("complete")){
            page=TeamRoleCatalogRead.read(source,()->{},new JSONObject(request.toString()).put("length",503).put("offset",page.getInt("endOffset"))
                .put("expectedCatalogRevision",first.getLong("catalogRevision")).put("expectedContentHash",first.getString("contentHash")));
            complete.append(page.getString("text"));assertTrue(page.toString().getBytes(StandardCharsets.UTF_8).length<=TeamRoleCatalogRead.MAX_BYTES);assertTrue(++pages<30);
        }
        JSONObject actual=new JSONObject(complete.toString());assertEquals("🙂".repeat(1000),actual.getString("instruction"));assertEquals(first.getString("contentHash"),TeamRoleCatalog.dataHash(actual));
        JSONObject continuation=new JSONObject(request.toString()).put("offset",first.getInt("endOffset")).put("expectedCatalogRevision",first.getLong("catalogRevision")).put("expectedContentHash",first.getString("contentHash"));
        capabilities.set(new JSONArray());assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(source,()->{},continuation));
        capabilities.set(TeamTemplateApplicationTest.capabilities());current.set(TeamTemplateApplicationTest.change(doc,"saveRole",new JSONObject().put("role",TeamTemplateApplicationTest.role("research"))));
        assertThrows(IllegalStateException.class,()->TeamRoleCatalogRead.read(source,()->{},continuation));
    }
    @Test public void sourceOrOriginalFenceRevocationDuringReadNeverReturnsTheComputedPage()throws Exception {
        JSONObject doc=TeamTemplateApplicationTest.document(),changed=TeamTemplateApplicationTest.change(doc,"saveRole",new JSONObject().put("role",TeamTemplateApplicationTest.role("research").put("instruction","new version")));
        AtomicInteger calls=new AtomicInteger();assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(()->snapshot(calls.incrementAndGet()==1?doc:changed),()->{},new JSONObject().put("kind","catalog")));
        assertEquals(2,calls.get());AtomicInteger checks=new AtomicInteger();assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(()->snapshot(doc),()->{if(checks.incrementAndGet()==2)throw new SecurityException("original task/run/source revoked");},new JSONObject().put("kind","catalog")));
        assertEquals(2,checks.get());assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(()->snapshot(doc),null,new JSONObject().put("kind","catalog")));
    }
    @Test public void cursorsAndJsonCannotSupplyTaskIdentityWritesOrUnpinnedLaterPages()throws Exception {
        JSONObject doc=TeamTemplateApplicationTest.document(),role=roleRequest(doc);TeamRoleCatalogRead.CurrentSource source=()->snapshot(doc);
        for(String key:new String[]{"taskId","runId","scope","action","budget","capabilities"})assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject().put("kind","catalog").put(key,"forged")));
        assertThrows(IllegalArgumentException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject().put("kind","catalog").put("limit",9)));
        assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject().put("kind","catalog").put("after",1)));
        assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject(role.toString()).put("offset",1)));
        assertThrows(IllegalArgumentException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject(role.toString()).put("length",4001)));
        assertThrows(IllegalStateException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject(role.toString()).put("hash","0".repeat(64))));
        JSONObject full=TeamRoleCatalogRead.read(source,()->{},role);int emoji=full.getString("text").indexOf("😀");assertTrue(emoji>=0);
        assertThrows(IllegalArgumentException.class,()->TeamRoleCatalogRead.read(source,()->{},new JSONObject(role.toString()).put("offset",emoji+1).put("expectedCatalogRevision",full.getLong("catalogRevision")).put("expectedContentHash",full.getString("contentHash"))));
    }
    @Test public void missingCliProbeAndReadToolsRemainConditionsAndNeverBecomeADataGrant()throws Exception {
        JSONObject doc=TeamTemplateApplicationTest.change(TeamTemplateApplicationTest.document(),"saveRole",new JSONObject().put("role",TeamTemplateApplicationTest.role("remote").put("runner","claude")));
        JSONObject result=TeamRoleCatalogRead.read(()->new TeamRoleCatalogRead.Snapshot(doc,TeamTemplateApplicationTest.capabilities(),new JSONArray()),()->{},new JSONObject().put("kind","catalog"));
        JSONArray entries=result.getJSONArray("entries");JSONObject remote=null;for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getString("id").equals("remote"))remote=entries.getJSONObject(i);
        assertNotNull(remote);JSONObject availability=remote.getJSONObject("availability");assertFalse(availability.getBoolean("available"));assertFalse(availability.getBoolean("probeVerified"));
        assertEquals("team_read_source",availability.getJSONArray("missingReadTools").getString(0));assertFalse(result.getBoolean("executionAuthorized"));
    }
    @Test public void sourceHashTamperingRejectsEvenWhenStoredIdAndVersionWereUnchanged()throws Exception {
        JSONObject doc=TeamTemplateApplicationTest.document();doc.getJSONObject(TeamRoleCatalog.KEY).getJSONArray("roles").getJSONObject(0).put("instruction","body altered under old hash");
        assertThrows(SecurityException.class,()->TeamRoleCatalogRead.read(()->snapshot(doc),()->{},new JSONObject().put("kind","catalog")));
    }
}
