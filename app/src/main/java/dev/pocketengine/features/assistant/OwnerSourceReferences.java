package dev.pocketengine.features.assistant;

import dev.pocketengine.features.memory.SourceRef;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Typed owner-selected original ranges. Metadata only, never an authority or an evidence body. */
public final class OwnerSourceReferences {
    private OwnerSourceReferences(){}
    public static final int MAX_REFERENCES=4,MAX_RANGE_CHARS=4000,MAX_TOTAL_CHARS=8000;

    /**
     * Canonical defensive copy. The opaque generation is the original DB's active
     * assistant_history_generation value, not a source revision or a permission.
     * Callers must still admit every selection against the current original source.
     */
    public static JSONArray checked(JSONArray supplied)throws Exception {
        if(supplied==null||supplied.length()>MAX_REFERENCES)throw new IllegalArgumentException("每条输入最多4个原文范围引用");
        JSONArray checked=new JSONArray();Set<String> selected=new HashSet<>();long total=0;
        for(int i=0;i<supplied.length();i++){
            JSONObject row=supplied.getJSONObject(i);keys(row,Set.of("generation","sourceConversationId","sourceRef","bodyFingerprint","offset","chars","unit"));
            String generation=text(row,"generation",80),conversation=text(row,"sourceConversationId",2048),fingerprint=text(row,"bodyFingerprint",71);
            JSONObject identity=row.getJSONObject("sourceRef");keys(identity,Set.of("kind","sourceId","revision","ownerId","spaceId"));
            // Do not let JSONObject numeric coercion turn a string, double or hash into a revision.
            integer(identity,"revision",1,Long.MAX_VALUE);SourceRef source=SourceRef.fromJson(identity);
            if(!"message".equals(source.kind)||!"owner".equals(source.ownerId)||!"private".equals(source.spaceId)
                    ||!fingerprint.matches("sha256:[a-f0-9]{64}")||!"UTF-16".equals(row.opt("unit")))
                throw new SecurityException("原文引用不属于当前私人消息来源/精确范围");
            int offset=(int)integer(row,"offset",0,Integer.MAX_VALUE),chars=(int)integer(row,"chars",1,MAX_RANGE_CHARS);
            if((long)offset+chars>Integer.MAX_VALUE||(total+=chars)>MAX_TOTAL_CHARS)throw new IllegalArgumentException("原文引用总范围超过8000 UTF-16字符");
            String key=new JSONArray().put(generation).put(conversation).put(source.sourceId).put(source.revision).put(offset).put(chars).toString();
            if(!selected.add(key))throw new IllegalArgumentException("相同原文范围不能重复加入本条输入");
            checked.put(new JSONObject().put("generation",generation).put("sourceConversationId",conversation).put("sourceRef",source.toJson())
                    .put("bodyFingerprint",fingerprint).put("offset",offset).put("chars",chars).put("unit","UTF-16"));
        }
        return checked;
    }
    private static String text(JSONObject row,String key,int maximum)throws Exception {
        Object raw=row.opt(key);if(!(raw instanceof String))throw new IllegalArgumentException("原文引用字段必须为文字："+key);String value=(String)raw;
        if(value.isEmpty()||value.length()>maximum||value.indexOf('\0')>=0||!StandardCharsets.UTF_8.newEncoder().canEncode(value)
                ||value.codePoints().allMatch(Character::isWhitespace))throw new IllegalArgumentException("原文引用字段无效："+key);return value;
    }
    private static long integer(JSONObject row,String key,long minimum,long maximum){
        Object raw=row.opt(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<minimum||((Number)raw).longValue()>maximum)
            throw new IllegalArgumentException("原文引用必须使用预算内实际整数："+key);return ((Number)raw).longValue();
    }
    private static void keys(JSONObject row,Set<String> allowed){
        if(row==null||row.length()!=allowed.size())throw new SecurityException("原文引用只允许固定元数据字段");
        for(var keys=row.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("原文引用不能附带正文、权限或替代身份");
    }
}
