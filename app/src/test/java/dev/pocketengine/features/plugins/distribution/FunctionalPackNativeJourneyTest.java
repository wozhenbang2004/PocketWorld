package dev.pocketengine.features.plugins.distribution;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.plugins.catalog.ExecutablePluginCatalog;
import dev.pocketengine.features.plugins.catalog.PluginCatalog;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Original SDK35 TaskStore/three installed component authorities/NativePluginHost, real ZIP/source/file IO.
 * These are installation/ownership journeys, not live HTTP/provider/JS-runtime execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class FunctionalPackNativeJourneyTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks=new TaskStore(app);
        final FeatureContext context=new FeatureContext(app,null,new SecretVault(app),event->{});final PluginsModule plugins=new PluginsModule(context);final SkillsModule skills=new SkillsModule(context);
        final PluginCatalog configured=new PluginCatalog(plugins,new File(app.getFilesDir(),"plugin-catalog-sources.json"),35);
        final File jsFile=new File(app.getFilesDir(),"executable-plugin-catalog.json");final ExecutablePluginCatalog js=new ExecutablePluginCatalog(configured,jsFile);
        final AtomicReference<java.util.function.Consumer<String>> during=new AtomicReference<>();
        final NativePluginHost host=new NativePluginHost(app,plugins,tasks,(binding,control)->tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,control),id->{var callback=during.get();if(callback!=null)callback.accept(id);});
        Fixture()throws Exception{host.mountFunctionalPacks(skills);}
        FunctionalPackHost.Page page(){var page=host.newFunctionalPackPage();page.setForeground(true);return page;}
        JSONObject install(byte[] bytes)throws Exception {try(var page=page()){var review=page.inspect(new ByteArrayInputStream(bytes));assertTrue(review.view().toString(2),review.ready);return page.submit(review,review.digest);}}
        JSONObject pack(String id)throws Exception{try(var page=page()){JSONArray rows=page.snapshot().getJSONArray("packs");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("id")))return row;}throw new AssertionError("missing pack");}}
        @Override public void close(){host.close();js.close();configured.close();plugins.close();}
    }
    private static byte[] zip(String version)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream archive=new ZipOutputStream(out,StandardCharsets.UTF_8)){ZipEntry manifest=new ZipEntry("release-notes/SKILL.md");manifest.setTime(1_600_000_000_000L);archive.putNextEntry(manifest);archive.write(("---\nname: release-notes\ndescription: 整理公开版本记录并比较变化\nlicense: MIT\nmetadata:\n  version: '"+version+"'\n---\n先使用已授权的 plugin:release-data:fetch 读取公开版本记录，再用 js:release-transform:count 做纯文本计算。\r\n这里只是 instruction recipe；不自动运行，不授予工具权限。\n").getBytes(StandardCharsets.UTF_8));archive.closeEntry();ZipEntry reference=new ZipEntry("release-notes/references/source.txt");reference.setTime(1_600_000_000_000L);archive.putNextEntry(reference);archive.write(("准确资源内容 "+version+"\r\n").getBytes(StandardCharsets.UTF_8));archive.closeEntry();}return out.toByteArray();}
    private static byte[] packageBytes(String version,boolean mcp)throws Exception {
        JSONObject tool=new JSONObject().put("name","fetch").put("kind",mcp?"mcp":"http").put("endpoint","http://127.0.0.1:1/releases").put("effect","read");
        if(mcp)tool.put("protocolVersion","2026-07-28").put("descriptorDigest","a".repeat(64)).put("serverDigest","b".repeat(64));
        byte[] declaration=SdkPackageCodec.declaration(new JSONObject().put("id","release-data").put("name","公开版本读取").put("version",version).put("tools",new JSONArray().put(tool)),new JSONObject().put("label","本机生命周期fixture作者"));
        JsPluginPackage js=JsPluginPackage.author(new ByteArrayInputStream(("\ufeff// 准确原源码 "+version+"\r\ndefineExtension({tools:[{name:'count',description:'纯文本统计',parameters:{type:'object',properties:{text:{type:'string'}},required:['text']},execute(args){return {value:{characters:args.text.length}};}}]});\n").getBytes(StandardCharsets.UTF_8)),"release-transform","版本记录计算",version,new RunControl());
        JSONObject metadata=new JSONObject().put("id","release-kit").put("name","版本记录整理功能包").put("version",version).put("license","MIT").put("source",new JSONObject().put("label","原始fixture准确组件，非真实服务执行")).put("recipe",new JSONObject().put("kind","instruction").put("entry","method"));
        JSONObject method=new JSONObject().put("key","method").put("kind","skill_zip").put("requires",new JSONArray().put("data").put("transform")).put("requiredCapabilities",new JSONArray().put("plugin:release-data:fetch").put("js:release-transform:count"));
        JSONObject data=new JSONObject().put("key","data").put("kind","declaration").put("requires",new JSONArray()).put("requiredCapabilities",new JSONArray());JSONObject transform=new JSONObject().put("key","transform").put("kind","executable_js").put("requires",new JSONArray()).put("requiredCapabilities",new JSONArray());
        return FunctionalPackage.author(metadata,List.of(method,data,transform),List.of(zip(version),declaration,js.packageBytes()),new RunControl());
    }
    @Test public void actualThreeAuthorityInstallUpdateExactRawRestoreAndOwnedUninstallKeepOriginalReceipts()throws Exception {
        try(Fixture f=new Fixture()) {
            byte[] one=packageBytes("one",false),two=packageBytes("two",false);FunctionalPackage parsed=FunctionalPackage.read(new ByteArrayInputStream(one),new RunControl());assertArrayEquals(one,parsed.exactBytes());assertEquals("data",parsed.components().get(0).key);assertArrayEquals(zip("one"),parsed.components().get(2).exactBytes());
            JSONObject first=f.install(one);assertEquals("completed",first.getString("status"));JSONObject original=f.pack("release-kit");assertEquals(3,original.getJSONArray("components").length());assertEquals(3,f.tasks.get(first.getString("taskId"),false).getJSONArray("operations").length());
            JSONObject skill=f.skills.snapshot().getJSONArray("skills").getJSONObject(0),decl=f.plugins.snapshot().getJSONArray("plugins").getJSONObject(0),js=f.js.snapshot().getJSONArray("plugins").getJSONObject(0);assertFalse(skill.getBoolean("enabled"));assertFalse(decl.getBoolean("enabled"));assertEquals(0,decl.getJSONArray("grants").length());assertFalse(js.getBoolean("approved"));assertFalse(js.getBoolean("enabled"));
            f.skills.handle("setEnabled",new JSONObject().put("scope",scope()).put("id",skill.getString("id")).put("enabled",true).put("acceptedDigest",skill.getString("digest")).put("confirmed",true));dev.pocketengine.features.plugins.PluginOwnerTestReview.enableCurrent(f.plugins,"release-data",true);f.plugins.handle("grant",new JSONObject().put("id","release-data").put("grants",new JSONArray().put("tool:fetch")));var approved=f.js.reviewInstalled("release-transform",new RunControl());f.js.approveReviewed(approved,approved.reviewDigest,true,new RunControl());approved=f.js.reviewInstalled("release-transform",new RunControl());f.js.setEnabledReviewed(approved,approved.reviewDigest,true,new RunControl());
            JSONObject second=f.install(two);assertEquals("completed",second.getString("status"));assertFalse(f.skills.snapshot().getJSONArray("skills").getJSONObject(0).getBoolean("enabled"));assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").getJSONObject(0).getJSONArray("grants").length());assertFalse(f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getBoolean("approved"));
            try(var page=f.page()){var rollback=page.restore("release-kit",original.getString("installationId"));assertTrue(rollback.view().getBoolean("restoring"));assertEquals(parsed.packageSha256,rollback.view().getJSONObject("package").getString("packageSha256"));JSONObject restored=page.submit(rollback,rollback.digest);assertEquals("completed",restored.getString("status"));assertNotEquals(first.getString("installationId"),restored.getString("installationId"));assertEquals("one",f.pack("release-kit").getString("version"));assertEquals(3,f.skills.snapshot().getJSONArray("skills").getJSONObject(0).getInt("revision"));assertNotEquals(js.getString("installationId"),f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("installationId"));
                var remove=page.reviewRemove("release-kit");JSONObject removed=page.submit(remove,remove.digest);assertEquals("completed",removed.getString("status"));assertEquals("removed",f.pack("release-kit").getString("state"));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").length());assertEquals(0,f.js.snapshot().getJSONArray("plugins").length());assertEquals(3,f.tasks.get(restored.getString("taskId"),false).getJSONArray("operations").length());assertEquals("receipt",f.tasks.get(first.getString("taskId"),false).getJSONArray("operations").getJSONObject(0).getString("state"));}
            assertEquals("not_prepared",f.host.runtimeSnapshot().getString("status"));
        }
    }
    @Test public void nativePreflightApprovalAbaAndManualReplacementCannotDeleteOrTakeOverUnownedComponents()throws Exception {
        try(Fixture f=new Fixture()) {byte[] one=packageBytes("one",false);f.install(one);
            try(var page=f.page()){var stale=page.reviewRemove("release-kit");JSONObject skill=f.skills.snapshot().getJSONArray("skills").getJSONObject(0);f.skills.handle("setEnabled",new JSONObject().put("scope",scope()).put("id",skill.getString("id")).put("enabled",true).put("acceptedDigest",skill.getString("digest")).put("confirmed",true));f.skills.handle("setEnabled",new JSONObject().put("scope",scope()).put("id",skill.getString("id")).put("enabled",false));assertThrows(SecurityException.class,()->page.submit(stale,stale.digest));assertEquals(1,f.skills.snapshot().getJSONArray("skills").length());
                JsPluginPackage manual=JsPluginPackage.author(new ByteArrayInputStream("// 手工替换不可被旧包卸载".getBytes(StandardCharsets.UTF_8)),"release-transform","手工变体","manual",new RunControl());var read=f.js.inspect(manual,new RunControl());String installation=f.js.installReviewed(read,read.reviewDigest,new RunControl()).getJSONObject("plugin").getString("installationId");var remove=page.reviewRemove("release-kit");assertTrue(remove.view().toString().contains("retain_changed"));page.submit(remove,remove.digest);assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").length());assertEquals(installation,f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("installationId"));
                var blocked=page.inspect(new ByteArrayInputStream(one));assertFalse(blocked.ready);assertTrue(blocked.view().getJSONArray("issues").length()>0);assertThrows(SecurityException.class,()->page.submit(blocked,blocked.digest));}
        }
    }
    @Test public void cancelledPartialUpdateKeepsOldConfirmedOwnershipAndOnlyRemovesAccuratelyOwnedVersions()throws Exception {
        try(Fixture f=new Fixture()){f.install(packageBytes("one",false));try(var page=f.page()){
            var update=page.inspect(new ByteArrayInputStream(packageBytes("two",false)));f.during.set(task->{try{JSONArray ops=f.tasks.get(task,false).getJSONArray("operations");if(ops.length()==1&&ops.getJSONObject(0).optJSONObject("receipt")!=null)page.cancel();}catch(Exception failure){throw new IllegalStateException(failure);}});
            JSONObject result=page.submit(update,update.digest);f.during.set(null);assertEquals("partial",result.getString("status"));JSONObject retained=f.pack("release-kit");assertEquals("partial",retained.getString("state"));assertEquals(3,retained.getJSONArray("components").length());assertEquals("one",f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("version"));assertEquals("two",f.plugins.snapshot().getJSONArray("plugins").getJSONObject(0).getJSONObject("manifest").getString("version"));assertEquals(1,f.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());
            try(var next=f.page()){var remove=next.reviewRemove("release-kit");next.submit(remove,remove.digest);assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(0,f.js.snapshot().getJSONArray("plugins").length());assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").length());}
        }}
    }
    @Test public void actualUncertainComponentWriteRetainsSqlUnknownAndReadbackNeverReplaysOrInstallsLaterSkill()throws Exception {
        try(Fixture f=new Fixture();var page=f.page()){byte[] pack=packageBytes("one",false);var inspection=page.inspect(new ByteArrayInputStream(pack));f.during.set(task->{try{JSONArray ops=f.tasks.get(task,false).getJSONArray("operations");if(ops.length()==1&&ops.getJSONObject(0).optJSONObject("receipt")!=null&&!f.jsFile.exists())assertTrue(f.jsFile.mkdir());}catch(Exception failure){throw new IllegalStateException(failure);}});
            JSONObject result=page.submit(inspection,inspection.digest);f.during.set(null);assertEquals("unknown",result.getString("status"));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());JSONObject task=f.tasks.get(result.getString("taskId"),false);assertEquals(2,task.getJSONArray("operations").length());assertEquals("unknown",task.getJSONArray("operations").getJSONObject(1).getString("state"));assertEquals("plugins_pack_component",f.tasks.unresolvedCatalogOperation().getString("tool"));assertTrue(f.jsFile.isDirectory());assertTrue(f.jsFile.delete());
            assertFalse(page.reconcile("release-kit").getBoolean("reconciled"));assertEquals(2,f.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());assertThrows(IllegalStateException.class,()->page.inspect(new ByteArrayInputStream(pack)));assertEquals(0,f.js.snapshot().getJSONArray("plugins").length());assertEquals(1,f.plugins.snapshot().getJSONArray("plugins").length());
            // An unsupported MCP component is a complete preflight refusal, not a forged server installation.
            try(var fresh=f.page()){byte[] unsupported=new JSONObject(new String(packageBytes("mcp",true),StandardCharsets.UTF_8)).put("id","mcp-kit").toString().getBytes(StandardCharsets.UTF_8);var blocked=fresh.inspect(new ByteArrayInputStream(unsupported));assertFalse(blocked.ready);assertThrows(SecurityException.class,()->fresh.submit(blocked,blocked.digest));}
        }
    }
    @Test public void blockedMcpDoesNotBlockExplicitIndependentJsAndMissingDependencyCannotBeClaimed()throws Exception {
        try(Fixture f=new Fixture();var page=f.page()){
            var initial=page.inspect(new ByteArrayInputStream(packageBytes("independent",true)));assertFalse(initial.ready);assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").length());
            var invalid=page.selectComponents(initial,new JSONArray().put("method").put("transform"));assertFalse(invalid.ready);assertTrue(invalid.view().getJSONArray("selectionProblems").length()>0);assertThrows(SecurityException.class,()->page.submit(invalid,invalid.digest));
            var selected=page.selectComponents(invalid,new JSONArray().put("transform"));assertTrue(selected.view().toString(2),selected.ready);assertFalse(selected.view().getBoolean("wholePackageSelected"));JSONObject result=page.submit(selected,selected.digest);
            assertEquals("partial",result.getString("status"));assertTrue(result.getBoolean("selectedActionsCompleted"));assertFalse(result.getBoolean("wholePackageInstalled"));assertFalse(result.getBoolean("recipeContentsComplete"));assertEquals(2,result.getJSONArray("excludedComponents").length());assertEquals(1,f.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").length());assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(1,f.js.snapshot().getJSONArray("plugins").length());assertFalse(f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getBoolean("approved"));
            JSONObject reconciled=page.reconcile("release-kit");assertTrue(reconciled.getBoolean("reconciled"));assertEquals("partial",reconciled.getString("state"));assertEquals(1,f.tasks.get(result.getString("taskId"),false).getJSONArray("operations").length());assertEquals(1,f.pack("release-kit").getJSONArray("components").length());
        }
    }
    @Test public void explicitSubsetUpdatePreservesUnselectedSkillAndManualJsAndKeepsOriginalPermissions()throws Exception {
        try(Fixture f=new Fixture()){
            f.install(packageBytes("one",false));JSONObject oldSkill=f.skills.snapshot().getJSONArray("skills").getJSONObject(0);
            JsPluginPackage manual=JsPluginPackage.author(new ByteArrayInputStream("// 手工变更不属于新包".getBytes(StandardCharsets.UTF_8)),"release-transform","手工变体","manual",new RunControl());var inspected=f.js.inspect(manual,new RunControl());String manualId=f.js.installReviewed(inspected,inspected.reviewDigest,new RunControl()).getJSONObject("plugin").getString("installationId");
            try(var page=f.page()){
                var full=page.inspect(new ByteArrayInputStream(packageBytes("two",false)));assertFalse(full.ready);var onlyData=page.selectComponents(full,new JSONArray().put("data"));assertTrue(onlyData.ready);JSONObject updated=page.submit(onlyData,onlyData.digest);assertEquals("partial",updated.getString("status"));assertEquals(1,f.tasks.get(updated.getString("taskId"),false).getJSONArray("operations").length());assertEquals(manualId,f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("installationId"));assertEquals(oldSkill.getInt("revision"),f.skills.snapshot().getJSONArray("skills").getJSONObject(0).getInt("revision"));assertFalse(f.plugins.snapshot().getJSONArray("plugins").getJSONObject(0).getBoolean("enabled"));assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").getJSONObject(0).getJSONArray("grants").length());
                var remove=page.reviewRemove("release-kit");page.submit(remove,remove.digest);assertEquals(manualId,f.js.snapshot().getJSONArray("plugins").getJSONObject(0).getString("installationId"));assertEquals(0,f.skills.snapshot().getJSONArray("skills").length());assertEquals(0,f.plugins.snapshot().getJSONArray("plugins").length());
            }
        }
    }

}
