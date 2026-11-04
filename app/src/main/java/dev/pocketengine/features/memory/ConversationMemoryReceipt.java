package dev.pocketengine.features.memory;

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
        if(action.equals("create")){if(revision!=1)throw new SecurityException("原新候选不能以其他正式修订冒充本次采用");}
        else {JSONObject target=proposal.getJSONObject("target");if(!target.getString("id").equals(record.getString("id"))||revision!=Math.addExact(positive(target,"revision"),1))throw new SecurityException("原纠正回执不属于被审阅记录的下一修订");}
    }
    private static long positive(JSONObject row,String key)throws Exception {Object raw=row.get(key);if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1)throw new SecurityException("原记忆回执数字无效");return ((Number)raw).longValue();}
}
