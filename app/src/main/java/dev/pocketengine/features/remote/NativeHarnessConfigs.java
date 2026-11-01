package dev.pocketengine.features.remote;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original native owner configuration workflow; never an EngineModule/model command or a Runner. */
public final class NativeHarnessConfigs {
    private static final Set<String> COMMANDS=Set.of("openHarnessConfig","previewHarnessConfig","confirmHarnessConfig","queryHarnessConfig","cancelHarnessConfigPreview");
    private static final class Window {
        final String id,task;final Object owner;final JSONObject selection;
        volatile boolean closed,busy;volatile long revision;String profileBinding,operation;JSONObject source,probe,record,plan,receipt;
        Window(String id,String task,Object owner,JSONObject selection){this.id=id;this.task=task;this.owner=owner;this.selection=selection;}
    }
    /** Ephemeral exact native foreground approval. Its JSON view cannot recreate this object. */
    public static final class Approval {
        private final NativeHarnessConfigs host;private final Window window;private final TaskStore issuer;
        private final long revision;private final String value;
        private Approval(NativeHarnessConfigs host,Window window)throws Exception {
            this.host=host;this.window=window;issuer=host.tasks;revision=window.revision;
            value=new JSONObject().put("taskId",window.task).put("operationId",window.operation).put("source",window.source).put("plan",window.plan).toString();
        }
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public boolean issuedBy(TaskStore store){return issuer==store;}
        public void checkCurrent()throws Exception {
            if(window.closed||window.owner==null||host.foregroundOwner!=window.owner||window.revision!=revision||window.plan==null||window.plan.optLong("expiresAt")<=System.currentTimeMillis())throw new SecurityException("原配置审阅已关闭、切后台、过期或被替换；没有派发");
        }
    }
    /** Read-only native original-endpoint observation; grants no dispatch or run authority. */
    public static final class Observation {
        private final NativeHarnessConfigs host;private final Window window;private final TaskStore issuer;private final long revision;private final String value;private final boolean cancel;
        private Observation(NativeHarnessConfigs host,Window window,JSONObject actual,boolean cancel)throws Exception {this.host=host;this.window=window;issuer=host.tasks;revision=window.revision;this.cancel=cancel;value=new JSONObject().put("taskId",window.task).put("operationId",window.operation).put("actual",actual).toString();}
        public boolean issuedBy(TaskStore store){return issuer==store;}
        public void checkCurrent()throws Exception {host.current(window,revision);}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public boolean cancelPrepared(){return cancel;}
    }
    private final FeatureHost features;private final TaskStore tasks;private final EventSink events;
    private final Map<String,Window> windows=new LinkedHashMap<>();private volatile Object foregroundOwner;
    public NativeHarnessConfigs(FeatureHost features,TaskStore tasks,EventSink events){this.features=features;this.tasks=tasks;this.events=events;}
    public static boolean command(String command){return COMMANDS.contains(command);}
    public synchronized void setForeground(Object owner,boolean foreground){Object next=foreground?owner:foregroundOwner==owner?null:foregroundOwner;if(next!=foregroundOwner){foregroundOwner=next;for(Window window:windows.values()){window.revision++;window.plan=null;}}}
    public synchronized void close(String id){Window window=windows.remove(id);if(window!=null){window.closed=true;window.revision++;window.plan=null;}}
    private RemoteModule remote(){Object module=features.module("remote");if(!(module instanceof RemoteModule))throw new IllegalStateException("原电脑模块不可用");return (RemoteModule)module;}
    private void current(Window window,long revision)throws Exception {if(window.closed||window.owner==null||foregroundOwner!=window.owner||window.revision!=revision)throw new SecurityException("原配置页面已失效；迟到结果不成为新确认");}
    public JSONObject handle(Object owner,String command,JSONObject input)throws Exception {
        Set<String> allowed=command.equals("openHarnessConfig")?Set.of("windowId","taskId","selection","operationId"):command.equals("confirmHarnessConfig")?Set.of("windowId","taskId","acceptedPlanDigest"):Set.of("windowId","taskId");
        for(java.util.Iterator<String> keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("配置审阅不接受路径、凭据、执行许可或未审字段");
        String id=input.getString("windowId"),task=input.getString("taskId");Window window;
        synchronized(this){
            if(command.equals("openHarnessConfig")){
                if(owner==null||foregroundOwner!=owner||!id.matches("[a-f0-9-]{36}")||windows.containsKey(id)||windows.size()>=4)throw new SecurityException("须由真实前台Activity打开原任务配置审阅");
                JSONObject selection=input.optJSONObject("selection");
                if(input.has("operationId")){if(selection!=null)throw new SecurityException("核对原操作不能改目标");selection=new JSONObject();}
                else validateSelection(selection);
                window=new Window(id,task,owner,selection==null?new JSONObject():new JSONObject(selection.toString()));window.operation=input.optString("operationId",null);windows.put(id,window);
            }else window=windows.get(id);
            if(window==null||window.owner!=owner||!window.task.equals(task))throw new SecurityException("不是原任务/Activity的配置页面");current(window,window.revision);
            if(window.busy)throw new IllegalStateException("正在核对原配置操作，不并发第二次写入");window.busy=true;
        }
        try{
            if(command.equals("openHarnessConfig")){if(window.operation!=null)query(window);else window.source=tasks.remoteOwnerTarget(task);return response(window);}
            if(command.equals("previewHarnessConfig"))return preview(window);
            if(command.equals("queryHarnessConfig")){query(window);return response(window);}
            if(command.equals("cancelHarnessConfigPreview")){query(window);tasks.recordHarnessConfigReceipt(new Observation(this,window,window.receipt,true));window.plan=null;window.receipt=tasks.harnessConfigOperation(window.task,window.operation).optJSONObject("receipt");return response(window);}
            if(command.equals("confirmHarnessConfig"))return confirm(window,input.getString("acceptedPlanDigest"));
            throw new IllegalArgumentException("未知配置审阅动作");
        }finally{synchronized(this){window.busy=false;}}
    }
    private static void validateSelection(JSONObject selection)throws Exception {
        if(selection==null)throw new SecurityException("请选择准确Harness目标与已批准的gateway引用");
        Set<String> allowed=selection.has("restoreOperationId")?Set.of("bindingId","restoreOperationId"):Set.of("bindingId","model","profileDigest","endpointLeaseRef");
        if(selection.length()!=allowed.size())throw new SecurityException("所选配置引用不完整");for(String key:allowed)if(!selection.has(key)||!(selection.get(key) instanceof String)||selection.getString(key).isBlank()||selection.getString(key).length()>200)throw new SecurityException("配置选择仅接受有界引用");
        for(String key:new String[]{"bindingId","endpointLeaseRef","restoreOperationId"})if(selection.has(key)&&!selection.getString(key).matches("[A-Za-z0-9_-]{1,80}"))throw new SecurityException("配置引用身份无效");
        if(selection.has("profileDigest")&&!selection.getString("profileDigest").matches("[a-f0-9]{64}"))throw new SecurityException("Profile须固定准确摘要");
    }
    private JSONObject transport(Window window)throws Exception {
        JSONObject source=window.record==null?window.source:window.record.getJSONObject("source");
        String binding=window.profileBinding==null?remote().profileBindingHash(source.getString("profileId")):window.profileBinding;
        JSONObject profile=remote().requireProfileBinding(source.getString("profileId"),binding);
        if(!source.getString("serviceBase").equals(profile.getString("serviceBase")))throw new SecurityException("原电脑地址已改变，未向新地址发送配置");
        return new JSONObject().put("profileId",source.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("_profileBinding",binding);
    }
    private JSONObject preview(Window window)throws Exception {
        long revision=window.revision;current(window,revision);
        if(window.operation!=null){query(window);return response(window);}
        JSONObject source=tasks.remoteOwnerTarget(window.task);window.source=source;
        if(!source.optString("executorStatus").matches("completed|failed|cancelled"))throw new SecurityException("请先结算原电脑任务；本操作不会接管正在运行的CLI");
        window.profileBinding=remote().profileBindingHash(source.getString("profileId"));JSONObject input=transport(window);
        JSONObject environments=remote().handle("environments",new JSONObject(input.toString()));
        JSONObject env=RemoteTaskRequest.environment(environments,source.getString("environmentId"),source.getString("worker"));
        JSONObject target=new JSONObject().put("environmentId",source.getString("environmentId")).put("environmentBinding",env.getString("bindingHash")).put("bindingId",window.selection.getString("bindingId"));
        JSONObject probe=remote().harnessConfigProbe(new JSONObject(input.toString()).put("request",target));current(window,revision);
        if(!probe.getJSONObject("capabilities").optBoolean("configWritable")||probe.optString("identityDigest").isBlank())throw new SecurityException("目标schema、覆盖优先级或配置写权未验证；保持只读");
        JSONObject request;
        String restore=window.selection.optString("restoreOperationId",null);
        if(restore!=null){JSONObject original=tasks.harnessConfigOperation(window.task,restore);request=new JSONObject(original.getJSONObject("request").toString());}
        else request=new JSONObject(window.selection.toString());
        request.remove("restoreOperationId");for(String key:new String[]{"environmentId","environmentBinding","bindingId"})request.put(key,target.get(key));
        request.put("identityDigest",probe.getString("identityDigest")).put("taskId",window.task).put("runId",source.getString("runId")).put("epoch",source.getInt("coordinatorEpoch"));request.remove("operationId");
        window.probe=probe;window.record=tasks.prepareHarnessConfigOperation(window.task,source,request,probe,restore);window.operation=window.record.getString("operationId");
        JSONObject sent=new JSONObject(input.toString()).put("request",window.record.getJSONObject("request"));if(restore!=null)sent.put("restoreOf",restore);
        JSONObject plan=remote().harnessConfigPlan(sent);current(window,revision);validatePlan(plan,window.record);window.plan=plan;return response(window);
    }
    private JSONObject confirm(Window window,String accepted)throws Exception {
        long revision=window.revision;current(window,revision);if(window.plan==null||!accepted.equals(window.plan.optString("planDigest")))throw new SecurityException("只可确认屏幕上的准确配置计划，不接受不同digest");
        Approval approval=new Approval(this,window);TaskStore.HarnessConfigClaim claim=tasks.claimHarnessConfigOperation(approval);
        if(!claim.canDispatch()){query(window);return response(window);}
        JSONObject record=claim.view(),request=record.getJSONObject("request"),plan=window.plan;
        JSONObject body=new JSONObject().put("operationId",window.operation).put("planDigest",plan.getString("planDigest"));for(String key:new String[]{"environmentId","environmentBinding","bindingId"})body.put(key,request.get(key));
        JSONObject actual;try{actual=remote().harnessConfigApply(transport(window).put("planId",plan.getString("planId")).put("request",body),claim::beforeDispatch);}
        catch(java.io.IOException unavailable){actual=unknown(window);}
        tasks.recordHarnessConfigReceipt(claim,actual);current(window,revision);window.receipt=actual;emit(window.task);return response(window);
    }
    private void query(Window window)throws Exception {
        long revision=window.revision;current(window,revision);window.record=tasks.harnessConfigOperation(window.task,window.operation);window.source=window.record.getJSONObject("source");window.probe=window.record.optJSONObject("target");
        if(window.record.optJSONObject("receipt")!=null&&window.record.getJSONObject("receipt").optString("state").equals("CANCELLED_PREVIEW")){window.receipt=window.record.getJSONObject("receipt");window.plan=null;return;}
        window.profileBinding=remote().profileBindingHash(window.source.getString("profileId"));JSONObject actual=remote().harnessConfigOperation(transport(window).put("operationId",window.operation));current(window,revision);
        if(actual.optJSONObject("plan")!=null){JSONObject plan=actual.getJSONObject("plan");validatePlan(plan,window.record);window.plan=plan;}
        tasks.recordHarnessConfigReceipt(new Observation(this,window,actual,false));
        // A query is observation only. It cannot manufacture the ephemeral dispatch claim.
        window.receipt=actual;
    }
    private static JSONObject unknown(Window window)throws Exception {JSONObject value=new JSONObject().put("operationId",window.operation).put("planDigest",window.plan.getString("planDigest")).put("state","unknown").put("configApplied",false).put("runtimeVerified",false);for(String key:new String[]{"environmentId","environmentBinding","bindingId"})value.put(key,window.record.getJSONObject("request").get(key));return value;}
    public static void validatePlan(JSONObject plan,JSONObject record)throws Exception {
        JSONObject request=record.getJSONObject("request"),identity=plan.getJSONObject("identity");
        if(!record.getString("operationId").equals(plan.optString("operationId"))||!plan.optString("planId").matches("[A-Za-z0-9_-]{1,80}")||!plan.optString("planDigest").matches("[a-f0-9]{64}")||!plan.optString("writeConsistency").equals("OPTIMISTIC")||plan.optBoolean("runtimeVerified")||plan.getJSONArray("filePlans").length()!=1)throw new SecurityException("不是受支持的原配置计划");
        for(String key:new String[]{"environmentBinding","bindingId"})if(!request.get(key).equals(identity.opt(key)))throw new SecurityException("计划返回了另一准确环境/Harness");
        if(!request.getString("environmentId").equals(plan.optString("environmentId"))||!request.getString("profileDigest").equals(plan.optString("profileDigest"))||!request.getString("endpointLeaseRef").equals(plan.optString("endpointLeaseRef"))||!"GATEWAY_ACCESS".equals(plan.optString("credentialRefType")))throw new SecurityException("计划模型Profile或gateway引用不同");
        JSONObject gateway=plan.getJSONObject("gatewayScope");if(!gateway.optString("audience").equals("OWNER_PRIVATE")||!gateway.optString("purpose").equals("EXTERNAL_RELAY")||gateway.opt("billingBackendVerified")!=Boolean.FALSE)throw new SecurityException("配置计划不能变成群API-only计费证明或APP_OWNER权限");
        String expected=identity.getString("family").equals("codex")?"config.toml":identity.getString("family").equals("claude")?"settings.json":"";
        if(expected.isEmpty()||!expected.equals(plan.getJSONArray("filePlans").getJSONObject(0).optString("pathRef")))throw new SecurityException("计划文件不属于已知adapter");
        if(plan.toString().length()>65536)throw new SecurityException("配置计划超出有界审阅");
    }
    private JSONObject response(Window window)throws Exception {
        JSONObject result=new JSONObject().put("windowId",window.id).put("taskId",window.task).put("configurationOnly",true).put("executionAuthorized",false).put("runtimeVerified",false)
            .put("risk","将替换所示目标的有效认证/endpoint字段，仅使用有期限的gateway引用；不复制ChatGPT会话凭据，不授权运行。关闭页面不自动恢复。外部修改会保留并阻止不安全恢复。")
            .put("message",window.receipt==null?"先核对真实目标与逐字段差异，再确认准确计划":"以原operationId查询真实回执，不盲重发");
        if(window.source!=null)result.put("source",window.source);if(window.operation!=null)result.put("operationId",window.operation);if(window.record!=null)result.put("operationState",window.record.optString("state"));if(window.probe!=null)result.put("target",window.probe);if(window.plan!=null)result.put("plan",new JSONObject(window.plan.toString()));if(window.receipt!=null)result.put("receipt",new JSONObject(window.receipt.toString()));return new JSONObject().put("snapshot",result);
    }
    private void emit(String task)throws Exception {events.emit(new JSONObject().put("type","task_changed").put("taskId",task));}
}
