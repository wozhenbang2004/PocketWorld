package dev.pocketengine.features.channels;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable routing authority. Only authenticated transports may supply canonical events. */
public final class ChannelCore {
    private final File file;
    private JSONObject state;

    public ChannelCore(File directory) throws Exception {
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建消息渠道目录");
        file=new File(directory,"channels.json");
        if(file.isFile()) {
            if(file.length()>4_000_000)throw new IllegalStateException("消息记录超出读取上限");
            state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        } else state=new JSONObject();
        for(String key:new String[]{"profiles","bindings","spaces","inbox","outbox","seen","taskRequests"})
            if(state.optJSONArray(key)==null)state.put(key,new JSONArray());
        boolean changed=false;
        for(int i=0;i<array("profiles").length();i++){JSONObject profile=array("profiles").getJSONObject(i);if(!profile.has("transportEpoch")){profile.put("transportEpoch",UUID.randomUUID().toString());changed=true;}}
        for(int i=0;i<array("bindings").length();i++){
            JSONObject binding=array("bindings").getJSONObject(i);
            if(!binding.has("bindingEpoch")){binding.put("bindingEpoch",UUID.randomUUID().toString());resetTaskGrant(binding);changed=true;}
        }
        for(int i=0;i<array("taskRequests").length();i++){
            JSONObject request=array("taskRequests").getJSONObject(i);
            if("admitting".equals(request.optString("admission"))){request.put("admission","admission_unknown");changed=true;}
            if("requesting".equals(request.optString("cancellation"))){request.put("cancellation","cancellation_unknown");changed=true;}
        }
        for(int i=0;i<array("outbox").length();i++) {
            JSONObject row=array("outbox").getJSONObject(i);
            if("sending".equals(row.optString("status"))){row.put("status","unknown").put("message","发送进程中断，投递状态未知，请先在原平台核对");changed=true;}
        }
        for(int i=0;i<array("inbox").length();i++) {
            JSONObject row=array("inbox").getJSONObject(i);
            if("answering".equals(row.optString("status"))||"queued".equals(row.optString("status"))){row.put("status","interrupted");changed=true;}
        }
        if(changed)save();
    }

    public synchronized JSONObject saveProfile(JSONObject input) throws Exception {
        String id=input.optString("id").isEmpty()?UUID.randomUUID().toString():identifier(input,"id");
        JSONObject previous=find("profiles","id",id);
        String provider=input.optString("provider",previous==null?"webhook":previous.optString("provider"));
        if(!provider.equals("webhook")&&!provider.equals("qq")&&!provider.equals("weixin"))throw new IllegalArgumentException("当前支持可信桥接、QQ官方机器人和微信iLink私聊");
        String appId=provider.equals("qq")||provider.equals("weixin")?text(input,"appId",180,false):"";
        String apiBase=provider.equals("weixin")?WeixinClient.validateApiBase(input.optString("apiBase",previous==null?WeixinClient.DEFAULT_BASE:previous.optString("apiBase",WeixinClient.DEFAULT_BASE))):"";
        if(previous!=null&&(!provider.equals(previous.optString("provider"))||!appId.equals(previous.optString("appId"))))
            throw new IllegalArgumentException("平台或 AppID 改变时请新建连接，避免沿用旧身份绑定");
        if(previous==null&&array("profiles").length()>=20)throw new IllegalStateException("最多保存20个渠道连接");
        if(input.has("ownerImagesEnabled")&&!(input.opt("ownerImagesEnabled") instanceof Boolean))throw new IllegalArgumentException("请明确选择是否处理主人私聊图片");
        JSONObject profile=new JSONObject().put("id",id).put("name",text(input,"name",80,false))
                .put("provider",provider).put("appId",appId).put("enabled",input.optBoolean("enabled",false))
                .put("apiBase",apiBase).put("transportEpoch",UUID.randomUUID().toString())
                .put("autoReply",input.optBoolean("autoReply",false)).put("ownerImagesEnabled",provider.equals("weixin")&&input.optBoolean("ownerImagesEnabled",false)).put("replyUrl",input.optString("replyUrl","").trim())
                .put("maxRepliesPerHour",Math.max(1,Math.min(200,input.optInt("maxRepliesPerHour",30))))
                .put("updatedAt",System.currentTimeMillis());
        replace("profiles","id",id,profile);save();return copy(profile);
    }
    public synchronized JSONObject profile(String id)throws Exception {
        JSONObject profile=find("profiles","id",id);if(profile==null)throw new IllegalArgumentException("渠道连接不存在");return copy(profile);
    }
    public synchronized void prepareCredentialChange(String id)throws Exception {
        JSONObject profile=find("profiles","id",id);if(profile==null)throw new IllegalArgumentException("渠道连接不存在");
        JSONObject binding=find("bindings","profileId",id);if(binding!=null){binding.put("bindingEpoch",UUID.randomUUID().toString());resetTaskGrant(binding);}
        profile.put("enabled",false).put("configurationPending",true).put("transportEpoch",UUID.randomUUID().toString());save();
    }
    public synchronized void removeProfile(String id)throws Exception {
        remove("profiles","id",id);remove("bindings","profileId",id);
        for(String key:new String[]{"inbox","outbox","spaces"})remove(key,"profileId",id);
        save();
    }

    /** Sender ids come only from a verified signature, authenticated official session or trusted relay. */
    public synchronized JSONObject accept(String profileId,JSONObject canonical,String authentication) throws Exception {
        JSONObject profile=profile(profileId);
        if(!profile.optBoolean("enabled"))throw new IllegalStateException("此连接已暂停");
        if(!authentication.equals("qq_signature")&&!authentication.equals("qq_gateway")&&!authentication.equals("trusted_relay")&&!authentication.equals("weixin_session"))throw new SecurityException("消息未认证");
        if(profile.optString("provider").equals("qq")&&!authentication.equals("qq_signature")&&!authentication.equals("qq_gateway"))throw new SecurityException("QQ连接必须验证平台签名或官方已授权网关会话");
        if(!profile.optString("provider").equals("qq")&&(authentication.equals("qq_signature")||authentication.equals("qq_gateway")))throw new SecurityException("QQ认证不能用于其他渠道消息");
        if(profile.optString("provider").equals("weixin")&&!authentication.equals("weixin_session"))throw new SecurityException("微信连接只接受已授权官方会话的消息");
        if(!profile.optString("provider").equals("weixin")&&authentication.equals("weixin_session"))throw new SecurityException("微信会话不能认证其他渠道消息");
        String messageId=identifier(canonical,"messageId"),senderId=identifier(canonical,"senderId");
        String kind=canonical.optString("kind");
        if(!kind.equals("dm")&&!kind.equals("group"))throw new IllegalArgumentException("未知会话类型");
        if(profile.optString("provider").equals("weixin")&&!kind.equals("dm"))throw new SecurityException("当前微信适配器只支持私人文字会话");
        String roomId=kind.equals("group")?identifier(canonical,"roomId"):"";
        String key=digest(profileId+"\u0000"+messageId);
        JSONObject seen=find("seen","key",key);
        if(seen!=null)return new JSONObject().put("duplicate",true).put("eventId",seen.getString("eventId"));
        long now=System.currentTimeMillis();
        JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("profileId",profileId)
                .put("messageId",messageId).put("senderId",senderId).put("roomId",roomId).put("kind",kind)
                .put("text",text(canonical,"text",4000,profile.getString("provider").equals("weixin")&&ChannelMessageParts.weixinImagePositions(canonical.optJSONObject("messageParts")).length()>0)).put("authenticatedBy",authentication).put("transportEpoch",profile.getString("transportEpoch"))
                .put("receivedAt",now).put("sentAt",canonical.optLong("sentAt",now)).put("status","received");
        if(kind.equals("group")&&profile.getString("provider").equals("qq")&&canonical.optJSONArray("groupAttachments")!=null)row.put("groupAttachments",copy(canonical.getJSONArray("groupAttachments")));
        if(canonical.optJSONObject("messageParts")!=null)row.put("messageParts",ChannelMessageParts.authenticatedCopy(canonical.getJSONObject("messageParts"),profile.getString("provider"),messageId));
        if(canonical.optBoolean("partialInput"))row.put("partialInput",true).put("inputNotice","已保留文字与引用；其他消息项未下载/解密，不能声称已读取媒体");
        JSONObject route=route(row);row.put("route",route);
        if(profile.getString("provider").equals("weixin")&&authentication.equals("weixin_session"))resolveOriginalWeixinQuotes(row,now);
        if(find("spaces","spaceId",route.getString("spaceId"))==null) {
            if(array("spaces").length()>=200)throw new IllegalStateException("会话空间已达200个，请删除不用的连接");
            array("spaces").put(new JSONObject().put("profileId",profileId).put("spaceId",route.getString("spaceId"))
                    .put("kind",kind).put("capabilities",new JSONArray().put("chat")));
        }
        appendBounded("inbox",row,200);
        appendBounded("seen",new JSONObject().put("key",key).put("eventId",row.getString("id")).put("at",now),2000);
        save();return new JSONObject().put("duplicate",false).put("event",copy(row)).put("route",route);
    }

    /** Original bounded inbox only: no network history lookup, second cache or cross-space fallback. */
    private void resolveOriginalWeixinQuotes(JSONObject incoming,long now)throws Exception {
        JSONObject structured=incoming.optJSONObject("messageParts");if(structured==null)return;JSONArray parts=structured.getJSONArray("parts");
        for(int i=0;i<parts.length();i++){
            JSONObject quote=parts.getJSONObject(i);if(!quote.optString("kind").equals("quote")||!quote.optBoolean("resolveFromOriginalInbox"))continue;
            String reference=quote.optString("quoteRef"),selectedText=null;JSONObject selected=null;boolean ambiguous=false;
            for(int j=0;j<array("inbox").length();j++){
                JSONObject original=array("inbox").getJSONObject(j);if(!sameWeixinQuoteConversation(incoming,original)||now-original.getLong("receivedAt")>30L*86_400_000L)continue;
                String candidate=null;
                if(quote.optString("referenceIdKind").equals("server_message_id")){
                    if(original.getString("messageId").equals("wx-"+reference))candidate=original.optJSONObject("messageParts")==null?original.getString("text"):ChannelMessageParts.currentText(original.getJSONObject("messageParts"));
                }else {
                    JSONObject sourceParts=original.optJSONObject("messageParts");JSONArray source=sourceParts==null?null:sourceParts.optJSONArray("parts");
                    for(int k=0;source!=null&&k<source.length();k++){JSONObject text=source.getJSONObject(k);if(text.optString("kind").equals("text")&&reference.equals(text.optString("nativeItemId"))){if(candidate!=null){ambiguous=true;break;}candidate=text.optString("text");}}
                }
                if(candidate==null)continue;if(selected!=null||ambiguous){ambiguous=true;break;}selected=original;selectedText=candidate;
            }
            quote.put("resolveFromOriginalInbox",false).put("cacheBounds","original_inbox_max200_30days");
            if(selected==null||ambiguous){quote.put("reason",ambiguous?"ambiguous_original_message_id":"original_not_in_current_account_conversation");continue;}
            ChannelMessageParts.quotedText(quote,selectedText,"original_inbox");
            if(quote.optString("availability").equals("original_inbox"))quote.put("originalSource",new JSONObject().put("eventId",selected.getString("id")).put("messageId",selected.getString("messageId"))
                    .put("profileId",selected.getString("profileId")).put("transportEpoch",selected.getString("transportEpoch")).put("senderId",selected.getString("senderId")).put("receivedAt",selected.getLong("receivedAt")));
        }
    }
    private static boolean sameWeixinQuoteConversation(JSONObject incoming,JSONObject original)throws Exception {
        if(!incoming.getString("kind").equals("dm")||!original.optString("kind").equals("dm")||!original.optString("authenticatedBy").equals("weixin_session")
                ||!incoming.getString("profileId").equals(original.optString("profileId"))||!incoming.getString("senderId").equals(original.optString("senderId"))
                ||!incoming.getString("transportEpoch").equals(original.optString("transportEpoch")))return false;
        JSONObject a=incoming.getJSONObject("route"),b=original.optJSONObject("route");return b!=null&&a.getString("spaceId").equals(b.optString("spaceId"))&&a.optString("bindingEpoch").equals(b.optString("bindingEpoch"));
    }

    public synchronized JSONObject route(JSONObject event)throws Exception {
        JSONObject profile=profile(event.getString("profileId"));
        JSONObject binding=find("bindings","profileId",profile.getString("id"));
        boolean owner=event.optString("kind").equals("dm")&&binding!=null&&binding.optString("senderId").equals(event.optString("senderId"));
        String space=owner?"private":profile.getString("provider")+":"+profile.getString("id")+":"+event.getString("kind")+":"
                +digest(event.optString("kind").equals("group")?event.getString("roomId"):event.getString("senderId")).substring(0,32);
        String principal=owner?"owner":"external:"+digest(profile.getString("id")+"|"+event.getString("kind")+"|"+event.optString("roomId")+"|"+event.getString("senderId")).substring(0,32);
        JSONArray grants=owner?new JSONArray().put("chat").put("memory.private").put("phone.request").put("remote.request").put("files.private"):new JSONArray().put("chat");
        JSONObject configured=find("spaces","spaceId",space);
        if(!owner&&configured!=null)grants=new JSONArray(configured.getJSONArray("capabilities").toString());
        return new JSONObject().put("ownerId","owner").put("spaceId",space).put("principalId",principal).put("privateOwner",owner)
                .put("bindingEpoch",owner?binding.optString("bindingEpoch"):"")
                .put("ownerTasksEnabled",owner&&binding.optBoolean("ownerTasksEnabled",false))
                .put("taskGrantEpoch",owner?binding.optString("taskGrantEpoch"):"")
                .put("capabilities",grants).put("scope",new JSONObject().put("ownerId","owner").put("spaceId",space));
    }
    public synchronized JSONObject bindOwner(String profileId,String eventId,boolean confirmed)throws Exception {
        if(!confirmed)throw new SecurityException("请在本机确认绑定账号");
        JSONObject event=event(eventId);
        if(!event.getString("profileId").equals(profileId)||!event.getString("kind").equals("dm"))throw new SecurityException("只能绑定此连接已认证的私聊账号");
        try{checkControlOrigin(event);}catch(SecurityException stale){throw new SecurityException("此私聊的认证来源或连接代次已变化，请接收一条新消息后重新核对");}
        if(System.currentTimeMillis()-event.getLong("receivedAt")>24*60*60*1000L)throw new SecurityException("绑定依据已过期，请重新发一条私聊消息");
        JSONObject binding=new JSONObject().put("profileId",profileId).put("senderId",event.getString("senderId"))
                .put("verifiedEventId",eventId).put("boundAt",System.currentTimeMillis()).put("principalId","owner").put("bindingEpoch",UUID.randomUUID().toString());
        resetTaskGrant(binding);
        replace("bindings","profileId",profileId,binding);save();return copy(binding);
    }
    public synchronized void unbindOwner(String profileId)throws Exception {remove("bindings","profileId",profileId);save();}
    /** This separate, native opt-in is never accepted from profile fields or incoming text. */
    public synchronized JSONObject setOwnerTasks(String profileId,boolean enabled,boolean confirmed)throws Exception {
        if(!confirmed)throw new SecurityException("请在本机确认主人私聊任务权限");
        JSONObject profile=profile(profileId),binding=find("bindings","profileId",profileId);
        if(binding==null)throw new SecurityException("请先绑定已认证的主人私聊账号");
        if(enabled&&!profile.optBoolean("enabled"))throw new SecurityException("请先启用此渠道连接");
        binding.put("ownerTasksEnabled",enabled).put("taskGrantEpoch",UUID.randomUUID().toString());save();return copy(binding);
    }
    /** Native reviewed opt-in: compare and mutate under the same authority lock. */
    public synchronized JSONObject setOwnerTasks(String profileId,boolean enabled,boolean confirmed,String expectedBindingEpoch,String expectedTaskGrantEpoch)throws Exception {
        JSONObject binding=find("bindings","profileId",profileId);
        if(binding==null||expectedBindingEpoch==null||expectedTaskGrantEpoch==null||expectedBindingEpoch.isEmpty()||expectedTaskGrantEpoch.isEmpty()
                ||!expectedBindingEpoch.equals(binding.optString("bindingEpoch"))||!expectedTaskGrantEpoch.equals(binding.optString("taskGrantEpoch")))
            throw new SecurityException("主人绑定或任务权限已变化，请重新核对后确认");
        return setOwnerTasks(profileId,enabled,confirmed);
    }
    private static void resetTaskGrant(JSONObject binding)throws Exception{binding.put("ownerTasksEnabled",false).put("taskGrantEpoch",UUID.randomUUID().toString());}

    /** Persist once before crossing the host boundary. Never evict origin deduplication records. */
    public synchronized JSONObject prepareHostTask(String eventId)throws Exception {
        JSONObject event=event(eventId),authority=route(event);requireTaskRoute(event,authority);
        String key=digest(event.getString("profileId")+"\u0000"+event.getString("messageId"));
        JSONObject existing=find("taskRequests","eventKey",key);
        if(existing!=null){checkHostTaskAuthority(existing.getString("requestId"));return new JSONObject().put("known",true).put("request",taskMetadata(existing));}
        String text=event.getString("text");if(!text.startsWith("/task ")||text.substring(6).trim().isEmpty())throw new IllegalArgumentException("任务命令格式为 /task 目标");
        if(array("taskRequests").length()>=500)throw new IllegalStateException("渠道任务来源账簿已满，未丢弃去重证据");
        JSONObject request=new JSONObject().put("requestId",UUID.randomUUID().toString()).put("taskId",UUID.randomUUID().toString())
                .put("eventKey",key).put("originEvent",event).put("goal",text.substring(6).trim())
                .put("bindingEpoch",authority.getString("bindingEpoch")).put("taskGrantEpoch",authority.getString("taskGrantEpoch"))
                .put("admission","admitting").put("createdAt",System.currentTimeMillis());
        array("taskRequests").put(request);save();return new JSONObject().put("known",false).put("request",taskMetadata(request));
    }
    public synchronized HostTaskPort.Request hostTaskRequest(String requestId)throws Exception {
        checkHostTaskAuthority(requestId);return new HostTaskPort.Request(requiredTask(requestId));
    }
    public synchronized void checkHostTaskAuthority(String requestId)throws Exception {
        JSONObject request=requiredTask(requestId),event=request.getJSONObject("originEvent"),authority=route(event);requireTaskRoute(event,authority);
        if(!request.getString("bindingEpoch").equals(authority.getString("bindingEpoch"))||!request.getString("taskGrantEpoch").equals(authority.getString("taskGrantEpoch")))throw new SecurityException("此渠道任务的主人绑定或授权代次已失效");
    }
    private void requireTaskRoute(JSONObject event,JSONObject authority)throws Exception {
        JSONObject original=event.getJSONObject("route");
        if(!profile(event.getString("profileId")).optBoolean("enabled")||!event.getString("kind").equals("dm")
                ||!authority.optBoolean("privateOwner")||!"owner".equals(authority.optString("principalId"))||!"private".equals(authority.optString("spaceId"))
                ||!authority.optBoolean("ownerTasksEnabled")||!original.optBoolean("ownerTasksEnabled")
                ||authority.optString("bindingEpoch").isEmpty()||!authority.getString("bindingEpoch").equals(original.optString("bindingEpoch"))
                ||!authority.getString("taskGrantEpoch").equals(original.optString("taskGrantEpoch")))throw new SecurityException("此消息没有当前主人私聊的独立任务授权");
        requireChat(authority);
    }
    /** Persist authoritative host identity before any independent reply/delivery attempt. */
    public synchronized JSONObject hostTaskAdmitted(String requestId,HostTaskPort.TaskState receipt)throws Exception {
        JSONObject request=requiredTask(requestId);checkTaskReceipt(request,receipt);checkHostTaskAuthority(requestId);
        long previous=request.optLong("hostReceiptRevision",request.optLong("lastUpdateRevision",0));
        int epoch=request.optInt("hostCoordinatorEpoch",0);String run=request.optString("hostRunId","");
        if(receipt.coordinatorEpoch==epoch&&!receipt.runId.equals(run))throw new SecurityException("同代宿主执行身份冲突");
        // An admit() return may race a faster worker's newer result. It may confirm
        // the linkage, but must never replace that result or its cancellation target.
        if(receipt.revision<previous){
            if(receipt.coordinatorEpoch>epoch)throw new SecurityException("新执行代次不能使用旧宿主版本");
            return taskMetadata(request);
        }
        if(receipt.coordinatorEpoch<epoch)throw new SecurityException("旧执行代次不能覆盖新任务");
        String fingerprint=digest(receipt.runId+"\u0000"+receipt.coordinatorEpoch+"\u0000"+receipt.status+"\u0000"+receipt.summary);
        if(receipt.revision==previous&&request.has("hostReceiptFingerprint")){
            if(!fingerprint.equals(request.getString("hostReceiptFingerprint")))throw new SecurityException("同一宿主回执版本的内容冲突");
            return taskMetadata(request);
        }
        request.put("admission","admitted").put("hostRunId",receipt.runId).put("hostCoordinatorEpoch",receipt.coordinatorEpoch)
                .put("hostReceiptRevision",receipt.revision).put("hostReceiptFingerprint",fingerprint).put("hostStatus",receipt.status);
        save();return taskMetadata(request);
    }
    /** The command event must itself be a current authenticated owner DM from this exact origin. */
    public synchronized void authorizeHostTaskCommand(String requestId,String eventId)throws Exception {
        JSONObject command=event(eventId),origin=requiredTask(requestId).getJSONObject("originEvent");requireTaskRoute(command,route(command));checkHostTaskAuthority(requestId);
        if(!command.getString("profileId").equals(origin.getString("profileId"))||!command.getString("senderId").equals(origin.getString("senderId")))throw new SecurityException("不能从其他渠道会话操作此任务");
    }
    public synchronized HostTaskPort.Request prepareHostTaskCancellation(String requestId)throws Exception {
        checkHostTaskAuthority(requestId);JSONObject request=requiredTask(requestId);String run=request.optString("hostRunId","");int epoch=request.optInt("hostCoordinatorEpoch",0);
        if(request.has("cancellation")){
            if(run.equals(request.optString("cancellationRunId"))&&epoch==request.optInt("cancellationEpoch",0))return null;
            if(epoch<=request.optInt("cancellationEpoch",0))throw new SecurityException("没有取得新的宿主执行代次，不能再次发送取消");
        }
        request.put("cancellation","requesting").put("cancellationRunId",run).put("cancellationEpoch",epoch);save();return new HostTaskPort.Request(request,true);
    }
    public synchronized void hostTaskCancellation(String requestId,String expectedRunId,int expectedEpoch,boolean acknowledged)throws Exception {
        JSONObject request=requiredTask(requestId);if(!expectedRunId.equals(request.optString("cancellationRunId"))||expectedEpoch!=request.optInt("cancellationEpoch",0))return;
        request.put("cancellation",acknowledged?"requested":"cancellation_unknown");save();
    }
    public synchronized void hostTaskAdmissionUnknown(String requestId)throws Exception {
        JSONObject request=requiredTask(requestId);if(!request.optString("admission").equals("admitted")){request.put("admission","admission_unknown");save();}
    }
    /** A host update creates at most one outbox row for its strictly monotone revision. */
    public synchronized JSONObject queueHostTaskUpdate(String requestId,HostTaskPort.TaskState receipt)throws Exception {
        JSONObject request=requiredTask(requestId);checkTaskReceipt(request,receipt);checkHostTaskAuthority(requestId);
        if(!request.optString("admission").equals("admitted"))throw new IllegalStateException("宿主任务关联尚未确认，未投递结果");
        long observed=request.optLong("hostReceiptRevision",request.optLong("lastUpdateRevision",0));
        if(receipt.revision<observed)throw new SecurityException("旧宿主回执不能覆盖已核对的新结果");
        if(receipt.revision>observed){hostTaskAdmitted(requestId,receipt);request=requiredTask(requestId);}
        else if(request.has("hostStatus")&&!receipt.status.equals(request.getString("hostStatus")))throw new SecurityException("同一宿主回执版本的状态冲突");
        // Host metadata is already durable. A quota/window/queue failure below
        // affects delivery only, never the current run used by an explicit stop.
        int epoch=request.optInt("hostCoordinatorEpoch",0);String run=request.optString("hostRunId","");
        if(receipt.coordinatorEpoch<epoch||receipt.coordinatorEpoch==epoch&&!receipt.runId.equals(run))throw new SecurityException("旧执行代次或冲突执行身份的回执不能覆盖新任务");
        String fingerprint=digest(receipt.runId+"\u0000"+receipt.coordinatorEpoch+"\u0000"+receipt.status+"\u0000"+receipt.summary);long previous=request.optLong("lastUpdateRevision",0);
        if(receipt.revision<previous)throw new SecurityException("旧宿主回执不能覆盖新结果");
        if(receipt.revision==previous){
            if(!fingerprint.equals(request.optString("lastUpdateFingerprint")))throw new SecurityException("同一宿主回执版本的内容冲突");
            JSONObject retained=find("outbox","id",request.getString("lastOutboxId"));return retained==null?new JSONObject().put("id",request.getString("lastOutboxId")).put("status","not_retained"):copy(retained);
        }
        JSONObject event=request.getJSONObject("originEvent"),row=queueReplyRecord(event,"任务 "+receipt.taskId+"\n来源 "+requestId+"\n状态："+receipt.status+(receipt.summary.isEmpty()?"":"\n"+receipt.summary));
        row.put("taskRequestId",requestId).put("hostTaskId",receipt.taskId).put("hostRevision",receipt.revision)
                .put("hostRunId",receipt.runId).put("hostCoordinatorEpoch",receipt.coordinatorEpoch)
                .put("taskGrantEpoch",request.getString("taskGrantEpoch"));
        request.put("hostRunId",receipt.runId).put("hostCoordinatorEpoch",receipt.coordinatorEpoch)
                .put("lastUpdateRevision",receipt.revision).put("lastUpdateFingerprint",fingerprint).put("lastOutboxId",row.getString("id"));save();return copy(row);
    }
    public synchronized JSONObject hostTask(String requestId)throws Exception {return taskMetadata(requiredTask(requestId));}
    private JSONObject requiredTask(String id)throws Exception {JSONObject request=find("taskRequests","requestId",id);if(request==null)throw new IllegalArgumentException("渠道任务来源不存在");return request;}
    private static void checkTaskReceipt(JSONObject request,HostTaskPort.TaskState receipt)throws Exception{if(receipt==null||!request.getString("taskId").equals(receipt.taskId))throw new SecurityException("宿主回执不属于此渠道任务");}
    private static JSONObject taskMetadata(JSONObject request)throws Exception {
        JSONObject event=request.getJSONObject("originEvent"),result=new JSONObject().put("requestId",request.getString("requestId")).put("taskId",request.getString("taskId")).put("eventId",event.getString("id"))
                .put("profileId",event.getString("profileId")).put("bindingEpoch",request.getString("bindingEpoch")).put("taskGrantEpoch",request.getString("taskGrantEpoch"))
                .put("admission",request.getString("admission")).put("createdAt",request.getLong("createdAt"));
        if(request.has("cancellation"))result.put("cancellation",request.getString("cancellation"));
        if(request.has("hostRunId"))result.put("hostRunId",request.getString("hostRunId")).put("hostCoordinatorEpoch",request.getInt("hostCoordinatorEpoch"));
        if(request.has("hostReceiptRevision"))result.put("hostReceiptRevision",request.getLong("hostReceiptRevision")).put("hostStatus",request.getString("hostStatus"));
        if(request.has("lastOutboxId"))result.put("lastOutboxId",request.getString("lastOutboxId")).put("lastUpdateRevision",request.getLong("lastUpdateRevision"));return result;
    }
    public synchronized JSONObject grant(String spaceId,JSONArray capabilities)throws Exception {
        if(spaceId.equals("private"))throw new SecurityException("主人权限由已认证账号绑定确定");
        JSONObject space=find("spaces","spaceId",spaceId);if(space==null)throw new IllegalArgumentException("会话空间不存在");
        JSONArray accepted=new JSONArray();
        if(capabilities.length()>1)throw new SecurityException("当前共享会话仅开放文字问答能力");
        for(int i=0;i<capabilities.length();i++) {
            if(!"chat".equals(capabilities.getString(i)))throw new SecurityException("共享会话不能获得私人设备或记忆权限");
            accepted.put("chat");
        }
        space.put("capabilities",accepted);save();return copy(space);
    }
    public synchronized JSONObject event(String id)throws Exception {JSONObject row=find("inbox","id",id);if(row==null)throw new IllegalArgumentException("消息记录不存在");return copy(row);}
    private JSONObject originalSeenEvent(String id)throws Exception {
        JSONObject original=find("inbox","id",id);if(original!=null)return original;
        for(int i=0;i<array("taskRequests").length();i++){JSONObject origin=array("taskRequests").getJSONObject(i).optJSONObject("originEvent");if(origin!=null&&id.equals(origin.optString("id")))return origin;}
        for(int i=0;i<array("outbox").length();i++){JSONObject row=array("outbox").getJSONObject(i);for(String key:new String[]{"nativeGroupEvent","nativeRemoteEvent","nativeRemoteResultEvent","nativeCompletionEvent"}){JSONObject origin=row.optJSONObject(key);if(origin!=null&&id.equals(origin.optString("id")))return origin;}}
        return null;
    }

    /** Body-free native observation. This is neither a waiting-task association nor audience grant.
     * Only original retained inbox/task/outbox source records may back a cold read. */
    synchronized JSONObject inboundFactMetadata(String id)throws Exception {
        JSONObject event=originalSeenEvent(id);if(event==null)throw new SecurityException("原认证消息已不在有界来源记录中，未补造事件");checkControlOrigin(event);
        JSONObject profile=profile(event.getString("profileId")),received=event.getJSONObject("route"),current=route(event);
        for(String key:new String[]{"ownerId","spaceId","principalId","bindingEpoch"})if(!received.optString(key).equals(current.optString(key)))throw new SecurityException("原消息接收受众/主人绑定已变化");
        if(received.optBoolean("privateOwner")!=current.optBoolean("privateOwner"))throw new SecurityException("旧外部材料不能因后续绑定升级主人事实");
        JSONObject payload=new JSONObject().put("text",event.getString("text"));if(event.optJSONObject("messageParts")!=null)payload.put("messageParts",event.getJSONObject("messageParts"));
        String provider=profile.getString("provider");JSONObject facts=new JSONObject().put("format","pocketworld.channel-inbound-fact.v1").put("eventId",id).put("payloadVersion","original_channel_inbound_v1").put("payloadSha256",dev.pocketengine.store.TaskStore.capabilityFingerprint(payload))
                .put("provider",provider).put("profileId",profile.getString("id")).put("appId",profile.optString("appId")).put("transportEpoch",event.getString("transportEpoch"))
                .put("conversationKind",provider.equals("qq")?QqConversation.scene(event):event.getString("kind")).put("conversationId",event.getString("kind").equals("group")?event.getString("roomId"):event.getString("senderId")).put("conversationParentId",provider.equals("qq")&&QqConversation.guild(event)?event.getString("qqGuildId"):"")
                .put("providerActorId",event.getString("senderId")).put("ownerId",received.getString("ownerId")).put("principalId",received.getString("principalId")).put("spaceId",received.getString("spaceId")).put("bindingEpoch",received.optString("bindingEpoch")).put("privateOwner",received.optBoolean("privateOwner"))
                .put("providerMessageId",event.getString("messageId")).put("receivedAt",event.getLong("receivedAt")).put("sentAt",event.getLong("sentAt"))
                .put("sourceTaskId",JSONObject.NULL).put("sourceRunId",JSONObject.NULL).put("sourceEpoch",JSONObject.NULL).put("sourceTaskBasis","unassociated_original_inbound");
        for(int i=0;i<array("taskRequests").length();i++){JSONObject task=array("taskRequests").getJSONObject(i);if(!id.equals(task.getJSONObject("originEvent").getString("id"))||!task.optString("admission").equals("admitted"))continue;
            checkHostTaskAuthority(task.getString("requestId"));if(task.optString("hostRunId").isEmpty()||task.optInt("hostCoordinatorEpoch")<1)throw new SecurityException("原宿主执行关联尚未确认");
            facts.put("sourceTaskId",task.getString("taskId")).put("sourceRunId",task.getString("hostRunId")).put("sourceEpoch",task.getInt("hostCoordinatorEpoch")).put("sourceTaskBasis","original_channel_task_request");break;
        }
        JSONArray refs=new JSONArray(),images=event.optJSONArray("ownerImageArtifacts");for(int i=0;images!=null&&i<images.length();i++){JSONObject image=images.getJSONObject(i);refs.put(new JSONObject().put("artifactId",image.getString("artifactId")).put("sha256",image.getString("sha256")).put("bytes",image.getLong("bytes")).put("mimeType",image.getString("mimeType")).put("itemIndex",image.getInt("itemIndex")));}JSONArray media=event.optJSONArray("ownerMediaArtifacts");for(int i=0;media!=null&&i<media.length();i++){JSONObject item=media.getJSONObject(i);refs.put(new JSONObject().put("artifactId",item.getString("artifactId")).put("sha256",item.getString("sha256")).put("bytes",item.getLong("bytes")).put("mimeType",item.getString("mimeType")).put("itemIndex",item.getInt("itemIndex")).put("mediaKind",item.getString("mediaKind")));}facts.put("artifactRefs",refs);
        // A current native association/frozen-reference change creates a different fact fingerprint,
        // not a claimed provider revision. Provider message identity/payload hash remain unchanged.
        return facts.put("fingerprint",dev.pocketengine.store.TaskStore.capabilityFingerprint(facts));
    }
    /** Ephemeral body-free original transport/route pin for detecting window expiry separately
     * from revocation. Only a privately constructed accepted carrier retains this snapshot. */
    synchronized JSONObject inboundFactReadScope(String id)throws Exception {
        JSONObject event=originalSeenEvent(id);if(event==null)throw new SecurityException("原认证入站已超出保留窗口");checkControlOrigin(event);
        JSONObject scope=new JSONObject();for(String key:new String[]{"profileId","senderId","roomId","kind","authenticatedBy","transportEpoch","conversationKind","qqGuildId","qqChannelId"})if(event.has(key))scope.put(key,event.get(key));scope.put("route",copy(event.getJSONObject("route")));checkInboundFactReadScope(scope);return scope;
    }
    synchronized void checkInboundFactReadScope(JSONObject scope)throws Exception {
        checkControlOrigin(scope);JSONObject received=scope.getJSONObject("route"),actual=route(scope);
        for(String key:new String[]{"ownerId","spaceId","principalId","bindingEpoch"})if(!received.optString(key).equals(actual.optString(key)))throw new SecurityException("原入站受众/主人绑定已撤销或变化");
        if(received.optBoolean("privateOwner")!=actual.optBoolean("privateOwner"))throw new SecurityException("旧来源不能升级主人身份");
    }
    /** Native-only bounded scan of the SAME original inbox. A cursor is a local position,
     * never a provider sequence, accepted message, task association or audience grant. */
    synchronized JSONObject nextInboundFacts(JSONObject anchor,JSONObject capturedReadScope,String afterEventId,int limit)throws Exception {
        if(limit<1||limit>100)throw new IllegalArgumentException("原入站窗口每次只可扫描1至100行");
        checkInboundFactReadScope(capturedReadScope);boolean anchorRetained=originalSeenEvent(anchor.getString("eventId"))!=null;
        if(anchorRetained){JSONObject current=inboundFactMetadata(anchor.getString("eventId"));if(!anchor.getString("fingerprint").equals(current.getString("fingerprint")))throw new SecurityException("原等待来源已变化");}
        String cursor=afterEventId==null||afterEventId.isEmpty()?anchor.getString("eventId"):afterEventId;
        JSONArray inbox=array("inbox"),facts=new JSONArray();int start=-1;
        if(anchorRetained)for(int i=0;i<inbox.length();i++)if(cursor.equals(inbox.getJSONObject(i).getString("id"))){start=i;break;}
        JSONObject metadata=new JSONObject().put("status",start<0?"retention_gap":"available").put("nextEventId",cursor)
                .put("localWindowOnly",true).put("gap",start<0).put("scannedRows",0).put("retainedRows",inbox.length()).put("hasMore",false);
        if(start<0)return new JSONObject().put("metadata",metadata).put("facts",facts);
        int end=Math.min(inbox.length(),start+1+limit);
        for(int i=start+1;i<end;i++){
            JSONObject event=inbox.getJSONObject(i);metadata.put("nextEventId",event.getString("id"));
            if(sameInboundScope(anchor,event))facts.put(inboundFactMetadata(event.getString("id")));
        }
        metadata.put("scannedRows",end-start-1).put("hasMore",end<inbox.length());
        return new JSONObject().put("metadata",metadata).put("facts",facts);
    }
    private boolean sameInboundScope(JSONObject anchor,JSONObject event)throws Exception {
        if(!anchor.getString("profileId").equals(event.getString("profileId"))||!anchor.getString("transportEpoch").equals(event.getString("transportEpoch"))
                ||!anchor.getString("providerActorId").equals(event.getString("senderId")))return false;
        JSONObject route=event.getJSONObject("route");for(String key:new String[]{"ownerId","principalId","spaceId","bindingEpoch"})if(!anchor.optString(key).equals(route.optString(key)))return false;
        if(anchor.optBoolean("privateOwner")!=route.optBoolean("privateOwner"))return false;
        boolean qq=anchor.getString("provider").equals("qq");
        return anchor.getString("conversationKind").equals(qq?QqConversation.scene(event):event.getString("kind"))
                &&anchor.getString("conversationId").equals(event.getString("kind").equals("group")?event.getString("roomId"):event.getString("senderId"))
                &&anchor.getString("conversationParentId").equals(qq&&QqConversation.guild(event)?event.getString("qqGuildId"):"");
    }
    public synchronized void eventStatus(String id,String status,String message)throws Exception {
        JSONObject row=find("inbox","id",id);if(row==null)return;row.put("status",status).put("message",message);save();
    }
    public synchronized JSONObject beginAnswer(String id)throws Exception {
        JSONObject row=find("inbox","id",id);if(row==null)throw new IllegalArgumentException("消息记录不存在");
        if(row.optString("status").equals("queued")||row.optString("status").equals("answering"))throw new IllegalStateException("此消息已在处理");
        JSONObject profile=find("profiles","id",row.getString("profileId"));
        if(profile==null||!profile.optBoolean("enabled"))throw new IllegalStateException("此连接已暂停");
        JSONObject authority=route(row);requireChat(authority);
        long now=System.currentTimeMillis();
        if(now-profile.optLong("quotaWindowStart",0)>=60*60*1000L)profile.put("quotaWindowStart",now).put("quotaUsed",0);
        if(profile.optInt("quotaUsed",0)>=profile.optInt("maxRepliesPerHour",30))throw new IllegalStateException("此连接本小时问答额度已用完");
        profile.put("quotaUsed",profile.optInt("quotaUsed",0)+1);row.put("status","queued");save();
        return copy(row).put("route",authority);
    }
    /** Native caller check only. It must never call RunControl.check and recurse into itself. */
    synchronized void checkAnswerOrigin(JSONObject captured,JSONObject capturedRoute)throws Exception {
        JSONObject current=event(captured.getString("id"));
        for(String key:new String[]{"profileId","messageId","senderId","roomId","kind","authenticatedBy","transportEpoch"})
            if(!captured.optString(key).equals(current.optString(key)))throw new SecurityException("原渠道问答来源已变化");
        checkControlOrigin(current);JSONObject authority=route(current);requireChat(authority);
        for(String key:new String[]{"spaceId","principalId","ownerId","bindingEpoch"})
            if(!capturedRoute.optString(key).equals(authority.optString(key)))throw new SecurityException("原渠道账号、主人绑定或问答受众已变化");
        if(capturedRoute.optBoolean("privateOwner")!=authority.optBoolean("privateOwner"))throw new SecurityException("原渠道问答主人身份已变化");
    }
    /** Only a source received under this exact owner binding may import private attachment bytes. */
    synchronized void checkOwnerImageOrigin(JSONObject captured,JSONObject capturedRoute)throws Exception {
        checkAnswerOrigin(captured,capturedRoute);JSONObject original=event(captured.getString("id")),received=original.getJSONObject("route"),profile=profile(original.getString("profileId"));
        if(!profile.getString("provider").equals("weixin")||!profile.optBoolean("ownerImagesEnabled")||!original.getString("authenticatedBy").equals("weixin_session")||!original.getString("kind").equals("dm")
                ||!capturedRoute.optBoolean("privateOwner")||!capturedRoute.optString("spaceId").equals("private")||!capturedRoute.optString("principalId").equals("owner")
                ||!received.optBoolean("privateOwner")||received.optString("bindingEpoch").isEmpty()||!received.optString("bindingEpoch").equals(capturedRoute.optString("bindingEpoch")))throw new SecurityException("仅当前已明确允许的原主人私聊图片可下载并作为私人模型输入；旧消息不会因后续绑定而升级");
    }
    synchronized JSONArray ownerImageReferences(JSONObject captured,JSONObject route)throws Exception {
        checkOwnerImageOrigin(captured,route);JSONArray refs=event(captured.getString("id")).optJSONArray("ownerImageArtifacts");return refs==null?new JSONArray():copy(refs);
    }
    synchronized void ownerImageFrozen(JSONObject captured,JSONObject route,JSONObject reference)throws Exception {
        checkOwnerImageOrigin(captured,route);JSONObject row=find("inbox","id",captured.getString("id"));JSONArray positions=ChannelMessageParts.weixinImagePositions(row.optJSONObject("messageParts"));int position=reference.getInt("itemIndex");boolean found=false;
        for(int i=0;i<positions.length();i++)if(positions.getInt(i)==position)found=true;
        if(!found||positions.length()>4||!reference.optString("artifactId").matches("[a-f0-9-]{36}")||!reference.optString("sha256").matches("[a-f0-9]{64}")||reference.getLong("bytes")<1||reference.getLong("bytes")>4*1024*1024L||!reference.optString("mimeType").matches("image/(png|jpeg|webp)"))throw new SecurityException("图片冻结回执与原消息项不符");
        JSONArray refs=row.optJSONArray("ownerImageArtifacts");if(refs==null)refs=new JSONArray();
        for(int i=0;i<refs.length();i++)if(refs.getJSONObject(i).getInt("itemIndex")==position){if(!refs.getJSONObject(i).getString("artifactId").equals(reference.getString("artifactId"))||!refs.getJSONObject(i).getString("sha256").equals(reference.getString("sha256")))throw new SecurityException("原图片项已有不同固定版本");return;}
        refs.put(copy(reference));row.put("ownerImageArtifacts",refs).put("imageInputState","frozen_bytes_not_model_understanding");save();
    }
    synchronized void ownerImageModelState(JSONObject captured,JSONObject route,String status)throws Exception {
        checkOwnerImageOrigin(captured,route);if(!status.matches("model_request_prepared|model_response_received|model_result_unconfirmed"))throw new IllegalArgumentException("未知图片输入状态");JSONObject row=find("inbox","id",captured.getString("id"));if(row.optJSONArray("ownerImageArtifacts")==null)throw new SecurityException("原图片尚未冻结");row.put("imageInputState",status).put("imageInputNotice","实际字节已冻结；模型输入/响应状态不代表已理解全部图片内容");save();
    }
    public synchronized JSONArray history(JSONObject current,int limit)throws Exception {
        JSONObject authority=route(current);JSONArray history=new JSONArray();int end=-1;
        for(int i=0;i<array("inbox").length();i++)if(array("inbox").getJSONObject(i).getString("id").equals(current.getString("id"))){end=i;break;}
        if(end<0)throw new IllegalStateException("消息已不在当前记录中，请刷新后处理");
        for(int i=Math.max(0,end-100);i<end;i++) {
            JSONObject row=array("inbox").getJSONObject(i);
            if(!route(row).getString("spaceId").equals(authority.getString("spaceId")))continue;
            // Before owner binding, channel messages belonged to an isolated DM; do not silently promote that history.
            if(!row.getJSONObject("route").getString("spaceId").equals(authority.getString("spaceId")))continue;
            history.put(new JSONObject().put("role","user").put("content",row.getString("text").isEmpty()?"[原历史消息仅含图片；本轮未重新提供其图片，不代表已看图]":row.getString("text")));
            // Only acknowledged or owner-reconciled deliveries belong to the conversation.
            // Drafts/unknown sends were not established as visible to the remote participant.
            for(int j=0;j<array("outbox").length();j++){
                JSONObject reply=array("outbox").getJSONObject(j);
                if(row.getString("id").equals(reply.optString("eventId"))&&reply.optString("status").equals("sent")
                        &&authority.getString("spaceId").equals(reply.getJSONObject("route").optString("spaceId")))
                    history.put(new JSONObject().put("role","assistant").put("content",reply.getString("text")));
            }
        }
        JSONArray bounded=new JSONArray();for(int i=Math.max(0,history.length()-Math.min(8,limit));i<history.length();i++)bounded.put(history.get(i));return bounded;
    }
    public synchronized JSONObject queueReply(String eventId,String text)throws Exception {
        return queueReplyExpected(eventId,text,null);
    }
    public synchronized JSONObject queueReplyExpected(String eventId,String text,String expectedSpace)throws Exception {
        return queueReplyExpected(eventId,text,expectedSpace,null);
    }
    public synchronized JSONObject queueReplyExpected(String eventId,String text,String expectedSpace,String expectedBindingEpoch)throws Exception {
        JSONObject event=event(eventId),profile=profile(event.getString("profileId")),authority=route(event);
        if(expectedSpace!=null&&!expectedSpace.equals(authority.getString("spaceId")))throw new SecurityException("账号权限已变化，回答未加入投递队列");
        if(expectedBindingEpoch!=null&&!expectedBindingEpoch.equals(authority.optString("bindingEpoch")))throw new SecurityException("主人绑定代次已变化，回答未加入投递队列");
        JSONObject row=queueReplyRecord(event,text);save();return copy(row);
    }
    /** Native fixed protocol notices only. This is not a model reply or task/execution grant. */
    synchronized JSONObject queueControlFeedback(String eventId,String code)throws Exception {
        if(code.startsWith("group_"))throw new SecurityException("群反馈必须核对原生主人策略，普通chat不授予群外发权");
        JSONObject event=event(eventId);if(!event.getString("kind").equals("dm"))throw new SecurityException("私人控制反馈不能借群消息绕开原群主人策略");
        String body=controlText(code),requestId="";
        if(code.equals("owner_task_unconfirmed")){
            JSONObject original=find("taskRequests","eventKey",digest(event.getString("profileId")+"\u0000"+event.getString("messageId")));
            if(original!=null){String candidate=original.getString("requestId");authorizeHostTaskCommand(candidate,eventId);requestId=candidate;body+="\n仅查询此原任务：/task-status "+candidate;}
        }
        return queueNativeControl(event,code,body,requestId,null);
    }
    JSONObject queueGroupControlFeedback(String eventId,String code,dev.pocketengine.features.groups.GroupsModule.IntakeFeedback proof)throws Exception {
        java.util.Objects.requireNonNull(proof);proof.check();JSONObject scope=proof.view();if(!eventId.equals(scope.optString("eventId"))||!code.matches("group_intake_unconfirmed|group_capability_failed"))throw new SecurityException("原群反馈不属于这条认证消息");
        JSONObject result;synchronized(this){result=queueNativeControl(event(eventId),code,controlText(code),"",scope);}proof.check();return result;
    }
    private static String controlText(String code){
        String text;
        if(code.equals("group_intake_unconfirmed"))text="这条群请求未确认完成接入或附件读取；原消息已保留。原任务可能已被宿主接收，请主人在手机核对原消息/任务，不要重复下达；没有自动重试、批准或读取其他空间。";
        else if(code.equals("group_capability_failed"))text="这条群任务未完成；原消息与原任务已保留。所需能力、配置或执行仍需主人在手机核对，请勿重复下达或扩大权限。";
        else if(code.equals("owner_task_unconfirmed"))text="这条任务命令未确认完成。请在手机核对原消息/任务；已有请求可能已受理，不要重复提交。查询原任务可发送 /task-status 来源编号；这不会重新执行或批准工具。";
        else if(code.equals("owner_status_unavailable"))text="未能按当前认证账号和原来源编号取得任务状态。请检查 /task-status 来源编号，并在手机核对原任务；没有重新提交、继续或扩大权限。";
        else throw new SecurityException("不是支持的原生控制反馈类型");
        return text;
    }
    /** The fresh command authenticates the same original owner; it is not a new task. */
    synchronized JSONObject queueHostTaskStatus(String commandEventId,String requestId,boolean readConfirmed)throws Exception {
        JSONObject command=event(commandEventId);String text=command.getString("text").trim();
        if(!text.startsWith("/task-status ")||!text.substring(13).trim().equals(requestId))throw new SecurityException("原状态命令与来源编号不一致");
        authorizeHostTaskCommand(requestId,commandEventId);
        JSONObject task=taskMetadata(requiredTask(requestId));
        String body="原任务 "+task.getString("taskId")+"\n来源 "+requestId+"\n最近宿主状态："+task.optString("hostStatus","未确认受理")
                +"\n本次查询："+(readConfirmed?"已核对原宿主回执":"未取得新回执；以上仅为最近保存的状态，不能证明仍在运行")
                +"\n只读查询，不重提、恢复或批准任务。";
        return queueNativeControl(command,"owner_task_status",body,requestId,null);
    }
    private JSONObject queueNativeControl(JSONObject event,String code,String text,String originalRequestId,JSONObject groupScope)throws Exception {
        checkControlOrigin(event);JSONObject route=route(event);requireChat(route);
        String id=UUID.nameUUIDFromBytes(("pocketworld:channel-native-control:v1\n"+event.getString("profileId")+"\n"+event.getString("id")+"\n"+code).getBytes(StandardCharsets.UTF_8)).toString();
        JSONObject old=find("outbox","id",id);
        if(old!=null){
            if(!code.equals(old.optString("nativeControlCode"))||!event.getString("id").equals(old.optString("eventId"))||!originalRequestId.equals(old.optString("taskRequestId")))throw new SecurityException("原生反馈身份冲突");
            if(groupScope!=null&&(old.optJSONObject("nativeControlGroupScope")==null||!groupScope.getString("scopeHash").equals(old.getJSONObject("nativeControlGroupScope").optString("scopeHash"))))throw new SecurityException("原群反馈策略已变化，旧投递不能改绑");
            checkControlReply(old,event);return copy(old).put("known",true); // Freeze the original status copy, even after a later GET.
        }
        long now=System.currentTimeMillis();int used=0;
        for(int i=0;i<array("outbox").length();i++){JSONObject row=array("outbox").getJSONObject(i);if(event.getString("profileId").equals(row.optString("profileId"))&&row.has("nativeControlCode")&&now-row.optLong("createdAt")<3_600_000L)used++;}
        if(used>=profile(event.getString("profileId")).optInt("maxRepliesPerHour",30))throw new IllegalStateException("此连接的原生控制反馈已达本小时上限，状态仍保留在手机");
        JSONObject row=queueReplyRecord(event,text);row.put("id",id).put("nativeControlCode",code).put("nativeControlEvent",copy(event))
                .put("nativeControlTransportEpoch",event.getString("transportEpoch")).put("nativeControlSpace",route.getString("spaceId"));
        if(!originalRequestId.isEmpty())row.put("taskRequestId",originalRequestId).put("hostTaskId",requiredTask(originalRequestId).getString("taskId"));
        if(groupScope!=null)row.put("nativeControlGroupScope",copy(groupScope));
        save();return copy(row).put("known",false);
    }
    synchronized JSONObject groupControlScope(String id)throws Exception {JSONObject row=find("outbox","id",id);if(row==null||!row.optString("nativeControlCode").startsWith("group_"))return null;if(row.optJSONObject("nativeControlGroupScope")==null)throw new SecurityException("旧群反馈没有原生主人范围，不能派发");return new JSONObject().put("eventId",row.getString("eventId")).put("scope",copy(row.getJSONObject("nativeControlGroupScope")));}
    private void checkControlOrigin(JSONObject event)throws Exception {
        JSONObject profile=profile(event.getString("profileId"));String auth=event.optString("authenticatedBy"),provider=profile.getString("provider");
        if(!profile.optBoolean("enabled")||!profile.getString("transportEpoch").equals(event.optString("transportEpoch"))
                ||!(provider.equals("qq")&&auth.matches("qq_signature|qq_gateway")||provider.equals("webhook")&&auth.equals("trusted_relay")||provider.equals("weixin")&&auth.equals("weixin_session")&&event.getString("kind").equals("dm")))throw new SecurityException("原生反馈的认证来源或连接代次已失效");
    }
    private void checkControlReply(JSONObject row,JSONObject event)throws Exception {
        if(!row.has("nativeControlCode"))return;
        checkControlOrigin(event);
        if(!row.getString("nativeControlTransportEpoch").equals(event.getString("transportEpoch"))||!row.getString("nativeControlSpace").equals(route(event).getString("spaceId")))throw new SecurityException("原控制反馈的账号或会话空间已变化");
        if(profile(event.getString("profileId")).getString("provider").equals("qq")&&System.currentTimeMillis()-event.getLong("sentAt")>(event.getString("kind").equals("group")?300_000L:3_600_000L))throw new SecurityException("原控制消息的被动回复窗口已过期，未改用主动发送");
    }
    private JSONObject queueReplyRecord(JSONObject event,String text)throws Exception {
        String eventId=event.getString("id");JSONObject profile=profile(event.getString("profileId")),authority=route(event);
        requireChat(authority);if(!profile.optBoolean("enabled"))throw new IllegalStateException("此渠道已暂停");
        if(text==null||text.trim().isEmpty()||text.length()>4000)throw new IllegalArgumentException("回复需为1–4000字文字");
        int count=0;for(int i=0;i<array("outbox").length();i++)if(eventId.equals(array("outbox").getJSONObject(i).optString("eventId")))count++;
        int max=event.getString("kind").equals("group")?5:4;
        if(profile.getString("provider").equals("qq")&&count>=max)throw new IllegalStateException("此消息的回复次数已达本地上限");
        JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("eventId",eventId)
                .put("profileId",event.getString("profileId")).put("text",text.trim()).put("route",authority)
                .put("sequence",count+1).put("status","queued").put("createdAt",System.currentTimeMillis());
        if(array("outbox").length()>=200){for(int i=0;i<array("outbox").length();i++){JSONObject old=array("outbox").getJSONObject(i);if(old.has("groupDeliveryId")||old.has("nativeControlCode"))throw new IllegalStateException("原确定性投递回执仍须保留，投递账簿已满");}}
        appendBounded("outbox",row,200);return row;
    }
    public synchronized JSONObject prepareSend(String id)throws Exception {
        JSONObject row=find("outbox","id",id);if(row==null)throw new IllegalArgumentException("待发送回复不存在");
        if(row.has("groupDeliveryId"))throw new SecurityException("群任务投递只能使用保留原生群授权的入口");
        if(!row.optString("status").equals("queued"))throw new IllegalStateException("此回复已发送或状态待核对，不能重复投递");
        JSONObject event=deliveryEvent(row),authority=route(event),profile=profile(row.getString("profileId"));
        if(!authority.getString("spaceId").equals(row.getJSONObject("route").getString("spaceId")))throw new SecurityException("账号权限已经变化，请重新生成回复");
        checkReplyEpoch(row,authority);
        checkControlReply(row,event);
        requireChat(authority);if(!profile.optBoolean("enabled"))throw new IllegalStateException("此连接已暂停");
        if(profile.getString("provider").equals("qq")) {
            long window=event.getString("kind").equals("group")?5*60*1000L:60*60*1000L;
            if(System.currentTimeMillis()-event.getLong("sentAt")>window)throw new IllegalStateException("平台被动回复窗口已过期，回复保留在本机");
        }
        row.put("status","sending");save();return copy(row);
    }
    public synchronized void checkSendAuthority(String id)throws Exception {
        JSONObject row=find("outbox","id",id);if(row==null||!"sending".equals(row.optString("status")))throw new SecurityException("投递任务已结束或失效");
        JSONObject event=deliveryEvent(row),authority=route(event),profile=profile(row.getString("profileId"));
        if(!profile.optBoolean("enabled")||!authority.getString("spaceId").equals(row.getJSONObject("route").getString("spaceId")))
            throw new SecurityException("投递前账号权限已变化，未授权发送");
        requireChat(authority);
        checkReplyEpoch(row,authority);
        checkControlReply(row,event);
    }
    public synchronized JSONObject deliveryEvent(JSONObject row)throws Exception{if(row.has("nativeGroupEvent"))return copy(row.getJSONObject("nativeGroupEvent"));if(row.has("nativeControlEvent"))return copy(row.getJSONObject("nativeControlEvent"));return row.has("taskRequestId")?copy(requiredTask(row.getString("taskRequestId")).getJSONObject("originEvent")):event(row.getString("eventId"));}
    private void checkReplyEpoch(JSONObject row,JSONObject authority)throws Exception {
        JSONObject original=row.getJSONObject("route");
        if(original.optBoolean("privateOwner")&&(original.optString("bindingEpoch").isEmpty()||!original.getString("bindingEpoch").equals(authority.optString("bindingEpoch"))))throw new SecurityException("回复的主人绑定代次已失效");
        if(row.has("taskRequestId"))checkHostTaskAuthority(row.getString("taskRequestId"));
    }
    public synchronized void delivery(String id,String status,String message,String receiptId)throws Exception {
        JSONObject row=find("outbox","id",id);if(row==null)return;
        if(!status.equals("sent")&&!status.equals("failed")&&!status.equals("unknown"))throw new IllegalArgumentException("无效投递回执状态");
        if(!row.optString("status").equals("sending"))throw new IllegalStateException("投递已结束，旧回执不能覆盖新状态");
        row.put("status",status).put("message",message).put("receiptId",receiptId).put("updatedAt",System.currentTimeMillis());save();
    }
    /** Only the authenticated local UI may reconcile an uncertain side effect after inspecting it. */
    public synchronized JSONObject resolveDelivery(String id,String outcome,boolean confirmed,String note)throws Exception {
        if(!confirmed)throw new SecurityException("请先在原平台核对，并在本机确认投递结果");
        if(!outcome.equals("delivered")&&!outcome.equals("not_delivered"))throw new IllegalArgumentException("请选择已投递或未投递");
        if(note==null||note.trim().isEmpty()||note.length()>500)throw new IllegalArgumentException("请填写1至500字的核对说明");
        JSONObject row=find("outbox","id",id);if(row==null)throw new IllegalArgumentException("待核对回复不存在");
        if(!row.optString("status").equals("unknown"))throw new IllegalStateException("只有投递结果未知的回复需要核对");
        row.put("status",outcome.equals("delivered")?"sent":"queued")
                .put("message",outcome.equals("delivered")?"机主在原平台确认已投递":"机主在原平台确认未投递，保留为待发送；未自动重发")
                .put("reconciliation",new JSONObject().put("source","local_owner").put("outcome",outcome).put("note",note.trim()).put("at",System.currentTimeMillis()))
                .put("updatedAt",System.currentTimeMillis());save();return copy(row);
    }
    public synchronized JSONObject cancelDraft(String id)throws Exception {
        JSONObject row=find("outbox","id",id);if(row==null)throw new IllegalArgumentException("待发送回复不存在");
        if(!row.optString("status").equals("queued"))throw new IllegalStateException("只有尚未发送的回复可直接取消；未知结果请先核对");
        row.put("status","cancelled").put("message","机主取消了尚未发送的回复").put("updatedAt",System.currentTimeMillis());save();return copy(row);
    }
    /** Native group bridge only. The inbox row was created by the existing authenticated transport. */
    public synchronized JSONObject authenticatedGroupEvent(String eventId,String expectedEpoch)throws Exception {
        JSONObject event=event(eventId),profile=profile(event.getString("profileId"));
        if(!event.optString("kind").equals("group")||!profile.optBoolean("enabled")||expectedEpoch==null||expectedEpoch.isEmpty()
                ||!expectedEpoch.equals(profile.optString("transportEpoch"))||!expectedEpoch.equals(event.optString("transportEpoch")))throw new SecurityException("群消息认证代次已失效");
        String auth=event.optString("authenticatedBy"),provider=profile.getString("provider");
        if(!(provider.equals("qq")&&auth.matches("qq_signature|qq_gateway")||provider.equals("webhook")&&auth.equals("trusted_relay")))throw new SecurityException("该连接没有已验证的群消息协议");
        identifier(event,"senderId");identifier(event,"roomId");identifier(event,"messageId");return event;
    }
    /** The deterministic outbox id and original event survive inbox trimming; no new task state. */
    public synchronized JSONObject queueGroupOutput(String deliveryId,JSONObject authenticatedEvent,String text,
            dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.check();identifier(new JSONObject().put("id",deliveryId),"id");
        String profileId=authenticatedEvent.getString("profileId"),epoch=authenticatedEvent.getString("transportEpoch");
        checkGroupEventCurrent(authenticatedEvent);if(text==null||text.trim().isEmpty()||text.length()>4000)throw new IllegalArgumentException("群文字结果需为1至4000字");
        JSONObject old=find("outbox","id",deliveryId);
        if(old!=null){if(!deliveryId.equals(old.optString("groupDeliveryId"))||!authenticatedEvent.getString("id").equals(old.optString("eventId"))||!text.equals(old.optString("text"))||!epoch.equals(old.optString("groupTransportEpoch")))throw new SecurityException("群投递身份内容冲突");authority.check();return copy(old).put("known",true);}
        int sequence=1;for(int i=0;i<array("outbox").length();i++)if(authenticatedEvent.getString("id").equals(array("outbox").getJSONObject(i).optString("eventId")))sequence++;
        if(profile(profileId).optString("provider").equals("qq")&&sequence>5)throw new IllegalStateException("此群消息的被动回复次数已达上限");
        // Never evict unresolved/deterministic delivery evidence to make room for a new group result.
        if(array("outbox").length()>=200)throw new IllegalStateException("渠道投递账簿已满，请先处理现有回执");
        JSONObject row=new JSONObject().put("id",deliveryId).put("groupDeliveryId",deliveryId).put("eventId",authenticatedEvent.getString("id"))
                .put("profileId",profileId).put("text",text).put("route",route(authenticatedEvent)).put("sequence",sequence)
                .put("nativeGroupEvent",copy(authenticatedEvent)).put("groupTransportEpoch",epoch).put("status","queued").put("createdAt",System.currentTimeMillis());
        authority.check();array("outbox").put(row);save();return copy(row).put("known",false);
    }
    /** Fixed native lifecycle copy leaves two slots for the original text and artifact. */
    public synchronized JSONObject queueGroupFeedback(String deliveryId,JSONObject event,String text,
            dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.check();JSONObject existing=find("outbox","id",deliveryId);
        if(existing==null&&groupReplySlots(event,authority)<=2)throw new IllegalStateException("状态已合并，保留结果回复名额；请用新/status消息查询");
        return queueGroupOutput(deliveryId,event,text,authority);
    }
    public synchronized int groupReplySlots(JSONObject event,dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.checkRead();checkGroupEventCurrent(event);int used=0;
        for(int i=0;i<array("outbox").length();i++)if(event.getString("id").equals(array("outbox").getJSONObject(i).optString("eventId")))used++;
        authority.checkRead();return Math.max(0,5-used);
    }
    public synchronized JSONObject queueGroupMedia(String deliveryId,JSONObject event,dev.pocketengine.features.groups.GroupHostPort.Output output,
            dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.check();JSONObject identity=new JSONObject().put("outputId",output.outputId).put("spaceId",output.spaceId).put("audience",output.audience).put("mimeType",output.mimeType).put("artifactId",output.artifactId).put("bytes",output.bytes);
        JSONObject existing=find("outbox","id",deliveryId);if(existing!=null){if(existing.optJSONObject("groupMedia")==null||!existing.getJSONObject("groupMedia").toString().equals(identity.toString()))throw new SecurityException("原群媒体投递身份不符");return copy(existing).put("known",true);}
        JSONObject created=queueGroupOutput(deliveryId,event,"群媒体产物 "+output.outputId,authority);JSONObject row=find("outbox","id",deliveryId);row.put("groupMedia",identity).put("mediaPhase","prepared");authority.check();save();return copy(row).put("known",false);
    }
    public synchronized void groupMediaPhase(String id,String phase,String fileInfo,dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.check();JSONObject row=find("outbox","id",id);if(row==null||row.optJSONObject("groupMedia")==null||!row.optString("status").equals("sending"))throw new SecurityException("群媒体投递已经关闭");
        String old=row.optString("mediaPhase");if(!(old.equals("prepared")&&phase.equals("uploading")||old.equals("uploading")&&phase.equals("uploaded")||old.equals("uploaded")&&phase.equals("message_sending")))throw new SecurityException("原群媒体阶段不能重放");
        if(phase.equals("uploaded")){if(fileInfo==null||fileInfo.isEmpty()||fileInfo.length()>8192)throw new IllegalArgumentException("QQ媒体回执无效");row.put("groupFileInfo",fileInfo);}row.put("mediaPhase",phase);authority.check();save();
    }
    public synchronized JSONObject prepareGroupSend(String id,dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.check();JSONObject row=find("outbox","id",id);if(row==null||!id.equals(row.optString("groupDeliveryId")))throw new SecurityException("没有此原生群投递");
        if(!row.optString("status").equals("queued"))throw new SecurityException("原群投递已派发或结果待核对，只能查询");
        checkGroupEventCurrent(row.getJSONObject("nativeGroupEvent"));JSONObject event=row.getJSONObject("nativeGroupEvent");
        if(profile(row.getString("profileId")).optString("provider").equals("qq")&&System.currentTimeMillis()-event.getLong("sentAt")>300_000)throw new SecurityException("QQ群被动回复窗口已关闭");
        authority.check();row.put("status","sending");save();return copy(row);
    }
    public synchronized void checkGroupSend(String id,dev.pocketengine.features.groups.GroupHostPort.Authority authority)throws Exception {
        authority.check();JSONObject row=find("outbox","id",id);if(row==null||!id.equals(row.optString("groupDeliveryId"))||!row.optString("status").equals("sending"))throw new SecurityException("群投递已关闭");
        checkGroupEventCurrent(row.getJSONObject("nativeGroupEvent"));authority.check();
    }
    public synchronized JSONObject groupOutbox(String id)throws Exception {JSONObject row=find("outbox","id",id);if(row==null)return null;if(!id.equals(row.optString("groupDeliveryId")))throw new SecurityException("不是原群投递");return copy(row);}
    public synchronized void checkGroupEventCurrent(JSONObject event)throws Exception {
        JSONObject profile=profile(event.getString("profileId"));if(!profile.optBoolean("enabled")||!event.optString("kind").equals("group")||event.optString("transportEpoch").isEmpty()||!event.getString("transportEpoch").equals(profile.optString("transportEpoch")))throw new SecurityException("原群连接或认证代次已撤销");
        requireChat(route(event));
    }
    public synchronized JSONObject snapshot()throws Exception {
        JSONArray tasks=new JSONArray();JSONArray requests=array("taskRequests");for(int i=Math.max(0,requests.length()-40);i<requests.length();i++)tasks.put(taskMetadata(requests.getJSONObject(i)));
        return new JSONObject().put("profiles",copy(array("profiles"))).put("bindings",copy(array("bindings")))
                .put("spaces",copy(array("spaces"))).put("inbox",tail(array("inbox"),40)).put("outbox",tail(array("outbox"),40)).put("taskRequests",tasks);
    }
    static void requireChat(JSONObject route)throws Exception {
        JSONArray grants=route.getJSONArray("capabilities");for(int i=0;i<grants.length();i++)if("chat".equals(grants.getString(i)))return;
        throw new SecurityException("此会话未开放问答权限");
    }
    static String identifier(JSONObject input,String name)throws Exception {
        String value=text(input,name,180,false);if(!value.matches("[A-Za-z0-9_.:@-]{1,180}"))throw new IllegalArgumentException(name+"格式无效");return value;
    }
    static String text(JSONObject input,String name,int max,boolean empty)throws Exception {
        Object value=input.opt(name);if(!(value instanceof String)||((String)value).length()>max||(!empty&&((String)value).trim().isEmpty()))throw new IllegalArgumentException(name+"长度或类型无效");
        return ((String)value).trim();
    }
    static String digest(String value)throws Exception {byte[] result=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:result)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    static JSONArray copy(JSONArray value)throws Exception{return new JSONArray(value.toString());}
    private JSONArray array(String key)throws Exception{return state.getJSONArray(key);}
    private JSONObject find(String array,String key,String value)throws Exception {JSONArray rows=array(array);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(value.equals(row.optString(key)))return row;}return null;}
    private void remove(String array,String key,String value)throws Exception {JSONArray next=new JSONArray(),rows=array(array);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!value.equals(row.optString(key)))next.put(row);}state.put(array,next);}
    private void replace(String array,String key,String value,JSONObject row)throws Exception {remove(array,key,value);array(array).put(row);}
    private void appendBounded(String array,JSONObject row,int max)throws Exception {JSONArray rows=array(array),next=new JSONArray();for(int i=Math.max(0,rows.length()-max+1);i<rows.length();i++)next.put(rows.get(i));next.put(row);state.put(array,next);}
    private static JSONArray tail(JSONArray rows,int count)throws Exception {JSONArray result=new JSONArray();for(int i=Math.max(0,rows.length()-count);i<rows.length();i++)result.put(copy(rows.getJSONObject(i)));return result;}
    private void save()throws Exception {
        try {
            byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>4_000_000)throw new IllegalStateException("渠道记录空间不足");
            File staging=new File(file.getParentFile(),"channels.pending");
            try(FileOutputStream output=new FileOutputStream(staging)){output.write(bytes);output.getFD().sync();}
            Files.move(staging.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception failure) {
            state=file.isFile()?new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)):new JSONObject();
            for(String key:new String[]{"profiles","bindings","spaces","inbox","outbox","seen","taskRequests"})if(state.optJSONArray(key)==null)state.put(key,new JSONArray());
            throw failure;
        }
    }
}
