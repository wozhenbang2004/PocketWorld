package dev.pocketengine.features.attachments;

import dev.pocketengine.core.RunControl;
import java.io.*;
import java.nio.charset.*;
import java.util.Set;
import org.json.*;

/** Bounded streaming reader over the same immutable native snapshot. Encoding is an explicit
 * owner choice, never a guessed conversion or a write back to the original file. */
public final class OwnerTextDocument {
    interface Open {InputStream open()throws Exception;}
    private final Open original;private static final int MAX_CHARS=20*1024*1024;
    public static final Set<String> ENCODINGS=Set.of("UTF-8","UTF-16LE","UTF-16BE","GB18030","ISO-8859-1");
    OwnerTextDocument(Open original){this.original=original;}
    private Reader reader(String encoding)throws Exception {if(!ENCODINGS.contains(encoding))throw new IllegalArgumentException("请选择支持的原文件解码方式");return new InputStreamReader(original.open(),Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));}
    private static char skip(Reader in,int offset,RunControl control)throws Exception {
        if(offset<0||offset>MAX_CHARS)throw new IllegalArgumentException("原文件阅读位置超出范围");char last=0;char[] buffer=new char[4096];int at=0;
        while(at<offset){control.check();int n=in.read(buffer,0,Math.min(buffer.length,offset-at));if(n<0)throw new IllegalArgumentException("原文件没有此阅读位置");if(n==0)continue;at+=n;last=buffer[n-1];}return last;
    }
    public JSONObject page(String encoding,int offset,int maxChars,RunControl control)throws Exception {
        if(maxChars<2||maxChars>16000)throw new IllegalArgumentException("每次原文件文字读取为2至16000字符");
        try(Reader in=reader(encoding)){
            char prior=skip(in,offset,control);StringBuilder text=new StringBuilder();boolean end=false;char[] buffer=new char[4096];
            while(text.length()<maxChars+1){control.check();int n=in.read(buffer,0,Math.min(buffer.length,maxChars+1-text.length()));if(n<0){end=true;break;}if(n>0)text.append(buffer,0,n);}
            if(text.length()>0&&offset>0&&Character.isHighSurrogate(prior)&&Character.isLowSurrogate(text.charAt(0)))throw new IllegalArgumentException("阅读位置在同一个字符中间，请从上一页返回");
            int used=Math.min(maxChars,text.length());if(used<text.length()&&used>0&&Character.isHighSurrogate(text.charAt(used-1))&&Character.isLowSurrogate(text.charAt(used)))used--;
            if(offset+used>MAX_CHARS)throw new IllegalArgumentException("本次解码文字超出20Mi字符阅读边界，原字节仍可导出");control.check();
            return new JSONObject().put("encoding",encoding).put("content",text.substring(0,used)).put("offset",offset).put("nextOffset",end&&used==text.length()?JSONObject.NULL:offset+used).put("atEnd",end&&used==text.length()).put("decodedCharacters",end&&used==text.length()?offset+used:JSONObject.NULL).put("originalBytesChanged",false);
        }catch(CharacterCodingException invalid){throw new IllegalArgumentException("当前解码方式不能完整读取此文件；请选择实际编码，未替换非法字符或改写原件",invalid);}
    }
    /** Literal, case-sensitive, non-overlapping matches; cursor refers to this exact decode. */
    public JSONObject search(String encoding,String query,int cursor,RunControl control)throws Exception {
        if(query==null||query.isEmpty()||query.length()>512)throw new IllegalArgumentException("请输入1至512字符的字面查找文字");StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(query));
        int[] prefix=new int[query.length()];for(int i=1,j=0;i<query.length();i++){while(j>0&&query.charAt(i)!=query.charAt(j))j=prefix[j-1];if(query.charAt(i)==query.charAt(j))j++;prefix[i]=j;}
        JSONArray hits=new JSONArray();int at=cursor,matched=0;boolean end=false;long until=System.nanoTime()+20_000_000_000L;
        try(Reader in=reader(encoding)){
            char previous=skip(in,cursor,control);char[] buffer=new char[4096];boolean first=true;
            outer:for(;;){control.check();if(System.nanoTime()>until)break;int n=in.read(buffer);if(n<0){end=true;break;}
                for(int i=0;i<n;i++){
                    char next=buffer[i];if(first&&cursor>0&&Character.isHighSurrogate(previous)&&Character.isLowSurrogate(next))throw new IllegalArgumentException("查找起点在同一个字符中间");first=false;
                    if(++at>MAX_CHARS)throw new IllegalArgumentException("本次文字查找超过20Mi字符边界，原件仍保留");while(matched>0&&next!=query.charAt(matched))matched=prefix[matched-1];if(next==query.charAt(matched))matched++;
                    if(matched==query.length()){hits.put(new JSONObject().put("offset",at-query.length()).put("length",query.length()));matched=0;if(hits.length()>=40)break outer;}
                }
            }
        }catch(CharacterCodingException invalid){throw new IllegalArgumentException("所选编码不能完整读取查找范围，请核对编码；没有把未知范围视为无匹配",invalid);}
        control.check();return new JSONObject().put("encoding",encoding).put("query",query).put("matches",hits).put("startOffset",cursor).put("scannedUntil",at).put("nextOffset",end?JSONObject.NULL:at-matched).put("atEnd",end).put("caseSensitive",true).put("overlapping",false);
    }
}
