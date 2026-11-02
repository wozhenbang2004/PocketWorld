package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original module/definitions file. Catalog operations start no team or inference. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamRoleModuleNativeTest {
    private TeamsModule module()throws Exception {
        var context=RuntimeEnvironment.getApplication();
        return new TeamsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}));
    }
    private JSONObject role(String id)throws Exception{return new JSONObject().put("id",id).put("name","研究角色").put("description","核对证据").put("instruction","只读取已准入材料").put("acceptance","明确来源和未知").put("runner","model").put("readToolNeeds",new JSONArray()).put("inputContract","原任务材料").put("outputContract","结论和依据");}
    private JSONObject catalog(TeamsModule module)throws Exception{return module.handle("roleCatalog",new JSONObject());}
    private JSONObject saveRole(TeamsModule module,String id)throws Exception{return module.handle("saveRole",new JSONObject().put("expectedCatalogRevision",catalog(module).getLong("revision")).put("role",role(id))).getJSONObject("value");}
    private JSONObject team()throws Exception{return new JSONObject().put("name","普通团队").put("mode","collaborate").put("leaderId","researcher").put("members",new JSONArray().put(new JSONObject().put("id","researcher").put("name","研究者").put("role","核查").put("runner","model").put("device","phone").put("assignment","只读原任务材料").put("dependsOn",new JSONArray())));}
    @Test public void ordinarySavePlanAndRemovePreserveTheSamePinnedCatalogAcrossColdReopen()throws Exception {
        String id="role_"+UUID.randomUUID().toString().replace("-","");TeamsModule module=module();
        JSONObject saved;
        try{saved=saveRole(module,id);JSONObject definition=module.handle("save",team());String team=definition.getString("id");
            JSONArray assignments=new JSONArray().put(new JSONObject().put("memberId","researcher").put("assignment","补充的只读任务").put("acceptance","准确来源").put("dependsOn",new JSONArray()));
            module.handle("savePlan",new JSONObject().put("teamId",team).put("expectedDefinition",definition.toString()).put("assignments",assignments));
            assertEquals(saved.getString("hash"),catalog(module).getJSONArray("roles").getJSONObject(0).getString("hash"));
            module.handle("remove",new JSONObject().put("id",team));assertEquals(0,module.snapshot().getJSONArray("teams").length());
            assertEquals(0,module.snapshot().getJSONArray("runs").length());
        }finally{module.close();}
        module=module();try{assertEquals(saved.getString("hash"),catalog(module).getJSONArray("roles").getJSONObject(0).getString("hash"));assertFalse(catalog(module).getBoolean("executionAuthorized"));}finally{module.close();}
    }
    @Test public void staleAndForgedCatalogMutationsLeaveTheActualDefinitionsFileUnchanged()throws Exception {
        TeamsModule module=module();try{saveRole(module,"research");String before=catalog(module).toString();
            assertThrows(IllegalStateException.class,()->module.handle("saveRole",new JSONObject().put("expectedCatalogRevision",0).put("role",role("stale"))));
            assertThrows(SecurityException.class,()->module.handle("saveRole",new JSONObject().put("expectedCatalogRevision",1).put("role",role("forged").put("budget",1000))));
            assertEquals(before,catalog(module).toString());assertEquals(0,module.snapshot().getJSONArray("runs").length());
        }finally{module.close();}
    }
    @Test public void savedTemplateIsExactVersionDataAndUnprobedExternalRoleRemainsUnavailable()throws Exception {
        TeamsModule module=module();try{JSONObject role=module.handle("saveRole",new JSONObject().put("expectedCatalogRevision",0).put("role",role("external").put("runner","claude"))).getJSONObject("value");
            assertFalse(catalog(module).getJSONArray("roles").getJSONObject(0).getJSONObject("availability").getBoolean("available"));
            JSONObject draft=new JSONObject().put("id","review").put("name","受限模板").put("mode","collaborate").put("leaderId","reviewer").put("members",new JSONArray().put(new JSONObject().put("memberId","reviewer").put("name","审查者").put("roleId",role.getString("id")).put("roleVersion",role.getInt("version")).put("roleHash",role.getString("hash")).put("dependsOn",new JSONArray())));
            JSONObject template=module.handle("saveTemplate",new JSONObject().put("expectedCatalogRevision",1).put("template",draft)).getJSONObject("value");
            JSONObject read=module.handle("getTemplate",new JSONObject().put("id","review").put("version",template.getInt("version")).put("hash",template.getString("hash")));
            assertFalse(read.getBoolean("executionAuthorized"));assertEquals(role.getString("hash"),read.getJSONArray("members").getJSONObject(0).getJSONObject("role").getString("hash"));
            assertThrows(IllegalStateException.class,()->module.handle("getTemplate",new JSONObject().put("id","review").put("version",2).put("hash",template.getString("hash"))));
            assertEquals(0,module.snapshot().getJSONArray("runs").length());assertEquals(0,module.snapshot().getJSONArray("teams").length());
        }finally{module.close();}
    }
}
