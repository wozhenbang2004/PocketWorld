package dev.pocketengine.features.teams;

import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.features.FeatureContext;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses real module/JSON persistence with an isolated files-directory context, no Android lifecycle claim. */
public class TeamsDefinitionTest {
    private TeamsModule module()throws Exception {
        File files=Files.createTempDirectory("teams-definition-").toFile();Context context=new ContextWrapper(null){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
        return new TeamsModule(new FeatureContext(context,null,null,e->{}));
    }
    private JSONObject definition()throws Exception{return new JSONObject().put("name","分工组").put("mode","collaborate").put("leaderId","a").put("members",new JSONArray().put(new JSONObject().put("id","a").put("name","甲").put("role","取证").put("model","fixture-model")).put(new JSONObject().put("id","b").put("name","乙").put("role","核验")));}
    private JSONObject plan(JSONObject definition)throws Exception{return new JSONObject().put("teamId",definition.getString("id")).put("expectedDefinition",definition.toString()).put("assignments",new JSONArray().put(new JSONObject().put("memberId","a").put("assignment","研究事实").put("acceptance","有来源").put("dependsOn",new JSONArray())).put(new JSONObject().put("memberId","b").put("assignment","核对事实").put("acceptance","").put("dependsOn",new JSONArray().put("a"))));}
    @Test public void nativePlanCasPreservesRunnerModelAndLegacyEditorOmissions()throws Exception {
        try(TeamsModule module=module()){
            JSONObject original=module.handle("save",definition()),request=plan(original),saved=module.handle("savePlan",request);assertEquals("fixture-model",saved.getJSONArray("members").getJSONObject(0).getString("model"));assertEquals("a",saved.getJSONArray("members").getJSONObject(1).getJSONArray("dependsOn").getString(0));
            assertThrows(IllegalStateException.class,()->module.handle("savePlan",request));
            JSONObject oldEditor=definition().put("id",saved.getString("id"));JSONObject resaved=module.handle("save",oldEditor);assertEquals("研究事实",resaved.getJSONArray("members").getJSONObject(0).getString("assignment"));assertEquals("a",resaved.getJSONArray("members").getJSONObject(1).getJSONArray("dependsOn").getString(0));assertEquals(0,module.snapshot().getJSONArray("runs").length());
        }
    }
    @Test public void cycleAndForgedRunnerFieldFailBeforeDefinitionChanges()throws Exception {
        try(TeamsModule module=module()){
            JSONObject original=module.handle("save",definition()),cyclic=plan(original);cyclic.getJSONArray("assignments").getJSONObject(0).put("dependsOn",new JSONArray().put("b"));assertThrows(IllegalArgumentException.class,()->module.handle("savePlan",cyclic));
            JSONObject forged=plan(original);forged.getJSONArray("assignments").getJSONObject(0).put("runner","codex_app_server");assertThrows(IllegalArgumentException.class,()->module.handle("savePlan",forged));assertEquals(original.toString(),module.snapshot().getJSONArray("teams").getJSONObject(0).toString());
            JSONArray capabilities=module.snapshot().getJSONArray("runnerCapabilities");for(int i=0;i<capabilities.length();i++)if(capabilities.getJSONObject(i).getString("id").equals("claude"))assertFalse(capabilities.getJSONObject(i).getBoolean("available"));
        }
    }
    @Test public void reviewedEditRemoveAndStartCannotUseAReplacedDefinition()throws Exception {
        try(TeamsModule module=module()){
            JSONObject first=module.handle("save",definition());String id=first.getString("id"),reviewed=first.toString();
            JSONObject updated=module.handle("save",definition().put("id",id).put("name","新的职责配置").put("expectedDefinition",reviewed));
            assertThrows(SecurityException.class,()->module.handle("save",definition().put("id",id).put("expectedDefinition",reviewed)));
            assertThrows(SecurityException.class,()->module.handle("remove",new JSONObject().put("id",id).put("expectedDefinition",reviewed)));
            assertThrows(SecurityException.class,()->module.handle("start",new JSONObject().put("teamId",id).put("goal","旧页面的目标").put("rounds",1).put("expectedDefinition",reviewed)));
            assertEquals(updated.toString(),module.snapshot().getJSONArray("teams").getJSONObject(0).toString());assertEquals(0,module.snapshot().getJSONArray("runs").length());
            assertTrue(module.handle("remove",new JSONObject().put("id",id).put("expectedDefinition",updated.toString())).getBoolean("removed"));
            assertThrows(SecurityException.class,()->module.handle("remove",new JSONObject().put("id",id).put("expectedDefinition",updated.toString())));
        }
    }
    @Test public void reviewedCreationCannotOverwriteAnAlreadySavedTeam()throws Exception {
        try(TeamsModule module=module()){
            String id=java.util.UUID.randomUUID().toString();JSONObject draft=definition().put("id",id).put("expectedDefinition","");
            JSONObject saved=module.handle("save",draft);assertThrows(SecurityException.class,()->module.handle("save",draft));
            assertEquals(saved.toString(),module.snapshot().getJSONArray("teams").getJSONObject(0).toString());
        }
    }
}
