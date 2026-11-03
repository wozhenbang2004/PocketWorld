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

/** Original registry/reader/dispatch admission, with real on-disk packages and cold reopen. */
public final class SkillSourcePolicyTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject input(String name,String body)throws Exception {
        return new JSONObject().put("manifest","---\nname: "+name+"\ndescription: source selection guide\n---\n"+body)
                .put("source","native:test").put("sourceVersion","pinned-v1")
                .put("resources",new JSONObject().put("references/readme.md","PRIVATE_RESOURCE").put("scripts/example.py","print('body')\n"));
    }
    private JSONObject install(SkillBook book,String name,String body,SkillSourcePolicy.Layer layer,SkillSourcePolicy.Invocation invocation)throws Exception {
        JSONObject row=book.install(scope(),input(name,body),1000);
        row=book.configureSourceForOwner(scope(),row.getString("id"),row.getInt("revision"),row.getString("digest"),row.getLong("executionGeneration"),layer,invocation,1100);
        return book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1200);
    }
    private JSONObject page(SkillBook book)throws Exception{return book.cataloguePageForHost(scope(),Set.of(),null,20,1300);}
    private JSONObject diagnostics(SkillBook book,String id)throws Exception {
        JSONArray rows=book.diagnoseDiscovery(scope(),"source",Set.of(),1300).getJSONArray("skills");
        for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id")))return rows.getJSONObject(i);
        throw new AssertionError("missing original registered source");
    }

    @Test public void sameNameUsesNativeLayerBeforeQueryAndSurvivesColdReopen()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);
        JSONObject bundled=install(book,"same-name","BUILTIN_BODY",SkillSourcePolicy.Layer.BUNDLED,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject user=install(book,"same-name","USER_BODY",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject project=install(book,"same-name","PROJECT_BODY",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        SkillBook reopened=new SkillBook(root);JSONObject result=page(reopened);assertEquals(1,result.getInt("totalCount"));JSONObject selected=result.getJSONArray("skills").getJSONObject(0);
        assertEquals(project.getString("id"),selected.getString("id"));assertEquals("PROJECT",selected.getJSONObject("sourcePolicy").getString("layer"));
        assertEquals("native:test",selected.getString("source"));assertEquals("pinned-v1",selected.getString("sourceVersion"));assertFalse(selected.getJSONObject("sourcePolicy").getBoolean("trustedCode"));
        assertFalse(result.toString().contains("PROJECT_BODY"));assertFalse(result.toString().contains("PRIVATE_RESOURCE"));
        assertEquals("shadowed",diagnostics(reopened,user.getString("id")).getString("status"));assertEquals(project.getString("id"),diagnostics(reopened,bundled.getString("id")).getString("shadowedBy"));
        assertThrows(SecurityException.class,()->reopened.readForHost(scope(),user.getString("id"),"SKILL.md",0,100,1,user.getString("digest"),Set.of(),1300));
        assertTrue(reopened.readForHost(scope(),project.getString("id"),"SKILL.md",0,1000,1,project.getString("digest"),Set.of(),1300).getString("content").contains("PROJECT_BODY"));
    }

    @Test public void sameLayerTieIsStableAndAllSourcesRemainVisibleToOwner()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);JSONObject a=install(book,"same-name","ONE",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER),b=install(book,"same-name","TWO",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        String expected=a.getString("id").compareTo(b.getString("id"))<0?a.getString("id"):b.getString("id");
        assertEquals(expected,page(book).getJSONArray("skills").getJSONObject(0).getString("id"));assertEquals(expected,page(new SkillBook(root)).getJSONArray("skills").getJSONObject(0).getString("id"));
        assertEquals(2,book.list(scope(),1300).length());assertEquals(1,book.discoverForHost(scope(),"source",Set.of(),20,32000,1300).getJSONObject("omitted").getInt("shadowed"));
    }

    @Test public void ownerOnlyWinnerCannotFallThroughToLowerLayerOrAnyHostReader()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());install(book,"same-name","LOWER",SkillSourcePolicy.Layer.BUNDLED,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject row=install(book,"same-name","OWNER_BODY",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY);String id=row.getString("id"),digest=row.getString("digest");
        assertEquals(0,page(book).getInt("totalCount"));assertEquals(0,book.catalogueForHost(scope(),1300).getJSONArray("skills").length());assertEquals("ownerOnly",diagnostics(book,id).getString("status"));
        assertTrue(book.read(scope(),id,"SKILL.md",0,1000,1300).getString("content").contains("OWNER_BODY"));
        assertThrows(SecurityException.class,()->book.loadForHost(scope(),id,digest,Set.of(),1300));
        assertThrows(SecurityException.class,()->book.readForHost(scope(),id,"references/readme.md",0,100,1,digest,Set.of(),1300));
        assertThrows(SecurityException.class,()->book.selectReferenceForHost(scope(),id,1,digest,Set.of(),1300));
        assertThrows(SecurityException.class,()->book.executionSnapshot(scope(),id,1,digest,Set.of(),1300));
    }

    @Test public void manifestCanRestrictModelButCannotCreateNativeSourcePolicy()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject request=input("restricted","OWNER_BODY");request.put("manifest",request.getString("manifest").replace("description: source selection guide","description: source selection guide\ndisable-model-invocation: true\nuser-invocable: false"));
        JSONObject row=book.install(scope(),request,1000);book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1100);
        assertEquals(0,page(book).getInt("totalCount"));JSONObject policy=book.get(scope(),row.getString("id"),1200).getJSONObject("sourcePolicy");assertFalse(policy.getBoolean("modelInvocable"));assertFalse(policy.getBoolean("userInvocable"));assertTrue(policy.getBoolean("manifestRestrictsModel"));
        assertThrows(SecurityException.class,()->book.install(scope(),input("forged","BODY").put("sourcePolicy",new JSONObject().put("layer","PROJECT")),1300));
        assertThrows(IllegalArgumentException.class,()->SkillManifest.parse("---\nname: invalid\ndescription: invalid policy\ndisable-model-invocation: sometimes\n---\nBODY"));
    }

    @Test public void policyChangesWithdrawApprovalAndRejectOldReferenceAndOwnerCallback()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);JSONObject original=install(book,"source-one","BODY",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        String id=original.getString("id"),digest=original.getString("digest");long generation=original.getLong("executionGeneration");
        SkillBook.ReferenceSelection old=book.selectReferenceForHost(scope(),id,1,digest,Set.of(),1300);
        JSONObject changed=new SkillBook(root).configureSourceForOwner(scope(),id,1,digest,generation,SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,1400);
        assertFalse(changed.getBoolean("enabled"));assertEquals(generation+1,changed.getLong("executionGeneration"));assertEquals(0,page(book).getInt("totalCount"));assertThrows(SecurityException.class,old::checkCurrent);
        assertThrows(SecurityException.class,()->book.configureSourceForOwner(scope(),id,1,digest,generation,SkillSourcePolicy.Layer.BUNDLED,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,1500));
        book.setEnabled(scope(),id,true,digest,true,1600);assertThrows(SecurityException.class,old::checkCurrent);assertEquals(1,page(book).getInt("totalCount"));
    }

    @Test public void repeatedEnableAndDisableDoNotCreateNewApprovalGenerations()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject row=install(book,"guide","BODY",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);String id=row.getString("id"),digest=row.getString("digest");long enabled=row.getLong("executionGeneration");
        assertEquals(enabled,book.setEnabled(scope(),id,true,digest,true,1400).getLong("executionGeneration"));
        long disabled=book.setEnabled(scope(),id,false,"",false,1500).getLong("executionGeneration");assertEquals(enabled+1,disabled);
        assertEquals(disabled,book.setEnabled(scope(),id,false,"",false,1600).getLong("executionGeneration"));
    }

    @Test public void brokenWinnerDoesNotHideUnrelatedSourcesOrDeleteKnownRows()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);JSONObject low=install(book,"same-name","LOWER",SkillSourcePolicy.Layer.BUNDLED,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject high=install(book,"same-name","HIGHER",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);JSONObject other=install(book,"unrelated","HEALTHY",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        File path=new File(root,high.getString("id")+"/1/same-name/SKILL.md");Files.write(path.toPath(),"broken".getBytes(StandardCharsets.UTF_8));
        JSONObject result=page(book);assertEquals("partial",result.getString("status"));assertEquals(1,result.getInt("totalCount"));assertEquals(other.getString("id"),result.getJSONArray("skills").getJSONObject(0).getString("id"));assertEquals(3,book.list(scope(),1400).length());
        assertThrows(SecurityException.class,()->book.readForHost(scope(),low.getString("id"),"SKILL.md",0,100,1,low.getString("digest"),Set.of(),1400));
        book.setEnabled(scope(),high.getString("id"),false,"",false,1500);assertEquals(2,page(book).getInt("totalCount"));
    }

    @Test public void contentUpdateKeepsOwnerRestrictionAndInvalidatesTheOldVersion()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject old=install(book,"same-name","OLD",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY);
        JSONObject next=book.install(scope(),input("same-name","NEW").put("id",old.getString("id")).put("revision",1),1400);
        assertEquals(2,next.getInt("revision"));assertEquals("PROJECT",next.getJSONObject("sourcePolicy").getString("layer"));assertEquals("OWNER_ONLY",next.getJSONObject("sourcePolicy").getString("invocation"));assertFalse(next.getBoolean("enabled"));
        book.setEnabled(scope(),next.getString("id"),true,next.getString("digest"),true,1500);assertEquals(0,page(book).getInt("totalCount"));
        assertThrows(SecurityException.class,()->book.readForHost(scope(),old.getString("id"),"SKILL.md",0,100,1,old.getString("digest"),Set.of(),1600));
    }

    @Test public void oldSourceReviewCannotOverwriteANewerOwnerPolicy()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject row=install(book,"guide","BODY",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        SkillBook.SourceReview review=book.inspectRetainedRevision(scope(),row.getString("id"),1,1300);
        book.configureSourceForOwner(scope(),row.getString("id"),1,row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY,1400);
        assertEquals("stale_target",assertThrows(SkillBook.SourceUnavailable.class,()->book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,true,1500)).code);
        assertEquals(1,book.get(scope(),row.getString("id"),1600).getInt("revision"));assertEquals("OWNER_ONLY",book.get(scope(),row.getString("id"),1600).getJSONObject("sourcePolicy").getString("invocation"));
    }

    @Test public void currentScopeCannotClassifyReadOrDiscoverAnotherScope()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject row=install(book,"private-guide","PRIVATE",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject other=new JSONObject().put("ownerId","owner").put("spaceId","group:1");
        assertEquals(0,book.cataloguePageForHost(other,Set.of(),null,20,1300).getInt("totalCount"));
        assertThrows(IllegalArgumentException.class,()->book.configureSourceForOwner(other,row.getString("id"),1,row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,1400));
        assertThrows(IllegalArgumentException.class,()->book.readForHost(other,row.getString("id"),"SKILL.md",0,100,1,row.getString("digest"),Set.of(),1400));
    }
}
