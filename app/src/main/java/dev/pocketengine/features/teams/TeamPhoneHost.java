package dev.pocketengine.features.teams;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.devices.DeviceGoalPhonePort;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DeviceWorkspaceNativeOwner;
import dev.pocketengine.features.devices.NativeDeviceTaskScopeHost;
import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner review and adapter for explicit phone work. The existing Coordinator, TaskStore,
 * PhoneRunService and paired-device route remain the only scheduler, ledger and executors. */
public final class TeamPhoneHost implements TeamCoordinator.PhoneFactory {
    @FunctionalInterface public interface Definition { JSONObject read(String id)throws Exception; }
    private final EngineApplication app;
    private final TaskStore tasks;
    private final DeviceWorkspaceNativeOwner devices;
    @FunctionalInterface interface NativeLocalOptions { JSONArray read()throws Exception; }
    private final NativeLocalOptions localOptions;
    interface OriginalPhoneRuns {
        JSONObject start(Admission admission)throws Exception;
        boolean active(String task,RunControl original);
        void pause(String task,String run,int epoch)throws Exception;
        void resume(String task,String run,int epoch)throws Exception;
        void stop(String task,String run,int epoch)throws Exception;
    }
    private final OriginalPhoneRuns phoneRuns;
    private final ConcurrentHashMap<String,Admission> active=new ConcurrentHashMap<>();
    public TeamPhoneHost(EngineApplication app,DeviceWorkspaceNativeOwner devices){this(app,devices,devices::localGrantOptions);}
    TeamPhoneHost(EngineApplication app,DeviceWorkspaceNativeOwner devices,NativeLocalOptions nativeOptions){this(app,devices,nativeOptions,new OriginalPhoneRuns(){
        public JSONObject start(Admission admission)throws Exception{return app.admitTeamPhoneRun(admission);}
        public boolean active(String task,RunControl original){return app.isBackgroundPhoneRun(task,original);}
        public void pause(String task,String run,int epoch)throws Exception{app.pauseOriginalPhoneRun(task,run,epoch);}
        public void resume(String task,String run,int epoch)throws Exception{app.resumeOriginalPhoneRun(task,run,epoch);}
        public void stop(String task,String run,int epoch)throws Exception{app.stopOriginalPhoneRun(task,run,epoch);}
    });}
    TeamPhoneHost(EngineApplication app,DeviceWorkspaceNativeOwner devices,NativeLocalOptions nativeOptions,OriginalPhoneRuns originalRuns){this.app=java.util.Objects.requireNonNull(app);this.tasks=app.tasks;this.devices=java.util.Objects.requireNonNull(devices);localOptions=java.util.Objects.requireNonNull(nativeOptions);phoneRuns=java.util.Objects.requireNonNull(originalRuns);}

    private record Option(String id,String label,String device,String resource,String tools,int toolMax,int modelMax,long expiry,NativeDeviceTaskScopeHost.RouteOption route) {
        JSONObject view()throws Exception{return new JSONObject().put("optionId",id).put("label",label).put("resourceBinding",new JSONObject(resource)).put("toolNames",new JSONArray(tools)).put("toolBudgetMax",toolMax).put("modelBudgetMax",modelMax).put("expiresAtMax",expiry).put("available",true);}
    }
    private List<Option> options(JSONObject device)throws Exception {
        java.util.ArrayList<Option> result=new java.util.ArrayList<>();String kind=device.getString("kind");
        if(kind.equals("local_phone")){
            JSONArray candidates=localOptions.read();for(int i=0;i<candidates.length();i++){
                JSONObject candidate=candidates.getJSONObject(i),resource=candidate.getJSONArray("resources").getJSONObject(0);
                if(!candidate.getString("optionId").startsWith("goal_")||!"background".equals(resource.optString("screenMode")))continue;
                result.add(new Option(candidate.getString("optionId"),candidate.getString("label"),DeviceProtocol.canonical(device),DeviceProtocol.canonical(resource),candidate.getJSONArray("toolNames").toString(),100,32,candidate.getLong("expiresAtMax"),null));
            }
        }else if(kind.equals("paired_android")){
            for(var route:devices.taskScopeHost().forwardingOptions())if(route.kind().equals("goal")&&route.receivingGrant().targetDeviceId().equals(device.getString("targetDeviceId"))&&route.resourceBinding().optString("screenMode").matches("main_screen|background")){
                JSONObject grant=route.receivingGrant().grant();result.add(new Option(route.optionId(),"配对手机 · "+route.resourceBinding().getString("screenMode"),DeviceProtocol.canonical(device),DeviceProtocol.canonical(route.resourceBinding()),grant.getJSONArray("toolNames").toString(),Math.min(68,grant.getInt("toolBudget")),Math.min(32,grant.getInt("modelBudget")),route.receivingGrant().deadline(),route));
            }
        }
        return List.copyOf(result);
    }
    public JSONArray targets()throws Exception {
        JSONArray targets=new JSONArray();JSONObject readiness=app.phoneReadiness().optJSONObject("backgroundPreparation");
        targets.put(new JSONObject().put("kind","local_phone").put("screenMode","background").put("label","这台手机 · 独立后台屏").put("available",readiness!=null&&readiness.optBoolean("ready")));
        java.util.HashSet<String> seen=new java.util.HashSet<>();for(var grant:devices.nativeOutgoingGrants())if(seen.add(grant.targetDeviceId()))targets.put(new JSONObject().put("kind","paired_android").put("targetDeviceId",grant.targetDeviceId()).put("label","配对 Android · "+DeviceProtocol.deviceId(grant.targetDeviceId(),"targetDeviceId").substring(4,12)).put("available",true).put("requiresFreshScopeReview",true));
        return targets;
    }
    public Page open(TeamCoordinator coordinator,Definition definitions,String teamId,String goal,int rounds,String conversationId,ModelConfig model,TeamCoordinator.EvidenceFactory evidence,ExternalMemberFactory external)throws Exception{return new Page(coordinator,definitions,teamId,goal,rounds,conversationId,model,evidence,external);}
    public final class Page implements AutoCloseable {
        private final TeamCoordinator coordinator;private final Definition definitions;private final String definition,teamId,goal,conversation;private final int rounds;
        private final ModelConfig model;private final TeamCoordinator.EvidenceFactory evidence;private final ExternalMemberFactory external;
        private final Map<String,List<Option>> choices=new LinkedHashMap<>();private final java.util.concurrent.atomic.AtomicInteger pageState=new java.util.concurrent.atomic.AtomicInteger();private Review pending;
        private Page(TeamCoordinator coordinator,Definition definitions,String id,String goal,int rounds,String conversation,ModelConfig model,TeamCoordinator.EvidenceFactory evidence,ExternalMemberFactory external)throws Exception {
            this.coordinator=coordinator;this.definitions=definitions;this.teamId=id;this.goal=goal;this.rounds=rounds;this.conversation=conversation==null?"":conversation;this.model=model;this.evidence=evidence;this.external=external;
            JSONObject team=definitions.read(id);definition=team.toString();if(!"collaborate".equals(team.optString("mode"))||rounds!=1||goal==null||goal.isBlank()||goal.length()>8000)throw new SecurityException("手机协作每次由主人确认一轮准确分工和目标");
            JSONArray members=team.getJSONArray("members");for(int i=0;i<members.length();i++){JSONObject member=members.getJSONObject(i);if("phone_agent".equals(member.optString("runner")))choices.put(member.getString("id"),options(member.getJSONObject("device")));}
            if(choices.isEmpty())throw new SecurityException("此团队没有明确手机工作成员");
            for(int i=0;i<members.length();i++)if("phone_agent".equals(members.getJSONObject(i).optString("runner"))&&"local_phone".equals(members.getJSONObject(i).getJSONObject("device").optString("kind"))&&(model==null||model.usesAccount()))throw new SecurityException("本机手机成员需要先在设置中选择已配置的 API 模型；目标手机成员由目标机确认模型");
        }
        private void check()throws Exception {if(pageState.get()!=0||!definition.equals(definitions.read(teamId).toString()))throw new SecurityException("原团队定义或本次手机范围页已变化，请重新审阅");}
        public synchronized JSONObject view()throws Exception {
            check();JSONObject team=new JSONObject(definition);JSONArray members=new JSONArray();for(int i=0;i<team.getJSONArray("members").length();i++){JSONObject member=team.getJSONArray("members").getJSONObject(i);List<Option> selected=choices.get(member.getString("id"));if(selected==null)continue;JSONArray rows=new JSONArray();for(Option option:selected)rows.put(option.view());members.put(new JSONObject().put("memberId",member.getString("id")).put("name",member.getString("name")).put("assignment",member.getString("assignment")).put("device",member.getJSONObject("device")).put("options",rows));}
            return new JSONObject().put("teamId",teamId).put("definition",team).put("definitionDigest",DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(team))).put("goal",goal).put("rounds",rounds).put("members",members).put("executionAuthorized",false);
        }
        public synchronized Review review(JSONObject input)throws Exception {
            check();if(input.length()!=1)throw new SecurityException("手机范围仅接受本页逐成员选择");JSONArray rows=input.getJSONArray("members");if(rows.length()!=choices.size())throw new SecurityException("请为全部手机成员选择准确范围");Map<String,Binding> bindings=new LinkedHashMap<>();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.length()!=5)throw new SecurityException("手机范围字段无效");String member=row.getString("memberId");List<Option> candidates=choices.get(member);Option selected=null;for(Option option:candidates==null?List.<Option>of():candidates)if(option.id.equals(row.getString("optionId")))selected=option;if(selected==null||bindings.containsKey(member))throw new SecurityException("范围不是本页原生候选");
                int tools=(int)DeviceProtocol.integer(row.opt("toolBudget"),"toolBudget",1,selected.toolMax),models=(int)DeviceProtocol.integer(row.opt("modelBudget"),"modelBudget",1,selected.modelMax);long expires=DeviceProtocol.integer(row.opt("expiresAt"),"expiresAt",System.currentTimeMillis()+1000,selected.expiry);bindings.put(member,new Binding(selected,tools,models,expires));}
            pending=new Review(this,new Approval(TeamPhoneHost.this,definition,goal,rounds,conversation,model,bindings));return pending;
        }
        public synchronized JSONObject confirm(Review review)throws Exception {
            check();if(review==null||review!=pending||review.page!=this)throw new SecurityException("不是本页准确原生审阅");
            for(var entry:review.approval.bindings.entrySet()){Binding binding=entry.getValue();boolean found=false;for(Option actual:options(new JSONObject(binding.option.device)))if(actual.id.equals(binding.option.id)&&actual.resource.equals(binding.option.resource)&&actual.tools.equals(binding.option.tools))found=true;if(!found)throw new SecurityException("原设备授权或实际范围已变化");}
            review.approval.checkModel();check();if(!pageState.compareAndSet(0,2))throw new SecurityException("原手机审阅页已关闭");String operation=UUID.randomUUID().toString();tasks.beginExternal("teams","start",operation,new JSONObject().put("teamId",teamId).put("goal",goal).put("rounds",rounds),conversation);
            try{JSONObject result=coordinator.startPhones(new JSONObject(definition),goal,rounds,model,operation,review.approval,evidence,external);pending=null;return result;}
            catch(Exception failure){try{tasks.status(operation,"failed","本次原生手机协作未完成启动，请核对原记录；没有自动重提");}catch(Exception ignored){}throw failure;}
        }
        public void close(){pageState.compareAndSet(0,1);}
    }
    public static final class Review {
        private final Page page;private final Approval approval;
        private Review(Page page,Approval approval){this.page=page;this.approval=approval;}
        public JSONObject view()throws Exception{return approval.view().put("executionAuthorized",false);}
    }
    public static final class Binding {
        private final Option option;private final int tools,models;private final long expires;
        private Binding(Option option,int tools,int models,long expires){this.option=option;this.tools=tools;this.models=models;this.expires=expires;}
        public boolean remote(){return option.route!=null;}
        public JSONObject resourceBinding()throws Exception{return new JSONObject(option.resource);}
        public JSONArray toolNames()throws Exception{return remote()?new JSONArray().put("device_delegate_goal").put("device_operation_get"):new JSONArray(option.tools);}
        public int toolBudget(){return tools;}public int sourceToolBudget(){return tools+(remote()?32:0);}public int modelBudget(){return models;}public long expiresAt(){return expires;}
        public JSONObject view()throws Exception{return option.view().put("device",new JSONObject(option.device)).put("toolBudget",tools).put("modelBudget",models).put("expiresAt",expires).put("sourceQueryToolReserve",remote()?32:0);}
    }
    public static final class Approval {
        private final TeamPhoneHost host;private final String definition,goal,conversation;private final int rounds;private final ModelConfig model;private final Map<String,Binding> bindings;private String installedRun;
        private Approval(TeamPhoneHost host,String definition,String goal,int rounds,String conversation,ModelConfig model,Map<String,Binding> bindings){this.host=host;this.definition=definition;this.goal=goal;this.rounds=rounds;this.conversation=conversation;this.model=model;this.bindings=Map.copyOf(bindings);}
        public void requireStart(JSONObject team,String goal,int rounds)throws Exception{if(installedRun!=null||!definition.equals(team.toString())||!this.goal.equals(goal)||this.rounds!=rounds)throw new SecurityException("原手机协作审阅已消费或目标发生变化");for(String member:bindings.keySet())requireMember(member);}
        public Binding requireMember(String id)throws Exception {Binding binding=bindings.get(id);if(binding==null||binding.expires<=System.currentTimeMillis())throw new SecurityException("没有本次准确成员范围或已过期");if(binding.remote())binding.option.route.receivingGrant().checkCurrent(System.currentTimeMillis());return binding;}
        private void checkModel()throws Exception {if(bindings.values().stream().anyMatch(binding->!binding.remote())){if(model==null||model.usesAccount())throw new SecurityException("本机手机成员需要先在设置中选择已配置的 API 模型；不会借用未经确认的账号推理范围");ModelConfig current=host.app.settings.currentForCaptured(model);if(!dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(model).equals(dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(current))||!java.security.MessageDigest.isEqual(model.apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),current.apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new SecurityException("原生模型配置已变化，请重新审阅");}}
        public JSONObject view()throws Exception {JSONObject team=new JSONObject(definition);JSONArray members=new JSONArray();for(int i=0;i<team.getJSONArray("members").length();i++){JSONObject member=team.getJSONArray("members").getJSONObject(i);Binding binding=bindings.get(member.getString("id"));if(binding!=null)members.put(binding.view().put("memberId",member.getString("id")).put("name",member.getString("name")).put("assignment",member.getString("assignment")));}return new JSONObject().put("teamId",team.getString("id")).put("goal",goal).put("rounds",rounds).put("conversationId",conversation).put("members",members).put("model",model==null?"":model.model).put("budgetAutoRefill",false);}
        public void install(TeamCoordinator.InitialBudgetPlan plan)throws Exception {if(installedRun!=null)throw new SecurityException("原手机批准只可启动一次");plan.checkCurrent();checkModel();host.tasks.deviceTasks().installTeamPhonePlan(plan,this);installedRun=plan.view().getString("id");}
        public JSONObject budgetSnapshot()throws Exception{return host.tasks.teamBudgetSnapshot(installedRun);}
        public void requireRun(String id)throws Exception{if(!id.equals(installedRun))throw new SecurityException("不是本次原生批准的团队运行");}
    }
    public static final class Admission {
        private final TeamPhoneHost host;private final TeamCoordinator.PhoneCall call;private final Binding binding;private final RunControl original;private final String task;
        private Admission(TeamPhoneHost host,TeamCoordinator.PhoneCall call,Binding binding,RunControl original,String task){this.host=host;this.call=call;this.binding=binding;this.original=original;this.task=task;}
        public String taskId(){return task;}public RunControl originalControl(){return original;}public ModelConfig frozenModel(){return call.approval().model;}
        public JSONObject resourceBinding()throws Exception{return binding.resourceBinding();}public JSONArray toolNames()throws Exception{return binding.toolNames();}
        public void checkRetained()throws Exception {call.checkRetained();call.approval().requireRun(call.teamRunId);call.approval().checkModel();host.tasks.deviceTasks().requireTeamPhoneCurrent(call,task,original);}
        public void checkCurrent()throws Exception {checkRetained();JSONObject row=host.tasks.get(task,false);if(!row.optString("status").matches("preparing|running|paused|pausing|awaiting_user"))throw new SecurityException("原手机执行已结束，不派发新操作");}
        public PhonePort scopedPhone(PhonePort physical)throws Exception {checkCurrent();return new DeviceGoalPhonePort(physical,binding.resourceBinding(),binding.toolNames(),original,this::checkCurrent);}
    }
    public RunControl currentControl(String task){Admission admission=active.get(task);return admission==null?null:admission.original;}
    private List<Admission> selectedActive(String teamRun,String member)throws Exception {
        java.util.ArrayList<Admission> selected=new java.util.ArrayList<>();for(Admission admission:active.values())if(admission.call.teamRunId.equals(teamRun)&&(member==null||member.equals(admission.call.memberId))){if(admission.binding.remote())throw new SecurityException("远端原任务需在目标设备暂停或继续；本页不能把本地等待当作目标已暂停，可使用停止核对原操作");selected.add(admission);}return selected;
    }
    @Override public void pause(String teamRun,String member)throws Exception {for(Admission admission:selectedActive(teamRun,member)){var run=admission.original.hostRunIdentity();phoneRuns.pause(admission.task,run.runId(),run.epoch());}}
    @Override public void resume(String teamRun,String member)throws Exception {for(Admission admission:selectedActive(teamRun,member)){admission.checkCurrent();var run=admission.original.hostRunIdentity();phoneRuns.resume(admission.task,run.runId(),run.epoch());}}
    @Override public JSONObject execute(TeamCoordinator.PhoneCall call,RunControl original,Consumer<JSONObject> progress)throws Exception {
        call.checkCurrent();Binding binding=call.approval().requireMember(call.memberId);call.approval().requireRun(call.teamRunId);
        JSONObject task=tasks.deviceTasks().prepareTeamPhone(call,binding,original);String id=task.getString("id");
        if(!task.optString("runId").isEmpty())return receipt(task).put("queryOnly",true).put("goalMatches",call.goal().equals(task.optString("goal"))).put("summary",task.optString("summary","此原手机分工已有执行，只核对原任务，不创建第二次运行"));
        if(binding.remote())try(var window=devices.taskScopeHost().open(id)){
            var choice=new NativeDeviceTaskScopeHost.RouteChoice(binding.option.id,binding.tools,binding.models,binding.expires,binding.tools,binding.models,List.of(),NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY);
            window.confirm(window.review(new NativeDeviceTaskScopeHost.Selection(List.of(choice))));
        }
        task=tasks.deviceTasks().startTeamPhone(call,id,original);Admission admission=new Admission(this,call,binding,original,id);active.put(id,admission);original.setAuthorityCheck(admission::checkRetained);
        progress.accept(receipt(task));
        try {
            if(binding.remote())return remote(admission,progress);
            phoneRuns.start(admission);
            for(;;){JSONObject observed=tasks.get(id,false);progress.accept(receipt(observed));if(observed.optString("status").matches("completed|failed|cancelled|interrupted|awaiting_user|awaiting_approval|needs_confirmation|blocked_unknown|reconciling")){if(!phoneRuns.active(id,original))return receipt(observed);Thread.sleep(50);continue;}original.sleep(500);}
        } catch(Exception failure) {
            original.cancel();
            if(binding.remote())try{tasks.status(id,"reconciling","原设备执行等待中断，正在核对并停止原子任务；保留原操作，不换 ID 重发");}catch(Exception unavailable){failure.addSuppressed(unavailable);}
            throw failure;
        } finally {
            active.remove(id,admission);
            if(original.isCancelled()){
                if(binding.remote()){try{devices.stopOriginalChildren(id,original);}catch(Exception unknown){tasks.status(id,"reconciling","原设备子任务停止尚待核对；保留原操作，不重新派发");}}
                else {var run=original.hostRunIdentity();phoneRuns.stop(id,run.runId(),run.epoch());}
            }
        }
    }
    private JSONObject remote(Admission admission,Consumer<JSONObject> progress)throws Exception {
        RunControl original=admission.original;Binding binding=admission.binding;var run=original.hostRunIdentity();RunJournal journal=tasks.journal(admission.task,run.runId(),run.epoch(),original);
        try(CapabilityPort port=devices.originalTaskDeviceTools(original)){
            if(port==null)throw new SecurityException("原设备路由尚未绑定");JSONObject args=new JSONObject().put("routeId",binding.option.id).put("targetDeviceId",new JSONObject(binding.option.device).getString("targetDeviceId")).put("goal",admission.call.goal()).put("toolBudget",binding.tools).put("modelBudget",binding.models);
            String operation=journal.beginOperation("device_delegate_goal",args);tasks.deviceTasks().recordTeamPhoneOperation(admission.call,admission.task,original,operation);JSONObject result=port.execute("device_delegate_goal",new JSONObject(args.toString()).put("_hostOperationId",operation),original);journal.finishOperation(operation,result);progress.accept(receipt(tasks.get(admission.task,false)).put("deviceReceipt",result));
            JSONObject query=new JSONObject().put("routeId",binding.option.id).put("targetDeviceId",args.getString("targetDeviceId")).put("operationId",operation);
            for(int i=0;i<30;i++){
                original.sleep(10000);String queryOperation=journal.beginOperation("device_operation_get",query);result=port.execute("device_operation_get",new JSONObject(query.toString()).put("_hostOperationId",queryOperation),original);journal.finishOperation(queryOperation,result);
                JSONObject typed=result.optJSONObject("deviceReceipt"),payload=typed==null?null:typed.optJSONObject("payload");progress.accept(receipt(tasks.get(admission.task,false)).put("deviceReceipt",result));
                if(payload!=null&&payload.optBoolean("terminal")&&payload.optBoolean("stopped")){String status="succeeded".equals(typed.optString("status"))?"completed":typed.optString("status").equals("cancelled")?"cancelled":"failed";tasks.endAttempt(admission.task,run.runId(),status,payload.optString("summary",payload.optString("message","已核对目标手机原任务终态")),result);return receipt(tasks.get(admission.task,false)).put("deviceReceipt",result);}
            }
            original.cancel();tasks.status(admission.task,"reconciling","本次原设备查询额度已用尽，已请求停止原子任务；回执未确认时只查原任务，不换 ID 重发");return receipt(tasks.get(admission.task,false)).put("queryOnly",true).put("deviceReceipt",result);
        }
    }
    private static JSONObject receipt(JSONObject task)throws Exception{return new JSONObject().put("taskId",task.getString("id")).put("runId",task.optString("runId")).put("executorEpoch",task.optInt("coordinatorEpoch")).put("status",task.optString("status")).put("summary",task.optString("summary")).put("supportedControls",new JSONObject().put("pause",!"paired_android".equals(task.optJSONObject("teamPhoneScope")==null?"":task.getJSONObject("teamPhoneScope").getJSONObject("device").optString("kind"))).put("cancel",true).put("queryOriginal",true)).put("businessVerified",false);}
}
