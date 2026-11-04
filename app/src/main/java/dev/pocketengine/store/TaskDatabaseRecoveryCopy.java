package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.assistant.ConversationRepository;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Selected conversation content from a verified byte copy into the original schema-only staging. */
final class TaskDatabaseRecoveryCopy {
    private TaskDatabaseRecoveryCopy() { }
    static JSONObject restoreConversations(File preserved,DatabaseFilePreservation.Snapshot identity,File mirror,File staging,String repair,BooleanSupplier current)throws Exception {
        DatabaseFilePreservation.requireCurrent(current);DatabaseFilePreservation.verify(preserved,identity);
        DatabaseFilePreservation.preserve(preserved,mirror,identity,current);
        long bytes=0,messages=0,omitted=0;int conversations=0;
        File readOnly=new File(mirror,TaskStore.DATABASE_NAME);
        try(SQLiteDatabase source=SQLiteDatabase.openDatabase(readOnly.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS,new TaskDatabaseCorruptionHandler(null));
            SQLiteDatabase destination=SQLiteDatabase.openDatabase(staging.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE,new TaskDatabaseCorruptionHandler(null))) {
            if(source.getVersion()!=TaskStore.DATABASE_SCHEMA||destination.getVersion()!=TaskStore.DATABASE_SCHEMA)throw new SecurityException("verified_copy_schema_incompatible");
            ConversationRepository old=new ConversationRepository(source,new Object()),restored=new ConversationRepository(destination,new Object());
            if(!old.active()||!restored.active())throw new SecurityException("verified_copy_history_authority_unavailable");
            String generation;try(Cursor row=source.rawQuery("SELECT value FROM meta WHERE key='assistant_history_generation'",null)){if(!row.moveToFirst())throw new SecurityException("verified_copy_history_generation_unavailable");generation=row.getString(0);}
            List<String> ids=new ArrayList<>();try(Cursor rows=source.rawQuery("SELECT conversation_id FROM assistant_conversations WHERE generation=? AND deleted=0 ORDER BY conversation_id LIMIT 201",new String[]{generation})){while(rows.moveToNext()){if(ids.size()>=200)throw new IllegalStateException("verified_copy_conversation_limit");ids.add(rows.getString(0));}}
            for(String id:ids){
                DatabaseFilePreservation.requireCurrent(current);JSONObject exported=old.exportVerifiedReadOnlyCopy(new JSONObject().put("conversationId",id).put("format","json"));
                var omissions=exported.getJSONArray("omitted");for(int i=0;i<omissions.length();i++)if(java.util.Set.of("source_integrity_unavailable","source_revision_or_role_unavailable","message_read_limited").contains(omissions.getJSONObject(i).getString("reason")))throw new SecurityException("verified_copy_original_record_unconfirmed");
                bytes=Math.addExact(bytes,exported.getLong("utf8Bytes"));if(bytes>64L*1024*1024)throw new IllegalStateException("verified_copy_content_byte_limit");
                String action=UUID.nameUUIDFromBytes(("maintenance-conversation:"+repair+":"+id).getBytes(StandardCharsets.UTF_8)).toString();
                JSONObject actual=restored.importConversation(new JSONObject().put("actionId",action).put("exportJson",exported.getString("content")).put("sha256",exported.getString("sha256")));
                if(id.equals(actual.getString("conversationId"))||actual.optBoolean("executionAuthorized",true))throw new SecurityException("verified_copy_new_identity_required");
                conversations++;messages=Math.addExact(messages,actual.getLong("importedMessages"));omitted=Math.addExact(omitted,exported.getJSONArray("omitted").length());
            }
        }
        // Android may change mirror-side SHM on read. The preserved source itself was never opened in SQLite.
        DatabaseFilePreservation.verify(preserved,identity);DatabaseFilePreservation.requireCurrent(current);TaskStore.verifyMaintenanceStaging(staging,repair);
        return new JSONObject().put("section","conversations").put("conversationCount",conversations).put("messageCount",messages).put("omittedCount",omitted).put("contentUtf8Bytes",bytes)
            .put("sourceFingerprint",identity.fingerprint()).put("sourceReadOnly",true).put("preservedSourceOpenedInSQLite",false)
            .put("restoresExistingIds",false).put("restoresActiveGrants",false).put("restoresTaskHistory",false).put("restoresAttachmentBytes",false).put("restoresCredentialsOrPairings",false)
            .put("omissionMeaning","原删除、撤回、不可用或非user/assistant记录沿原导出规则明确省略；单条校验或读取失败会拒绝本次暂存，不自动降级为空库");
    }
}
