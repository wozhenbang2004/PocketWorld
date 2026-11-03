package dev.pocketengine.features.memory;

import java.util.Set;
import org.json.JSONObject;

/** The reviewed candidate's explanation, not the owner's motive or evidence of effectiveness.
 * Lives with its original Book row and is removed when that row's meaning is corrected. */
final class MemoryLearningRationale {
    private static final String FORMAT="pocketworld.memory-learning-rationale.v1";
    private static final Set<String> FIELDS=Set.of("format","candidateId","candidateRevision","contentDigest","reason","kind","validationStatus");
    private MemoryLearningRationale(){}

    static JSONObject accepted(JSONObject candidate,JSONObject record)throws Exception {
        return new JSONObject().put("format",FORMAT).put("candidateId",candidate.getString("id"))
                .put("candidateRevision",candidate.getLong("revision")).put("contentDigest",contentDigest(record))
                .put("reason",candidate.getString("reason")).put("kind","reviewed_candidate_explanation").put("validationStatus","unverified");
    }

    static void checkStored(JSONObject record)throws Exception {
        if(!record.has("learningRationale"))return;
        JSONObject value=record.getJSONObject("learningRationale");
        MemoryArchive.keys(value,FIELDS);
        if(value.length()!=FIELDS.size()||!FORMAT.equals(value.optString("format"))
                ||!"reviewed_candidate_explanation".equals(value.optString("kind"))||!"unverified".equals(value.optString("validationStatus"))
                ||!value.optString("candidateId").matches("[0-9a-f-]{36}")
                ||!value.getString("candidateId").equals(record.optString("learningCandidateId"))
                ||!value.optString("contentDigest").matches("[0-9a-f]{64}"))throw new IllegalArgumentException("学习说明缺少原候选身份或被误标为事实证明");
        SourceRef.integer(value,"candidateRevision",1);
        Object reason=value.opt("reason");
        if(!(reason instanceof String)||((String)reason).isBlank()||((String)reason).length()>MemoryBook.MAX_CANDIDATE_REASON_CHARS)
            throw new IllegalArgumentException("学习说明文字长度无效");
        MemoryBook.validateUnicode((String)reason);
        if(!value.getString("contentDigest").equals(contentDigest(record)))throw new IllegalArgumentException("学习说明属于另一版内容，不能用于解释当前记忆");
    }

    static boolean matches(JSONObject previous,JSONObject next)throws Exception {
        return previous.has("learningRationale")&&previous.getJSONObject("learningRationale").getString("contentDigest").equals(contentDigest(next));
    }

    private static String contentDigest(JSONObject record)throws Exception {
        JSONObject content=new JSONObject().put("text",record.getString("text")).put("layer",record.getString("layer"))
                .put("applicability",record.optString("applicability",""));
        if(record.has("assertion"))content.put("assertion",record.getJSONObject("assertion"));
        return TaskMemoryUse.digest(MemoryArchive.canonical(content));
    }
}
