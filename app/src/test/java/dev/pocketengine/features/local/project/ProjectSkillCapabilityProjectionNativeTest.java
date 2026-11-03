package dev.pocketengine.features.local.project;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Original LocalModule/runtime descriptions and SQLite admission; no executable tool package.
 * Capability metadata must not create a run, grant or managed/Termux dispatch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectSkillCapabilityProjectionNativeTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    @Test public void ownerProjectionMatchesOriginalRuntimeSchemasWithoutGrantOrDispatch()throws Exception {
        Context android=RuntimeEnvironment.getApplication();
        ManagedProjectRuntime runtime=ManagedProjectRuntime.unconfigured(temporary.newFolder("runtime"));
        FeatureContext context=new FeatureContext(android,null,null,event->{});
        WorkspaceModule workspace=new WorkspaceModule(context);
        LocalModule local=new LocalModule(context,workspace,runtime);
        TaskStore tasks=new TaskStore(android);
        try {
            // The owner projection is usable before any task or original control exists.
            Set<String> managed=ProjectCapabilityPort.configuredSkillCapabilitiesForOwner(local,ManagedProjectRuntime.RUNTIME_ID);
            Set<String> termux=ProjectCapabilityPort.configuredSkillCapabilitiesForOwner(local,"termux");
            assertEquals(0,tasks.list().length());
            assertFalse(runtime.describe().getBoolean("canExecute"));
            assertThrows(UnsupportedOperationException.class,()->managed.add("unreviewed_tool"));
            assertThrows(SecurityException.class,()->ProjectCapabilityPort.configuredSkillCapabilitiesForOwner(local,"missing_runtime"));

            String project=workspace.handle("create",new JSONObject().put("name","Original capability metadata")).getString("id");
            workspace.handle("write",new JSONObject().put("projectId",project).put("path","main.py").put("expectedVersion",0).put("content","print('not executed')\n"));
            String checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
            String task=tasks.createCapabilityTask("Review project Skill dependencies without running them","").getString("id");
            JSONObject attempt=tasks.startAttempt(task,"capabilities",8);
            String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");
            RunControl original=new RunControl();original.bindHostRun(task,run,epoch);
            String before=TaskStore.capabilityFingerprint(tasks.get(task,false));
            for(String runtimeId:new String[]{ManagedProjectRuntime.RUNTIME_ID,"termux"}) {
                Set<String> expected=runtimeId.equals("termux")?termux:managed;
                JSONObject facts=local.projectRuntimeForHost(runtimeId);
                var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,original,true,project);
                var readOnly=new ModelCapabilityAdapters.Binding(task,run,epoch,original,false,project);
                try(ProjectCapabilityPort writable=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,runtimeId);
                    ProjectCapabilityPort reads=new ProjectCapabilityPort(readOnly,new TaskStoreCapabilityAuthority(tasks,readOnly,event->{}),original,null,local,tasks,checkout,runtimeId)) {
                    assertEquals(expected,names(writable.tools()));
                    assertEquals(expected,ProjectCapabilityPort.configuredSkillCapabilitiesForOwner(local,runtimeId));
                    boolean phone=runtimeId.equals(ManagedProjectRuntime.RUNTIME_ID);
                    assertTrue(expected.contains(TaskStoreProjectHost.TOOL));
                    assertEquals(phone,expected.contains("project_log"));
                    assertEquals(phone,expected.contains("project_git_result"));
                    assertEquals(phone,expected.contains("project_stop"));
                    assertEquals(phone,expected.contains("project_live_preview"));
                    assertEquals(phone,expected.contains("project_wait_input"));
                    assertEquals(phone&&facts.optBoolean("ptyAvailable"),expected.contains("project_process_input"));
                    // The original managed provider reports collection; the original Termux
                    // description currently has no COLLECT phase and must not invent one.
                    assertEquals(phone,expected.contains("project_collect_plan"));
                    assertEquals(phone,expected.contains("project_generated_artifact"));
                    Set<String> readonlyExpected=new LinkedHashSet<>(expected);
                    readonlyExpected.remove(TaskStoreProjectHost.TOOL);
                    readonlyExpected.remove("project_process_input");readonlyExpected.remove("project_stop");
                    assertEquals(readonlyExpected,names(reads.tools()));
                    assertThrows(SecurityException.class,()->writable.execute("project_inspect",new JSONObject(),original));
                }
            }
            assertEquals(before,TaskStore.capabilityFingerprint(tasks.get(task,false)));
            assertEquals(0,tasks.get(task,false).getJSONArray("operations").length());
            assertEquals(0L,runtime.currentCheckoutSequence(checkout));
            JSONObject state=local.snapshot();
            assertEquals(0,state.getJSONArray("jobs").length());assertEquals(0,state.getJSONArray("termuxJobs").length());
            assertNull(shadowOf((Application)android).getNextStartedService());
            assertFalse(runtime.describe().getBoolean("canExecute"));
        } finally {
            local.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);
            ((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();
        }
    }
    private static Set<String> names(JSONArray definitions)throws Exception {
        Set<String> names=new LinkedHashSet<>();
        for(int i=0;i<definitions.length();i++)assertTrue(names.add(definitions.getJSONObject(i).getJSONObject("function").getString("name")));
        return names;
    }
}
