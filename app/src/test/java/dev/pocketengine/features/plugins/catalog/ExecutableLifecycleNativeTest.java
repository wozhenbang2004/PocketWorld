package dev.pocketengine.features.plugins.catalog;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.NativePluginHost;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.runtime.extensions.ExtensionProgram;
import dev.pocketengine.runtime.extensions.ExtensionRuntime;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual SDK35 SQLite/task JSON + catalog files + native confirmation + real source admission.
 * No sandbox/provider/device execution claim: these tests deliberately never prepare or evaluate JS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ExecutableLifecycleNativeTest {
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();
        final TaskStore tasks=new TaskStore(app);
        final PluginsModule plugins=new PluginsModule(new FeatureContext(app,null,new SecretVault(app),event->{}));
        final PluginCatalog configured=new PluginCatalog(plugins,new File(app.getFilesDir(),"plugin-catalog-sources.json"),35);
        final File state=new File(app.getFilesDir(),"executable-plugin-catalog.json");
        final ExecutablePluginCatalog catalog=new ExecutablePluginCatalog(configured,state);
        final NativePluginHost host=new NativePluginHost(app,plugins,tasks,(binding,control)->tasks.checkExtensionRun(binding.taskId,binding.runId,binding.epoch,control),id->{});
        JsPluginPackage packaged(String id,String version,String exact)throws Exception {return JsPluginPackage.author(new ByteArrayInputStream(exact.getBytes(StandardCharsets.UTF_8)),id,"原始源码 "+id,version,new RunControl());}
        JSONObject install(JsPluginPackage pkg)throws Exception {var read=catalog.inspect(pkg,new RunControl());return catalog.installReviewed(read,read.reviewDigest,new RunControl()).getJSONObject("plugin");}
        void approve(String id)throws Exception {var read=catalog.reviewInstalled(id,new RunControl());catalog.approveReviewed(read,read.reviewDigest,true,new RunControl());read=catalog.reviewInstalled(id,new RunControl());catalog.setEnabledReviewed(read,read.reviewDigest,true,new RunControl());}
        String task()throws Exception {return tasks.createCapabilityTask("只选这项任务需要的版本整理计算扩展","").getString("id");}
        @Override public void close(){host.close();catalog.close();configured.close();plugins.close();}
    }
    @Test public void accuratePackageHistoryRestoresNewUnapprovedIdentityAndNeverOldProgramOrPermission()throws Exception {
        try(Fixture f=new Fixture()) {
            String source="\ufeff//真实原字节😀\r\ndefineExtension({tools:[]});\n";
            JsPluginPackage authored=f.packaged("release","one",source);
            String serialized=new String(authored.packageBytes(),StandardCharsets.UTF_8);
            byte[] original=(" \n{\n "+serialized.substring(1)+"\r\n").getBytes(StandardCharsets.UTF_8);
            JsPluginPackage exact=JsPluginPackage.read(new ByteArrayInputStream(original),new RunControl());
            JSONObject initial=f.install(exact);f.approve("release");var oldSource=f.catalog.approvedSources(new RunControl()).get(0);
            f.install(f.packaged("release","two","// second\ndefineExtension({tools:[]});"));f.approve("release");
            JSONObject history=f.catalog.history("release",new RunControl());assertEquals(1,history.getJSONArray("versions").length());JSONObject version=history.getJSONArray("versions").getJSONObject(0);
            assertEquals(exact.packageSha256,version.getString("packageSha256"));assertFalse(version.has("approved"));assertFalse(version.has("enabled"));assertFalse(version.has("source"));
            File retained=new File(f.state.getParentFile(),f.state.getName()+".packages/"+exact.packageSha256+".pwjs");assertArrayEquals(original,Files.readAllBytes(retained.toPath()));
            NativePluginHost.Page page=f.host.newOwnerPage();Object owner=page.nativeOwner();
            var historyView=page.session.begin(owner,java.util.UUID.randomUUID().toString(),"jsHistory",new JSONObject().put("id","release")).execute();assertEquals(1,historyView.data().getJSONObject("jsHistory").getJSONArray("versions").length());
            var inspected=page.session.begin(owner,java.util.UUID.randomUUID().toString(),"jsPrepareRestore",new JSONObject().put("id","release").put("installationId",initial.getString("installationId"))).execute();
            assertTrue(inspected.data().getJSONObject("review").getBoolean("restoring"));assertEquals(source,inspected.data().getJSONObject("review").getString("source"));
            var rollback=inspected.approval;var request=page.session.submit(owner,java.util.UUID.randomUUID().toString(),rollback);var pending=page.session.view(owner);page.beforeDispatch(request,pending,new JSONObject());
            var done=request.execute();JSONObject restored=request.receipt();page.settled(request,done,restored);JSONObject row=restored.getJSONObject("plugin");
            assertEquals("completed",done.data().getString("status"));assertThrows(SecurityException.class,()->page.session.submit(owner,java.util.UUID.randomUUID().toString(),rollback));page.close();
            assertTrue(restored.getBoolean("restored"));assertFalse(restored.getBoolean("permissionsRestored"));assertEquals("one",row.getString("version"));assertNotEquals(initial.getString("installationId"),row.getString("installationId"));assertFalse(row.getBoolean("approved"));assertFalse(row.getBoolean("enabled"));
            assertTrue(f.catalog.approvedSources(new RunControl()).isEmpty());assertThrows(SecurityException.class,()->f.catalog.checkCurrent(oldSource,new RunControl()));
            f.approve("release");assertThrows(SecurityException.class,()->f.catalog.checkCurrent(oldSource,new RunControl()));
        }
    }
    @Test public void historyRetentionAndRestoreCasProtectCurrentInstallTamperAndUninstallOwnership()throws Exception {
        try(Fixture f=new Fixture()) {
            JSONObject first=f.install(f.packaged("release","one","// one"));
            for(int i=2;i<=5;i++)f.install(f.packaged("release","v"+i,"// exact "+i));
            JSONArray history=f.catalog.history("release",new RunControl()).getJSONArray("versions");assertEquals(3,history.length());assertEquals("v4",history.getJSONObject(0).getString("version"));
            assertThrows(IllegalStateException.class,()->f.catalog.prepareRestore("release",first.getString("installationId"),new RunControl()));
            JSONObject selected=history.getJSONObject(0);var rollback=f.catalog.prepareRestore("release",selected.getString("installationId"),new RunControl());
            f.approve("release");assertThrows(IllegalStateException.class,()->f.catalog.installReviewed(rollback,rollback.reviewDigest,new RunControl()));
            File retained=new File(f.state.getParentFile(),f.state.getName()+".packages/"+selected.getString("packageSha256")+".pwjs");byte[] good=Files.readAllBytes(retained.toPath());byte[] bad=good.clone();bad[bad.length-1]^=1;Files.write(retained.toPath(),bad);
            assertThrows(SecurityException.class,()->f.catalog.prepareRestore("release",selected.getString("installationId"),new RunControl()));Files.write(retained.toPath(),good);
            var remove=f.catalog.reviewInstalled("release",new RunControl());f.catalog.removeReviewed(remove,remove.reviewDigest,new RunControl());assertEquals(0,f.catalog.snapshot().getJSONArray("plugins").length());assertFalse(retained.exists());
            assertThrows(IllegalArgumentException.class,()->f.catalog.history("release",new RunControl()));assertThrows(IllegalStateException.class,()->f.catalog.prepareRestore("release",selected.getString("installationId"),new RunControl()));
            // Legacy source is never silently reserialized and claimed to be the original package.
            f.install(f.packaged("release","legacy","// legacy"));JSONObject legacy=JsonFiles.read(f.state);legacy.getJSONArray("plugins").getJSONObject(0).remove("packageSha256");legacy.getJSONArray("plugins").getJSONObject(0).remove("packageByteSize");JsonFiles.write(f.state,legacy);
            f.install(f.packaged("release","next","// next"));JSONObject unavailable=f.catalog.history("release",new RunControl()).getJSONArray("versions").getJSONObject(0);assertFalse(unavailable.getBoolean("restorable"));
            assertThrows(IllegalStateException.class,()->f.catalog.prepareRestore("release",unavailable.getString("installationId"),new RunControl()));
        }
    }
    @Test public void nativeTaskChoiceSelectsOneOfSeventeenWithoutGlobalDisableAndSurvivesSqlReload()throws Exception {
        try(Fixture f=new Fixture()) {
            for(int i=0;i<17;i++){String id="release"+i;f.install(f.packaged(id,"one","// pure "+i));f.approve(id);}
            String task=f.task(),other=f.task();try(NativePluginHost.TaskSelection page=f.host.newTaskSelection(task)) {
                assertEquals(17,page.snapshot().getJSONArray("available").length());assertThrows(SecurityException.class,()->page.review(Set.of("release3")));page.setForeground(true);
                var chosen=page.review(Set.of("release3"));assertFalse(chosen.view().getBoolean("executionAuthorized"));JSONObject saved=page.save(chosen,chosen.digest);assertTrue(saved.getBoolean("selectionSaved"));
                assertEquals(17,f.catalog.approvedSources(new RunControl()).size());assertEquals("inherit_enabled",f.tasks.extensionSelectionTarget(other).getJSONObject("selection").getString("mode"));
                assertThrows(SecurityException.class,()->page.save(chosen,chosen.digest));
                JSONObject original=f.tasks.startAttempt(task,"capabilities",10);ExtensionRuntime.Binding binding=new ExtensionRuntime.Binding(task,original.getString("runId"),original.getInt("coordinatorEpoch"));RunControl control=new RunControl();control.bindHostRun(binding.taskId,binding.runId,binding.epoch);
                JSONObject target=f.tasks.extensionSelectionTarget(task);var selection=new TaskExtensionSelection(target,(run,revision,current)->f.tasks.checkExtensionSelection(run.taskId,run.runId,run.epoch,revision));
                try(ExecutableExtensionSource source=new ExecutableExtensionSource(f.catalog,(run,current)->f.tasks.checkExtensionRun(run.taskId,run.runId,run.epoch,current),200,selection)) {
                    List<ExtensionProgram> programs=source.approved(binding,control);assertEquals(1,programs.size());assertEquals("release3",programs.get(0).id);
                    assertThrows(IllegalStateException.class,()->page.review(Set.of("release4")));
                    f.tasks.status(task,"paused","原任务明确暂停");var next=page.review(Set.of("release4"));page.save(next,next.digest);
                    assertThrows(SecurityException.class,()->source.checkSourceCurrent(programs.get(0),binding,control));
                }
                TaskStore reopened=new TaskStore(f.app);JSONObject restored=reopened.extensionSelectionTarget(task).getJSONObject("selection");assertEquals(2,restored.getLong("revision"));assertEquals("release4",restored.getJSONArray("programs").getJSONObject(0).getString("id"));
                assertEquals(17,f.catalog.approvedSources(new RunControl()).size());
            }
        }
    }
    @Test public void selectionPageCasAndReplacementDoNotRefreshOldReferencesOrOtherTasks()throws Exception {
        try(Fixture f=new Fixture()) {
            f.install(f.packaged("release","one","// first"));f.approve("release");String task=f.task();
            try(var page=f.host.newTaskSelection(task);var competing=f.host.newTaskSelection(task)) {
                page.setForeground(true);competing.setForeground(true);var a=page.review(Set.of("release"));var b=competing.review(Set.of("release"));
                page.save(a,a.digest);assertThrows(SecurityException.class,()->competing.save(b,b.digest));
                var stale=page.review(Set.of("release"));f.install(f.packaged("release","two","// second"));f.approve("release");assertThrows(SecurityException.class,()->page.save(stale,stale.digest));
                JSONObject run=f.tasks.startAttempt(task,"capabilities",5);ExtensionRuntime.Binding binding=new ExtensionRuntime.Binding(task,run.getString("runId"),run.getInt("coordinatorEpoch"));RunControl control=new RunControl();control.bindHostRun(binding.taskId,binding.runId,binding.epoch);
                var selection=new TaskExtensionSelection(f.tasks.extensionSelectionTarget(task),(current,revision,c)->f.tasks.checkExtensionSelection(current.taskId,current.runId,current.epoch,revision));
                assertThrows(SecurityException.class,()->selection.select(binding,f.catalog.approvedSources(new RunControl()),control));
                f.tasks.status(task,"paused","待重新选用");var empty=page.review(Set.of());page.save(empty,empty.digest);
                assertEquals(0,f.tasks.extensionSelectionTarget(task).getJSONObject("selection").getJSONArray("programs").length());assertEquals(1,f.catalog.approvedSources(new RunControl()).size());
                JSONObject continued=f.tasks.startAttempt(task,"capabilities",5);ExtensionRuntime.Binding next=new ExtensionRuntime.Binding(task,continued.getString("runId"),continued.getInt("coordinatorEpoch"));RunControl nativeControl=new RunControl();nativeControl.bindHostRun(next.taskId,next.runId,next.epoch);
                CapabilityPort base=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject arguments,RunControl c){throw new AssertionError("no task or extension tool may run while preparing an empty selection");}};
                try(CapabilityPort actual=f.host.bind(base,next,nativeControl)){actual.beginRun("原任务继续，明确空选用",nativeControl);assertEquals(0,actual.prepareTurn("只读当前工具表",1,0,nativeControl).getJSONArray("tools").length());actual.endRun(nativeControl);}finally{f.host.release(task,nativeControl);}
                assertEquals("not_prepared",f.host.runtimeSnapshot().getString("status"));f.tasks.status(task,"paused","空选用已核对，未运行JS");
                var oldPage=page.review(Set.of("release"));page.close();assertThrows(SecurityException.class,()->page.save(oldPage,oldPage.digest));
            }
            String operationRun=java.util.UUID.randomUUID().toString();JSONObject unresolved=f.tasks.beginExternal("plugins","fixture_pending",operationRun,new JSONObject().put("goal","原已派发动作待核对"));String unresolvedTask=unresolved.getString("id");
            f.tasks.status(unresolvedTask,"running","原操作已登记");String operation=f.tasks.beginCapabilityOperation(unresolvedTask,operationRun,"plugins","fixture_pending",new JSONObject());f.tasks.status(unresolvedTask,"paused","暂停不能清除原未决动作");
            try(var page=f.host.newTaskSelection(unresolvedTask)){page.setForeground(true);assertFalse(page.snapshot().getBoolean("editable"));assertThrows(IllegalStateException.class,()->page.review(Set.of("release")));}
            JSONObject retained=f.tasks.get(unresolvedTask,false).getJSONArray("operations").getJSONObject(0);assertEquals(operation,retained.getString("operationId"));assertEquals("prepared",retained.getString("state"));
        }
    }
}
