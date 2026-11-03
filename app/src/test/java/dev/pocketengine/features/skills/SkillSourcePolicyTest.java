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
    private static final String PROJECT_A="10000000-0000-0000-0000-000000000001",PROJECT_B="10000000-0000-0000-0000-000000000002";
    private static final String CHECKOUT_A="20000000-0000-0000-0000-000000000001",CHECKOUT_B="20000000-0000-0000-0000-000000000002";
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject projectScope()throws Exception{return projectScope(PROJECT_A,CHECKOUT_A);}
    private JSONObject projectScope(String project,String checkout)throws Exception{return scope().put("projectId",project).put("checkoutId",checkout).put("projectRevision","a".repeat(64));}
    private JSONObject input(String name,String body)throws Exception {
        return new JSONObject().put("manifest","---\nname: "+name+"\ndescription: source selection guide\n---\n"+body)
                .put("source","native:test").put("sourceVersion","pinned-v1")
                .put("resources",new JSONObject().put("references/readme.md","PRIVATE_RESOURCE").put("scripts/example.py","print('body')\n"));
    }
    private JSONObject install(SkillBook book,String name,String body,SkillSourcePolicy.Layer layer,SkillSourcePolicy.Invocation invocation)throws Exception {
        JSONObject row=book.install(scope(),input(name,body),1000);
        row=book.configureSourceForOwner(scope(),row.getString("id"),row.getInt("revision"),row.getString("digest"),row.getLong("executionGeneration"),layer,invocation,layer==SkillSourcePolicy.Layer.PROJECT?PROJECT_A:null,1100);
        return book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1200);
    }
    private JSONObject page(SkillBook book)throws Exception{return book.cataloguePageForHost(projectScope(),Set.of(),null,20,1300);}
    private JSONObject diagnostics(SkillBook book,String id)throws Exception {
        JSONArray rows=book.diagnoseDiscovery(projectScope(),"source",Set.of(),1300).getJSONArray("skills");
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
        assertThrows(SecurityException.class,()->reopened.readForHost(projectScope(),user.getString("id"),"SKILL.md",0,100,1,user.getString("digest"),Set.of(),1300));
        assertTrue(reopened.readForHost(projectScope(),project.getString("id"),"SKILL.md",0,1000,1,project.getString("digest"),Set.of(),1300).getString("content").contains("PROJECT_BODY"));
    }

    @Test public void sameLayerTieIsStableAndAllSourcesRemainVisibleToOwner()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);JSONObject a=install(book,"same-name","ONE",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER),b=install(book,"same-name","TWO",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        String expected=a.getString("id").compareTo(b.getString("id"))<0?a.getString("id"):b.getString("id");
        assertEquals(expected,page(book).getJSONArray("skills").getJSONObject(0).getString("id"));assertEquals(expected,page(new SkillBook(root)).getJSONArray("skills").getJSONObject(0).getString("id"));
        assertEquals(2,book.list(scope(),1300).length());assertEquals(1,book.discoverForHost(projectScope(),"source",Set.of(),20,32000,1300).getJSONObject("omitted").getInt("shadowed"));
    }

    @Test public void ownerOnlyWinnerCannotFallThroughToLowerLayerOrAnyHostReader()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());install(book,"same-name","LOWER",SkillSourcePolicy.Layer.BUNDLED,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject row=install(book,"same-name","OWNER_BODY",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY);String id=row.getString("id"),digest=row.getString("digest");
        assertEquals(0,page(book).getInt("totalCount"));assertEquals(0,book.catalogueForHost(projectScope(),1300).getJSONArray("skills").length());assertEquals("ownerOnly",diagnostics(book,id).getString("status"));
        assertTrue(book.read(scope(),id,"SKILL.md",0,1000,1300).getString("content").contains("OWNER_BODY"));
        assertThrows(SecurityException.class,()->book.loadForHost(projectScope(),id,digest,Set.of(),1300));
        assertThrows(SecurityException.class,()->book.readForHost(projectScope(),id,"references/readme.md",0,100,1,digest,Set.of(),1300));
        assertThrows(SecurityException.class,()->book.selectReferenceForHost(projectScope(),id,1,digest,Set.of(),1300));
        assertThrows(SecurityException.class,()->book.executionSnapshot(projectScope(),id,1,digest,Set.of(),1300));
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
        SkillBook.ReferenceSelection old=book.selectReferenceForHost(projectScope(),id,1,digest,Set.of(),1300);
        JSONObject changed=new SkillBook(root).configureSourceForOwner(scope(),id,1,digest,generation,SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,PROJECT_A,1400);
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
        assertThrows(SecurityException.class,()->book.readForHost(projectScope(),low.getString("id"),"SKILL.md",0,100,1,low.getString("digest"),Set.of(),1400));
        book.setEnabled(scope(),high.getString("id"),false,"",false,1500);assertEquals(2,page(book).getInt("totalCount"));
    }

    @Test public void contentUpdateKeepsOwnerRestrictionAndInvalidatesTheOldVersion()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject old=install(book,"same-name","OLD",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY);
        JSONObject next=book.install(scope(),input("same-name","NEW").put("id",old.getString("id")).put("revision",1),1400);
        assertEquals(2,next.getInt("revision"));assertEquals("PROJECT",next.getJSONObject("sourcePolicy").getString("layer"));assertEquals("OWNER_ONLY",next.getJSONObject("sourcePolicy").getString("invocation"));assertFalse(next.getBoolean("enabled"));
        book.setEnabled(scope(),next.getString("id"),true,next.getString("digest"),true,1500);assertEquals(0,page(book).getInt("totalCount"));
        assertThrows(SecurityException.class,()->book.readForHost(projectScope(),old.getString("id"),"SKILL.md",0,100,1,old.getString("digest"),Set.of(),1600));
    }

    @Test public void oldSourceReviewCannotOverwriteANewerOwnerPolicy()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject row=install(book,"guide","BODY",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        SkillBook.SourceReview review=book.inspectRetainedRevision(scope(),row.getString("id"),1,1300);
        book.configureSourceForOwner(scope(),row.getString("id"),1,row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.OWNER_ONLY,PROJECT_A,1400);
        assertEquals("stale_target",assertThrows(SkillBook.SourceUnavailable.class,()->book.applySourceReview(scope(),review,SkillBook.SourceChoice.REPLACE,true,1500)).code);
        assertEquals(1,book.get(scope(),row.getString("id"),1600).getInt("revision"));assertEquals("OWNER_ONLY",book.get(scope(),row.getString("id"),1600).getJSONObject("sourcePolicy").getString("invocation"));
    }

    @Test public void currentScopeCannotClassifyReadOrDiscoverAnotherScope()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject row=install(book,"private-guide","PRIVATE",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject other=new JSONObject().put("ownerId","owner").put("spaceId","group:1");
        assertEquals(0,book.cataloguePageForHost(other,Set.of(),null,20,1300).getInt("totalCount"));
        assertThrows(IllegalArgumentException.class,()->book.configureSourceForOwner(other,row.getString("id"),1,row.getString("digest"),row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,PROJECT_A,1400));
        assertThrows(IllegalArgumentException.class,()->book.readForHost(other,row.getString("id"),"SKILL.md",0,100,1,row.getString("digest"),Set.of(),1400));
    }

    @Test public void projectPriorityAppliesOnlyInsideItsOriginalNamespaceAndOwnerManagementStaysComplete()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject global=install(book,"same-name","GLOBAL",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject a=install(book,"same-name","PROJECT_A",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        JSONObject b=install(book,"same-name","PROJECT_B",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        b=book.configureSourceForOwner(scope(),b.getString("id"),1,b.getString("digest"),b.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,PROJECT_B,1250);
        b=book.setEnabled(scope(),b.getString("id"),true,b.getString("digest"),true,1260);final JSONObject projectB=b;
        JSONObject aScope=projectScope(),bScope=projectScope(PROJECT_B,CHECKOUT_B);
        assertEquals(a.getString("id"),book.cataloguePageForHost(aScope,Set.of(),null,20,1300).getJSONArray("skills").getJSONObject(0).getString("id"));
        assertEquals(b.getString("id"),book.cataloguePageForHost(bScope,Set.of(),null,20,1300).getJSONArray("skills").getJSONObject(0).getString("id"));
        JSONObject globalPage=book.cataloguePageForHost(scope(),Set.of(),null,20,1300);assertEquals(global.getString("id"),globalPage.getJSONArray("skills").getJSONObject(0).getString("id"));
        assertFalse(globalPage.toString().contains(PROJECT_A));assertFalse(globalPage.toString().contains(PROJECT_B));assertEquals(3,book.list(scope(),1300).length());
        assertTrue(book.read(scope(),a.getString("id"),"SKILL.md",0,1000,1300).getString("content").contains("PROJECT_A"));
        assertThrows(SecurityException.class,()->book.readForHost(scope(),a.getString("id"),"SKILL.md",0,100,1,a.getString("digest"),Set.of(),1300));
        assertThrows(SecurityException.class,()->book.readForHost(aScope,projectB.getString("id"),"SKILL.md",0,100,1,projectB.getString("digest"),Set.of(),1300));
        assertThrows(SecurityException.class,()->book.selectExecutionForOwner(bScope,a.getString("id"),1,a.getString("digest"),a.getLong("executionGeneration"),Set.of(),1300));
        SkillBook.OwnerExecutionSelection owner=book.selectExecutionForOwner(aScope,a.getString("id"),1,a.getString("digest"),a.getLong("executionGeneration"),Set.of(),1300);
        owner.requireRequest(aScope,a.getString("id"),1,a.getString("digest"));
        assertThrows(SecurityException.class,()->owner.requireRequest(bScope,a.getString("id"),1,a.getString("digest")));
    }

    @Test public void projectRebindingWithdrawsOldBodyScriptAndCursorEvenWhenPackageBytesStayEqual()throws Exception {
        SkillBook book=new SkillBook(temp.newFolder());JSONObject row=install(book,"a-guide","BODY",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        install(book,"z-global","SECOND",SkillSourcePolicy.Layer.USER,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        String id=row.getString("id"),digest=row.getString("digest");long generation=row.getLong("executionGeneration");JSONObject aScope=projectScope(),bScope=projectScope(PROJECT_B,CHECKOUT_B);
        JSONObject cursor=book.cataloguePageForHost(aScope,Set.of(),null,1,1300).getJSONObject("nextCursor");
        SkillBook.ReferenceSelection reference=book.selectReferenceForHost(aScope,id,1,digest,Set.of(),1300);
        assertEquals(PROJECT_A,reference.metadata().getJSONObject("skillScope").getString("projectId"));assertEquals(CHECKOUT_A,reference.metadata().getJSONObject("skillScope").getString("checkoutId"));
        SkillBook.OwnerExecutionSelection owner=book.selectExecutionForOwner(aScope,id,1,digest,generation,Set.of(),1300);
        JSONObject otherCheckout=projectScope(PROJECT_A,CHECKOUT_B);
        assertThrows(SecurityException.class,()->book.cataloguePageForHost(otherCheckout,Set.of(),cursor,1,1300));
        assertThrows(SecurityException.class,()->owner.requireRequest(otherCheckout,id,1,digest));
        JSONObject next=book.configureSourceForOwner(scope(),id,1,digest,generation,SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,PROJECT_B,1400);
        assertFalse(next.getBoolean("enabled"));assertEquals("",next.getString("approvedDigest"));assertEquals(generation+1,next.getLong("executionGeneration"));
        assertThrows(SecurityException.class,reference::checkCurrent);assertThrows(SecurityException.class,owner::checkCurrent);
        book.setEnabled(scope(),id,true,digest,true,1500);
        assertThrows(SecurityException.class,()->book.cataloguePageForHost(aScope,Set.of(),cursor,1,1500));
        assertThrows(SecurityException.class,()->book.readForHost(aScope,id,"scripts/example.py",0,100,1,digest,Set.of(),1500));
        JSONObject body=book.readForHost(bScope,id,"SKILL.md",0,1000,1,digest,Set.of(),1500);
        assertEquals(SkillSourcePolicy.scopeFingerprint(bScope),body.getString("skillScopeFingerprint"));assertEquals(PROJECT_B,body.getJSONObject("sourcePolicy").getString("projectId"));
        assertThrows(SecurityException.class,()->book.configureSourceForOwner(scope(),id,1,digest,next.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,1600));
    }

    @Test public void legacyUnboundProjectIsRetainedForExplicitRepairWithoutGlobalFallback()throws Exception {
        File root=temp.newFolder();SkillBook book=new SkillBook(root);JSONObject row=install(book,"legacy-guide","LEGACY_BODY",SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER);
        File index=new File(root,"index.json");JSONObject saved=new JSONObject(new String(Files.readAllBytes(index.toPath()),StandardCharsets.UTF_8));saved.getJSONArray("skills").getJSONObject(0).getJSONObject("sourcePolicy").remove("projectId");Files.write(index.toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));
        SkillBook reopened=new SkillBook(root);String id=row.getString("id"),digest=row.getString("digest");JSONObject retained=reopened.get(scope(),id,1300);
        assertTrue(retained.getJSONObject("sourcePolicy").getBoolean("projectBindingRequired"));assertFalse(retained.getBoolean("active"));assertEquals("unbound_project",retained.getJSONObject("sourcePolicy").getString("namespace"));
        assertEquals(0,page(reopened).getInt("totalCount"));assertEquals(0,reopened.catalogueForHost(scope(),1300).getJSONArray("skills").length());
        assertTrue(reopened.read(scope(),id,"SKILL.md",0,1000,1300).getString("content").contains("LEGACY_BODY"));
        assertThrows(SecurityException.class,()->reopened.setEnabled(scope(),id,true,digest,true,1400));
        JSONObject repaired=reopened.configureSourceForOwner(scope(),id,1,digest,row.getLong("executionGeneration"),SkillSourcePolicy.Layer.PROJECT,SkillSourcePolicy.Invocation.MODEL_AND_OWNER,PROJECT_A,1500);
        assertFalse(repaired.getBoolean("enabled"));assertEquals(row.getLong("executionGeneration")+1,repaired.getLong("executionGeneration"));
        reopened.setEnabled(scope(),id,true,digest,true,1600);assertEquals(1,page(reopened).getInt("totalCount"));
        assertEquals(0,reopened.catalogueForHost(scope(),1600).getJSONArray("skills").length());
    }
}
