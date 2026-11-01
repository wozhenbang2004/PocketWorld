package dev.pocketengine.features.plugins.distribution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded RFC8259 parser/canonical writer; rejects duplicate keys and permissive platform extensions. */
final class PackageJson {
    private final String source;
    private int offset,nodes;
    private PackageJson(String source){this.source=source;}
    static JSONObject parse(String text)throws Exception {
        PackageJson parser=new PackageJson(text);Object value=parser.value(0);parser.space();
        if(parser.offset!=text.length()||!(value instanceof JSONObject))throw invalid();return (JSONObject)value;
    }
    private Object value(int depth)throws Exception {
        if(depth>16||++nodes>12000)throw invalid();space();if(offset>=source.length())throw invalid();char c=source.charAt(offset);
        if(c=='{'){offset++;JSONObject object=new JSONObject();space();if(take('}'))return object;while(true){space();String key=string();if(object.has(key))throw invalid();space();expect(':');object.put(key,value(depth+1));space();if(take('}'))return object;expect(',');}}
        if(c=='['){offset++;JSONArray array=new JSONArray();space();if(take(']'))return array;while(true){array.put(value(depth+1));space();if(take(']'))return array;expect(',');}}
        if(c=='"')return string();
        for(String literal:new String[]{"true","false","null"})if(source.startsWith(literal,offset)){offset+=literal.length();return literal.equals("null")?JSONObject.NULL:literal.equals("true");}
        int start=offset;while(offset<source.length()&&"-+0123456789.eE".indexOf(source.charAt(offset))>=0)offset++;String token=source.substring(start,offset);
        if(token.length()>128||!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw invalid();
        BigDecimal number=new BigDecimal(token);if(number.precision()>128||Math.abs((long)number.scale())>128)throw invalid();
        try{return number.longValueExact();}catch(ArithmeticException fractionalOrLarge){
            double converted=number.doubleValue();if(!Double.isFinite(converted)||BigDecimal.valueOf(converted).compareTo(number)!=0)throw new IllegalArgumentException("数字无法在Android声明中无损保存");return converted;
        }
    }
    private String string(){
        expect('"');StringBuilder out=new StringBuilder();boolean ended=false;
        while(offset<source.length()){char c=source.charAt(offset++);if(c=='"'){ended=true;break;}if(c<32)throw invalid();
            if(c=='\\'){if(offset>=source.length())throw invalid();char escaped=source.charAt(offset++);switch(escaped){case '"':case '\\':case '/':c=escaped;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':if(offset+4>source.length())throw invalid();String hex=source.substring(offset,offset+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw invalid();c=(char)Integer.parseInt(hex,16);offset+=4;break;default:throw invalid();}}
            out.append(c);
        }
        if(!ended)throw invalid();unicode(out.toString());return out.toString();
    }
    private void space(){while(offset<source.length()&&" \t\r\n".indexOf(source.charAt(offset))>=0)offset++;}
    private boolean take(char c){if(offset<source.length()&&source.charAt(offset)==c){offset++;return true;}return false;}
    private void expect(char c){if(!take(c))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("插件包不是受支持的严格JSON声明");}
    static void unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c==0)throw invalid();if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i))))throw invalid();}}
    static String canonical(Object value)throws Exception {
        return canonical(value,0,new int[]{0});
    }
    private static String canonical(Object value,int depth,int[] nodes)throws Exception {
        if(depth>20||++nodes[0]>24000)throw invalid();
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();object.keys().forEachRemaining(keys::add);List<String> parts=new ArrayList<>();for(String key:keys)parts.add(quote(key)+":"+canonical(object.get(key),depth+1,nodes));return "{"+String.join(",",parts)+"}";}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;List<String> parts=new ArrayList<>();for(int i=0;i<array.length();i++)parts.add(canonical(array.get(i),depth+1,nodes));return "["+String.join(",",parts)+"]";}
        if(value==null||value==JSONObject.NULL)return "null";if(value instanceof String)return quote((String)value);if(value instanceof Boolean)return value.toString();
        if(value instanceof Number){BigDecimal number=new BigDecimal(value.toString());if(number.precision()>128||Math.abs((long)number.scale())>128)throw invalid();return number.signum()==0?"0":number.stripTrailingZeros().toPlainString();}throw invalid();
    }
    private static String quote(String value){unicode(value);StringBuilder out=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"':out.append("\\\"");break;case '\\':out.append("\\\\");break;case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;case '\n':out.append("\\n");break;case '\r':out.append("\\r");break;case '\t':out.append("\\t");break;default:if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}return out.append('"').toString();}
}
