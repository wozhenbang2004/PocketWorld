package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real data/persistence contracts. The protected TeamsModule save bridge is not mounted here. */
public final class TeamTemplateApplicationTest {
    static JSONArray reads(){return new JSONArray().put("team_materials").put("team_read_source");}
    static JSONArray capabilities()throws Exception{return new JSONArray().put(new JSONObject().put("id","model").put("available",true))
        .put(new JSONObject().put("id","claude").put("available",false).put("probeRequired",true));}
    static JSONObject role(String id)throws Exception{return new JSONObject().put("id",id).put("name","证据研究 "+id).put("description","角色说明")
        .put("instruction","保留原文与不确定性 😀").put("acceptance","准确来源").put("runner","model").put("suggestedModel","configured-model")
        .put("readToolNeeds",new JSONArray().put("team_read_source")).put("inputContract","已选择来源").put("outputContract","证据与结论");}
    static JSONObject change(JSONObject stored,String action,JSONObject input)throws Exception{return TeamRoleCatalog.change(stored,action,
        input.put("expectedCatalogRevision",TeamRoleCatalog.catalogRevision(stored)),reads()).document();}
    static JSONObject document()throws Exception {
        JSONObject doc=new JSONObject().put("teams",new JSONArray().put(new JSONObject().put("id","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").put("inflightSnapshot","unchanged")))
            .put("unrelatedRoot",new JSONObject().put("value","保留未知根字段"));
        doc=change(doc,"saveRole",new JSONObject().put("role",role("research")));
        JSONObject role=TeamRoleCatalog.snapshot(doc,capabilities(),reads()).getJSONArray("roles").getJSONObject(0);
        JSONObject template=new JSONObject().put("id","research_team").put("name","研究模板").put("mode","collaborate").put("leaderId","researcher")
            .put("members",new JSONArray().put(new JSONObject().put("memberId","researcher").put("name","研究者").put("roleId","research")
                .put("roleVersion",role.getInt("version")).put("roleHash",role.getString("hash")).put("dependsOn",new JSONArray())));
        return change(doc,"saveTemplate",new JSONObject().put("template",template));
    }
    static JSONObject request(JSONObject doc)throws Exception {
        JSONObject template=TeamRoleCatalog.snapshot(doc,capabilities(),reads()).getJSONArray("templates").getJSONObject(0);
        return new JSONObject().put("expectedCatalogRevision",TeamRoleCatalog.catalogRevision(doc)).put("templateId",template.getString("id"))
            .put("templateVersion",template.getInt("version")).put("templateHash",template.getString("hash")).put("name","新的原生团队");
    }
    // A deterministic data callback exercises the bridge contract. It is deliberately not
    // advertised as execution of AgentSlot/TeamsModule's protected normalization.
    static JSONObject normalizedData(JSONObject input)throws Exception{return new JSONObject(input.toString()).put("discussionFormat","classic");}
    private static void persist(File file,TeamTemplateApplication.Change change)throws Exception {
        if(change.writeRequired()){change.requireUnchanged(JsonFiles.read(file));JsonFiles.write(file,change.document());}
    }
    @Test public void oneNativeActionPersistsDefinitionPinsAndReceiptTogetherThenReopensQueryOnly()throws Exception {
        File file=new File(Files.createTempDirectory("team-template-action-").toFile(),"definitions.json");JSONObject doc=document();JsonFiles.write(file,doc);
        String action=UUID.randomUUID().toString();AtomicInteger nativeCalls=new AtomicInteger();
        var first=TeamTemplateApplication.apply(JsonFiles.read(file),action,request(doc),capabilities(),reads(),input->{nativeCalls.incrementAndGet();
            assertEquals(5,input.length());assertFalse(input.has("templateReference"));assertFalse(input.has("budget"));return normalizedData(input);});
        assertTrue(first.writeRequired());assertFalse(first.receipt().getBoolean("executionAuthorized"));persist(file,first);
        JSONObject reopened=JsonFiles.read(file),saved=reopened.getJSONArray("teams").getJSONObject(1);assertEquals(2,reopened.getJSONArray("teams").length());
        assertEquals("保留未知根字段",reopened.getJSONObject("unrelatedRoot").getString("value"));assertEquals("unchanged",reopened.getJSONArray("teams").getJSONObject(0).getString("inflightSnapshot"));
        assertEquals(request(doc).getString("templateHash"),saved.getJSONObject("templateReference").getString("hash"));
        assertEquals("research",saved.getJSONArray("templateRoleReferences").getJSONObject(0).getString("roleId"));
        assertEquals(TeamTemplateApplication.definitionId(action),saved.getString("id"));assertEquals(1,nativeCalls.get());
        byte[] before=Files.readAllBytes(file.toPath());var replay=TeamTemplateApplication.apply(reopened,action,request(doc),new JSONArray(),new JSONArray(),input->{throw new AssertionError("replay must not normalize/create again");});
        assertFalse(replay.writeRequired());assertTrue(replay.receipt().getBoolean("duplicate"));assertFalse(replay.receipt().getBoolean("historical"));persist(file,replay);
        assertEquals(first.receipt().getString("definitionId"),replay.receipt().getString("definitionId"));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
    }
    @Test public void changedRequestRejectsAndDeletedDefinitionNeverResurrectsOnReplay()throws Exception {
        JSONObject doc=document(),request=request(doc);String action=UUID.randomUUID().toString();doc=TeamTemplateApplication.apply(doc,action,request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData).document();
        JSONObject saved=doc;assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(saved,action,new JSONObject(request.toString()).put("name","different"),capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        doc.getJSONArray("teams").remove(1);String before=doc.toString();var replay=TeamTemplateApplication.apply(doc,action,request,capabilities(),reads(),input->{throw new AssertionError();});
        assertFalse(replay.writeRequired());assertTrue(replay.receipt().getBoolean("historical"));assertFalse(replay.receipt().getBoolean("definitionPresent"));
        assertEquals(before,replay.document().toString());assertEquals(1,replay.document().getJSONArray("teams").length());
    }
    @Test public void changedCatalogOrFailedNativeValidationCannotPublishHalfAnApplication()throws Exception {
        JSONObject doc=document(),request=request(doc);String original=doc.toString();
        assertThrows(IllegalStateException.class,()->TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),new JSONObject(request.toString()).put("expectedCatalogRevision",0),capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),request,capabilities(),reads(),input->{throw new SecurityException("original save rejected the exact slot");}));
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),request,capabilities(),reads(),input->normalizedData(input).put("id",UUID.randomUUID().toString())));
        assertEquals(original,doc.toString());assertFalse(doc.has(TeamTemplateApplication.KEY));
        var ready=TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData);
        JSONObject concurrentlyChanged=new JSONObject(doc.toString()).put("newRootField",true);assertThrows(IllegalStateException.class,()->ready.requireUnchanged(concurrentlyChanged));
    }
    @Test public void exactPinnedRoleSurvivesRoleUpdatesAndApplicationsPreserveTheWholeDocument()throws Exception {
        JSONObject doc=document(),request=request(doc);String action=UUID.randomUUID().toString();doc=TeamTemplateApplication.apply(doc,action,request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData).document();
        String oldRoleHash=doc.getJSONArray("teams").getJSONObject(1).getJSONArray("templateRoleReferences").getJSONObject(0).getString("hash");
        doc=change(doc,"saveRole",new JSONObject().put("role",role("research").put("instruction","新版不覆盖旧pin")));
        assertTrue(doc.has(TeamTemplateApplication.KEY));assertEquals(oldRoleHash,doc.getJSONArray("teams").getJSONObject(1).getJSONArray("templateRoleReferences").getJSONObject(0).getString("hash"));
        JSONObject current=doc;assertThrows(IllegalStateException.class,()->TeamTemplateApplication.apply(current,UUID.randomUUID().toString(),request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        JSONObject fresh=request(doc);var next=TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),fresh,capabilities(),reads(),input->{assertEquals("保留原文与不确定性 😀",input.getJSONArray("members").getJSONObject(0).getString("assignment"));return normalizedData(input);});
        assertEquals(3,next.document().getJSONArray("teams").length());assertEquals(2,next.document().getJSONObject(TeamTemplateApplication.KEY).getJSONArray("receipts").length());
    }
    @Test public void authorityFieldsCorruptSourceAndUnavailableNativeCapabilitiesFailClosed()throws Exception {
        JSONObject doc=document(),request=request(doc);
        for(String key:new String[]{"taskId","budget","templateReference","executionAuthorized","privateHistory"})assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),new JSONObject(request.toString()).put(key,"forged"),capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),request,new JSONArray(),reads(),TeamTemplateApplicationTest::normalizedData));
        JSONObject corrupt=new JSONObject(doc.toString());corrupt.getJSONObject(TeamRoleCatalog.KEY).getJSONArray("templates").getJSONObject(0).put("name","same stored hash but altered bytes");
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(corrupt,UUID.randomUUID().toString(),request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        JSONObject invalidCatalog=new JSONObject(doc.toString()).put(TeamRoleCatalog.KEY,"unread legacy data");
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(invalidCatalog,UUID.randomUUID().toString(),request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        JSONObject invalidTeams=new JSONObject(doc.toString()).put("teams","unread legacy team definitions");
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(invalidTeams,UUID.randomUUID().toString(),request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        assertEquals("unread legacy team definitions",invalidTeams.getString("teams"));
        assertThrows(java.nio.charset.CharacterCodingException.class,()->TeamTemplateApplication.apply(doc,UUID.randomUUID().toString(),new JSONObject(request.toString()).put("name","bad \ud800"),capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
        assertThrows(SecurityException.class,()->TeamTemplateApplication.apply(doc,"not-a-native-action",request,capabilities(),reads(),TeamTemplateApplicationTest::normalizedData));
    }
}
