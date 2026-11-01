package dev.pocketengine.browser;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.skills.SkillsModule;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real existing SkillBook package files/digests; no actual webpage/provider execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class BrowserReadRecipeCatalogTest {
    @Test public void originalSkillPackagePersistsReadRecipeAndDisablingRevokesPreparedVersion()throws Exception {SkillsModule skills=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));BrowserReadRecipeCatalog catalog=new BrowserReadRecipeCatalog(skills);JSONObject saved=catalog.saveForOwner(BrowserReadRecipeTest.source("table"),"项目",true),skill=saved.getJSONObject("skill"),ref=new JSONObject().put("id",skill.getString("id")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest"));assertFalse(saved.getBoolean("executionAuthorized"));BrowserReadRecipeCatalog.Prepared prepared=catalog.prepare("owner-default",ref,new JSONObject().put("mode","table").put("query","项目"));prepared.requireCurrent();assertTrue(catalog.forHost("owner-default").toString().contains(ref.getString("id")));SkillsModule reopened=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));new BrowserReadRecipeCatalog(reopened).prepare("owner-default",ref,new JSONObject().put("mode","table").put("query","项目")).requireCurrent();skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",false));assertThrows(SecurityException.class,prepared::requireCurrent);assertThrows(SecurityException.class,()->catalog.prepare("owner-default",ref,new JSONObject().put("mode","table").put("query","项目")));}
    @Test public void installedDisabledRecipeAndOtherProfileAreNotCurrentExecutableConfig()throws Exception {SkillsModule skills=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));BrowserReadRecipeCatalog catalog=new BrowserReadRecipeCatalog(skills);JSONObject skill=catalog.saveForOwner(BrowserReadRecipeTest.source("list"),"条目",false).getJSONObject("skill"),ref=new JSONObject().put("id",skill.getString("id")).put("revision",skill.getInt("revision")).put("digest",skill.getString("digest"));assertFalse(skill.getBoolean("enabled"));assertThrows(SecurityException.class,()->catalog.prepare("owner-default",ref,new JSONObject().put("mode","list").put("query","条目")));skills.handle("setEnabled",new JSONObject().put("id",skill.getString("id")).put("enabled",true).put("acceptedDigest",skill.getString("digest")).put("confirmed",true));assertThrows(SecurityException.class,()->catalog.prepare("profile-other",ref,new JSONObject().put("mode","list").put("query","条目")));}
}
