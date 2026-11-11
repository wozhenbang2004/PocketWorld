package dev.pocketengine.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Complete, public plan text only. A plan is neither an execution grant nor a file path. */
public final class ExecutionPlan {
    public static final String FORMAT="pocketworld.execution-plan.v1";
    public static final int MAX_UTF8_BYTES=32000;
    private static final Set<String> ROOT_KEYS=Set.of("format","goal","steps","sources");
    private static final Set<String> STEP_KEYS=Set.of("id","title","detail");

    private ExecutionPlan(){}

    /** Returns an independently owned, complete value; invalid or oversized input is never truncated. */
    public static JSONObject checked(JSONObject input)throws Exception {
        if(input==null)throw new SecurityException("计划草稿缺失");
        keys(input,ROOT_KEYS,Set.of("format","goal","steps"));
        if(!FORMAT.equals(input.opt("format")))throw new SecurityException("计划草稿格式无效");
        JSONObject result=new JSONObject().put("format",FORMAT).put("goal",text(input,"goal",4000,true));
        Object rawSteps=input.opt("steps");
        if(!(rawSteps instanceof JSONArray steps)||steps.length()<1||steps.length()>32)throw new SecurityException("计划需要1至32个完整步骤");
        JSONArray checkedSteps=new JSONArray();Set<String> identities=new HashSet<>();
        for(int i=0;i<steps.length();i++){
            if(!(steps.opt(i) instanceof JSONObject step))throw new SecurityException("计划步骤必须是固定结构");
            keys(step,STEP_KEYS,STEP_KEYS);
            String id=text(step,"id",64,true);
            if(id.codePoints().anyMatch(Character::isISOControl)||!identities.add(id))throw new SecurityException("计划步骤需要唯一稳定编号");
            checkedSteps.put(new JSONObject().put("id",id).put("title",text(step,"title",200,false)).put("detail",text(step,"detail",2000,false)));
        }
        result.put("steps",checkedSteps);
        if(input.has("sources")){
            if(!(input.opt("sources") instanceof JSONArray sources)||sources.length()>8)throw new SecurityException("计划来源只能是最多8条纯文本");
            JSONArray checkedSources=new JSONArray();
            for(int i=0;i<sources.length();i++)checkedSources.put(text(sources.opt(i),1000,false));
            result.put("sources",checkedSources);
        }
        if(utf8(encode(result)).length>MAX_UTF8_BYTES)throw new SecurityException("完整计划超过32000字节，未截断或保存");
        return result;
    }

    /** Canonical JSON preserves every string and array position while sorting object keys. */
    public static String canonical(JSONObject input)throws Exception{return encode(checked(input));}

    public static String hash(JSONObject input)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(utf8(canonical(input)));
        StringBuilder result=new StringBuilder(64);
        for(byte value:digest)result.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));
        return result.toString();
    }

    public static boolean supports(String tool){return ExecutionPlanning.SUBMIT.equals(tool)||ExecutionPlanning.REQUEST.equals(tool);}

    public static void validateTool(String tool,JSONObject arguments)throws Exception {
        if(arguments==null)throw new SecurityException("规划请求参数缺失");
        if(ExecutionPlanning.SUBMIT.equals(tool)){
            keys(arguments,Set.of("plan"),Set.of("plan"));
            if(!(arguments.opt("plan") instanceof JSONObject plan))throw new SecurityException("只可提交完整结构化计划");
            checked(plan);return;
        }
        if(!ExecutionPlanning.REQUEST.equals(tool))throw new SecurityException("不是受限规划请求");
        keys(arguments,Set.of("phase","reason"),Set.of("phase","reason"));
        String phase=text(arguments,"phase",9,true);if(!"executing".equals(phase))throw new IllegalArgumentException("只能请求审阅已提交的完整计划；进入只读规划由主人选择");
        text(arguments,"reason",2000,true);
    }

    /** Formal planning belongs to the owner's selected phase, not ordinary execution. */
    public static JSONArray tools(boolean planning)throws Exception {
        return tools(planning,0L);
    }

    public static JSONArray tools(boolean planning,long planRevision)throws Exception {
        JSONArray tools=new JSONArray();
        if(!planning)return tools;
        JSONObject stepProperties=new JSONObject().put("id",textSchema(1,64)).put("title",textSchema(0,200)).put("detail",textSchema(0,2000));
        JSONObject stepSchema=objectSchema(stepProperties,"id","title","detail");
        JSONObject properties=new JSONObject().put("format",new JSONObject().put("type","string").put("enum",new JSONArray().put(FORMAT)))
                .put("goal",textSchema(1,4000)).put("steps",new JSONObject().put("type","array").put("minItems",1).put("maxItems",32).put("items",stepSchema))
                .put("sources",new JSONObject().put("type","array").put("maxItems",8).put("items",textSchema(0,1000)));
        tools.put(tool(ExecutionPlanning.SUBMIT,"保存完整结构化计划供主人审阅。此动作仅写原任务的计划草稿，不编辑项目文件、不批准效果、不追加预算；提交后停止等待主人确认准确版本。",objectSchema(new JSONObject().put("plan",objectSchema(properties,"format","goal","steps")),"plan")));
        if(planRevision>0){
            JSONObject request=new JSONObject().put("phase",new JSONObject().put("type","string").put("enum",new JSONArray().put("executing"))).put("reason",textSchema(1,2000));
            tools.put(tool(ExecutionPlanning.REQUEST,"请主人审阅当前已提交的完整计划，确认后再执行。仅适用于只读规划中已有准确计划版本的任务；请求本身不切换阶段、不批准计划、不增加工具或预算。",objectSchema(request,"phase","reason")));
        }
        return tools;
    }

    private static JSONObject textSchema(int min,int max)throws Exception{return new JSONObject().put("type","string").put("minLength",min).put("maxLength",max);}
    private static JSONObject objectSchema(JSONObject properties,String... required)throws Exception{return new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray(required)).put("additionalProperties",false);}
    private static JSONObject tool(String name,String description,JSONObject schema)throws Exception{return new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description",description).put("parameters",schema));}

    private static void keys(JSONObject value,Set<String> allowed,Set<String> required){
        Set<String> actual=new HashSet<>();value.keys().forEachRemaining(actual::add);
        if(!allowed.containsAll(actual)||!actual.containsAll(required))throw new SecurityException("计划只接受明确文本字段，不能携带秘密、权限或执行参数");
    }

    private static String text(JSONObject value,String key,int limit,boolean nonblank){return text(value.opt(key),limit,nonblank);}
    private static String text(Object value,int limit,boolean nonblank){
        if(!(value instanceof String text)||text.length()>limit||nonblank&&text.codePoints().allMatch(Character::isWhitespace))throw new SecurityException("计划文本缺失、类型无效或超长");
        utf8(text);
        return text;
    }

    private static byte[] utf8(String value){
        try{
            ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] result=new byte[bytes.remaining()];bytes.get(result);return result;
        }catch(CharacterCodingException invalid){throw new SecurityException("计划文本包含无效Unicode，未替换原文",invalid);}
    }

    private static String encode(Object value)throws Exception {
        if(value instanceof JSONObject object){
            StringBuilder result=new StringBuilder("{");
            TreeSet<String> keys=new TreeSet<>();object.keys().forEachRemaining(keys::add);
            for(String key:keys){if(result.length()>1)result.append(',');result.append(JSONObject.quote(key)).append(':').append(encode(object.get(key)));}
            return result.append('}').toString();
        }
        if(value instanceof JSONArray array){
            StringBuilder result=new StringBuilder("[");
            for(int i=0;i<array.length();i++){if(i>0)result.append(',');result.append(encode(array.get(i)));}
            return result.append(']').toString();
        }
        if(value instanceof String text)return JSONObject.quote(text);
        throw new SecurityException("计划只接受完整固定结构与纯文本");
    }
}
