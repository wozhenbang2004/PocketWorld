package dev.pocketengine.features.skills;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SkillBookTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private String manifest(){return "---\nname: calculator-check\ndescription: 核对计算器任务的真实结果\nlicense: owner-authored\nmetadata:\n  version: '1.0'\nallowed-tools: phone_observe phone_tap\n---\n观察结果，核对128乘36是否为4608。\n参照references/check.md。";}
    private JSONObject imported()throws Exception{return new JSONObject().put("manifest",manifest()).put("source","local:owner-authored").put("requiredCapabilities",new JSONArray().put("phone.observe"))
            .put("resources",new JSONObject().put("references/check.md","先核对界面，不盲目重复发送或写入。"));}
    private JSONObject enable(SkillBook book,JSONObject record,long now)throws Exception{return book.setEnabled(scope(),record.getString("id"),true,record.getString("digest"),true,now);}
    @Test public void importPersistsMetadataAndResourcesButNeedsExplicitVersionApproval()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);JSONObject record=book.install(scope(),imported(),1000);
        assertFalse(record.getBoolean("active"));assertFalse(record.has("instructions"));assertFalse(record.has("manifest"));assertEquals("1.0",record.getString("sourceVersion"));
        SkillBook reopened=new SkillBook(root);assertEquals(1,reopened.list(scope(),1100).length());
        assertThrows(SecurityException.class,()->reopened.loadForHost(scope(),record.getString("id"),record.getString("digest"),Set.of("phone.observe"),1100));
        assertThrows(SecurityException.class,()->reopened.setEnabled(scope(),record.getString("id"),true,"wrong-digest",true,1100));
        enable(reopened,record,1200);JSONObject loaded=reopened.loadForHost(scope(),record.getString("id"),record.getString("digest"),Set.of("phone.observe"),1300);
        assertTrue(loaded.getString("instructions").contains("4608"));assertEquals(0,loaded.getJSONArray("grantsAdded").length());assertFalse(loaded.getBoolean("scriptsExecutable"));
    }
    @Test public void allowedToolsAndScriptsNeverGrantShellOrUnknownHostCapabilities()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject input=imported().put("manifest",manifest().replace("phone_observe phone_tap","Bash(rm:*)"));
        input.getJSONObject("resources").put("scripts/unsafe.sh","rm -rf /some/path");JSONObject record=book.install(scope(),input,1000);enable(book,record,1100);
        assertThrows(SecurityException.class,()->book.loadForHost(scope(),record.getString("id"),record.getString("digest"),Set.of(),1200));
        JSONObject script=book.readForHost(scope(),record.getString("id"),"scripts/unsafe.sh",0,100,record.getString("digest"),Set.of("phone.observe"),1200);
        assertFalse(script.getBoolean("executable"));assertTrue(script.getBoolean("untrustedReference"));assertTrue(script.getString("content").contains("rm -rf"));
    }
    @Test public void scopeAndExpiryFenceModelReadsWithoutDeletingUserInspectableContent()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject record=book.install(scope(),imported().put("expiresAt",2000),1000);enable(book,record,1100);
        JSONObject group=new JSONObject().put("ownerId","owner").put("spaceId","qq:group:1");assertEquals(0,book.list(group,1200).length());assertThrows(IllegalArgumentException.class,()->book.get(group,record.getString("id"),1200));
        assertFalse(book.get(scope(),record.getString("id"),2001).getBoolean("active"));assertThrows(SecurityException.class,()->book.loadForHost(scope(),record.getString("id"),record.getString("digest"),Set.of("phone.observe"),2001));
        assertTrue(book.read(scope(),record.getString("id"),"SKILL.md",0,100,2001).getString("content").contains("name:"));
    }
    @Test public void updatesKeepIdentityIncrementRevisionAndRequireNewApproval()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject old=book.install(scope(),imported(),1000);enable(book,old,1100);
        JSONObject update=imported().put("id",old.getString("id")).put("revision",old.getInt("revision")).put("manifest",manifest()+"\n补充登录后重新观察。"),fresh=book.install(scope(),update,1200);
        assertEquals(old.getString("id"),fresh.getString("id"));assertEquals(2,fresh.getInt("revision"));assertFalse(fresh.getBoolean("active"));assertNotEquals(old.getString("digest"),fresh.getString("digest"));
        assertThrows(IllegalStateException.class,()->book.install(scope(),update,1300));assertThrows(SecurityException.class,()->book.loadForHost(scope(),old.getString("id"),old.getString("digest"),Set.of("phone.observe"),1300));
        enable(book,fresh,1400);assertEquals(1,book.list(scope(),1500).length());
    }
    @Test public void tamperedPackageFailsLoadingAndCannotSilentlyRemainApproved()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);JSONObject record=book.install(scope(),imported(),1000);enable(book,record,1100);
        File resource=new File(root,record.getString("id")+"/1/calculator-check/references/check.md");Files.write(resource.toPath(),"被替换的内容".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->book.readForHost(scope(),record.getString("id"),"references/check.md",0,100,record.getString("digest"),Set.of("phone.observe"),1200));
        assertThrows(IllegalStateException.class,()->enable(book,record,1200));
    }
    @Test public void progressiveReadsRespectPackagePathsAndBoundEachChunk()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject input=imported();input.getJSONObject("resources").put("references/long.md","长".repeat(20000));JSONObject record=book.install(scope(),input,1000);enable(book,record,1100);
        JSONObject first=book.readForHost(scope(),record.getString("id"),"references/long.md",0,50000,record.getString("digest"),Set.of("phone.observe"),1200);assertEquals(12000,first.getString("content").length());assertTrue(first.getBoolean("hasMore"));
        JSONObject second=book.read(scope(),record.getString("id"),"references/long.md",first.getInt("nextOffset"),12000,1200);assertEquals(8000,second.getString("content").length());assertFalse(second.getBoolean("hasMore"));
        assertThrows(IllegalArgumentException.class,()->book.read(scope(),record.getString("id"),"../index.json",0,100,1200));
        JSONObject bad=imported();bad.getJSONObject("resources").put("references/../../outside.txt","bad");assertThrows(IllegalArgumentException.class,()->book.install(scope(),bad,1300));
    }
    @Test public void manifestSubsetValidatesNamingAndDoesNotEvaluateYamlFeatures()throws Exception{
        String folded="---\nname: inspect-task\ndescription: >\n  核对真实结果。\n  在用户接管后使用。\ncompatibility: Android\nmetadata:\n  author: 'owner'\n---\n操作前重新观察。";
        JSONObject parsed=SkillManifest.parse(folded);assertTrue(parsed.getString("description").contains("真实结果。 在"));
        assertThrows(IllegalArgumentException.class,()->SkillManifest.parse(manifest().replace("calculator-check","Invalid_Name")));
        assertThrows(IllegalArgumentException.class,()->SkillManifest.parse(manifest().replace("owner-authored","!!java/object java.lang.Runtime")));
        assertThrows(IllegalArgumentException.class,()->SkillManifest.parse("name: missing-frontmatter"));
    }
    @Test public void disablingAndDeletingSkillPreventsSubsequentHostUse()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);JSONObject record=book.install(scope(),imported(),1000);enable(book,record,1100);
        book.setEnabled(scope(),record.getString("id"),false,"",false,1200);assertThrows(SecurityException.class,()->book.readForHost(scope(),record.getString("id"),"SKILL.md",0,100,record.getString("digest"),Set.of("phone.observe"),1300));
        book.delete(scope(),record.getString("id"));assertEquals(0,new SkillBook(root).list(scope(),1400).length());assertFalse(new File(root,record.getString("id")).exists());
    }
    @Test public void selectedSingleDocumentUsesOriginalPreviewDigestPolicyAndDisabledRevision()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject created=book.install(scope(),imported().put("expiresAt",9000),1000);
        JSONObject old=book.configureSourceForOwner(scope(),created.getString("id"),created.getInt("revision"),created.getString("digest"),created.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY,"10000000-0000-0000-0000-000000000001",1050);enable(book,old,1100);
        byte[] selected=(manifest()+"\n新的所选正文。").getBytes(StandardCharsets.UTF_8);
        JSONObject preview=book.inspectDocument(scope(),new java.io.ByteArrayInputStream(selected),old.getString("id"),1200),options=new JSONObject().put("source","local:selected-skill-document").put("id",old.getString("id")).put("revision",old.getInt("revision")).put("expectedCurrentDigest",old.getString("digest")).put("inspectedDigest",preview.getJSONObject("package").getString("digest"));
        assertTrue(preview.getBoolean("singleDocumentOnly"));assertEquals("references/check.md",preview.getJSONObject("comparison").getJSONArray("removed").getString(0));
        assertThrows(SecurityException.class,()->book.importDocument(scope(),new java.io.ByteArrayInputStream((manifest()+" changed").getBytes(StandardCharsets.UTF_8)),options,1300));assertEquals(1,book.get(scope(),old.getString("id"),1300).getInt("revision"));
        JSONObject updated=book.importDocument(scope(),new java.io.ByteArrayInputStream(selected),options,1400);assertEquals(old.getString("id"),updated.getString("id"));assertEquals(2,updated.getInt("revision"));assertEquals(1,updated.getJSONArray("resources").length());assertEquals("SKILL.md",updated.getJSONArray("resources").getString(0));assertEquals("phone.observe",updated.getJSONArray("requiredCapabilities").getString(0));assertEquals(9000,updated.getLong("expiresAt"));assertFalse(updated.getBoolean("enabled"));assertFalse(updated.getBoolean("active"));
        JSONObject policy=updated.getJSONObject("sourcePolicy");assertEquals("PROJECT",policy.getString("layer"));assertEquals("OWNER_ONLY",policy.getString("invocation"));assertEquals("10000000-0000-0000-0000-000000000001",policy.getString("projectId"));assertFalse(policy.getBoolean("modelInvocable"));
        assertThrows(IllegalStateException.class,()->book.importDocument(scope(),new java.io.ByteArrayInputStream(selected),options,1500));
    }
}
