package dev.pocketengine.features.assistant;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native legacy-array byte locator only. Never assigns a historical SourceRef or timestamp.
 * The original JSON bytes are partitioned, not normalized into a second body copy. */
public final class LegacyConversationImport {
    private LegacyConversationImport(){}
    public static final int MAX_SOURCE_BYTES=32*1024*1024;
    public static final class Range {
        public final int start,end;
        Range(int a,int b){start=a;end=b;}
        public int length(){return end-start;}
    }
    public static final class Conversation {
        public final Range bytes;public final String id;public final JSONObject header;public final List<Range> messages;
        Conversation(Range r,String key,JSONObject h,List<Range> rows){bytes=r;id=key;header=h;messages=java.util.Collections.unmodifiableList(new ArrayList<>(rows));}
    }
    public static List<Conversation> index(byte[] original,String sourceSha)throws Exception{
        if(original.length>MAX_SOURCE_BYTES||!sourceSha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("旧原件超过已审核导入预算或缺真实SHA");
        // Reject malformed UTF-8 instead of silently replacing bytes before locating evidence.
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original));
        Scanner scanner=new Scanner(original);List<Range> conversations=scanner.array(new Range(0,original.length));List<Conversation> result=new ArrayList<>();
        int number=0;java.util.Set<String> ids=new java.util.HashSet<>();
        for(Range range:conversations){Map<String,Range> fields=scanner.fields(range);Range messageArray=fields.get("messages");if(messageArray==null)throw new IllegalArgumentException("旧对话缺原messages数组，未猜正文");
            List<Range> messages=scanner.array(messageArray);JSONObject header=new JSONObject(text(original,range));header.remove("messages");Object old=header.opt("conversationId");boolean known=old instanceof String&&!((String)old).isEmpty()&&((String)old).length()<=240;
            String id=known?(String)old:"import-conversation:"+sourceSha+":"+number;number++;if(!ids.add(id))throw new IllegalArgumentException("旧会话标识重复，原字节保留待核对");
            header.put("conversationId",id).put("conversationIdProvenance",known?"stored_legacy_field":"import_assigned_not_historical").put("importedOriginalFormat","legacy_array").put("originalSourceSha256",sourceSha).put("historicalIdentityVerified",false);
            for(Range message:messages)new JSONObject(text(original,message)); // The exact range must be one original message object.
            result.add(new Conversation(range,id,header,messages));
        }
        return java.util.Collections.unmodifiableList(result);
    }
    /** Preserve unverified old metadata without letting current UI/native code treat it as an ACK,
     * attachment grant, model memory receipt or a formal source association. Original bytes remain. */
    public static JSONObject ownerView(JSONObject raw)throws Exception{
        JSONObject view=new JSONObject(raw.toString()),metadata=new JSONObject();for(String key:new String[]{"attachments","memoryReference","memoryContext","submission","nativeReplyId","clientMessageId","requestFingerprint"})if(view.has(key)){metadata.put(key,view.get(key));view.remove(key);}if(metadata.length()>0)view.put("unverifiedOriginalMetadata",metadata);
        Object time=raw.opt("at");boolean known=time instanceof Byte||time instanceof Short||time instanceof Integer||time instanceof Long;view.put("at",known?time:JSONObject.NULL).put("historicalTimeKnown",known);return view;
    }
    public static String text(byte[] original,Range range){return new String(original,range.start,range.length(),StandardCharsets.UTF_8);}
    static byte[] bytes(byte[] original,Range range){return java.util.Arrays.copyOfRange(original,range.start,range.end);}
    /** Standard JSON delimiters only. No regex over braces inside quoted message text. */
    private static final class Scanner {
        final byte[] data;int p;
        Scanner(byte[] bytes){data=bytes;}
        void whitespace(){while(p<data.length&&(data[p]==' '||data[p]=='\n'||data[p]=='\r'||data[p]=='\t'))p++;}
        void expect(int c){whitespace();if(p>=data.length||data[p++]!=c)throw new IllegalArgumentException("旧原件JSON边界不可核对");}
        List<Range> array(Range range){p=range.start;expect('[');List<Range> result=new ArrayList<>();whitespace();if(p<data.length&&data[p]==']'){p++;finish(range);return result;}
            while(true){whitespace();int start=p;skip(0);result.add(new Range(start,p));whitespace();if(p>=data.length)throw new IllegalArgumentException("旧JSON数组未闭合");int next=data[p++];if(next==']')break;if(next!=',')throw new IllegalArgumentException("旧JSON数组边界不正确");}finish(range);return result;
        }
        Map<String,Range> fields(Range range)throws Exception{p=range.start;expect('{');Map<String,Range> result=new LinkedHashMap<>();whitespace();if(p<data.length&&data[p]=='}'){p++;finish(range);return result;}
            while(true){whitespace();int begin=p;string();String key=new JSONArray("["+new String(data,begin,p-begin,StandardCharsets.UTF_8)+"]").getString(0);expect(':');whitespace();begin=p;skip(0);if(result.put(key,new Range(begin,p))!=null)throw new IllegalArgumentException("旧原件字段重复，未猜哪一份为原件");whitespace();if(p>=data.length)throw new IllegalArgumentException("旧JSON对象未闭合");int next=data[p++];if(next=='}')break;if(next!=',')throw new IllegalArgumentException("旧JSON对象边界不正确");}finish(range);return result;
        }
        void finish(Range range){while(p<range.end&&(data[p]==' '||data[p]=='\n'||data[p]=='\r'||data[p]=='\t'))p++;if(p!=range.end)throw new IllegalArgumentException("旧原件范围含额外内容");}
        void string(){if(p>=data.length||data[p++]!='"')throw new IllegalArgumentException("旧JSON字段不是已知字符串");while(p<data.length){int c=data[p++]&255;if(c=='"')return;if(c=='\\'){if(p>=data.length)break;p++;}else if(c<32)throw new IllegalArgumentException("旧JSON字符串含未转义控制字节");}throw new IllegalArgumentException("旧JSON字符串未闭合");}
        void skip(int depth){if(depth>128)throw new IllegalArgumentException("旧JSON嵌套超过导入预算");whitespace();if(p>=data.length)throw new IllegalArgumentException("旧原件值缺失");int c=data[p];if(c=='"'){string();return;}
            if(c=='{'||c=='['){int close=c=='{'?'}':']';p++;whitespace();if(p<data.length&&data[p]==close){p++;return;}while(true){if(c=='{'){whitespace();string();expect(':');}skip(depth+1);whitespace();if(p>=data.length)break;int next=data[p++];if(next==close)return;if(next!=',')throw new IllegalArgumentException("旧原件嵌套边界无效");}throw new IllegalArgumentException("旧原件嵌套未闭合");}
            int begin=p;while(p<data.length&&data[p]!=','&&data[p]!=']'&&data[p]!='}'&&data[p]!=' '&&data[p]!='\n'&&data[p]!='\r'&&data[p]!='\t')p++;if(p==begin)throw new IllegalArgumentException("旧原件值边界无效");
        }
    }
}
