package dev.pocketengine.features.capabilities;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Display-only excerpts of explicit text output. Never use for identities,
 * cursors, paged chunks, image data or the original retained output bytes. */
public final class ToolTextDisplay {
    private static final String GAP="\n[…展示省略…]\n";
    private ToolTextDisplay(){}
    public static final class Excerpt {
        public final String text;
        public final int sourceBytes,headBytes,tailBytes,omittedBytes;
        private Excerpt(String text,int sourceBytes,int headBytes,int tailBytes){this.text=text;this.sourceBytes=sourceBytes;this.headBytes=headBytes;this.tailBytes=tailBytes;omittedBytes=sourceBytes-headBytes-tailBytes;}
        public boolean truncated(){return omittedBytes>0;}
        public JSONObject metadata()throws Exception{return new JSONObject().put("sourceByteScope","returned_text_utf8").put("sourceUtf8Bytes",sourceBytes).put("headUtf8Bytes",headBytes).put("tailUtf8Bytes",tailBytes).put("omittedUtf8Bytes",omittedBytes).put("gapIsSourceContent",false).put("nativeOutputChanged",false);}
    }
    public static Excerpt clip(String source,int jsonByteBudget){
        int bytes=source.getBytes(StandardCharsets.UTF_8).length;
        if(JSONObject.quote(source).getBytes(StandardCharsets.UTF_8).length<=jsonByteBudget)return new Excerpt(source,bytes,bytes,0);
        int room=jsonByteBudget-JSONObject.quote(GAP).getBytes(StandardCharsets.UTF_8).length;
        if(room<0)return new Excerpt("",bytes,0,0);
        int head=0,headCost=0,half=room/2;
        while(head<source.length()){int point=source.codePointAt(head),cost=cost(point);if(headCost+cost>half)break;headCost+=cost;head+=Character.charCount(point);}
        int tail=source.length(),tailCost=0;
        while(tail>head){int point=source.codePointBefore(tail),cost=cost(point);if(tailCost+cost>room-headCost)break;tailCost+=cost;tail-=Character.charCount(point);}
        String first=source.substring(0,head),last=source.substring(tail);
        return new Excerpt(first+GAP+last,bytes,first.getBytes(StandardCharsets.UTF_8).length,last.getBytes(StandardCharsets.UTF_8).length);
    }
    private static int cost(int point){
        // Slash may be escaped after '<' by a JSON implementation; reserve it
        // conservatively so concatenation cannot exceed the original budget.
        return Math.max(point=='/'?2:0,JSONObject.quote(new String(Character.toChars(point))).getBytes(StandardCharsets.UTF_8).length-2);
    }
}
