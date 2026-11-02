package dev.pocketengine.features.groups.host;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.groups.GroupHostPort;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit group-only routes. Never wraps HostToolSupervisor or imports private reference context. */
public final class GroupSafeCapabilities implements CapabilityPort {
    public static final class Services {
        public final GroupArtifactStore artifacts;
        public final GroupPublicSearch search;
        public final GroupMediaService media;
        public Services(GroupArtifactStore artifacts,GroupPublicSearch search,GroupMediaService media){this.artifacts=java.util.Objects.requireNonNull(artifacts);this.search=search;this.media=media;}
        void requireAvailable(GroupHostPort.Request request)throws Exception {
            switch(request.capability){case "public.answer":return;case "public.search":if(search!=null)return;break;case "group.files":if(request.inputArtifacts().length()>0)return;break;
                case "media.image":case "media.audio":case "media.video":if(media!=null&&media.available(request))return;break;default:throw new SecurityException("非群安全能力");}
            throw new IllegalStateException("本次群能力的真实服务或群附件尚未接入；未启动模型或伪造产物");
        }
    }
    private final TaskStore tasks;
    private final GroupHostPort.Request request;
    private final GroupHostPort.Authority authority;
    private final String run;
    private final int epoch;
    private final RunControl control;
    private final ModelConfig config;
    private final Services services;
    private final RunJournal journal;
    private final JSONArray definitions;
    GroupSafeCapabilities(TaskStore tasks,GroupHostPort.Request request,GroupHostPort.Authority authority,String run,int epoch,RunControl control,ModelConfig config,Services services,RunJournal journal)throws Exception {
        this.tasks=tasks;this.request=request;this.authority=authority;this.run=run;this.epoch=epoch;this.control=control;this.config=config;this.services=services;this.journal=journal;definitions=definitions(request.capability);
    }
    @Override public JSONArray tools()throws Exception {authority.check();return new JSONArray(definitions.toString());}
    @Override public boolean supports(String name){for(int i=0;i<definitions.length();i++)if(name.equals(definitions.optJSONObject(i).optJSONObject("function").optString("name")))return true;return false;}
    @Override public boolean sideEffecting(String name){return name.startsWith("group_media_")||name.equals("group_request_input");}
    @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl live)throws Exception {
        current(live);services.requireAvailable(request);JSONObject saved=journal.restore();JSONArray messages=saved.optJSONArray("messages");if(messages==null)throw new SecurityException("群任务缺少真实持久上下文");
        // Conservative resource reservations, not guessed provider billing: never reset on retries/runs.
        long bytes=new JSONObject().put("messages",messages).put("tools",definitions).toString().getBytes(StandardCharsets.UTF_8).length;
        long charge=Math.addExact(Math.addExact(Math.addExact(Math.multiplyExact(bytes,2),config.maxOutputTokens),512),dev.pocketengine.features.channels.ChannelGroupInputs.reserveTokens(request,config));
        tasks.reserveGroupModelTokens(request,run,epoch,charge,authority);current(live);
        return new JSONObject().put("tools",new JSONArray(definitions.toString())).put("reference",new JSONObject());
    }
    @Override public JSONObject contextSnapshot(String query)throws Exception {authority.check();return new JSONObject();}
    @Override public boolean responseCurrent(RunControl live)throws Exception {current(live);return true;}
    @Override public void validate(String name,JSONObject args)throws Exception {
        if(!supports(name))throw new SecurityException("此群授权不包含该工具");if(args==null||args.toString().length()>16384)throw new IllegalArgumentException("群工具参数无效");
        java.util.Set<String> allowed=name.equals("group_files_list")?java.util.Set.of():name.equals("group_file_read")?java.util.Set.of("artifactId"):name.equals("group_public_search")?java.util.Set.of("query"):name.equals("group_request_input")?java.util.Set.of("question"):java.util.Set.of("prompt");
        java.util.Iterator<String> keys=args.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new SecurityException("群参数不能覆盖身份、配置、路径或执行路由");
        if(!allowed.isEmpty()){String field=allowed.iterator().next();Object raw=args.opt(field);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>(field.equals("artifactId")?240:field.equals("question")?2000:4000))throw new IllegalArgumentException("群工具参数缺失或过长");}
    }
    @Override public JSONObject execute(String name,JSONObject args,RunControl live)throws Exception {
        current(live);JSONObject clean=new JSONObject(args.toString());String operation=clean.optString("_hostOperationId");clean.remove("_hostOperationId");validate(name,clean);
        JSONObject result;
        if(name.equals("group_request_input")) {
            if(operation.isEmpty())throw new SecurityException("群提问必须先登记原TaskStore操作");
            JSONObject question=tasks.requestGroupInput(request,run,epoch,operation,clean.getString("question"),live,authority);
            result=new JSONObject().put("ok",true).put("code","needs_user").put("questionId",question.getString("questionId")).put("message","等待原群补充："+question.getString("text"));
        }
        else if(name.equals("group_public_search"))result=services.search.search(clean.getString("query"),live);
        else if(name.equals("group_files_list"))result=new JSONObject().put("artifacts",services.artifacts.inputs(request,authority));
        else if(name.equals("group_file_read"))result=services.artifacts.readInputText(request,clean.getString("artifactId"),authority);
        else {
            if(operation.isEmpty())throw new SecurityException("群媒体必须先记录原TaskStore operation");
            JSONObject capability=new JSONObject().put("kind","native_group_media").put("requestId",request.requestId).put("spaceId",request.spaceId).put("actorId",request.actorId).put("capability",request.capability).put("resource",request.resource).put("policyEpoch",request.policyEpoch).put("audience",request.audience);
            JSONObject approval=tasks.requireCapabilityApproval(request.taskId,run,name,clean,capability);
            if(!approval.optBoolean("approved"))return new JSONObject().put("ok",false).put("accepted",false).put("dispatched",false).put("code","approval_required").put("approvalId",approval.getString("approvalId")).put("message","群媒体原参数等待本机主人明确审批；群消息中的同意不构成审批");
            current(live);
            result=services.media.execute(request,run,epoch,operation,clean.getString("prompt"),live,authority);
        }
        if(!sideEffecting(name))current(live);return result;
    }
    private void current(RunControl live)throws Exception {if(live!=control||!live.matchesHostRun(request.taskId,run,epoch))throw new SecurityException("群工具执行控制不匹配");live.check();authority.check();tasks.checkGroupTaskControl(request,live);}
    public static JSONArray definitions(String capability)throws Exception {
        JSONArray result=new JSONArray();switch(capability){case "public.answer":break;
            case "public.search":add(result,"group_public_search","通过已配置的公开检索服务查询；不读取私人浏览器、记忆或账号数据。","query");break;
            case "group.files":add(result,"group_files_list","仅列出本次原群事件实际导入的附件。","");add(result,"group_file_read","读取本次原群事件的UTF-8文字附件；不接受路径或URI。","artifactId");break;
            case "media.image":add(result,"group_media_image","通过原生授权的媒体服务生成群图片；未知结果不重试。","prompt");break;
            case "media.audio":add(result,"group_media_audio","通过原生授权的媒体服务生成群音频；未知结果不重试。","prompt");break;
            case "media.video":add(result,"group_media_video","通过原生授权的媒体服务生成群视频；未接入时不可用。","prompt");break;
            default:throw new SecurityException("非群安全能力");}
        add(result,"group_request_input","需要澄清时向原群提出一个公开问题并等待；回复只回原任务，不授予任何工具或私人权限。","question");
        return result;
    }
    private static void add(JSONArray tools,String name,String description,String field)throws Exception {
        JSONObject properties=new JSONObject();JSONArray required=new JSONArray();if(!field.isEmpty()){properties.put(field,new JSONObject().put("type","string").put("maxLength",field.equals("artifactId")?240:field.equals("question")?2000:4000));required.put(field);}
        tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false))));
    }
}
