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
}
