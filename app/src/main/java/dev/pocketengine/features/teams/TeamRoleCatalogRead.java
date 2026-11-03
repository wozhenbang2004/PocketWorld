package dev.pocketengine.features.teams;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded data projection for the original parent/lead read facade.
 * The host supplies the existing live SQL/source fence and settles its original read reservation.
 * JSON cannot supply a task identity, a fence, capabilities, or permission to modify definitions. */
public final class TeamRoleCatalogRead {
    public static final int MAX_BYTES=16000,MAX_PAGE=8,MAX_TEXT=4000;
    private TeamRoleCatalogRead(){}
    @FunctionalInterface public interface CurrentSource {Snapshot current()throws Exception;}
    @FunctionalInterface public interface ReadFence {void checkCurrent()throws Exception;}
    public static JSONObject toolDefinition()throws Exception {
        JSONObject p=new JSONObject().put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("catalog").put("role").put("template")))
            .put("id",stringSchema(64)).put("hash",stringSchema(64)).put("expectedContentHash",stringSchema(64)).put("expectedSnapshotHash",stringSchema(64));
        for(String key:new String[]{"version","expectedCatalogRevision","after","limit","offset","length"})p.put(key,new JSONObject().put("type","integer").put("minimum",key.equals("version")||key.equals("limit")||key.equals("length")?1:0)
            .put("maximum",key.equals("expectedCatalogRevision")?Long.MAX_VALUE:key.equals("after")?48:key.equals("limit")?MAX_PAGE:key.equals("length")?MAX_TEXT:Integer.MAX_VALUE));
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name","team_role_catalog")
            .put("description","只读原生本地角色/模板数据和能力前提；分页固定revision/hash，不授工具、来源、预算、组队或写配置权。")
            .put("parameters",new JSONObject().put("type","object").put("properties",p).put("required",new JSONArray().put("kind")).put("additionalProperties",false)));
    }
    public static void validate(JSONObject request)throws Exception {
        String kind=text(request,"kind");if(request.has("expectedCatalogRevision"))integer(request,"expectedCatalogRevision",0,Long.MAX_VALUE);
        if(kind.equals("catalog")){
            fields(request,Set.of("kind","expectedCatalogRevision","expectedSnapshotHash","after","limit"));int after=request.has("after")?(int)integer(request,"after",0,48):0;
            if(request.has("expectedSnapshotHash")&&!text(request,"expectedSnapshotHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("目录观测hash无效");
            if(request.has("limit"))integer(request,"limit",1,MAX_PAGE);if(after>0&&(!request.has("expectedCatalogRevision")||!request.has("expectedSnapshotHash")))throw new SecurityException("目录后续页必须固定revision和能力观测hash");
        }else if(kind.equals("role")||kind.equals("template")){
            fields(request,Set.of("kind","expectedCatalogRevision","id","version","hash","offset","length","expectedContentHash"));
            if(!text(request,"id").matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||!text(request,"hash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("角色引用身份无效");integer(request,"version",1,Integer.MAX_VALUE);
            int offset=request.has("offset")?(int)integer(request,"offset",0,Integer.MAX_VALUE):0;if(request.has("length"))integer(request,"length",1,MAX_TEXT);
            if(request.has("expectedContentHash")&&!text(request,"expectedContentHash").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("正文hash无效");
            if(offset>0&&(!request.has("expectedCatalogRevision")||!request.has("expectedContentHash")))throw new SecurityException("正文后续页必须固定revision和正文hash");
        }else throw new IllegalArgumentException("角色读取只支持catalog、role或template");
    }
    public static final class Snapshot {
        private final String document,capabilities,readTools;
        public Snapshot(JSONObject originalDocument,JSONArray nativeCapabilities,JSONArray nativeReadTools){
            document=originalDocument.toString();capabilities=nativeCapabilities.toString();readTools=nativeReadTools.toString();
        }
        private JSONObject document()throws Exception{return new JSONObject(document);}
        private JSONArray capabilities()throws Exception{return new JSONArray(capabilities);}
        private JSONArray readTools()throws Exception{return new JSONArray(readTools);}
        private String fingerprint()throws Exception {
            JSONObject data=document();TeamRoleCatalog.catalogRevision(data);
            return TeamRoleCatalog.dataHash(new JSONObject().put("catalog",data.has(TeamRoleCatalog.KEY)?data.get(TeamRoleCatalog.KEY):JSONObject.NULL)
                .put("capabilities",capabilities()).put("readTools",readTools()));
        }
    }
    public static JSONObject read(CurrentSource source,ReadFence original,JSONObject request)throws Exception {
        if(source==null||original==null)throw new SecurityException("角色读取必须来自原宿主source与原任务fence");
        validate(request);
        original.checkCurrent();Snapshot snapshot=java.util.Objects.requireNonNull(source.current());String fingerprint=snapshot.fingerprint();
        JSONObject document=snapshot.document();long revision=TeamRoleCatalog.catalogRevision(document);
        if(request.has("expectedCatalogRevision")&&integer(request,"expectedCatalogRevision",0,Long.MAX_VALUE)!=revision)
            throw new IllegalStateException("原角色目录已变化，不混合不同版本的页面");
        String kind=text(request,"kind");JSONObject result;
        if(kind.equals("catalog"))result=index(snapshot,request,revision);
        else if(kind.equals("role")||kind.equals("template"))result=detail(snapshot,request,revision,kind);
        else throw new IllegalArgumentException("角色读取只支持catalog、role或template");
        original.checkCurrent();
        if(!fingerprint.equals(java.util.Objects.requireNonNull(source.current()).fingerprint()))throw new SecurityException("原角色、探测或只读能力在读取时已变化");
        original.checkCurrent();return bounded(result.put("queryOnly",true).put("dataOnly",true).put("executionAuthorized",false));
    }
    private static JSONObject index(Snapshot snapshot,JSONObject request,long revision)throws Exception {
        fields(request,Set.of("kind","expectedCatalogRevision","expectedSnapshotHash","after","limit"));
        int after=request.has("after")?(int)integer(request,"after",0,48):0;
        int limit=request.has("limit")?(int)integer(request,"limit",1,MAX_PAGE):MAX_PAGE;
        String snapshotHash=snapshot.fingerprint();
        if(request.has("expectedSnapshotHash")&&!snapshotHash.equals(text(request,"expectedSnapshotHash")))throw new SecurityException("原目录或能力观测已变化，不拼接陈旧目录页面");
        JSONObject document=snapshot.document(),catalog=TeamRoleCatalog.snapshot(document,snapshot.capabilities(),snapshot.readTools());
        JSONArray all=new JSONArray(),roles=catalog.getJSONArray("roles"),templates=catalog.getJSONArray("templates");
        for(int i=0;i<roles.length();i++){
            JSONObject role=roles.getJSONObject(i);all.put(new JSONObject().put("kind","role").put("id",role.getString("id")).put("version",role.getInt("version"))
                .put("hash",role.getString("hash")).put("name",role.getString("name")).put("runner",role.getString("runner"))
                .put("archived",role.getBoolean("archived")).put("availability",role.getJSONObject("availability")));
        }
        for(int i=0;i<templates.length();i++){
            JSONObject template=templates.getJSONObject(i);all.put(new JSONObject().put("kind","template").put("id",template.getString("id"))
                .put("version",template.getInt("version")).put("hash",template.getString("hash")).put("name",template.getString("name"))
                .put("archived",template.getBoolean("archived")).put("availability",TeamRoleCatalog.templateAvailability(document,template,snapshot.capabilities(),snapshot.readTools())));
        }
        if(after>all.length())throw new IllegalArgumentException("目录cursor不属于原快照");
        JSONArray page=new JSONArray();int next=after;
        JSONObject result=new JSONObject().put("kind","catalog").put("catalogRevision",revision).put("catalogSnapshotHash",snapshotHash).put("after",after).put("entries",page)
            .put("entryCount",all.length()).put("next",next).put("complete",next==all.length());
        while(next<all.length()&&page.length()<limit){
            page.put(all.get(next));result.put("next",next+1).put("complete",next+1==all.length());
            // Reserve space for the fixed outer read flags before returning the whole entry.
            if(bytes(result)>MAX_BYTES-160){page.remove(page.length()-1);if(page.length()==0)throw new IllegalStateException("原目录条目超过有界回执范围，未截断其能力前提");break;}
            next++;
        }
        return result.put("next",next).put("complete",next==all.length());
    }
    private static JSONObject detail(Snapshot snapshot,JSONObject request,long revision,String kind)throws Exception {
        fields(request,Set.of("kind","expectedCatalogRevision","id","version","hash","offset","length","expectedContentHash"));
        String id=text(request,"id"),hash=text(request,"hash");int version=(int)integer(request,"version",1,Integer.MAX_VALUE);
        if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("角色或模板需要准确hash");
        int offset=request.has("offset")?(int)integer(request,"offset",0,Integer.MAX_VALUE):0;
        int length=request.has("length")?(int)integer(request,"length",1,MAX_TEXT):MAX_TEXT;
        if(offset>0&&(!request.has("expectedCatalogRevision")||!request.has("expectedContentHash")))throw new SecurityException("正文后续页必须固定目录revision与实际正文hash");
        JSONObject document=snapshot.document(),value;
        if(kind.equals("role")){
            value=TeamRoleCatalog.role(document,id,version,hash);JSONArray roles=TeamRoleCatalog.snapshot(document,snapshot.capabilities(),snapshot.readTools()).getJSONArray("roles");
            for(int i=0;i<roles.length();i++)if(id.equals(roles.getJSONObject(i).getString("id")))value.put("availability",roles.getJSONObject(i).getJSONObject("availability"));
        }else{value=TeamRoleCatalog.template(document,id,version,hash);value.put("availability",TeamRoleCatalog.templateAvailability(document,value,snapshot.capabilities(),snapshot.readTools()));}
        String text=TeamRoleCatalog.dataText(value),contentHash=TeamRoleCatalog.dataHash(value);
        if(request.has("expectedContentHash")&&!contentHash.equals(text(request,"expectedContentHash")))throw new SecurityException("原角色正文或能力前提已变，不拼接陈旧页面");
        if(offset>text.length()||insidePair(text,offset))throw new IllegalArgumentException("正文cursor超过原范围或切开Unicode字符");
        int end=(int)Math.min((long)text.length(),(long)offset+length);if(insidePair(text,end))end--;
        if(end==offset&&offset<text.length())throw new IllegalArgumentException("页面长度不能容纳下一个完整Unicode字符");
        return new JSONObject().put("kind",kind).put("id",id).put("version",version).put("hash",hash).put("catalogRevision",revision)
            .put("contentHash",contentHash).put("offset",offset).put("endOffset",end).put("totalChars",text.length()).put("complete",end==text.length())
            .put("text",text.substring(offset,end)).put("capabilityPreconditionsAreNotGrants",true);
    }
    private static boolean insidePair(String value,int offset){return offset>0&&offset<value.length()&&Character.isHighSurrogate(value.charAt(offset-1))&&Character.isLowSurrogate(value.charAt(offset));}
    private static JSONObject bounded(JSONObject result){if(bytes(result)>MAX_BYTES)throw new IllegalStateException("角色回执超过有界范围，未静默截断");return result;}
    private static int bytes(JSONObject result){return result.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static JSONObject stringSchema(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static String text(JSONObject value,String key)throws Exception{Object v=value.get(key);if(!(v instanceof String))throw new IllegalArgumentException("角色读取文字参数类型无效");return (String)v;}
    private static long integer(JSONObject value,String key,long min,long max)throws Exception {Object v=value.get(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<min||((Number)v).longValue()>max)throw new IllegalArgumentException("角色读取cursor或版本必须为准确整数");return ((Number)v).longValue();}
    private static void fields(JSONObject value,Set<String> allowed){for(var keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("角色数据读取不能提供宿主身份或写入字段");}
}
