package dev.pocketengine.features.attachments;

import dev.pocketengine.store.TaskStore;
import dev.pocketengine.features.local.project.OriginalNodeCompilerDelivery;
import org.json.JSONObject;

/** A comparison receipt for one already-selected original private result. No URI, write or execution authority. */
public final class OriginalTaskOutputPin {
    private OriginalTaskOutputPin(){}
    public static boolean kind(Object value){return "managed_build_output".equals(value)||"remote_result_snapshot".equals(value);}
    private static SecurityException changed(){return new SecurityException("原任务、文件或固定版本已变化，不能把它当作同一原成果");}
    private static String text(JSONObject o,String key){Object v=o.opt(key);if(!(v instanceof String s)||s.isBlank()||s.length()>2048||s.indexOf(0)>=0)throw changed();return s;}
    private static long count(JSONObject o,String key){Object n=o.opt(key);if(!(n instanceof Integer||n instanceof Long))throw changed();
        long size=((Number)n).longValue();if(size<0||size>AttachmentBook.MAX_FILE_BYTES)throw changed();return size;}
    /** Host supplied the current private task + real Book metadata after requireReference; the displayed row can only narrow this selection. */
    public static JSONObject checked(JSONObject task,JSONObject meta,JSONObject shown)throws Exception{
        String taskId=text(task,"id"),id=text(meta,"id"),sha=text(meta,"sha256");long bytes=count(meta,"size");JSONObject source=meta.getJSONObject("source");String origin=text(source,"kind");
        if(!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||!"owner".equals(meta.opt("principalId"))||!"private".equals(meta.opt("spaceId"))||
                task.optBoolean("contextWithdrawn")||Boolean.FALSE.equals(task.opt("contextAvailable"))||task.optBoolean("sourceCurrentReadRequired")||"source_unavailable".equals(task.opt("taskProjection"))||
                !task.isNull("teamRunId")||!task.isNull("memberId")||meta.optBoolean("outputSourceRetired")||source.has("groupSpaceId")||!kind(origin)||!taskId.equals(source.opt("taskId"))||!taskId.equals(shown.opt("sourceTaskId"))||
                !id.matches("[a-f0-9-]{36}")||!id.equals(shown.opt("id"))||!sha.matches("[a-f0-9]{64}")||!sha.equals(shown.opt("sha256"))||bytes!=count(shown,"size")||
                !origin.equals(shown.opt("sourceKind"))||!"result".equals(shown.opt("role"))||!"available".equals(shown.opt("availability")))throw changed();
        JSONObject from=task.optJSONObject("origin");if(from!=null&&from.optString("kind").matches("group|channel|reviewed_external_group|team.*"))throw changed();
        String op=text(source,"operationId");if(!op.equals(shown.opt("sourceOperationId")))throw changed();
        JSONObject pin=new JSONObject().put("taskId",taskId).put("id",id).put("sourceKind",origin).put("sha256",sha).put("bytes",bytes).put("operationId",op);
        if(origin.equals("managed_build_output")){
            if(!text(source,"runId").equals(shown.opt("sourceTaskRunId"))||!text(source,"planDigest").matches("[a-f0-9]{64}"))throw changed();
            pin.put("runId",text(source,"runId")).put("planDigest",text(source,"planDigest"));
            if(OriginalNodeCompilerDelivery.claims(source)){
                // The caller has already matched the complete native save row and Book source.
                // This added pin compares the same saved output across the existing export picker.
                if(!OriginalNodeCompilerDelivery.PROVENANCE.equals(shown.opt("sourceRunEvidence")))
                    throw changed();
                pin.put("artifactPlanDigest",text(source,"artifactPlanDigest"))
                    .put("originalCompilerWitness",source.getJSONObject("originalCompilerWitness"));
            }else if(source.has("projectId")||source.has("buildJobId")){
                Object sequence=source.opt("buildCheckoutSequence");if(source.has("producer")||!"selected_output_observed_after_build".equals(source.opt("buildOutputAttribution"))||
                        !(sequence instanceof Integer||sequence instanceof Long)||((Number)sequence).longValue()<=0)throw changed();
                for(String k:new String[]{"projectId","checkoutId","path","sourceDigest","buildJobId","buildReceiptIdentity","artifactPlanDigest"})pin.put(k,text(source,k));
                pin.put("buildCheckoutSequence",source.getLong("buildCheckoutSequence"));
            }else{
                if(!"original_managed_skill".equals(source.opt("producer")))throw changed();
                pin.put("producer","original_managed_skill").put("outputPath",text(source,"outputPath"));
            }
        }else{
            // Archive records carry a native jobOperationId but not necessarily a runId. Never manufacture a current-run association.
            if(!Boolean.TRUE.equals(source.opt("immutableArtifact"))||bytes!=count(source,"bytes")||!sha.equals(source.opt("sha256")))throw changed();
            for(String k:new String[]{"jobOperationId","jobId","snapshotId","snapshotVersion","fileVersion","path","workspaceIdentity"})pin.put(k,text(source,k));
        }
        return new JSONObject().put("id",id).put("sha256",sha).put("bytes",bytes).put("identity",TaskStore.capabilityFingerprint(pin));
    }
}
