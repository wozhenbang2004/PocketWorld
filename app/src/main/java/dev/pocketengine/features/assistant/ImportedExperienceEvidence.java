package dev.pocketengine.features.assistant;

import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.store.OwnerExperienceProjection;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Passive identity and byte proof for the original repository's supported import records.
 * The assigned record/version is not a historical message ID/revision or a SourceRef. */
public final class ImportedExperienceEvidence {
    private ImportedExperienceEvidence(){}
    public static final int MAX_BYTES=ConversationRepository.MAX_BYTES;
    public static final class ProtectedFields extends SecurityException {public ProtectedFields(){super("旧导入原件含非用户可见推理/凭据字段，未将原字节写入归档");}}
    public static JSONObject checkedLocator(JSONObject input)throws Exception {
        if(input==null)throw new SecurityException("缺少旧导入原件定位");JSONObject out=new JSONObject(input.toString());Set<String> keys=new HashSet<>();out.keys().forEachRemaining(keys::add);
        boolean transferred=input.has("sourceFormat");
        if(!keys.equals(transferred?Set.of("recordId","recordVersion","conversationId","sourceSha256","offset","length","fragmentSha256","sourceFormat"):Set.of("recordId","recordVersion","conversationId","sourceSha256","offset","length","fragmentSha256"))||transferred&&!"conversation_export_v1".equals(out.opt("sourceFormat")))throw new SecurityException("导入定位不能包含未知格式、来源授权或替代身份");
        String id=out.getString("recordId"),source=out.getString("sourceSha256"),conversation=out.getString("conversationId");
        String identity=transferred?"import-message:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}":"import-message:"+source+":[0-9]+:[0-9]+";
        if(!source.matches("[a-f0-9]{64}")||!id.matches(identity)||conversation.isEmpty()||conversation.length()>240||!out.getString("fragmentSha256").matches("[a-f0-9]{64}"))throw new SecurityException("导入记录身份/hash与原格式不符");
        number(out,"recordVersion",1);long offset=number(out,"offset",0),length=number(out,"length",1);if(length>LegacyConversationImport.MAX_SOURCE_BYTES||offset>LegacyConversationImport.MAX_SOURCE_BYTES-length)throw new SecurityException("旧导入原件范围超过原读取预算");return out;
    }
    public static JSONObject passiveRow(JSONObject locator,byte[] fragment,Long historicalRevision,Long at,String storedRole)throws Exception {
        JSONObject pin=checkedLocator(locator);if(fragment==null||fragment.length>MAX_BYTES||fragment.length!=pin.getLong("length")||!ProjectSources.sha256(fragment).equals(pin.getString("fragmentSha256")))throw new SecurityException("旧导入原件段缺失、长度或hash不符");
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(fragment)).toString();JSONTokener parser=new JSONTokener(json);Object parsed=parser.nextValue();if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw new SecurityException("旧导入原件段不是一个完整消息对象");JSONObject raw=(JSONObject)parsed;
        if(OwnerExperienceProjection.hasProtectedArchiveFields(raw))throw new ProtectedFields();
        Long rawRevision=integerOrUnknown(raw.opt("revision"),true),rawAt=integerOrUnknown(raw.opt("at"),false);String role=raw.opt("role") instanceof String?raw.getString("role"):"";
        if(!Objects.equals(rawRevision,historicalRevision)||!Objects.equals(rawAt,at)||!Objects.equals(role,storedRole))throw new SecurityException("旧导入历史修订、时间或角色与原件段不符，未补造来源");
        Object content=raw.opt("content");String text=content instanceof String?(String)content:content==null?"":content.toString();byte[] body=text.getBytes(StandardCharsets.UTF_8);
        JSONObject row=new JSONObject().put("kind","imported_message").put("ownerId","owner").put("spaceId","private").put("importedLocator",pin)
            .put("recordId",pin.getString("recordId")).put("recordVersion",pin.getLong("recordVersion")).put("conversationId",pin.getString("conversationId"))
            .put("importAssignedIdentity",true).put("identityProvenance","import_assigned_not_historical_source").put("formalSourceRefAvailable",false)
            .put("historicalRevision",historicalRevision==null?JSONObject.NULL:historicalRevision).put("historicalRevisionKnown",historicalRevision!=null)
            .put("at",at==null?JSONObject.NULL:at).put("historicalTimeKnown",at!=null).put("role",role).put("roleProvenance","stored_legacy_field_not_native_author_proof")
            .put("text",text).put("sha256",ProjectSources.sha256(body)).put("bytes",body.length).put("originalFragmentBase64",Base64.getEncoder().encodeToString(fragment))
            .put("originalIdentity",new JSONObject().put("id",raw.has("id")?raw.get("id"):JSONObject.NULL).put("revision",raw.has("revision")?raw.get("revision"):JSONObject.NULL))
            .put("archiveOnly",true).put("executionAuthorized",false);
        if(pin.has("sourceFormat")){
            // The transfer importer retained these normalized bytes. Its declared historical
            // record is evidence carried by the import, never a native SourceRef or author proof.
            JSONObject origin=raw.optJSONObject("originExportRecord");if(origin==null)throw new SecurityException("会话导入原件缺少原导出记录线索");
            row.put("sourceFormat",pin.getString("sourceFormat")).put("roleProvenance","stored_imported_export_field_not_native_author_proof").put("originExportRecord",new JSONObject(origin.toString())).put("savedRepresentation","normalized_import_fragment_not_original_export_file").put("originalExportBytesIncluded",false).put("transferMetadataKnown",raw.has("originExportEvidence"));
            if(raw.has("originExportEvidence"))row.put("originExportEvidence",checkedTransferEvidence(raw.getJSONObject("originExportEvidence"),origin.getString("sourceRecordId")));
        }
        if(raw.has("attachments"))row.put("originalAttachmentMetadata",raw.get("attachments"));return row;
    }
    public static JSONObject checkedRow(JSONObject input)throws Exception {
        JSONObject row=new JSONObject(input.toString());if(row.has("sourceRef")||row.has("revision")||!"imported_message".equals(row.optString("kind")))throw new SecurityException("旧导入记录不能伪装原生来源或历史修订");
        String encoded=row.getString("originalFragmentBase64");if(encoded.length()>4L*((MAX_BYTES+2L)/3L))throw new SecurityException("旧导入原件编码超过预算");byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new SecurityException("旧导入原件编码损坏",invalid);}if(!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw new SecurityException("旧导入原件编码不是确切规范字节");
        Long revision=row.isNull("historicalRevision")?null:integerOrUnknown(row.get("historicalRevision"),true),at=row.isNull("at")?null:integerOrUnknown(row.get("at"),false);
        JSONObject expected=passiveRow(row.getJSONObject("importedLocator"),bytes,revision,at,row.getString("role"));if(!ProjectSources.canonical(expected).equals(ProjectSources.canonical(row)))throw new SecurityException("旧导入原件投影已被换版、补造字段或提升权限");return row;
    }
    public static boolean sameOriginal(JSONObject left,JSONObject right)throws Exception {
        JSONObject a=checkedLocator(left),b=checkedLocator(right);return a.optString("sourceFormat","legacy_array").equals(b.optString("sourceFormat","legacy_array"))&&a.getString("recordId").equals(b.getString("recordId"))&&a.getString("conversationId").equals(b.getString("conversationId"))&&a.getString("sourceSha256").equals(b.getString("sourceSha256"))&&a.getLong("offset")==b.getLong("offset")&&a.getLong("length")==b.getLong("length")&&a.getString("fragmentSha256").equals(b.getString("fragmentSha256"));
    }
    private static JSONObject checkedTransferEvidence(JSONObject input,String record)throws Exception {
        JSONObject value=new JSONObject(input.toString());ConversationOrganization.fields(value,"exportId","exportSha256","attachmentReferences","omitted","unmatchedAttachmentReferences","unmatchedOmissions");
        if(value.length()!=6||value.getString("exportId").isEmpty()||value.getString("exportId").length()>240||!value.getString("exportSha256").matches("[a-f0-9]{64}"))throw new SecurityException("导入原件的导出身份/hash无效");
        JSONArray refs=value.getJSONArray("attachmentReferences"),omitted=value.getJSONArray("omitted");long unmatchedRefs=number(value,"unmatchedAttachmentReferences",0),unmatchedOmissions=number(value,"unmatchedOmissions",0);
        if(refs.length()+unmatchedRefs>ConversationTransfer.MAX_MESSAGES*4L||omitted.length()+unmatchedOmissions>ConversationTransfer.MAX_MESSAGES*2L)throw new SecurityException("导入原件的引用/缺口清单超出原预算");
        for(int i=0;i<refs.length();i++){JSONObject ref=refs.getJSONObject(i);ConversationOrganization.fields(ref,"messageId","attachmentId","sha256","availability");if(ref.length()!=4||!record.equals(ref.getString("messageId"))||ref.getString("attachmentId").isEmpty()||ref.getString("attachmentId").length()>240||!ref.getString("sha256").matches("[a-f0-9]{64}")||!"reference_only_unverified_not_restored".equals(ref.getString("availability")))throw new SecurityException("导入附件线索不能跨记录或伪装原件权限");}
        for(int i=0;i<omitted.length();i++){JSONObject gap=omitted.getJSONObject(i);ConversationOrganization.fields(gap,"sourceRecordId","reason");if(gap.length()!=2||!record.equals(gap.getString("sourceRecordId"))||gap.getString("reason").length()>120)throw new SecurityException("导入缺口线索不属于原记录");}
        return value;
    }
    private static Long integerOrUnknown(Object value,boolean positive){if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))return null;long number=((Number)value).longValue();return positive&&number<1?null:number;}
    private static long number(JSONObject value,String field,long minimum){Object raw=value.opt(field);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<minimum)throw new SecurityException("旧导入数字字段无效："+field);return ((Number)raw).longValue();}
}
