package dev.pocketengine.features.teams;

import org.json.JSONObject;

/** Numbers for the narrow existing-draft handoff, not a permission, reservation or independent estimate. */
public final class TeamLocalHandoffBudget {
    private TeamLocalHandoffBudget() {}
    public static JSONObject inherit(JSONObject facts,JSONObject draft,JSONObject pool,JSONObject parent)throws Exception {
        int old=draft.getInt("remainingOperations"),toolLimit=pool.getInt("toolOperationLimit"),modelLimit=pool.getInt("modelRequestLimit");
        int tools=pool.getInt("remainingToolOperations"),models=pool.getInt("remainingModelRequests"),parentLeft=parent.getInt("remainingOperations");
        if(tools<0||tools>toolLimit||models<1||models>modelLimit||old<1||parentLeft<1)throw new SecurityException("原父池或草稿不再有可核对的剩余额度，未补充任何权限");
        int carry=Math.min(old,Math.min(tools,parentLeft));
        if(carry<2)throw new SecurityException("原草稿和父池的余额不足以接收并冻结文件；没有重置或补额度");
        return facts.put("parent",parent).put("carryLocalRemainder",true).put("oldDraftRemaining",old)
                .put("carryAllowDelete",draft.getBoolean("allowDelete")).put("proposedDraftOperations",carry)
                .put("proposedModelRequestLimit",modelLimit).put("proposedToolOperationLimit",toolLimit)
                .put("parentOperationShortfall",0).put("parentBudgetIncreaseRequired",false).put("budgetChanged",false)
                .put("draftOperationsAllocatedFromReviewedParentRemaining",true)
                .put("budgetPlan",new JSONObject().put("source","old_item_remainder").put("originalDraftRemaining",old)
                        .put("carriedDraftOperations",carry).put("autoRefill",false));
    }
}
