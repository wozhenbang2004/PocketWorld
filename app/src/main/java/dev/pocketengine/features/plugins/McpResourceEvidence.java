package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact native resource snapshots in the original private AttachmentBook. Neither a snapshot,
 * a saved file nor its event is a model instruction, a grant, or evidence of a current server. */
public final class McpResourceEvidence {
    public static final String EVENT_TYPE="mcp_resource_retained";
    public static final String FORMAT="pocketworld.mcp-resource-snapshot.v1";
    public static final String INDEX_FORMAT="pocketworld.mcp-resource-retained.v1";
    public static final String COVERAGE="exact_retained_credential_filtered_mcp_resource_json";
    public static final int MAX_SNAPSHOT_BYTES=1_048_576;
    private static final long MAX_AGE_MILLIS=5*60_000L;
    private static final String ROLE="mcp_resource_snapshot";
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private McpResourceEvidence(){}

    /** Installation/source check only. Never retain the completed HTTP read's RunControl. */
    @FunctionalInterface public interface CurrentSourceCheck {void check()throws Exception;}

    /** Native existing-task metadata, not authority. TaskStore must check its original rows
     * before the file save and again in the original link/event transaction. */
    public static final class Target {
        public final String taskId,runId;public final int epoch;
        public Target(String taskId,String runId,int epoch){
            if(!uuid(taskId)||!uuid(runId)||epoch<1)throw new SecurityException("请选择已有私人任务及其原run，不能为保存来源新建任务");
            this.taskId=taskId;this.runId=runId;this.epoch=epoch;
        }
        public JSONObject view()throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch);}
        private boolean same(Target other){return other!=null&&taskId.equals(other.taskId)&&runId.equals(other.runId)&&epoch==other.epoch;}
    }

    /** Package-native producer seam: PluginsModule calls this only after an actual matched
     * resources/read and its original installation/source checks. There is no JSON restore API. */
    static Snapshot captured(JSONObject result,CurrentSourceCheck check)throws Exception{
        return captured(result,check,System::currentTimeMillis);
    }
    static Snapshot captured(JSONObject result,CurrentSourceCheck check,LongSupplier clock)throws Exception{
        Objects.requireNonNull(check).check();Snapshot snapshot=new Snapshot(checkedResult(result),check,Objects.requireNonNull(clock));
        snapshot.checkCurrent();return snapshot;
    }

    public static final class Snapshot implements AutoCloseable {
        private final String id=UUID.randomUUID().toString();
        private final JSONObject result,retained;
        private final byte[] bytes;
        private final String sha256;
        private final CurrentSourceCheck current;
        private final LongSupplier clock;
        private final long retrievedAt,expiresAt;
        private volatile boolean closed;
        private Target target;
        private boolean publicationEntered;
        private Snapshot(JSONObject result,CurrentSourceCheck current,LongSupplier clock)throws Exception{
            this.result=result;this.current=current;this.clock=clock;retrievedAt=clock.getAsLong();
            if(retrievedAt<1)throw new SecurityException("原资源读取时间不可核对");expiresAt=Math.addExact(retrievedAt,MAX_AGE_MILLIS);
            retained=McpData.copy(result);retained.remove("operationId");
            retained.put("format",FORMAT).put("snapshotId",id).put("readOperationId",result.getString("operationId"))
                    .put("retrievedAt",retrievedAt).put("historicalSnapshot",true).put("coverage",COVERAGE)
                    .put("serverResourceRevision",JSONObject.NULL);
            bytes=retained.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_SNAPSHOT_BYTES)throw new SecurityException("资源安全快照超出1MiB保存范围，没有裁剪成完整原件");
            sha256=sha(bytes);
        }
        public String snapshotId(){return id;}
        public String publicationId(){return id;}
        public JSONObject view()throws Exception{
            checkCurrent();return McpData.copy(result).put("snapshotId",id).put("publicationId",id)
                    .put("retrievedAt",retrievedAt).put("expiresAt",expiresAt).put("snapshotOnly",true)
                    .put("saved",false).put("executionAuthorized",false);
        }
        /** No Snapshot monitor, Tasks, Book, file IO, or old download control is acquired here. */
        public void checkCurrent()throws Exception{
            if(closed||clock.getAsLong()>=expiresAt)throw new SecurityException("这次资源读取窗口已结束，请明确重新读取；旧正文不能作为新保存授权");
            current.check();
            if(closed||clock.getAsLong()>=expiresAt)throw new SecurityException("原资源选择已撤回或过期");
        }
        public Publication saveForOwner(AttachmentBook originals,String taskId,String runId,int epoch,RunControl ownerControl)throws Exception{
            return saveForOwner(originals,new Target(taskId,runId,epoch),ownerControl);
        }
        public synchronized Publication saveForOwner(AttachmentBook originals,Target selected,RunControl ownerControl)throws Exception{
            Objects.requireNonNull(originals);Objects.requireNonNull(selected);Objects.requireNonNull(ownerControl).check();checkCurrent();
            if(target!=null&&!target.same(selected))throw new SecurityException("这次原资源保存已绑定另一原任务或run，不能换绑重复请求");
            boolean hasText=false;JSONArray contents=result.getJSONArray("contents");for(int i=0;i<contents.length();i++)hasText|=!contents.getJSONObject(i).getString("text").isEmpty();
            if(!hasText)throw new SecurityException("这次资源没有实际取得的文本正文，不能把二进制遗漏或名称存成文本证据");
            if(target==null)target=selected;
            String plan=McpData.digest(new JSONObject().put("publicationId",id).put("target",target.view()).put("sha256",sha256));
            JSONObject metadata=origin(target,retained).put("snapshotSha256",sha256);
            JSONObject actual;
            try(AutoCloseable authority=ownerControl.retainAuthorityCheck(this::checkCurrent)){
                ownerControl.check();
                if(publicationEntered){
                    actual=originals.findNativeResult(id,ROLE,target.taskId,target.runId,plan,ownerControl);
                    if(!actual.optBoolean("found"))throw new IllegalStateException("原资源发布尚无完整原件回执；请核对原发布，不重新保存或读取远端");
                }else{
                    publicationEntered=true;
                    actual=originals.importNativeResult(new ByteArrayInputStream(bytes),"mcp-resource-"+id.substring(0,8)+".json","application/json",metadata,id,ROLE,target.taskId,target.runId,plan,ownerControl);
                }
                ownerControl.check();checkCurrent();
                if(!sha256.equals(actual.optString("sha256"))||actual.optLong("size",-1)!=bytes.length)throw new SecurityException("原资源发布字节与这次固定快照不一致，不能覆盖或重存");
                AttachmentBook.OutputSourceEligibility generation=originals.outputSourceEligibility(actual.getString("id"),sha256);
                if(generation==null)throw new SecurityException("原资源文件尚未取得本机私人字节核验代次");
                return new Publication(target,id,actual,generation.generation,retained,plan,this::checkCurrent);
            }
        }
        @Override public void close(){closed=true;}
    }

    /** Actual original Book publication. Its accessors are metadata-only and safe under Tasks.
     * The original owner workflow separately fences the live plugin and the target transaction. */
    public static final class Publication {
        private final Target target;private final String id,attachment,index;
        private final CurrentSourceCheck current;
        private Publication(Target target,String id,JSONObject actual,String generation,JSONObject retained,String plan,CurrentSourceCheck current)throws Exception{
            this.target=target;this.id=id;this.current=current;
            JSONObject source=actual.getJSONObject("source"),nativePublication=source.getJSONObject("nativePublication");
            if(!ROLE.equals(source.optString("kind"))||!target.taskId.equals(source.optString("taskId"))||!target.runId.equals(source.optString("runId"))||target.epoch!=source.optInt("epoch")
                    ||!id.equals(nativePublication.optString("operationId"))||!ROLE.equals(nativePublication.optString("role"))||!plan.equals(nativePublication.optString("planDigest"))
                    ||!target.taskId.equals(nativePublication.optString("taskId"))||!target.runId.equals(nativePublication.optString("runId"))
                    ||!"owner".equals(actual.optString("principalId"))||!"private".equals(actual.optString("spaceId"))||actual.optBoolean("outputSourceRetired"))throw new SecurityException("原资源发布不属于这一次私人原件与任务");
            attachment=new JSONObject().put("id",actual.getString("id")).put("sha256",actual.getString("sha256"))
                    .put("mimeType",actual.getString("mimeType")).put("size",actual.getLong("size")).toString();
            JSONObject event=new JSONObject().put("type",EVENT_TYPE).put("format",INDEX_FORMAT).put("taskId",target.taskId).put("runId",target.runId).put("epoch",target.epoch)
                    .put("publicationId",id).put("attachmentId",actual.getString("id")).put("sha256",actual.getString("sha256")).put("attachmentGeneration",generation)
                    .put("size",actual.getLong("size")).put("source",McpData.copy(retained.getJSONObject("source"))).put("uri",retained.getString("uri"))
                    .put("descriptorDigest",retained.getString("descriptorDigest")).put("contentDigest",retained.getString("contentDigest"))
                    .put("readOperationId",retained.getString("readOperationId")).put("retrievedAt",retained.getLong("retrievedAt"))
                    .put("contentsCount",retained.getJSONArray("contents").length()).put("omittedContentCount",retained.getJSONArray("omittedContent").length())
                    .put("readContext","native_owner_resource_save").put("coverage",COVERAGE).put("historicalSnapshot",true).put("originalBodyIncluded",false)
                    .put("untrustedReference",true).put("instructionsAreData",true).put("automaticConversationInjection",false).put("executionAuthorized",false).put("businessVerified",false);
            if(retained.has("resourceTemplate"))event.put("resourceTemplate",McpResourceTemplateData.index(retained.getJSONObject("resourceTemplate")));
            requireTemplatePublicationMetadata(event,source);
            requireIndexEvent(event);index=event.toString();
        }
        public Target target(){return target;}
        public String taskId(){return target.taskId;}
        public String runId(){return target.runId;}
        public int epoch(){return target.epoch;}
        public String publicationId(){return id;}
        public JSONObject attachmentReference()throws Exception{return new JSONObject(attachment);}
        public JSONObject indexEvent()throws Exception{return new JSONObject(index);}
        public void checkCurrentSource()throws Exception{current.check();}
    }

    /** Pure index validation. A valid JSON index still cannot mint a native Publication. */
    public static void requireIndexEvent(JSONObject event)throws Exception{
        if(event==null||!EVENT_TYPE.equals(event.optString("type"))||!INDEX_FORMAT.equals(event.optString("format"))
                ||!"native_owner_resource_save".equals(event.optString("readContext"))||!COVERAGE.equals(event.optString("coverage"))
                ||!Boolean.TRUE.equals(event.opt("historicalSnapshot"))||!Boolean.TRUE.equals(event.opt("untrustedReference"))||!Boolean.TRUE.equals(event.opt("instructionsAreData"))
                ||!Boolean.FALSE.equals(event.opt("originalBodyIncluded"))||!Boolean.FALSE.equals(event.opt("automaticConversationInjection"))
                ||!Boolean.FALSE.equals(event.opt("executionAuthorized"))||!Boolean.FALSE.equals(event.opt("businessVerified"))
                ||event.has("contents")||event.has("text"))throw new SecurityException("原MCP保存事件缺少准确历史原件边界");
        for(String key:new String[]{"taskId","runId","publicationId","attachmentId","readOperationId"})if(!uuid(event.optString(key)))throw new SecurityException("原MCP保存事件身份无效");
        integer(event,"epoch",1,Integer.MAX_VALUE);integer(event,"size",1,MAX_SNAPSHOT_BYTES);integer(event,"retrievedAt",1,Long.MAX_VALUE);
        if(integer(event,"contentsCount",1,64)+integer(event,"omittedContentCount",0,64)>64)throw new SecurityException("原MCP保存范围超过原返回块数");
        for(String key:new String[]{"sha256","descriptorDigest","contentDigest"})hash(event,key);
        if(!event.optString("attachmentGeneration").matches("sha256:[a-f0-9]{64}"))throw new SecurityException("原MCP原件缺少准确本机代次");
        text(event,"uri",2048,false);checkedSource(event.getJSONObject("source"));
        if(event.has("resourceTemplate"))McpResourceTemplateData.checkIndex(event.getJSONObject("resourceTemplate"),event.getString("descriptorDigest"));
    }

    /** Explicit IO warmup, outside Tasks and MemoryBook locks. Saved source currentness is
     * the local original's generation; no current MCP connection or credentials are needed. */
    public static void prepareRetainedSource(AttachmentBook originals,JSONObject event,RunControl control)throws Exception{
        requireIndexEvent(event);Objects.requireNonNull(control).check();
        long bytes=originals.revalidateOutputSource(event.getString("attachmentId"),event.getString("sha256"),event.getString("attachmentGeneration"),MAX_SNAPSHOT_BYTES,control);
        if(bytes!=event.getLong("size"))throw new SecurityException("原MCP快照大小变化");
        requirePublicationMetadata(originals.get(event.getString("attachmentId")),event);control.check();
    }

    /** Exact saved UTF-8 JSON, including every obtained text block and its safe URI/MIME.
     * SourceRef ranges address this retained file, not an inferred remote document offset. */
    public static String readRetainedText(AttachmentBook originals,JSONObject event,RunControl control)throws Exception{
        prepareRetainedSource(originals,event,control);
        byte[] bytes=originals.readVerifiedSnapshot(event.getString("attachmentId"),event.getString("sha256"),control);
        if(bytes.length!=event.getLong("size")||bytes.length>MAX_SNAPSHOT_BYTES)throw new SecurityException("原MCP快照字节范围不一致");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JSONObject saved=new JSONObject(text);
        if(!FORMAT.equals(saved.optString("format"))||!event.getString("publicationId").equals(saved.optString("snapshotId"))||!Boolean.TRUE.equals(saved.opt("historicalSnapshot"))
                ||!COVERAGE.equals(saved.optString("coverage"))||saved.opt("serverResourceRevision")!=JSONObject.NULL)throw new SecurityException("原MCP快照格式或发布身份不同");
        JSONObject actual=McpData.copy(saved).put("operationId",saved.getString("readOperationId"));checkedResult(actual);
        for(String key:new String[]{"uri","descriptorDigest","contentDigest","readOperationId"})if(!event.getString(key).equals(saved.optString(key)))throw new SecurityException("原MCP快照与保存事件的来源不一致");
        requireTemplateIdentity(event,saved);
        if(event.getLong("retrievedAt")!=saved.getLong("retrievedAt")||event.getInt("contentsCount")!=saved.getJSONArray("contents").length()
                ||event.getInt("omittedContentCount")!=saved.getJSONArray("omittedContent").length()||!McpData.digest(event.getJSONObject("source")).equals(McpData.digest(saved.getJSONObject("source"))))throw new SecurityException("原MCP快照的来源、时间或范围改变");
        requirePublicationMetadata(originals.get(event.getString("attachmentId")),event);
        if(!originals.outputSourceCurrent(event.getString("attachmentId"),event.getString("sha256"),event.getString("attachmentGeneration")))throw new SecurityException("原MCP原件已经撤回或来源代次变化");
        control.check();return text;
    }

    private static JSONObject origin(Target target,JSONObject retained)throws Exception{
        JSONObject source=new JSONObject().put("kind",ROLE).put("taskId",target.taskId).put("runId",target.runId).put("epoch",target.epoch)
                .put("publicationId",retained.getString("snapshotId")).put("readOperationId",retained.getString("readOperationId"))
                .put("mcpSource",McpData.copy(retained.getJSONObject("source"))).put("uri",retained.getString("uri"))
                .put("descriptorDigest",retained.getString("descriptorDigest")).put("contentDigest",retained.getString("contentDigest"))
                .put("retrievedAt",retained.getLong("retrievedAt")).put("historicalSnapshot",true).put("untrustedReference",true).put("executionAuthorized",false);
        // The Book's public metadata omits private source URIs. Keep only an identity digest
        // here; the exact template and arguments live in the SHA-bound saved JSON.
        if(retained.has("resourceTemplate"))source.put("resourceTemplateDigest",McpData.digest(McpResourceTemplateData.index(retained.getJSONObject("resourceTemplate"))));
        return source;
    }
    private static void requirePublicationMetadata(JSONObject actual,JSONObject event)throws Exception{
        JSONObject origin=actual.getJSONObject("source"),nativePublication=origin.getJSONObject("nativePublication");
        // AttachmentBook's public metadata intentionally omits source.uri/url. The original
        // URI is checked against the SHA-bound private JSON in readRetainedText, not this view.
        if(!event.getString("attachmentId").equals(actual.optString("id"))||!event.getString("sha256").equals(actual.optString("sha256"))||event.getLong("size")!=actual.optLong("size",-1)
                ||!"application/json".equals(actual.optString("mimeType"))||!"owner".equals(actual.optString("principalId"))||!"private".equals(actual.optString("spaceId"))
                ||actual.optBoolean("outputSourceRetired")||!ROLE.equals(origin.optString("kind"))||origin.has("groupSpaceId")||!ROLE.equals(nativePublication.optString("role")))throw new SecurityException("原MCP附件不是原私人历史快照");
        for(String key:new String[]{"taskId","runId"})if(!event.getString(key).equals(origin.optString(key))||!event.getString(key).equals(nativePublication.optString(key)))throw new SecurityException("原MCP快照不属于所选原任务与run");
        String plan=McpData.digest(new JSONObject().put("publicationId",event.getString("publicationId"))
                .put("target",new Target(event.getString("taskId"),event.getString("runId"),event.getInt("epoch")).view()).put("sha256",event.getString("sha256")));
        if(!plan.equals(nativePublication.optString("planDigest"))||event.getLong("retrievedAt")!=origin.optLong("retrievedAt")
                ||!Boolean.TRUE.equals(origin.opt("historicalSnapshot"))||!Boolean.TRUE.equals(origin.opt("untrustedReference"))||!Boolean.FALSE.equals(origin.opt("executionAuthorized"))
                ||event.getInt("epoch")!=origin.optInt("epoch")||!event.getString("publicationId").equals(origin.optString("publicationId"))
                ||!event.getString("publicationId").equals(nativePublication.optString("operationId"))||!event.getString("sha256").equals(origin.optString("snapshotSha256"))
                ||!event.getString("readOperationId").equals(origin.optString("readOperationId"))||!event.getString("contentDigest").equals(origin.optString("contentDigest"))
                ||!event.getString("descriptorDigest").equals(origin.optString("descriptorDigest"))
                ||!McpData.digest(event.getJSONObject("source")).equals(McpData.digest(origin.getJSONObject("mcpSource"))))throw new SecurityException("原MCP快照保存来源不匹配");
        requireTemplatePublicationMetadata(event,origin);
    }
    private static void requireTemplatePublicationMetadata(JSONObject event,JSONObject origin)throws Exception{
        if(event.has("resourceTemplate")!=origin.has("resourceTemplateDigest"))throw new SecurityException("原资源发布的模板来源被增加或移除");
        if(event.has("resourceTemplate")&&!McpData.digest(event.getJSONObject("resourceTemplate")).equals(origin.optString("resourceTemplateDigest")))
            throw new SecurityException("原资源发布的模板身份不同");
    }
    private static void requireTemplateIdentity(JSONObject index,JSONObject source)throws Exception{
        if(index.has("resourceTemplate")!=source.has("resourceTemplate"))throw new SecurityException("原资源模板来源被增加或移除");
        if(!index.has("resourceTemplate"))return;
        JSONObject expected=index.getJSONObject("resourceTemplate"),actual=McpResourceTemplateData.index(source.getJSONObject("resourceTemplate"));
        if(!McpData.digest(expected).equals(McpData.digest(actual)))throw new SecurityException("原资源模板或参数身份变化");
    }
    private static JSONObject checkedResult(JSONObject supplied)throws Exception{
        if(supplied==null||!uuid(supplied.optString("operationId"))||!Boolean.TRUE.equals(supplied.opt("instructionsAreData"))
                ||!Boolean.FALSE.equals(supplied.opt("automaticConversationInjection"))||!Boolean.FALSE.equals(supplied.opt("executionAuthorized")))throw new SecurityException("只接受原MCP宿主实际读取的低信任文本回执");
        JSONObject result=new JSONObject().put("source",checkedSource(supplied.getJSONObject("source"))).put("operationId",supplied.getString("operationId"))
                .put("uri",text(supplied,"uri",2048,false)).put("descriptorDigest",hash(supplied,"descriptorDigest")).put("contentDigest",hash(supplied,"contentDigest"));
        if(supplied.has("resourceTemplate"))result.put("resourceTemplate",McpResourceTemplateData.checkedProvenance(supplied.getJSONObject("resourceTemplate"),result.getString("uri"),result.getString("descriptorDigest")));
        JSONArray contents=supplied.getJSONArray("contents"),omitted=supplied.getJSONArray("omittedContent"),copied=new JSONArray(),missing=new JSONArray();
        if(contents.length()+omitted.length()>64)throw new SecurityException("原MCP资源返回块超过64项");
        for(int i=0;i<contents.length();i++){
            JSONObject row=contents.getJSONObject(i);String body=text(row,"text",MAX_SNAPSHOT_BYTES,true);
            copied.put(new JSONObject().put("uri",text(row,"uri",2048,false)).put("mimeType",text(row,"mimeType",256,true)).put("text",body));
        }
        for(int i=0;i<omitted.length();i++){
            JSONObject row=omitted.getJSONObject(i);long index=integer(row,"index",0,63);
            if(!"binary_content_not_decoded_or_fetched".equals(row.optString("reason")))throw new SecurityException("原资源遗漏范围不可核对");
            missing.put(new JSONObject().put("index",index).put("uri",text(row,"uri",2048,false)).put("reason",row.getString("reason")));
        }
        if(!result.getString("contentDigest").equals(McpData.digest(copied)))throw new SecurityException("原MCP文本摘要与实际内容不匹配");
        return result.put("contents",copied).put("omittedContent",missing).put("untrusted",true).put("untrustedReference",true)
                .put("instructionsAreData",true).put("automaticConversationInjection",false).put("executionAuthorized",false);
    }
    private static JSONObject checkedSource(JSONObject source)throws Exception{
        if(source==null||!installationId(source.optString("installationId"))||!McpData.supportedVersion(source.optString("protocolVersion")))throw new SecurityException("原MCP安装或协议来源缺失");
        return new JSONObject().put("pluginId",text(source,"pluginId",160,false)).put("installationId",source.getString("installationId"))
                .put("stateRevision",integer(source,"stateRevision",0,Long.MAX_VALUE)).put("protocolVersion",source.getString("protocolVersion"))
                .put("endpointDigest",hash(source,"endpointDigest")).put("serverDigest",hash(source,"serverDigest"));
    }
    private static String text(JSONObject source,String key,int max,boolean empty)throws Exception{
        Object raw=source.get(key);if(!(raw instanceof String))throw new SecurityException("原MCP文本字段类型无效");String value=(String)raw;
        if(value.length()>max||!empty&&value.isEmpty())throw new SecurityException("原MCP文本字段范围无效");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw new SecurityException("原MCP文本含不完整Unicode字符");}
            else if(Character.isLowSurrogate(c))throw new SecurityException("原MCP文本含不完整Unicode字符");
            else if(!key.equals("text")&&(c<32||c==127))throw new SecurityException("原MCP来源字段含控制字符");
        }return value;
    }
    private static long integer(JSONObject source,String key,long min,long max)throws Exception{
        Object value=source.get(key);if(!(value instanceof Integer||value instanceof Long))throw new SecurityException("原MCP索引整数类型无效");
        long n=((Number)value).longValue();if(n<min||n>max)throw new SecurityException("原MCP索引整数范围无效");return n;
    }
    private static String hash(JSONObject value,String key)throws Exception{String found=text(value,key,64,false);if(!found.matches("[a-f0-9]{64}"))throw new SecurityException("原MCP摘要无效");return found;}
    private static boolean uuid(String value){return value!=null&&value.matches(UUID_PATTERN);}
    private static boolean installationId(String value){
        if(uuid(value))return true;
        if(value==null||!value.matches("legacy-[1-9][0-9]{0,18}"))return false;
        try{return Long.parseLong(value.substring(7))>0;}catch(NumberFormatException outsideTimestamp){return false;}
    }
    private static String sha(byte[] bytes)throws Exception{
        StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
    }
}
