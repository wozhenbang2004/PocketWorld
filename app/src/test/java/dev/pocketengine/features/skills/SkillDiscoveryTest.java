package dev.pocketengine.features.skills;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SkillDiscoveryTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private String manifest(String name,String description){return "---\nname: "+name+"\ndescription: "+description+"\nmetadata:\n  topic: '结果核查 arithmetic'\nallowed-tools: phone_observe shell_exec\n---\nBODY_SECRET_DO_NOT_DISCLOSE 执行正文不可用于匹配。";}
    private JSONObject input(String name,String description)throws Exception{return new JSONObject().put("manifest",manifest(name,description)).put("source","local:owner")
            .put("resources",new JSONObject().put("references/check.md","RESOURCE_SECRET_DO_NOT_DISCLOSE").put("scripts/no-run.sh","SCRIPT_SECRET_NEVER_EXECUTE"));}
    private JSONObject add(SkillBook book,String name,String description)throws Exception{JSONObject row=book.install(scope(),input(name,description),1000);enable(book,row);return row;}
    private void enable(SkillBook book,JSONObject row)throws Exception{book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1100);}
    private JSONObject discover(SkillBook book,String query,Set<String> capabilities,int limit,int chars,long now)throws Exception{return book.discoverForHost(scope(),query,capabilities,limit,chars,now);}
    private JSONObject discover(SkillBook book,String query)throws Exception{return discover(book,query,Set.of(),20,32000,1200);}
    private JSONObject diagnostic(SkillBook book,String id,Set<String> caps,long now)throws Exception{JSONArray rows=book.diagnoseDiscovery(scope(),"核查",caps,now).getJSONArray("skills");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);throw new AssertionError("missing diagnostic");}
    private File resource(File root,JSONObject row,String path)throws Exception{return new File(root,row.getString("id")+"/"+row.getInt("revision")+"/"+row.getString("name")+"/"+path);}

    @Test public void chineseTaskFindsMetadataAndReturnsOnlyBoundedReadIdentity()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject row=add(book,"calculator-check","核对计算器任务的真实结果");
        JSONObject result=discover(book,"请帮我核对计算器结果"),found=result.getJSONArray("skills").getJSONObject(0);
        assertEquals(row.getString("id"),found.getString("id"));assertEquals(row.getInt("revision"),found.getInt("revision"));assertEquals(row.getString("digest"),found.getString("digest"));assertEquals("SKILL.md",found.getString("entryPath"));
        assertTrue(result.getBoolean("metadataOnly"));assertTrue(result.getBoolean("untrustedReference"));assertFalse(result.getBoolean("executable"));assertEquals(0,result.getJSONArray("grantsAdded").length());assertEquals(0,found.getJSONArray("missingCapabilities").length());
        assertTrue(found.getJSONArray("matchReasons").toString().contains("计算"));assertFalse(result.toString().contains("BODY_SECRET"));assertFalse(result.toString().contains("RESOURCE_SECRET"));assertFalse(result.toString().contains("SCRIPT_SECRET"));
        assertEquals(0,discover(book,"BODY_SECRET_DO_NOT_DISCLOSE").getJSONArray("skills").length());
        JSONObject read=book.readForHost(scope(),found.getString("id"),found.getString("entryPath"),0,12000,found.getInt("revision"),found.getString("digest"),Set.of(),1200);
        assertTrue(read.getString("content").contains("BODY_SECRET"));assertFalse(read.getBoolean("executable"));
    }

    @Test public void capabilityMismatchIsExcludedAndExplainedOnlyInManagement()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject row=book.install(scope(),input("calculator-check","核查结果").put("requiredCapabilities",new JSONArray().put("phone_observe").put("workspace_read")),1000);enable(book,row);
        JSONObject result=discover(book,"核查",Set.of("phone.observe"),10,10000,1200);assertEquals(0,result.getJSONArray("skills").length());assertEquals(1,result.getJSONObject("omitted").getInt("missingCapabilities"));assertFalse(result.toString().contains(row.getString("id")));
        JSONObject blocked=diagnostic(book,row.getString("id"),Set.of("phone.observe"),1200);assertEquals("missingCapabilities",blocked.getString("status"));assertFalse(blocked.getBoolean("eligibleForDiscovery"));assertEquals("[\"phone_observe\",\"workspace_read\"]",blocked.getJSONArray("missingCapabilities").toString());
        assertEquals(1,discover(book,"核查",Set.of("phone_observe","workspace_read"),10,10000,1200).getJSONArray("skills").length());
        assertThrows(SecurityException.class,()->book.readForHost(scope(),row.getString("id"),"SKILL.md",0,100,row.getString("digest"),Set.of(),1200));
        assertEquals(0,book.loadForHost(scope(),row.getString("id"),row.getString("digest"),Set.of("phone_observe","workspace_read"),1200).getJSONArray("grantsAdded").length());
    }

    @Test public void rankingIsExplainableStableAndSupportsCaseWidthAndLiteralChinese()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);
        add(book,"z-last","核查 calculator");add(book,"a-first","核查 calculator");JSONObject byName=add(book,"calculator","用于核查结果");add(book,"metadata-only","无关用途");
        JSONObject result=discover(book,"ＣＡＬＣＵＬＡＴＯＲ");JSONArray rows=result.getJSONArray("skills");assertEquals(3,rows.length());assertEquals(byName.getString("id"),rows.getJSONObject(0).getString("id"));assertEquals(40,rows.getJSONObject(0).getInt("score"));assertEquals("exactName",rows.getJSONObject(0).getJSONArray("matchReasons").getJSONObject(0).getString("kind"));
        assertEquals("a-first",rows.getJSONObject(1).getString("name"));assertEquals("z-last",rows.getJSONObject(2).getString("name"));assertEquals(result.toString(),discover(new SkillBook(root),"ＣＡＬＣＵＬＡＴＯＲ").toString());
        assertEquals(discover(book,"calculator calculator").toString(),discover(book,"ＣＡＬＣＵＬＡＴＯＲ ＣＡＬＣＵＬＡＴＯＲ").toString());
        assertEquals(8,discover(book,"calculator calculator").getJSONArray("skills").getJSONObject(0).getInt("score"));
    }

    @Test public void metadataTermsMatchButEnglishSubstringsAndBodyDoNot()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));add(book,"calculator-check","生成图表并核查结果");
        JSONObject found=discover(book,"arithmetic").getJSONArray("skills").getJSONObject(0);assertEquals(1,found.getInt("score"));assertEquals("metadata",found.getJSONArray("matchReasons").getJSONObject(0).getString("field"));
        assertEquals(0,discover(book,"calc").getJSONArray("skills").length());assertEquals(1,discover(book,"图").getJSONArray("skills").length());
        assertEquals(discover(book,"arithmetic").toString(),discover(book,"arithmetic arithmetic").toString());
    }

    @Test public void selectionDeduplicatesContentAndHonorsCountAndWholeJsonCharacterBudgets()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));add(book,"a-first","核查结果");add(book,"a-first","核查结果");add(book,"b-second","核查结果");add(book,"c-third","核查结果");
        JSONObject full=discover(book,"核查");assertEquals(3,full.getJSONArray("skills").length());assertEquals(1,full.getJSONObject("omitted").getInt("duplicates"));
        JSONObject limited=discover(book,"核查",Set.of(),1,32000,1200);assertEquals(1,limited.getJSONArray("skills").length());assertEquals("a-first",limited.getJSONArray("skills").getJSONObject(0).getString("name"));assertEquals(2,limited.getJSONObject("omitted").getInt("limit"));assertTrue(limited.getBoolean("truncated"));
        for(int budget:new int[]{512,900,1400,2200,32000}){JSONObject result=discover(book,"核查",Set.of(),20,budget,1200);assertTrue(result.toString().length()<=budget);Set<String> digests=new HashSet<>();for(int i=0;i<result.getJSONArray("skills").length();i++)assertTrue(digests.add(result.getJSONArray("skills").getJSONObject(i).getString("digest")));}
        JSONObject tiny=discover(book,"核查",Set.of(),20,512,1200);assertEquals(0,tiny.getJSONArray("skills").length());assertEquals(3,tiny.getJSONObject("omitted").getInt("budget"));assertTrue(tiny.getBoolean("truncated"));
    }

    @Test public void queryAndNativeHostInputsHaveStrictBoundsWithoutPrivateFallback()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));add(book,"calculator-check","核查结果");
        for(String query:new String[]{""," ","!!!","x".repeat(513),"核查\0","核查\ud800"})assertThrows(IllegalArgumentException.class,()->discover(book,query));
        assertThrows(IllegalArgumentException.class,()->discover(book,null));assertThrows(IllegalArgumentException.class,()->discover(book,"核查",null,10,4000,1200));
        assertThrows(IllegalArgumentException.class,()->discover(book,"核查",Set.of("phone_*"),10,4000,1200));
        Set<String> tooMany=new HashSet<>();for(int i=0;i<257;i++)tooMany.add("tool_"+i);assertThrows(IllegalArgumentException.class,()->discover(book,"核查",tooMany,10,4000,1200));
        for(int limit:new int[]{-1,0,21})assertThrows(IllegalArgumentException.class,()->discover(book,"核查",Set.of(),limit,4000,1200));
        for(int budget:new int[]{0,511,32001,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->discover(book,"核查",Set.of(),10,budget,1200));
        assertThrows(IllegalArgumentException.class,()->book.discoverForHost(null,"核查",Set.of(),10,4000,1200));assertThrows(IllegalArgumentException.class,()->book.discoverForHost(new JSONObject(),"核查",Set.of(),10,4000,1200));
    }

    @Test public void fullHundredSkillRegistryStillProducesOnlyTheRequestedBoundedSelection()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));for(int i=0;i<100;i++)add(book,String.format(java.util.Locale.ROOT,"skill-%03d",i),"核查结果 "+i);
        JSONObject result=discover(book,"核查",Set.of(),3,4000,1200);assertEquals(3,result.getJSONArray("skills").length());assertTrue(result.toString().length()<=4000);assertEquals(97,result.getJSONObject("omitted").getInt("limit"));
        assertEquals("skill-000",result.getJSONArray("skills").getJSONObject(0).getString("name"));assertEquals("skill-002",result.getJSONArray("skills").getJSONObject(2).getString("name"));assertFalse(result.toString().contains("BODY_SECRET"));
        assertThrows(IllegalStateException.class,()->add(book,"overflow","核查结果"));
    }

    @Test public void ownerAndSpaceAreExactAndForeignMetadataNeverAppears()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject row=add(book,"private-secret","核查结果");
        for(JSONObject other:new JSONObject[]{new JSONObject().put("ownerId","other").put("spaceId","private"),new JSONObject().put("ownerId","owner").put("spaceId","group:1")}){
            assertEquals(0,book.discoverForHost(other,"核查",Set.of(),20,32000,1200).getJSONArray("skills").length());assertFalse(book.diagnoseDiscovery(other,"核查",Set.of(),1200).toString().contains(row.getString("id")));
            assertThrows(IllegalArgumentException.class,()->book.readForHost(other,row.getString("id"),"SKILL.md",0,100,row.getInt("revision"),row.getString("digest"),Set.of(),1200));}
    }

    @Test public void oneBadPackageIsExplicitlyInvalidWithoutHidingHealthyCatalogueEntries()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);JSONObject bad=add(book,"a-bad","核查结果"),good=add(book,"b-good","核查结果");
        Files.write(resource(root,bad,"references/check.md").toPath(),new byte[]{(byte)0x80});
        JSONObject result=discover(book,"核查");assertEquals("partial",result.getString("status"));assertEquals(1,result.getJSONObject("omitted").getInt("invalid"));assertEquals(good.getString("id"),result.getJSONArray("skills").getJSONObject(0).getString("id"));
        JSONObject current=book.get(scope(),bad.getString("id"),1200);assertEquals("invalid",current.getString("status"));assertFalse(current.getBoolean("active"));assertFalse(current.getBoolean("contentVerified"));assertTrue(current.getJSONArray("statusReasons").toString().contains("contentInvalid"));
        assertEquals("invalid",diagnostic(book,bad.getString("id"),Set.of(),1200).getString("status"));
        JSONObject catalogue=book.catalogueForHost(scope(),1200);assertEquals("partial",catalogue.getString("status"));assertEquals(1,catalogue.getInt("invalidCount"));assertEquals(1,catalogue.getJSONArray("skills").length());
        assertThrows(IllegalStateException.class,()->book.readForHost(scope(),bad.getString("id"),"SKILL.md",0,100,bad.getString("digest"),Set.of(),1200));
        Files.delete(resource(root,good,"SKILL.md").toPath());assertEquals(0,discover(book,"核查").getJSONArray("skills").length());assertEquals(2,discover(book,"核查").getJSONObject("omitted").getInt("invalid"));
    }

    @Test public void registryCorruptionFailsInsteadOfBeingReportedAsSuccessfulEmptyDiscovery()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);add(book,"calculator-check","核查结果");Files.write(new File(root,"index.json").toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,()->discover(book,"核查"));assertThrows(Exception.class,()->book.catalogueForHost(scope(),1200));assertThrows(Exception.class,()->book.diagnoseDiscovery(scope(),"核查",Set.of(),1200));
    }

    @Test public void discoveryMetadataMustBelongToTheVerifiedManifestNotAnAlteredIndexProjection()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);JSONObject row=add(book,"calculator-check","核查结果");File index=new File(root,"index.json");
        JSONObject state=new JSONObject(new String(Files.readAllBytes(index.toPath()),StandardCharsets.UTF_8));state.getJSONArray("skills").getJSONObject(0).put("description","伪造搜索命中");Files.write(index.toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject result=discover(book,"伪造搜索");assertEquals(0,result.getJSONArray("skills").length());assertEquals(1,result.getJSONObject("omitted").getInt("invalid"));
        assertEquals("invalid",book.get(scope(),row.getString("id"),1200).getString("status"));assertThrows(IllegalStateException.class,()->book.readForHost(scope(),row.getString("id"),"SKILL.md",0,100,row.getString("digest"),Set.of(),1200));
    }

    @Test public void disableDeleteExpiryAndUpdateInvalidateSelectionsAcrossBookInstances()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root),other=new SkillBook(root);JSONObject row=add(book,"calculator-check","核查结果");String id=row.getString("id"),digest=row.getString("digest");
        other.setEnabled(scope(),id,false,"",false,1300);assertEquals(0,discover(book,"核查").getJSONArray("skills").length());assertEquals("inactive",diagnostic(book,id,Set.of(),1400).getString("status"));assertThrows(SecurityException.class,()->book.readForHost(scope(),id,"SKILL.md",0,100,1,digest,Set.of(),1400));
        enable(other,row);JSONObject updated=other.install(scope(),input("calculator-check","新的表格整理任务").put("id",id).put("revision",1).put("expiresAt",2000),1500);
        assertEquals(0,discover(book,"表格").getJSONArray("skills").length());enable(other,updated);assertEquals(0,discover(book,"计算器").getJSONArray("skills").length());assertEquals(1,discover(book,"表格").getJSONArray("skills").length());
        assertThrows(SecurityException.class,()->book.readForHost(scope(),id,"SKILL.md",0,100,1,digest,Set.of(),1600));
        assertEquals(0,discover(book,"表格",Set.of(),20,32000,2000).getJSONArray("skills").length());assertTrue(diagnostic(book,id,Set.of(),2000).getJSONArray("statusReasons").toString().contains("expired"));assertThrows(SecurityException.class,()->book.readForHost(scope(),id,"SKILL.md",0,100,updated.getString("digest"),Set.of(),2000));
        other.delete(scope(),id);assertEquals(0,discover(book,"表格").getJSONArray("skills").length());assertThrows(IllegalArgumentException.class,()->book.readForHost(scope(),id,"SKILL.md",0,100,updated.getString("digest"),Set.of(),1600));
    }

    @Test public void zipRestoreRequiresFreshApprovalAndNewSelectionEvenForSameOriginalDigest()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject original=add(book,"calculator-check","核查结果");ByteArrayOutputStream saved=new ByteArrayOutputStream();book.exportPackage(scope(),original.getString("id"),1,original.getString("digest"),saved,1200);
        JSONObject changed=book.install(scope(),input("calculator-check","整理表格").put("id",original.getString("id")).put("revision",1),1300);enable(book,changed);
        JSONObject preview=book.inspectPackage(scope(),new ByteArrayInputStream(saved.toByteArray()),original.getString("id"),1400);JSONObject options=new JSONObject().put("source","local:restored").put("id",original.getString("id")).put("revision",2)
                .put("expectedCurrentDigest",changed.getString("digest")).put("inspectedDigest",preview.getJSONObject("package").getString("digest"));
        JSONObject restored=book.importPackage(scope(),new ByteArrayInputStream(saved.toByteArray()),options,1500);assertEquals(3,restored.getInt("revision"));assertEquals(original.getString("digest"),restored.getString("digest"));assertNotEquals(changed.getString("digest"),restored.getString("digest"));
        assertEquals(0,discover(book,"核查").getJSONArray("skills").length());enable(book,restored);JSONObject fresh=discover(book,"核查").getJSONArray("skills").getJSONObject(0);assertEquals(3,fresh.getInt("revision"));
        assertThrows(SecurityException.class,()->book.readForHost(scope(),fresh.getString("id"),"SKILL.md",0,100,1,original.getString("digest"),Set.of(),1600));
        assertThrows(SecurityException.class,()->book.readForHost(scope(),fresh.getString("id"),"SKILL.md",0,100,changed.getString("digest"),Set.of(),1600));
        assertEquals(3,book.readForHost(scope(),fresh.getString("id"),"SKILL.md",0,100,3,fresh.getString("digest"),Set.of(),1600).getInt("revision"));
    }
}
