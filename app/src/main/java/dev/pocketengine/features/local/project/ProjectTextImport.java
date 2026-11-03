package dev.pocketengine.features.local.project;

import org.json.JSONObject;

/** Exact text baseline reconciliation, never a retry of an earlier project write. */
public final class ProjectTextImport {
    public static final String REVIEW="pocketworld.local-text-file-review.v1",RECEIPT="pocketworld.local-text-file-import.v1";
    private ProjectTextImport() {}
    /** Copies a fresh matching observation into an explicitly baseline-only native action. */
    public static JSONObject reconciliationPayload(JSONObject review)throws Exception {
        if(!REVIEW.equals(review.optString("format"))||!Boolean.TRUE.equals(review.opt("same"))||!Boolean.TRUE.equals(review.opt("baselineNeedsRefresh"))||!Boolean.TRUE.equals(review.opt("importable"))||review.optBoolean("truncated"))throw new IllegalArgumentException("请重新核对正式版本与副本完全一致，再明确只登记基线");
        JSONObject input=new JSONObject().put("textReviewContract",REVIEW).put("reconcileBaselineOnly",true);
        for(String key:new String[]{"checkoutId","projectId","path","expectedHash","expectedTargetHash"})input.put(key,review.getString(key));
        Object deleted=review.opt("targetDeleted"),version=review.opt("expectedVersion");
        if(!(deleted instanceof Boolean)||!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()<0||((Number)version).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException("基线审阅缺少正式版本或存在状态");
        String hash=review.getString("expectedHash");if(!hash.equals(review.getString("expectedTargetHash"))||!(hash.equals("absent")||hash.matches("[a-f0-9]{64}"))||((Boolean)deleted)!=hash.equals("absent"))throw new IllegalArgumentException("基线审阅的正式与副本SHA/存在状态不一致");
        return input.put("expectedVersion",version).put("expectedTargetDeleted",deleted);
    }
    public static void requireReconciliation(JSONObject input,String project,JSONObject file,JSONObject target)throws Exception {
        if(!REVIEW.equals(input.optString("textReviewContract"))||!Boolean.TRUE.equals(input.opt("reconcileBaselineOnly")))throw new SecurityException("需要本次明确的文字基线登记，不重试原正式写入");
        for(String key:new String[]{"content","contentBase64","bytes"})if(input.has(key))throw new SecurityException("基线登记不接受新正文或原字节写入");
        if(!project.equals(input.getString("projectId"))||!file.getString("sha256").equals(input.getString("expectedHash")))throw new SecurityException("原项目或所选工作副本SHA已变化");
        Object version=input.opt("expectedVersion"),deleted=input.opt("expectedTargetDeleted");
        String targetHash=target.optBoolean("deleted")?"absent":ProjectSources.sha256(target.getString("content").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!(version instanceof Integer||version instanceof Long)||((Number)version).longValue()!=target.getInt("version")||!(deleted instanceof Boolean)||((Boolean)deleted)!=target.optBoolean("deleted")||!targetHash.equals(input.getString("expectedTargetHash"))||file.optBoolean("deleted")!=target.optBoolean("deleted")||!file.getString("sha256").equals(targetHash))throw new SecurityException("正式版本/SHA/存在状态或所选副本已变化，请重新审阅；未写版本或基线");
    }
}
