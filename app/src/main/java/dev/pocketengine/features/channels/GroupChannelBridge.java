package dev.pocketengine.features.channels;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.groups.GroupDeliveryPort;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.features.groups.GroupIngressPort;
import dev.pocketengine.features.groups.GroupsModule;
import dev.pocketengine.features.groups.host.GroupArtifactStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native adapter over the existing authenticated inbox and outbox, never a JSON authority. */
public final class GroupChannelBridge implements GroupIngressPort,GroupDeliveryPort,AutoCloseable {
    interface Credentials {String get(String profileId)throws Exception;}
    interface ControlFeedback {void failed(String originalEventId,String fixedKind)throws Exception;}
    private final ChannelCore core;
    private final ChannelHttp http;
    private final Credentials credentials;
    private final File proofFile;
    private JSONObject proofs;
    private volatile GroupsModule groups;
    private volatile GroupArtifactStore artifacts;
    private final GroupInboundFiles inbound;
    private final ControlFeedback controlFeedback;
    private volatile Executor workers;
    private volatile boolean closed;
    private final java.util.concurrent.ConcurrentHashMap<String,ActiveDelivery> sending=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String,ActiveDelivery> importing=new java.util.concurrent.ConcurrentHashMap<>();

    GroupChannelBridge(ChannelCore core,ChannelHttp http,Credentials credentials,File directory)throws Exception {this(core,http,credentials,directory,new GroupInboundFiles());}
    /** Explicit loopback downloader injection exists only in native package tests. */
    GroupChannelBridge(ChannelCore core,ChannelHttp http,Credentials credentials,File directory,GroupInboundFiles inbound)throws Exception {
        this(core,http,credentials,directory,inbound,null);
    }
    /** The production callback only queues a fixed notice in this original channel outbox. */
    GroupChannelBridge(ChannelCore core,ChannelHttp http,Credentials credentials,File directory,GroupInboundFiles inbound,ControlFeedback feedback)throws Exception {
        this.controlFeedback=feedback;
        this.inbound=java.util.Objects.requireNonNull(inbound);this.core=java.util.Objects.requireNonNull(core);this.http=java.util.Objects.requireNonNull(http);this.credentials=java.util.Objects.requireNonNull(credentials);
        Files.createDirectories(directory.toPath());proofFile=new File(directory,"group-proofs.json");
        if(proofFile.isFile()&&proofFile.length()>8*1024*1024L)throw new IllegalStateException("群原生事件凭据账簿超过上限");
        proofs=proofFile.isFile()?new JSONObject(new String(Files.readAllBytes(proofFile.toPath()),StandardCharsets.UTF_8)):new JSONObject();
    }
    /** Application only, using its existing bounded capability worker. No new executor or account. */
    public void bind(GroupsModule groups,Executor workers){this.groups=java.util.Objects.requireNonNull(groups);this.workers=java.util.Objects.requireNonNull(workers);}
    public void bindArtifacts(GroupArtifactStore artifacts){this.artifacts=java.util.Objects.requireNonNull(artifacts);}
    public boolean mounted(){return !closed&&groups!=null&&workers!=null;}
    /** Same native message proof and owner policy; this does not admit/read a task. */
    GroupsModule.IntakeFeedback intakeFeedback(String eventId,JSONObject retainedScope)throws Exception {
        open();GroupsModule module=groups;if(module==null)throw new SecurityException("原群主人策略尚未挂载，反馈仅保留在手机");
        JSONObject row=proof(eventId).getJSONObject("event");String originalCapability=capability(row.getString("text"));
        return retainedScope==null?module.prepareIntakeFeedback(eventId,originalCapability):module.restoreIntakeFeedback(eventId,originalCapability,retainedScope);
    }

    /** Called only immediately after the existing transport has authenticated and accepted an event. */
    boolean accepted(JSONObject originalProfile,String eventId)throws Exception {
        open();JSONObject row=core.authenticatedGroupEvent(eventId,originalProfile.getString("transportEpoch"));
        String command=row.getString("text");if(!isCommand(command))return false;
        pin(originalProfile,row);GroupsModule module=groups;Executor worker=workers;
        if(module==null||worker==null)throw new IllegalStateException("认证群任务宿主尚未挂载，未启动任务");
        worker.execute(()->{
            try {
                importIncoming(eventId,module);Event event=authenticate(eventId);
                if(statusCommand(command))module.replyTaskStatus(eventId,command.substring(command.indexOf(' ')+1).trim());
                else if(cancelCommand(command)){
                    String request=command.substring(command.indexOf(' ')+1).trim();JSONObject known=module.inspectTask(eventId,request);
                    JSONObject task=known.optJSONObject("task");if(task==null||task.optString("runId").isEmpty())throw new IllegalStateException("原任务执行身份尚未知，只能查询");
                    module.cancelTask(eventId,request,task.getString("runId"),task.getInt("coordinatorEpoch"));module.replyTaskStatus(eventId,request);
                } else {
                    JSONObject admitted=module.receiveTask(event.eventId,capability(command),"group",shared(command)?"members":"requester",requestedTokens(command));
                    module.acknowledgeTask(admitted.getJSONObject("reference").getString("requestId"));
                    if(admitted.optJSONObject("task")!=null&&admitted.getJSONObject("task").optString("status").equals("failed")&&controlFeedback!=null)controlFeedback.failed(eventId,"group_capability_failed");
                }
                core.eventStatus(eventId,"group_task_linked","认证群请求已交给原TaskStore；结果以原任务及投递回执为准");
            } catch(Exception failed){
                try{core.eventStatus(eventId,"group_task_blocked",safe(failed));}catch(Exception ignored){}
                // No raw exception, original file content or model output is sent. Admission
                // may already have happened, so the notice never claims definitely not started.
                if(!closed&&controlFeedback!=null)try{controlFeedback.failed(eventId,"group_intake_unconfirmed");}catch(Exception unavailable){/* Original local status remains. Never retry the task or the feedback. */}
            }
        });
        return true;
    }
    private void pin(JSONObject originalProfile,JSONObject row)throws Exception {
        String epoch=originalProfile.getString("transportEpoch");core.authenticatedGroupEvent(row.getString("id"),epoch);
        JSONObject profile=core.profile(row.getString("profileId"));String credential=credentials.get(profile.getString("id"));
        if(credential==null||credential.isEmpty())throw new SecurityException("群连接凭据已失效");
        String id=row.getString("id");
        long received=row.getLong("receivedAt"),until=Math.min(received+86_400_000L,row.getLong("sentAt")+(profile.getString("provider").equals("qq")?300_000L:3_600_000L));
        if(until<received)throw new SecurityException("原群事件回复窗口已关闭");
        Event event=new Event(id,profile.getString("provider"),profile.getString("id"),row.getString("roomId"),row.getString("senderId"),row.getString("messageId"),epoch,goal(row.getString("text")),received,until,new JSONArray());
        JSONObject candidate=new JSONObject().put("event",new JSONObject(row.toString())).put("credentialFingerprint",ChannelCore.digest(credential)).put("replyUntil",until).put("goal",event.text).put("artifacts",new JSONArray()).put("intake",row.optJSONArray("groupAttachments")==null?"ready":"prepared");
        // Core may hold its monitor while invoking native authority -> proof(). Never call Core,
        // credentials, or an authority callback while this proof-state monitor is held.
        synchronized(this){
            open();JSONObject existing=proofs.optJSONObject(id);
            if(existing!=null){if(!existing.getJSONObject("event").toString().equals(row.toString()))throw new SecurityException("群事件凭据身份冲突");}
            else {if(proofs.length()>=2000)throw new IllegalStateException("群认证事件引用已满，未遗忘原请求凭据");JSONObject next=new JSONObject(proofs.toString());next.put(id,candidate);save(next);proofs=next;}
        }
        // A configuration/credential may have changed during preparation or persistence. This
        // live check is deliberately outside the proof lock and precedes any task dispatch.
        authenticate(id);
    }
    @Override public Event authenticate(String eventId)throws Exception {
        JSONObject proof=proof(eventId),row=proof.getJSONObject("event");JSONObject profile=core.profile(row.getString("profileId"));
        Event event=new Event(eventId,profile.getString("provider"),profile.getString("id"),row.getString("roomId"),row.getString("senderId"),row.getString("messageId"),row.getString("transportEpoch"),proof.getString("goal"),row.getLong("receivedAt"),proof.getLong("replyUntil"),proof.optJSONArray("artifacts")==null?new JSONArray():proof.getJSONArray("artifacts"),proof.optJSONObject("messageParts")==null?row.optJSONObject("messageParts"):proof.getJSONObject("messageParts"));
        check(event);return event;
    }
    @Override public void check(Event event)throws Exception {
        open();JSONObject proof=proof(event.eventId),row=proof.getJSONObject("event");core.checkGroupEventCurrent(row);
        JSONObject profile=core.profile(row.getString("profileId"));String current=credentials.get(profile.getString("id"));
        if(current==null||current.isEmpty()||!proof.getString("credentialFingerprint").equals(ChannelCore.digest(current))
                ||!event.accountId.equals(profile.getString("id"))||!event.provider.equals(profile.getString("provider"))||!event.roomId.equals(row.getString("roomId"))
                ||!event.senderId.equals(row.getString("senderId"))||!event.messageId.equals(row.getString("messageId"))||!event.transportEpoch.equals(row.getString("transportEpoch"))
                ||!event.text.equals(proof.getString("goal"))||event.receivedAt!=row.getLong("receivedAt")||event.replyUntil!=proof.getLong("replyUntil")||!event.artifacts().toString().equals(proof.optJSONArray("artifacts")==null?"[]":proof.getJSONArray("artifacts").toString()))
            throw new SecurityException("原群事件、机器人或凭据代次已失效");
        JSONObject expectedParts=proof.optJSONObject("messageParts")==null?row.optJSONObject("messageParts"):proof.getJSONObject("messageParts");
        if(expectedParts!=null&&!event.messageParts().toString().equals(expectedParts.toString()))throw new SecurityException("原群消息parts或冻结来源已变化");
    }
    private void importIncoming(String eventId,GroupsModule module)throws Exception {
        JSONObject proof=proof(eventId),event=proof.getJSONObject("event");JSONArray incoming=event.optJSONArray("groupAttachments");if(incoming==null||incoming.length()==0)return;
        if(!event.getString("text").startsWith("/file "))throw new SecurityException("群附件只能通过明确的/file任务使用");
        GroupArtifactStore storage=artifacts;if(storage==null)throw new IllegalStateException("原生群附件存储尚未挂载");
        synchronized(this){JSONObject current=proofs.getJSONObject(eventId);if(current.optString("intake").equals("imported"))return;if(!current.optString("intake").equals("prepared"))throw new SecurityException("原群附件导入状态未知，只能核对原事件");JSONObject next=new JSONObject(proofs.toString());next.getJSONObject(eventId).put("intake","importing");save(next);proofs=next;}
        JSONObject scope=module.authenticatedScope(eventId);GroupHostPort.Authority nativeProof=()->{check(authenticate(eventId));JSONObject current=module.authenticatedScope(eventId);if(!scope.toString().equals(current.toString()))throw new SecurityException("原群附件作用域已变化");};
        RunControl control=new RunControl();control.setAuthorityCheck(nativeProof::check);ActiveDelivery intake=new ActiveDelivery(event.getString("profileId"),control);if(importing.putIfAbsent(eventId,intake)!=null)throw new SecurityException("原群附件仍在导入，不能重复下载");JSONArray imported=new JSONArray();JSONObject parts=event.optJSONObject("messageParts")==null?new JSONObject():new JSONObject(event.getJSONObject("messageParts").toString());
        try {
            for(int i=0;i<incoming.length();i++){JSONObject attachment=incoming.getJSONObject(i);try(GroupInboundFiles.Download download=inbound.open(attachment,control)){
                JSONObject actual=storage.importInput(scope.getString("spaceId"),scope.getString("actorId"),"group",eventId,attachment.getString("mimeType"),download.input,scope.getLong("maxArtifactBytes"),nativeProof);
                JSONObject reference=new JSONObject().put("artifactId",actual.getString("artifactId")).put("spaceId",actual.getString("spaceId")).put("mimeType",actual.getString("mimeType")).put("bytes",actual.getLong("bytes"));if(actual.optString("sha256").matches("[a-f0-9]{64}"))reference.put("sha256",actual.getString("sha256"));JSONObject nativeReference=new JSONObject(reference.toString());nativeReference.remove("sha256");imported.put(nativeReference);
                int partIndex=attachment.optInt("partIndex",-1);if(partIndex>=0&&parts.optJSONArray("parts")!=null&&partIndex<parts.getJSONArray("parts").length()){
                    JSONObject part=parts.getJSONArray("parts").getJSONObject(partIndex);JSONArray refs=part.optJSONArray("artifactRefs");if(refs==null){refs=new JSONArray();part.put("artifactRefs",refs);}refs.put(new JSONObject(reference.toString()));part.put("contentRead",false).put("sourceFrozen",true);
                }}}
            nativeProof.check();synchronized(this){JSONObject next=new JSONObject(proofs.toString());next.getJSONObject(eventId).put("artifacts",imported).put("intake","imported");if(parts.length()>0)next.getJSONObject(eventId).put("messageParts",parts);save(next);proofs=next;}
        }catch(Exception failed){synchronized(this){JSONObject next=new JSONObject(proofs.toString());next.getJSONObject(eventId).put("intake","unknown");save(next);proofs=next;}throw failed;}
        finally{importing.remove(eventId,intake);}
    }
    @Override public boolean supports(String provider,String accountId,String audience,String mimeType)throws Exception {
        open();JSONObject profile=core.profile(accountId);return profile.optBoolean("enabled")&&provider.equals(profile.getString("provider"))
                &&provider.matches("qq|webhook")&&audience.equals("group")&&(mimeType.equals("text/plain")||provider.equals("qq")&&artifacts!=null&&mimeType.matches("image/(png|jpeg|webp)|audio/(mpeg|ogg|wav|silk)|video/mp4|application/(pdf|octet-stream)"));
    }
    @Override public Receipt send(Delivery delivery,GroupHostPort.Authority authority)throws Exception {
        open();authority.check();check(delivery.event);verify(delivery);
        if(!supports(delivery.event.provider,delivery.event.accountId,delivery.audience,delivery.output.mimeType))throw new IllegalStateException("此原生渠道不支持该受众或媒体类型");
        JSONObject original=proof(delivery.event.eventId).getJSONObject("event");
        boolean media=!delivery.output.mimeType.equals("text/plain");
        JSONObject queued=media?core.queueGroupMedia(delivery.deliveryId,original,delivery.output,authority):delivery.kind.equals("feedback")?core.queueGroupFeedback(delivery.deliveryId,original,delivery.output.text,authority):core.queueGroupOutput(delivery.deliveryId,original,delivery.output.text,authority);
        if(queued.getBoolean("known"))return inspect(delivery,authority); // Including queued: lost ACK never authorizes another send.
        JSONObject row=core.prepareGroupSend(delivery.deliveryId,authority),profile=core.profile(delivery.event.accountId);RunControl control=new RunControl();
        control.setAuthorityCheck(()->{authority.check();check(delivery.event);core.checkGroupSend(delivery.deliveryId,authority);});
        ChannelHttp.DispatchGuard guard=control::check;ActiveDelivery active=new ActiveDelivery(delivery.event.accountId,control);if(sending.putIfAbsent(delivery.deliveryId,active)!=null)throw new SecurityException("原群投递仍在发送");
        try {
            JSONObject actual;
            if(profile.getString("provider").equals("qq")){
                if(media){core.groupMediaPhase(delivery.deliveryId,"uploading","",authority);byte[] bytes=artifacts.deliveryBytes(delivery.output,delivery.taskId,authority);
                    JSONObject upload=http.uploadGroupMedia(profile,credentials.get(profile.getString("id")),original,delivery.output.mimeType,bytes,control,guard);String fileInfo=upload.optString("file_info");
                    if(fileInfo.isEmpty())throw new IllegalStateException("QQ原媒体上传没有返回明确file_info，未发送消息或重试上传");
                    core.groupMediaPhase(delivery.deliveryId,"uploaded",fileInfo,authority);core.groupMediaPhase(delivery.deliveryId,"message_sending","",authority);
                    actual=http.sendGroupMedia(profile,credentials.get(profile.getString("id")),original,row,fileInfo,control,guard);
                } else actual=http.sendQq(profile,credentials.get(profile.getString("id")),original,row,control,guard);
            }
            else actual=http.sendRelay(profile.optString("replyUrl"),credentials.get(profile.getString("id")),new JSONObject().put("deliveryId",delivery.deliveryId)
                    .put("replyTo",delivery.event.messageId).put("kind","group").put("roomId",delivery.event.roomId).put("senderId",delivery.event.senderId).put("audience","group").put("text",delivery.output.text),control,guard);
            String receipt=actual.optString("id",actual.optString("receiptId"));
            if(receipt.isEmpty()||!actual.optBoolean("accepted",true))core.delivery(delivery.deliveryId,"unknown","平台未返回明确受理回执","");
            else core.delivery(delivery.deliveryId,"sent","平台已受理；不代表成员已收到或阅读",receipt.substring(0,Math.min(180,receipt.length())));
        } catch(ChannelHttp.Rejected rejected){core.delivery(delivery.deliveryId,"failed","平台拒绝原群投递","");}
        catch(Exception unknown){core.delivery(delivery.deliveryId,"unknown","原群投递结果未知；只核对原编号，不自动重发","");}
        finally{sending.remove(delivery.deliveryId,active);}
        return inspect(delivery,queryAuthority(authority));
    }
    @Override public int remainingReplies(Event event,GroupHostPort.Authority authority)throws Exception {
        open();authority.checkRead();check(event);int slots=core.groupReplySlots(proof(event.eventId).getJSONObject("event"),authority);authority.checkRead();return slots;
    }
    @Override public Receipt inspect(Delivery delivery,GroupHostPort.Authority authority)throws Exception {
        open();authority.check();verify(delivery);JSONObject row=core.groupOutbox(delivery.deliveryId);authority.check();
        if(row==null)return null;
        if(!row.getString("eventId").equals(delivery.event.eventId)||!row.getString("profileId").equals(delivery.event.accountId)||!row.optString("groupTransportEpoch").equals(delivery.event.transportEpoch))throw new SecurityException("原投递回执身份冲突");
        if(delivery.output.mimeType.equals("text/plain")){if(!row.getString("text").equals(delivery.output.text))throw new SecurityException("原群文字产物已变化");}
        else {JSONObject media=row.optJSONObject("groupMedia");if(media==null||!media.optString("outputId").equals(delivery.output.outputId)||!media.optString("artifactId").equals(delivery.output.artifactId)||!media.optString("spaceId").equals(delivery.output.spaceId)||!media.optString("mimeType").equals(delivery.output.mimeType)||media.optLong("bytes")!=delivery.output.bytes||!media.optString("audience").equals(delivery.audience))throw new SecurityException("原群媒体产物已变化");}
        String status=row.getString("status"),state=status.equals("sent")?"accepted":status.matches("failed|cancelled")?"failed":"unknown";
        JSONObject reconciliation=row.optJSONObject("reconciliation");if(status.equals("sent")&&reconciliation!=null&&reconciliation.optString("source").equals("local_owner")&&reconciliation.optString("outcome").equals("delivered"))state="delivered";
        return new Receipt(delivery.deliveryId,state,row.optString("receiptId"));
    }
    /**
     * Native result viewer only: read the original outbox under its scoped owner read.
     * A platform upload token, original text, credential or URL never leaves this projection.
     */
    public JSONObject inspectDeliveryEvidence(GroupHostPort.Request request,String deliveryId,GroupHostPort.Output output,
            GroupHostPort.Authority readAuthority)throws Exception {
        return inspectDeliveryEvidence(request,deliveryId,output,request.event,readAuthority);
    }
    /** The renewed target comes only from GroupsModule.withOwnerDeliveryRead, never JSON. */
    public JSONObject inspectDeliveryEvidence(GroupHostPort.Request request,Delivery original,GroupHostPort.Output output,
            GroupHostPort.Authority readAuthority)throws Exception {
        if(!request.requestId.equals(original.requestId)||!request.taskId.equals(original.taskId)||!original.kind.equals("result")
                ||!original.output.outputId.equals(output.outputId)||!original.output.artifactId.equals(output.artifactId)
                ||!original.output.spaceId.equals(output.spaceId)||!original.output.audience.equals(output.audience)
                ||!original.output.mimeType.equals(output.mimeType)||original.output.bytes!=output.bytes||!original.output.text.equals(output.text)
                ||!request.event.provider.equals(original.event.provider)||!request.event.accountId.equals(original.event.accountId)||!request.event.roomId.equals(original.event.roomId))
            throw new SecurityException("原任务产物与原生投递上下文不一致");
        return inspectDeliveryEvidence(request,original.deliveryId,output,original.event,readAuthority);
    }
    private JSONObject inspectDeliveryEvidence(GroupHostPort.Request request,String deliveryId,GroupHostPort.Output output,Event target,
            GroupHostPort.Authority readAuthority)throws Exception {
        open();readAuthority.checkRead();
        if(!request.spaceId.equals(output.spaceId)||!request.audience.equals(output.audience))throw new SecurityException("投递产物不属于原群任务受众");
        String expected=java.util.UUID.nameUUIDFromBytes(("group-delivery:"+request.requestId+":"+output.outputId+":"+request.audience).getBytes(StandardCharsets.UTF_8)).toString();
        if(!expected.equals(deliveryId))throw new SecurityException("投递编号不是原任务产物的确定身份");
        JSONObject row=core.groupOutbox(deliveryId);readAuthority.checkRead();
        JSONObject result=new JSONObject().put("deliveryId",deliveryId).put("requestId",request.requestId).put("taskId",request.taskId)
                .put("outputId",output.outputId).put("spaceId",request.spaceId).put("audience",request.audience).put("queryOnly",true).put("readConfirmed",false)
                .put("originEventId",request.event.eventId).put("replyEventId",target.eventId).put("renewedReplyContext",!request.event.eventId.equals(target.eventId));
        if(row==null)return result.put("known",false).put("state","unreported").put("uploadState","unreported").put("messageAcknowledged",false).put("platformMessageId","");
        JSONObject event=row.optJSONObject("nativeGroupEvent");
        if(event==null||!target.eventId.equals(row.optString("eventId"))||!target.accountId.equals(row.optString("profileId"))
                ||!target.transportEpoch.equals(row.optString("groupTransportEpoch"))||!target.roomId.equals(event.optString("roomId"))
                ||!target.senderId.equals(event.optString("senderId"))||!target.messageId.equals(event.optString("messageId")))
            throw new SecurityException("原群Outbox来源与任务不一致");
        boolean media=!output.mimeType.equals("text/plain");String phase="unreported";
        if(media) {
            JSONObject identity=row.optJSONObject("groupMedia");
            if(identity==null||!output.outputId.equals(identity.optString("outputId"))||!output.artifactId.equals(identity.optString("artifactId"))
                    ||!output.spaceId.equals(identity.optString("spaceId"))||!output.audience.equals(identity.optString("audience"))
                    ||!output.mimeType.equals(identity.optString("mimeType"))||output.bytes!=identity.optLong("bytes",-1))
                throw new SecurityException("原群Outbox媒体与已读产物不一致");
            phase=row.optString("mediaPhase","unreported");
        } else if(!output.text.equals(row.optString("text"))||row.optJSONObject("groupMedia")!=null)throw new SecurityException("原群Outbox文字产物不一致");
        String status=row.getString("status"),messageId=row.optString("receiptId"),state=status.equals("sent")&&!messageId.isEmpty()?"accepted":status.matches("failed|cancelled")?"failed":"unknown";
        JSONObject reconciliation=row.optJSONObject("reconciliation");
        if(state.equals("accepted")&&reconciliation!=null&&reconciliation.optString("source").equals("local_owner")&&reconciliation.optString("outcome").equals("delivered"))state="delivered";
        String upload=!media?"not_required":phase.equals("prepared")?"not_started":phase.equals("uploading")?"uploading":phase.matches("uploaded|message_sending")?"uploaded":"unreported";
        result.put("known",true).put("state",state).put("uploadState",upload).put("mediaPhase",phase).put("platformMessageId",messageId)
                .put("messageAcknowledged",state.matches("accepted|delivered")).put("observedAt",System.currentTimeMillis()).put("updatedAt",row.optLong("updatedAt",row.optLong("createdAt")));
        if(status.matches("unknown|failed|cancelled")) {
            String message=row.optString("message");if(message.length()>500)message=message.substring(0,500);
            result.put("error",new JSONObject().put("code",status.equals("unknown")?"delivery_unknown":"delivery_failed").put("message",message).put("queryOnly",true));
        }
        readAuthority.checkRead();return result;
    }
    private static GroupHostPort.Authority queryAuthority(GroupHostPort.Authority source){return new GroupHostPort.Authority(){public void check()throws Exception{source.checkRead();}public void checkRead()throws Exception{source.checkRead();}};}
    private static void verify(Delivery delivery)throws Exception {
        if(!delivery.audience.equals(delivery.output.audience)||!delivery.senderId.equals(delivery.event.senderId)||!delivery.output.spaceId.startsWith("group:"))throw new SecurityException("群投递产物或受众无效");
    }
    private synchronized JSONObject proof(String id)throws Exception {open();JSONObject found=proofs.optJSONObject(id);if(found==null)throw new SecurityException("没有此原生认证群事件");return new JSONObject(found.toString());}
    private void save(JSONObject next)throws Exception {byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024L)throw new IllegalStateException("群事件凭据容量已满");File pending=new File(proofFile.getParentFile(),proofFile.getName()+".pending");try(FileOutputStream out=new FileOutputStream(pending)){out.write(bytes);out.getFD().sync();}Files.move(pending.toPath(),proofFile.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static boolean statusCommand(String text){return text.startsWith("/task-status ")||text.startsWith("/status ");}
    private static boolean cancelCommand(String text){return text.startsWith("/task-cancel ")||text.startsWith("/cancel ");}
    static boolean isCommand(String text){return text.matches("(?s)/(task|search|file|image|audio|video)( .*)?")||statusCommand(text)||cancelCommand(text);}
    private static String capability(String command){if(command.startsWith("/search "))return "public.search";if(command.startsWith("/file "))return "group.files";if(command.startsWith("/image "))return "media.image";if(command.startsWith("/audio "))return "media.audio";if(command.startsWith("/video "))return "media.video";return "public.answer";}
    private static String goal(String text){return options(text).goal;}
    private static boolean shared(String text){return options(text).shared;}
    private static final long MEDIA_FLOW_RESERVATION=32_000L;
    private static final class Options {final String goal;final boolean shared;final long tokens;Options(String goal,boolean shared,long tokens){this.goal=goal;this.shared=shared;this.tokens=tokens;}}
    private static Options options(String text){
        int space=text.indexOf(' ');String rest=space<0?"":text.substring(space+1).trim();boolean share=false,explicit=false;long tokens=capability(text).startsWith("media.")?MEDIA_FLOW_RESERVATION:4000;
        while(rest.startsWith("--")){
            int next=rest.indexOf(' ');String flag=next<0?rest:rest.substring(0,next);rest=next<0?"":rest.substring(next+1).trim();
            if(flag.equals("--share")&&!share)share=true;
            else if(flag.startsWith("--tokens=")&&!explicit){String value=flag.substring(9);if(!value.matches("[0-9]{1,6}"))throw new IllegalArgumentException("群模型额度参数格式为--tokens=整数");tokens=Long.parseLong(value);explicit=true;}
            else throw new IllegalArgumentException("群命令参数未知或重复；可使用--share和--tokens=N");
        }
        if(rest.isEmpty())throw new IllegalArgumentException("请在命令参数后填写目标或原任务编号");
        if(tokens<1||tokens>100000)throw new IllegalArgumentException("群模型额度请求超出范围");
        if(capability(text).startsWith("media.")&&tokens<MEDIA_FLOW_RESERVATION)throw new IllegalArgumentException("媒体命令的本地流程预留至少为"+MEDIA_FLOW_RESERVATION+"，用于原参数审批和同任务续接；本次只指定"+tokens+"，未启动模型。请在主人已授权上限内选择--tokens=N，已保存限额不会自动提高");
        return new Options(rest,share,tokens);
    }
    private static long requestedTokens(String text){return options(text).tokens;}
    private static String safe(Exception error){return error instanceof SecurityException||error instanceof IllegalStateException||error instanceof IllegalArgumentException?error.getMessage():"群任务未取得完成回执；原请求不会自动重试";}
    boolean cancelSending(String id){ActiveDelivery active=sending.get(id);if(active==null)return false;active.control.cancel();return true;}
    void cancelProfile(String accountId){for(ActiveDelivery active:sending.values())if(active.accountId.equals(accountId))active.control.cancel();for(ActiveDelivery active:importing.values())if(active.accountId.equals(accountId))active.control.cancel();}
    private static final class ActiveDelivery {final String accountId;final RunControl control;ActiveDelivery(String accountId,RunControl control){this.accountId=accountId;this.control=control;}}
    private void open(){if(closed)throw new IllegalStateException("原生群渠道已关闭");}
    @Override public void close(){closed=true;for(ActiveDelivery active:sending.values())active.control.cancel();for(ActiveDelivery active:importing.values())active.control.cancel();groups=null;workers=null;}
}
