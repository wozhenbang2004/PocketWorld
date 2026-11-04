package dev.pocketengine.features.groups;

import dev.pocketengine.features.EngineModule;
import java.io.File;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native group service. It does not install a channel gateway or a parallel task executor. */
public final class GroupsModule implements EngineModule {
    private final GroupRegistry registry;
    private final GroupIngressPort ingress;
    private final GroupHostPort host;
    private final GroupDeliveryPort delivery;
    private final GroupOwnerAuthority owner;
    private final LongSupplier clock;
    private volatile boolean closed;
    private volatile dev.pocketengine.features.groups.host.GroupArtifactStore remoteInputArtifacts;

    public GroupsModule(File privateDirectory,GroupIngressPort ingress,GroupHostPort tasks,GroupDeliveryPort delivery,GroupOwnerAuthority owner) throws Exception {
        this(privateDirectory,ingress,tasks,delivery,owner,System::currentTimeMillis);
    }
    GroupsModule(File directory,GroupIngressPort ingress,GroupHostPort tasks,GroupDeliveryPort delivery,GroupOwnerAuthority owner,LongSupplier clock) throws Exception {
        this.ingress=ingress;host=tasks;this.delivery=delivery;this.owner=Objects.requireNonNull(owner);this.clock=Objects.requireNonNull(clock);registry=new GroupRegistry(directory);
    }
    @Override public String id() {return "groups";}
    /** Safe for the generic FeatureHost capability snapshot; contains no group identities or content. */
    @Override public JSONObject snapshot() throws Exception {
        open();return registry.counts().put("capabilities",new JSONObject().put("nativeGroupManagement",true).put("nativeOwnerRouteRequired",true)
                .put("authenticatedIngressMounted",ingress!=null).put("taskStorePortMounted",host!=null).put("deliveryPortMounted",delivery!=null)
                .put("groupTasksReady",ingress!=null&&host!=null).put("privatePhoneAccess",false).put("privateMemoryAccess",false)
                .put("qqMediaPlatformVerified",false).put("weixinGroups",false))
                .put("commands",new JSONArray(new String[]{"inspect"}));
    }
    @Override public JSONObject handle(String command,JSONObject input) throws Exception {
        if(command.equals("inspect"))return snapshot();
        throw new SecurityException("群管理与任务操作仅允许原生主人入口或认证群通道，通用feature JSON不持有权限");
    }
    public JSONObject ownerSnapshot() throws Exception {
        ownerCheck();return registry.ownerSnapshot(clock.getAsLong()).put("capabilities",snapshot().getJSONObject("capabilities"))
                .put("commands",new JSONArray(new String[]{"snapshot","group.create","policy.set","member.set","task.inspect","task.cancel","task.resume","delivery.send","delivery.inspect"}));
    }
    /** Host must call only from its verified native Activity owner route, on a bounded worker. */
    public JSONObject ownerCommand(String command,JSONObject input) throws Exception {
        ownerCheck();JSONObject value=GroupJson.copy(Objects.requireNonNull(input));long now=clock.getAsLong();
        switch(command) {
            case "snapshot":return ownerSnapshot();
            case "group.create":return registry.create(value,now);
            case "policy.set":return registry.policy(value,now);
            case "member.set":return registry.member(value,null,now);
            case "task.inspect":return inspectOwner(GroupJson.id(value,"requestId"));
            case "task.resume":{
                requireHost();GroupHostPort.Request request=registry.request(GroupJson.id(value,"requestId"));
                GroupHostPort.Authority read=ownerReadAuthority();GroupHostPort.TaskReceipt actual=host.inspect(request,read);read.checkRead();validate(request,actual);
                if(actual==null)throw new IllegalStateException("原群任务尚未知，只能核对，不能重新准入");registry.receipt(request.requestId,actual);
                boolean reviewed=value.has("runId")||value.has("coordinatorEpoch")||value.has("taskRevision");
                if(reviewed||actual.status.equals("interrupted"))reviewedResume(value,actual);
                if(actual.status.equals("interrupted"))return recoverOwner(request,actual);
                GroupHostPort.Authority execution=executionAuthority(request);
                execution.check();GroupHostPort.TaskReceipt receipt=host.resume(request,execution);execution.checkRead();validate(request,receipt);return registry.receipt(request.requestId,receipt);
            }
            case "task.cancel":return cancel(GroupJson.id(value,"requestId"),GroupJson.text(value,"runId",36),(int)GroupJson.number(value,"coordinatorEpoch",1,Integer.MAX_VALUE),this::ownerCheck);
            case "delivery.send":{
                GroupHostPort.Request request=registry.request(GroupJson.id(value,"requestId"));
                boolean reviewed=value.has("taskRevision")||value.has("runId")||value.has("coordinatorEpoch")||value.has("expectedOutput");
                return sendOutput(request,GroupJson.id(value,"outputId"),request.event,null,reviewed?value:null);
            }
            case "delivery.inspect":return inspectDelivery(GroupJson.id(value,"deliveryId"),true);
            default:throw new IllegalArgumentException("未知原生群管理操作");
        }
    }
    /** Root obtains the message proof from an authenticated native channel adapter, never JSON. */
    public JSONObject receiveTask(String eventId,String capability,String audience,String visibility,long maxTokens) throws Exception {
        open();requireHost();GroupIngressPort.Event event=authenticate(eventId);
        GroupHostPort.Request request=registry.prepare(event,capability,audience,visibility,maxTokens,clock.getAsLong());
        if(!registry.begin(request.requestId))return inspect(request.requestId);
        GroupHostPort.Authority authority=executionAuthority(request);
        try {
            authority.check();GroupHostPort.TaskReceipt receipt=host.admit(request,authority);authority.checkRead();validate(request,receipt);
            if(receipt==null)registry.unknown(request.requestId);return registry.receipt(request.requestId,receipt);
        } catch(Exception failure) {registry.unknown(request.requestId);throw failure;}
    }
    /** Exact question/request references are derived from the immutable authenticated message. */
    public JSONObject receiveInput(String eventId)throws Exception {
        open();requireHost();GroupIngressPort.Event event=authenticate(eventId);GroupTaskInput.Command command=GroupTaskInput.parse(event.text);
        String epoch=registry.contributor(command.request,event,null,clock.getAsLong());GroupTaskInput input=new GroupTaskInput(event,epoch);GroupHostPort.Request request=registry.request(input.requestId);
        GroupHostPort.Authority authority=executionAuthority(request);authority.checkInput(input);GroupHostPort.TaskReceipt receipt=host.input(request,input,authority);authority.checkRead();validate(request,receipt);return registry.receipt(request.requestId,receipt);
    }
    private void checkInput(GroupHostPort.Request request,GroupTaskInput input)throws Exception {
        checkRequest(request,false);if(!request.requestId.equals(input.requestId))throw new SecurityException("补充不是此原群请求");
        GroupIngressPort.Event event=authenticate(input.event.eventId);
        if(!GroupJson.canonical(event.json()).equals(GroupJson.canonical(input.event.json())))throw new SecurityException("原群补充事件发生变化");
        registry.contributor(request.requestId,event,input.memberEpoch,clock.getAsLong());
    }
    /** Native authenticated ingress only. It returns original scope identity, never a task grant. */
    public JSONObject authenticatedScope(String eventId) throws Exception {
        GroupIngressPort.Event event=authenticate(eventId);JSONObject result=registry.authenticatedScope(event,clock.getAsLong());ingress.check(event);return result;
    }
    /** Process-local proof issued from the original owner policy, not from chat/JSON/--share. */
    public static final class IntakeFeedback {
        private final GroupsModule issuer;private final GroupIngressPort.Event event;private final String capability;private final JSONObject scope;
        private IntakeFeedback(GroupsModule issuer,GroupIngressPort.Event event,String capability,JSONObject scope){this.issuer=issuer;this.event=event;this.capability=capability;this.scope=scope;}
        public JSONObject view()throws Exception{return GroupJson.copy(scope);}
        public void check()throws Exception{issuer.open();issuer.ingress.check(event);JSONObject current=issuer.registry.intakeFeedbackScope(event,capability,issuer.clock.getAsLong());if(!GroupJson.canonical(scope).equals(GroupJson.canonical(current)))throw new SecurityException("原群反馈策略或成员代次已变化");issuer.ingress.check(event);}
    }
    public IntakeFeedback prepareIntakeFeedback(String eventId,String capability)throws Exception {
        GroupIngressPort.Event event=authenticate(eventId);JSONObject scope=registry.intakeFeedbackScope(event,GroupJson.text(capability,80,"capability"),clock.getAsLong());
        IntakeFeedback proof=new IntakeFeedback(this,event,capability,scope);proof.check();return proof;
    }
    public IntakeFeedback restoreIntakeFeedback(String eventId,String nativeCapability,JSONObject storedScope)throws Exception {
        IntakeFeedback proof=prepareIntakeFeedback(eventId,nativeCapability);if(storedScope==null||!GroupJson.canonical(proof.scope).equals(GroupJson.canonical(storedScope)))throw new SecurityException("旧反馈不是当前原群主人批准的确切范围");proof.check();return proof;
    }
    public JSONObject memberSnapshot(String eventId,String groupId) throws Exception {
        GroupIngressPort.Event event=authenticate(eventId);return registry.memberView(event,GroupJson.id(groupId,"groupId"),clock.getAsLong());
    }
    /** Group admins can change only lower roles; grants/budgets remain native owner decisions. */
    public JSONObject manageMember(String eventId,JSONObject input) throws Exception {
        GroupIngressPort.Event event=authenticate(eventId);return registry.member(GroupJson.copy(input),event,clock.getAsLong());
    }
    public JSONObject inspectTask(String eventId,String requestId) throws Exception {
        GroupIngressPort.Event event=authenticate(eventId);checkReader(requestId,event);JSONObject result=inspect(requestId,event);
        GroupHostPort.Request request=registry.request(requestId);
        if(!request.event.senderId.equals(event.senderId)&&request.audience.equals("requester")&&result.optJSONObject("task")!=null)
            result.getJSONObject("task").put("summary","").put("outputs",new JSONArray()).put("resultPrivate",true);
        checkReader(requestId,event);
        return result;
    }
    public JSONObject cancelTask(String eventId,String requestId,String expectedRunId,int expectedCoordinatorEpoch) throws Exception {
        GroupIngressPort.Event event=authenticate(eventId);registry.cancellable(requestId,event);
        return cancel(requestId,expectedRunId,expectedCoordinatorEpoch,()->{open();ingress.check(event);registry.cancellable(requestId,event);});
    }
    /** Native admission acknowledgement; public progress requires explicit members visibility. */
    public JSONObject acknowledgeTask(String requestId)throws Exception {
        open();requireHost();GroupHostPort.Request request=registry.request(requestId);GroupHostPort.Authority read=readAuthority(request,null);
        read.checkRead();GroupHostPort.Progress progress=host.inspectProgress(request,read);read.checkRead();
        GroupHostPort.TaskReceipt actual=progress==null?null:progress.task;validate(request,actual);registry.receipt(requestId,actual);
        JSONObject result=feedback(request,request.event,"accepted","已保存原群请求引用。编号 "+requestId+"。可在本群 /status "+requestId+" 查询、/cancel "+requestId+" 请求停止；准入与执行以原任务回执为准，敏感操作须本机主人审批。",false);
        if(actual!=null&&!feedbackKind(progress).isEmpty())feedback(request,request.event,feedbackKind(progress),progressText(request,progress),false);
        read.checkRead();return result;
    }
    /** Native task lifecycle sink: fixed status copy and real outputs from the original host only. */
    public JSONObject publishTaskState(String requestId)throws Exception {
        return publishTaskState(requestId,null,false);
    }
    /** A new authenticated command supplies its own bounded passive reply context. */
    public JSONObject replyTaskStatus(String eventId,String requestId)throws Exception {
        GroupIngressPort.Event reader=authenticate(eventId);checkReader(requestId,reader);
        JSONObject result=publishTaskState(requestId,reader,true);checkReader(requestId,reader);return result;
    }
    private JSONObject publishTaskState(String requestId,GroupIngressPort.Event reader,boolean query)throws Exception {
        open();requireHost();GroupHostPort.Request request=registry.request(requestId);GroupHostPort.Authority read=readAuthority(request,reader);
        read.checkRead();GroupHostPort.Progress progress=host.inspectProgress(request,read);read.checkRead();
        GroupHostPort.TaskReceipt actual=progress==null?null:progress.task;validate(request,actual);registry.receipt(requestId,actual);
        GroupIngressPort.Event target=reader==null?request.event:reader;JSONObject result=new JSONObject().put("requestId",requestId).put("taskKnown",actual!=null).put("taskReadOnly",true).put("resubmitsTask",false);
        if(actual==null)return result.put("state","unknown");
        String key=query?"query:"+target.eventId:feedbackKind(progress);
        if(query||!key.isEmpty())result.put("feedback",feedback(request,target,key,progressText(request,progress),query));
        JSONArray sent=new JSONArray();
        if(actual.status.equals("awaiting_user")&&!progress.unresolved&&progress.question!=null&&request.audience.equals("group")) {
            JSONObject question=progress.question;String questionId=question.getString("questionId");
            String body=question.getString("text")+"\n补充此原问题：/reply "+requestId+" "+questionId+" 你的补充\n群回复不批准工具或私人手机权限。";
            GroupHostPort.Output output=new GroupHostPort.Output(questionId,request.spaceId,request.audience,"text/plain","",body,body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            GroupHostPort.Authority send=deliveryAuthority(request,target,true);send.check();
            if(delivery!=null&&delivery.remainingReplies(target,send)>0)sent.put(deliver(request,registry.prepareQuestion(requestId,output,target,clock.getAsLong()),target));
            else result.put("waitingNewReplyContext",true);
        }

        if(actual.status.equals("completed")&&!progress.unresolved)for(GroupHostPort.Output output:actual.outputs){
            JSONObject known=registry.outputDelivery(requestId,output.outputId);
            if(known!=null){String state=known.optString("state");if(state.matches("accepted|delivered"))continue;if(!state.equals("prepared")){result.put("deliveryNeedsReconciliation",true);break;}}
            GroupHostPort.Authority budget=deliveryAuthority(request,target,false);budget.checkRead();
            if(delivery==null||clock.getAsLong()>target.replyUntil||delivery.remainingReplies(target,budget)<1){result.put("waitingNewReplyContext",true);break;}
            JSONObject part=sendOutput(request,output.outputId,target,reader);sent.put(part);
            if(!part.optString("state").matches("accepted|delivered")){result.put("deliveryNeedsReconciliation",true);break;}
        }
        read.checkRead();return result.put("deliveries",sent);
    }
    private JSONObject feedback(GroupHostPort.Request request,GroupIngressPort.Event target,String key,String text,boolean query)throws Exception {
        if(!request.visibility.equals("members")||!request.audience.equals("group"))return new JSONObject().put("state","private_in_native_ui").put("sent",false);
        if(delivery==null)return new JSONObject().put("state","unmounted").put("sent",false);
        GroupHostPort.Authority authority=deliveryAuthority(request,target,true);authority.check();
        // Three coalesced notices at most leave two original slots for text + a real artifact.
        int remaining=delivery.remainingReplies(target,authority);
        if(remaining<=(query?0:2))return new JSONObject().put("state","waiting_new_reply_context").put("sent",false);
        GroupDeliveryPort.Delivery value=registry.prepareFeedback(request.requestId,target,key,text,clock.getAsLong());
        return deliver(request,value,target);
    }
    private static String feedbackKind(GroupHostPort.Progress value){
        if(value.unresolved)return "unknown";
        if(value.question!=null&&value.task.status.equals("awaiting_user"))return "";
        if(value.task.status.equals("completed"))return "";
        if(value.task.status.matches("cancelled|failed"))return value.task.status;
        if(value.approvalState.equals("pending"))return "approval";
        if(value.approvalState.equals("denied"))return "denied";
        if(value.approvalState.equals("expired"))return "approval_expired";
        if(value.task.status.equals("needs_confirmation"))return "confirmation";
        if(value.task.status.matches("failed|cancelled|interrupted|awaiting_user|paused|stopping|reconciling"))return value.task.status;
        return "";
    }
    private static String progressText(GroupHostPort.Request request,GroupHostPort.Progress value){
        String message;
        if(value.unresolved)message="原操作回执仍未知，已停止后续派发；请主人核对原编号，不会重新提交或重发。";
        else if(value.task.status.equals("completed"))message="原任务已完成。产物投递按原编号核对，平台受理不代表成员收到或阅读。";
        else if(value.task.status.equals("cancelled"))message="已停止原任务后续派发；已发生的操作不会被撤销。";
        else if(value.task.status.equals("failed"))message="原任务执行失败，真实失败记录已保留；请主人在手机查看原因，未自动重试。";
        else if(value.approvalState.equals("pending"))message="等待本机主人审批原操作；群内回复同意不构成执行授权。";
        else if(value.approvalState.equals("denied"))message="主人已拒绝原操作；原任务记录保留，未重新提交。";
        else if(value.approvalState.equals("expired"))message="原操作审批已过期或失效，未据此派发。请主人在手机核对原参数与当前授权，再继续同一任务。";
        else switch(value.task.status){
            case "needs_confirmation":message="已有执行回执，等待本机主人核验完成；尚未把执行回执当成任务完成。";break;
            case "interrupted":message="原任务执行已中断，记录与产物保留；请主人在手机核对后继续同一任务。";break;
            case "stopping":message="已登记停止意图，正在核对已派发操作；尚未宣称远端已停止。";break;
            case "reconciling":message="原任务等待核对实际效果；查询不会重新执行。";break;
            case "paused":message="原任务已暂停，等待主人处理；查询不会自动继续。";break;
            case "awaiting_user":message="原任务等待主人处理或补充资料，请在手机查看原任务。";break;
            default:message="原任务正在处理，过程与审批可在手机查看；仅在状态需要处理时合并通知。";
        }
        return "任务 "+request.requestId+"："+message+" 继续查询：/status "+request.requestId;
    }
    /** Can be called by the trusted host for a group-owned result, never an arbitrary reply body. */
    public JSONObject sendOutput(String requestId,String outputId) throws Exception {
        GroupHostPort.Request request=registry.request(requestId);return sendOutput(request,outputId,request.event,null);
    }
    private JSONObject sendOutput(GroupHostPort.Request request,String outputId,GroupIngressPort.Event target,GroupIngressPort.Event reader)throws Exception {
        return sendOutput(request,outputId,target,reader,null);
    }
    private JSONObject sendOutput(GroupHostPort.Request request,String outputId,GroupIngressPort.Event target,GroupIngressPort.Event reader,JSONObject reviewed)throws Exception {
        open();requireHost();if(delivery==null)throw new IllegalStateException("官方群消息或媒体投递端尚未接入，未发送结果");
        GroupHostPort.Authority read=readAuthority(request,reader);read.checkRead();
        GroupHostPort.Progress progress=host.inspectProgress(request,read);GroupHostPort.TaskReceipt receipt=progress==null?null:progress.task;read.checkRead();validate(request,receipt);registry.receipt(request.requestId,receipt);
        if(progress!=null&&progress.unresolved)throw new IllegalStateException("原操作效果仍未知，只能核对原编号，不能发送或重试结果");
        if(receipt==null||!receipt.status.equals("completed"))throw new IllegalStateException("任务尚无已核验完成的结果");
        GroupHostPort.Output output=null;for(GroupHostPort.Output candidate:receipt.outputs)if(candidate.outputId.equals(outputId))output=candidate;
        if(output==null)throw new IllegalArgumentException("TaskStore回执中没有此产物");
        if(reviewed!=null){
            reviewedResume(reviewed,receipt);
            if(!GroupJson.canonical(output.json()).equals(GroupJson.canonical(reviewed.getJSONObject("expectedOutput"))))
                throw new SecurityException("原产物内容或受众已变化，请重新审阅后发回");
        }
        if(!delivery.supports(target,request.audience,output.mimeType))throw new IllegalStateException("该官方渠道不支持此受众或媒体类型，未发送");
        GroupDeliveryPort.Delivery value=registry.prepareDelivery(request.requestId,output,target,clock.getAsLong());
        JSONObject result=deliver(request,value,target);read.checkRead();return result;
    }
    private JSONObject deliver(GroupHostPort.Request request,GroupDeliveryPort.Delivery value,GroupIngressPort.Event reader)throws Exception {
        if(!value.newlyPrepared||!registry.beginDelivery(value.deliveryId))return inspectDelivery(value.deliveryId);
        GroupHostPort.Authority send=deliveryAuthority(request,value.event,true,reader);
        try {
            send.beforeDelivery();GroupDeliveryPort.Receipt result=delivery.send(value,send);send.check();
            if(result==null)registry.deliveryUnknown(value.deliveryId);return registry.deliveryReceipt(value.deliveryId,result);
        } catch(Exception failure) {registry.deliveryUnknown(value.deliveryId);throw failure;}
    }
    public JSONObject inspectDelivery(String deliveryId) throws Exception {
        return inspectDelivery(deliveryId,false);
    }
    private JSONObject inspectDelivery(String deliveryId,boolean nativeOwner) throws Exception {
        open();if(delivery==null)throw new IllegalStateException("群投递查询尚未接入");
        GroupDeliveryPort.Delivery value=registry.delivery(deliveryId);GroupHostPort.Request request=registry.request(value.requestId);
        // This callback is given only to the query method. It cannot open a send/retry route.
        GroupHostPort.Authority read=nativeOwner?this::ownerCheck:deliveryAuthority(request,false);
        read.check();GroupDeliveryPort.Receipt receipt=delivery.inspect(value,read);read.check();
        JSONObject result=registry.deliveryReceipt(deliveryId,receipt);read.check();return result;
    }
    private JSONObject inspect(String requestId) throws Exception {
        return inspect(requestId,null);
    }
    private JSONObject inspect(String requestId,GroupIngressPort.Event reader) throws Exception {
        open();requireHost();GroupHostPort.Request request=registry.request(requestId);GroupHostPort.Authority authority=readAuthority(request,reader);
        authority.checkRead();GroupHostPort.TaskReceipt receipt=host.inspect(request,authority);authority.checkRead();validate(request,receipt);return registry.receipt(requestId,receipt);
    }
    /** Native owner recovery is read-only and must remain available after group/transport revocation. */
    private JSONObject inspectOwner(String requestId) throws Exception {
        ownerCheck();requireHost();GroupHostPort.Request request=registry.request(requestId);
        GroupHostPort.Authority read=ownerReadAuthority();
        read.checkRead();GroupHostPort.TaskReceipt receipt=host.inspect(request,read);read.checkRead();validate(request,receipt);
        JSONObject result=registry.receipt(requestId,receipt);read.checkRead();return result;
    }
    @FunctionalInterface public interface OwnerTaskReader<T> {
        T read(GroupHostPort.Request request,GroupHostPort.Authority readAuthority) throws Exception;
    }
    /** Original Application construction only. This does not expose a feature/model command. */
    public synchronized void bindRemoteInputArtifacts(dev.pocketengine.features.groups.host.GroupArtifactStore artifacts){if(closed||remoteInputArtifacts!=null)throw new IllegalStateException("原群文件宿主已经绑定或关闭");remoteInputArtifacts=Objects.requireNonNull(artifacts);}
    /** Native source proof, not a remote account/budget/model or transmission grant. */
    public static final class RemoteInputSource {
        private final GroupsModule issuer;private final GroupHostPort.Request request;private final String files,run;private final int epoch;
        private RemoteInputSource(GroupsModule issuer,GroupHostPort.Request request,JSONArray files,GroupHostPort.TaskReceipt original){this.issuer=issuer;this.request=request;this.files=files.toString();run=original.runId;epoch=original.coordinatorEpoch;}
        private void originCurrent()throws Exception{issuer.checkRequest(request,false);if(!GroupJson.canonical(request.origin()).equals(GroupJson.canonical(issuer.registry.request(request.requestId).origin())))throw new SecurityException("原群来源身份已变化");}
        private GroupHostPort.Authority readAuthority(){return new GroupHostPort.Authority(){public void check(){throw new SecurityException("群原件读取不授予执行或外发");}public void checkRead()throws Exception{originCurrent();}};}
        public void checkCurrent()throws Exception{originCurrent();GroupHostPort.TaskReceipt current=issuer.host.inspect(request,readAuthority());if(current==null||!run.equals(current.runId)||epoch!=current.coordinatorEpoch||!current.status.equals("completed"))throw new SecurityException("所选原群任务已变更、停止或尚未完成核验，不能继续外传");originCurrent();}
        public JSONObject view()throws Exception{return new JSONObject().put("inferenceAudience","EXTERNAL_GROUP").put("privateBookImported",false).put("requestId",request.requestId).put("taskId",request.taskId).put("runId",run).put("coordinatorEpoch",epoch).put("spaceId",request.spaceId).put("actorId",request.actorId).put("audience",request.audience).put("origin",request.origin()).put("files",new JSONArray(files));}
        public JSONObject selectedView(JSONArray selected)throws Exception{JSONObject value=view();JSONArray actual=new JSONArray();for(int i=0;i<selected.length();i++)actual.put(file(selected.getJSONObject(i).getString("attachmentId"),selected.getJSONObject(i).getString("sha256")));return value.put("files",actual);}
        public JSONArray files()throws Exception{return new JSONArray(files);}
        private JSONObject file(String id,String sha)throws Exception{JSONArray values=new JSONArray(files);for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);if(id.equals(value.getString("attachmentId"))&&sha.equals(value.getString("sha256")))return value;}throw new SecurityException("所选群文件不是此原任务可核对的字节版本");}
        public JSONObject checkFile(String id,String sha)throws Exception{checkCurrent();JSONObject expected=file(id,sha),actual=issuer.remoteInputArtifacts.inputMetadata(request,id,readAuthority());if(actual.getLong("bytes")!=expected.getLong("bytes")||!actual.getString("sha256").equals(sha)||!actual.getString("mimeType").equals(expected.getString("mimeType")))throw new SecurityException("群原件内容/类型变化");checkCurrent();return expected;}
        public byte[] readExact(String id,String sha,dev.pocketengine.core.RunControl control)throws Exception{JSONObject expected=checkFile(id,sha);var snapshot=issuer.remoteInputArtifacts.readInputSnapshot(request,id,readAuthority(),control);JSONObject actual=snapshot.metadata();if(actual.getLong("bytes")!=expected.getLong("bytes")||!actual.getString("sha256").equals(sha))throw new SecurityException("群原件实际hash变化");control.check();checkCurrent();return snapshot.copyBytes();}
        public boolean issuedBy(GroupsModule module){return issuer==module;}
        public GroupHostPort.Request originalRequest(){return request;}
    }
    /** Only the actual foreground native owner route can mint the original group source. */
    public RemoteInputSource remoteInputSourceForOwner(String requestId)throws Exception{
        ownerCheck();requireHost();if(remoteInputArtifacts==null)throw new IllegalStateException("群原件宿主尚未接入");GroupHostPort.Request request=registry.request(GroupJson.id(requestId,"requestId"));checkRequest(request,false);GroupHostPort.Progress original=host.inspectProgress(request,ownerReadAuthority());if(original==null||original.task==null||!original.task.status.equals("completed")||original.unresolved)throw new SecurityException("请先在原群任务核验完成并结清未决动作，再另审远端数据使用");
        JSONArray files=new JSONArray(),all=request.inputArtifacts();for(int i=0;i<all.length();i++){JSONObject pinned=all.getJSONObject(i);if(pinned.optLong("bytes")>262144)continue;JSONObject meta=remoteInputArtifacts.inputMetadata(request,pinned.getString("artifactId"),ownerReadAuthority());files.put(new JSONObject().put("attachmentId",meta.getString("artifactId")).put("name","group-"+meta.getString("artifactId")+".bin").put("mimeType",meta.getString("mimeType")).put("bytes",meta.getLong("bytes")).put("sha256",meta.getString("sha256")).put("generatedDisplayName",true));}
        ownerCheck();RemoteInputSource source=new RemoteInputSource(this,request,files,original.task);source.checkCurrent();return source;
    }
    /** Real original ingress supplies a possible passive reply context, never arbitrary endpoint JSON. */
    public JSONObject remoteReplyContextForOwner(RemoteInputSource source,String eventId)throws Exception{
        ownerCheck();if(source==null||!source.issuedBy(this))throw new SecurityException("不是此原群来源");source.checkCurrent();GroupIngressPort.Event target=authenticate(eventId);registry.visible(source.request.requestId,target);if(clock.getAsLong()>target.replyUntil)throw new SecurityException("原消息回复窗口已过期");return replyContext(target);
    }
    private static JSONObject replyContext(GroupIngressPort.Event target)throws Exception {JSONObject value=new JSONObject().put("eventId",target.eventId).put("provider",target.provider).put("accountId",target.accountId).put("roomId",target.roomId).put("senderId",target.senderId).put("messageId",target.messageId).put("transportEpoch",target.transportEpoch).put("conversationKind",target.conversationKind).put("conversationParentId",target.conversationParentId).put("replyUntil",target.replyUntil).put("receivedAt",target.receivedAt);return value;}
    /** Only the second exact native content/target approval may use the original delivery/outbox. */
    public JSONObject sendRemoteResult(dev.pocketengine.features.remote.RemoteGroupInputs.ReturnApproval approval)throws Exception{
        ownerCheck();if(approval==null||!approval.originalSource().issuedBy(this))throw new SecurityException("没有原群成果回发批准");approval.checkCurrent();approval.checkOriginalReceipt();RemoteInputSource source=approval.originalSource();source.checkCurrent();JSONObject scope=approval.scope(),saved=approval.saved();GroupHostPort.Request request=source.request;JSONObject record=remoteInputArtifacts.readOutputMetadata(request,saved.getString("artifactId"),source.readAuthority(),approval.readControl());
        if(!record.getString("sha256").equals(scope.getString("sha256"))||record.getLong("bytes")!=scope.getLong("bytes")||!record.getString("operationId").equals(scope.getString("operationId")))throw new SecurityException("原群冻结成果不是本次核对内容");GroupHostPort.Output output=remoteInputArtifacts.output(request,record.getString("artifactId"),source.readAuthority());JSONObject selectedTarget=approval.deliveryContext();GroupIngressPort.Event target=authenticate(selectedTarget.getString("eventId"));if(!GroupJson.canonical(replyContext(target)).equals(GroupJson.canonical(selectedTarget)))throw new SecurityException("原群回复上下文已变化");registry.visible(request.requestId,target);if(delivery==null||!delivery.supports(target,request.audience,output.mimeType))throw new IllegalStateException("原正式渠道不支持此群文件类型；成果已保留，未改成私人或其它受众发送");
        approval.checkCurrent();source.checkCurrent();GroupDeliveryPort.Delivery value=registry.prepareDelivery(request.requestId,output,target,clock.getAsLong());JSONObject result=deliver(request,value,target);ownerCheck();return result;
    }
    /**
     * Native artifact/result readers use the original registry identity inside the current
     * owner window. Neither this callback nor its authority can execute, send, or resume.
     * Read bytes within the callback: the authority is invalidated before this method returns.
     */
    public <T> T withOwnerTaskRead(String requestId,OwnerTaskReader<T> reader) throws Exception {
        ownerCheck();requireHost();Objects.requireNonNull(reader);
        GroupHostPort.Request request=registry.request(GroupJson.id(requestId,"requestId"));
        java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
        GroupHostPort.Authority ownerRead=ownerReadAuthority();
        GroupHostPort.Authority scopedRead=new GroupHostPort.Authority() {
            @Override public void check() throws Exception {ownerRead.check();}
            @Override public void checkRead() throws Exception {
                if(!active.get())throw new SecurityException("原生主人读取回调已结束，请重新读取原任务");
                ownerRead.checkRead();
            }
        };
        try {
            scopedRead.checkRead();T result=reader.read(request,scopedRead);scopedRead.checkRead();return result;
        } finally {active.set(false);}
    }
    @FunctionalInterface public interface OwnerDeliveryReader<T> {
        T read(GroupHostPort.Request request,GroupDeliveryPort.Delivery delivery,GroupHostPort.Authority readAuthority)throws Exception;
    }
    public <T> T withOwnerDeliveryRead(String deliveryId,OwnerDeliveryReader<T> reader)throws Exception {
        ownerCheck();Objects.requireNonNull(reader);GroupDeliveryPort.Delivery value=registry.delivery(GroupJson.id(deliveryId,"deliveryId"));
        return withOwnerTaskRead(value.requestId,(request,read)->{
            if(!request.taskId.equals(value.taskId)||!request.spaceId.equals(value.output.spaceId)||!request.audience.equals(value.audience))throw new SecurityException("原投递不属于原任务空间");
            read.checkRead();T result=reader.read(request,value,read);read.checkRead();return result;
        });
    }
    private GroupHostPort.Authority ownerReadAuthority() {
        return new GroupHostPort.Authority() {
            @Override public void check() {throw new SecurityException("主人恢复查询不授权任何新任务执行");}
            @Override public void checkRead() throws Exception {ownerCheck();}
            @Override public void checkInput(GroupTaskInput input)throws Exception {ownerCheck();} // Native audit only; check() still rejects every dispatch.
        };
    }
    private JSONObject cancel(String requestId,String runId,int epoch,GroupHostPort.Authority control) throws Exception {
        open();requireHost();GroupHostPort.Request request=registry.request(requestId);JSONObject known=registry.cancelTarget(requestId);
        if(runId==null||!runId.matches("[a-f0-9-]{36}")||epoch<1||!runId.equals(known.getString("runId"))||epoch!=known.getInt("coordinatorEpoch"))
            throw new SecurityException("取消目标不是最近核对的执行代次，请重新查询原任务");
        control.check();GroupHostPort.TaskReceipt receipt=host.cancel(request,runId,epoch,control);control.check();validate(request,receipt);return registry.receipt(requestId,receipt);
    }
    private static void reviewedResume(JSONObject input,GroupHostPort.TaskReceipt actual) throws Exception {
        Object raw=input.opt("runId");long epoch=GroupJson.number(input,"coordinatorEpoch",0,Integer.MAX_VALUE),revision=GroupJson.number(input,"taskRevision",1,Long.MAX_VALUE);
        if(!(raw instanceof String)||!actual.runId.equals(raw)||epoch!=actual.coordinatorEpoch||revision!=actual.revision)
            throw new SecurityException("原群任务执行或版本已变化，请重新读取后核对恢复");
    }
    private JSONObject recoverOwner(GroupHostPort.Request request,GroupHostPort.TaskReceipt actual) throws Exception {
        ownerCheck();checkRequest(request,false);ownerCheck();long generation=registry.claimRecovery(request.requestId,actual,clock.getAsLong());
        GroupHostPort.Authority execution=executionAuthority(request,generation);boolean invoked=false;
        try {
            execution.check();ownerCheck();invoked=true;
            GroupHostPort.TaskReceipt receipt=host.recover(request,actual.runId,actual.coordinatorEpoch,actual.revision,execution);
            execution.checkRead();validate(request,receipt);
            if(receipt==null)registry.recoveryUnknown(request.requestId,generation);
            return registry.receipt(request.requestId,receipt);
        } catch(Exception failure) {
            if(!invoked||failure instanceof GroupHostPort.RecoveryNotStarted)registry.recoveryNotStarted(request.requestId,generation);
            else registry.recoveryUnknown(request.requestId,generation);
            throw failure;
        }
    }
    private GroupHostPort.Authority executionAuthority(GroupHostPort.Request request) throws Exception {
        return executionAuthority(request,registry.executionGeneration(request.requestId));
    }
    private GroupHostPort.Authority executionAuthority(GroupHostPort.Request request,long generation) {
        return new GroupHostPort.Authority() {
            @Override public void check() throws Exception {checkExecutionRequest(request,generation);}
            @Override public void checkRead() throws Exception {checkRequest(request,false);}
            @Override public void checkInput(GroupTaskInput input)throws Exception {GroupsModule.this.checkInput(request,input);}
        };
    }
    private GroupHostPort.Authority readAuthority(GroupHostPort.Request request,GroupIngressPort.Event reader) {
        return new GroupHostPort.Authority() {
            @Override public void check() {throw new SecurityException("任务详情读取不授权任何新任务执行");}
            @Override public void checkRead() throws Exception {checkRequest(request,false);if(reader!=null)checkReader(request.requestId,reader);}
            @Override public void checkInput(GroupTaskInput input)throws Exception {GroupsModule.this.checkInput(request,input);}
        };
    }
    private GroupHostPort.Authority deliveryAuthority(GroupHostPort.Request request,boolean sending) {
        return deliveryAuthority(request,request.event,sending);
    }
    private GroupHostPort.Authority deliveryAuthority(GroupHostPort.Request request,GroupIngressPort.Event target,boolean sending) {
        return deliveryAuthority(request,target,sending,target);
    }
    private GroupHostPort.Authority deliveryAuthority(GroupHostPort.Request request,GroupIngressPort.Event target,boolean sending,GroupIngressPort.Event reader) {
        return new GroupHostPort.Authority() {
            @Override public void check() throws Exception {
                checkRequest(request,false);checkReader(request.requestId,reader);ingress.check(target);
                if(sending&&clock.getAsLong()>target.replyUntil)throw new SecurityException("平台回复窗口已过期，请在原群用新/status消息查询原任务");
            }
            @Override public void checkRead() throws Exception {check();}
            @Override public void beforeDelivery()throws Exception {
                // ChannelCore invokes check() while holding its monitor. The original
                // SQL source check stays at the physical bridge boundary, outside it.
                check();host.checkInputSources(request,readAuthority(request,reader));check();
            }
        };
    }
    private void checkReader(String requestId,GroupIngressPort.Event event) throws Exception {
        open();if(ingress==null)throw new SecurityException("认证群通道未接入");ingress.check(event);registry.visible(requestId,event);
    }
    private void checkRequest(GroupHostPort.Request request,boolean execution) throws Exception {
        open();if(ingress==null)throw new SecurityException("认证群通道未接入");
        registry.check(request.requestId,clock.getAsLong(),execution);ingress.check(request.event);
        registry.check(request.requestId,clock.getAsLong(),execution);
    }
    private void checkExecutionRequest(GroupHostPort.Request request,long generation) throws Exception {
        open();if(ingress==null)throw new SecurityException("认证群通道未接入");
        registry.checkExecution(request.requestId,clock.getAsLong(),generation);ingress.check(request.event);
        registry.checkExecution(request.requestId,clock.getAsLong(),generation);
    }
    private GroupIngressPort.Event authenticate(String eventId) throws Exception {
        open();if(ingress==null)throw new IllegalStateException("认证群通道尚未接入");GroupJson.id(eventId,"eventId");
        GroupIngressPort.Event event=ingress.authenticate(eventId);
        if(event==null||!event.eventId.equals(eventId))throw new SecurityException("认证群事件身份不匹配");ingress.check(event);return event;
    }
    private static void validate(GroupHostPort.Request request,GroupHostPort.TaskReceipt receipt) throws Exception {
        if(receipt==null)return;if(!request.taskId.equals(receipt.taskId))throw new SecurityException("宿主任务回执不属于该群请求");
        for(GroupHostPort.Output output:receipt.outputs)if(!request.spaceId.equals(output.spaceId)||!request.audience.equals(output.audience))throw new SecurityException("宿主结果属于其他空间或受众");
    }
    private void requireHost() {if(host==null)throw new IllegalStateException("唯一TaskStore群任务端尚未接入，没有启动任务");}
    private void ownerCheck() throws Exception {open();owner.check();}
    private void open() {if(closed)throw new IllegalStateException("群协作服务已关闭");}
    @Override public void close() {closed=true;registry.close();}
}
