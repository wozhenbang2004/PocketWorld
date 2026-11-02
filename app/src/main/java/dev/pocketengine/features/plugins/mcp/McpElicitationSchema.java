package dev.pocketengine.features.plugins.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded form data for the original native pending-interaction host; never an approval gate. */
public final class McpElicitationSchema {
    private McpElicitationSchema(){}
    private static final Set<String> ROOT=Set.of("type","properties","required","additionalProperties","title","description","$schema");
    private static final Set<String> FIELD=Set.of("type","title","description","enum","minLength","maxLength","minimum","maximum");
    public static final class Unsupported extends IllegalArgumentException {
        public final String code;
        private Unsupported(String code){super("不支持此远端输入表单："+code);this.code=code;}
    }
    public static final class Form {
        public final String schemaDigest;
        private final String schema;
        private Form(JSONObject schema)throws Exception{this.schema=schema.toString();schemaDigest=McpJson.digest(schema);}
        /** Display data only; no default answer, OAuth secret field or execution authority. */
        public JSONObject view()throws Exception{return new JSONObject().put("schemaDigest",schemaDigest).put("schema",new JSONObject(schema)).put("answerRequired",true).put("authenticationAllowed",false);}
        public JSONObject validate(JSONObject content)throws Exception {
            if(content==null||content.toString().length()>16384)throw new IllegalArgumentException("请输入原表单的有界字段值");
            JSONObject source=new JSONObject(schema),properties=source.getJSONObject("properties");JSONArray required=source.getJSONArray("required");
            for(String key:keys(content))if(!properties.has(key))throw new IllegalArgumentException("表单包含未请求的字段："+key);
            for(int i=0;i<required.length();i++)if(!content.has(required.getString(i)))throw new IllegalArgumentException("缺少必填字段："+required.getString(i));
            JSONObject result=new JSONObject();for(String key:keys(content)){Object value=content.get(key);validateValue(properties.getJSONObject(key),value);result.put(key,value);}
            return result;
        }
    }
    public static Form parse(String mode,JSONObject requestedSchema)throws Exception {
        if(mode!=null&&!mode.equals("form"))throw new Unsupported("unsupported_mode");
        if(requestedSchema==null||requestedSchema.toString().length()>32768)throw new Unsupported("schema_size");
        JSONObject schema=new JSONObject(requestedSchema.toString());checkKeys(schema,ROOT);
        if(!"object".equals(schema.opt("type"))||schema.optJSONObject("properties")==null)throw new Unsupported("object_properties_required");
        if(schema.has("additionalProperties")&&!Boolean.FALSE.equals(schema.get("additionalProperties")))throw new Unsupported("additional_properties");
        if(schema.has("$schema")&&!"https://json-schema.org/draft/2020-12/schema".equals(schema.get("$schema")))throw new Unsupported("schema_dialect");
        annotations(schema);JSONObject properties=schema.getJSONObject("properties");if(properties.length()<1||properties.length()>16)throw new Unsupported("field_count");
        for(String name:keys(properties)){
            if(!name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))throw new Unsupported("field_name");
            String lower=name.toLowerCase(java.util.Locale.ROOT).replaceAll("[_.-]","");
            for(String secret:new String[]{"password","token","secret","apikey","authorization","credential"})if(lower.contains(secret))throw new Unsupported("authentication_field");
            JSONObject field=properties.optJSONObject(name);if(field==null)throw new Unsupported("field_schema");checkKeys(field,FIELD);annotations(field);
            String type=field.optString("type");if(!Set.of("string","number","integer","boolean").contains(type))throw new Unsupported("scalar_type");
            if(type.equals("string")){
                int min=length(field,"minLength",0),max=length(field,"maxLength",4096);if(min>max)throw new Unsupported("length_range");
                if(field.has("minimum")||field.has("maximum"))throw new Unsupported("numeric_constraint_on_string");
            }else{
                if(field.has("minLength")||field.has("maxLength"))throw new Unsupported("length_constraint_on_scalar");
                if(type.equals("boolean")&&(field.has("minimum")||field.has("maximum")))throw new Unsupported("numeric_constraint_on_boolean");
                BigDecimal min=field.has("minimum")?decimal(field.get("minimum")):null,max=field.has("maximum")?decimal(field.get("maximum")):null;
                if(min!=null&&max!=null&&min.compareTo(max)>0)throw new Unsupported("numeric_range");
            }
            if(field.has("enum")){
                JSONArray choices=field.optJSONArray("enum");if(choices==null||choices.length()<1||choices.length()>32)throw new Unsupported("enum_size");
                JSONObject withoutEnum=new JSONObject(field.toString());withoutEnum.remove("enum");Set<String> seen=new HashSet<>();
                for(int i=0;i<choices.length();i++){try{validateValue(withoutEnum,choices.get(i));}catch(IllegalArgumentException invalid){throw new Unsupported("enum_value");}if(!seen.add(McpJson.digest(choices.get(i))))throw new Unsupported("duplicate_enum");}
            }
        }
        JSONArray required=schema.optJSONArray("required");if(schema.has("required")&&required==null)throw new Unsupported("required_type");if(required==null)required=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<required.length();i++){Object key=required.get(i);if(!(key instanceof String)||!properties.has((String)key)||!seen.add((String)key))throw new Unsupported("required_fields");}
        schema.put("required",required).put("additionalProperties",false);return new Form(schema);
    }
    private static void validateValue(JSONObject field,Object value)throws Exception {
        String type=field.getString("type");
        switch(type){
            case "string" -> {
                if(!(value instanceof String))throw new IllegalArgumentException("字段需要文字");String text=(String)value;validText(text,4096);int count=text.codePointCount(0,text.length());
                if(count<length(field,"minLength",0)||count>length(field,"maxLength",4096))throw new IllegalArgumentException("文字长度不在表单范围内");
            }
            case "boolean" -> {if(!(value instanceof Boolean))throw new IllegalArgumentException("字段需要明确的是或否");}
            case "number","integer" -> {
                BigDecimal number;try{number=decimal(value);}catch(Unsupported invalid){throw new IllegalArgumentException("字段需要有限数字");}
                if(type.equals("integer")&&number.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("字段需要整数");
                if(field.has("minimum")&&number.compareTo(decimal(field.get("minimum")))<0||field.has("maximum")&&number.compareTo(decimal(field.get("maximum")))>0)throw new IllegalArgumentException("数字不在表单范围内");
            }
            default -> throw new Unsupported("scalar_type");
        }
        if(field.has("enum")){JSONArray choices=field.getJSONArray("enum");boolean found=false;for(int i=0;i<choices.length();i++)if(McpJson.digest(value).equals(McpJson.digest(choices.get(i))))found=true;if(!found)throw new IllegalArgumentException("请选择表单列出的值");}
    }
    private static int length(JSONObject schema,String key,int fallback)throws Exception {
        if(!schema.has(key))return fallback;try{int n=decimal(schema.get(key)).intValueExact();if(n<0||n>4096)throw new ArithmeticException();return n;}catch(ArithmeticException invalid){throw new Unsupported("length_limit");}
    }
    private static BigDecimal decimal(Object value){
        if(!(value instanceof Number))throw new Unsupported("number_type");try{BigDecimal n=new BigDecimal(value.toString());if(n.abs().compareTo(new BigDecimal("1e100"))>0||n.scale()>1000)throw new NumberFormatException();return n;}catch(NumberFormatException invalid){throw new Unsupported("number_limit");}
    }
    private static void annotations(JSONObject schema)throws Exception {for(String key:new String[]{"title","description"})if(schema.has(key)){if(!(schema.get(key) instanceof String))throw new Unsupported("annotation_type");validText(schema.getString(key),1024);}}
    private static void validText(String text,int max){
        if(text.length()>max*2)throw new IllegalArgumentException("文字超过表单预算");for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c==0||Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')throw new IllegalArgumentException("文字含无效字符");if(Character.isHighSurrogate(c)){if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("文字包含无效Unicode");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("文字包含无效Unicode");}
        if(text.codePointCount(0,text.length())>max)throw new IllegalArgumentException("文字超过表单预算");
    }
    private static void checkKeys(JSONObject object,Set<String> allowed){for(String key:keys(object))if(!allowed.contains(key))throw new Unsupported("unsupported_keyword_"+key.substring(0,Math.min(64,key.length())));}
    private static List<String> keys(JSONObject object){List<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);return keys;}
}
