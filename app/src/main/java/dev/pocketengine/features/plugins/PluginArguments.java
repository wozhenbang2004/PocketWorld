package dev.pocketengine.features.plugins;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded validation core for preflight feedback and permitted execution. */
final class PluginArguments {
    static final int ERROR_LIMIT=20;
    private static final int MAX_DEPTH=6,MAX_FIELDS=30,MAX_ITEMS=100,MAX_NODES=4096,MAX_JSON_CHARS=65536;
    private final JSONArray errors=new JSONArray();
    private int nodes,characters;
    private boolean truncated,stopped;
    private PluginArguments() { }

    static JSONObject diagnose(Object arguments,JSONObject schema)throws Exception {
        PluginArguments check=new PluginArguments();
        if(!(arguments instanceof JSONObject))check.error("","type","请提供一组对象形式的工具参数");
        else{
            check.value(arguments,schema,"",0,true);
            // Traverse first: never serialize an unbounded/cyclic input just to measure it.
            if(check.errors.length()==0&&arguments.toString().length()>MAX_JSON_CHARS)
                check.error("","input_size_limit","参数 JSON 最多65536个字符，请减少内容后重试");
        }
        return new JSONObject().put("valid",check.errors.length()==0).put("errors",check.errors)
                .put("truncated",check.truncated).put("errorLimit",ERROR_LIMIT);
    }

    private void value(Object value,JSONObject schema,String path,int depth,boolean precisePath)throws Exception {
        if(stopped)return;
        if(++nodes>MAX_NODES){error("","input_complexity_limit","参数内容过多，请拆分为较小的请求");stopped=true;return;}
        if(depth>MAX_DEPTH){error(path,"depth_limit","参数最多支持6层嵌套，请简化结构");return;}
        if(value instanceof String){
            if(!count(((String)value).length()))return;
        }else if(value instanceof Number){
            if(!count(value.toString().length()))return;
        }
        if(value!=JSONObject.NULL&&!(value instanceof JSONObject)&&!(value instanceof JSONArray)&&!(value instanceof String)&&!(value instanceof Number)&&!(value instanceof Boolean)){
            error(path,"json_value","请使用有效的 JSON 值");return;
        }
        Object declaredType=schema.opt("type");BigDecimal number=PluginRules.decimal(value);
        if(value instanceof Number&&number==null){error(path,"number","请输入有限的有效数字");return;}
        boolean matches=declaredType==null;
        if(declaredType instanceof JSONArray){
            JSONArray types=(JSONArray)declaredType;
            for(int i=0;i<types.length();i++)if(matchesType(value,types.getString(i),number))matches=true;
        }else if(declaredType instanceof String)matches=matchesType(value,(String)declaredType,number);
        if(!matches){
            error(path,"type",declaredType instanceof String?typeMessage((String)declaredType):"此字段的值不符合声明允许的类型");return;
        }
        if(number!=null){
            if(schema.has("minimum")&&number.compareTo(PluginRules.decimal(schema.get("minimum")))<0)error(path,"minimum","数字低于允许的最小值，请增大数值");
            if(schema.has("maximum")&&number.compareTo(PluginRules.decimal(schema.get("maximum")))>0)error(path,"maximum","数字高于允许的最大值，请减小数值");
        }
        JSONArray choices=schema.optJSONArray("enum");
        if(choices!=null){boolean found=false;for(int i=0;i<choices.length();i++)if(equal(value,choices.get(i)))found=true;if(!found)error(path,"enum","请选择此字段允许的值");}
        if(value instanceof String){
            String text=(String)value;int length=text.codePointCount(0,text.length()),minimum=schema.optInt("minLength",0),limit=Math.min(16000,schema.optInt("maxLength",16000));
            if(length<minimum)error(path,"min_length","文字至少"+minimum+"个字符，请补充内容");
            if(length>limit)error(path,"max_length","文字最多"+limit+"个字符，请缩短内容");
        }
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value,properties=schema.optJSONObject("properties");JSONArray required=schema.optJSONArray("required");
            if(object.length()>MAX_FIELDS){error(path,"property_limit","每组参数最多30个字段，请减少字段");return;}
            if(required!=null)for(int i=0;i<required.length()&&!stopped;i++){
                String name=required.getString(i);
                // Presence and null are separate: a present null is checked by its property schema.
                if(!object.has(name))error(precisePath?pointer(path,name):path,"required","请填写此必需字段");
            }
            List<String> keys=new ArrayList<>();Iterator<String> names=object.keys();while(names.hasNext())keys.add(names.next());Collections.sort(keys);
            boolean extraReported=false;
            for(String key:keys){
                if(stopped||!count(key.length()))return;
                JSONObject child=properties==null?null:properties.optJSONObject(key);
                if(child!=null)value(object.get(key),child,precisePath?pointer(path,key):path,depth+1,precisePath);
                else if(!schema.optBoolean("additionalProperties",true)){
                    // An undeclared key is itself user input and may contain a secret. Point at its container.
                    if(!extraReported){error(path,"additional_property","包含未声明的字段，请移除额外字段");extraReported=true;}
                }else value(object.get(key),new JSONObject(),path,depth+1,false);
            }
        }
        if(value instanceof JSONArray){
            JSONArray array=(JSONArray)value;int limit=Math.min(MAX_ITEMS,schema.optInt("maxItems",MAX_ITEMS));
            if(array.length()>limit)error(path,"max_items","列表最多"+limit+"项，请移除多余项目");
            JSONObject items=schema.optJSONObject("items");if(items==null)items=new JSONObject();
            for(int i=0;i<Math.min(array.length(),MAX_ITEMS)&&!stopped;i++)value(array.get(i),items,precisePath?pointer(path,Integer.toString(i)):path,depth+1,precisePath);
        }
    }

    private boolean count(int amount)throws Exception {
        if(amount>MAX_JSON_CHARS-characters){error("","input_size_limit","参数 JSON 最多65536个字符，请减少内容后重试");stopped=true;return false;}
        characters+=amount;return true;
    }
    private void error(String path,String code,String message)throws Exception {
        if(errors.length()<ERROR_LIMIT)errors.put(new JSONObject().put("path",path).put("code",code).put("message",message));
        else{truncated=true;stopped=true;}
    }
    private static boolean equal(Object a,Object b){
        if(a instanceof Number&&b instanceof Number){BigDecimal left=PluginRules.decimal(a),right=PluginRules.decimal(b);return left!=null&&right!=null&&left.compareTo(right)==0;}
        return java.util.Objects.equals(a,b);
    }
    private static String pointer(String path,String key){return path+"/"+key.replace("~","~0").replace("/","~1");}
    private static boolean matchesType(Object value,String type,BigDecimal number){
        switch(type){
            case "object":return value instanceof JSONObject;
            case "array":return value instanceof JSONArray;
            case "string":return value instanceof String;
            case "boolean":return value instanceof Boolean;
            case "number":return number!=null;
            case "integer":return number!=null&&PluginRules.integer(number);
            case "null":return value==JSONObject.NULL;
            default:throw new IllegalArgumentException("不支持的参数类型");
        }
    }
    private static String typeMessage(String type){
        switch(type){
            case "null":return "此字段只接受null";
            case "string":return "请输入文字";
            case "boolean":return "请选择是或否";
            case "number":return "请输入数字，不要使用文字形式的数字";
            case "integer":return "请输入没有小数部分的整数";
            case "array":return "请提供列表形式的值";
            default:return "请提供对象形式的一组字段";
        }
    }

    /** Bounded snapshot before validation; never reparse numbers through a platform JSON parser. */
    static JSONObject snapshot(JSONObject value)throws Exception {return (JSONObject)copy(value,0,new int[]{0,0});}
    private static Object copy(Object value,int depth,int[] budget)throws Exception {
        if(depth>MAX_DEPTH||++budget[0]>MAX_NODES)throw new IllegalArgumentException("参数结构过深或内容过多，请简化后重试");
        if(value instanceof JSONObject){
            JSONObject result=new JSONObject(),object=(JSONObject)value;
            if(object.length()>MAX_FIELDS)throw new IllegalArgumentException("每组参数最多30个字段，请减少字段");
            Iterator<String> keys=object.keys();while(keys.hasNext()){String key=keys.next();copySize(key.length(),budget);result.put(key,copy(object.get(key),depth+1,budget));}return result;
        }
        if(value instanceof JSONArray){
            JSONArray result=new JSONArray(),array=(JSONArray)value;
            if(array.length()>MAX_ITEMS)throw new IllegalArgumentException("列表最多100项，请移除多余项目");
            for(int i=0;i<array.length();i++)result.put(copy(array.get(i),depth+1,budget));return result;
        }
        if(value instanceof String){copySize(((String)value).length(),budget);return value;}
        if(value instanceof Number){
            copySize(value.toString().length(),budget);BigDecimal number=PluginRules.decimal(value);
            if(number==null)throw new IllegalArgumentException("请输入有限的有效数字");
            // BigDecimal also freezes a mutable Number supplied by a native caller.
            return number;
        }
        if(value instanceof Boolean||value==JSONObject.NULL)return value;
        throw new IllegalArgumentException("请使用有效的 JSON 值");
    }
    private static void copySize(int amount,int[] budget){
        if(amount>MAX_JSON_CHARS-budget[1])throw new IllegalArgumentException("参数 JSON 最多65536个字符，请减少内容后重试");
        budget[1]+=amount;
    }
}

