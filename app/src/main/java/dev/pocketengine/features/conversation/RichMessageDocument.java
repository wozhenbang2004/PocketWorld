package dev.pocketengine.features.conversation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Read-only native presentation of common chat formatting. Never evaluates HTML or code. */
public final class RichMessageDocument {
    public enum Kind { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, CODE, TABLE }
    public static final class Span {
        public final String text,style,url;
        Span(String text,String style,String url){this.text=text;this.style=style;this.url=url;}
    }
    public static final class Block {
        public final Kind kind;
        public final String text,language,marker;
        public final int level;
        public final boolean closed;
        public final List<Span> spans;
        public final List<List<String>> rows;
        Block(Kind kind,String text,String language,String marker,int level,boolean closed,List<List<String>> rows){this.kind=kind;this.text=text;this.language=language;this.marker=marker;this.level=level;this.closed=closed;this.rows=List.copyOf(rows);this.spans=kind==Kind.CODE?List.of(new Span(text,"code","")):inline(text);}
    }
    private RichMessageDocument(){}
    public static List<Block> parse(String content){
        String[] lines=(content==null?"":content).replace("\r\n","\n").replace('\r','\n').split("\n",-1);List<Block> result=new ArrayList<>();StringBuilder paragraph=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            String line=lines[i],trimmed=line.stripLeading();String fence=fence(trimmed);
            if(!fence.isEmpty()){
                flush(result,paragraph);String info=trimmed.substring(fence.length()).trim(),language=info.matches("[A-Za-z0-9_+.#-]{1,40}")?info:"";StringBuilder code=new StringBuilder();boolean closed=false;int bodyLines=0;
                while(++i<lines.length){String end=lines[i].strip();if(end.length()>=fence.length()&&end.chars().allMatch(c->c==fence.charAt(0))){closed=true;break;}if(bodyLines++>0)code.append('\n');code.append(lines[i]);}
                result.add(new Block(Kind.CODE,code.toString(),language,"",0,closed,List.of()));continue;
            }
            if(line.isBlank()){flush(result,paragraph);continue;}
            if(i+1<lines.length&&line.contains("|")&&tableSeparator(lines[i+1])){
                flush(result,paragraph);List<List<String>> rows=new ArrayList<>();rows.add(cells(line));i++;
                while(i+1<lines.length&&!lines[i+1].isBlank()&&lines[i+1].contains("|")){rows.add(cells(lines[++i]));}
                result.add(new Block(Kind.TABLE,"","","",0,true,rows));continue;
            }
            int heading=0;while(heading<Math.min(trimmed.length(),6)&&trimmed.charAt(heading)=='#')heading++;
            if(heading>0&&trimmed.length()>heading&&trimmed.charAt(heading)==' '){flush(result,paragraph);result.add(new Block(Kind.HEADING,trimmed.substring(heading+1),"","",heading,true,List.of()));continue;}
            if(trimmed.startsWith("> ")){flush(result,paragraph);result.add(new Block(Kind.QUOTE,trimmed.substring(2),"","",0,true,List.of()));continue;}
            String marker="";int prefix=0;
            if(trimmed.startsWith("- ")||trimmed.startsWith("* ")||trimmed.startsWith("+ ")){marker="•";prefix=2;}
            else{int dot=0;while(dot<Math.min(trimmed.length(),6)&&Character.isDigit(trimmed.charAt(dot)))dot++;if(dot>0&&trimmed.length()>dot+1&&(trimmed.charAt(dot)=='.'||trimmed.charAt(dot)==')')&&trimmed.charAt(dot+1)==' '){marker=trimmed.substring(0,dot+1);prefix=dot+2;}}
            if(prefix>0){flush(result,paragraph);result.add(new Block(Kind.LIST_ITEM,trimmed.substring(prefix),"",marker,Math.min(3,(line.length()-trimmed.length())/2),true,List.of()));continue;}
            if(paragraph.length()>0)paragraph.append('\n');paragraph.append(line);
        }
        flush(result,paragraph);return List.copyOf(result);
    }
    private static String fence(String line){if(line.startsWith("```")){int n=3;while(n<line.length()&&line.charAt(n)=='`')n++;return line.substring(0,n);}if(line.startsWith("~~~")){int n=3;while(n<line.length()&&line.charAt(n)=='~')n++;return line.substring(0,n);}return "";}
    private static void flush(List<Block> blocks,StringBuilder text){if(text.length()>0){blocks.add(new Block(Kind.PARAGRAPH,text.toString(),"","",0,true,List.of()));text.setLength(0);}}
    private static boolean tableSeparator(String line){List<String> values=cells(line);return values.size()>1&&values.stream().allMatch(value->value.matches(":?-{3,}:?"));}
    private static List<String> cells(String line){String value=line.trim();if(value.startsWith("|"))value=value.substring(1);if(value.endsWith("|")&&!value.endsWith("\\|"))value=value.substring(0,value.length()-1);List<String> cells=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean escaped=false;for(char c:value.toCharArray()){if(escaped){if(c!='|')cell.append('\\');cell.append(c);escaped=false;}else if(c=='\\')escaped=true;else if(c=='|'){cells.add(cell.toString().trim());cell.setLength(0);}else cell.append(c);}if(escaped)cell.append('\\');cells.add(cell.toString().trim());return List.copyOf(cells);}
    public static List<Span> inline(String text){
        List<Span> result=new ArrayList<>();StringBuilder plain=new StringBuilder();
        for(int i=0;i<text.length();){
            char c=text.charAt(i);if(c=='\\'&&i+1<text.length()&&"\\`*_[]()".indexOf(text.charAt(i+1))>=0){plain.append(text.charAt(i+1));i+=2;continue;}
            String delimiter=text.startsWith("**",i)?"**":c=='`'?"`":"";
            if(!delimiter.isEmpty()){int end=text.indexOf(delimiter,i+delimiter.length());if(end>i+delimiter.length()){span(result,plain);result.add(new Span(text.substring(i+delimiter.length(),end),delimiter.equals("`")?"code":"bold",""));i=end+delimiter.length();continue;}}
            if(c=='['){int labelEnd=text.indexOf("](",i+1),urlEnd=labelEnd<0?-1:text.indexOf(')',labelEnd+2);if(labelEnd>i+1&&urlEnd>labelEnd+2){String url=text.substring(labelEnd+2,urlEnd);if(safeUrl(url)){span(result,plain);result.add(new Span(text.substring(i+1,labelEnd),"link",url));i=urlEnd+1;continue;}}}
            plain.append(c);i++;
        }
        span(result,plain);return List.copyOf(result);
    }
    private static void span(List<Span> spans,StringBuilder plain){if(plain.length()>0){spans.add(new Span(plain.toString(),"plain",""));plain.setLength(0);}}
    public static boolean safeUrl(String value){try{URI uri=new URI(value);return value.length()<=4096&&(uri.getScheme().equalsIgnoreCase("https")||uri.getScheme().equalsIgnoreCase("http"))&&uri.getHost()!=null&&uri.getRawUserInfo()==null;}catch(Exception malformed){return false;}}
}
