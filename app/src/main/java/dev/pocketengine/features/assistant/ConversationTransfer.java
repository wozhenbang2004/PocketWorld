package dev.pocketengine.features.assistant;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Content-only interchange. This schema has no task, control, endpoint, source grant or ACK fields. */
final class ConversationTransfer {
    static final int MAX_BYTES=8*1024*1024,MAX_MESSAGES=5000;
    private ConversationTransfer(){}
    static String sha(byte[] bytes)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte value:hash)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    static byte[] utf8(String text)throws Exception{
        if(text==null||text.length()>MAX_BYTES||!StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new IllegalArgumentException("会话文字不是预算内完整Unicode");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("会话导出超过8MiB预算");return bytes;
    }
    static JSONObject checked(byte[] bytes)throws Exception{
        if(bytes==null||bytes.length>MAX_BYTES)throw new IllegalArgumentException("会话导入超过8MiB预算");
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        int depth=0;boolean quoted=false,escaped=false;for(int i=0;i<json.length();i++){char c=json.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>16)throw new IllegalArgumentException("会话导入结构嵌套超出预算");}else if(c=='}'||c==']')depth--;}
        JSONObject payload=new JSONObject(json);ConversationOrganization.fields(payload,"schema","schemaVersion","exportId","sourceConversationId","sourceGeneration","historyWatermark","exportedAt","title","messages","omitted","attachmentReferences");
        if(!"pocketworld_conversation_export".equals(payload.getString("schema"))||ConversationOrganization.integer(payload,"schemaVersion",1,1)!=1)throw new IllegalArgumentException("不支持的会话导出schema");
        for(String key:Set.of("exportId","sourceConversationId","sourceGeneration"))if(!(payload.opt(key) instanceof String)||payload.getString(key).isEmpty()||payload.getString(key).length()>240)throw new IllegalArgumentException("会话导出身份无效");
        long upper=ConversationOrganization.integer(payload,"historyWatermark",0,Long.MAX_VALUE-1);ConversationOrganization.integer(payload,"exportedAt",0,Long.MAX_VALUE-1);
        if(!(payload.opt("title") instanceof String)||payload.getString("title").length()>120)throw new IllegalArgumentException("会话标题超出预算");
        JSONArray messages=payload.getJSONArray("messages"),omitted=payload.getJSONArray("omitted");if(messages.length()>MAX_MESSAGES||omitted.length()>MAX_MESSAGES*2)throw new IllegalArgumentException("会话清单条数超限");
        Set<String> ids=new HashSet<>();long prior=0;
        for(int i=0;i<messages.length();i++){
            JSONObject row=messages.getJSONObject(i);ConversationOrganization.fields(row,"sourceRecordId","sourceRecordVersion","sequence","role","content","contentSha256","sourceKind","at");
            String id=row.getString("sourceRecordId");if(id.isEmpty()||id.length()>240||!ids.add(id))throw new IllegalArgumentException("会话清单来源ID重复或无效");
            ConversationOrganization.integer(row,"sourceRecordVersion",1,Long.MAX_VALUE-1);long sequence=ConversationOrganization.integer(row,"sequence",1,upper);if(sequence<=prior)throw new IllegalArgumentException("会话清单顺序重复或颠倒");prior=sequence;
            if(row.has("at"))ConversationOrganization.integer(row,"at",0,Long.MAX_VALUE-1);
            if(!Set.of("user","assistant").contains(row.getString("role"))||!Set.of("native_original","imported_history").contains(row.getString("sourceKind")))throw new IllegalArgumentException("会话导入角色或来源类型不支持");
            Object raw=row.opt("content");if(!(raw instanceof String)||utf8((String)raw).length>1_048_576)throw new IllegalArgumentException("单条原文超过1MiB预算");
            if(!sha(utf8((String)raw)).equals(row.getString("contentSha256")))throw new SecurityException("原文SHA与清单不一致");
        }
        for(int i=0;i<omitted.length();i++){JSONObject row=omitted.getJSONObject(i);ConversationOrganization.fields(row,"sourceRecordId","reason");if(!(row.opt("sourceRecordId") instanceof String)||row.getString("sourceRecordId").length()>240||!(row.opt("reason") instanceof String)||row.getString("reason").length()>120)throw new IllegalArgumentException("省略清单无效");}
        if(payload.has("attachmentReferences")){JSONArray refs=payload.getJSONArray("attachmentReferences");if(refs.length()>MAX_MESSAGES*4)throw new IllegalArgumentException("附件引用清单超限");for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);ConversationOrganization.fields(ref,"messageId","attachmentId","sha256","availability");for(String key:Set.of("messageId","attachmentId"))if(!(ref.opt(key) instanceof String)||ref.getString(key).isEmpty()||ref.getString(key).length()>240)throw new IllegalArgumentException("附件引用身份无效");if(!ref.getString("sha256").matches("[a-f0-9]{64}")||!"reference_only_unverified_not_restored".equals(ref.getString("availability")))throw new IllegalArgumentException("附件引用不能冒充已获准原件");}}
        return payload;
    }
    static JSONObject render(JSONObject payload,String format)throws Exception{
        if(!Set.of("json","txt","markdown").contains(format))throw new IllegalArgumentException("请选择json/txt/markdown导出格式");
        byte[] json=utf8(payload.toString());checked(json);String content;
        if(format.equals("json"))content=new String(json,StandardCharsets.UTF_8);
        else{
            StringBuilder text=new StringBuilder();text.append(format.equals("markdown")?"# ":"").append(payload.getString("title")).append('\n').append("Export: ").append(payload.getString("exportId")).append('\n').append("Source: ").append(payload.getString("sourceConversationId")).append('\n').append('\n');
            JSONArray messages=payload.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);text.append(format.equals("markdown")?"## ":"").append(row.getString("role")).append(" [").append(row.getString("sourceRecordId")).append('@').append(row.getLong("sourceRecordVersion")).append("]\n").append(row.getString("content")).append("\n\n");}content=text.toString();
        }
        byte[] bytes=utf8(content);JSONArray manifest=new JSONArray();for(int i=0;i<payload.getJSONArray("messages").length();i++){JSONObject row=new JSONObject(payload.getJSONArray("messages").getJSONObject(i).toString());row.remove("content");manifest.put(row);}
        return new JSONObject().put("format",format).put("content",content).put("utf8Bytes",bytes.length).put("sha256",sha(bytes)).put("exportId",payload.getString("exportId"))
                .put("sourceConversationId",payload.getString("sourceConversationId")).put("historyWatermark",payload.getLong("historyWatermark")).put("includedMessages",manifest).put("omitted",payload.getJSONArray("omitted")).put("attachmentReferences",payload.has("attachmentReferences")?payload.getJSONArray("attachmentReferences"):new JSONArray())
                .put("snapshotSemantics","one_original_database_transaction_no_new_tail").put("executionAuthorized",false).put("deliveryStatus","bytes_prepared_not_saved_or_shared");
    }
}
