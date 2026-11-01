package dev.pocketengine.features.local.project;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual original LocalModule/artifact catalog. Optional host Node proves export independence, not ARM. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectPackageJourneyTest {
    private static final byte[] BINARY={0,1,2,-1,4,9,0};
    @Test public void actualFilesFreezeColdReopenExportReimportEmptyCheckoutLockedRebuildAndContinueWithoutOldAuthority()throws Exception {
        Context android=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(android,null,null,event->{});WorkspaceModule workspace=new WorkspaceModule(context);
        File logs=Files.createTempDirectory("project-package-journey-").toFile();String originalCheckout,project;JSONObject sourceArtifact,outputArtifact;byte[] exported;
        try {
            try(LocalModule local=new LocalModule(context,workspace)){
                JSONObject created=local.createProjectForOwner("完整项目包旅程","web");originalCheckout=created.getString("id");project=created.getString("projectId");File active=files(android,originalCheckout);
                write(local,originalCheckout,"src/build/amount.js","export function amount(){return (20+20).toFixed(2)}\n",true);
                String main=local.handle("readFile",input(originalCheckout,"src/main.js")).getString("content");write(local,originalCheckout,"src/main.js","import { amount } from './build/amount.js';\nglobalThis.packageAmount=amount();\nglobalThis.packageCanary='original-fixed-package';\n"+main,false);
                ProjectPackageArchiveTest.bytes(active,"assets/opaque.bin",BINARY);ProjectPackageArchiveTest.text(active,".env","SECRET_DO_NOT_EXPORT=fixture");ProjectPackageArchiveTest.text(active,"config/accounts.json","private fixture");assertTrue(new File(active,"empty/kept").mkdirs());
                assertTrue(local.handle("changes",new JSONObject().put("checkoutId",originalCheckout)).getJSONArray("files").toString().contains("\"binary\":true"));
                try{local.handle("readFile",input(originalCheckout,"assets/opaque.bin"));fail("Binary opened as editable text");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("UTF-8"));}
                assertEquals("40.00",node(active,logs,"business-original","--input-type=module","-e","import {amount} from './src/build/amount.js'; process.stdout.write(amount());").trim());
                npm(active,logs,"original-dependencies","ci","--ignore-scripts","--offline","--no-audit","--no-fund");npm(active,logs,"original-tests","test");npm(active,logs,"original-build","run","build");
                sourceArtifact=freeze(local,originalCheckout,"source_project","");outputArtifact=freeze(local,originalCheckout,"web_output","dist/index.html");
                JSONObject packageMeta=local.handle("project.package.get",ref(sourceArtifact));assertFalse(packageMeta.getBoolean("businessVerified"));assertFalse(packageMeta.getJSONObject("manifest").getBoolean("atomicSourceFence"));assertTrue(packageMeta.getJSONObject("manifest").getJSONArray("omitted").toString().contains("credential_or_private_configuration"));
                JSONObject previewMeta=local.handle("project.package.preview",ref(outputArtifact).put("entryPath","dist/index.html"));var oldPreview=local.projectPreviewForOwner(previewMeta.getString("previewId"),originalCheckout);assertEquals(4,oldPreview.manifest().length());assertEquals(outputArtifact.getString("id"),oldPreview.artifactId);
                write(local,originalCheckout,"src/build/amount.js","export function amount(){return (20+25).toFixed(2)}\n",false);assertEquals("45.00",node(active,logs,"business-original-changed","--input-type=module","-e","import {amount} from './src/build/amount.js'; process.stdout.write(amount());").trim());
                ByteArrayOutputStream destination=new ByteArrayOutputStream();local.exportProjectPackageForOwner(sourceArtifact.getString("id"),sourceArtifact.getString("sha256"),destination);exported=destination.toByteArray();assertEquals(sourceArtifact.getString("sha256"),ProjectSources.sha256(exported));
                assertFalse(sourceArtifact.has("taskId"));assertFalse(sourceArtifact.has("runId"));assertFalse(sourceArtifact.has("grants"));
            }
            // New canonical repository/module instances: no active preview object or old process is reused.
            WorkspaceModule coldWorkspace=new WorkspaceModule(context);
            try(LocalModule cold=new LocalModule(context,coldWorkspace)){
                cold.handle("project.package.get",ref(outputArtifact));JSONObject pm=cold.handle("project.package.preview",ref(outputArtifact).put("entryPath","dist/index.html"));ProjectPreviewBundle.Verified reopened=cold.projectPreviewForOwner(pm.getString("previewId"),originalCheckout);assertEquals(4,reopened.manifest().length());assertEquals(outputArtifact.getString("sha256"),reopened.artifactSha256);
                String selection=UUID.randomUUID().toString();JSONObject staged=cold.stageProjectPackageForOwner(new ByteArrayInputStream(exported),selection);JSONObject review=cold.planProjectPackageImportForOwner(selection,staged.getString("sha256"),"导入后的独立项目",null);JSONObject plan=review.getJSONObject("plan");JSONObject imported=cold.importProjectPackageForOwner(plan);String checkout=imported.getString("id");File active=files(android,checkout);
                assertNotEquals(originalCheckout,checkout);assertNotEquals(project,imported.getString("projectId"));assertFalse(imported.getBoolean("taskOrGrantRestored"));assertFalse(imported.getJSONObject("sourceImport").getBoolean("executionAuthorityRestored"));assertTrue(imported.getBoolean("originalCheckoutUnchanged"));assertFalse(new File(active,"node_modules").exists());assertFalse(new File(active,"dist").exists());assertFalse(new File(active,".env").exists());assertFalse(new File(active,"config/accounts.json").exists());assertTrue(new File(active,"empty/kept").isDirectory());assertArrayEquals(BINARY,Files.readAllBytes(new File(active,"assets/opaque.bin").toPath()));
                assertEquals(checkout,cold.importProjectPackageForOwner(plan).getString("id"));assertTrue(cold.findProjectPackageImportForOwner(plan).getBoolean("found"));assertEquals(checkout,cold.openProjectForOwner(imported.getString("projectId")).getString("id"));assertEquals(0,coldWorkspace.handle("export",new JSONObject().put("projectId",imported.getString("projectId"))).getJSONArray("files").length());
                assertEquals("40.00",node(active,logs,"business-imported","--input-type=module","-e","import {amount} from './src/build/amount.js'; process.stdout.write(amount());").trim());
                npm(active,logs,"imported-dependencies","ci","--ignore-scripts","--offline","--no-audit","--no-fund");npm(active,logs,"imported-tests","test");npm(active,logs,"imported-build","run","build");verifyOwnServer(active,logs,"new-imported-server","40.00");
                write(cold,checkout,"src/build/amount.js","export function amount(){return (20+25).toFixed(2)}\n",false);assertEquals("45.00",node(active,logs,"business-imported-continued","--input-type=module","-e","import {amount} from './src/build/amount.js'; process.stdout.write(amount());").trim());npm(active,logs,"continued-build","run","build");verifyOwnServer(active,logs,"continued-imported-server","45.00");
                JSONObject later=freeze(cold,checkout,"web_output","dist/index.html");assertNotEquals(outputArtifact.getString("sha256"),later.getString("sha256"));assertEquals("45.00",node(files(android,originalCheckout),logs,"original-still-changed","--input-type=module","-e","import {amount} from './src/build/amount.js'; process.stdout.write(amount());").trim());assertEquals(outputArtifact.getString("sha256"),cold.handle("project.package.get",ref(outputArtifact)).getString("sha256"));
                cold.discardStagedProjectPackageForOwner(selection,staged.getString("sha256"));assertEquals(0,cold.listStagedProjectPackagesForOwner().length());assertTrue(files(android,checkout).isDirectory());
                System.out.println("PROJECT_PACKAGE_JOURNEY original="+originalCheckout+"; immutable="+sourceArtifact.getString("id")+"; new_empty_checkout="+checkout+"; business 40.00 -> explicit CAS 45.00; real official cached npm ci/test/build and new loopback HTTP verified; old fixed four resources remain cold-readable; no old task/grant restored; host, not Android ARM/provider/visual");
            }
        }finally{System.out.println("PROJECT_PACKAGE_FIXTURE_LOGS="+logs.getAbsolutePath());}
    }
    @Test public void sourceSelectionChangeAndExistingProjectCasRejectWithoutOverwritingOrLosingOriginalPackage()throws Exception {
        Context android=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(android,null,null,event->{});WorkspaceModule workspace=new WorkspaceModule(context);
        try(LocalModule local=new LocalModule(context,workspace)){
            String project=workspace.handle("create",new JSONObject().put("name","导入冲突")).getString("id");workspace.handle("write",new JSONObject().put("projectId",project).put("path","index.html").put("content","original").put("expectedVersion",0));String checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
            JSONObject before=local.handle("project.package.plan",new JSONObject().put("checkoutId",checkout).put("mode","source_project"));write(local,checkout,"index.html","changed",false);
            try{local.handle("project.package.freeze",freezeInput(before,"source_project","").put("clientSnapshotId",UUID.randomUUID().toString()));fail("Stale selection saved new bytes");}catch(SecurityException expected){}
            JSONObject artifact=freeze(local,checkout,"source_project","");ByteArrayOutputStream out=new ByteArrayOutputStream();local.exportProjectPackageForOwner(artifact.getString("id"),artifact.getString("sha256"),out);String selection=UUID.randomUUID().toString();JSONObject staged=local.stageProjectPackageForOwner(new ByteArrayInputStream(out.toByteArray()),selection);
            JSONObject plan=local.planProjectPackageImportForOwner(selection,staged.getString("sha256"),"原项目的新副本",checkout).getJSONObject("plan");write(local,checkout,"index.html","target changed again",false);
            try{local.importProjectPackageForOwner(plan);fail("Stale target CAS imported over original checkout");}catch(SecurityException expected){}
            assertEquals("target changed again",local.handle("readFile",input(checkout,"index.html")).getString("content"));assertFalse(local.findProjectPackageImportForOwner(plan).getBoolean("found"));assertEquals(artifact.getString("sha256"),local.handle("project.package.get",ref(artifact)).getString("sha256"));
            JSONObject refreshed=local.planProjectPackageImportForOwner(selection,staged.getString("sha256"),"原项目的新副本",checkout).getJSONObject("plan");JSONObject imported=local.importProjectPackageForOwner(refreshed);assertEquals(project,imported.getString("projectId"));assertNotEquals(checkout,imported.getString("id"));assertEquals("changed",local.handle("readFile",input(imported.getString("id"),"index.html")).getString("content"));assertEquals("target changed again",local.handle("readFile",input(checkout,"index.html")).getString("content"));assertEquals(1,workspace.snapshot().getJSONArray("projects").length());
            System.out.println("PROJECT_PACKAGE_CAS stale source rejected; stale target rejected without publication; original cache and artifact retained; explicit re-review creates same-project NEW checkout, never changes old task/checkout");
        }
    }
    private static JSONObject input(String checkout,String path)throws Exception{return new JSONObject().put("checkoutId",checkout).put("path",path);}
    private static void write(LocalModule local,String checkout,String path,String value,boolean create)throws Exception{String hash=create?"absent":local.handle("readFile",input(checkout,path)).getString("sha256");local.handle("writeFile",input(checkout,path).put("content",value).put("expectedHash",hash));}
    private static JSONObject ref(JSONObject artifact)throws Exception{return new JSONObject().put("artifactId",artifact.getString("id")).put("sha256",artifact.getString("sha256"));}
    private static JSONObject freeze(LocalModule local,String checkout,String mode,String entry)throws Exception{JSONObject plan=local.handle("project.package.plan",new JSONObject().put("checkoutId",checkout).put("mode",mode).put("entryPath",entry));return local.handle("project.package.freeze",freezeInput(plan,mode,entry).put("clientSnapshotId",UUID.randomUUID().toString()));}
    private static JSONObject freezeInput(JSONObject plan,String mode,String entry)throws Exception{return new JSONObject().put("checkoutId",plan.getString("checkoutId")).put("expectedSourceDigest",plan.getString("sourceDigest")).put("expectedManifestIdentity",plan.getString("manifestIdentity")).put("mode",mode).put("entryPath",entry);}
    private static File files(Context android,String checkout){return new File(android.getFilesDir(),"local-development/checkouts/"+checkout+"/files");}
    private static String node(File cwd,File logs,String label,String...args)throws Exception{return command(cwd,logs,label,args);}
    private static void npm(File cwd,File logs,String label,String...args)throws Exception{String[] actual=new String[args.length+1];actual[0]=System.getProperty("a26.npmCli");System.arraycopy(args,0,actual,1,args.length);command(cwd,logs,label,actual);}
    private static String command(File cwd,File logs,String label,String...args)throws Exception{
        String node=System.getProperty("a26.node");assertNotNull("Explicit authorized host Node path required",node);File log=new File(logs,label+".log");var argv=new java.util.ArrayList<String>();argv.add(node);argv.addAll(Arrays.asList(args));ProcessBuilder builder=new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true).redirectOutput(log);builder.environment().put("PATH",new File(node).getParent()+File.pathSeparator+builder.environment().getOrDefault("PATH",""));builder.environment().put("npm_config_cache",System.getProperty("a26.npmCache"));builder.environment().put("NODE_OPTIONS","--max-old-space-size=96");Process process=builder.start();try{assertTrue(label+" timed out",process.waitFor(60,TimeUnit.SECONDS));String output=Files.readString(log.toPath());assertEquals(label+" failed: "+output,0,process.exitValue());return output;}finally{if(process.isAlive()){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(2,TimeUnit.SECONDS));}}}
    }
    private static void verifyOwnServer(File cwd,File logs,String label,String amount)throws Exception{
        File log=new File(logs,label+".log");Process process=new ProcessBuilder(System.getProperty("a26.node"),"tools/serve.mjs").directory(cwd).redirectErrorStream(true).redirectOutput(log).start();try{
            String claimed=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<deadline&&process.isAlive()){String line=log.isFile()?Files.readString(log.toPath()):"";var matcher=java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:[0-9]+/__project/[a-f0-9-]+/").matcher(line);if(matcher.find()){claimed=matcher.group();break;}Thread.sleep(20);}assertNotNull("No actual loopback startup line: "+(log.isFile()?Files.readString(log.toPath()):""),claimed);URI base=URI.create(claimed);File dist=new File(cwd,"dist");int count=0;
            try(var paths=Files.walk(dist.toPath())){for(var path:paths.filter(Files::isRegularFile).toList()){String relative=dist.toPath().relativize(path).toString().replace(File.separatorChar,'/');HttpURLConnection connection=(HttpURLConnection)base.resolve(relative).toURL().openConnection(Proxy.NO_PROXY);connection.setConnectTimeout(2000);connection.setReadTimeout(2000);try{assertEquals(200,connection.getResponseCode());try(var input=connection.getInputStream()){assertArrayEquals("HTTP bytes differ from this NEW cwd: "+relative,Files.readAllBytes(path),input.readAllBytes());}}finally{connection.disconnect();}count++;}}
            assertEquals(4,count);try(var assets=Files.list(new File(dist,"assets").toPath())){assertTrue("Rebuilt served bundle lacks the actual imported module/canary",assets.filter(p->p.toString().endsWith(".js")).map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).anyMatch(text->text.contains("packageAmount")&&text.contains("original-fixed-package")));}
            assertEquals(amount,node(cwd,logs,label+"-business","--input-type=module","-e","import {amount} from './src/build/amount.js'; process.stdout.write(amount());").trim());System.out.println("PROJECT_PACKAGE_HTTP_VERIFIED "+label+" resources="+count+" actualBusiness="+amount+" cwd="+cwd);
        }finally{process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));}assertFalse(process.isAlive());}
    }
}
