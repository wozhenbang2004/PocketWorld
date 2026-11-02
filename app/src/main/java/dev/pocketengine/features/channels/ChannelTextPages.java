package dev.pocketengine.features.channels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Lossless, surrogate-safe views of one frozen answer. Limits bound storage, not silent truncation. */
public final class ChannelTextPages {
    public static final int BODY_CHARS=3500,MAX_PAGES=64;
    private ChannelTextPages(){}
    public static List<String> bodies(String text){
        if(text==null||text.isBlank())throw new IllegalArgumentException("完整答复为空");
        List<String> pages=new ArrayList<>();if(text.length()<=4000){pages.add(text);return pages;}
        for(int start=0;start<text.length();){
            if(pages.size()>=MAX_PAGES)throw new IllegalArgumentException("完整答复超过有界分片范围；未截断或声称完整交付，请缩小输出或改用原任务生成附件");
            int end=Math.min(text.length(),start+BODY_CHARS);
            if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
            pages.add(text.substring(start,end));start=end;
        }
        return pages;
    }
    public static String sha256(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    public static String page(String body,int index,int count,String hash,String continuation){
        return count==1?body:"[完整答复 "+(index+1)+"/"+count+"；SHA256 "+hash+"；未收到余段可用 "+continuation+"]\n"+body;
    }
}
