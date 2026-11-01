package dev.pocketengine.browser;

import android.os.Looper;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.json.JSONArray;

/** Waits for one native decision while the original runner/control/prepared operation stay alive. */
public final class BrowserTaskApprovalPort implements BrowserTaskCapabilityPort.ApprovalPort,AutoCloseable {
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final RunControl control;
    private final TaskStoreCapabilityAuthority authority;
    private final ModelCapabilityAdapters.ResourceCheck resource;
    private final EventSink events;
    private static final class Waiting {
        final String operationId,tool;final JSONObject args,pin;
        Waiting(String operationId,String tool,JSONObject args,JSONObject pin){this.operationId=operationId;this.tool=tool;this.args=args;this.pin=pin;}
    }
    private final ConcurrentHashMap<String,Waiting> waiting=new ConcurrentHashMap<>();
    private volatile boolean closed;
    public BrowserTaskApprovalPort(TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl originalControl,
            TaskStoreCapabilityAuthority authority,ModelCapabilityAdapters.ResourceCheck resource,EventSink events) {
        this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);control=Objects.requireNonNull(originalControl);
        this.authority=Objects.requireNonNull(authority);this.resource=Objects.requireNonNull(resource);this.events=Objects.requireNonNull(events);
    }
    private void sameRead(ModelCapabilityAdapters.Binding supplied,RunControl original)throws Exception {
        if(closed||supplied!=binding||original!=control||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("只读范围不能替换原任务控制器");
    }
    @Override public boolean nativeReadAuthority(ModelCapabilityAdapters.Binding supplied,RunControl original,String tool)throws Exception {
        sameRead(supplied,original);if(!BrowserReadScope.readTool(tool))return false;
        control.check();resource.check();tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,tool);
        if(control.isCancelled()||control.isPaused()||Thread.currentThread().isInterrupted())throw new SecurityException("原只读执行已停止");return true;
    }
    @Override public JSONObject admitReadScope(ModelCapabilityAdapters.Binding supplied,RunControl original,String operation,String tool,JSONObject args,JSONObject pin)throws Exception {
        if(!BrowserReadScope.readTool(tool))return null;nativeReadAuthority(supplied,original,tool);authority.requirePrepared(operation,tool,args);
        JSONObject permit=tasks.admitBrowserReadScope(binding.taskId,binding.runId,binding.epoch,control,operation,tool,args,pin);
        if(permit!=null){checkReadScope(supplied,original,permit);try{events.emit(new JSONObject().put("type","browser_read_scope_used").put("taskId",binding.taskId).put("scopeId",permit.getString("scopeId")).put("operationId",operation));}catch(Exception lostNotice){/* Admission is already durable in the original grant and journal. */}}
        return permit;
    }
    @Override public void checkReadScope(ModelCapabilityAdapters.Binding supplied,RunControl original,JSONObject permit)throws Exception {
        sameRead(supplied,original);String tool=permit.getString("tool");nativeReadAuthority(supplied,original,tool);
        tasks.checkBrowserReadScopePermit(binding.taskId,binding.runId,binding.epoch,control,permit);
        control.check();resource.check();if(control.isCancelled()||control.isPaused())throw new SecurityException("只读许可外部复核已失效");
    }
    @Override public JSONObject readScopeSnapshot(ModelCapabilityAdapters.Binding supplied,RunControl original)throws Exception {
        if(supplied!=binding||original!=control)throw new SecurityException("只读范围不属于原任务");return tasks.browserReadScopeForOwner(binding.taskId,binding.runId,binding.epoch,control.pauseRevision());
    }
    /** Re-project only the exact already-journalled safe read; no old prepared permit is revived. */
    @Override public void checkReadContextRepair(ModelCapabilityAdapters.Binding supplied,RunControl original,JSONObject safeReceipt)throws Exception {
        nativeReadAuthority(supplied,original,"browser_task_observe");String operation=safeReceipt.getString("operationId");BrowserTaskSpace.uuid(operation);
        synchronized(tasks){JSONObject task=tasks.get(binding.taskId,false),row=null;JSONArray operations=task.getJSONArray("operations");for(int i=0;i<operations.length();i++)if(operation.equals(operations.getJSONObject(i).optString("operationId"))){if(row!=null)throw new SecurityException("browser_context_original_operation_ambiguous");row=operations.getJSONObject(i);}
            if(row==null||!binding.runId.equals(row.getString("runId"))||!row.getString("tool").equals("browser_task_observe")||!row.getString("state").equals("receipt")||!TaskStore.capabilityFingerprint(row.getJSONObject("receipt")).equals(TaskStore.capabilityFingerprint(safeReceipt)))throw new SecurityException("browser_context_original_safe_receipt_changed");
            JSONObject read=safeReceipt.optJSONObject("readAuthorization");if(read!=null){JSONObject scope=tasks.browserReadScopeForOwner(binding.taskId,binding.runId,binding.epoch,control.pauseRevision());if(!scope.optString("state").matches("active|exhausted")||scope.optLong("expiresAt")<=System.currentTimeMillis()||!read.getString("scopeId").equals(scope.getString("scopeId"))||read.getLong("scopeRevision")!=scope.getLong("revision"))throw new SecurityException("browser_context_read_scope_version_changed");}
            else{JSONObject approved=null;JSONArray approvals=task.getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject a=approvals.getJSONObject(i);if(operation.equals(a.optString("operationId"))&&"browser_task_observe".equals(a.optString("tool"))&&"consumed".equals(a.optString("state"))){if(approved!=null)throw new SecurityException("browser_context_original_approval_ambiguous");approved=a;}}if(approved==null)throw new SecurityException("browser_context_original_approval_unavailable");}
        }
        control.check();resource.check();
    }
    @Override public JSONObject approve(ModelCapabilityAdapters.Binding supplied,RunControl original,String operationId,String tool,JSONObject args,JSONObject pin)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("浏览审批等待不能阻塞原生主线程");
        if(supplied!=binding||original!=control||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch)||!binding.taskId.equals(pin.optString("taskId"))||!binding.runId.equals(pin.optString("runId"))||binding.epoch!=pin.optInt("epoch"))throw new SecurityException("浏览审批必须保留原任务控制与固定身份");
        BrowserTaskSpace.uuid(operationId);if(!tool.matches("browser_task_[a-z_]+"))throw new SecurityException("不是任务浏览工具");
        JSONObject parameters=new JSONObject(args.toString()),identity=new JSONObject(pin.toString());JSONObject result=request(operationId,tool,parameters,identity);
        if(result.optBoolean("approved")||!result.optString("state").equals("pending"))return result;
        String id=result.getString("approvalId");Waiting monitor=new Waiting(operationId,tool,parameters,identity);if(waiting.putIfAbsent(id,monitor)!=null)throw new IllegalStateException("该原审批已在等待");
        Runnable cancelled=()->{synchronized(monitor){monitor.notifyAll();}};control.onCancel(cancelled);
        try{
            events.emit(new JSONObject().put("type","capability_approval").put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch).put("operationId",operationId).put("approvalId",id).put("tool",tool).put("arguments",new JSONObject(parameters.toString())).put("capability",new JSONObject(identity.toString())).put("sameRunBrowserWaiting",true).put("message","请确认本次网页参数、私人来源范围与页面/提交目标；原任务等待此决定，尚未派发。"));
            long deadline=System.nanoTime()+300_000_000_000L;
            while(System.nanoTime()<deadline){
                result=poll(id,monitor);if(result.optBoolean("approved")||!result.optString("state").equals("pending"))return result;
                synchronized(monitor){monitor.wait(100);}
            }
            return new JSONObject().put("approved",false).put("approvalId",id).put("state","expired");
        }catch(Exception stopped){
            if(control.isPaused()&&!control.isCancelled())try{synchronized(tasks){JSONObject task=tasks.get(binding.taskId,false);if(task!=null&&binding.runId.equals(task.optString("runId"))&&binding.epoch==task.optInt("coordinatorEpoch")){tasks.decideCapabilityApproval(binding.taskId,id,false);tasks.event(binding.taskId,binding.runId,binding.epoch,new JSONObject().put("type","browser_approval_withdrawn").put("approvalId",id).put("operationId",operationId).put("reason","owner_took_control"));}}}catch(Exception alreadyDecided){/* An already decided request still cannot dispatch through the paused original gate. */}
            throw stopped;
        }finally{waiting.remove(id,monitor);control.removeCancelAction(cancelled);}
    }
    private JSONObject request(String operationId,String tool,JSONObject args,JSONObject pin)throws Exception {
        current(operationId,tool,args);
        return tasks.requireCapabilityApproval(binding.taskId,binding.runId,tool,args,pin);
    }
    private void current(String operationId,String tool,JSONObject args)throws Exception {if(closed)throw new IllegalStateException("浏览审批已关闭");if(BrowserReadScope.readTool(tool))nativeReadAuthority(binding,control,tool);else{resource.check();authority.check(tool,control);}authority.requirePrepared(operationId,tool,args);}
    private JSONObject poll(String id,Waiting selected)throws Exception {
        current(selected.operationId,selected.tool,selected.args);
        synchronized(tasks){
            JSONObject task=tasks.get(binding.taskId,false);if(task==null)throw new SecurityException("原浏览任务已删除");org.json.JSONArray approvals=task.optJSONArray("approvals");
            for(int i=0;approvals!=null&&i<approvals.length();i++){JSONObject record=approvals.getJSONObject(i);if(!id.equals(record.optString("id")))continue;
                if(!binding.runId.equals(record.optString("runId"))||!selected.tool.equals(record.optString("tool"))||!TaskStore.capabilityFingerprint(selected.args).equals(record.optString("argumentsHash"))||!TaskStore.capabilityFingerprint(selected.pin).equals(TaskStore.capabilityFingerprint(record.getJSONObject("capability"))))throw new SecurityException("原浏览审批身份已变化");
                String state=record.optString("state");if(record.optLong("expiresAt")<=System.currentTimeMillis())state="expired";
                if(state.equals("approved")){JSONObject consumed=tasks.requireCapabilityApproval(binding.taskId,binding.runId,selected.tool,selected.args,selected.pin);if(id.equals(consumed.optString("approvalId")))return consumed;state="expired";}
                if(!state.matches("pending|denied|expired|invalidated"))throw new SecurityException("原浏览审批已被其他调用消费");
                return new JSONObject().put("approved",false).put("approvalId",id).put("state",state);
            }throw new SecurityException("原浏览审批已不可查询");
        }
    }
    /** Native host checks this before choosing the generic new-attempt approval-resume path. */
    public boolean isWaiting(String taskId,String approvalId,RunControl exactControl)throws Exception {Waiting selected=waiting.get(approvalId);if(closed||exactControl!=control||!binding.taskId.equals(taskId)||selected==null||control.isCancelled()||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))return false;authority.requirePrepared(selected.operationId,selected.tool,selected.args);return true;}
    public void nativeDecisionChanged(String taskId,String approvalId,RunControl exactControl){if(exactControl!=control||!binding.taskId.equals(taskId))return;Waiting monitor=waiting.get(approvalId);if(monitor!=null)synchronized(monitor){monitor.notifyAll();}}
    @Override public void close(){closed=true;for(Object monitor:waiting.values())synchronized(monitor){monitor.notifyAll();}}
}
