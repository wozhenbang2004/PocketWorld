package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.features.local.project.ProjectSources;
import java.util.*;
import org.json.JSONObject;

/** Bounded editable source for actual document writers, not HTML, macros, file paths or an
 * arbitrary Office filename. Tabs form simple tables; # headings remain editable source. */
public final class DocumentDraft {
    public static final String FORMAT="pocketworld.editable-document.v1";
    public static final int MAX_BODY=16000,MAX_BLOCKS=500,MAX_COLUMNS=16;
    private final JSONObject source;public final String title,body,name,format,identity;
    public final int fontPoints;public final List<Block> blocks;
    public static final class Block {
        public final int heading;public final List<String> cells;public final String text;
        private Block(int heading,String text,List<String> cells){this.heading=heading;this.text=text;this.cells=cells==null?List.of():Collections.unmodifiableList(new ArrayList<>(cells));}
        public boolean table(){return !cells.isEmpty();}
    }
    public DocumentDraft(JSONObject input)throws Exception{
        for(Iterator<String> i=input.keys();i.hasNext();){String key=i.next();if(!Set.of("format","name","title","body","fontPoints","parentArtifactId","parentSha256","editableSourceId","editableSourceSha256").contains(key))throw new IllegalArgumentException("不支持的文档参数："+key);}
        format=input.getString("format");if(!Set.of("docx","pdf").contains(format))throw new IllegalArgumentException("当前生成真实DOCX或PDF，不把文本伪装成其他格式");
        title=input.optString("title","");body=input.getString("body");fontPoints=input.optInt("fontPoints",12);name=input.optString("name","document");
        if(title.length()>200||body.isEmpty()||body.length()>MAX_BODY||name.isEmpty()||name.length()>80||name.matches("(?s).*[\\p{Cntrl}/\\\\].*")||!Set.of(10,11,12,14,16).contains(fontPoints))throw new IllegalArgumentException("文档标题最多200字、正文1至16000字；字号仅10/11/12/14/16pt");
        strict(title);strict(body);strict(name);source=new JSONObject(input.toString()).put("format",format).put("title",title).put("body",body).put("fontPoints",fontPoints).put("name",name).put("sourceFormat",FORMAT);
        boolean parent=input.has("parentArtifactId")||input.has("parentSha256"),editable=input.has("editableSourceId")||input.has("editableSourceSha256");
        if(parent&&(!uuid(input.optString("parentArtifactId"))||!sha(input.optString("parentSha256")))||editable&&(!uuid(input.optString("editableSourceId"))||!sha(input.optString("editableSourceSha256"))))throw new SecurityException("原父成果或可编辑源引用不完整");
        List<Block> values=new ArrayList<>();if(!title.isEmpty())values.add(new Block(1,title,null));
        for(String line:body.replace("\r\n","\n").replace('\r','\n').split("\\n",-1)){
            if(values.size()>=MAX_BLOCKS)throw new IllegalArgumentException("文档最多500段或表格行，未截断生成");if(line.endsWith("\r"))line=line.substring(0,line.length()-1);
            if(line.contains("\t")){String[] cells=line.split("\t",-1);if(cells.length>MAX_COLUMNS)throw new IllegalArgumentException("表格最多16列，未丢列");values.add(new Block(0,"",Arrays.asList(cells)));}
            else {int heading=line.startsWith("### ")?3:line.startsWith("## ")?2:line.startsWith("# ")?1:0;values.add(new Block(heading,heading==0?line:line.substring(heading+1),null));}
        }
        blocks=Collections.unmodifiableList(values);identity=ProjectSources.digest(source);
    }
    public JSONObject source()throws Exception{return new JSONObject(source.toString());}
    public byte[] sourceBytes()throws Exception{return strict(source.toString());}
    public String fileName(){String base=name.replaceFirst("(?i)\\.(docx|pdf)$","");return base+"."+format;}
    public String mime(){return format.equals("pdf")?"application/pdf":"application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
    public static JSONObject sourceInput(JSONObject saved)throws Exception{JSONObject value=new JSONObject(saved.toString());if(!FORMAT.equals(value.getString("sourceFormat")))throw new SecurityException("不是此格式的原可编辑源");value.remove("sourceFormat");return value;}
    private static boolean uuid(String value){return value.matches("[a-f0-9-]{36}");}private static boolean sha(String value){return value.matches("[a-f0-9]{64}");}
    private static byte[] strict(String value)throws Exception{if(value.indexOf('\0')>=0||value.codePoints().anyMatch(c->c<32&&c!='\n'&&c!='\r'&&c!='\t'))throw new IllegalArgumentException("文档含不支持的控制字符");var b=java.nio.charset.StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));byte[] bytes=new byte[b.remaining()];b.get(bytes);return bytes;}
}
