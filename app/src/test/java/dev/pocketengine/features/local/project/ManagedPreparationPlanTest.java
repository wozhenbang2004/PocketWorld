package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.features.EngineModule;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Production planning/approval payload checks; no Android runtime is emulated. */
public final class ManagedPreparationPlanTest {
    static final class Local implements EngineModule {
        Local() throws Exception {}
        boolean downloadable=true,ready=false;
        final JSONObject source=new JSONObject().put("checkout",new JSONObject().put("id","checkout").put("projectId","project"))
                .put("files",new JSONArray().put(new JSONObject().put("path","main.py").put("content","import sys\nprint(sys.prefix)\n")).put(new JSONObject().put("path","requirements.txt").put("content","example==1.0\n")));
        public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject input) throws Exception {
            switch(command){
                case "export":return new JSONObject(source.toString());
                case "probe":return new JSONObject().put("termux",new JSONObject().put("installed",true).put("permissionGranted",true).put("enabled",true));
                case "listJobs":return new JSONObject().put("termuxJobs",new JSONArray());
                case "project.runtime.describe":return new JSONObject().put("runtimeId","managed_process_v1").put("identity","a".repeat(64)).put("canExecute",ready).put("preparationAvailable",downloadable)
                        .put("preparationEffects",new JSONObject().put("catalogIdentity","b".repeat(64)).put("archives",new JSONArray().put(new JSONObject().put("url","https://nodejs.org/exact").put("archiveBytes",123).put("archiveSha256","c".repeat(64)))))
                        .put("tools",new JSONArray().put(new JSONObject().put("name","sh").put("probeVerified",ready)).put(new JSONObject().put("name","python3").put("probeVerified",ready)));
                default:throw new IllegalArgumentException(command);
            }
        }
        JSONObject plan(String intent,String runtime) throws Exception {return ProjectBuildPlan.create(this,new JSONObject().put("checkoutId","checkout").put("expectedSourceDigest",ProjectSources.describe(source).getString("sourceDigest")).put("runtimeId",runtime).put("intent",intent).put("ecosystem","python"));}
    }
    @Test public void prepareBeforeCanExecuteStillHasExactApprovedDownloadIdentity() throws Exception {
        Local local=new Local();JSONObject plan=local.plan("prepare","managed_process_v1");assertTrue(plan.getBoolean("available"));JSONObject step=ProjectBuildPlan.step(plan,"prepare");
        assertEquals("project.runtime.prepare",step.getString("localCommand"));assertTrue(step.getJSONObject("effects").getBoolean("networkMayBeUsed"));assertEquals("b".repeat(64),step.getJSONObject("payload").getJSONObject("preparationCatalog").getString("catalogIdentity"));
        ProjectBuildPlan.verified(plan);plan.getJSONArray("steps").getJSONObject(0).getJSONObject("payload").getJSONObject("preparationCatalog").put("catalogIdentity","d".repeat(64));assertThrows(SecurityException.class,()->ProjectBuildPlan.verified(plan));
    }
    @Test public void unavailableNativeCatalogDoesNotFallBackToInstalledTermux() throws Exception {
        Local local=new Local();local.downloadable=false;JSONObject plan=local.plan("prepare","managed_process_v1");assertFalse(plan.getBoolean("available"));assertEquals("managed_process_v1",plan.getString("runtimeId"));assertEquals("project.runtime.prepare",ProjectBuildPlan.step(plan,"prepare").getString("localCommand"));
    }
    @Test public void managedPythonUsesSameCheckoutVenvWhileTermuxCompatibilityIsUnchanged() throws Exception {
        Local local=new Local();local.ready=true;JSONObject dependencies=ProjectBuildPlan.step(local.plan("dependencies","managed_process_v1"),"dependencies").getJSONObject("payload");assertEquals("sh",dependencies.getString("executable"));String script=dependencies.getJSONArray("argv").getString(2);assertTrue(script.contains("python3 -m venv .venv\n"));assertTrue(script.contains(".venv/bin/python -m pip install"));assertTrue(script.contains("export VIRTUAL_ENV=\"$PWD/.venv\""));assertTrue(script.contains("export PATH=\"$VIRTUAL_ENV/bin:$PATH\""));assertFalse(script.contains("--target"));
        JSONObject run=ProjectBuildPlan.step(local.plan("run","managed_process_v1"),"run").getJSONObject("payload");assertTrue(run.getJSONArray("argv").getString(2).contains("exec \"$py\" './main.py'"));
        JSONObject test=ProjectBuildPlan.step(local.plan("test","managed_process_v1"),"test").getJSONObject("payload");assertTrue(test.getJSONArray("argv").getString(2).contains("'-m' 'unittest' 'discover' '.'"));
        JSONObject compatibility=ProjectBuildPlan.step(local.plan("dependencies","termux"),"dependencies").getJSONObject("payload");assertEquals("python3",compatibility.getString("executable"));assertTrue(compatibility.getJSONArray("argv").toString().contains("--target"));
    }

}
