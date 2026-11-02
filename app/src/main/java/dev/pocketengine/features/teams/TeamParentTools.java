package dev.pocketengine.features.teams;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed schemas/validation only; names and JSON are not delegation authority. */
public final class TeamParentTools {
    private TeamParentTools(){}
    public static JSONObject parameters(String name)throws Exception {
        JSONObject p;String[] required;
        switch(name){
            case "teams_plan":p=props("teamId",text(80),"goal",text(8000),"workItems",array(new JSONObject().put("type","object").put("additionalProperties",false).put("properties",props("key",text(40),"title",text(120),"instruction",text(2000),"acceptance",text(1200),"memberId",text(80),"paths",array(text(240),1,8),"dependsOn",array(text(40),0,7))).put("required",new JSONArray(new String[]{"key","title","instruction","acceptance","memberId","paths","dependsOn"})),1,8),"modelRequestLimit",integer(1,64),"toolOperationLimit",integer(1,32));required=new String[]{"teamId","goal","workItems"};break;
            case "teams_start":p=props("teamId",text(80),"goal",text(8000),"rounds",integer(1,2),"mode",text(20),"planRef",text(80),"planHash",text(64));required=new String[]{};break;
            case "teams_get":p=props("teamRunId",text(80),"view",choice("status","members","events","artifacts","output","question","review","integration","research_summary","member_output"),"afterEvent",integer(0,Integer.MAX_VALUE),"limit",integer(1,16),"artifactId",text(80),"assignmentId",text(80),"questionId",text(80),"memberId",text(80),"researchRound",integer(1,6),"expectedOutputHash",text(64),"offset",integer(0,1000000),"length",integer(1,8000));required=new String[]{"teamRunId"};break;
            case "teams_wait":p=props("teamRunId",text(80),"afterEvent",integer(0,Integer.MAX_VALUE),"maxWait",integer(0,20000));required=new String[]{"teamRunId","afterEvent"};break;
            case "teams_message":p=props("teamRunId",text(80),"memberId",text(80),"recipients",array(new JSONObject().put("type","object").put("additionalProperties",false).put("properties",props("memberId",text(80),"expectedMemberGeneration",integer(0,Integer.MAX_VALUE),"identityRevision",integer(1,Integer.MAX_VALUE))).put("required",new JSONArray(new String[]{"memberId","expectedMemberGeneration","identityRevision"})),1,4),"expectedTeamRevision",integer(1,Integer.MAX_VALUE),"expectedGeneration",integer(0,Integer.MAX_VALUE),"expectedMemberGeneration",integer(0,Integer.MAX_VALUE),"delivery",choice("queue","continue"),"messageId",text(80),"text",text(4000),"quoteMessageId",text(80),"assignmentId",text(80),"questionId",text(80),"answerId",text(80),"answer",text(4000),"researchRound",integer(1,6),"workItemId",text(80),"itemRevision",integer(1,8),"ownerGeneration",integer(0,Integer.MAX_VALUE));required=new String[]{"teamRunId","expectedTeamRevision","expectedGeneration","delivery"};break;
            case "teams_control":p=props("teamRunId",text(80),"action",choice("pause","cancel","resume","reassign","revise","request_review","request_integration"),"expectedTeamRevision",integer(1,Integer.MAX_VALUE),"expectedGeneration",integer(0,Integer.MAX_VALUE),"memberId",text(80),"expectedMemberGeneration",integer(0,Integer.MAX_VALUE),"researchRound",integer(1,6),"workItemId",text(80),"itemRevision",integer(1,8),"ownerGeneration",integer(0,Integer.MAX_VALUE),"newMemberId",text(80),"expectedPlanHash",text(64),"text",text(4000));required=new String[]{"teamRunId","action","expectedTeamRevision","expectedGeneration"};break;
            default:throw new SecurityException("未知团队模型工具");
        }
        return new JSONObject().put("type","object").put("properties",p).put("required",new JSONArray(required)).put("additionalProperties",false);
    }
    public static void validate(String name,JSONObject args)throws Exception {
        if(name.equals("teams_plan")){JSONArray items=args.getJSONArray("workItems");if(items.length()<1||items.length()>8)throw new IllegalArgumentException("工作项需1至8项，不能为空");for(int i=0;i<items.length();i++){JSONArray paths=items.getJSONObject(i).getJSONArray("paths");if(paths.length()<1||paths.length()>8)throw new IllegalArgumentException("每项需1至8个路径");}}
        if(name.equals("teams_start")){if(args.has("planRef")){if(!args.has("planHash")||args.length()!=2)throw new SecurityException("计划启动只接受原planRef+planHash，不重传或替换原内容");}else if(!args.has("teamId")||!args.has("goal"))throw new IllegalArgumentException("需原teamId/goal，或已审planRef/planHash");}
        if(name.equals("teams_message")){
            if(args.has("recipients")){TeamParentBroadcast.validate(args);return;}
            need(args,"memberId");need(args,"expectedMemberGeneration");
            if(args.has("questionId")){for(String key:new String[]{"assignmentId","answerId","answer"})need(args,key);if(args.has("text")||args.has("messageId"))throw new IllegalArgumentException("回答原问题不能夹带第二条普通消息");}
            else {need(args,"messageId");need(args,"text");if(args.has("answer")||args.has("answerId"))throw new IllegalArgumentException("回答需要精确原questionId");}
            if(args.has("researchRound")&&(args.has("workItemId")||args.has("itemRevision")||args.has("ownerGeneration")||args.has("assignmentId")||args.has("questionId")))throw new SecurityException("研究阶段不能夹带制作工作项身份");
            if(args.optString("delivery").equals("continue")&&!args.has("questionId")&&!args.has("researchRound"))for(String key:new String[]{"workItemId","itemRevision","ownerGeneration"})need(args,key);
        }
        if(name.equals("teams_control")){
            String action=args.getString("action");Set<String> extra=action.equals("pause")||action.equals("cancel")?Set.of("memberId","expectedMemberGeneration"):action.equals("resume")?(args.has("researchRound")?Set.of("memberId","expectedMemberGeneration","researchRound"):Set.of("memberId","expectedMemberGeneration","workItemId","itemRevision","ownerGeneration")):action.equals("reassign")?Set.of("workItemId","itemRevision","ownerGeneration","newMemberId"):action.equals("revise")?Set.of("workItemId","itemRevision","ownerGeneration","text"):Set.of("expectedPlanHash");
            for(java.util.Iterator<String> keys=args.keys();keys.hasNext();){String key=keys.next();if(!Set.of("teamRunId","action","expectedTeamRevision","expectedGeneration").contains(key)&&!extra.contains(key))throw new SecurityException("该阶段控制不能夹带其他动作参数");}
            if(!action.equals("pause")&&!action.equals("cancel"))for(String key:extra)need(args,key);if(args.has("memberId"))need(args,"expectedMemberGeneration");
        }
        if(name.equals("teams_get")){String view=args.optString("view","status");Set<String> specific=view.equals("research_summary")?Set.of("offset","length","expectedOutputHash"):view.equals("member_output")?Set.of("memberId","researchRound","offset","length","expectedOutputHash"):view.equals("output")?Set.of("artifactId","offset","length"):view.equals("question")?Set.of("assignmentId","questionId","offset","length"):view.equals("events")?Set.of("afterEvent","limit"):Set.of();for(java.util.Iterator<String> keys=args.keys();keys.hasNext();){String key=keys.next();if(!Set.of("teamRunId","view").contains(key)&&!specific.contains(key))throw new SecurityException("此读取视图不能夹带其他来源或范围参数");}if(view.equals("output"))need(args,"artifactId");if(view.equals("member_output")){need(args,"memberId");need(args,"researchRound");}if(view.matches("research_summary|member_output")&&args.optInt("offset",0)>0)need(args,"expectedOutputHash");if(view.equals("question")){need(args,"assignmentId");need(args,"questionId");}}
    }
    private static void need(JSONObject args,String key){if(!args.has(key)||args.isNull(key))throw new IllegalArgumentException("缺少本动作准确参数："+key);}
    private static JSONObject props(Object...values)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    private static JSONObject integer(int min,int max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    private static JSONObject array(JSONObject items,int min,int max)throws Exception{return new JSONObject().put("type","array").put("items",items).put("maxItems",max);}
    private static JSONObject choice(String...choices)throws Exception{return new JSONObject().put("type","string").put("enum",new JSONArray(choices));}
}
