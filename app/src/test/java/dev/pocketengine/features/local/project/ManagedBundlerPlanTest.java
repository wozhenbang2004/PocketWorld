package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import dev.pocketengine.features.EngineModule;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Formal template/plan contracts only; npm, device and model execution are separate evidence. */
public final class ManagedBundlerPlanTest {
    private static final class Local implements EngineModule {
        final JSONObject source;
        Local()throws Exception {source=new JSONObject().put("checkout",new JSONObject().put("id","checkout").put("projectId","project")).put("files",ProjectTemplates.files("web"));}
        public String id(){return "local";}public JSONObject snapshot(){return new JSONObject();}
        public JSONObject handle(String command,JSONObject input)throws Exception {
            switch(command){
                case "export":return ProjectSources.copy(source);
                case "probe":return new JSONObject().put("termux",new JSONObject().put("installed",false));
                case "listJobs":return new JSONObject().put("termuxJobs",new JSONArray());
                case "project.runtime.describe":return new JSONObject().put("runtimeId","managed_process_v1").put("identity","a".repeat(64)).put("canExecute",true).put("preparationAvailable",true)
                        .put("tools",new JSONArray().put(new JSONObject().put("name","sh").put("probeVerified",true)).put(new JSONObject().put("name","node").put("probeVerified",true)).put(new JSONObject().put("name","npm").put("probeVerified",true)));
                default:throw new IllegalArgumentException(command);
            }
        }
        JSONObject plan(String intent,String digest)throws Exception {return ProjectBuildPlan.create(this,new JSONObject().put("checkoutId","checkout").put("runtimeId","managed_process_v1").put("ecosystem","node").put("intent",intent).put("expectedSourceDigest",digest));}
        String digest()throws Exception{return ProjectSources.describe(source).getString("sourceDigest");}
    }
    @Test public void formalWebProjectHasPinnedRealBundlerAndCompleteArmAndHostLockMetadata()throws Exception {
        Local local=new Local();Map<String,String> files=ProjectSources.files(local.source);assertEquals(12,files.size());assertTrue(files.containsKey("README.md"));
        JSONObject pkg=new JSONObject(files.get("package.json")),lock=new JSONObject(files.get("package-lock.json")),packages=lock.getJSONObject("packages");
        assertEquals("0.25.12",pkg.getJSONObject("devDependencies").getString("esbuild"));assertEquals(3,lock.getInt("lockfileVersion"));assertEquals("0.25.12",packages.getJSONObject("").getJSONObject("devDependencies").getString("esbuild"));
        JSONObject esbuild=packages.getJSONObject("node_modules/esbuild");assertEquals("0.25.12",esbuild.getString("version"));assertTrue(esbuild.getString("integrity").startsWith("sha512-"));
        for(String name:new String[]{"linux-arm64","linux-x64"}){JSONObject platform=packages.getJSONObject("node_modules/@esbuild/"+name);assertEquals("0.25.12",platform.getString("version"));assertTrue(platform.getString("resolved").startsWith("https://registry.npmjs.org/@esbuild/"));assertTrue(platform.getString("integrity").startsWith("sha512-"));}
        assertTrue(files.get("tools/build.mjs").contains("import { build } from 'esbuild'"));assertTrue(files.get("tools/build.mjs").contains("bundle: true"));assertTrue(files.get("tools/build.mjs").contains("publicPath: './assets'"));
        assertTrue(files.get("src/main.js").contains("'./model.js'"));assertTrue(files.get("src/main.js").contains("'./view.js'"));assertTrue(files.get("src/main.js").contains("'../assets/mark.svg'"));assertTrue(files.get("test/model.test.js").contains("toggleItem"));
    }
    @Test public void managedDependencyTestAndBuildPlansBindTheSameSourceAndDoNotRelaxInstallScripts()throws Exception {
        Local local=new Local();String digest=local.digest();JSONObject dependencies=local.plan("dependencies",digest);assertTrue(dependencies.getBoolean("available"));assertFalse(dependencies.getBoolean("automaticChaining"));assertEquals("managed_process_v1",dependencies.getString("runtimeId"));
        JSONObject dependency=ProjectBuildPlan.step(dependencies,"dependencies");assertEquals("project.runtime.run",dependency.getString("localCommand"));assertTrue(dependency.getJSONObject("effects").getBoolean("networkMayBeUsed"));assertEquals("npm",dependency.getJSONObject("payload").getString("executable"));assertEquals("[\"ci\",\"--ignore-scripts\",\"--no-audit\",\"--no-fund\"]",dependency.getJSONObject("payload").getJSONArray("argv").toString());
        for(String intent:new String[]{"test","build"}){JSONObject plan=ProjectBuildPlan.verified(local.plan(intent,digest));assertEquals(digest,plan.getString("sourceDigest"));JSONObject step=ProjectBuildPlan.step(plan,intent);assertEquals("npm",step.getJSONObject("payload").getString("executable"));assertEquals(new JSONArray().put("run").put(intent).toString(),step.getJSONObject("payload").getJSONArray("argv").toString());}
        JSONArray files=local.source.getJSONArray("files");for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getString("path").equals("src/model.js"))files.getJSONObject(i).put("content",files.getJSONObject(i).getString("content")+"\n// original source changed\n");
        assertThrows(SecurityException.class,()->local.plan("build",digest));
    }
    @Test public void ownedServiceIsExplicitManagedRunOnlyAndTemplateStartIsActualLoopbackServer()throws Exception {
        Local local=new Local();JSONObject request=new JSONObject().put("checkoutId","checkout").put("runtimeId","managed_process_v1").put("ecosystem","node").put("intent","run").put("runMode","owned_service").put("expectedSourceDigest",local.digest());
        JSONObject plan=ProjectBuildPlan.verified(ProjectBuildPlan.create(local,request)),step=ProjectBuildPlan.step(plan,"run");assertEquals("owned_service",plan.getString("runMode"));assertEquals("owned_service",step.getJSONObject("payload").getString("runMode"));assertEquals(300000,step.getJSONObject("payload").getInt("timeoutMillis"));assertEquals(new JSONArray().put("run").put("start").toString(),step.getJSONObject("payload").getJSONArray("argv").toString());
        assertThrows(IllegalArgumentException.class,()->ProjectBuildPlan.create(local,new JSONObject(request.toString()).put("intent","build")));assertThrows(IllegalArgumentException.class,()->ProjectBuildPlan.create(local,new JSONObject(request.toString()).put("runtimeId","termux")));
        JSONObject ordinary=local.plan("run",local.digest());assertFalse(ordinary.has("runMode"));assertFalse(ProjectBuildPlan.step(ordinary,"run").getJSONObject("payload").has("runMode"));
        Map<String,String> files=ProjectSources.files(local.source);assertEquals("node tools/serve.mjs",new JSONObject(files.get("package.json")).getJSONObject("scripts").getString("start"));String server=files.get("tools/serve.mjs");assertTrue(server.contains("'127.0.0.1'"));assertTrue(server.contains("randomUUID()"));assertTrue(server.contains("realpath"));assertTrue(server.contains("SIGTERM"));assertFalse(server.contains("'0.0.0.0'"));
    }

}
