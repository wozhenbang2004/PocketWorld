package dev.pocketengine.features.memory;

import org.json.JSONArray;
import org.json.JSONObject;

/** The native original operation may record only the effect its reviewed memory action permits. */
public final class ConversationMemoryReceipt {
    private ConversationMemoryReceipt(){}
    public static void require(JSONObject proposal,JSONObject receipt)throws Exception {
        String action=proposal.getString("action"),phase=receipt.getString("status");
        if(!action.matches("create|update|forget")||!action.equals(receipt.optString("action")))throw new SecurityException("原记忆回执动作不符");
        if("unknown".equals(phase)){
            for(String field:new String[]{"accepted","mutationApplied","formalMemorySaved"})if(!receipt.has(field)||!receipt.isNull(field))throw new SecurityException("未知记忆结果不能称已保存或未修改");return;
        }
        if("rejected".equals(phase)){
            for(String field:new String[]{"accepted","mutationApplied","formalMemorySaved"})if(!Boolean.FALSE.equals(receipt.opt(field)))throw new SecurityException("未进入修改的记忆回执不能称已保存");return;
        }
        if(!"saved".equals(phase)||!Boolean.TRUE.equals(receipt.opt("accepted"))||!Boolean.TRUE.equals(receipt.opt("mutationApplied"))||!Boolean.valueOf(!action.equals("forget")).equals(receipt.opt("formalMemorySaved")))throw new SecurityException("原记忆保存事实与阶段不一致");
        if(action.equals("forget")){if(receipt.has("record")||positive(receipt,"deleted")<1)throw new SecurityException("原遗忘缺少确定删除回执");return;}
        JSONObject record=receipt.getJSONObject("record");if(!record.optString("id").matches("[a-f0-9-]{36}"))throw new SecurityException("原记忆保存缺少确切记录身份");long revision=positive(record,"revision");
        if(action.equals("create")){if(revision!=1||!proposal.getJSONObject("candidate").getString("id").equals(record.optString("learningCandidateId")))throw new SecurityException("原新候选不能以其他正式记忆或修订冒充本次采用");}
        else {JSONObject target=proposal.getJSONObject("target");if(!target.getString("id").equals(record.getString("id"))||revision!=Math.addExact(positive(target,"revision"),1))throw new SecurityException("原纠正回执不属于被审阅记录的下一修订");}
    }
    /** Original save identity only. The host must separately read the current record before undo. */
    public static JSONObject createdRecordForUndo(JSONObject proposal,JSONObject applied)throws Exception {
        if(!"create".equals(proposal.optString("action"))||!"saved".equals(applied.optString("status"))
                ||!Boolean.TRUE.equals(applied.opt("accepted"))||!Boolean.TRUE.equals(applied.opt("mutationApplied"))||!Boolean.TRUE.equals(applied.opt("formalMemorySaved")))
            throw new SecurityException("仅本次新增且已确认保存的记忆可以撤销");
        JSONObject record=applied.getJSONObject("record");String candidate=proposal.getJSONObject("candidate").getString("id");
        if(!record.optString("id").matches("[a-f0-9-]{36}")||positive(record,"revision")!=1||!candidate.matches("[a-f0-9-]{36}")
                ||!candidate.equals(record.optString("learningCandidateId")))throw new SecurityException("原新增记忆身份不可核对");
        return new JSONObject().put("id",record.getString("id")).put("revision",1).put("learningCandidateId",candidate);
    }
    /** Undo has its own durable outcome; it never changes the earlier save fact. */
    public static void requireUndo(JSONObject proposal,JSONObject applied,JSONObject receipt)throws Exception {
        JSONObject target=createdRecordForUndo(proposal,applied),record=receipt.getJSONObject("record");String phase=receipt.getString("status");
        if(!"create".equals(receipt.optString("action"))||!target.getString("id").equals(record.optString("id"))||positive(record,"revision")!=1
                ||!target.getString("learningCandidateId").equals(record.optString("learningCandidateId"))||!Boolean.FALSE.equals(receipt.opt("undoAvailable")))throw new SecurityException("撤销回执不属于原新增记忆");
        if("unknown".equals(phase)||"undoing_unknown".equals(phase)){
            if(!receipt.has("undone")||!receipt.isNull("undone")||!receipt.has("mutationApplied")||!receipt.isNull("mutationApplied")||receipt.has("deleted"))throw new SecurityException("未知撤销不能声称已经删除或尚未修改");return;
        }
        if("rejected".equals(phase)){
            if(!Boolean.FALSE.equals(receipt.opt("undone"))||!Boolean.FALSE.equals(receipt.opt("mutationApplied"))||receipt.has("deleted"))throw new SecurityException("未进入撤销不能声称已删除");return;
        }
        if(!"reverted".equals(phase)||!Boolean.TRUE.equals(receipt.opt("undone"))||!Boolean.TRUE.equals(receipt.opt("mutationApplied"))||positive(receipt,"deleted")<1)throw new SecurityException("原撤销缺少确定删除回执");
        JSONArray removed=receipt.getJSONArray("removedRefs");boolean exact=false;
        for(int i=0;i<removed.length();i++){JSONObject ref=removed.getJSONObject(i);if(target.getString("id").equals(ref.optString("recordId"))&&positive(ref,"revision")==1
                &&"owner".equals(ref.optString("ownerId"))&&"private".equals(ref.optString("spaceId")))exact=true;}
        if(!exact)throw new SecurityException("撤销回执没有包含原记录的确切修订");
    }
    private static long positive(JSONObject row,String key)throws Exception {Object raw=row.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1)throw new SecurityException("原记忆回执数字无效");return ((Number)raw).longValue();}
}
