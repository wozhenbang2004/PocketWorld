package dev.pocketengine.features.groups;

import org.json.JSONObject;

/** Original authenticated group message data, never owner or private execution authority. */
public final class GroupTaskInput {
    public final GroupIngressPort.Event event;
    public final String requestId,questionId,content,memberEpoch;
    GroupTaskInput(GroupIngressPort.Event event,String memberEpoch){
        Command command=parse(event.text);this.event=event;requestId=command.request;questionId=command.question;content=command.content;this.memberEpoch=GroupJson.id(memberEpoch,"memberEpoch");
    }
    public JSONObject json()throws Exception{return new JSONObject().put("eventId",event.eventId).put("requestId",requestId).put("questionId",questionId).put("memberEpoch",memberEpoch).put("event",event.json());}
    public static GroupTaskInput restore(JSONObject row)throws Exception{return new GroupTaskInput(GroupIngressPort.Event.from(row.getJSONObject("event")),row.getString("memberEpoch"));}
    public static final class Command {public final String request,question,content;private Command(String request,String question,String content){this.request=request;this.question=question;this.content=content;}}
    public static Command parse(String text){
        String[] parts=text.trim().split("\\s+",4);
        if(parts.length!=4||!parts[0].equals("/reply")||!parts[1].matches("[a-f0-9-]{36}")||!parts[2].matches("[a-f0-9-]{36}")||parts[3].isBlank()||parts[3].length()>4000)throw new IllegalArgumentException("补充格式：/reply 原请求编号 原问题编号 补充文字（最多4000字）；群回复不批准工具");
        return new Command(parts[1],parts[2],parts[3]);
    }
}
