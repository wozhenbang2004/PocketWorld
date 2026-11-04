package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import dev.pocketengine.features.assistant.ConversationRepository;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Selected conversation content from a verified byte copy into the original schema-only staging. */
final class TaskDatabaseRecoveryCopy {
    private TaskDatabaseRecoveryCopy() { }
    static JSONObject restoreConversations(File preserved,DatabaseFilePreservation.Snapshot identity,File mirror,File staging,String repair,BooleanSupplier current)throws Exception {
        DatabaseFilePreservation.requireCurrent(current);DatabaseFilePreservation.verify(preserved,identity);
        DatabaseFilePreservation.preserve(preserved,mirror,identity,current);
        long bytes=0,messages=0,omitted=0;int sourceCount,unreadable=0;String generation;
        JSONArray conversations=new JSONArray(),gaps=new JSONArray();Set<String> restoredIds=new HashSet<>();
        File readOnly=new File(mirror,TaskStore.DATABASE_NAME);
        try(SQLiteDatabase source=SQLiteDatabase.openDatabase(readOnly.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS,new TaskDatabaseCorruptionHandler(null));
            SQLiteDatabase destination=SQLiteDatabase.openDatabase(staging.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE,new TaskDatabaseCorruptionHandler(null))) {
            if(!source.isReadOnly()||source.getVersion()!=TaskStore.DATABASE_SCHEMA||destination.getVersion()!=TaskStore.DATABASE_SCHEMA)throw new SecurityException("verified_copy_schema_incompatible");
            ConversationRepository old=new ConversationRepository(source,new Object()),restored=new ConversationRepository(destination,new Object());
            if(!old.active()||!restored.active())throw new SecurityException("verified_copy_history_authority_unavailable");
            try(Cursor row=source.rawQuery("SELECT value FROM meta WHERE key='assistant_history_generation'",null)){if(!row.moveToFirst())throw new SecurityException("verified_copy_history_generation_unavailable");generation=row.getString(0);}
            requireIdentity(generation,"verified_copy_history_generation_unavailable");
            List<String> ids=new ArrayList<>();
            try(Cursor rows=source.rawQuery("SELECT conversation_id FROM assistant_conversations WHERE generation=? AND deleted=0 ORDER BY conversation_id LIMIT 201",new String[]{generation})){
                while(rows.moveToNext()){DatabaseFilePreservation.requireCurrent(current);if(ids.size()>=TaskDatabaseRecoveryManifest.MAX_CONVERSATIONS)throw new IllegalStateException("verified_copy_conversation_limit");String id=rows.getString(0);requireIdentity(id,"verified_copy_conversation_identity_unavailable");ids.add(id);}
            }
            sourceCount=ids.size();Set<String> sourceIds=new HashSet<>(ids);
            if(sourceIds.size()!=sourceCount)throw new SecurityException("verified_copy_conversation_identity_unavailable");
            for(String id:ids){
                DatabaseFilePreservation.requireCurrent(current);JSONObject exported;
                try{exported=old.exportVerifiedReadOnlyCopy(new JSONObject().put("conversationId",id).put("format","json"));}
                catch(Exception unavailable){
                    // A partial source can yield independent conversations. A destination failure is
                    // deliberately outside this catch and invalidates the entire staging attempt.
                    DatabaseFilePreservation.requireCurrent(current);unreadable++;
                    gaps.put(new JSONObject().put("sourceConversationId",id).put("reason",unreadableReason(unavailable)).put("recordCount",JSONObject.NULL));continue;
                }
                DatabaseFilePreservation.requireCurrent(current);
                byte[] content=exported.getString("content").getBytes(StandardCharsets.UTF_8);
                if(!id.equals(exported.getString("sourceConversationId"))||!"json".equals(exported.getString("format"))||!Boolean.FALSE.equals(exported.get("executionAuthorized"))||content.length!=exported.getLong("utf8Bytes")||!DatabaseFilePreservation.sha256(content).equals(exported.getString("sha256")))throw new SecurityException("verified_copy_export_identity_changed");
                JSONObject payload=new JSONObject(exported.getString("content"));
                if(!generation.equals(payload.getString("sourceGeneration")))throw new SecurityException("verified_copy_history_generation_unavailable");
                int included=exported.getJSONArray("includedMessages").length();JSONArray omissions=exported.getJSONArray("omitted");
                Map<String,Long> reasons=TaskDatabaseRecoveryManifest.omissionCounts(omissions);
                bytes=Math.addExact(bytes,content.length);if(bytes>TaskDatabaseRecoveryManifest.MAX_CONTENT_BYTES)throw new IllegalStateException("verified_copy_content_byte_limit");
                TaskDatabaseRecoveryManifest.writeExport(mirror,conversations.length(),content,current);
                String action=UUID.nameUUIDFromBytes(("maintenance-conversation:"+repair+":"+id).getBytes(StandardCharsets.UTF_8)).toString();
                JSONObject actual=restored.importConversation(new JSONObject().put("actionId",action).put("exportJson",exported.getString("content")).put("sha256",exported.getString("sha256")));
                String restoredId=actual.getString("conversationId");
                if(sourceIds.contains(restoredId)||!restoredIds.add(restoredId)||!Boolean.FALSE.equals(actual.get("executionAuthorized"))||!Boolean.FALSE.equals(actual.get("attachmentsRestored"))||!Boolean.FALSE.equals(actual.get("duplicate"))||!"content_only_requires_explicit_reference_selection".equals(actual.getString("importPolicy"))||actual.getLong("importedMessages")!=included)throw new SecurityException("verified_copy_new_identity_required");
                conversations.put(new JSONObject().put("sourceConversationId",id).put("restoredConversationId",restoredId).put("messageCount",included).put("omittedCount",omissions.length()).put("exportSha256",exported.getString("sha256")).put("exportBytes",content.length));
                for(Map.Entry<String,Long> reason:reasons.entrySet())gaps.put(new JSONObject().put("sourceConversationId",id).put("reason",reason.getKey()).put("recordCount",reason.getValue()));
                messages=Math.addExact(messages,included);omitted=Math.addExact(omitted,omissions.length());
            }
        }
        // Android may change mirror-side SHM on read. The preserved source itself was never opened in SQLite.
        DatabaseFilePreservation.verify(preserved,identity);DatabaseFilePreservation.requireCurrent(current);TaskStore.verifyMaintenanceStaging(staging,repair);
        if(messages==0)throw new SecurityException("verified_copy_no_recoverable_messages");
        JSONObject summary=new JSONObject().put("section","conversations").put("sourceConversationCount",sourceCount).put("conversationCount",conversations.length()).put("messageCount",messages).put("omittedCount",omitted).put("unreadableConversationCount",unreadable).put("partial",gaps.length()>0).put("contentUtf8Bytes",bytes)
            .put("sourceFingerprint",identity.fingerprint()).put("sourceGeneration",generation).put("conversations",conversations).put("gaps",gaps).put("sourceReadOnly",true).put("preservedSourceOpenedInSQLite",false)
            .put("restoresExistingIds",false).put("restoresActiveGrants",false).put("restoresTaskHistory",false).put("restoresAttachmentBytes",false).put("restoresCredentialsOrPairings",false)
            .put("omissionMeaning",TaskDatabaseRecoveryManifest.OMISSION_MEANING);
        return TaskDatabaseRecoveryManifest.seal(mirror,repair,summary,current);
    }
    static void verifyExtraction(File mirror,JSONObject restoration,String repair,String sourceFingerprint,BooleanSupplier current)throws Exception{TaskDatabaseRecoveryManifest.verify(mirror,restoration,repair,sourceFingerprint,current);}
    private static void requireIdentity(String value,String reason){if(value==null||value.isEmpty()||value.length()>240||value.indexOf('\0')>=0||!StandardCharsets.UTF_8.newEncoder().canEncode(value))throw new SecurityException(reason);}
    private static String unreadableReason(Exception failure){if(failure instanceof SQLiteDatabaseCorruptException)return "source_conversation_corrupt";if(failure instanceof ConversationRepository.ReadLimited)return "source_conversation_read_limited";if(failure instanceof SecurityException||failure instanceof IllegalArgumentException||failure instanceof org.json.JSONException)return "source_conversation_validation_failed";return "source_conversation_unreadable";}
}
