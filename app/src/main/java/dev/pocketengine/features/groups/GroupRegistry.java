package dev.pocketengine.features.groups;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable group policy, reservations and references only. TaskStore remains task authority. */
final class GroupRegistry implements AutoCloseable {
    static final String[] CAPABILITIES={"public.answer","public.search","group.files","media.image","media.audio","media.video"};
    private final File file;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    private JSONObject state;
    private boolean closed;
    GroupRegistry(File directory) throws Exception {
        Files.createDirectories(directory.toPath());file=new File(directory,"registry.json");
        lockFile=new RandomAccessFile(new File(directory,"registry.lock"),"rw");
        FileLock candidate=null;
        try {
            candidate=lockFile.getChannel().tryLock();if(candidate==null)throw new IOException("群空间已由另一个宿主持有");
            if(file.exists()) {
                if(file.length()>8*1024*1024L)throw new IOException("群空间存储超过安全上限");
                state=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                if(state.getInt("schema")!=1)throw new IOException("不支持的群空间版本");
                state.getJSONArray("groups");state.getJSONArray("requests");state.getJSONArray("deliveries");state.getJSONArray("audit");
            } else state=new JSONObject().put("schema",1).put("revision",0L).put("groups",new JSONArray()).put("requests",new JSONArray()).put("deliveries",new JSONArray()).put("audit",new JSONArray());
            lock=candidate;
            // A previous module's authority is closed/dead before its exclusive lock is released.
            // Its unfinished synchronous restart can now only be reconciled by a fresh host read.
            JSONObject recovered=null;JSONArray saved=state.getJSONArray("requests");
            for(int i=0;i<saved.length();i++)if(saved.getJSONObject(i).optJSONObject("recovery")!=null){
                if(recovered==null)recovered=GroupJson.copy(state);
                recovered.getJSONArray("requests").getJSONObject(i).getJSONObject("recovery").put("phase","unknown");
            }
            if(recovered!=null)commit(recovered);
        } catch(Exception failure) {if(candidate!=null)candidate.release();lockFile.close();throw failure;}
    }
    synchronized JSONObject counts() throws Exception {open();return new JSONObject().put("groupCount",state.getJSONArray("groups").length()).put("requestCount",state.getJSONArray("requests").length());}
    synchronized JSONObject ownerSnapshot(long now) throws Exception {
        open();JSONObject result=GroupJson.copy(state);result.remove("schema");
        JSONArray groups=result.getJSONArray("groups");for(int i=0;i<groups.length();i++)groups.getJSONObject(i).put("usage",usage(state,groups.getJSONObject(i).getString("id"),"",now));
        JSONArray refs=new JSONArray();JSONArray requests=state.getJSONArray("requests");
        for(int i=0;i<requests.length();i++)refs.put(reference(requests.getJSONObject(i)));
        result.put("requests",refs);return result;
    }
    /** Read-only original registry lookup for authenticated attachment import; grants no execution. */
    synchronized JSONObject authenticatedScope(GroupIngressPort.Event event,long now) throws Exception {
        open();JSONArray groups=state.getJSONArray("groups");for(int i=0;i<groups.length();i++){
            JSONObject group=groups.getJSONObject(i);if(!routeMatches(group,event))continue;
            if(!group.getBoolean("enabled"))throw new SecurityException("原群空间已关闭");JSONObject member=activeMember(group,event.senderId);if(member.getString("role").equals("viewer"))throw new SecurityException("只读成员不能导入群任务附件");
            grant(group,"group.files",now);
            return new JSONObject().put("spaceId",group.getString("spaceId")).put("actorId",GroupJson.uuid(event.routeIdentity()+"\n"+event.senderId))
                    .put("policyEpoch",group.getString("policyEpoch")).put("memberEpoch",member.getString("memberEpoch")).put("maxArtifactBytes",group.getJSONObject("quota").getLong("maxMediaBytes"));
        }throw new SecurityException("此认证事件没有原生群空间");
    }
    /** Fixed denial/status copy only; chat defaults/--share never authorize a group send. */
    synchronized JSONObject intakeFeedbackScope(GroupIngressPort.Event event,String capability,long now)throws Exception {
        open();if(now>event.replyUntil)throw new SecurityException("原回复上下文的本地期限已过，反馈仅保留在手机");JSONArray groups=state.getJSONArray("groups");
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.getJSONObject(i);if(!routeMatches(group,event))continue;
            if(!group.getBoolean("enabled"))throw new SecurityException("原群服务已关闭，反馈仅保留在手机");
            JSONObject member=activeMember(group,event.senderId);if(member.getString("role").equals("viewer"))throw new SecurityException("原成员未获任务能力，反馈仅保留在手机");
            JSONObject grant=grant(group,capability,now);if(!grant.optBoolean("allowGroupAudience"))throw new SecurityException("主人未允许该能力向原群公开结果或反馈");
            JSONObject scope=new JSONObject().put("version",1).put("purpose","fixed_intake_feedback_only").put("eventId",event.eventId)
                    .put("groupId",group.getString("id")).put("spaceId",group.getString("spaceId")).put("policyEpoch",group.getString("policyEpoch"))
                    .put("memberEpoch",member.getString("memberEpoch")).put("capability",capability).put("grantExpiresAt",grant.getLong("expiresAt"))
                    .put("accountId",event.accountId).put("roomId",event.roomId).put("senderId",event.senderId).put("transportEpoch",event.transportEpoch).put("audience","group");
            if(!event.conversationKind.equals("group"))scope.put("conversationKind",event.conversationKind).put("conversationParentId",event.conversationParentId);return scope.put("scopeHash",GroupJson.hash(GroupJson.canonical(scope)));
        }
        throw new SecurityException("此认证事件没有主人已授权的原群反馈范围");
    }
    synchronized JSONObject create(JSONObject input,long now) throws Exception {
        open();JSONObject next=GroupJson.copy(state);JSONArray groups=next.getJSONArray("groups");if(groups.length()>=32)throw new IllegalStateException("最多配置32个群空间");
        String provider=GroupJson.id(input,"provider"),account=GroupJson.id(input,"accountId"),room=GroupJson.id(input,"roomId");String scene=input.optString("conversationKind","group"),parent=input.optString("conversationParentId");if(!scene.equals("group")&&!(provider.equals("qq")&&scene.equals("qq_guild_channel")&&!parent.isEmpty()))throw new SecurityException("请明确选择QQ频道原生场景；不继承普通群授权");if(scene.equals("group")&&!parent.isEmpty())throw new SecurityException("原群无频道父身份");if(!parent.isEmpty())GroupJson.id(parent,"conversationParentId");
        for(int i=0;i<groups.length();i++) {JSONObject g=groups.getJSONObject(i);if(provider.equals(g.getString("provider"))&&account.equals(g.getString("accountId"))&&room.equals(g.getString("roomId"))&&scene.equals(g.optString("conversationKind","group"))&&parent.equals(g.optString("conversationParentId")))throw new IllegalArgumentException("该机器人与群已绑定空间");}
        String id=UUID.randomUUID().toString();JSONObject group=new JSONObject().put("id",id).put("spaceId","group:"+id).put("name",GroupJson.text(input,"name",100))
                .put("provider",provider).put("accountId",account).put("roomId",room).put("revision",1L).put("policyEpoch",UUID.randomUUID().toString())
                .put("enabled",false).put("visibility","requester").put("grants",new JSONArray()).put("members",new JSONArray()).put("quota",defaultQuota()).put("createdAt",now);
        if(!scene.equals("group"))group.put("conversationKind",scene).put("conversationParentId",parent);groups.put(group);audit(next,id,"group.created",now);commit(next);return GroupJson.copy(group);
    }
    synchronized JSONObject policy(JSONObject input,long now) throws Exception {
        open();JSONObject next=GroupJson.copy(state),group=group(next,GroupJson.id(input,"id"));cas(group,input);
        boolean enabled=GroupJson.bool(input,"enabled");String visibility=GroupJson.one(GroupJson.text(input,"visibility",30),"可见性","requester","members");
        JSONObject quota=quota(input.getJSONObject("quota"));JSONArray grants=input.getJSONArray("grants");if(grants.length()>CAPABILITIES.length)throw new IllegalArgumentException("群能力授权数超限");
        JSONArray normalized=new JSONArray();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<grants.length();i++) {
            JSONObject grant=grants.getJSONObject(i);String capability=capability(GroupJson.text(grant,"capability",50));
            if(!seen.add(capability))throw new IllegalArgumentException("群能力重复授权");
            String resource=GroupJson.id(grant,"resource");
            if(capability.startsWith("public.")&&!resource.equals("public"))throw new IllegalArgumentException("公开能力只允许公开资源");
            if(capability.equals("group.files")&&!resource.equals(group.getString("spaceId")))throw new SecurityException("文件授权只能属于本群空间");
            long expires=GroupJson.number(grant,"expiresAt",enabled?now+1:1,now+31L*86_400_000);
            normalized.put(new JSONObject().put("capability",capability).put("resource",resource).put("expiresAt",expires).put("allowGroupAudience",GroupJson.bool(grant,"allowGroupAudience")));
        }
        if(enabled&&normalized.length()==0)throw new IllegalArgumentException("启用前请明确授权至少一项群能力");
        group.put("enabled",enabled).put("visibility",visibility).put("grants",normalized).put("quota",quota).put("revision",group.getLong("revision")+1).put("policyEpoch",UUID.randomUUID().toString());
        audit(next,group.getString("id"),"group.policy_changed",now);commit(next);return GroupJson.copy(group);
    }
    synchronized JSONObject member(JSONObject input,GroupIngressPort.Event admin,long now) throws Exception {
        open();JSONObject next=GroupJson.copy(state),group=group(next,GroupJson.id(input,"id"));cas(group,input);
        String sender=GroupJson.id(input,"senderId"),role=GroupJson.one(GroupJson.text(input,"role",20),"成员角色","admin","member","viewer");
        JSONArray members=group.getJSONArray("members");JSONObject previous=GroupJson.find(members,"senderId",sender);
        if(admin!=null) {
            if(!group.getBoolean("enabled"))throw new SecurityException("群服务已关闭，群成员管理暂停");
            sameRoute(group,admin);JSONObject actor=activeMember(group,admin.senderId);
            if(!actor.getString("role").equals("admin")||role.equals("admin")||sender.equals(admin.senderId)||previous!=null&&previous.getString("role").equals("admin"))
                throw new SecurityException("群管理员只能管理普通成员，不能修改管理员或扩大工具授权");
        }
        if(previous==null&&members.length()>=256)throw new IllegalStateException("群成员数已达256上限");
        JSONObject row=previous==null?new JSONObject().put("senderId",sender):previous;
        row.put("displayName",GroupJson.text(input,"displayName",80)).put("role",role).put("enabled",GroupJson.bool(input,"enabled"))
                .put("memberEpoch",UUID.randomUUID().toString());
        if(previous==null)members.put(row);group.put("revision",group.getLong("revision")+1);
        audit(next,group.getString("id"),"group.member_changed",now);commit(next);return GroupJson.copy(group);
    }
    synchronized JSONObject memberView(GroupIngressPort.Event event,String groupId,long now) throws Exception {
        open();JSONObject group=group(state,groupId);sameRoute(group,event);activeMember(group,event.senderId);
        JSONArray refs=new JSONArray(),requests=state.getJSONArray("requests");
        for(int i=0;i<requests.length();i++) {JSONObject row=requests.getJSONObject(i);if(row.getString("groupId").equals(groupId)&&visible(row,event.senderId))refs.put(reference(row));}
        return new JSONObject().put("id",groupId).put("name",group.getString("name")).put("spaceId",group.getString("spaceId"))
                .put("enabled",group.getBoolean("enabled")).put("visibility",group.getString("visibility")).put("revision",group.getLong("revision"))
                .put("role",activeMember(group,event.senderId).getString("role")).put("requests",refs).put("usage",usage(state,groupId,event.senderId,now));
    }
    synchronized GroupHostPort.Request prepare(GroupIngressPort.Event event,String cap,String audience,String visibility,long tokens,long now) throws Exception {
        open();capability(cap);GroupJson.one(audience,"受众","group","requester");GroupJson.one(visibility,"可见性","members","requester");
        JSONObject selected=null;JSONArray groups=state.getJSONArray("groups");for(int i=0;i<groups.length();i++) {
            JSONObject g=groups.getJSONObject(i);if(routeMatches(g,event)){selected=g;break;}
        }
        if(selected==null)throw new SecurityException("该渠道群尚未绑定原生群空间");
        String groupId=selected.getString("id"),space=selected.getString("spaceId");
        String requestId=GroupJson.uuid(event.routeIdentity()+"\n"+event.messageId);
        String fingerprint=GroupJson.hash(event.senderId+"\n"+event.text+"\n"+cap+"\n"+audience+"\n"+visibility+"\n"+tokens+"\n"+GroupJson.canonical(event.artifacts())+(event.messageParts().length()==0?"":"\n"+GroupJson.canonical(event.messageParts())));
        JSONObject existing=GroupJson.find(state.getJSONArray("requests"),"requestId",requestId);
        if(existing!=null) {if(!fingerprint.equals(existing.getString("fingerprint")))throw new SecurityException("同一平台消息的身份或请求内容冲突");return new GroupHostPort.Request(existing);}
        if(!selected.getBoolean("enabled"))throw new SecurityException("群空间未启用");
        JSONObject member=activeMember(selected,event.senderId);if(member.getString("role").equals("viewer"))throw new SecurityException("只读成员不能创建任务");
        if(now<event.receivedAt-30_000||now>event.replyUntil)throw new SecurityException("群消息已过有效窗口");
        JSONObject grant=grant(selected,cap,now);if(audience.equals("group")&&!grant.getBoolean("allowGroupAudience"))throw new SecurityException("此能力未授权群内公开结果");
        if(visibility.equals("members")&&!selected.getString("visibility").equals("members"))throw new SecurityException("此群只允许发起人查看任务");
        JSONArray artifacts=event.artifacts();if(artifacts.length()>0&&!cap.equals("group.files"))throw new SecurityException("附件必须使用本群文件能力");
        for(int i=0;i<artifacts.length();i++)if(!space.equals(artifacts.getJSONObject(i).getString("spaceId")))throw new SecurityException("不能读取私人或其他群的附件");
        if(cap.equals("group.files")&&artifacts.length()==0)throw new IllegalArgumentException("请先附上本群文件");
        JSONObject quota=selected.getJSONObject("quota"),groupUsage=usage(state,groupId,"",now),memberUsage=usage(state,groupId,event.senderId,now);
        if(tokens<1||tokens>quota.getLong("maxTokensPerTask"))throw new IllegalArgumentException("本次群任务请求预留"+tokens+"模型额度，主人已授权的单任务上限为"+quota.getLong("maxTokensPerTask")+"；未启动模型。请主人在群权限中明确核对额度，已保存限额不会自动提高");
        int media=cap.startsWith("media.")?1:0;
        if(groupUsage.getInt("dailyTasks")>=quota.getInt("dailyTasks")||memberUsage.getInt("dailyTasks")>=quota.getInt("memberDailyTasks")
                ||groupUsage.getLong("reservedTokens")+tokens>quota.getLong("dailyTokens")||groupUsage.getInt("activeTasks")>=quota.getInt("maxConcurrent")
                ||groupUsage.getInt("dailyMedia")+media>quota.getInt("dailyMedia")||memberUsage.getInt("dailyMedia")+media>quota.getInt("memberDailyMedia"))
            throw new IllegalStateException("群或成员的任务、模型、媒体额度已用完，或并发已满");
        JSONObject next=GroupJson.copy(state);JSONArray requests=next.getJSONArray("requests");if(requests.length()>=2000)throw new IllegalStateException("群请求引用已达上限，请由宿主管理保留记录");
        JSONObject row=new JSONObject().put("requestId",requestId).put("taskId",GroupJson.uuid("group-task:"+requestId)).put("groupId",groupId).put("spaceId",space)
                .put("actorId",GroupJson.uuid(event.routeIdentity()+"\n"+event.senderId)).put("senderId",event.senderId)
                .put("goal",event.text).put("capability",cap).put("resource",grant.getString("resource")).put("audience",audience).put("visibility",visibility)
                .put("policyEpoch",selected.getString("policyEpoch")).put("memberEpoch",member.getString("memberEpoch")).put("grantExpiresAt",grant.getLong("expiresAt"))
                .put("maxTokens",tokens).put("maxMediaBytes",quota.getLong("maxMediaBytes")).put("mediaItems",media).put("createdAt",now).put("fingerprint",fingerprint)
                .put("event",event.json()).put("admission","reserved").put("released",false).put("hostRevision",0L).put("hostRunId","").put("hostEpoch",0);
        requests.put(row);audit(next,groupId,"task.reserved",now);commit(next);return new GroupHostPort.Request(row);
    }
    synchronized boolean begin(String requestId) throws Exception {
        open();JSONObject next=GroupJson.copy(state),row=request(next,requestId);if(!row.getString("admission").equals("reserved"))return false;
        row.put("admission","dispatching");commit(next);return true;
    }
    synchronized void unknown(String requestId) throws Exception {open();JSONObject next=GroupJson.copy(state);JSONObject row=request(next,requestId);if(row.getString("admission").equals("dispatching")){row.put("admission","unknown");commit(next);}}
    synchronized GroupHostPort.Request request(String id) throws Exception {open();return new GroupHostPort.Request(request(state,id));}
    synchronized JSONObject reference(String id) throws Exception {open();return reference(request(state,id));}
    synchronized void check(String requestId,long now,boolean execution) throws Exception {
        open();JSONObject row=request(state,requestId),group=group(state,row.getString("groupId"));
        if(!group.getBoolean("enabled")||!group.getString("policyEpoch").equals(row.getString("policyEpoch")))throw new SecurityException("群服务授权已撤销或变化");
        JSONObject member=activeMember(group,row.getString("senderId"));
        if(member.getString("role").equals("viewer")||!member.getString("memberEpoch").equals(row.getString("memberEpoch")))throw new SecurityException("成员任务授权已变化");
        JSONObject grant=grant(group,row.getString("capability"),now);
        if(!grant.getString("resource").equals(row.getString("resource"))||now>=row.getLong("grantExpiresAt"))throw new SecurityException("任务资源授权已失效");
        if(execution&&row.getBoolean("released"))throw new SecurityException("该群任务已结束；重新执行需要新的群请求和额度");
    }
    synchronized long executionGeneration(String requestId) throws Exception {open();return request(state,requestId).optLong("executionGeneration");}
    synchronized void checkExecution(String requestId,long now,long generation) throws Exception {
        check(requestId,now,true);
        if(request(state,requestId).optLong("executionGeneration")!=generation)throw new SecurityException("旧群执行授权已失效，不能借恢复重新获得执行权");
    }
    /** Current native owner + an actual interrupted TaskStore receipt are required by GroupsModule. */
    synchronized long claimRecovery(String requestId,GroupHostPort.TaskReceipt actual,long now) throws Exception {
        check(requestId,now,false);JSONObject row=request(state,requestId);
        if(actual==null||!actual.status.equals("interrupted")||!row.getString("taskId").equals(actual.taskId)
                ||row.getLong("hostRevision")!=actual.revision||row.getInt("hostEpoch")!=actual.coordinatorEpoch||!row.getString("hostRunId").equals(actual.runId))
            throw new SecurityException("恢复目标不是刚核对的原中断任务");
        if(row.optJSONObject("recovery")!=null||!row.getBoolean("released"))throw new IllegalStateException("原任务恢复正在核对中，请查询原任务");
        JSONObject group=group(state,row.getString("groupId")),quota=group.getJSONObject("quota"),all=usage(state,row.getString("groupId"),"",now),member=usage(state,row.getString("groupId"),row.getString("senderId"),now);
        if(all.getInt("activeTasks")>=quota.getInt("maxConcurrent"))throw new IllegalStateException("群并发已被其他任务占用，未恢复原任务");
        long day=now/86_400_000L;boolean reserveDay=!reservedOn(row,day);
        if(reserveDay&&(all.getInt("dailyTasks")>=quota.getInt("dailyTasks")||member.getInt("dailyTasks")>=quota.getInt("memberDailyTasks")
                ||all.getLong("reservedTokens")+row.getLong("maxTokens")>quota.getLong("dailyTokens")
                ||all.getInt("dailyMedia")+row.getInt("mediaItems")>quota.getInt("dailyMedia")||member.getInt("dailyMedia")+row.getInt("mediaItems")>quota.getInt("memberDailyMedia")))
            throw new IllegalStateException("恢复当天的群或成员额度不足，未增加原任务预算或派发");
        JSONObject next=GroupJson.copy(state),changed=request(next,requestId);long generation=Math.addExact(changed.optLong("executionGeneration"),1);
        if(reserveDay){JSONArray days=changed.optJSONArray("recoveryDays");if(days==null)days=new JSONArray();if(days.length()>=32)throw new IllegalStateException("原群授权恢复日期已达上限");days.put(day);changed.put("recoveryDays",days);}
        changed.put("executionGeneration",generation).put("released",false).put("recovery",new JSONObject().put("generation",generation).put("phase","dispatching")
                .put("previousRunId",actual.runId).put("previousEpoch",actual.coordinatorEpoch).put("previousRevision",actual.revision));
        audit(next,row.getString("groupId"),"task.recovery_reserved",now);commit(next);return generation;
    }
    synchronized void recoveryUnknown(String requestId,long generation) throws Exception {
        open();JSONObject next=GroupJson.copy(state),row=request(next,requestId),recovery=row.optJSONObject("recovery");
        if(recovery!=null&&recovery.getLong("generation")==generation){recovery.put("phase","unknown");commit(next);}
    }
    synchronized void recoveryNotStarted(String requestId,long generation) throws Exception {
        open();JSONObject next=GroupJson.copy(state),row=request(next,requestId),recovery=row.optJSONObject("recovery");
        if(recovery!=null&&recovery.getLong("generation")==generation){row.remove("recovery");row.put("released",true).put("executionGeneration",Math.addExact(row.optLong("executionGeneration"),1));commit(next);}
    }
    synchronized void visible(String requestId,GroupIngressPort.Event event) throws Exception {
        open();JSONObject row=request(state,requestId),group=group(state,row.getString("groupId"));sameRoute(group,event);activeMember(group,event.senderId);
        if(!visible(row,event.senderId))throw new SecurityException("此任务仅发起人可见");
    }
    synchronized String contributor(String requestId,GroupIngressPort.Event event,String expectedEpoch,long now)throws Exception {
        check(requestId,now,false);visible(requestId,event);JSONObject row=request(state,requestId),group=group(state,row.getString("groupId")),member=activeMember(group,event.senderId);
        if(member.getString("role").equals("viewer")||expectedEpoch!=null&&!expectedEpoch.equals(member.getString("memberEpoch")))throw new SecurityException("原群补充成员权限或代次已失效");
        if(expectedEpoch==null&&(now<event.receivedAt-30_000||now>event.replyUntil))throw new SecurityException("群补充消息已过有效窗口");
        return member.getString("memberEpoch");
    }
    synchronized JSONObject outputDelivery(String requestId,String outputId)throws Exception {
        String id=GroupJson.uuid("group-delivery:"+requestId+":"+outputId+":"+request(state,requestId).getString("audience"));JSONObject row=GroupJson.find(state.getJSONArray("deliveries"),"deliveryId",id);return row==null?null:GroupJson.copy(row);
    }
    synchronized GroupDeliveryPort.Delivery prepareQuestion(String requestId,GroupHostPort.Output output,GroupIngressPort.Event target,long now)throws Exception {return prepareDelivery(requestId,output,target,now,"question");}
    synchronized void cancellable(String requestId,GroupIngressPort.Event event) throws Exception {
        open();JSONObject row=request(state,requestId),group=group(state,row.getString("groupId"));sameRoute(group,event);activeMember(group,event.senderId);
        if(!row.getString("senderId").equals(event.senderId))throw new SecurityException("只能取消自己发起的群任务");
    }
    synchronized JSONObject receipt(String requestId,GroupHostPort.TaskReceipt receipt) throws Exception {
        open();JSONObject original=request(state,requestId);
        if(receipt==null)return new JSONObject().put("reference",reference(original)).put("known",false);
        if(!original.getString("taskId").equals(receipt.taskId)||receipt.revision<original.getLong("hostRevision")||receipt.coordinatorEpoch<original.getInt("hostEpoch"))throw new SecurityException("宿主返回了其他任务或过期回执");
        String receiptHash=GroupJson.hash(GroupJson.canonical(receipt.json()));
        if(receipt.revision==original.getLong("hostRevision")&&!receiptHash.equals(original.optString("hostReceiptHash")))throw new SecurityException("同版本宿主回执内容发生变化");
        if(receipt.coordinatorEpoch==original.getInt("hostEpoch")&&!original.getString("hostRunId").isEmpty()&&!original.getString("hostRunId").equals(receipt.runId))throw new SecurityException("执行身份在同代次发生变化");
        JSONObject next=GroupJson.copy(state),row=request(next,requestId);row.put("admission","admitted").put("hostRevision",receipt.revision).put("hostRunId",receipt.runId).put("hostEpoch",receipt.coordinatorEpoch).put("hostReceiptHash",receiptHash);
        JSONObject recovery=row.optJSONObject("recovery");
        if(recovery==null){if(receipt.terminal())row.put("released",true);}
        else if(receipt.coordinatorEpoch>recovery.getInt("previousEpoch")){row.remove("recovery");row.put("released",receipt.terminal());}
        else if(recovery.getString("phase").equals("unknown")&&receipt.terminal()){
            // recover() has ended (or its old module is closed), and the synchronous original
            // TaskStore still has no newer attempt. Query settles the reservation, not execution.
            row.remove("recovery");row.put("released",true).put("executionGeneration",Math.addExact(row.optLong("executionGeneration"),1));
        }
        commit(next);return new JSONObject().put("reference",reference(row)).put("known",true).put("task",receipt.json());
    }
    synchronized JSONObject cancelTarget(String requestId) throws Exception {open();JSONObject row=request(state,requestId);return new JSONObject().put("runId",row.getString("hostRunId")).put("coordinatorEpoch",row.getInt("hostEpoch"));}
    synchronized GroupDeliveryPort.Delivery prepareDelivery(String requestId,GroupHostPort.Output output,long now) throws Exception {
        return prepareDelivery(requestId,output,new GroupHostPort.Request(request(state,requestId)).event,now,"result");
    }
    synchronized GroupDeliveryPort.Delivery prepareDelivery(String requestId,GroupHostPort.Output output,GroupIngressPort.Event target,long now) throws Exception {
        return prepareDelivery(requestId,output,target,now,"result");
    }
    /** Native fixed-copy status only. Model/UI text never enters this route. */
    synchronized GroupDeliveryPort.Delivery prepareFeedback(String requestId,GroupIngressPort.Event target,String key,String text,long now)throws Exception {
        JSONObject row=request(state,requestId);
        if(!row.getString("visibility").equals("members")||!row.getString("audience").equals("group"))throw new SecurityException("此任务状态不能向群成员公开，请在本机查看");
        String outputId=GroupJson.uuid("group-feedback:"+requestId+":"+key);
        GroupHostPort.Output output=new GroupHostPort.Output(outputId,row.getString("spaceId"),"group","text/plain","",text,text.getBytes(StandardCharsets.UTF_8).length);
        return prepareDelivery(requestId,output,target,now,"feedback");
    }
    private GroupDeliveryPort.Delivery prepareDelivery(String requestId,GroupHostPort.Output output,GroupIngressPort.Event target,long now,String kind) throws Exception {
        check(requestId,now,false);JSONObject row=request(state,requestId);
        if(!row.getString("spaceId").equals(output.spaceId)||!row.getString("audience").equals(output.audience))throw new SecurityException("产物的群空间或受众不匹配");
        if(output.bytes>row.getLong("maxMediaBytes"))throw new IllegalArgumentException("媒体产物超过本群大小限制");
        boolean text=output.mimeType.equals("text/plain");
        String cap=row.getString("capability");boolean compatible=text||cap.equals("media.image")&&output.mimeType.matches("image/(png|jpeg|webp)")
                ||cap.equals("media.audio")&&output.mimeType.matches("audio/(mpeg|ogg|wav|silk)")||cap.equals("media.video")&&output.mimeType.equals("video/mp4")
                ||cap.equals("group.files")&&(output.mimeType.equals("application/pdf")||output.mimeType.equals("application/octet-stream"));
        if(!compatible)throw new SecurityException("该任务能力不能发送此媒体类型");
        String id=GroupJson.uuid("group-delivery:"+requestId+":"+output.outputId+":"+output.audience);JSONArray all=state.getJSONArray("deliveries");JSONObject existing=GroupJson.find(all,"deliveryId",id);
        if(existing!=null) {if(!existing.optString("kind","result").equals(kind)||!GroupJson.canonical(existing.getJSONObject("output")).equals(GroupJson.canonical(output.json())))throw new SecurityException("相同产物身份的内容发生变化");return new GroupDeliveryPort.Delivery(existing);}
        // A fresh authenticated event may supply a new passive window, without changing task origin.
        visible(requestId,target);if(now<target.receivedAt-30_000||now>target.replyUntil)throw new IllegalStateException("平台回复窗口已过期，保留原任务，请在原群重新/status查询");
        if(all.length()>=2000)throw new IllegalStateException("群投递引用数已达上限");
        if(!text&&row.getInt("mediaItems")>0) {int count=0;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("requestId").equals(requestId)&&!all.getJSONObject(i).getJSONObject("output").getString("mimeType").equals("text/plain"))count++;
            if(count>=row.getInt("mediaItems"))throw new IllegalStateException("本任务媒体投递额度已使用");}
        JSONObject next=GroupJson.copy(state),value=new JSONObject().put("deliveryId",id).put("requestId",requestId).put("taskId",row.getString("taskId"))
                .put("audience",row.getString("audience")).put("senderId",target.senderId).put("event",target.json()).put("kind",kind)
                .put("output",output.json()).put("state","prepared").put("platformMessageId","").put("createdAt",now);
        next.getJSONArray("deliveries").put(value);commit(next);return new GroupDeliveryPort.Delivery(value,true);
    }
    synchronized boolean beginDelivery(String id) throws Exception {open();JSONObject next=GroupJson.copy(state),row=delivery(next,id);if(!row.getString("state").equals("prepared"))return false;row.put("state","sending");commit(next);return true;}
    synchronized void deliveryUnknown(String id) throws Exception {open();JSONObject next=GroupJson.copy(state);JSONObject row=delivery(next,id);if(row.getString("state").equals("sending")){row.put("state","unknown");commit(next);}}
    synchronized GroupDeliveryPort.Delivery delivery(String id) throws Exception {open();return new GroupDeliveryPort.Delivery(delivery(state,id));}
    synchronized JSONObject deliveryReceipt(String id,GroupDeliveryPort.Receipt receipt) throws Exception {
        open();JSONObject next=GroupJson.copy(state),row=delivery(next,id);
        if(receipt!=null) {
            if(!id.equals(receipt.deliveryId))throw new SecurityException("投递回执身份不匹配");
            if(row.getString("state").equals("delivered")&&!receipt.state.equals("delivered"))throw new SecurityException("已送达结果不能被旧回执覆盖");
            if(!row.getString("platformMessageId").isEmpty()&&!receipt.platformMessageId.isEmpty()&&!row.getString("platformMessageId").equals(receipt.platformMessageId))throw new SecurityException("平台消息身份发生变化");
            row.put("state",receipt.state);if(!receipt.platformMessageId.isEmpty())row.put("platformMessageId",receipt.platformMessageId);commit(next);
        }
        // Keep only the stored output identity after stripping its content and transport event.
        JSONObject safe=GroupJson.copy(row).put("outputId",row.getJSONObject("output").getString("outputId"));safe.remove("event");safe.remove("output");return safe;
    }
    private static boolean visible(JSONObject row,String sender) throws Exception {return row.getString("senderId").equals(sender)||row.getString("visibility").equals("members");}
    private static JSONObject reference(JSONObject row) throws Exception {return new JSONObject().put("requestId",row.getString("requestId")).put("taskId",row.getString("taskId"))
            .put("groupId",row.getString("groupId")).put("spaceId",row.getString("spaceId")).put("goal",row.getString("goal")).put("capability",row.getString("capability"))
            .put("audience",row.getString("audience")).put("visibility",row.getString("visibility")).put("admission",row.getString("admission")).put("createdAt",row.getLong("createdAt")).put("messageParts",row.optJSONObject("event")==null?null:row.getJSONObject("event").optJSONObject("messageParts"));}
    private static JSONObject usage(JSONObject state,String groupId,String sender,long now) throws Exception {
        int daily=0,media=0,active=0;long tokens=0;JSONArray rows=state.getJSONArray("requests");
        for(int i=0;i<rows.length();i++) {JSONObject row=rows.getJSONObject(i);if(!row.getString("groupId").equals(groupId)||!sender.isEmpty()&&!row.getString("senderId").equals(sender))continue;
            if(!row.getBoolean("released"))active++;if(reservedOn(row,now/86_400_000L)) {daily++;tokens+=row.getLong("maxTokens");media+=row.getInt("mediaItems");}}
        return new JSONObject().put("dailyTasks",daily).put("reservedTokens",tokens).put("dailyMedia",media).put("activeTasks",active);
    }
    private static boolean reservedOn(JSONObject row,long day) throws Exception {
        if(row.getLong("createdAt")/86_400_000L==day)return true;JSONArray days=row.optJSONArray("recoveryDays");
        for(int i=0;days!=null&&i<days.length();i++)if(days.getLong(i)==day)return true;return false;
    }
    private static JSONObject defaultQuota() throws Exception {return new JSONObject().put("dailyTasks",20).put("memberDailyTasks",5).put("dailyTokens",40_000L)
            .put("maxTokensPerTask",4000L).put("maxConcurrent",2).put("dailyMedia",4).put("memberDailyMedia",1).put("maxMediaBytes",20*1024*1024L);}
    private static JSONObject quota(JSONObject input) throws Exception {JSONObject value=new JSONObject();
        value.put("dailyTasks",GroupJson.number(input,"dailyTasks",1,500)).put("memberDailyTasks",GroupJson.number(input,"memberDailyTasks",1,100))
                .put("dailyTokens",GroupJson.number(input,"dailyTokens",1,5_000_000L)).put("maxTokensPerTask",GroupJson.number(input,"maxTokensPerTask",1,100_000))
                .put("maxConcurrent",GroupJson.number(input,"maxConcurrent",1,8)).put("dailyMedia",GroupJson.number(input,"dailyMedia",0,100))
                .put("memberDailyMedia",GroupJson.number(input,"memberDailyMedia",0,20)).put("maxMediaBytes",GroupJson.number(input,"maxMediaBytes",1,20*1024*1024L));
        if(value.getInt("memberDailyTasks")>value.getInt("dailyTasks")||value.getLong("maxTokensPerTask")>value.getLong("dailyTokens")||value.getInt("memberDailyMedia")>value.getInt("dailyMedia"))throw new IllegalArgumentException("成员或单次限额不能超过全群限额");return value;}
    private static String capability(String value) {return GroupJson.one(value,"群能力（私人手机、浏览器、SSH和私人记忆不在群授权中）",CAPABILITIES);}
    private static JSONObject group(JSONObject state,String id) throws Exception {JSONObject result=GroupJson.find(state.getJSONArray("groups"),"id",id);if(result==null)throw new IllegalArgumentException("群空间不存在");return result;}
    private static JSONObject request(JSONObject state,String id) throws Exception {JSONObject result=GroupJson.find(state.getJSONArray("requests"),"requestId",id);if(result==null)throw new IllegalArgumentException("群任务引用不存在");return result;}
    private static JSONObject delivery(JSONObject state,String id) throws Exception {JSONObject result=GroupJson.find(state.getJSONArray("deliveries"),"deliveryId",id);if(result==null)throw new IllegalArgumentException("群投递不存在");return result;}
    private static JSONObject activeMember(JSONObject group,String sender) throws Exception {JSONObject result=GroupJson.find(group.getJSONArray("members"),"senderId",sender);if(result==null||!result.getBoolean("enabled"))throw new SecurityException("该稳定身份尚未获准加入群服务");return result;}
    private static JSONObject grant(JSONObject group,String capability,long now) throws Exception {JSONObject result=GroupJson.find(group.getJSONArray("grants"),"capability",capability);if(result==null||now>=result.getLong("expiresAt"))throw new SecurityException("该群能力未授权或已过期");return result;}
    private static boolean routeMatches(JSONObject group,GroupIngressPort.Event event) throws Exception {return group.getString("provider").equals(event.provider)&&group.getString("accountId").equals(event.accountId)&&group.getString("roomId").equals(event.roomId)&&group.optString("conversationKind","group").equals(event.conversationKind)&&group.optString("conversationParentId").equals(event.conversationParentId);}
    private static void sameRoute(JSONObject group,GroupIngressPort.Event event) throws Exception {if(!routeMatches(group,event))throw new SecurityException("认证消息不属于此机器人群空间");}
    private static void cas(JSONObject group,JSONObject input) throws Exception {if(GroupJson.number(input,"revision",1,Long.MAX_VALUE-1)!=group.getLong("revision"))throw new IllegalStateException("群设置已变化，请刷新后重核");}
    private static void audit(JSONObject next,String groupId,String type,long now) throws Exception {JSONArray rows=next.getJSONArray("audit");while(rows.length()>=500)rows.remove(0);rows.put(new JSONObject().put("groupId",groupId).put("type",type).put("at",now));}
    private void open() {if(closed)throw new IllegalStateException("群空间服务已关闭");}
    private void commit(JSONObject next) throws Exception {
        next.put("revision",state.optLong("revision",0)+1);
        byte[] bytes=next.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new IOException("群空间存储超过安全上限");
        File temporary=new File(file.getParentFile(),"registry.next");
        try(FileOutputStream stream=new FileOutputStream(temporary)) {stream.write(bytes);stream.getFD().sync();}
        Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;
    }
    @Override public synchronized void close() {if(closed)return;closed=true;try{lock.release();}catch(Exception ignored){}try{lockFile.close();}catch(Exception ignored){}}
}
