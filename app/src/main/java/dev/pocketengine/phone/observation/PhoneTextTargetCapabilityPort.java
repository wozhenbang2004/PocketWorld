package dev.pocketengine.phone.observation;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A current OCR target is another original phone action, not a new runner, grant or approval store. */
public final class PhoneTextTargetCapabilityPort implements CapabilityPort {
    private final CapabilityPort existing;
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final RunControl original;
    private final AdaptivePhonePort phone;
    private final TaskStoreCapabilityAuthority authority;
    private boolean closed;
    private PhoneTextTargetCapabilityPort(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,
            RunControl original,AdaptivePhonePort phone,EventSink approvalEvents) {
        this.existing=Objects.requireNonNull(existing);this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);
        this.original=Objects.requireNonNull(original);this.phone=Objects.requireNonNull(phone);authority=new TaskStoreCapabilityAuthority(tasks,binding,approvalEvents);
        if(existing.supports(AdaptivePhonePort.CLICK_TEXT))throw new IllegalArgumentException("文字目标工具名称重复");
    }
    public static CapabilityPort bindIfAvailable(CapabilityPort existing,TaskStore tasks,ModelCapabilityAdapters.Binding binding,
            RunControl original,PhonePort selected,EventSink approvalEvents)throws Exception {
        if(!(selected instanceof AdaptivePhonePort)||!binding.allowWrites)return existing;
        AdaptivePhonePort phone=(AdaptivePhonePort)selected;JSONObject task=tasks.get(binding.taskId,false);
        if(task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background")
                ||!phone.matchesHostScope(binding.taskId,binding.runId,binding.epoch)||!phone.isConnected()||!phone.textTargetingAvailable())return existing;
        original.check();
        if(original.isPaused()||!original.hasHostRun()||!original.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("文字目标需要原手机执行控制");
        return new PhoneTextTargetCapabilityPort(existing,tasks,binding,original,phone,approvalEvents);
    }
    private boolean own(String name){return AdaptivePhonePort.CLICK_TEXT.equals(name);}
    private void check(RunControl control)throws Exception {
        if(closed||control!=original)throw new SecurityException("文字目标不属于原任务控制");authority.check(AdaptivePhonePort.CLICK_TEXT,control);
        JSONObject task=tasks.get(binding.taskId,false);
        if(!binding.allowWrites||task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background"))
            throw new SecurityException("仅原手机任务可操作当前文字目标");
    }
    private JSONArray append(JSONArray base)throws Exception {
        JSONArray tools=new JSONArray(base.toString());
        JSONObject properties=new JSONObject().put("observationId",new JSONObject().put("type","string").put("maxLength",100))
                .put("textTargetId",new JSONObject().put("type","string").put("maxLength",36));
        tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",AdaptivePhonePort.CLICK_TEXT)
                .put("description","点击本轮OCR文字中的原生textTargetId；必须同时给该observationId。原生复核原截图/会话后定位，不接受坐标或任意文字搜索。OCR可能识错且文字不证明可点击；不确定时请求主人处理。仅表示平台接收，随后观察核验。")
                .put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray().put("observationId").put("textTargetId")).put("additionalProperties",false))));
        return tools;
    }
    @Override public JSONArray tools()throws Exception{return append(existing.tools());}
    @Override public boolean supports(String name){return own(name)||existing.supports(name);}
    @Override public boolean sideEffecting(String name){return own(name)||existing.sideEffecting(name);}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!own(name)){existing.validate(name,args);return;}
        if(args==null||args.length()!=2||!(args.opt("observationId") instanceof String)||args.getString("observationId").isEmpty()
                ||args.getString("observationId").length()>100||!(args.opt("textTargetId") instanceof String))throw new IllegalArgumentException("请使用本轮原生文字目标引用");
        if(!UUID.fromString(args.getString("textTargetId")).toString().equals(args.getString("textTargetId")))throw new IllegalArgumentException("文字目标引用无效");
    }
    @Override public synchronized JSONObject execute(String name,JSONObject input,RunControl control)throws Exception {
        if(!own(name))return existing.execute(name,input,control);
        JSONObject args=new JSONObject(input.toString());Object raw=args.opt("_hostOperationId");args.remove("_hostOperationId");validate(name,args);
        if(!(raw instanceof String)||!UUID.fromString((String)raw).toString().equals(raw))throw new SecurityException("需要原AgentRunner预留的operation");String operation=(String)raw;
        NativePhoneEntry.Admission admission=()->{check(control);authority.requirePrepared(operation,name,new JSONObject(args.toString()));};
        try{admission.check();}catch(Exception refused){return new JSONObject().put("ok",false).put("accepted",false).put("verified",false).put("code","needs_user")
                .put("requiresPause",true).put("automaticReplayAllowed",false).put("message","原任务、权限或预留操作已变化，文字点击尚未派发。");}
        return phone.tapTextTarget(args,control,admission).put("operationId",operation).put("verifiedBusinessSuccess",false);
    }
    @Override public JSONObject contextSnapshot(String query)throws Exception{return existing.contextSnapshot(query);}
    @Override public void beginRun(String goal,RunControl control)throws Exception{check(control);existing.beginRun(goal,control);}
    @Override public void admitCapabilities(dev.pocketengine.core.CapabilitySnapshot snapshot,RunControl control)throws Exception{check(control);existing.admitCapabilities(snapshot,control);}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception{check(control);JSONObject turn=existing.prepareTurn(query,round,steps,control);return turn.put("tools",append(turn.getJSONArray("tools")));}
    @Override public boolean responseCurrent(RunControl control)throws Exception{check(control);return existing.responseCurrent(control);}
    @Override public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{if(own(name))check(control);return existing.beforeTool(name,args,control);}
    @Override public void afterTool(String name,JSONObject args,JSONObject receipt,RunControl control)throws Exception{existing.afterTool(name,args,receipt,control);}
    @Override public void beforeModelRequest(String requestId,JSONArray messages,RunControl control)throws Exception{check(control);existing.beforeModelRequest(requestId,messages,control);}
    @Override public JSONObject afterModelResponse(String requestId,String answerId,RunControl control)throws Exception{check(control);return existing.afterModelResponse(requestId,answerId,control);}
    @Override public void modelRequestAbandoned(String requestId)throws Exception{existing.modelRequestAbandoned(requestId);}
    @Override public void endRun(RunControl control)throws Exception{existing.endRun(control);}
    @Override public synchronized void close(){if(closed)return;closed=true;phone.clearReferences();existing.close();}
}
