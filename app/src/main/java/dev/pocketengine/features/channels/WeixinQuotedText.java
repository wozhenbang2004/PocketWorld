package dev.pocketengine.features.channels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** iLink selected-quote text only. Hash matching is content integrity, never actor authority. */
final class WeixinQuotedText {
    private WeixinQuotedText() { }
    static JSONObject select(String original,JSONObject partial)throws Exception {
        if(original==null||original.isEmpty()||original.length()>4000)return missing("original_text_unavailable");
        if(partial==null)return new JSONObject().put("available",true).put("text",original).put("selection","whole_original").put("integrity","not_requested");
        Object left=partial.opt("start"),right=partial.opt("end"),startIndex=partial.opt("startindex"),endIndex=partial.opt("endindex");
        if(!(left instanceof String)||!(right instanceof String)||((String)left).isEmpty()||((String)right).isEmpty()||((String)left).length()>512||((String)right).length()>512
                ||!ordinal(startIndex)||!ordinal(endIndex))return missing("invalid_partial_selection");
        String first=(String)left,last=(String)right;int from=occurrence(original,first,((Number)startIndex).intValue(),0);
        if(from<0)return missing("partial_boundary_not_found");
        int global=occurrence(original,last,((Number)endIndex).intValue(),0),relative=occurrence(original,last,((Number)endIndex).intValue(),from+first.length());
        String hash=partial.optString("quotemd5").toLowerCase(java.util.Locale.ROOT);if(!hash.isEmpty()&&!hash.matches("[a-f0-9]{32}"))return missing("invalid_partial_hash");
        for(int end:new int[]{global,relative}){
            if(end<from||end+last.length()>original.length()||splitPair(original,from)||splitPair(original,end+last.length()))continue;
            String selected=original.substring(from,end+last.length());
            if(hash.isEmpty()||md5(selected).equals(hash))return new JSONObject().put("available",true).put("text",selected).put("selection","selected_substring")
                    .put("integrity",hash.isEmpty()?"no_hash_provided":"md5_matched").put("indexInterpretation",end==global?"global_occurrence":"relative_end_occurrence");
        }
        // A failed selected substring must not silently expand to the entire cached original.
        return missing(hash.isEmpty()?"partial_boundary_not_found":"partial_hash_mismatch");
    }
    private static boolean ordinal(Object value){return value instanceof Number&&((Number)value).doubleValue()==((Number)value).intValue()&&((Number)value).intValue()>=0&&((Number)value).intValue()<=1000;}
    private static int occurrence(String text,String marker,int ordinal,int from){
        int found=-1;for(int remaining=ordinal;remaining>=0;remaining--){found=text.indexOf(marker,from);if(found<0)return -1;from=found+marker.length();}return found;
    }
    private static boolean splitPair(String text,int at){return at>0&&at<text.length()&&Character.isHighSurrogate(text.charAt(at-1))&&Character.isLowSurrogate(text.charAt(at));}
    private static String md5(String text)throws Exception {byte[] digest=MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(32);for(byte b:digest)hex.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return hex.toString();}
    private static JSONObject missing(String reason)throws Exception{return new JSONObject().put("available",false).put("reason",reason).put("text","");}
}
