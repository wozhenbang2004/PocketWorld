package dev.pocketengine.features.assistant;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner metadata on the original conversation rows. No message copy, task or execution grant. */
final class ConversationOrganization {
    private ConversationOrganization(){}
    static void createSchema(SQLiteDatabase db){
        Set<String> columns=new HashSet<>();
        try(Cursor rows=db.rawQuery("PRAGMA table_info(assistant_conversations)",null)){while(rows.moveToNext())columns.add(rows.getString(1));}
        for(String definition:List.of("organization_revision INTEGER NOT NULL DEFAULT 0","pin_order INTEGER NOT NULL DEFAULT 0",
                "archived_at INTEGER NOT NULL DEFAULT 0","manual_unread INTEGER NOT NULL DEFAULT 0",
                "read_watermark INTEGER NOT NULL DEFAULT 0","group_id TEXT NOT NULL DEFAULT ''")){
            if(!columns.contains(definition.substring(0,definition.indexOf(' '))))db.execSQL("ALTER TABLE assistant_conversations ADD COLUMN "+definition);
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS assistant_conversations_organized ON assistant_conversations(generation,deleted,archived_at,group_id,pin_order,updated_at,conversation_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_conversation_groups(generation TEXT NOT NULL,group_id TEXT NOT NULL,title TEXT NOT NULL,revision INTEGER NOT NULL,project_id TEXT NOT NULL DEFAULT '',action_fingerprint TEXT NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(generation,group_id))");
    }
    static void fields(JSONObject input,String... allowed){
        Set<String> keys=Set.of(allowed);for(var iterator=input.keys();iterator.hasNext();){String key=iterator.next();if(!keys.contains(key))throw new SecurityException("会话主人操作不接受字段："+key);}
    }
    static long integer(JSONObject input,String key,long minimum,long maximum){
        Object raw=input.opt(key);if(!(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long))throw new IllegalArgumentException(key+"必须是整数");
        long value=((Number)raw).longValue();if(value<minimum||value>maximum)throw new IllegalArgumentException(key+"超出范围");return value;
    }
    static boolean bool(JSONObject input,String key){Object raw=input.opt(key);if(!(raw instanceof Boolean))throw new IllegalArgumentException(key+"必须是布尔值");return (Boolean)raw;}
    static String title(JSONObject input){Object raw=input.opt("title");if(!(raw instanceof String))throw new IllegalArgumentException("请输入组/会话名称");String title=((String)raw).trim();if(title.isEmpty()||title.length()>120||!StandardCharsets.UTF_8.newEncoder().canEncode(title))throw new IllegalArgumentException("名称应为1至120个完整字符");return title;}
    private static String sha(byte[] bytes)throws Exception {byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}
    private static JSONObject header(SQLiteDatabase db,String generation,String conversation)throws Exception {
        try(Cursor size=db.rawQuery("SELECT length(CAST(header_json AS BLOB)) FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0",new String[]{generation,conversation})){if(!size.moveToFirst())throw new IllegalArgumentException("原会话不存在");if(size.getLong(0)>1_048_576)throw new IllegalStateException("原会话metadata超过读取预算");}
        try(Cursor row=db.rawQuery("SELECT header_json FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0",new String[]{generation,conversation})){if(!row.moveToFirst())throw new IllegalArgumentException("原会话不存在");return new JSONObject(row.getString(0));}
    }
    private static String revisionKey(String generation){return "assistant.organization.catalog."+generation;}
    static long catalogRevision(SQLiteDatabase db,String generation){try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{revisionKey(generation)})){return row.moveToFirst()?Long.parseLong(row.getString(0)):0;}}
    static long changed(SQLiteDatabase db,String generation){long next=Math.addExact(catalogRevision(db,generation),1);ContentValues value=new ContentValues();value.put("key",revisionKey(generation));value.put("value",Long.toString(next));db.insertWithOnConflict("meta",null,value,SQLiteDatabase.CONFLICT_REPLACE);return next;}
    static JSONObject get(SQLiteDatabase db,String generation,String conversation)throws Exception{
        JSONObject header=header(db,generation,conversation);try(Cursor row=db.rawQuery("SELECT organization_revision,pin_order,archived_at,manual_unread,read_watermark,group_id,updated_at FROM assistant_conversations WHERE generation=? AND conversation_id=? AND deleted=0",new String[]{generation,conversation})){
            if(!row.moveToFirst())throw new IllegalArgumentException("原会话不存在");long watermark=row.getLong(4);JSONObject result=new JSONObject().put("conversationId",conversation).put("title",header.optString("title"))
                    .put("updatedAt",row.getLong(6)).put("organizationRevision",row.getLong(0)).put("pinOrder",row.getLong(1)).put("archivedAt",row.getLong(2)).put("archived",row.getLong(2)>0)
                    .put("manualUnread",row.getInt(3)!=0).put("readWatermark",watermark).put("groupId",row.getString(5));
            long unread=0,latest=0;
            try(Cursor counts=db.rawQuery("SELECT coalesce(max(n),0),coalesce(sum(CASE WHEN n>? AND role='assistant' THEN 1 ELSE 0 END),0) FROM (SELECT m.sequence n,s.role role FROM assistant_messages m JOIN assistant_sources s ON s.generation=m.generation AND s.source_id=m.source_id WHERE m.generation=? AND m.conversation_id=? AND m.body IS NOT NULL AND s.deleted=0 UNION ALL SELECT sequence n,role FROM assistant_import_records WHERE generation=? AND conversation_id=? AND deleted=0)",new String[]{Long.toString(watermark),generation,conversation,generation,conversation})){counts.moveToFirst();latest=counts.getLong(0);unread=counts.getLong(1);}
            try(Cursor maximum=db.rawQuery("SELECT coalesce(max(n),0) FROM (SELECT sequence n FROM assistant_messages WHERE generation=? AND conversation_id=? UNION ALL SELECT sequence n FROM assistant_import_records WHERE generation=? AND conversation_id=?)",new String[]{generation,conversation,generation,conversation})){maximum.moveToFirst();latest=maximum.getLong(0);}
            return result.put("latestWatermark",latest).put("unreadReplyCount",unread).put("unread",result.getBoolean("manualUnread")||unread>0)
                    .put("catalogRevision",catalogRevision(db,generation)).put("ownerId","owner").put("spaceId","private").put("bodyIncluded",false).put("executionAuthorized",false);
        }
    }
    static JSONObject update(SQLiteDatabase db,String generation,JSONObject input)throws Exception{
        fields(input,"conversationId","expectedRevision","changes");String conversation=input.getString("conversationId");long expected=integer(input,"expectedRevision",0,Long.MAX_VALUE-1);
        JSONObject changes=input.getJSONObject("changes");fields(changes,"title","pinOrder","archived","manualUnread","readWatermark","groupId");if(changes.length()==0)throw new IllegalArgumentException("会话变更不能为空");
        JSONObject old=get(db,generation,conversation);if(expected!=old.getLong("organizationRevision"))throw new IllegalStateException("会话组织已变化，请刷新后重试");
        ContentValues values=new ContentValues();values.put("organization_revision",expected+1);
        if(changes.has("title")){JSONObject header=header(db,generation,conversation);String title=title(changes);header.put("title",title).put("nativeUserTitle",title);values.put("header_json",header.toString());}
        if(changes.has("pinOrder"))values.put("pin_order",integer(changes,"pinOrder",0,1_000_000_000));
        if(changes.has("archived")){boolean archived=bool(changes,"archived");values.put("archived_at",archived?(old.getLong("archivedAt")>0?old.getLong("archivedAt"):System.currentTimeMillis()):0);}
        if(changes.has("manualUnread"))values.put("manual_unread",bool(changes,"manualUnread")?1:0);
        if(changes.has("readWatermark")){long watermark=integer(changes,"readWatermark",old.getLong("readWatermark"),old.getLong("latestWatermark"));values.put("read_watermark",watermark);}
        if(changes.has("groupId")){Object raw=changes.get("groupId");if(!(raw instanceof String)||((String)raw).length()>240)throw new IllegalArgumentException("组身份无效");String group=(String)raw;if(!group.isEmpty())group(db,generation,group);values.put("group_id",group);}
        if(db.update("assistant_conversations",values,"generation=? AND conversation_id=? AND deleted=0 AND organization_revision=?",new String[]{generation,conversation,Long.toString(expected)})!=1)throw new IllegalStateException("会话组织已变化");changed(db,generation);return get(db,generation,conversation);
    }
    static JSONObject list(SQLiteDatabase db,String generation,JSONObject input)throws Exception{
        fields(input,"cursor","limit","archive","groupId");int limit=input.has("limit")?(int)integer(input,"limit",1,20):20;
        String archive=input.optString("archive","active"),group=input.optString("groupId"),cursor=input.optString("cursor");if(!Set.of("active","archived","all").contains(archive)||group.length()>240||cursor.length()>4000)throw new IllegalArgumentException("列表范围无效");
        if(!group.isEmpty())group(db,generation,group);long revision=catalogRevision(db,generation);String signature=sha((generation+"\0"+archive+"\0"+group).getBytes(StandardCharsets.UTF_8));
        JSONObject position=cursor.isEmpty()?new JSONObject():new JSONObject(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));
        if(!cursor.isEmpty()&&(!signature.equals(position.optString("signature"))||revision!=integer(position,"revision",0,Long.MAX_VALUE)))return new JSONObject().put("status","source_changed").put("reason","conversation_catalog_changed").put("conversations",new JSONArray()).put("nextCursor","").put("hasMore",false).put("catalogRevision",revision).put("restartRequired",true);
        String where="generation=? AND deleted=0";List<String> args=new ArrayList<>();args.add(generation);
        if(!archive.equals("all"))where+=archive.equals("archived")?" AND archived_at>0":" AND archived_at=0";
        if(!group.isEmpty()){where+=" AND group_id=?";args.add(group);}
        String rank="CASE WHEN pin_order>0 THEN 0 ELSE 1 END";
        if(!cursor.isEmpty()){where+=" AND (("+rank+")>CAST(? AS INTEGER) OR (("+rank+")=CAST(? AS INTEGER) AND (pin_order>? OR (pin_order=? AND (updated_at<? OR (updated_at=? AND conversation_id<?))))))";
            args.add(Long.toString(integer(position,"rank",0,1)));args.add(args.get(args.size()-1));args.add(Long.toString(integer(position,"pin",0,1_000_000_000)));args.add(args.get(args.size()-1));args.add(Long.toString(integer(position,"at",0,Long.MAX_VALUE)));args.add(args.get(args.size()-1));args.add(position.getString("id"));}
        args.add(Integer.toString(limit+1));JSONArray rows=new JSONArray();JSONObject last=null;boolean more=false;
        try(Cursor current=db.rawQuery("SELECT conversation_id,"+rank+",pin_order,updated_at FROM assistant_conversations WHERE "+where+" ORDER BY "+rank+",pin_order,updated_at DESC,conversation_id DESC LIMIT ?",args.toArray(new String[0]))){
            while(current.moveToNext()){if(rows.length()>=limit){more=true;break;}JSONObject item=get(db,generation,current.getString(0));rows.put(item);last=new JSONObject().put("signature",signature).put("revision",revision).put("rank",current.getInt(1)).put("pin",current.getLong(2)).put("at",current.getLong(3)).put("id",current.getString(0));}
        }
        String next=more&&last!=null?Base64.getUrlEncoder().withoutPadding().encodeToString(last.toString().getBytes(StandardCharsets.UTF_8)):"";
        return new JSONObject().put("status","current").put("conversations",rows).put("nextCursor",next).put("hasMore",more).put("catalogRevision",revision).put("paginationSemantics","revision_fenced_keyset_restart_on_catalog_change").put("bodyIncluded",false).put("executionAuthorized",false);
    }
    private static JSONObject group(SQLiteDatabase db,String generation,String id)throws Exception{
        try(Cursor row=db.rawQuery("SELECT title,revision,project_id,action_fingerprint FROM assistant_conversation_groups WHERE generation=? AND group_id=? AND deleted=0",new String[]{generation,id})){if(!row.moveToFirst())throw new IllegalArgumentException("主人组不存在或已移除");return new JSONObject().put("groupId",id).put("title",row.getString(0)).put("revision",row.getLong(1)).put("projectId",row.getString(2)).put("actionFingerprint",row.getString(3));}
    }
    static JSONObject createGroup(SQLiteDatabase db,String generation,JSONObject input)throws Exception{
        fields(input,"actionId","title");String action=input.getString("actionId");if(!action.matches("[a-fA-F0-9-]{36}"))throw new IllegalArgumentException("创建组需要固定actionId");String title=title(input),fingerprint=sha(title.getBytes(StandardCharsets.UTF_8)),id="group:"+UUID.nameUUIDFromBytes((generation+":"+action).getBytes(StandardCharsets.UTF_8));
        try(Cursor row=db.rawQuery("SELECT action_fingerprint FROM assistant_conversation_groups WHERE generation=? AND group_id=?",new String[]{generation,id})){if(row.moveToFirst()){if(!fingerprint.equals(row.getString(0)))throw new SecurityException("同actionId不能创建不同组");return group(db,generation,id).put("duplicate",true);}}
        ContentValues value=new ContentValues();value.put("generation",generation);value.put("group_id",id);value.put("title",title);value.put("revision",1);value.put("project_id","");value.put("action_fingerprint",fingerprint);db.insertOrThrow("assistant_conversation_groups",null,value);changed(db,generation);return group(db,generation,id).put("duplicate",false);
    }
    static JSONObject updateGroup(SQLiteDatabase db,String generation,JSONObject input)throws Exception{
        fields(input,"groupId","expectedRevision","title");String id=input.getString("groupId"),title=title(input);long revision=integer(input,"expectedRevision",1,Long.MAX_VALUE-1);JSONObject old=group(db,generation,id);if(old.getLong("revision")!=revision)throw new IllegalStateException("组已变化");ContentValues value=new ContentValues();value.put("title",title);value.put("revision",revision+1);db.update("assistant_conversation_groups",value,"generation=? AND group_id=? AND revision=? AND deleted=0",new String[]{generation,id,Long.toString(revision)});changed(db,generation);return group(db,generation,id);
    }
    static JSONObject groups(SQLiteDatabase db,String generation,JSONObject input)throws Exception{
        fields(input,"after","limit");int limit=input.has("limit")?(int)integer(input,"limit",1,50):50;String after=input.optString("after");if(after.length()>240)throw new IllegalArgumentException("组游标过大");JSONArray groups=new JSONArray();boolean more=false;String last="";
        try(Cursor rows=db.rawQuery("SELECT group_id FROM assistant_conversation_groups WHERE generation=? AND deleted=0 AND group_id>? ORDER BY group_id LIMIT ?",new String[]{generation,after,Integer.toString(limit+1)})){while(rows.moveToNext()){if(groups.length()>=limit){more=true;break;}last=rows.getString(0);JSONObject group=group(db,generation,last);group.remove("actionFingerprint");groups.put(group);}}
        return new JSONObject().put("groups",groups).put("hasMore",more).put("nextCursor",more?last:"").put("bodyIncluded",false).put("executionAuthorized",false);
    }
}
