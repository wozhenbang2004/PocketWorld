package dev.pocketengine.runtime.context;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free structure of the exact selected request, using its existing safety estimate. */
public final class ContextBreakdown {
    private static final Map<String,String> LABELS=Map.of("system","系统提示词","tools","工具定义","reference","记忆与能力参考",
            "summary","历史摘要","history","对话消息","current_input","当前输入","tool_results","工具结果",
            "attachments","图片与视觉输入","overhead","协议与估算预留");
    private ContextBreakdown() { }
    static JSONObject from(JSONArray messages,JSONArray tools,ContextWindow.Budget budget,ContextWindow.Plan plan,
                           Map<JSONObject,String> origins)throws Exception {
        Map<String,Part> parts=new LinkedHashMap<>();
        for(String[] definition:new String[][]{{"system","系统提示词"},{"tools","工具定义"},{"reference","记忆与能力参考"},
                {"summary","历史摘要"},{"history","对话消息"},{"current_input","当前输入"},{"tool_results","工具结果"},
                {"attachments","图片与视觉输入"},{"overhead","协议与估算预留"}})parts.put(definition[0],new Part(definition[1]));
        Set<Integer> removed=new java.util.HashSet<>();for(ContextWindow.Removed row:plan.removed)removed.add(row.index());
        int count=0,images=0;
        for(int i=0;i<messages.length();i++){
            if(removed.contains(i))continue;count++;JSONObject row=messages.getJSONObject(i);
            String category=origins.get(row),role=row.optString("role");
            if(category==null)category=Set.of("system","developer").contains(role)?"system":"tool".equals(role)?"tool_results":"history";
            Part selected=parts.get(category);if(selected==null)selected=parts.get("history");
            long bytes=utf8(row.toString()),imageBytes=0;int imageCount=0;JSONArray content=row.optJSONArray("content");
            for(int c=0;content!=null&&c<content.length();c++){
                JSONObject item=content.optJSONObject(c),image=item==null?null:item.optJSONObject("image_url");
                if(item!=null&&"image_url".equals(item.optString("type"))&&image!=null&&image.opt("url") instanceof String){
                    imageCount++;imageBytes+=utf8(JSONObject.quote(image.getString("url")))-2;
                }
            }
            selected.bytes+=bytes-imageBytes;selected.tokens+=bytes-imageBytes;selected.messages++;
            Part media=parts.get("attachments");media.bytes+=imageBytes;media.images+=imageCount;
            media.tokens=Math.addExact(media.tokens,Math.multiplyExact(imageCount,budget.tokensPerImage));images+=imageCount;
        }
        Part schemas=parts.get("tools");schemas.bytes=utf8(tools==null?"[]":tools.toString());schemas.tokens=schemas.bytes;
        Part overhead=parts.get("overhead");overhead.bytes=utf8("{\"messages\":[],\"tools\":}")+Math.max(0,count-1);
        overhead.tokens=Math.addExact(overhead.bytes,budget.requestOverheadTokens);
        boolean known=budget.tokenWindowKnown&&(images==0||budget.tokensPerImage>0);long local=0;JSONArray components=new JSONArray();
        for(var entry:parts.entrySet()){
            Part part=entry.getValue();local=Math.addExact(local,part.tokens);
            components.put(new JSONObject().put("id",entry.getKey()).put("label",part.label).put("estimatedTokens",known?part.tokens:JSONObject.NULL)
                    .put("bytes",part.bytes).put("messages",part.messages).put("images",part.images));
        }
        JSONObject diagnostic=plan.diagnostics();boolean anchored=diagnostic.optBoolean("providerUsageUsed");
        return new JSONObject().put("format","pocketworld.context-breakdown.v1").put("source","local_estimate").put("tokenizerExact",false)
                .put("method","serialized_utf8_byte_charge_plus_host_image_reserve").put("complete",true).put("additive",!anchored)
                .put("estimatedInputTokens",known?plan.after.estimatedInputTokens():JSONObject.NULL)
                .put("localEstimatedInputTokens",known?local:JSONObject.NULL).put("components",components)
                .put("providerReportedInputTokens",diagnostic.opt("providerReportedInputTokens"))
                .put("providerAdjustmentTokens",known&&anchored?Math.subtractExact(plan.after.estimatedInputTokens(),local):JSONObject.NULL)
                .put("classification","native_message_structure_and_request_provenance")
                .put("referenceMeaning","memory_preferences_capability_time_and_recovery_reference_data")
                .put("attachmentMeaning","image_reserve_only_extracted_document_text_stays_in_its_message_category")
                .put("cacheReducesContext",false).put("bodyIncluded",false);
    }
    /** Keep summary snapshots bounded and free of model/owner text, even for old malformed rows. */
    public static JSONObject publicView(JSONObject original)throws Exception {
        if(original==null||!"pocketworld.context-breakdown.v1".equals(original.optString("format"))
                ||!"local_estimate".equals(original.optString("source"))||!Boolean.FALSE.equals(original.opt("tokenizerExact")))return null;
        JSONArray source=original.optJSONArray("components");if(source==null||source.length()>LABELS.size())return null;
        JSONArray components=new JSONArray();Set<String> seen=new java.util.HashSet<>();
        for(int i=0;i<source.length();i++){
            JSONObject row=source.optJSONObject(i);String id=row==null?"":row.optString("id");
            if(!LABELS.containsKey(id)||!seen.add(id))return null;
            JSONObject item=new JSONObject().put("id",id).put("label",LABELS.get(id));
            for(String key:new String[]{"estimatedTokens","bytes","messages","images"})copyNumber(row,item,key,false);
            components.put(item);
        }
        JSONObject view=new JSONObject().put("format","pocketworld.context-breakdown.v1").put("source","local_estimate").put("tokenizerExact",false)
                .put("components",components).put("bodyIncluded",false).put("cacheReducesContext",false);
        for(String key:new String[]{"estimatedInputTokens","localEstimatedInputTokens","providerReportedInputTokens"})copyNumber(original,view,key,false);
        copyNumber(original,view,"providerAdjustmentTokens",true);
        for(String key:new String[]{"complete","additive"})if(original.opt(key) instanceof Boolean)view.put(key,original.get(key));
        view.put("method","serialized_utf8_byte_charge_plus_host_image_reserve")
                .put("referenceMeaning","memory_preferences_capability_time_and_recovery_reference_data")
                .put("attachmentMeaning","image_reserve_only_extracted_document_text_stays_in_its_message_category");
        return view;
    }
    private static void copyNumber(JSONObject source,JSONObject target,String key,boolean signed)throws Exception{
        Object value=source.opt(key);if(value==JSONObject.NULL){target.put(key,JSONObject.NULL);return;}
        if((value instanceof Integer||value instanceof Long)&&(signed||((Number)value).longValue()>=0))target.put(key,value);
    }
    private static long utf8(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static final class Part {final String label;long tokens,bytes;int messages,images;Part(String label){this.label=label;}}
}
