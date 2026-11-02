package dev.pocketengine.features.memory;

import java.util.Objects;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Identity/version of host-owned evidence, never a permission or an embedded evidence body. */
public final class SourceRef {
    public final String kind,sourceId,ownerId,spaceId;
    public final long revision;
    /** Present only for workspace_file. This descriptor identifies bytes; it grants no read. */
    public final WorkspaceLocation workspace;

    public SourceRef(String kind,String sourceId,long revision,String ownerId,String spaceId) {
        this(kind,sourceId,revision,ownerId,spaceId,null);
    }
    private SourceRef(String kind,String sourceId,long revision,String ownerId,String spaceId,WorkspaceLocation location) {
        if(!Set.of("message","task_evidence","workspace_file").contains(kind))throw new IllegalArgumentException("未知外部记忆来源类型");
        boolean file="workspace_file".equals(kind);
        if(sourceId==null||!(file?sourceId.matches("vws:[A-Za-z0-9_-]{1,2044}"):sourceId.matches("[\\p{L}\\p{N}_.:/@-]{1,240}")))throw new IllegalArgumentException("来源标识无效");
        if(file&&(location==null||!sourceId.equals(workspaceId(location.projectId,location.path,location.offset,location.chars)))||!file&&location!=null)throw new IllegalArgumentException("工作区定位与来源标识不符");
        if(revision<1)throw new IllegalArgumentException("来源版本必须为正整数");
        if(file&&revision>Integer.MAX_VALUE)throw new IllegalArgumentException("工作区版本不是原真实整数版本");
        if(ownerId==null||!ownerId.matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||spaceId==null||!spaceId.matches("[\\p{L}\\p{N}_.:/@-]{1,200}"))
            throw new IllegalArgumentException("来源空间标识无效");
        this.kind=kind;this.sourceId=sourceId;this.revision=revision;this.ownerId=ownerId;this.spaceId=spaceId;workspace=location;
    }

    public static SourceRef fromJson(JSONObject value) {
        if(value==null)throw new IllegalArgumentException("必须提供来源引用");
        String kind=string(value,"kind");WorkspaceLocation location="workspace_file".equals(kind)?WorkspaceLocation.from(value.optJSONObject("workspace")):null;
        return new SourceRef(kind,string(value,"sourceId"),integer(value,"revision",1),string(value,"ownerId"),string(value,"spaceId"),location);
    }
    public JSONObject toJson()throws Exception {
        JSONObject value=new JSONObject().put("kind",kind).put("sourceId",sourceId).put("revision",revision).put("ownerId",ownerId).put("spaceId",spaceId);
        if(workspace!=null)value.put("workspace",workspace.toJson());return value;
    }
    String key(){return new JSONArray().put(ownerId).put(spaceId).put(kind).put(sourceId).toString();}
    boolean belongsTo(MemoryBook.Scope scope){return ownerId.equals(scope.ownerId)&&spaceId.equals(scope.spaceId);}
    static long integer(JSONObject value,String key,long minimum) {
        Object number=value.opt(key);
        if(!(number instanceof Byte||number instanceof Short||number instanceof Integer||number instanceof Long)||((Number)number).longValue()<minimum)
            throw new IllegalArgumentException(key+"必须为不小于"+minimum+"的整数");
        return ((Number)number).longValue();
    }
    private static String string(JSONObject value,String key) {
        Object text=value.opt(key);if(!(text instanceof String))throw new IllegalArgumentException(key+"必须为文字");return (String)text;
    }
    @Override public boolean equals(Object other) {
        if(!(other instanceof SourceRef))return false;SourceRef ref=(SourceRef)other;
        return revision==ref.revision&&kind.equals(ref.kind)&&sourceId.equals(ref.sourceId)&&ownerId.equals(ref.ownerId)&&spaceId.equals(ref.spaceId)&&Objects.equals(workspace,ref.workspace);
    }
    @Override public int hashCode(){return workspace==null?Objects.hash(kind,sourceId,revision,ownerId,spaceId):Objects.hash(kind,sourceId,revision,ownerId,spaceId,workspace);}

    public static final class WorkspaceAddress {
        public final String projectId,path;public final int offset,chars;
        private WorkspaceAddress(String project,String path,int offset,int chars){validateWorkspaceAddress(project,path,offset,chars);projectId=project;this.path=path;this.offset=offset;this.chars=chars;}
    }
    public static final class WorkspaceLocation {
        public final String projectId,path,revisionId,sha256;public final int offset,chars;
        private WorkspaceLocation(String project,String path,String revisionId,String sha,int offset,int chars){
            validateWorkspaceAddress(project,path,offset,chars);
            if(revisionId==null||!revisionId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||sha==null||!sha.matches("[a-f0-9]{64}|absent"))throw new IllegalArgumentException("工作区来源缺真实revisionId或完整字节SHA");
            projectId=project;this.path=path;this.revisionId=revisionId;sha256=sha;this.offset=offset;this.chars=chars;
        }
        private static WorkspaceLocation from(JSONObject value){
            if(value==null||value.length()!=6)throw new IllegalArgumentException("工作区来源定位必须是原闭集字段");
            for(var keys=value.keys();keys.hasNext();)if(!Set.of("projectId","path","revisionId","sha256","offset","chars").contains(keys.next()))throw new IllegalArgumentException("工作区定位不能覆盖受众或任意读取路径");
            long offset=integer(value,"offset",0),chars=integer(value,"chars",1);if(offset>Integer.MAX_VALUE||chars>24000)throw new IllegalArgumentException("工作区原文范围过大");
            return new WorkspaceLocation(string(value,"projectId"),string(value,"path"),string(value,"revisionId"),string(value,"sha256"),(int)offset,(int)chars);
        }
        public JSONObject toJson()throws Exception{return new JSONObject().put("projectId",projectId).put("path",path).put("revisionId",revisionId).put("sha256",sha256).put("offset",offset).put("chars",chars);}
        @Override public boolean equals(Object other){if(!(other instanceof WorkspaceLocation))return false;WorkspaceLocation x=(WorkspaceLocation)other;return offset==x.offset&&chars==x.chars&&projectId.equals(x.projectId)&&path.equals(x.path)&&revisionId.equals(x.revisionId)&&sha256.equals(x.sha256);}
        @Override public int hashCode(){return Objects.hash(projectId,path,revisionId,sha256,offset,chars);}
    }
    /** Native locator factory, not an issuer of owner/member authorization. */
    public static SourceRef workspaceFile(String project,String path,int version,String revisionId,String sha,int offset,int chars,String owner,String space){
        WorkspaceLocation location=new WorkspaceLocation(project,path,revisionId,sha,offset,chars);
        return new SourceRef("workspace_file",workspaceId(project,path,offset,chars),version,owner,space,location);
    }
    public static WorkspaceAddress workspaceAddress(String sourceId){
        if(sourceId==null||sourceId.length()>2048||!sourceId.matches("vws:[A-Za-z0-9_-]+"))throw new IllegalArgumentException("工作区来源地址无效");
        try{
            byte[] bytes=Base64.getUrlDecoder().decode(sourceId.substring(4));String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener tokener=new JSONTokener(text);Object decoded=tokener.nextValue();if(!(decoded instanceof JSONArray)||tokener.nextClean()!=0)throw new IllegalArgumentException("工作区来源地址含额外数据");JSONArray row=(JSONArray)decoded;
            if(row.length()!=4||!(row.get(0) instanceof String)||!(row.get(1) instanceof String))throw new IllegalArgumentException("工作区来源地址不完整");
            JSONObject numbers=new JSONObject().put("offset",row.get(2)).put("chars",row.get(3));long offset=integer(numbers,"offset",0),chars=integer(numbers,"chars",1);
            if(offset>Integer.MAX_VALUE||chars>24000)throw new IllegalArgumentException("工作区原范围过大");WorkspaceAddress address=new WorkspaceAddress(row.getString(0),row.getString(1),(int)offset,(int)chars);
            if(!sourceId.equals(workspaceId(address.projectId,address.path,address.offset,address.chars)))throw new IllegalArgumentException("工作区来源地址不是规范编码");return address;
        }catch(IllegalArgumentException invalid){throw invalid;}catch(Exception invalid){throw new IllegalArgumentException("工作区来源地址不可解码",invalid);}
    }
    public static String workspaceSourceId(String project,String path,int offset,int chars){return workspaceId(project,path,offset,chars);}
    private static String workspaceId(String project,String path,int offset,int chars){
        validateWorkspaceAddress(project,path,offset,chars);String raw=new JSONArray().put(project).put(path).put(offset).put(chars).toString();String id="vws:"+Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        if(id.length()>2048)throw new IllegalArgumentException("工作区来源地址超出支持长度，未截断或替换路径");return id;
    }
    private static void validateWorkspaceAddress(String project,String path,int offset,int chars){
        if(project==null||!project.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||path==null||path.isEmpty()||path.length()>240||path.startsWith("/")||path.contains("\\")||path.matches(".*[\\p{Cntrl}].*")||path.matches("(^|.*/)\\.\\.?(/.*|$)")||offset<0||chars<1||chars>24000)throw new IllegalArgumentException("工作区来源项目、相对路径或UTF-16范围无效");
        for(int i=0;i<path.length();i++){char c=path.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=path.length()||!Character.isLowSurrogate(path.charAt(++i)))throw new IllegalArgumentException("工作区路径含无效Unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("工作区路径含无效Unicode");}
    }

    /** Current evidence state supplied by the authenticated host, including deletion tombstones. */
    public static final class State {
        public final SourceRef ref;
        public final boolean deleted;
        public State(SourceRef ref,boolean deleted){this.ref=Objects.requireNonNull(ref);this.deleted=deleted;}
    }
    /**
     * Resolve from the host's authoritative message/task store, not from the submitted payload.
     * Scope comes from authenticated host identity; return null for missing/inaccessible evidence.
     * Revisions must increase for content changes/restoration, and IDs must not be reused.
     */
    /** Native proof port for literal user_said annotations. Implementations must read the exact
     * original author/revision/span without taking a TaskStore or source-admission monitor. */
    public interface AssertionResolver extends Resolver {void requireUserSaid(JSONObject assertion,String exactRecordText)throws Exception;}
    public interface Resolver {
        State resolve(MemoryBook.Scope scope,String kind,String sourceId)throws Exception;
    }
}
