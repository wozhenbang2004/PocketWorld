package dev.pocketengine.browser;

import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.json.JSONObject;

/** Bounded RAM knowledge of actual safe-JSON reads. No DOM/action refs, task state or permission is stored here. */
public final class BrowserReadDelta {
    public static final class ResyncRequired extends SecurityException {ResyncRequired(){super("browser_full_resync_required");}}
    private static final int MAX_READS=8,MAX_BYTES=131072;
    private static final long TTL_MS=120000;
    private static final Set<String> FULL_COMPARISONS=Set.of("not_attempted_full_projection","full_resync_missing_base","full_resync_context_not_present","full_resync_incomplete_coverage","full_resync_region_changed","full_resync_expired_base","full_resync_delta_not_smaller","full_resync_missing_base_in_final_context");
    private static final class Known {final JSONObject full;final Set<String> wires;final long at;Known(JSONObject full,Set<String> previous,String wire,long at){this.full=full;wires=new LinkedHashSet<>(previous);wires.add(wire);while(wires.size()>MAX_READS)wires.remove(wires.iterator().next());this.at=at;}}
    private static final class Offered {final JSONObject full,wire;final Known base;Offered(JSONObject full,JSONObject wire,Known base){this.full=full;this.wire=wire;this.base=base;}}
    private final Map<String,Known> known=new LinkedHashMap<>();
    private final Map<String,Offered> offered=new LinkedHashMap<>();
    private final Map<String,Set<String>> requests=new LinkedHashMap<>();
    private Set<String> modelKnows=Set.of();
    void clear(){known.clear();offered.clear();requests.clear();modelKnows=Set.of();}
    private static JSONObject copy(JSONObject v)throws Exception{return new JSONObject(v.toString());}
    private static int bytes(JSONObject v){return v.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static String fingerprint(Object v)throws Exception{return TaskStore.capabilityFingerprint(new JSONObject().put("value",v));}
    private static String wireFingerprint(JSONObject v)throws Exception {
        // Full original safe receipt, including operation/read-authorization/recipe metadata.
        // Matching body fields alone cannot authorize a context repair for another tool row.
        for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","tabId","tabGeneration","observationId","navigation","documentId","frameId","read","text"})v.get(key);
        return TaskStore.capabilityFingerprint(v);
    }
    private static String projectionFingerprint(JSONObject v)throws Exception{return TaskStore.capabilityFingerprint(new JSONObject().put("text",v.getString("text")).put("data",v.getJSONObject("read").getJSONArray("data")));}
    private static boolean sameRegion(JSONObject a,JSONObject b)throws Exception {
        for(String k:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId","frameId"})if(!String.valueOf(a.get(k)).equals(String.valueOf(b.get(k))))return false;
        JSONObject ar=a.getJSONObject("read"),br=b.getJSONObject("read");return ar.getString("mode").equals(br.getString("mode"))&&ar.getJSONObject("facts").getString("regionRef").equals(br.getJSONObject("facts").getString("regionRef"));
    }
    private static boolean sameCoverage(JSONObject a,JSONObject b)throws Exception {
        JSONObject before=a.getJSONObject("read").getJSONObject("facts"),after=b.getJSONObject("read").getJSONObject("facts");
        for(String k:new String[]{"scope","childFramesOmitted","openShadowRootsRead","closedShadowCoverage","nameComputation","textProjection"})if(!String.valueOf(before.get(k)).equals(String.valueOf(after.get(k))))return false;
        return true;
    }
    private static boolean complete(JSONObject v)throws Exception {
        JSONObject r=v.getJSONObject("read"),f=r.getJSONObject("facts");return !r.getString("mode").equals("outline")&&!f.getString("regionRef").isEmpty()&&!f.getBoolean("partial")&&r.isNull("cursor")&&f.getInt("from")==0&&f.getInt("to")==f.getInt("totalCaptured")&&f.getInt("omittedItems")==0&&f.getInt("returnedItems")==f.getInt("totalCaptured");
    }
    /** Called before journal/cleaning. Only the afterTool projection can commit this offered read. */
    JSONObject offer(JSONObject full,JSONObject request,long now)throws Exception {
        JSONObject wire=copy(full),read=wire.getJSONObject("read"),delivery=read.getJSONObject("delivery");String base=request.optString("baseRevision"),comparison="not_attempted_full_projection";Known prior=null;
        if(!base.isEmpty()){
            prior=known.get(base);comparison=prior==null?"full_resync_missing_base":now<prior.at||now-prior.at>TTL_MS?"full_resync_expired_base":!modelKnows.contains(base)?"full_resync_context_not_present":!sameRegion(prior.full,full)?"full_resync_region_changed":!complete(prior.full)||!complete(full)||!sameCoverage(prior.full,full)?"full_resync_incomplete_coverage":"";
            if(comparison.isEmpty()){
                JSONArray before=prior.full.getJSONObject("read").getJSONArray("data"),after=read.getJSONArray("data");int prefix=0,suffix=0;
                while(prefix<before.length()&&prefix<after.length()&&fingerprint(before.get(prefix)).equals(fingerprint(after.get(prefix))))prefix++;
                while(suffix<before.length()-prefix&&suffix<after.length()-prefix&&fingerprint(before.get(before.length()-1-suffix)).equals(fingerprint(after.get(after.length()-1-suffix))))suffix++;
                JSONArray insert=new JSONArray();for(int i=prefix;i<after.length()-suffix;i++)insert.put(after.get(i));
                int remove=before.length()-prefix-suffix;boolean changedText=!prior.full.getString("text").equals(full.getString("text")),unchanged=remove==0&&insert.length()==0&&!changedText;
                JSONObject next=new JSONObject().put("version",2).put("format",unchanged?"unchanged":"delta").put("revision",delivery.getString("revision")).put("baseRevision",base)
                        .put("comparison","same_complete_read_projection_only").put("deletionsInferred",remove>0).put("baseProjectionSha256",projectionFingerprint(prior.full)).put("targetItems",after.length())
                        .put("patch",unchanged?JSONObject.NULL:new JSONObject().put("at",prefix).put("remove",remove).put("insertCount",insert.length()).put("textChanged",changedText));
                read.put("delivery",next).put("data",insert);wire.put("text",changedText?full.getString("text"):"");
                wire.put("message",unchanged?"指定区域与已展示版本的文字和有序数据相同；新覆盖事实见read.facts，不代表业务完成。":"仅交付指定区域相对baseRevision的有序替换片段；按patch拼回后再使用，不是整页删除或业务完成证明。");
                if(bytes(wire)>=bytes(full)){wire=copy(full);comparison="full_resync_delta_not_smaller";prior=null;}
            }
        }
        if(!comparison.isEmpty())wire.getJSONObject("read").getJSONObject("delivery").put("comparison",comparison).put("deletionsInferred",false);
        validateDelivery(wire.getJSONObject("read").getJSONObject("delivery"));offered.put(full.getString("observationId"),new Offered(copy(full),copy(wire),prior));while(offered.size()>MAX_READS)offered.remove(offered.keySet().iterator().next());return wire;
    }
    /** The only knowledge commit: actual original Runner safe JSON, after its receipt and checkpoint. */
    JSONObject delivered(JSONObject actual,long now)throws Exception {
        Offered pending=offered.remove(actual.getString("observationId"));if(pending==null)throw new SecurityException("未准备这次语义交付");
        JSONObject read=actual.getJSONObject("read"),delivery=read.getJSONObject("delivery"),expected=pending.wire.getJSONObject("read");validateDelivery(delivery);
        if(!fingerprint(read.getJSONObject("facts")).equals(fingerprint(expected.getJSONObject("facts")))||!fingerprint(delivery).equals(fingerprint(expected.getJSONObject("delivery")))||!read.getString("projectionSha256").equals(expected.getString("projectionSha256"))||!sameRegion(actual,pending.full))throw new SecurityException("交付范围或版本不是原生已准备投影");
        JSONObject materialized=copy(actual);String format=delivery.getString("format");
        if(format.equals("full")){if(read.getJSONArray("data").length()!=read.getJSONObject("facts").getInt("returnedItems"))throw new SecurityException("完整交付行数不符");}
        else {
            if(pending.base==null||known.get(delivery.getString("baseRevision"))!=pending.base||!projectionFingerprint(pending.base.full).equals(delivery.getString("baseProjectionSha256")))throw new SecurityException("差分原基线已失效");
            JSONArray before=pending.base.full.getJSONObject("read").getJSONArray("data"),insert=read.getJSONArray("data"),data=new JSONArray();String text=pending.base.full.getString("text");
            if(format.equals("unchanged")){if(insert.length()!=0||!actual.getString("text").isEmpty())throw new SecurityException("unchanged不能夹带正文");for(int i=0;i<before.length();i++)data.put(before.get(i));}
            else {JSONObject p=delivery.getJSONObject("patch");int at=p.getInt("at"),remove=p.getInt("remove");if(at>before.length()||at+remove>before.length()||p.getInt("insertCount")!=insert.length())throw new SecurityException("有序差分越过原数组范围");for(int i=0;i<at;i++)data.put(before.get(i));for(int i=0;i<insert.length();i++)data.put(insert.get(i));for(int i=at+remove;i<before.length();i++)data.put(before.get(i));if(p.getBoolean("textChanged"))text=actual.getString("text");else if(!actual.getString("text").isEmpty())throw new SecurityException("未声明正文替换");}
            if(data.length()!=delivery.getInt("targetItems")||data.length()!=read.getJSONObject("facts").getInt("returnedItems")||text.length()>6000)throw new SecurityException("差分未重建完整目标投影");materialized.getJSONObject("read").put("data",data);materialized.put("text",text);
        }
        String revision=delivery.getString("revision");Known same=known.get(revision);Set<String> priorWires=same!=null&&sameRegion(same.full,materialized)&&projectionFingerprint(same.full).equals(projectionFingerprint(materialized))?same.wires:Set.of();known.put(revision,new Known(copy(materialized),priorWires,wireFingerprint(actual),now));trim(now);return materialized;
    }
    private void trim(long now){known.entrySet().removeIf(e->now<e.getValue().at||now-e.getValue().at>TTL_MS);while(known.size()>MAX_READS||known.values().stream().mapToInt(k->bytes(k.full)).sum()>MAX_BYTES)known.remove(known.keySet().iterator().next());}
    /** Only exact real tool receipts in the final projected request count, not user/page strings or summaries. */
    void beforeModelRequest(String id,JSONArray messages)throws Exception {
        Set<String> visible=new LinkedHashSet<>();if(messages.length()<=512)for(int i=0;i<messages.length();i++){
            JSONObject message=messages.optJSONObject(i);if(message==null||!message.optString("role").equals("tool")||!(message.opt("content") instanceof String))continue;String text=message.getString("content");if(text.length()>40000)continue;
            try{JSONTokener parser=new JSONTokener(text);Object value=parser.nextValue();if(!(value instanceof JSONObject)||parser.nextClean()!=0)continue;JSONObject receipt=(JSONObject)value;if(!receipt.optBoolean("semanticRead")||!receipt.optBoolean("ok")||!receipt.optString("status").equals("observed"))continue;
                JSONObject d=receipt.getJSONObject("read").getJSONObject("delivery");String revision=d.getString("revision");Known entry=known.get(revision);if(entry==null||!entry.wires.contains(wireFingerprint(receipt)))continue;if(d.getString("format").equals("full")||visible.contains(d.getString("baseRevision")))visible.add(revision);
            }catch(Exception notExactToolReceipt){/* Projected summaries and clipped receipts are not reconstructable baselines. */}
        }
        requests.put(id,visible);while(requests.size()>2)requests.remove(requests.keySet().iterator().next());
    }
    void afterModelResponse(String id){Set<String> visible=requests.remove(id);modelKnows=visible==null?Set.of():Set.copyOf(visible);}
    void abandoned(String id){requests.remove(id);}
    public record FullResync(String toolCallId,String expectedContentSha256,String fullSafeContent,String originalSafeContent) { public JSONObject original()throws Exception{return new JSONObject(originalSafeContent);} }
    /** Match only actual paired native browser tool receipts; changing user/system/page JSON is impossible here. */
    java.util.List<FullResync> repairs(JSONArray messages,long now)throws Exception {return repairs(messages,now,null);}
    java.util.List<FullResync> repairs(JSONArray messages,long now,String tabId)throws Exception {
        java.util.List<FullResync> out=new java.util.ArrayList<>();walk(messages,(call,content,receipt,base)->{
            if(base!=null||tabId!=null&&!tabId.equals(receipt.optString("tabId")))return;String revision=receipt.getJSONObject("read").getJSONObject("delivery").getString("revision");Known target=known.get(revision);
            if(target==null||now<target.at||now-target.at>TTL_MS||!target.wires.contains(wireFingerprint(receipt))||!sameRegion(target.full,receipt))throw new ResyncRequired();
            JSONObject full=copy(receipt);full.getJSONObject("read").put("data",new JSONArray(target.full.getJSONObject("read").getJSONArray("data").toString()))
                    .put("delivery",new JSONObject().put("format","full").put("revision",revision).put("baseRevision",receipt.getJSONObject("read").getJSONObject("delivery").get("baseRevision")).put("comparison","full_resync_missing_base_in_final_context").put("deletionsInferred",false));
            full.put("text",target.full.getString("text")).put("message","同一次已交付语义观察的完整安全投影：本次模型上下文缺少差分基线，因此改为full；没有重新读取网页、改动原回执或增加授权。");
            if(!complete(full)||bytes(full)>32768)throw new ResyncRequired();validateDelivery(full.getJSONObject("read").getJSONObject("delivery"));target.wires.add(wireFingerprint(full));while(target.wires.size()>MAX_READS)target.wires.remove(target.wires.iterator().next());
            out.add(new FullResync(call,sha256(content),full.toString(),receipt.toString()));
        });return java.util.List.copyOf(out);
    }
    static void validateFinalWire(JSONArray messages)throws Exception {walk(messages,(call,content,receipt,base)->{if(base==null)throw new ResyncRequired();});}
    @FunctionalInterface private interface DeltaCheck {void check(String call,String content,JSONObject receipt,JSONObject actualBase)throws Exception;}
    private static void walk(JSONArray messages,DeltaCheck check)throws Exception {
        try{walkUnsafe(messages,check);}catch(ResyncRequired unavailable){throw unavailable;}catch(Exception invalid){throw new ResyncRequired();}
    }
    private static void walkUnsafe(JSONArray messages,DeltaCheck check)throws Exception {
        if(messages==null||messages.length()>8192)throw new ResyncRequired();Map<String,String> tools=new LinkedHashMap<>();Set<String> duplicate=new LinkedHashSet<>();Map<String,JSONObject> bases=new LinkedHashMap<>();
        for(int i=0;i<messages.length();i++){
            JSONObject message=messages.optJSONObject(i);if(message==null)continue;
            if(message.optString("role").equals("assistant")){JSONArray calls=message.optJSONArray("tool_calls");for(int n=0;calls!=null&&n<calls.length();n++){JSONObject call=calls.getJSONObject(n);String id=call.optString("id"),name=call.getJSONObject("function").optString("name");if(tools.putIfAbsent(id,name)!=null)duplicate.add(id);}continue;}
            String id=message.optString("tool_call_id");if(!message.optString("role").equals("tool")||!"browser_task_observe".equals(tools.get(id)))continue;
            if(duplicate.contains(id)||id.isEmpty()||id.length()>200||!(message.opt("content") instanceof String))throw new ResyncRequired();String content=message.getString("content");if(content.getBytes(StandardCharsets.UTF_8).length>40000)throw new ResyncRequired();JSONObject receipt;try{JSONTokener parser=new JSONTokener(content);Object value=parser.nextValue();if(!(value instanceof JSONObject)||parser.nextClean()!=0)continue;receipt=(JSONObject)value;}catch(Exception notReceipt){continue;}
            if(!receipt.optBoolean("semanticRead")||!receipt.optBoolean("ok")||!receipt.optString("status").equals("observed"))continue;
            JSONObject delivery=receipt.getJSONObject("read").getJSONObject("delivery");validateDelivery(delivery);String key=scopeKey(receipt,delivery.getString("revision"));
            if(delivery.getString("format").equals("full")){if(receipt.getJSONObject("read").getJSONArray("data").length()!=receipt.getJSONObject("read").getJSONObject("facts").getInt("returnedItems"))throw new ResyncRequired();bases.put(key,copy(receipt));continue;}
            JSONObject base=bases.get(scopeKey(receipt,delivery.getString("baseRevision"))),actual=null;
            if(base!=null&&complete(base)&&complete(receipt)&&sameCoverage(base,receipt)&&projectionFingerprint(base).equals(delivery.getString("baseProjectionSha256")))actual=applyWire(base,receipt);
            check.check(id,content,receipt,actual==null?null:base);if(actual!=null)bases.put(key,actual);
        }
    }
    private static String scopeKey(JSONObject v,String revision)throws Exception {JSONObject scope=new JSONObject().put("revision",revision);for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","profileIsolation","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","documentId","frameId"})scope.put(key,v.get(key));scope.put("mode",v.getJSONObject("read").getString("mode")).put("regionRef",v.getJSONObject("read").getJSONObject("facts").getString("regionRef"));return TaskStore.capabilityFingerprint(scope);}
    private static JSONObject applyWire(JSONObject base,JSONObject receipt)throws Exception {
        JSONObject out=copy(receipt),r=out.getJSONObject("read"),d=r.getJSONObject("delivery");JSONArray before=base.getJSONObject("read").getJSONArray("data"),insert=r.getJSONArray("data"),data=new JSONArray();String text=base.getString("text");
        if(d.getString("format").equals("unchanged")){if(insert.length()!=0||!receipt.getString("text").isEmpty())throw new ResyncRequired();for(int i=0;i<before.length();i++)data.put(before.get(i));}
        else{JSONObject p=d.getJSONObject("patch");int at=p.getInt("at"),remove=p.getInt("remove");if(at>before.length()||at+remove>before.length()||p.getInt("insertCount")!=insert.length())throw new ResyncRequired();for(int i=0;i<at;i++)data.put(before.get(i));for(int i=0;i<insert.length();i++)data.put(insert.get(i));for(int i=at+remove;i<before.length();i++)data.put(before.get(i));if(p.getBoolean("textChanged"))text=receipt.getString("text");else if(!receipt.getString("text").isEmpty())throw new ResyncRequired();}
        if(data.length()!=d.getInt("targetItems")||data.length()!=r.getJSONObject("facts").getInt("returnedItems")||text.length()>6000)throw new ResyncRequired();r.put("data",data);out.put("text",text);return out;
    }
    private static String sha256(String value)throws Exception {byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    static void validateDelivery(JSONObject d)throws Exception {
        String format=d.getString("format");if(!d.getString("revision").matches("[a-f0-9]{64}")||!d.isNull("baseRevision")&&!d.getString("baseRevision").matches("[a-f0-9]{64}"))throw new SecurityException("读取版本不完整");
        if(format.equals("full")){if(d.length()!=5||!FULL_COMPARISONS.contains(d.getString("comparison"))||!Boolean.FALSE.equals(d.get("deletionsInferred")))throw new SecurityException("完整投影声明不符");return;}
        if(!Set.of("delta","unchanged").contains(format)||d.length()!=9||d.getInt("version")!=2||d.isNull("baseRevision")||!d.getString("comparison").equals("same_complete_read_projection_only")||!d.getString("baseProjectionSha256").matches("[a-f0-9]{64}")||!(d.get("deletionsInferred") instanceof Boolean)||d.getInt("targetItems")<0||d.getInt("targetItems")>200)throw new SecurityException("有界差分合同无效");
        if(format.equals("unchanged")){if(!d.isNull("patch")||d.getBoolean("deletionsInferred"))throw new SecurityException("unchanged不可推断删除");}
        else {JSONObject p=d.getJSONObject("patch");if(p.length()!=4||!(p.get("textChanged") instanceof Boolean))throw new SecurityException("差分补丁字段不完整");for(String k:new String[]{"at","remove","insertCount"}){Object n=p.get(k);if(!(n instanceof Number)||((Number)n).doubleValue()!=p.getInt(k)||p.getInt(k)<0||p.getInt(k)>200)throw new SecurityException("差分位置无效");}if(d.getBoolean("deletionsInferred")!=(p.getInt("remove")>0))throw new SecurityException("差分删除标记不符");}
    }
}
