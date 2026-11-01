package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.features.EngineModule;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Real template and original plan contract; this does not emulate pip or a phone. */
public final class ManagedPythonProjectPlanTest {
    private static final class Local implements EngineModule {
        final JSONObject source;
        Local()throws Exception {source=new JSONObject().put("checkout",new JSONObject().put("id","checkout").put("projectId","project")).put("files",ProjectTemplates.files("python"));}
        public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject input)throws Exception {
            switch(command){
                case "export":return ProjectSources.copy(source);
                case "probe":return new JSONObject().put("termux",new JSONObject().put("installed",false));
                case "listJobs":return new JSONObject().put("termuxJobs",new JSONArray());
                case "project.runtime.describe":return new JSONObject().put("runtimeId","managed_process_v1").put("identity","a".repeat(64)).put("canExecute",true).put("preparationAvailable",true)
                        .put("tools",new JSONArray().put(new JSONObject().put("name","sh").put("probeVerified",true)).put(new JSONObject().put("name","python3").put("probeVerified",true)));
                default:throw new IllegalArgumentException(command);
            }
        }
        JSONObject plan(String intent)throws Exception {return ProjectBuildPlan.create(this,new JSONObject().put("checkoutId","checkout").put("runtimeId","managed_process_v1").put("ecosystem","python").put("intent",intent).put("expectedSourceDigest",ProjectSources.describe(source).getString("sourceDigest")));}
    }
    @Test public void formalPythonProjectDeclaresExactHashedDependenciesRatherThanFakeInstalledFacts()throws Exception {
        Local local=new Local();Map<String,String> files=ProjectSources.files(local.source);assertEquals(8,files.size());
        assertTrue(files.get("requirements.txt").startsWith("--require-hashes\n"));assertTrue(files.get("requirements.txt").contains("python-dateutil==2.9.0.post0 --hash=sha256:"));assertTrue(files.get("requirements.txt").contains("six==1.17.0 --hash=sha256:"));
        assertTrue(files.get("schedule.py").contains("from dateutil.relativedelta import relativedelta"));assertTrue(files.get("reporting.py").contains("html.escape"));assertTrue(files.get("main.py").contains("write_report"));assertTrue(files.containsKey("test_schedule.py"));assertTrue(files.containsKey("test_reporting.py"));
        JSONObject inspection=ProjectBuildPlan.inspect(local,"checkout","managed_process_v1");assertFalse(inspection.getBoolean("dependencyResolutionComplete"));JSONArray dependencies=inspection.getJSONArray("dependencies");assertEquals(2,dependencies.length());
        assertEquals("python-dateutil",dependencies.getJSONObject(0).getString("name"));assertEquals("==2.9.0.post0",dependencies.getJSONObject(0).getString("spec"));
        for(int i=0;i<dependencies.length();i++){assertTrue(dependencies.getJSONObject(i).getBoolean("hashesDeclared"));assertEquals(2,dependencies.getJSONObject(i).getInt("hashCount"));}
        JSONObject python=inspection.getJSONArray("ecosystems").getJSONObject(0);assertEquals("checkout_venv",python.getString("dependencyEnvironment"));assertTrue(python.getBoolean("hashCheckingDeclared"));
    }
    @Test public void managedPipAndAllPythonCommandsUseTheSameCheckoutVenvAndPreserveExplicitSteps()throws Exception {
        Local local=new Local();JSONObject dependencies=local.plan("dependencies");assertTrue(dependencies.getBoolean("available"));assertFalse(dependencies.getBoolean("automaticChaining"));
        JSONObject step=ProjectBuildPlan.step(dependencies,"dependencies"),payload=step.getJSONObject("payload");assertEquals("project.runtime.run",step.getString("localCommand"));assertTrue(step.getJSONObject("effects").getBoolean("networkMayBeUsed"));assertEquals("sh",payload.getString("executable"));String install=payload.getJSONArray("argv").getString(2);
        assertTrue(install.contains("python3 -m venv .venv\n"));assertTrue(install.contains("export VIRTUAL_ENV=\"$PWD/.venv\""));assertTrue(install.contains("export PATH=\"$VIRTUAL_ENV/bin:$PATH\""));assertTrue(install.contains(".venv/bin/python -m pip install -r requirements.txt"));assertFalse(install.contains("--target"));assertFalse(install.contains("sudo"));
        for(String intent:new String[]{"test","build","run"}){JSONObject plan=ProjectBuildPlan.verified(local.plan(intent));JSONObject command=ProjectBuildPlan.step(plan,intent);String script=command.getJSONObject("payload").getJSONArray("argv").getString(2);assertTrue(script.contains("VIRTUAL_ENV"));assertTrue(script.contains("PATH=\"$VIRTUAL_ENV/bin:$PATH\""));assertTrue(script.contains("exec \"$py\""));assertFalse(plan.getBoolean("automaticChaining"));assertTrue(command.getJSONObject("effects").getBoolean("networkMayBeUsed"));}
        assertTrue(local.plan("build").getJSONArray("warnings").toString().contains("仅编译入口字节码"));
    }
    @Test public void pipOptionsAndContinuationHashesDoNotBecomeInventedPackageNames()throws Exception {
        Local local=new Local();JSONArray files=local.source.getJSONArray("files");for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getString("path").equals("requirements.txt"))files.getJSONObject(i).put("content","--require-hashes\n-r extras.txt\nexample==1.0 \\\n  --hash=sha256:"+"a".repeat(64)+"\n");
        JSONObject inspection=ProjectBuildPlan.inspect(local,"checkout","managed_process_v1");JSONArray dependencies=inspection.getJSONArray("dependencies");assertEquals(1,dependencies.length());assertEquals("example",dependencies.getJSONObject(0).getString("name"));assertEquals("==1.0",dependencies.getJSONObject(0).getString("spec"));assertEquals(1,dependencies.getJSONObject(0).getInt("hashCount"));assertTrue(inspection.getJSONArray("warnings").toString().contains("嵌套来源"));
    }
}
