package dev.pocketengine.features.skills;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SkillCataloguePagingTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject add(SkillBook book,String name)throws Exception{
        JSONObject row=book.install(scope(),new JSONObject().put("manifest","---\nname: "+name+"\ndescription: 与当前任务不同义的资料 "+name+"\n---\nPRIVATE_BODY_DO_NOT_INJECT").put("source","native:test"),1000);
        book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1100);return row;
    }
    @Test public void entireUnmatchedCatalogueIsDiscoverableInStableMetadataPages()throws Exception{
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));for(int i=0;i<13;i++)add(book,String.format(java.util.Locale.ROOT,"guide-%02d",i));
        assertEquals(0,book.discoverForHost(scope(),"calculator",Set.of(),8,6000,1200).getJSONArray("skills").length());
        JSONObject cursor=null;Set<String> ids=new HashSet<>();int pages=0;do{JSONObject page=book.cataloguePageForHost(scope(),Set.of(),cursor,4,1200);assertEquals(13,page.getInt("totalCount"));assertFalse(page.toString().contains("PRIVATE_BODY"));for(int i=0;i<page.getJSONArray("skills").length();i++)assertTrue(ids.add(page.getJSONArray("skills").getJSONObject(i).getString("id")));cursor=page.optJSONObject("nextCursor");pages++;}while(cursor!=null);
        assertEquals(13,ids.size());assertEquals(4,pages);
    }
    @Test public void changedRevisionCapabilityOrAudienceRejectsOldCursor()throws Exception{
        File root=new File(temp.getRoot(),"skills");SkillBook book=new SkillBook(root);JSONObject first=add(book,"a-guide");add(book,"b-guide");JSONObject cursor=book.cataloguePageForHost(scope(),Set.of(),null,1,1200).getJSONObject("nextCursor");
        assertThrows(SecurityException.class,()->book.cataloguePageForHost(scope(),Set.of("phone_observe"),cursor,1,1200));
        assertThrows(SecurityException.class,()->book.cataloguePageForHost(new JSONObject().put("ownerId","other").put("spaceId","private"),Set.of(),cursor,1,1200));
        new SkillBook(root).setEnabled(scope(),first.getString("id"),false,"",false,1300);
        assertThrows(SecurityException.class,()->book.cataloguePageForHost(scope(),Set.of(),cursor,1,1400));
        assertThrows(IllegalArgumentException.class,()->book.cataloguePageForHost(scope(),Set.of(),null,21,1400));
    }
    private static String version(JSONObject row)throws Exception{return new org.json.JSONArray().put(row.getString("id")).put(row.getInt("revision")).put(row.getString("digest")).toString();}
    @Test public void exactNativeSelectionIsAppliedBeforeCountsAndCursorNotAsAPostPageFilter()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject chosen=add(book,"z-selected");for(int n=0;n<12;n++)add(book,"a-hidden-"+n);
        Set<String> selected=Set.of(version(chosen));JSONObject page=book.cataloguePageForHost(scope(),Set.of(),null,1,selected,1200);
        assertEquals(1,page.getInt("totalCount"));assertEquals(chosen.getString("id"),page.getJSONArray("skills").getJSONObject(0).getString("id"));assertFalse(page.getBoolean("hasMore"));assertFalse(page.toString().contains("a-hidden"));
        assertEquals(0,book.cataloguePageForHost(scope(),Set.of(),null,2,Set.of(),1200).getInt("totalCount"));
        JSONObject changed=book.install(scope(),new JSONObject().put("source","native:test").put("id",chosen.getString("id")).put("revision",chosen.getInt("revision")).put("manifest","---\nname: z-selected\ndescription: new exact version\n---\nnew body"),1300);
        book.setEnabled(scope(),changed.getString("id"),true,changed.getString("digest"),true,1400);
        assertEquals(0,book.cataloguePageForHost(scope(),Set.of(),null,2,selected,1500).getInt("totalCount"));
    }
    @Test public void metadataPagesDoNotGrantMissingCapabilitiesOrIncludeDisabledVersions()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));JSONObject row=book.install(scope(),new JSONObject().put("source","native:test").put("manifest","---\nname: capability-only\ndescription: needs original phone capability\n---\nPRIVATE_BODY").put("requiredCapabilities",new org.json.JSONArray().put("phone_tap")),1000);
        book.setEnabled(scope(),row.getString("id"),true,row.getString("digest"),true,1100);
        assertEquals(0,book.cataloguePageForHost(scope(),Set.of(),null,2,1200).getInt("totalCount"));
        JSONObject page=book.cataloguePageForHost(scope(),Set.of("phone_tap"),null,2,1200);assertEquals(1,page.getInt("totalCount"));assertEquals(0,page.getJSONArray("grantsAdded").length());assertFalse(page.getBoolean("executable"));assertFalse(page.toString().contains("PRIVATE_BODY"));
        book.setEnabled(scope(),row.getString("id"),false,"",false,1300);assertEquals(0,book.cataloguePageForHost(scope(),Set.of("phone_tap"),null,2,1400).getInt("totalCount"));
    }
    @Test public void malformedCursorNeverSilentlyRestartsOrSkipsRows()throws Exception {
        SkillBook book=new SkillBook(new File(temp.getRoot(),"skills"));add(book,"a-guide");add(book,"b-guide");JSONObject cursor=book.cataloguePageForHost(scope(),Set.of(),null,1,1200).getJSONObject("nextCursor");
        for(Object value:new Object[]{-1,3,"1",1.5,true}){JSONObject bad=new JSONObject(cursor.toString()).put("offset",value);assertThrows(SecurityException.class,()->book.cataloguePageForHost(scope(),Set.of(),bad,1,1200));}
        JSONObject extra=new JSONObject(cursor.toString()).put("ownerId","owner");assertThrows(SecurityException.class,()->book.cataloguePageForHost(scope(),Set.of(),extra,1,1200));
    }

}
