package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** What one original file operation actually shows. Neither permission nor dispatch lives here. */
public final class TeamParentFileTrail {
    private TeamParentFileTrail() {}
    private static final String UUID_PATTERN="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

    private static JSONObject selected(JSONArray rows, JSONObject args) throws Exception {
        JSONObject match = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject file = rows.getJSONObject(i);
            if (!file.getString("path").equals(args.opt("path")) || !file.getString("artifactId").equals(args.opt("artifactId"))) continue;
            if (match != null) throw new IllegalStateException("原文件记录有歧义，未选择近似回执");
            match = file;
        }
        return match;
    }

    /** raw is one native SQL row for team_integrate_file on this unique child; never export raw bodies. */
    public static JSONObject observe(JSONObject binding, JSONObject progress, JSONArray approvals, JSONObject raw, int epoch) throws Exception {
        String run = raw.getString("runId"), operation = raw.getString("operationId");
        if (epoch < 1 || !"team_integrate_file".equals(raw.opt("tool")) || !operation.matches(UUID_PATTERN)) return null;
        JSONObject args = raw.optJSONObject("arguments");
        if (args == null || args.length()!=2) return null;
        JSONObject plan = binding.getJSONObject("plan"), file = selected(plan.getJSONArray("files"), args);
        if (file == null) return null; // A previous revision's paths and artifacts are not this view's evidence.
        JSONObject head = selected(progress.getJSONArray("files"), args);
        if (head == null) return null;
        boolean current = run.equals(binding.getString("runId")) && epoch == binding.getInt("coordinatorEpoch");
        JSONObject row = new JSONObject().put("operationId",operation).put("runId",run).put("epoch",epoch)
                .put("path",file.getString("path")).put("artifactId",file.getString("artifactId"))
                .put("memberId",file.getString("memberId")).put("assignmentId",file.getString("assignmentId"));
        String state = raw.getString("state");
        JSONObject receipt = raw.optJSONObject("receipt");
        if ("receipt".equals(state) && receipt != null && Boolean.TRUE.equals(receipt.opt("ok"))) {
            JSONObject saved = receipt.optJSONObject("teamIntegrated"), retained = receipt.optJSONObject("teamRetained");
            if (saved != null && retained != null) return null;
            if (retained != null) {
                if (!binding.getString("reviewId").equals(retained.opt("reviewId")) || !binding.getString("planHash").equals(retained.opt("planHash"))
                        || !binding.getString("taskId").equals(retained.opt("integrationTaskId"))) return null;
                saved = retained.optJSONObject("teamIntegrated");
                JSONArray original = plan.optJSONArray("retained"); boolean found = false;
                for (int i=0; saved!=null && original!=null && i<original.length(); i++)
                    if (TeamWorkFiles.canonical(saved).equals(TeamWorkFiles.canonical(original.getJSONObject(i)))) found = true;
                if (!found) return null;
            }
            if (saved==null || !binding.getString("teamRunId").equals(saved.opt("teamRunId"))
                    || !TeamWorkReview.sameArtifact(file,saved,binding.getString("taskId"),binding.getString("targetProjectId"))
                    || !saved.optString("integrationRunId").matches(UUID_PATTERN) || !saved.optString("revisionId").matches(UUID_PATTERN) || saved.optInt("version",0)<1) return null;
            if (retained==null && (!binding.getString("reviewId").equals(saved.opt("reviewId")) || !binding.getString("planHash").equals(saved.opt("planHash"))
                    || !file.getString("expectedHash").equals(saved.opt("expectedHash")) || file.getInt("expectedVersion")!=saved.optInt("expectedVersion",-1)
                    || (long)file.getInt("expectedVersion")+1!=saved.getLong("version"))) return null;
            boolean readback = retained!=null || Boolean.TRUE.equals(receipt.opt("known"));
            if (!readback && (!run.equals(saved.opt("integrationRunId")) ||
                    Boolean.TRUE.equals(receipt.opt("recoveredFromOriginalVersion")) && !operation.equals(receipt.opt("operationId")))) return null;
            String comparison = "not_verified";
            if (head.getInt("currentVersion")!=saved.getInt("version") || !head.optString("currentRevisionId").equals(saved.opt("revisionId"))) comparison="different_revision";
            else if (head.optString("state").matches("applied|retained") && head.optJSONObject("teamIntegrated")!=null &&
                    TeamWorkFiles.canonical(head.getJSONObject("teamIntegrated")).equals(TeamWorkFiles.canonical(saved))) comparison="same_verified_revision";
            // The modern native CAS embeds this precise SQL operation and attempt in its revision UUID.
            // Legacy/fallback random revisions keep their real journal details but cannot prove that link.
            boolean operationBound = !readback && TeamIntegrationFileRecovery.revisionId(new JSONObject(binding.toString())
                    .put("runId",run).put("coordinatorEpoch",epoch),operation,file).toString().equals(saved.getString("revisionId"));
            String effect=readback?"observed_previous_write_no_new_write":operationBound?"historical_write_verified":"write_recorded_without_operation_bound_revision";
            return row.put("effect",effect).put("fileChange",TeamWorkReview.isDeletion(file)?"deleted":"saved")
                    .put("writtenInRunId",saved.getString("integrationRunId")).put("writtenVersion",saved.getInt("version"))
                    .put("writtenRevisionId",saved.getString("revisionId")).put("writtenHash",saved.getString("sha256"))
                    .put("recoveredFromOriginalVersion",!readback && Boolean.TRUE.equals(receipt.opt("recoveredFromOriginalVersion")))
                    .put("headAtRead",comparison).put("observedHeadVersion",head.getInt("currentVersion"));
        }
        // A replacement can update the task's assignment BEFORE there is a new SQL run. A
        // matching path/run alone does not associate a negative or unknown with that later review.
        JSONObject capability=TeamIntegrationFiles.fileCapability(binding,file);
        if ("receipt".equals(state) && receipt!=null && Boolean.FALSE.equals(receipt.opt("ok")) && Boolean.FALSE.equals(receipt.opt("accepted")) &&
                Boolean.FALSE.equals(receipt.opt("dispatch")) && receipt.optString("code").matches("approval_required|approved_file_requires_owner_continuation") &&
                receipt.optString("approvalState").matches("pending|approved|denied")) {
            JSONObject decision=null;String id=receipt.optString("approvalId");
            for(int i=0;id.matches(UUID_PATTERN)&&approvals!=null&&i<approvals.length();i++){
                JSONObject a=approvals.getJSONObject(i);if(!id.equals(a.opt("id")))continue;
                if(decision!=null)return null;decision=a;
            }
            if(decision!=null && "team_integrate_file".equals(decision.opt("tool")) && TeamWorkFiles.canonical(capability).equals(decision.opt("capabilityKey")) &&
                    dev.pocketengine.store.TaskStore.capabilityFingerprint(args).equals(decision.opt("argumentsHash"))) {
                JSONObject predecessor=decision.optJSONObject("teamFileApprovalOrigin");
                if(run.equals(decision.opt("runId")) || predecessor!=null && run.equals(predecessor.opt("runId")) && epoch==predecessor.optInt("epoch",-1) && operation.equals(predecessor.opt("operationId")))
                    return row.put("effect","not_dispatched_for_decision").put("decisionAtOperation",receipt.getString("approvalState"));
            }
        }
        if ("unknown".equals(state) && receipt!=null && "team_operation_pending".equals(receipt.opt("code")) &&
                dev.pocketengine.store.TaskStore.capabilityFingerprint(capability).equals(receipt.opt("capabilityFingerprint")))
            return row.put("effect","effect_unknown_check_original");
        if (!current || plan.has("recovery") || plan.has("retained")) return null;
        if ("receipt".equals(state) && receipt!=null && Boolean.FALSE.equals(receipt.opt("ok")) && Boolean.FALSE.equals(receipt.opt("accepted"))) {
            if ("team_integration_conflict".equals(receipt.opt("code"))) return row.put("effect","original_base_conflict_no_write");
            if ("original_team_file_never_claimed".equals(receipt.opt("code")) && operation.equals(receipt.opt("operationId")) &&
                    Boolean.TRUE.equals(receipt.opt("notDispatchedConfirmed")) && "not_dispatched".equals(receipt.opt("status")))
                return row.put("effect","original_operation_confirmed_not_dispatched");
        }
        // A prepared row alone never proves a write. Other envelopes stay unverified, not failed.
        return row.put("effect","prepared".equals(state)?"prepared_effect_not_verified":"effect_not_verified");
    }
}
