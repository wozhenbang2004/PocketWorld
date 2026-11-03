package dev.pocketengine.runtime.policy;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Model declarations stay distinct from native operation receipts and business verification. */
public final class RunOutcomeTools {
    public static final String OBSTACLE="run_handle_obstacle",END="run_end_not_done",ASK="run_ask_user";
    private static final Set<String> KINDS=Set.of("popup","login","captcha","access_blocked","payment","missing_information","environment","other");
    private RunOutcomeTools(){}
    public static boolean supports(String tool){return OBSTACLE.equals(tool)||END.equals(tool)||ASK.equals(tool);}
    public static boolean terminal(String tool){return END.equals(tool)||ASK.equals(tool);}
    public static JSONArray tools()throws Exception {
        JSONObject kind=new JSONObject().put("type","string").put("enum",new JSONArray(new String[]{"popup","login","captcha","access_blocked","payment","missing_information","environment","other"}));
        return new JSONArray().put(tool(OBSTACLE,"记录当前障碍类别并取得处理建议；此工具不消除障碍、不绕过验证码或权限，也不派发手机动作。",new JSONObject().put("kind",kind).put("detail",text(2000)),"kind","detail"))
                .put(tool(END,"明确结束为未完成，分别说明已做、未做和阻塞原因；recoverable只是建议，不授权重试，也不覆盖原操作未知状态。",new JSONObject().put("kind",kind).put("reason",text(2000)).put("performed",text(4000)).put("remaining",text(4000)).put("recoverable",new JSONObject().put("type","boolean")),"kind","reason","performed","remaining","recoverable"))
                .put(tool(ASK,"只向主人询问缺少的信息并等待原任务回答；答案是2至5个明确选择时必须通过options提供稳定id与简短label，保留自由回答。选项不用于批准付款、权限或设备接管。",new JSONObject().put("question",text(2000)).put("options",OwnerQuestionChoices.schema()),"question"));
    }
    public static void validate(String name,JSONObject args)throws Exception {
        if(ASK.equals(name)){
            if(!args.has("question")||args.length()!=(args.has("options")?2:1)||!(args.opt("question") instanceof String question)||question.isBlank()||question.length()>2000)
                throw new IllegalArgumentException("普通问题只接受question与可选options，不接受执行身份或权限");
            OwnerQuestionChoices.options(args);return;
        }
        Set<String> allowed=ASK.equals(name)?Set.of("question"):OBSTACLE.equals(name)?Set.of("kind","detail"):END.equals(name)?Set.of("kind","reason","performed","remaining","recoverable"):Set.of();
        if(allowed.isEmpty()||args.length()!=allowed.size())throw new IllegalArgumentException("未完成工具只接受固定字段");for(var k=args.keys();k.hasNext();)if(!allowed.contains(k.next()))throw new IllegalArgumentException("未完成声明不能携带执行身份或权限");
        for(String k:allowed){if(k.equals("recoverable")){if(!(args.opt(k) instanceof Boolean))throw new IllegalArgumentException("recoverable需明确布尔值");continue;}Object v=args.opt(k);int limit=k.equals("performed")||k.equals("remaining")?4000:2000;if(!(v instanceof String)||((String)v).length()>limit||!k.equals("performed")&&((String)v).isBlank())throw new IllegalArgumentException("未完成说明无效或超长");}
        if(!ASK.equals(name)&&!KINDS.contains(args.getString("kind")))throw new IllegalArgumentException("未知障碍类别");
    }
    public static JSONObject obstacle(JSONObject args)throws Exception {
        validate(OBSTACLE,args);String kind=args.getString("kind"),hint=switch(kind){case "login","captcha","payment"->"请调用phone_request_user交由主人在真实页面处理，不代填私密信息或绕过挑战。";case "access_blocked"->"停止访问受限路径，说明限制并请主人选择合法可访问资料。";case "missing_information"->"用run_ask_user提出一个具体问题；回答回原任务。";case "popup"->"重新观察真实弹窗，只有现授权范围内的普通关闭动作可用原工具处理。";default->"核对真实工具回执；无法完成时用run_end_not_done交代已做与未做。";};
        return new JSONObject().put("ok",true).put("kind",kind).put("detail",args.getString("detail")).put("hint",hint).put("obstacleResolved",false).put("effectsDispatched",false);
    }
    public static JSONObject declaration(JSONObject args)throws Exception {validate(END,args);return new JSONObject(args.toString()).put("source","model_declared_not_native_effect_proof").put("businessVerified",false).put("automaticRetryAuthorized",false);}
    public static String summary(JSONObject args)throws Exception{return "本次未完成："+args.getString("reason")+"\n已做："+args.getString("performed")+"\n未做："+args.getString("remaining");}
    private static JSONObject text(int n)throws Exception{return new JSONObject().put("type","string").put("maxLength",n);}
    private static JSONObject tool(String name,String desc,JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",desc).put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false)));}
}
