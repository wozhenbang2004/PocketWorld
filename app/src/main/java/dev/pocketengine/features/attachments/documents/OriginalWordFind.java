package dev.pocketengine.features.attachments.documents;

import dev.pocketengine.core.RunControl;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Literal locations in the regular original Word parser's visible body, not visual pages or styles. */
public final class OriginalWordFind {
    private static final int MAX_HITS=40, SIDE=32;
    private final String query;
    private final RunControl control;
    private final int[] prefix;
    private final JSONArray matches=new JSONArray();
    private boolean more;

    private OriginalWordFind(String query,RunControl control){
        this.query=query;this.control=control;
        prefix=new int[query.length()];
        for(int i=1,j=0;i<query.length();i++){
            while(j>0&&query.charAt(i)!=query.charAt(j))j=prefix[j-1];
            if(query.charAt(i)==query.charAt(j))j++;
            prefix[i]=j;
        }
    }

    public static JSONObject read(byte[] bytes,String query,RunControl control)throws Exception{
        control.checkpoint();
        if(query==null||query.isEmpty()||query.length()>256||query.indexOf('\0')>=0)
            throw new IllegalArgumentException("请输入要在原 Word 正文中查找的文字");
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(query));
        if(bytes.length>DocumentReader.MAX_INPUT_BYTES||!"zip".equals(DocumentReader.identify(bytes)))
            throw new IllegalArgumentException("没有核对到可读取的原 DOCX 正文");
        var finder=new OriginalWordFind(query,control);
        // Exactly the same native OOXML validation and Word main-part pass; the observer
        // returns only locations/context instead of retaining any ordinary body chunks.
        var checked=new OfficeDocumentReader(finder).read(bytes,new DocumentContent.Request(1,0,1,2048,1,"text"),control);
        if(!"docx".equals(checked.format)||!"partial".equals(checked.status)||!"body_paragraphs_and_tables".equals(checked.coverage))
            throw new IllegalArgumentException("没有核对到可读取的原 DOCX 正文");
        control.checkpoint();
        return new JSONObject().put("format","pocketworld.original-word-find.v1").put("query",query)
                .put("coverage","body_paragraphs_and_tables").put("caseSensitive",true).put("totalUnits",checked.totalUnits)
                .put("hasMoreMatches",finder.more).put("matches",finder.matches);
    }

    void observe(int unit,String kind,Map<String,String> locator,String text)throws Exception{
        if(more||text.length()<query.length())return;
        int matched=0,chunk=0,start=0,cut=DocumentContent.textChunkEnd(text,0);
        for(int i=0;i<text.length();i++){
            if((i&4095)==0)control.checkpoint();
            if(i==cut){start=cut;cut=DocumentContent.textChunkEnd(text,start);chunk++;}
            char value=text.charAt(i);
            while(matched>0&&value!=query.charAt(matched))matched=prefix[matched-1];
            if(value==query.charAt(matched))matched++;
            if(matched!=query.length())continue;
            if(matches.length()>=MAX_HITS){more=true;return;}
            int end=i+1,offset=end-query.length();
            // The query is at most 256 UTF-16 units, so it can cross at most one
            // of the native text reader's 1023–1024 unit cuts.
            JSONObject hit=new JSONObject().put("unit",unit).put("chunk",offset<start?chunk-1:chunk)
                    .put("offset",offset).put("kind",kind);
            if("table_cell".equals(kind)){
                for(String coordinate:new String[]{"table","row","column"}){
                    String number=locator.get(coordinate);
                    if(number==null||!number.matches("[1-9][0-9]{0,6}"))
                        throw new IllegalArgumentException("找到了文字，但无法核对原 Word 表格位置");
                    hit.put(coordinate,number);
                }
            }
            int before=Math.max(0,offset-SIDE),after=Math.min(text.length(),end+SIDE);
            if(before<offset&&Character.isLowSurrogate(text.charAt(before)))before++;
            if(after>end&&Character.isHighSurrogate(text.charAt(after-1)))after--;
            hit.put("before",text.substring(before,offset)).put("after",text.substring(end,after));
            matches.put(hit);matched=0;
        }
    }
}
