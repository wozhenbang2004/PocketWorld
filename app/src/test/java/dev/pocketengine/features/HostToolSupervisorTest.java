package dev.pocketengine.features;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class HostToolSupervisorTest {
    private JSONObject attempt(TaskStore tasks)throws Exception{String id=tasks.create("读取并整理项目","main_screen").getString("id");return tasks.startAttempt(id,"main_screen",20);}
    private EngineModule workspace(AtomicInteger dispatched,AtomicReference<JSONObject> input){return new EngineModule(){public String id(){return "workspace";}public JSONObject snapshot()throws Exception{return new JSONObject().put("projects",new JSONArray());}public JSONObject handle(String command,JSONObject args)throws Exception{dispatched.incrementAndGet();input.set(new JSONObject(args.toString()));return new JSONObject().put("content","actual fixture file").put("command",command);}};}
    @Test public void readOnlyRoutesRequireTheExactTaskGrantAndCannotOverrideIdentity()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());JSONObject attempt=attempt(tasks);String task=attempt.getString("id"),run=attempt.getString("runId");AtomicInteger dispatched=new AtomicInteger();AtomicReference<JSONObject> input=new AtomicReference<>();
        try(FeatureHost host=new FeatureHost(event->{})){
            host.register(workspace(dispatched,input));HostToolSupervisor port=new HostToolSupervisor(host,tasks,task,run,attempt.getInt("coordinatorEpoch"),false);assertTrue(port.supports("workspace_read"));assertFalse(port.supports("workspace_write"));
            JSONObject args=new JSONObject().put("projectId","project").put("path","notes.txt");
            try{port.execute("workspace_read",args,new RunControl());fail("ungranted read cannot dispatch");}catch(SecurityException expected){}
            tasks.authorizeTools(task,run,new JSONArray().put("workspace_read"));assertEquals("actual fixture file",port.execute("workspace_read",args,new RunControl()).getString("content"));assertEquals(task,input.get().getString("_taskId"));assertEquals(run,input.get().getString("_runId"));
            try{port.validate("workspace_read",new JSONObject(args.toString()).put("taskId","another-owner-task"));fail("model identity injection");}catch(SecurityException expected){}
            RunControl cancelled=new RunControl();cancelled.cancel();try{port.execute("workspace_read",args,cancelled);fail("cancelled reads cannot dispatch");}catch(InterruptedException expected){}
            HostToolSupervisor oldEpoch=new HostToolSupervisor(host,tasks,task,run,attempt.getInt("coordinatorEpoch")-1,false);try{oldEpoch.execute("workspace_read",args,new RunControl());fail("epoch fence");}catch(SecurityException expected){}
            assertEquals(1,dispatched.get());
        }
    }
    @Test public void writeApprovalIsExactSingleUseAndDoesNotHashTheHostOperationId()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());JSONObject attempt=attempt(tasks);String task=attempt.getString("id"),run=attempt.getString("runId");AtomicInteger dispatched=new AtomicInteger();AtomicReference<JSONObject> input=new AtomicReference<>();CopyOnWriteArrayList<JSONObject> events=new CopyOnWriteArrayList<>();
        try(FeatureHost host=new FeatureHost(events::add)){
            host.register(workspace(dispatched,input));HostToolSupervisor port=new HostToolSupervisor(host,tasks,task,run,attempt.getInt("coordinatorEpoch"),true);tasks.authorizeTools(task,run,new JSONArray().put("workspace_write"));
            JSONObject args=new JSONObject().put("projectId","project").put("path","notes.txt").put("content","real content").put("expectedVersion",0).put("_hostOperationId",UUID.randomUUID().toString());
            JSONObject pending=port.execute("workspace_write",args,new RunControl());assertEquals("approval_required",pending.getString("code"));assertEquals(0,dispatched.get());assertTrue(events.stream().anyMatch(event->event.optString("type").equals("capability_approval")));assertFalse(tasks.get(task,false).getJSONArray("approvals").getJSONObject(0).getJSONObject("arguments").has("_hostOperationId"));
            tasks.decideCapabilityApproval(task,pending.getString("approvalId"),true);args.put("_hostOperationId",UUID.randomUUID().toString());port.execute("workspace_write",args,new RunControl());assertEquals(1,dispatched.get());assertFalse(input.get().has("_hostOperationId"));
            assertEquals("approval_required",port.execute("workspace_write",args,new RunControl()).getString("code"));assertEquals(1,dispatched.get());
        }
    }
    @Test public void validationRejectsMalformedOrReservedArgumentsBeforeAnyApprovalExists()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());JSONObject attempt=attempt(tasks);AtomicInteger dispatched=new AtomicInteger();
        try(FeatureHost host=new FeatureHost(event->{})){
            host.register(workspace(dispatched,new AtomicReference<>()));HostToolSupervisor port=new HostToolSupervisor(host,tasks,attempt.getString("id"),attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),true);
            JSONObject malformed=new JSONObject().put("projectId","p").put("path","x").put("content",42).put("expectedVersion",0);try{port.validate("workspace_write",malformed);fail("wrong type");}catch(IllegalArgumentException expected){}
            malformed.put("content","x").put("_hostOperationId",UUID.randomUUID().toString());try{port.validate("workspace_write",malformed);fail("a model cannot supply a host operation identity");}catch(SecurityException expected){}
            assertNull(tasks.get(attempt.getString("id"),false).optJSONArray("approvals"));assertEquals(0,dispatched.get());
        }
    }
    @Test public void pluginDescriptorsArePinnedAndUseTheActualHostOperationIdentity()throws Exception{
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());JSONObject attempt=attempt(tasks);String task=attempt.getString("id"),run=attempt.getString("runId"),operation=UUID.randomUUID().toString();String readName="plugin_0123456789abcdef01234567",writeName="plugin_abcdef0123456789abcdef01";AtomicReference<JSONObject> invocation=new AtomicReference<>();AtomicInteger cancels=new AtomicInteger();
        try(FeatureHost host=new FeatureHost(event->{})){
            host.register(new EngineModule(){public String id(){return "plugins";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject args)throws Exception {
                if(command.equals("registry")){JSONArray tools=new JSONArray();for(String name:new String[]{readName,writeName})tools.put(new JSONObject().put("name",name).put("pluginId","test").put("tool","query").put("version","2").put("installationId","installed-2").put("executionBinding",new JSONObject().put("installationId","installed-2").put("stateRevision",2).put("grantsDigest","b".repeat(64)).put("fixture",true)).put("effect",name.equals(readName)?"read":"write").put("description","真实声明工具").put("schema",new JSONObject().put("type","object").put("properties",new JSONObject().put("query",new JSONObject().put("type","string"))).put("required",new JSONArray().put("query")).put("additionalProperties",false)));return new JSONObject().put("tools",tools);}
                if(command.equals("cancel")){cancels.incrementAndGet();return new JSONObject();}invocation.set(new JSONObject(args.toString()));return new JSONObject().put("value","actual fixture tool result");}});
            HostToolSupervisor port=new HostToolSupervisor(host,tasks,task,run,attempt.getInt("coordinatorEpoch"),false);assertTrue(port.supports(readName));assertFalse(port.supports(writeName));tasks.authorizeTools(task,run,new JSONArray().put(readName));RunControl control=new RunControl();
            assertEquals("actual fixture tool result",port.execute(readName,new JSONObject().put("query","real query").put("_hostOperationId",operation),control).getString("value"));assertEquals(operation,invocation.get().getString("_operationId"));assertEquals("2",invocation.get().getString("expectedVersion"));assertEquals("installed-2",invocation.get().getString("expectedInstallationId"));control.cancel();Thread.sleep(30);assertEquals(0,cancels.get());
        }
    }
}
