package dev.pocketengine.features.remote;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original-job observation cursor only. A historical event never grants answer/approval authority. */
public final class RemoteJobEvents {
    public static final String PROTOCOL="original_job_events_v1", FILTER="normalized_driver_observations_v1";
    public static final String CHILD_SOURCE_PROTOCOL="claude_complete_child_v1";
    public static final String CLAUDE_CHILD_PROGRESS_VERSION="2.1.317";
    public static final String CODEX_CHILD_SOURCE_PROTOCOL="codex_public_child_v1", CODEX_CHILD_VERSION="0.166.0-alpha.1";
    private RemoteJobEvents(){}
    public static final class Page {
        private final JSONObject scope,request,value;
        private Page(JSONObject scope,JSONObject request,JSONObject value)throws Exception{this.scope=copy(scope);this.request=copy(request);this.value=copy(value);}
        public JSONObject scope()throws Exception{return copy(scope);}
        public JSONObject view()throws Exception{return copy(value);}
    }
    public static boolean supported(JSONObject job){JSONObject caps=job.optJSONObject("capabilities"),events=caps==null?null:caps.optJSONObject("originalJobEvents");return events!=null&&PROTOCOL.equals(events.optString("protocol"))&&FILTER.equals(events.optString("filter"))&&events.opt("supported")==Boolean.TRUE&&events.optInt("maxPageEvents")==32;}
    public static JSONObject scope(JSONObject source,String binding)throws Exception {
        if(binding==null||!binding.matches("[a-f0-9]{64}"))throw new SecurityException("缺少原电脑配置代次");
        JSONObject result=new JSONObject().put("filter",FILTER).put("profileBindingHash",binding);
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobId","jobOperationId","environmentId","worker"})result.put(key,source.get(key));
        for(String key:new String[]{"threadId","sessionId","promptRequestId","turnId"})if(!source.optString(key).isEmpty())result.put(key,source.getString(key));
        String version=codexRuntimeVersion(source);if(version!=null)result.put("nativeRuntimeVersion",version);
        if(claudeProgressRuntimeVersion(source)!=null){
            JSONObject runtime=source.getJSONObject("modelRuntime"),bound=new JSONObject();
            for(String key:new String[]{"protocol","worker","runtimeVersion","sessionId","promptRequestId"})bound.put(key,runtime.getString(key));
            result.put("modelRuntime",bound);
        }
        if(!source.optString("protocol").isEmpty())result.put("nativeProtocol",source.getString("protocol"));
        return result;
    }
    public static JSONObject request(JSONObject previous,JSONObject scope)throws Exception {
        boolean same=previous!=null&&same(previous.optJSONObject("scope"),scope);
        return new JSONObject().put("generation",same?previous.optString("generation"):"").put("after",same?previous.optLong("nextSequence"):0)
                .put("head",same&&previous.optBoolean("hasMore")?previous.optLong("headSequence"):0).put("limit",32).put("filter",FILTER);
    }
    /** Body-free read projection only. A native parent tool-use ID is NOT a child session handle. */
    public static JSONObject sourceView(JSONObject event,JSONObject originalSource)throws Exception {
        boolean marked=false;for(String key:new String[]{"childSourceProtocol","parentToolUseId","parentSessionId","parentPromptRequestId","nativeAgentId","nativeMessageId","nativeEventId","nativeLineageVersion","nativeParentProof","nativeParentThreadId","nativeParentTurnId","nativeChildThreadId","nativeChildTurnId"})marked|=event.has(key);
        JSONObject view=new JSONObject().put("executionAuthorized",false).put("childSessionControl",false).put("completeTranscript",false);
        if(!marked)return view.put("kind","main_or_unspecified").put("key","main_or_unspecified");
        if(CODEX_CHILD_SOURCE_PROTOCOL.equals(event.optString("childSourceProtocol")))return codexChildSource(event,originalSource,view);
        if(!CHILD_SOURCE_PROTOCOL.equals(event.optString("childSourceProtocol"))||!"claude".equals(originalSource.optString("worker"))||!"claude_stream_json".equals(originalSource.optString("nativeProtocol",originalSource.optString("protocol"))))throw new SecurityException("child观察缺少原受支持driver来源");
        String session=originalSource.optString("sessionId",originalSource.optString("threadId")),prompt=originalSource.optString("promptRequestId");
        if(!session.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||!prompt.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||!session.equals(event.optString("parentSessionId"))||!prompt.equals(event.optString("parentPromptRequestId")))throw new SecurityException("child观察不是此原session/prompt，不混入父记录");
        String kind=event.optString("kind"),nativeType=event.optString("nativeType");
        boolean progress=kind.equals("tool")&&nativeType.equals("tool_progress");
        if(progress)requireClaudeChildProgress(event,originalSource);
        else if(!(kind.equals("child_text")&&nativeType.matches("assistant/text|user/text")&&event.opt("text") instanceof String||kind.equals("tool")&&nativeType.equals("assistant/tool_use")||kind.equals("tool_result")&&nativeType.equals("user/tool_result")))throw new SecurityException("child观察只接完整公开text/tool block与固定版本工具进度，不接thinking或审批");
        if(!event.has("parentToolUseId"))throw new SecurityException("child观察没有原parent tool-use身份");for(String key:new String[]{"parentToolUseId","nativeAgentId","nativeMessageId","nativeEventId"})if(event.has(key)&&(!(event.opt(key) instanceof String)||!event.getString(key).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")))throw new SecurityException("child原生来源ID无效，未成为可操作会话");
        String parent=event.getString("parentToolUseId");view.put("kind","child").put("key","child:"+parent).put("sourceProtocol",CHILD_SOURCE_PROTOCOL).put("parentToolUseId",parent).put("parentSessionId",session).put("parentPromptRequestId",prompt);
        if(progress)view.put("nativeLineageVersion",CLAUDE_CHILD_PROGRESS_VERSION).put("publicObservationOnly",true);
        for(String key:new String[]{"nativeAgentId","nativeMessageId","nativeEventId"})if(event.has(key))view.put(key,event.getString(key));return view;
    }
    /** This is a projection of the original authenticated job readback, never a requested version. */
    private static String claudeProgressRuntimeVersion(JSONObject source){
        if(!"claude".equals(source.optString("worker")))return null;
        JSONObject runtime=source.optJSONObject("modelRuntime");
        if(runtime==null||!"native_model_runtime_v1".equals(runtime.optString("protocol"))||!"claude".equals(runtime.optString("worker"))
                ||!source.optString("sessionId").equals(runtime.optString("sessionId"))||!source.optString("promptRequestId").equals(runtime.optString("promptRequestId")))return null;
        return CLAUDE_CHILD_PROGRESS_VERSION.equals(runtime.optString("runtimeVersion"))?CLAUDE_CHILD_PROGRESS_VERSION:null;
    }
    private static void requireClaudeChildProgress(JSONObject event,JSONObject source)throws Exception {
        if(!CLAUDE_CHILD_PROGRESS_VERSION.equals(claudeProgressRuntimeVersion(source))||!CLAUDE_CHILD_PROGRESS_VERSION.equals(event.optString("nativeLineageVersion")))throw new SecurityException("Claude子工具进度缺少此原session/prompt的固定版本读回");
        if(!childId(event,"itemId")||!(event.opt("nativeEventId") instanceof String)||!event.getString("nativeEventId").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                ||!(event.opt("tool") instanceof String)||event.getString("tool").isBlank()||event.getString("tool").length()>200||event.getString("tool").codePoints().anyMatch(c->c<32||c==127||c>=0xd800&&c<=0xdfff)||!"running".equals(event.optString("status")))throw new SecurityException("Claude子工具进度的原生身份或公开状态无效");
        Set<String> fields=Set.of("kind","nativeType","itemId","sequence","observedAt","tool","status","childSourceProtocol","parentToolUseId","parentSessionId","parentPromptRequestId","nativeEventId","nativeLineageVersion");
        var keys=event.keys();while(keys.hasNext())if(!fields.contains(keys.next()))throw new SecurityException("Claude子工具进度含未声明字段，不显示隐藏正文或创建操作权");
    }
    private static String codexRuntimeVersion(JSONObject source){
        if(!"codex_app_server".equals(source.optString("worker")))return null;
        String bound=source.optString("nativeRuntimeVersion");if(CODEX_CHILD_VERSION.equals(bound))return bound;
        JSONObject runtime=source.optJSONObject("modelRuntime");if(runtime==null||!"native_model_runtime_v1".equals(runtime.optString("protocol"))||!"codex_app_server".equals(runtime.optString("worker"))||!source.optString("threadId").equals(runtime.optString("threadId"))||!source.optString("turnId").equals(runtime.optString("turnId")))return null;
        return CODEX_CHILD_VERSION.equals(runtime.optString("runtimeVersion"))?CODEX_CHILD_VERSION:null;
    }
    private static boolean childId(JSONObject event,String key){return event.opt(key) instanceof String&&event.optString(key).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}");}
    private static JSONObject codexChildSource(JSONObject event,JSONObject source,JSONObject view)throws Exception {
        if(!"codex_app_server".equals(source.optString("worker"))||!"codex_app_server".equals(source.optString("nativeProtocol",source.optString("protocol")))||!CODEX_CHILD_VERSION.equals(codexRuntimeVersion(source))||!CODEX_CHILD_VERSION.equals(event.optString("nativeLineageVersion"))||!"thread_started_direct_subagent_v1".equals(event.optString("nativeParentProof")))throw new SecurityException("Codex child观察缺少原固定版本和native parent证据");
        for(String key:new String[]{"nativeParentThreadId","nativeParentTurnId","nativeChildThreadId","itemId"})if(!childId(event,key))throw new SecurityException("Codex child原生来源身份不可核对");
        String parent=event.getString("nativeParentThreadId"),turn=event.getString("nativeParentTurnId"),child=event.getString("nativeChildThreadId");
        if(!parent.equals(source.optString("threadId"))||!turn.equals(source.optString("turnId"))||parent.equals(child))throw new SecurityException("Codex child不属于此原thread/turn");
        Object observation=event.opt("driverObservationIndex");if(!(observation instanceof Number)||!(observation instanceof Integer||observation instanceof Long)||((Number)observation).longValue()<1)throw new SecurityException("child本地观察序号无效，不能视作provider replay身份");
        String kind=event.optString("kind"),method=event.optString("nativeType"),form=event.optString("textForm");
        boolean manifest=kind.equals("child_source")&&method.equals("thread/started")&&child.equals(event.optString("itemId"));
        boolean text=kind.equals("child_text")&&event.opt("text") instanceof String&&(method.equals("item/agentMessage/delta")&&form.equals("received_public_delta_not_complete_message")||method.equals("item/completed")&&form.equals("received_complete_public_message"));
        boolean tool=kind.equals("tool")&&method.matches("item/started|item/completed")&&Set.of("commandExecution","fileChange","mcpToolCall","dynamicToolCall","collabAgentToolCall","webSearch","imageView").contains(event.optString("tool"));
        boolean status=kind.equals("child_status")&&method.equals("turn/completed")&&event.optString("status").matches("completed|failed|interrupted");
        if(!(manifest||text||tool||status)||!manifest&&!childId(event,"nativeChildTurnId"))throw new SecurityException("只接Codex已收到的公开child事件，不接thinking/用量/审批或恢复");
        if(manifest&&(event.has("text")||event.has("textForm")||event.has("tool")||event.has("status")||event.has("nativeChildTurnId"))||!text&&(event.has("text")||event.has("textForm"))||text&&(event.has("tool")||event.has("status"))||tool&&!event.optString("status").equals(method.equals("item/started")?"started":"completed")||status&&(!event.getString("itemId").equals(event.getString("nativeChildTurnId"))||event.has("tool")))throw new SecurityException("child公开事件形状不匹配，不借其他字段显示未知内容");
        Set<String> fields=Set.of("kind","nativeType","itemId","sequence","observedAt","childSourceProtocol","nativeLineageVersion","nativeParentProof","nativeParentThreadId","nativeParentTurnId","nativeChildThreadId","nativeChildTurnId","driverObservationIndex","text","textTruncated","textForm","tool","status","summary","summaryTruncated");
        var keys=event.keys();while(keys.hasNext())if(!fields.contains(keys.next()))throw new SecurityException("Codex child观察含未声明字段，原记录不改作父结果或授权");
        return view.put("kind","child").put("key","codex-child:"+parent+":"+turn+":"+child).put("sourceProtocol",CODEX_CHILD_SOURCE_PROTOCOL).put("nativeParentThreadId",parent).put("nativeParentTurnId",turn).put("nativeChildThreadId",child).put("nativeLineageVersion",CODEX_CHILD_VERSION).put("publicObservationOnly",true).put("providerReplayIdentity",false);
    }
    /** Package-only factory: RemoteModule calls this after the original authenticated GET and fences. */
    static Page received(JSONObject scope,JSONObject request,JSONObject actual)throws Exception {
        if(actual.toString().getBytes(StandardCharsets.UTF_8).length>32768||!PROTOCOL.equals(actual.optString("protocol"))||!FILTER.equals(actual.optString("filter"))
                ||!scope.getString("jobId").equals(actual.optString("id"))||!scope.getString("jobOperationId").equals(actual.optString("operationId"))
                ||actual.opt("liveStreaming")!=Boolean.FALSE||actual.opt("completeHistory")!=Boolean.FALSE||actual.opt("executionAuthorized")!=Boolean.FALSE)throw new SecurityException("不是支持的原job只读事件页");
        if(!request.getString("generation").equals(actual.optString("requestedGeneration"))||number(request,"after")!=number(actual,"requestedAfter")||number(request,"head")!=number(actual,"requestedHead"))throw new SecurityException("事件回执不是本次准确游标请求");
        for(String key:new String[]{"environmentId","worker","nativeProtocol","threadId","sessionId","promptRequestId","turnId","nativeRuntimeVersion"})if(scope.has(key)&&!scope.get(key).equals(actual.opt(key)))throw new SecurityException("事件页不是原环境、worker或native会话");
        String status=actual.getString("status"),generation=actual.getString("generation");JSONArray events=actual.getJSONArray("events");
        if(!Set.of("page","generation_changed","not_available").contains(status)||events.length()>32||events.toString().getBytes(StandardCharsets.UTF_8).length>24576||!(actual.opt("hasMore") instanceof Boolean))throw new SecurityException("事件页或范围不支持");
        long after=number(request,"after"),head=number(actual,"headSequence"),last=number(actual,"lastSequence"),next=number(actual,"nextSequence"),first=number(actual,"retainedFromSequence");
        if(status.equals("not_available")){if(!generation.isEmpty()||events.length()!=0||head!=0||next!=0||last!=0||first!=0||actual.optBoolean("hasMore")||actual.optJSONObject("gap")!=null)throw new SecurityException("未就绪事件页不能确认任何游标");return new Page(scope,request,actual);}
        if(!generation.matches("[a-f0-9-]{36}")||first<1||head>last||first>last+1)throw new SecurityException("事件代次或保留窗口无效");
        JSONObject gap=actual.optJSONObject("gap");
        if(status.equals("generation_changed")){
            if(request.getString("generation").isEmpty()||generation.equals(request.getString("generation"))||events.length()!=0||next!=0||actual.optBoolean("hasMore")||gap==null||!"generation_changed".equals(gap.optString("reason"))||!request.getString("generation").equals(gap.optString("previousGeneration")))throw new SecurityException("事件代次变化缺少明确原缺口");
            return new Page(scope,request,actual);
        }
        if(!request.getString("generation").isEmpty()&&!generation.equals(request.getString("generation"))||number(request,"head")!=0&&head!=number(request,"head")||after>head)throw new SecurityException("原事件固定head或代次变化，不能静默续接");
        long cursor=after;
        if(gap!=null){if(!"retention_gap".equals(gap.optString("reason"))||number(gap,"fromSequence")!=after+1||number(gap,"throughSequence")!=Math.min(first-1,head)||number(gap,"throughSequence")<after+1)throw new SecurityException("事件缺口不能捏造或跳过已保留内容");cursor=number(gap,"throughSequence");}
        else if(after<Math.min(first-1,head))throw new SecurityException("事件页遗漏缺口说明");
        for(int i=0;i<events.length();i++){JSONObject row=events.getJSONObject(i);if(number(row,"sequence")!=++cursor||cursor<first||cursor>head||!(row.opt("kind") instanceof String)||!(row.opt("nativeType") instanceof String)||number(row,"observedAt")<1)throw new SecurityException("事件序号不连续或不是原观察");sourceView(row,scope);}
        if(next!=cursor||next>head||actual.optBoolean("hasMore")!=(next<head)||next<head&&events.length()==0)throw new SecurityException("事件页无进展或游标越界");
        return new Page(scope,request,actual);
    }
    /** Called inside the original TaskStore transaction; receipt validation precedes cursor advancement. */
    public static JSONObject merge(JSONObject original,Page received)throws Exception {
        JSONObject page=received.value,request=received.request,scope=received.scope;
        JSONObject previous=original!=null&&same(original.optJSONObject("scope"),scope)?copy(original):null;
        long cursor=previous==null?0:number(previous,"nextSequence");String generation=previous==null?"":previous.optString("generation");
        if(previous!=null&&page.getString("status").equals("generation_changed")&&page.getString("generation").equals(generation))return previous;
        if(number(request,"after")>cursor)throw new SecurityException("读取游标领先原持久记录，不能跳过本机提交");
        if(!generation.equals(request.getString("generation"))){
            if(page.getString("generation").equals(generation)&&page.getString("status").equals("generation_changed"))return previous;
            if(!request.getString("generation").isEmpty()||!page.getString("generation").equals(generation))throw new SecurityException("原持久游标已变化，不能确认另一代读取");
        }
        if(page.getString("status").equals("not_available"))return previous==null?new JSONObject().put("scope",scope).put("protocol",PROTOCOL).put("generation","").put("nextSequence",0).put("headSequence",0).put("hasMore",false).put("events",new JSONArray()).put("status","not_available"):previous;
        boolean changed=page.getString("status").equals("generation_changed");
        TreeMap<Long,JSONObject> rows=new TreeMap<>();JSONArray old=previous==null?null:previous.optJSONArray("events");
        if(!changed)for(int i=0;old!=null&&i<old.length();i++){JSONObject row=old.getJSONObject(i);rows.put(number(row,"sequence"),row);}
        JSONArray incoming=page.getJSONArray("events");for(int i=0;i<incoming.length();i++){JSONObject row=incoming.getJSONObject(i);long sequence=number(row,"sequence");JSONObject saved=rows.get(sequence);if(saved!=null&&!same(saved,row))throw new SecurityException("同原事件序号返回了不同内容，未推进游标");if(sequence>cursor)rows.put(sequence,copy(row));}
        if(!changed&&number(page,"nextSequence")<cursor)return previous;
        JSONArray gaps=previous==null?new JSONArray():previous.optJSONArray("gaps");if(gaps==null)gaps=new JSONArray();
        JSONObject gap=page.optJSONObject("gap");if(gap!=null){JSONObject exact=copy(gap).put("generation",page.getString("generation"));boolean known=false;for(int i=0;i<gaps.length();i++)known|=same(gaps.getJSONObject(i),exact);if(!known)gaps.put(exact);while(gaps.length()>8)gaps.remove(0);}
        JSONArray events=new JSONArray();for(JSONObject row:rows.values())events.put(row);long dropped=previous==null?0:previous.optLong("locallyDiscardedEvents");while(events.length()>64||events.toString().getBytes(StandardCharsets.UTF_8).length>24576){events.remove(0);dropped++;}
        return new JSONObject().put("protocol",PROTOCOL).put("scope",scope).put("generation",page.getString("generation")).put("nextSequence",number(page,"nextSequence"))
                .put("headSequence",number(page,"headSequence")).put("lastSequence",Math.max(previous==null||changed?0:previous.optLong("lastSequence"),number(page,"lastSequence")))
                .put("retainedFromSequence",number(page,"retainedFromSequence")).put("hasMore",changed||page.getBoolean("hasMore")).put("status",changed?"generation_changed":"current")
                .put("events",events).put("gaps",gaps).put("locallyDiscardedEvents",dropped).put("sourceScopeChanged",original!=null&&previous==null||previous!=null&&previous.optBoolean("sourceScopeChanged"))
                .put("completeHistory",false).put("liveStreaming",false).put("executionAuthorized",false).put("committedAt",System.currentTimeMillis());
    }
    private static long number(JSONObject value,String key)throws Exception{Object raw=value.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<0||((Number)raw).longValue()>9_007_199_254_740_991L)throw new SecurityException("事件游标不是有界整数");return ((Number)raw).longValue();}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    public static boolean same(JSONObject first,JSONObject second)throws Exception{return first!=null&&second!=null&&canonical(first).equals(canonical(second));}
    private static String canonical(Object raw)throws Exception{if(raw instanceof JSONObject){JSONObject value=(JSONObject)raw;TreeMap<String,String> fields=new TreeMap<>();for(java.util.Iterator<String> it=value.keys();it.hasNext();){String key=it.next();fields.put(key,canonical(value.get(key)));}StringBuilder out=new StringBuilder("{");for(var row:fields.entrySet()){if(out.length()>1)out.append(',');out.append(JSONObject.quote(row.getKey())).append(':').append(row.getValue());}return out.append('}').toString();}if(raw instanceof JSONArray){JSONArray value=(JSONArray)raw;StringBuilder out=new StringBuilder("[");for(int i=0;i<value.length();i++){if(i>0)out.append(',');out.append(canonical(value.get(i)));}return out.append(']').toString();}return raw instanceof String?JSONObject.quote((String)raw):String.valueOf(raw);}
}
