package dev.pocketengine.runtime.providers;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded RFC8259 parser adapted from the existing PackageJson implementation.
 * Protocol arguments must not inherit org.json single-quote, bare-word or duplicate-key leniency. */
public final class ProtocolJson {
    private final String source;
    private int offset,nodes;
    private final boolean fragmentUnicode;
    private ProtocolJson(String source,boolean fragmentUnicode){this.source=source;this.fragmentUnicode=fragmentUnicode;}
    public static JSONObject parse(String text)throws Exception {
        return parse(text,false);
    }
    static JSONObject parseEvent(String text)throws Exception{return parse(text,true);}
    private static JSONObject parse(String text,boolean fragmentUnicode)throws Exception {
        ProtocolJson parser=new ProtocolJson(text,fragmentUnicode);Object value=parser.value(0);parser.space();
        if(parser.offset!=text.length()||!(value instanceof JSONObject))throw invalid();return (JSONObject)value;
    }
    private Object value(int depth)throws Exception {
        if(depth>32||++nodes>131072)throw invalid();space();if(offset>=source.length())throw invalid();char c=source.charAt(offset);
        if(c=='{'){offset++;JSONObject object=new JSONObject();space();if(take('}'))return object;while(true){space();String key=string();if(object.has(key))throw invalid();space();expect(':');object.put(key,value(depth+1));space();if(take('}'))return object;expect(',');}}
        if(c=='['){offset++;JSONArray array=new JSONArray();space();if(take(']'))return array;while(true){array.put(value(depth+1));space();if(take(']'))return array;expect(',');}}
        if(c=='"')return string();
        for(String literal:new String[]{"true","false","null"})if(source.startsWith(literal,offset)){offset+=literal.length();return literal.equals("null")?JSONObject.NULL:literal.equals("true");}
        int start=offset;while(offset<source.length()&&"-+0123456789.eE".indexOf(source.charAt(offset))>=0)offset++;String token=source.substring(start,offset);
        if(token.length()>128||!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw invalid();
        BigDecimal number=new BigDecimal(token);if(number.precision()>128||Math.abs((long)number.scale())>128)throw invalid();
        try{return number.longValueExact();}catch(ArithmeticException fractionalOrLarge){
            double converted=number.doubleValue();if(!Double.isFinite(converted)||BigDecimal.valueOf(converted).compareTo(number)!=0)throw new IllegalArgumentException("协议数字无法无损保存");return converted;
        }
    }
    private String string(){
        expect('"');StringBuilder out=new StringBuilder();boolean ended=false;
        while(offset<source.length()){char c=source.charAt(offset++);if(c=='"'){ended=true;break;}if(c<32)throw invalid();
            if(c=='\\'){if(offset>=source.length())throw invalid();char escaped=source.charAt(offset++);switch(escaped){case '"':case '\\':case '/':c=escaped;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':if(offset+4>source.length())throw invalid();String hex=source.substring(offset,offset+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw invalid();c=(char)Integer.parseInt(hex,16);offset+=4;break;default:throw invalid();}}
            out.append(c);
        }
        if(!ended)throw invalid();if(!fragmentUnicode)unicode(out.toString());return out.toString();
    }
    private void space(){while(offset<source.length()&&" \t\r\n".indexOf(source.charAt(offset))>=0)offset++;}
    private boolean take(char c){if(offset<source.length()&&source.charAt(offset)==c){offset++;return true;}return false;}
    private void expect(char c){if(!take(c))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("模型协议不是有效的严格JSON");}
    static void unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isSurrogate(c)&&(!Character.isHighSurrogate(c)||i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i))))throw invalid();}}
}
