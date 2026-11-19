package dev.pocketengine.features.assistant;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original DB, current private conversation only. No body, header, export or model input. */
final class ConversationReferenceShelf {
    private static final int MAX=24, CAP=96*1024;
    private ConversationReferenceShelf(){}
    private static String prefix(String gen){return "assistant.private-reference-shelf.v1."+gen.length()+":"+gen+":";}
    private static String key(String gen,String conversation){return prefix(gen)+conversation;}
    static void clear(SQLiteDatabase db,String gen,String conversation){db.delete("meta","key=?",new String[]{key(gen,conversation)});}
    static void clearGeneration(SQLiteDatabase db,String gen){String p=prefix(gen);db.delete("meta","substr(key,1,?)=?",new String[]{Integer.toString(p.length()),p});}
    private static JSONArray entries(SQLiteDatabase db,String gen,String conversation)throws Exception{
        try(Cursor c=db.rawQuery("SELECT 1 FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0 AND archived_at=0 LIMIT 1",new String[]{gen,conversation})){
            if(!c.moveToFirst())throw new SecurityException("请回到原本机对话，未读取其他对话的备忘");
        }
        try(Cursor c=db.rawQuery("SELECT length(CAST(value AS BLOB)),CASE WHEN length(CAST(value AS BLOB))<=CAST(? AS INTEGER) THEN value ELSE NULL END FROM meta WHERE key=?",new String[]{Integer.toString(CAP),key(gen,conversation)})){
            if(!c.moveToFirst())return new JSONArray();if(c.getLong(0)>CAP||c.isNull(1))throw new IllegalStateException("本对话备忘暂无法完整核对");
            JSONArray a=new JSONArray(c.getString(1));if(a.length()>MAX)throw new IllegalStateException("本对话备忘超出可核对范围");return a;
        }
    }
    private static void write(SQLiteDatabase db,String gen,String conversation,JSONArray rows)throws Exception{
        if(!db.inTransaction())throw new IllegalStateException("备忘只能随原会话事务保存");
        if(rows.length()==0){clear(db,gen,conversation);return;}String text=rows.toString();
        if(text.getBytes(StandardCharsets.UTF_8).length>CAP)throw new IllegalStateException("本对话备忘已满");
        ContentValues v=new ContentValues();v.put("key",key(gen,conversation));v.put("value",text);
        if(db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1)throw new IllegalStateException("备忘尚未确认保存");
    }
    private static JSONObject meta(JSONObject row)throws Exception{return new JSONObject().put("id",row.getString("id")).put("label",row.getString("label")).put("createdAt",row.getLong("createdAt")).put("chars",row.getJSONObject("reference").getInt("chars"));}
    static JSONObject list(SQLiteDatabase db,String gen,String conversation)throws Exception{
        JSONArray rows=entries(db,gen,conversation),out=new JSONArray();for(int i=rows.length()-1;i>=0;i--)out.put(meta(rows.getJSONObject(i)));
        return new JSONObject().put("entries",out).put("limit",MAX).put("conversationId",conversation).put("bodyIncluded",false);
    }
    static JSONObject reference(SQLiteDatabase db,String gen,String conversation,String id)throws Exception{
        JSONArray rows=entries(db,gen,conversation);for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("id"))){
            JSONObject r=OwnerSourceReferences.checked(new JSONArray().put(row.getJSONObject("reference"))).getJSONObject(0);
            if(gen.equals(r.getString("generation"))&&conversation.equals(r.getString("sourceConversationId"))){requireOwnMessage(db,gen,conversation,r);return r;}
        }}throw new SecurityException("原备忘已移除或不属于此对话");
    }
    private static void requireOwnMessage(SQLiteDatabase db,String gen,String conversation,JSONObject r)throws Exception{
        JSONObject ref=r.getJSONObject("sourceRef");
        try(Cursor c=db.rawQuery("SELECT s.client_message_id,CASE WHEN length(CAST(s.source_json AS BLOB))<=98304 THEN s.source_json ELSE NULL END FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.source_id=? AND m.revision=? AND m.body_sha=? AND m.body IS NOT NULL AND s.conversation_id=m.conversation_id AND s.revision=m.revision AND s.role='user' AND s.client_message_id IS NOT NULL AND s.deleted=0 AND s.context_retired=0 AND s.retired_through<s.revision",new String[]{gen,conversation,ref.getString("sourceId"),Long.toString(ref.getLong("revision")),r.getString("bodyFingerprint")})){
            if(!c.moveToFirst()||c.isNull(1))throw new SecurityException("未能核对本对话本人的原消息");
            JSONObject source=new JSONObject(c.getString(1)),ack=source.optJSONObject("submission");
            if(ack==null||!"owner".equals(source.optString("ownerId"))||!"private".equals(source.optString("spaceId"))||
                    !conversation.equals(ack.optString("conversationId"))||!ref.getString("sourceId").equals(ack.optString("messageId"))||
                    !c.getString(0).equals(ack.optString("clientMessageId"))||c.getString(0).isEmpty()||
                    !java.util.Set.of("accepted","queued","consumed").contains(ack.optString("status")))throw new SecurityException("这段原话缺少本人的原输入回执");
        }
    }
    static JSONObject add(SQLiteDatabase db,String gen,String conversation,JSONObject selected,String label,long now)throws Exception{
        JSONObject r=OwnerSourceReferences.checked(new JSONArray().put(selected)).getJSONObject(0);
        if(!gen.equals(r.getString("generation"))||!conversation.equals(r.getString("sourceConversationId")))throw new SecurityException("不能跨对话保留原话");
        String title=label.trim();if(title.length()>60||title.codePoints().anyMatch(Character::isISOControl)||!StandardCharsets.UTF_8.newEncoder().canEncode(title))throw new IllegalArgumentException("备忘名最多60个完整字符");
        JSONObject ref=r.getJSONObject("sourceRef");requireOwnMessage(db,gen,conversation,r);
        JSONArray rows=entries(db,gen,conversation);
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),old=row.getJSONObject("reference");
            if(r.getString("bodyFingerprint").equals(old.getString("bodyFingerprint"))&&ref.getString("sourceId").equals(old.getJSONObject("sourceRef").getString("sourceId"))&&ref.getLong("revision")==old.getJSONObject("sourceRef").getLong("revision")&&r.getInt("offset")==old.getInt("offset")&&r.getInt("chars")==old.getInt("chars"))
                return meta(row).put("alreadyPinned",true).put("conversationId",conversation).put("bodyIncluded",false);
        }
        if(rows.length()>=MAX)throw new IllegalStateException("本对话已有24条备忘，请先移除不再需要的一条");
        JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("label",title).put("createdAt",now).put("reference",r);
        rows.put(row);write(db,gen,conversation,rows);return meta(row).put("alreadyPinned",false).put("conversationId",conversation).put("bodyIncluded",false);
    }
    static JSONObject remove(SQLiteDatabase db,String gen,String conversation,String id)throws Exception{
        JSONArray rows=entries(db,gen,conversation);for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("id"))){rows.remove(i);write(db,gen,conversation,rows);return new JSONObject().put("removed",true).put("id",id).put("conversationId",conversation);}
        throw new IllegalStateException("这条备忘已变化，请重新读取本对话备忘");
    }
}
