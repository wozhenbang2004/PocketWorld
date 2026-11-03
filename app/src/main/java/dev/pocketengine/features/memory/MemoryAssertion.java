package dev.pocketengine.features.memory;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional human semantics on the original Book row. This is a rebuildable projection,
 * never a source registry, identity matcher, confidence accumulator or authority to execute. */
public final class MemoryAssertion {
    public static final int VERSION=1;
    private static final Set<String> FIELDS=Set.of("version","kind","subjectId","subjectType","subjectLabel","predicate","object","objectId","objectType","condition","eventTime","assertedAt","validFrom","validTo","reviewAfter","evidenceSpans");
    private MemoryAssertion(){}
    public static JSONObject checked(JSONObject input)throws Exception {
        if(input==null)throw new IllegalArgumentException("人本记忆字段必须是对象");
        for(var keys=input.keys();keys.hasNext();)if(!FIELDS.contains(keys.next()))throw new IllegalArgumentException("陈述不能设置来源真实性、权限或模型自证字段");
        if(SourceRef.integer(input,"version",1)!=VERSION)throw new IllegalArgumentException("不支持的人本记忆版本");
        JSONObject out=new JSONObject().put("version",VERSION);
        String kind=text(input,"kind",1,20),type=text(input,"subjectType",1,16),id=text(input,"subjectId",1,160);
        if(!Set.of("user_said","inferred","told","imagined").contains(kind)||!Set.of("owner","person","entity").contains(type)||!id.matches("[\\p{L}\\p{N}_.:/@-]{1,160}"))throw new IllegalArgumentException("陈述类型或稳定主体身份无效");
        // Names are labels only. Two people named alike must keep distinct native/local IDs.
        if("owner".equals(type)!= "owner".equals(id))throw new IllegalArgumentException("主人主体只能使用明确的owner身份，不能按姓名推断");
        out.put("kind",kind).put("subjectId",id).put("subjectType",type).put("subjectLabel",text(input,"subjectLabel",1,160))
            .put("predicate",text(input,"predicate",1,160)).put("object",text(input,"object",1,4000)).put("condition",input.has("condition")?text(input,"condition",0,1000):"");
        if(input.has("objectId")&&!input.isNull("objectId")){
            String target=text(input,"objectId",1,160),targetType=text(input,"objectType",1,16);if(!target.matches("[\\p{L}\\p{N}_.:/@-]{1,160}")||!Set.of("owner","person","entity").contains(targetType)||"owner".equals(targetType)!= "owner".equals(target))throw new IllegalArgumentException("关系目标须为明确稳定身份，不能按显示名合并");out.put("objectId",target).put("objectType",targetType);
        }else if(input.has("objectType")&&!input.isNull("objectType"))throw new IllegalArgumentException("关系目标类型缺少稳定身份");
        for(String key:List.of("eventTime","assertedAt","validFrom","validTo","reviewAfter"))out.put(key,input.has(key)&&input.opt(key)!=JSONObject.NULL?SourceRef.integer(input,key,0):JSONObject.NULL);
        if(!out.isNull("validFrom")&&!out.isNull("validTo")&&out.getLong("validTo")<=out.getLong("validFrom"))throw new IllegalArgumentException("事实有效期终点必须晚于起点");
        JSONArray spans=input.has("evidenceSpans")?input.getJSONArray("evidenceSpans"):new JSONArray(),checked=new JSONArray();
        if(spans.length()>20)throw new IllegalArgumentException("一次最多20个精确原证据范围");
        Set<String> unique=new HashSet<>();
        for(int i=0;i<spans.length();i++){
            JSONObject span=spans.getJSONObject(i);if(span.length()!=4)throw new IllegalArgumentException("证据范围只接受原sourceRef、UTF-16起止和hash");
            for(var keys=span.keys();keys.hasNext();)if(!Set.of("sourceRef","offset","chars","sha256").contains(keys.next()))throw new IllegalArgumentException("证据范围不能自报已读或原话证明");
            SourceRef ref=SourceRef.fromJson(span.getJSONObject("sourceRef"));long offset=SourceRef.integer(span,"offset",0),chars=SourceRef.integer(span,"chars",1);String sha=text(span,"sha256",64,64);
            if(offset>Integer.MAX_VALUE||chars>24000||!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("原证据UTF-16范围或hash无效");
            JSONObject value=new JSONObject().put("sourceRef",ref.toJson()).put("offset",offset).put("chars",chars).put("sha256",sha);
            if(!unique.add(value.toString()))throw new IllegalArgumentException("证据范围重复，不能增加独立证据数");checked.put(value);
        }
        return out.put("evidenceSpans",checked);
    }
    static void requireEvidence(JSONObject assertion,JSONArray refs)throws Exception {
        if(assertion==null)return;Set<SourceRef> allowed=new HashSet<>();for(int i=0;i<refs.length();i++)allowed.add(SourceRef.fromJson(refs.getJSONObject(i)));
        JSONArray spans=assertion.getJSONArray("evidenceSpans");for(int i=0;i<spans.length();i++)if(!allowed.contains(SourceRef.fromJson(spans.getJSONObject(i).getJSONObject("sourceRef"))))throw new SecurityException("人本证据范围必须属于实际登记的同版本原来源");
    }
    static String key(JSONObject row)throws Exception {
        JSONObject a=row.getJSONObject("assertion");return new JSONArray().put(row.getString("ownerId")).put(row.getString("spaceId")).put(a.getString("subjectId")).put(a.getString("predicate")).put(a.getString("condition")).toString();
    }
    static long eventOrder(JSONObject row)throws Exception {
        if(row.has("assertionCorrectionAt"))return row.getLong("assertionCorrectionAt");
        JSONObject a=row.getJSONObject("assertion");for(String key:List.of("validFrom","eventTime","assertedAt"))if(!a.isNull(key))return a.getLong(key);return Long.MIN_VALUE;
    }
    /** Do not use insertion/updatedAt as event time: a late historical import cannot win today. */
    static Map<String,String> statuses(Collection<JSONObject> rows,Set<String> eligible,long now)throws Exception {return statuses(rows,eligible,now,Map.of());}
    static Map<String,String> statuses(Collection<JSONObject> rows,Set<String> eligible,long now,Map<String,JSONObject> fences)throws Exception {
        Map<String,String> result=new LinkedHashMap<>();Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
        for(JSONObject row:rows){if(!row.has("assertion"))continue;JSONObject a=row.getJSONObject("assertion");String id=row.getString("id"),status;
            if(!eligible.contains(id))status="source_or_record_inactive";
            else if(row.has("assertionResolution")&&"replace".equals(row.getJSONObject("assertionResolution").optString("decision"))&&!id.equals(row.getJSONObject("assertionResolution").optString("selectedId")))status="historical";
            else if("imagined".equals(a.getString("kind")))status="imagined_not_real_world_fact";
            else if(!a.isNull("validFrom")&&a.getLong("validFrom")>now)status="not_yet_valid";
            else if(!a.isNull("validTo")&&a.getLong("validTo")<=now)status="historical";
            else if(fences.containsKey(keyDigest(row))&&eventOrder(row)<fenceOrder(fences.get(keyDigest(row)),now))status="historical";
            else {groups.computeIfAbsent(key(row),unused->new ArrayList<>()).add(row);continue;}result.put(id,status);
        }
        for(List<JSONObject> group:groups.values()){
            long newest=Long.MIN_VALUE;for(JSONObject row:group)newest=Math.max(newest,eventOrder(row));
            List<JSONObject> latest=new ArrayList<>();for(JSONObject row:group)if(eventOrder(row)==newest)latest.add(row);else result.put(row.getString("id"),"historical");
            Set<String> objects=new HashSet<>();for(JSONObject row:latest)objects.add(row.getJSONObject("assertion").getString("object"));
            JSONObject review=exactResolution(latest);
            for(JSONObject row:latest){String id=row.getString("id");result.put(id,objects.size()<2?"current":review==null?"pending_conflict":"coexist".equals(review.getString("decision"))||id.equals(review.optString("selectedId"))?"current":"historical");}
        }
        return result;
    }
    static String keyDigest(JSONObject row)throws Exception{return TaskMemoryUse.digest(key(row));}
    static JSONObject mergeFence(JSONObject old,JSONObject row)throws Exception {
        JSONObject a=row.getJSONObject("assertion");long effective=a.isNull("validFrom")?0:a.getLong("validFrom"),order=eventOrder(row);TreeMap<Long,Long> timeline=new TreeMap<>();
        if(old!=null){JSONArray versions=old.getJSONArray("versions");for(int i=0;i<versions.length();i++){JSONObject v=versions.getJSONObject(i);timeline.put(v.getLong("effectiveAt"),v.getLong("eventOrder"));}}
        timeline.merge(effective,order,Math::max);JSONArray kept=new JSONArray();long floor=Long.MIN_VALUE;boolean first=true;
        for(var entry:timeline.entrySet())if(first||entry.getValue()>floor){kept.put(new JSONObject().put("effectiveAt",entry.getKey()).put("eventOrder",entry.getValue()));floor=entry.getValue();first=false;}
        if(kept.length()>32)throw new IllegalStateException("同一事实未来有效时点超过32个，未丢弃旧当前性屏障");
        return new JSONObject().put("keyDigest",keyDigest(row)).put("ownerId",row.getString("ownerId")).put("spaceId",row.getString("spaceId")).put("versions",kept);
    }
    private static long fenceOrder(JSONObject fence,long now)throws Exception{long order=Long.MIN_VALUE;JSONArray rows=fence.getJSONArray("versions");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getLong("effectiveAt")<=now)order=Math.max(order,row.getLong("eventOrder"));}return order;}
    public static void checkedFence(JSONObject value)throws Exception {
        if(value.length()!=4||!value.optString("keyDigest").matches("[a-f0-9]{64}"))throw new IllegalStateException("事实当前性屏障无效");MemoryBook.Scope.stored(value);JSONArray versions=value.getJSONArray("versions");if(versions.length()<1||versions.length()>32)throw new IllegalStateException("事实时序屏障数量无效");long previous=-1;for(int i=0;i<versions.length();i++){JSONObject row=versions.getJSONObject(i);Object order=row.opt("eventOrder");long effective=SourceRef.integer(row,"effectiveAt",0);if(row.length()!=2||effective<=previous||!(order instanceof Integer||order instanceof Long))throw new IllegalStateException("事实屏障不能猜测事件时间");previous=effective;}
    }
    private static JSONObject exactResolution(List<JSONObject> group)throws Exception {
        if(group.isEmpty())return null;JSONObject resolution=group.get(0).optJSONObject("assertionResolution");if(resolution==null)return null;
        JSONArray refs=resolution.getJSONArray("records");if(refs.length()!=group.size())return null;Map<String,Long> expected=new HashMap<>();for(JSONObject row:group){if(!resolution.toString().equals(row.optJSONObject("assertionResolution")==null?"":row.getJSONObject("assertionResolution").toString()))return null;expected.put(row.getString("id"),row.getLong("revision"));}
        for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(!Objects.equals(expected.remove(ref.getString("id")),ref.getLong("revision")))return null;}return expected.isEmpty()?resolution:null;
    }
    static void checkStored(JSONObject row,long version)throws Exception {
        if(!row.has("assertion")){if(row.has("assertionCorrectionAt")||row.has("assertionResolution"))throw new IllegalStateException("人本修订缺少原陈述");return;}
        if(version<9)throw new IllegalStateException("人本记忆缺少安全schema版本，未忽略其时间/冲突限制");row.put("assertion",checked(row.getJSONObject("assertion")));
        requireEvidence(row.getJSONObject("assertion"),row.getJSONArray("sourceRefs"));
        if(row.has("assertionCorrectionAt"))SourceRef.integer(row,"assertionCorrectionAt",0);
        if(row.has("assertionResolution")){
            JSONObject r=row.getJSONObject("assertionResolution");if(r.length()!=5||!Set.of("replace","coexist").contains(r.optString("decision")))throw new IllegalStateException("冲突审阅结果无效");text(r,"reason",1,1000);SourceRef.integer(r,"at",0);String selected=r.getString("selectedId");if(!selected.isEmpty()&&!selected.matches("[0-9a-f-]{36}"))throw new IllegalStateException("冲突保留身份无效");JSONArray refs=r.getJSONArray("records");if(refs.length()<2||refs.length()>20)throw new IllegalStateException("冲突审阅范围无效");Set<String> ids=new HashSet<>();for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);if(ref.length()!=2||!ref.getString("id").matches("[0-9a-f-]{36}")||!ids.add(ref.getString("id")))throw new IllegalStateException("冲突审阅版本重复或无效");SourceRef.integer(ref,"revision",1);}
            if(!ids.contains(row.getString("id"))||"replace".equals(r.getString("decision"))&&!ids.contains(selected)||"coexist".equals(r.getString("decision"))&&!selected.isEmpty())throw new IllegalStateException("原生冲突决策缺少所示记录或保留身份");
        }
    }
    private static String text(JSONObject object,String key,int min,int max){Object value=object.opt(key);if(!(value instanceof String)||((String)value).length()<min||((String)value).length()>max)throw new IllegalArgumentException(key+"文字长度无效");return (String)value;}
}
