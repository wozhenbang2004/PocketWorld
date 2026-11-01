package dev.pocketengine.features.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded derived serialization of a producer-sanitized native result. Not a second source or grant. */
final class ToolOutputEncoding {
    private ToolOutputEncoding(){}
    static final int CHUNK_CHARS=4096,MAX_CHUNKS=257,MAX_NODES=20000;
    static final class Payload {
        final String text,fingerprint;final long bytes;final boolean complete;
        final List<Chunk> chunks;
        private Payload(String text,int bytes,boolean complete)throws Exception{
            this.text=text;this.bytes=bytes;this.complete=complete;fingerprint=fingerprint(text);
            List<Chunk> blocks=new ArrayList<>();for(int at=0;at<text.length();){int end=Math.min(text.length(),at+CHUNK_CHARS);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;String block=text.substring(at,end);blocks.add(new Chunk(blocks.size(),at,block));at=end;}
            if(blocks.size()>MAX_CHUNKS)throw new IllegalStateException("有界原输出分块超限");chunks=Collections.unmodifiableList(blocks);
        }
    }
    static final class Chunk {
        final int sequence,start,chars,bytes;final String text,fingerprint;
        Chunk(int sequence,int start,String text)throws Exception{this.sequence=sequence;this.start=start;this.text=text;chars=text.length();bytes=text.getBytes(StandardCharsets.UTF_8).length;fingerprint=fingerprint(text);}
    }
    static String fingerprint(String text)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder("sha256:");for(byte b:bytes){int value=b&255;hex.append(Character.forDigit(value>>>4,16)).append(Character.forDigit(value&15,16));}return hex.toString();
    }
    /** Text cleaning is process-local and bound to the original captured inference, not global settings.
     * Input was already allowlisted by the producer. Never stringify native lease/credential objects. */
    static Payload encode(JSONObject safeNativeData,ToolOutputCapture.TextCleaner cleaner)throws Exception{
        if(safeNativeData==null||cleaner==null)throw new IllegalArgumentException("原native输出/捕获时清理门缺失");Writer out=new Writer(cleaner);boolean complete=true;
        try{out.value(safeNativeData,0);}catch(Limit reached){complete=false;}
        if(out.text.length()==0)throw new IllegalStateException("未保留任何原输出字节");return new Payload(out.text.toString(),out.bytes,complete);
    }
    private static final class Limit extends Exception {}
    private static final class Writer {
        final ToolOutputCapture.TextCleaner cleaner;final StringBuilder text=new StringBuilder();final long deadline=System.nanoTime()+500_000_000L;int bytes,nodes,writes;
        Writer(ToolOutputCapture.TextCleaner cleaner){this.cleaner=cleaner;}
        void reserve(int count)throws Exception{if(count>ToolOutputCapture.MAX_OUTPUT_BYTES-bytes||(++writes&127)==0&&System.nanoTime()>deadline)throw new Limit();bytes+=count;}
        // Internal JSON punctuation/escapes/numbers are ASCII; avoid allocating one byte[] per char.
        void atom(String value)throws Exception{reserve(value.length());text.append(value);}
        void point(int point)throws Exception{reserve(point<128?1:point<2048?2:point<65536?3:4);text.appendCodePoint(point);}
        void quoted(String raw)throws Exception{
            String value=cleaner.clean(raw);if(value==null)throw new IllegalArgumentException("原输出清理不能返回空对象");atom("\"");
            for(int i=0;i<value.length();){int point=value.codePointAt(i);char c=value.charAt(i);if(Character.isSurrogate(c)&&(point==c||Character.isLowSurrogate(c)))throw new IllegalArgumentException("原输出Unicode不完整");i+=Character.charCount(point);
                switch(point){case '"':atom("\\\"");break;case '\\':atom("\\\\");break;case '\b':atom("\\b");break;case '\f':atom("\\f");break;case '\n':atom("\\n");break;case '\r':atom("\\r");break;case '\t':atom("\\t");break;default:if(point<32){String hex=Integer.toHexString(point);atom("\\u"+"0000".substring(hex.length())+hex);}else point(point);}
            }atom("\"");
        }
        void value(Object value,int depth)throws Exception{
            if(depth>24||++nodes>MAX_NODES||System.nanoTime()>deadline)throw new Limit();
            if(value==null||value==JSONObject.NULL){atom("null");return;}
            if(value instanceof String){quoted((String)value);return;}
            if(value instanceof Boolean){atom(value.toString());return;}
            if(value instanceof Number){String number=value.toString();if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("原输出数值不是JSON数值");atom(number);return;}
            if(value instanceof JSONArray){JSONArray array=(JSONArray)value;atom("[");for(int i=0;i<array.length();i++){if(i>0)atom(",");value(array.get(i),depth+1);}atom("]");return;}
            if(value instanceof JSONObject){JSONObject object=(JSONObject)value;atom("{");List<String> keys=new ArrayList<>();for(var it=object.keys();it.hasNext();){if(keys.size()>=MAX_NODES)throw new Limit();keys.add(it.next());}Collections.sort(keys);boolean first=true;for(String key:keys){if(!first)atom(",");first=false;quoted(key);atom(":");value(object.get(key),depth+1);}atom("}");return;}
            throw new IllegalArgumentException("原输出含非JSON对象，未用描述字符串替代");
        }
    }
}
