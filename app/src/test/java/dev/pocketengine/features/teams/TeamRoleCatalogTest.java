package dev.pocketengine.features.teams;

import dev.pocketengine.features.workspace.JsonFiles;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual definitions-document persistence and data contracts. Protected TeamsModule mounting is separate. */
public final class TeamRoleCatalogTest {
    private final JSONArray reads=new JSONArray().put("team_materials").put("team_read_source");
    private JSONArray capabilities()throws Exception{return new JSONArray().put(new JSONObject().put("id","model").put("available",true)).put(new JSONObject().put("id","claude").put("available",false).put("probeRequired",true));}
    private JSONObject role(String id)throws Exception{return new JSONObject().put("id",id).put("name","证据研究").put("description","核对来源与不确定性").put("instruction","只读取已准入材料；保留原文证据").put("acceptance","有准确来源").put("runner","model").put("suggestedModel","configured-model").put("readToolNeeds",new JSONArray().put("team_read_source")).put("inputContract","主人已选择的来源").put("outputContract","结论、证据和未解问题");}
    private JSONObject change(JSONObject document,String action,JSONObject body)throws Exception{return TeamRoleCatalog.change(document,action,body.put("expectedCatalogRevision",TeamRoleCatalog.snapshot(document,capabilities(),reads).getLong("revision")),reads).document();}
    private JSONObject row(JSONObject document,String list)throws Exception{return TeamRoleCatalog.snapshot(document,capabilities(),reads).getJSONArray(list).getJSONObject(0);}
    private JSONObject template(JSONObject role)throws Exception{return new JSONObject().put("id","research_team").put("name","研究模板").put("mode","collaborate").put("leaderId","researcher").put("members",new JSONArray().put(new JSONObject().put("memberId","researcher").put("name","研究者").put("roleId",role.getString("id")).put("roleVersion",role.getInt("version")).put("roleHash",role.getString("hash")).put("dependsOn",new JSONArray())));}
    @Test public void versionedRolesAndPinnedTemplateSurviveRealReopenWithoutTouchingExistingTeams()throws Exception {
        JSONObject original=new JSONObject().put("teams",new JSONArray().put(new JSONObject().put("id","existing-run-definition"))),document=change(original,"saveRole",new JSONObject().put("role",role("research")));JSONObject role=row(document,"roles");document=change(document,"saveTemplate",new JSONObject().put("template",template(role)));
        File file=new File(Files.createTempDirectory("role-catalog-").toFile(),"definitions.json");JsonFiles.write(file,document);JSONObject reopened=JsonFiles.read(file),template=row(reopened,"templates");assertEquals("existing-run-definition",reopened.getJSONArray("teams").getJSONObject(0).getString("id"));assertEquals(role.getString("hash"),template.getJSONArray("members").getJSONObject(0).getJSONObject("role").getString("hash"));
        JSONObject proposal=TeamRoleCatalog.definitionProposal(reopened,"research_team",template.getInt("version"),template.getString("hash"),"新研究组",capabilities(),reads);assertFalse(proposal.getBoolean("executionAuthorized"));assertEquals("team_read_source",role.getJSONArray("readToolNeeds").getString(0));assertFalse(proposal.toString().contains("readToolNeeds"));assertFalse(proposal.has("budget"));assertFalse(proposal.has("origin"));assertFalse(proposal.getJSONArray("members").getJSONObject(0).has("writeFiles"));
        assertFalse(original.has(TeamRoleCatalog.KEY));
    }
    @Test public void staleCasAndForgedAuthorityFailWithoutChangingTheOriginalDocument()throws Exception {
        JSONObject original=change(new JSONObject(),"saveRole",new JSONObject().put("role",role("research")));String before=original.toString();JSONObject stale=new JSONObject().put("expectedCatalogRevision",0).put("role",role("research"));assertThrows(IllegalStateException.class,()->TeamRoleCatalog.change(original,"saveRole",stale,reads));
        for(String key:new String[]{"apiKey","budget","tools","privateHistory","script","executionAuthorized"}){JSONObject forged=new JSONObject().put("expectedCatalogRevision",1).put("role",role("other").put(key,"forged"));assertThrows(SecurityException.class,()->TeamRoleCatalog.change(original,"saveRole",forged,reads));}
        JSONObject writeTool=new JSONObject().put("expectedCatalogRevision",1).put("role",role("other").put("readToolNeeds",new JSONArray().put("shell_execute")));assertThrows(SecurityException.class,()->TeamRoleCatalog.change(original,"saveRole",writeTool,reads));assertEquals(before,original.toString());
    }
    @Test public void unprobedCliRoleCanBeInspectedButNeverAutomaticallyApplied()throws Exception {
        JSONObject document=change(new JSONObject(),"saveRole",new JSONObject().put("role",role("remote").put("runner","claude")));JSONObject role=row(document,"roles");assertFalse(role.getJSONObject("availability").getBoolean("available"));document=change(document,"saveTemplate",new JSONObject().put("template",template(role)));JSONObject saved=row(document,"templates"),exact=document;
        assertThrows(SecurityException.class,()->TeamRoleCatalog.definitionProposal(exact,"research_team",saved.getInt("version"),saved.getString("hash"),"未探测组",capabilities(),reads));
    }
    @Test public void roleUpdateDoesNotRewritePinnedTemplateOrExistingDefinitionProposal()throws Exception {
        JSONObject document=change(new JSONObject(),"saveRole",new JSONObject().put("role",role("research"))),firstRole=row(document,"roles");document=change(document,"saveTemplate",new JSONObject().put("template",template(firstRole)));JSONObject firstTemplate=row(document,"templates"),proposal=TeamRoleCatalog.definitionProposal(document,"research_team",firstTemplate.getInt("version"),firstTemplate.getString("hash"),"固定角色组",capabilities(),reads);
        document=change(document,"saveRole",new JSONObject().put("role",role("research").put("instruction","新版角色要求")));JSONObject current=row(document,"roles");assertEquals(2,current.getInt("version"));assertNotEquals(firstRole.getString("hash"),current.getString("hash"));assertEquals(firstTemplate.getString("hash"),row(document,"templates").getString("hash"));assertEquals(firstRole.getString("instruction"),proposal.getJSONArray("members").getJSONObject(0).getString("assignment"));
    }
    @Test public void archiveAndRemovalRetainExistingTeamsButPreventNewApplications()throws Exception {
        JSONObject document=change(new JSONObject().put("teams",new JSONArray().put(new JSONObject().put("id","inflight"))),"saveRole",new JSONObject().put("role",role("research"))),role=row(document,"roles");document=change(document,"saveTemplate",new JSONObject().put("template",template(role)));JSONObject template=row(document,"templates");document=change(document,"archiveRole",new JSONObject().put("id","research").put("expectedVersion",role.getInt("version")).put("expectedHash",role.getString("hash")));JSONObject archived=document;
        assertThrows(SecurityException.class,()->TeamRoleCatalog.definitionProposal(archived,"research_team",template.getInt("version"),template.getString("hash"),"不得新建",capabilities(),reads));assertEquals("inflight",document.getJSONArray("teams").getJSONObject(0).getString("id"));
        document=change(document,"removeTemplate",new JSONObject().put("id","research_team").put("expectedVersion",template.getInt("version")).put("expectedHash",template.getString("hash")));assertEquals(0,TeamRoleCatalog.snapshot(document,capabilities(),reads).getJSONArray("templates").length());assertEquals("inflight",document.getJSONArray("teams").getJSONObject(0).getString("id"));
    }
}
