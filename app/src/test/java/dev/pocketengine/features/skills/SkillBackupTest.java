package dev.pocketengine.features.skills;

import dev.pocketengine.core.RunControl;
import java.io.File;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class SkillBackupTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject input()throws Exception{return new JSONObject().put("manifest","---\nname: migration-check\ndescription: 检查恢复内容\n---\n先审阅原资料。\n").put("source","owner").put("requiredCapabilities",new JSONArray()).put("resources",new JSONObject().put("references/check.txt","原资料"));}
    @Test public void verifiedPackageRestoresWithNewIdentityNoApprovalAndNoModelInvocation()throws Exception {
        SkillBook source=new SkillBook(temp.newFolder());JSONObject old=source.install(scope(),input(),1000);source.setEnabled(scope(),old.getString("id"),true,old.getString("digest"),true,1001);JSONObject section=source.exportBackupForOwner(scope(),new RunControl());
        File root=temp.newFolder();SkillBook target=new SkillBook(root);String action=UUID.randomUUID().toString();var review=target.reviewBackupForOwner(scope(),section,action,new RunControl());assertTrue(review.restore().getBoolean("found"));JSONObject restored=target.list(scope(),2000).getJSONObject(0);
        assertNotEquals(old.getString("id"),restored.getString("id"));assertEquals(old.getString("digest"),restored.getString("digest"));assertFalse(restored.getBoolean("enabled"));assertFalse(restored.getBoolean("active"));assertEquals("",restored.getString("approvedDigest"));assertEquals("OWNER_ONLY",restored.getJSONObject("sourcePolicy").getString("invocation"));
        assertTrue(new SkillBook(root).queryBackupForOwner(scope(),section,action,new RunControl()).getBoolean("found"));assertEquals(1,target.list(scope(),2000).length());assertThrows(SecurityException.class,review::restore);
        assertThrows(SecurityException.class,()->target.readForHost(scope(),restored.getString("id"),"SKILL.md",0,100,restored.getString("digest"),Set.of(),2000));
    }
    @Test public void changedCatalogOrCancellationDoesNotOverwriteOrReplayAnImport()throws Exception {
        SkillBook source=new SkillBook(temp.newFolder());source.install(scope(),input(),1000);JSONObject section=source.exportBackupForOwner(scope(),new RunControl());SkillBook target=new SkillBook(temp.newFolder());String action=UUID.randomUUID().toString();var review=target.reviewBackupForOwner(scope(),section,action,new RunControl());target.install(scope(),input(),1000);String before=target.list(scope(),2000).toString();
        assertThrows(SecurityException.class,review::restore);assertEquals(before,target.list(scope(),2000).toString());assertFalse(target.queryBackupForOwner(scope(),section,action,new RunControl()).getBoolean("found"));RunControl cancelled=new RunControl();var other=target.reviewBackupForOwner(scope(),section,UUID.randomUUID().toString(),cancelled);cancelled.cancel();assertThrows(InterruptedException.class,other::restore);assertEquals(before,target.list(scope(),2000).toString());
    }
}
