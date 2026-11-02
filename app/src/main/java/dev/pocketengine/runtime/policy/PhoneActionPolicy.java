package dev.pocketengine.runtime.policy;

import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner completes financial commitments on the real device; ordinary actions keep their existing scope. */
public final class PhoneActionPolicy {
    private PhoneActionPolicy(){}
    private static final Set<String> ACTIONS=Set.of("phone_click_node","phone_tap","phone_type","phone_press_key","phone_gesture");
    private static final String COMMIT="(?s).*(确认支付|立即支付|立即付款|确认付款|确认转账|立即转账|确认汇款|支付密码|付款密码|确认购买|提交订单并支付|pay now|confirm payment|confirm transfer|send payment|complete purchase|place order and pay).*";
    public static boolean financialCommit(String text){return text!=null&&text.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").matches(COMMIT);}
    public static JSONObject before(String tool,JSONObject args,JSONObject observation)throws Exception {
        if(!ACTIONS.contains(tool)||observation==null)return null;
        JSONObject target=find(observation.optJSONArray("nodes"),args.optString("nodeId"));
        boolean targetCommit=target!=null&&(financialCommit(target.optString("text"))||financialCommit(target.optString("contentDescription"))||financialCommit(target.optString("description")));
        boolean financialPage=observation.optBoolean("financialCommitmentRequired")||financialCommit(observation.optString("activityName"))||containsFinancial(observation.optJSONArray("nodes"));
        boolean unknownTarget=target==null&&(tool.equals("phone_tap")||tool.equals("phone_press_key")||tool.equals("phone_gesture")||tool.equals("phone_type"));
        boolean privateFinancialInput=tool.equals("phone_type")&&financialPage&&target!=null&&target.optBoolean("password");
        if(!targetCommit&&!privateFinancialInput&&!(financialPage&&unknownTarget))return null;
        return new JSONObject().put("ok",false).put("accepted",false).put("code","payment_owner_action_required")
                .put("effectStatus","not_dispatched").put("policy","owner_completes_financial_commitment")
                .put("message","当前动作可能确认付款、转账或输入支付密码，已在派发前停下。请由你在真实页面核对对象和金额并亲自完成，再继续原任务。")
                .put("requiresOwner",true).put("approvalGranted",false);
    }
    private static boolean containsFinancial(JSONArray nodes){if(nodes==null)return false;for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null&&(financialCommit(n.optString("text"))||financialCommit(n.optString("contentDescription"))||containsFinancial(n.optJSONArray("children"))))return true;}return false;}
    private static JSONObject find(JSONArray nodes,String id){if(nodes==null||id.isEmpty())return null;for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null)continue;if(id.equals(n.optString("id")))return n;JSONObject child=find(n.optJSONArray("children"),id);if(child!=null)return child;}return null;}
    /** Route learning excludes commitment/edit controls even if the current owner authorized a one-off action. */
    public static boolean routeNavigationLabel(String label){
        String s=label==null?"":label.trim().toLowerCase(Locale.ROOT);
        return s.matches("首页|主页|搜索|设置|返回|下一页|上一页|展开|收起|详情|菜单|通知|消息|我的|home|search|settings|back|next|previous|details|menu|notifications|messages|profile");
    }
}
