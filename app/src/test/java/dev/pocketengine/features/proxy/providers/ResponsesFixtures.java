package dev.pocketengine.features.proxy.providers;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic Responses SSE, never a service call or tool execution. */
public final class ResponsesFixtures {
    private int sequence;
    private final StringBuilder stream=new StringBuilder();
    public ResponsesFixtures()throws Exception{add(new JSONObject().put("type","response.created").put("response",new JSONObject().put("id","resp_fixture").put("status","in_progress")));}
    public ResponsesFixtures add(JSONObject event)throws Exception{event.put("sequence_number",sequence++);String json=event.toString();StringBuilder escaped=new StringBuilder();for(int i=0;i<json.length();i++){char c=json.charAt(i);if(Character.isSurrogate(c))escaped.append(String.format("\\u%04x",(int)c));else escaped.append(c);}stream.append("event: ").append(event.getString("type")).append("\ndata: ").append(escaped).append("\n\n");return this;}
    public ResponsesFixtures item(int index,JSONObject item,boolean done)throws Exception{return add(new JSONObject().put("type",done?"response.output_item.done":"response.output_item.added").put("output_index",index).put("item",item));}
    public ResponsesFixtures argument(int index,String id,String value,boolean done)throws Exception{return add(new JSONObject().put("type",done?"response.function_call_arguments.done":"response.function_call_arguments.delta").put("output_index",index).put("item_id",id).put(done?"arguments":"delta",value));}
    public ResponsesFixtures text(int index,String id,String value,boolean refusal)throws Exception{
        String type=refusal?"refusal":"output_text",field=refusal?"refusal":"text";
        add(new JSONObject().put("type","response.content_part.added").put("output_index",index).put("item_id",id).put("content_index",0).put("part",new JSONObject().put("type",type).put(field,"")));
        add(new JSONObject().put("type",refusal?"response.refusal.delta":"response.output_text.delta").put("output_index",index).put("item_id",id).put("content_index",0).put("delta",value));
        add(new JSONObject().put("type",refusal?"response.refusal.done":"response.output_text.done").put("output_index",index).put("item_id",id).put("content_index",0).put(field,value));
        add(new JSONObject().put("type","response.content_part.done").put("output_index",index).put("item_id",id).put("content_index",0).put("part",new JSONObject().put("type",type).put(field,value)));return this;
    }
    public ResponsesFixtures complete(JSONArray output)throws Exception{return add(new JSONObject().put("type","response.completed").put("response",new JSONObject().put("id","resp_fixture").put("status","completed").put("output",output).put("usage",new JSONObject().put("input_tokens",4).put("output_tokens",6).put("total_tokens",10))));}
    @Override public String toString(){return stream.toString();}
    public static JSONObject function(String id,String call,String name,String args,boolean done)throws Exception{return new JSONObject().put("type","function_call").put("id",id).put("call_id",call).put("namespace","phone").put("name",name).put("arguments",args).put("status",done?"completed":"in_progress");}
    public static JSONObject message(String id,String value,boolean refusal,boolean done)throws Exception{return new JSONObject().put("type","message").put("id",id).put("role","assistant").put("status",done?"completed":"in_progress").put("content",done?new JSONArray().put(new JSONObject().put("type",refusal?"refusal":"output_text").put(refusal?"refusal":"text",value)):new JSONArray());}
    public static JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","namespace").put("name","phone").put("description","Host-approved phone operations only").put("tools",new JSONArray().put(declaration("observe")).put(declaration("tap"))));}
    public static JSONObject declaration(String name)throws Exception{return new JSONObject().put("type","function").put("name",name).put("description","Fixture declaration, no execution").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false)).put("strict",false);}
    public static ResponsesRequest request()throws Exception{return new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","Observe only")),tools());}
    public static String oneCall()throws Exception{JSONObject call=function("fc_0","call_0","observe","{}",true);return new ResponsesFixtures().item(0,function("fc_0","call_0","observe","",false),false).argument(0,"fc_0","{}",false).argument(0,"fc_0","{}",true).item(0,call,true).complete(new JSONArray().put(call)).toString();}
}
