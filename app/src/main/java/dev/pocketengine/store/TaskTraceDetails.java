package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.runtime.usage.ExecutionTraceCapture;
import org.json.JSONArray;
import org.json.JSONObject;

/** Detail projection over the retained original checkpoint and operation. Caller owns all source gates. */
final class TaskTraceDetails {
    private static final int MAX_SOURCE_BYTES=4*1024*1024;
    private TaskTraceDetails() { }

    static JSONArray messages(SQLiteDatabase db,String task,String run)throws Exception {
        try(Cursor row=db.rawQuery("SELECT length(CAST(checkpoint AS BLOB)) FROM runs WHERE task_id=? AND run_id=?",new String[]{task,run})){
            if(!row.moveToFirst()||row.getLong(0)<0||row.getLong(0)>MAX_SOURCE_BYTES)return null;
        }
        try(Cursor row=db.rawQuery("SELECT checkpoint FROM runs WHERE task_id=? AND run_id=? AND length(CAST(checkpoint AS BLOB))<=CAST(? AS INTEGER)",new String[]{task,run,Integer.toString(MAX_SOURCE_BYTES)})){
            if(!row.moveToFirst())return null;return new JSONObject(row.getString(0)).optJSONArray("messages");
        }
    }
    static JSONArray messages(SQLiteDatabase db,JSONObject node)throws Exception{
        String checkpoint=TeamCoordinatorCheckpoint.checkpoint(node);return checkpoint.isEmpty()?messages(db,node.getString("taskId"),node.getString("runId")):TeamCoordinatorCheckpoint.messages(db,node.getString("taskId"),node.getString("runId"),checkpoint);
    }

    static JSONObject expand(SQLiteDatabase db,JSONObject original,JSONArray messages)throws Exception {
        JSONObject node=new JSONObject(original.toString());String kind=node.getString("kind"),role=node.optString("role");
        if("model".equals(kind)){
            JSONArray refs=node.optJSONArray("sourceRefs"),context=new JSONArray();int missing=0;
            for(int i=0;refs!=null&&i<refs.length();i++){JSONObject message=find(messages,refs.getJSONObject(i));if(message==null)missing++;else context.put(visible(message));}
            if(refs!=null)node.put("input",new JSONObject().put("messages",context).put("missingMessages",missing)
                    .put("contextRefCount",node.optInt("contextRefCount",refs.length())).put("contextRefsRetained",refs.length())
                    .put("contextRefsLimited",node.optBoolean("contextRefsLimited"))
                    .put("meaning","retained_original_checkpoint_context_not_final_wire"));
            if(messages==null)node.put("contextAvailability",new JSONObject().put("available",false).put("reason","original_checkpoint_unavailable_or_read_limited"));
            JSONObject assistant=assistant(messages,node.getString("callId"));
            if(assistant!=null)node.put("output",visible(assistant));
            else node.put("outputAvailability",new JSONObject().put("available",false).put("reason",messages==null?"original_checkpoint_unavailable_or_read_limited":"original_assistant_not_retained"));
        }else if("tool".equals(kind)){
            operation(db,node);
            JSONObject call=toolCall(messages,node.optString("parentCallId"),node.optString("providerToolCallId"));
            if(call!=null&&unavailable(node.opt("input")))node.put("input",parse(call.getJSONObject("function").opt("arguments")));
            JSONObject result=toolResult(messages,node.optString("parentCallId"),node.optString("providerToolCallId"));
            if(result!=null&&unavailable(node.opt("output")))node.put("output",parse(result.opt("content")));
            if(!node.has("schema"))node.put("schemaAvailability",new JSONObject().put("available",false).put("reason","execution_time_schema_not_recorded"));
        }else if(node.optJSONObject("sourceRef")!=null){
            JSONObject message=find(messages,node.getJSONObject("sourceRef"));
            if(message==null)return node.put("contentAvailability",new JSONObject().put("available",false).put("reason",messages==null?"original_checkpoint_unavailable_or_read_limited":"original_message_not_retained_or_changed")).put("bodyIncluded",false);
            JSONObject raw=visible(message);Object content=raw.opt("content");
            node.put("raw",raw).put("message",content==null?"":content instanceof String?content:content.toString())
                    .put("preview",preview(content)).put("contentAvailability",new JSONObject().put("available",true).put("source","original_run_checkpoint"));
            if("assistant".equals(role))node.put("output",raw);else node.put("input",raw);
        }
        return node.put("bodyIncluded",node.has("input")||node.has("output")||node.has("message")||node.has("raw")||node.has("schema"));
    }

    private static void operation(SQLiteDatabase db,JSONObject node)throws Exception {
        String operation=node.optString("operationId");if(operation.isEmpty())return;
        String[] args={node.getString("taskId"),node.getString("runId"),operation};
        try(Cursor row=db.rawQuery("SELECT length(CAST(arguments AS BLOB))+coalesce(length(CAST(receipt AS BLOB)),0) FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",args)){
            if(!row.moveToFirst()||row.getLong(0)<0||row.getLong(0)>MAX_SOURCE_BYTES){node.put("operationBodyUnavailable",true);return;}
        }
        try(Cursor row=db.rawQuery("SELECT tool,arguments,receipt,state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",args)){
            if(!row.moveToFirst()||!node.optString("name").equals(row.getString(0)))return;
            node.put("input",parse(row.getString(1))).put("operationState",row.getString(3)).put("operationBodySource","original_operation_row");
            if(!row.isNull(2))node.put("output",parse(row.getString(2)));
        }
    }
    private static boolean unavailable(Object value){return value==null||value==JSONObject.NULL||value instanceof JSONObject&&Boolean.FALSE.equals(((JSONObject)value).opt("available"));}
    private static Object parse(Object raw){if(!(raw instanceof String))return raw==null?JSONObject.NULL:raw;try{org.json.JSONTokener parser=new org.json.JSONTokener((String)raw);Object value=parser.nextValue();return parser.nextClean()==0?value:raw;}catch(Exception plain){return raw;}}
    static JSONObject find(JSONArray messages,JSONObject reference)throws Exception {
        JSONObject found=null;String fingerprint=reference.optString("fingerprint");
        for(int i=0;messages!=null&&i<messages.length();i++){JSONObject message=messages.optJSONObject(i);if(message==null)continue;
            if(!fingerprint.equals(ExecutionTraceCapture.reference(message).optString("fingerprint")))continue;
            if(found!=null)return null;found=message;
        }return found;
    }
    private static JSONObject assistant(JSONArray messages,String call){
        JSONObject found=null;for(int i=0;messages!=null&&i<messages.length();i++){JSONObject message=messages.optJSONObject(i),provider=message==null?null:message.optJSONObject("provider_data");
            if(message!=null&&"assistant".equals(message.optString("role"))&&!message.optBoolean("memoryContextRetired")&&(call.equals(message.optString("nativeTraceCallId"))||provider!=null&&call.equals(provider.optString("originCallId")))){if(found!=null)return null;found=message;}}
        return found;
    }
    private static JSONObject toolCall(JSONArray messages,String request,String id){
        if(request.isEmpty()||id.isEmpty())return null;JSONObject assistant=assistant(messages,request);JSONArray calls=assistant==null?null:assistant.optJSONArray("tool_calls");JSONObject found=null;
        for(int i=0;calls!=null&&i<calls.length();i++){JSONObject call=calls.optJSONObject(i);if(call!=null&&id.equals(call.optString("id"))){if(found!=null)return null;found=call;}}return found;
    }
    private static JSONObject toolResult(JSONArray messages,String request,String id){
        if(toolCall(messages,request,id)==null)return null;JSONObject found=null;boolean inRequest=false;
        for(int i=0;messages!=null&&i<messages.length();i++){JSONObject message=messages.optJSONObject(i);if(message==null)continue;
            if("assistant".equals(message.optString("role"))){JSONObject provider=message.optJSONObject("provider_data");inRequest=request.equals(message.optString("nativeTraceCallId"))||provider!=null&&request.equals(provider.optString("originCallId"));}
            if(inRequest&&"tool".equals(message.optString("role"))&&id.equals(message.optString("tool_call_id"))){if(found!=null)return null;found=message;}}
        return found;
    }
    private static JSONObject visible(JSONObject original)throws Exception {
        JSONObject safe=new JSONObject();for(String key:new String[]{"role","content","tool_calls","tool_call_id","ownerMessageId","ownerMessageRevision","ownerInputMode"})if(original.has(key))safe.put(key,original.get(key));
        return safe;
    }
    private static String preview(Object content){String text=content==null?"":content.toString();int end=Math.min(text.length(),1200);if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);}
}
