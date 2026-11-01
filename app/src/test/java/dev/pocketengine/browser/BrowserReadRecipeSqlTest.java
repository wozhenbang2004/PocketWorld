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
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static dev.pocketengine.browser.BrowserSemanticReadTest.j;

/** Original SQLite/prepared/read-scope/SkillBook; DOM is explicitly controlled interface data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class BrowserReadRecipeSqlTest {
    static JSONObject savedRecipe(BrowserSemanticReadSqlTest.F x,BrowserReadRecipeCatalog catalog)throws Exception {
        x.driver.structuredRows=BrowserStructuredSourceTest.delivered("table").getJSONObject("read").getJSONArray("data");
        JSONObject observed=x.invoke("observe",x.args("table"));JSONObject saved=x.invoke("retain_source",j("tabId",x.tab,"observationId",observed.getString("observationId")));assertTrue(saved.toString(),saved.getBoolean("sourceRetained"));
        JSONObject source=BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).getJSONObject(0),skill=catalog.saveForOwner(source,"项目",true).getJSONObject("skill");return j("id",skill.getString("id"),"revision",skill.getInt("revision"),"digest",skill.getString("digest"));
    }
    @Test public void sameSavedRecipePerformsOneOriginalReadAndDriftReturnsActualObservationWithoutRetry()throws Exception {
        SkillsModule skills=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));BrowserReadRecipeCatalog catalog=new BrowserReadRecipeCatalog(skills);
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(false,catalog)){
            JSONObject ref=savedRecipe(x,catalog),args=x.args("table").put("recipe",ref);args.getJSONObject("read").put("query","项目");int before=x.driver.reads;
            JSONObject read=x.invoke("observe",args);assertTrue(read.toString(),read.getBoolean("ok"));assertEquals("matched",read.getJSONObject("readRecipe").getString("status"));assertEquals("¥42 😀",read.getJSONArray("recipeOutput").getJSONObject(0).getJSONObject("values").getString("column_1"));assertEquals(before+1,x.driver.reads);assertEquals("native_read_scope",read.getJSONObject("readAuthorization").getString("mode"));
            x.driver.structuredRows.getJSONObject(0).getJSONArray("cells").getJSONObject(1).put("text","changed column");JSONObject drift=x.invoke("observe",args);assertEquals("drift",drift.getJSONObject("readRecipe").getString("status"));assertEquals("header_precondition_changed",drift.getJSONObject("readRecipe").getString("reason"));assertFalse(drift.has("recipeOutput"));assertTrue(drift.getJSONObject("read").getJSONArray("data").toString().contains("changed column"));assertEquals(before+2,x.driver.reads);assertEquals(0,x.driver.original.mutableDispatches);
            assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());
        }
    }
    @Test public void disabledOrWrongVersionRecipeCannotReachNativeReadOrBorrowOldScope()throws Exception {
        SkillsModule skills=new SkillsModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));BrowserReadRecipeCatalog catalog=new BrowserReadRecipeCatalog(skills);
        try(BrowserSemanticReadSqlTest.F x=new BrowserSemanticReadSqlTest.F(false,catalog)){
            JSONObject ref=savedRecipe(x,catalog),args=x.args("table").put("recipe",ref);args.getJSONObject("read").put("query","项目");int before=x.driver.reads;
            skills.handle("setEnabled",j("id",ref.getString("id"),"enabled",false));JSONObject denied=x.invoke("observe",args);assertFalse(denied.toString(),denied.optBoolean("accepted"));assertEquals(before,x.driver.reads);assertEquals(0,x.driver.original.mutableDispatches);
            assertEquals(1,BrowserTaskSourcePort.sourcesForOwner(x.f.tasks,x.f.binding.taskId).length());
        }
    }
}
