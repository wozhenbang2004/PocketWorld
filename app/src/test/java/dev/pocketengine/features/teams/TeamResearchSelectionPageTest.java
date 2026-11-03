package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native owner page/TaskStore review over real workspace bytes. No model network or child read is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TeamResearchSelectionPageTest {
    @Test public void ownerChoosesRealOriginalFileThenReviewsExactVersionAndCanRevoke()throws Exception {
        var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);var dbField=TaskStore.class.getDeclaredField("db");dbField.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)dbField.get(tasks);
        FeatureHost features=new FeatureHost(event->{},tasks,()->false);WorkspaceModule workspace=new WorkspaceModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));features.register(workspace);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);TeamCoordinator coordinator=new TeamCoordinator(new File(app.getFilesDir(),"selected-research-"+UUID.randomUUID()+".json"),(model,messages,tools,control)->{entered.countDown();release.await(8,TimeUnit.SECONDS);return new JSONObject().put("content","原测试只验证主人资料选择，不声明真实模型或业务完成");},event->{});
        TeamMemberEvidenceHost host=new TeamMemberEvidenceHost(features,tasks,source->{throw new AssertionError("No private source selected in this fixture");});
        coordinator.setResearchFactory((call,model,control,progress)->{entered.countDown();release.await(8,TimeUnit.SECONDS);return new JSONObject().put("status","interrupted").put("message","owner page metadata fixture only");});
        coordinator.setBudgetHost(new TaskStoreTeamWriteHost(tasks,new TeamWorkspaceAccess(workspace),changed->{}));
        try {
            String project=workspace.handle("create",new JSONObject().put("name","原项目 "+UUID.randomUUID())).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","notes.txt").put("expectedVersion",0).put("content","准确原文件正文"));
            String run=UUID.randomUUID().toString();tasks.beginExternal("teams","start",run,new JSONObject().put("goal","明确选择只读研究资料"));
            JSONObject definition=new JSONObject().put("id",UUID.randomUUID().toString()).put("name","原研究成员").put("mode","collaborate").put("leaderId","a").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","成员甲").put("role","只读研究").put("runner","model").put("device","phone").put("model","fixture-model")));
            coordinator.start(definition,"明确选择只读研究资料",1,new ModelConfig("https://fixture.invalid/v1","","fixture-model",false,8,10),run,host,null);assertTrue(entered.await(3,TimeUnit.SECONDS));
            try(TeamMemberEvidenceHost.Page page=host.open(coordinator,run,"a")){
                assertThrows(SecurityException.class,page::catalogue);page.setForeground(true);assertTrue(page.catalogue().getBoolean("researchReadAvailable"));assertTrue(page.catalogue().getJSONArray("researchProjects").toString().contains(project));
                JSONObject listing=page.researchProjectFiles(project),file=listing.getJSONArray("files").getJSONObject(0);assertEquals("notes.txt",file.getString("path"));assertEquals(1,file.getInt("version"));assertTrue(listing.getBoolean("selectionOnly"));assertFalse(listing.getBoolean("executionAuthorized"));
                JSONObject input=new JSONObject().put("allowedRecords",new JSONArray()).put("allowedSources",new JSONArray()).put("skills",new JSONArray()).put("resources",new JSONArray()).put("researchReadAccess",new JSONObject().put("webOrigins",new JSONArray().put("https://example.org")).put("projectFiles",new JSONArray().put(new JSONObject().put("projectId",project).put("path","notes.txt"))));
                var review=page.review(input);JSONObject shown=review.view(),pin=shown.getJSONObject("researchReadAccess").getJSONArray("projectFiles").getJSONObject(0);assertEquals(file.getString("sha256"),pin.getString("sha256"));assertEquals(1,pin.getInt("version"));assertFalse(shown.getBoolean("executionAuthorized"));
                page.setForeground(false);assertThrows(SecurityException.class,()->page.approve(review,shown.getString("digest")));page.setForeground(true);var fresh=page.review(input);JSONObject saved=page.approve(fresh,fresh.view().getString("digest"));assertEquals("active",saved.getString("state"));assertEquals(file.getString("sha256"),page.current().getJSONObject("researchReadAccess").getJSONArray("projectFiles").getJSONObject(0).getString("sha256"));assertEquals("revoked",page.revoke(saved.getLong("revision")).getString("state"));
                JSONObject forged=new JSONObject(input.toString());forged.getJSONObject("researchReadAccess").getJSONArray("projectFiles").getJSONObject(0).put("version",1);assertThrows(SecurityException.class,()->page.review(forged));
            }
        }finally{
            release.countDown();coordinator.close();for(String field:new String[]{"coordinator","members"}){var executor=TeamCoordinator.class.getDeclaredField(field);executor.setAccessible(true);assertTrue(((java.util.concurrent.ExecutorService)executor.get(coordinator)).awaitTermination(3,TimeUnit.SECONDS));}features.close();db.close();
        }
    }
    @Test public void classicMemberCannotAdvertiseOrApproveAResearchScopeFromJson()throws Exception {
        var app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");TaskStore tasks=new TaskStore(app);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
        FeatureHost features=new FeatureHost(event->{},tasks,()->false);WorkspaceModule workspace=new WorkspaceModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}));features.register(workspace);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        TeamCoordinator coordinator=new TeamCoordinator(new File(app.getFilesDir(),"classic-materials-"+UUID.randomUUID()+".json"),(model,messages,tools,control)->{entered.countDown();release.await(8,TimeUnit.SECONDS);return new JSONObject().put("content","metadata fixture only");},event->{});
        TeamMemberEvidenceHost host=new TeamMemberEvidenceHost(features,tasks,source->{throw new AssertionError("no private source selected");});
        try{
            String run=UUID.randomUUID().toString();tasks.beginExternal("teams","start",run,new JSONObject().put("goal","原classic范围不升级为研究"));
            JSONObject definition=new JSONObject().put("id",UUID.randomUUID().toString()).put("name","classic").put("mode","collaborate").put("leaderId","a").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","原成员").put("role","classic").put("model","fixture")));
            coordinator.start(definition,"不增加研究权限",1,new ModelConfig("https://fixture.invalid/v1","","fixture",false,8,10),run,host,null);assertTrue(entered.await(3,TimeUnit.SECONDS));
            try(var page=host.open(coordinator,run,"a")){
                page.setForeground(true);JSONObject catalogue=page.catalogue();assertFalse(catalogue.getBoolean("researchReadAvailable"));assertFalse(catalogue.has("researchProjects"));
                assertThrows(SecurityException.class,()->page.researchProjectFiles(UUID.randomUUID().toString()));
                JSONObject forged=new JSONObject().put("allowedRecords",new JSONArray()).put("allowedSources",new JSONArray()).put("skills",new JSONArray()).put("resources",new JSONArray()).put("researchReadAccess",new JSONObject().put("webOrigins",new JSONArray().put("https://example.org")).put("projectFiles",new JSONArray()));
                assertThrows(SecurityException.class,()->page.review(forged));assertEquals(0,page.current().getLong("revision"));
            }
        }finally{
            release.countDown();coordinator.close();for(String name:new String[]{"coordinator","members"}){var f=TeamCoordinator.class.getDeclaredField(name);f.setAccessible(true);assertTrue(((java.util.concurrent.ExecutorService)f.get(coordinator)).awaitTermination(3,TimeUnit.SECONDS));}features.close();db.close();
        }
    }
}
