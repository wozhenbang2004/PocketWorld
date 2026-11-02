package dev.pocketengine.features.attachments.creation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned, bounded native Office source. This declares supported objects, never arbitrary
 * OOXML, scripts, external links, caller-provided calculation caches or filesystem paths. */
public final class StructuredOfficeDraft {
    public static final int MAX_SOURCE_BYTES=256*1024,MAX_CELLS=4096;
    public static final double SLIDE_WIDTH=13.333333,SLIDE_HEIGHT=7.5;
    public final List<Sheet> sheets;
    public final List<Slide> slides;

    public record Cell(String ref,Object value,String formula,String numberFormat) {}
    public record Sheet(String name,List<Cell> cells) {}
    public record Shape(String id,String text,double x,double y,double width,double height) {}
    public record Slide(String id,String title,List<String> paragraphs,String notes,List<Shape> shapes) {}

    public StructuredOfficeDraft(String format,JSONObject input)throws Exception {
        keys(input,Set.of("version",format.equals("xlsx")?"sheets":"slides"));
        if(!(input.get("version") instanceof Number)||input.getInt("version")!=1
                ||new BigDecimal(input.get("version").toString()).compareTo(BigDecimal.ONE)!=0)
            throw new IllegalArgumentException("Office结构源仅支持version=1");
        if(input.toString().getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("Office结构源超过256KiB，未截断生成");
        List<Sheet> work=new ArrayList<>();List<Slide> deck=new ArrayList<>();
        if(format.equals("xlsx")) {
            JSONArray rows=input.getJSONArray("sheets");count(rows,1,16,"工作表");Set<String> names=new HashSet<>();int total=0;
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);keys(row,Set.of("name","cells"));String name=text(row,"name",31,false);
                if(name.matches(".*[\\[\\]:*?/\\\\].*")||name.startsWith("'")||name.endsWith("'")||!names.add(name.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("工作表名称无效或重复");
                JSONArray cells=row.getJSONArray("cells");count(cells,1,MAX_CELLS,"单元格");total+=cells.length();if(total>MAX_CELLS)throw new IllegalArgumentException("工作簿最多4096个单元格");
                Set<String> refs=new HashSet<>();List<Cell> values=new ArrayList<>();
                for(int c=0;c<cells.length();c++) {
                    JSONObject value=cells.getJSONObject(c);keys(value,Set.of("ref","text","number","boolean","formula","numberFormat"));String ref=text(value,"ref",10,false);
                    if(!validRef(ref)||!refs.add(ref))throw new IllegalArgumentException("单元格引用无效或重复："+ref);
                    int supplied=0;String chosen=null;for(String field:List.of("text","number","boolean","formula"))if(value.has(field)){supplied++;chosen=field;}
                    if(supplied!=1)throw new IllegalArgumentException("单元格须且只能指定text、number、boolean或formula，不接受外部缓存");
                    String formula=null;Object data=null;
                    if(value.has("formula")){formula=text(value,"formula",512,false);if(formula.startsWith("="))formula=formula.substring(1);if(formula.isBlank())throw new IllegalArgumentException("公式不能为空");}
                    else {data=value.get(chosen);if(chosen.equals("text")){if(!(data instanceof String s))throw new IllegalArgumentException("单元格text必须为文本");validateText(s,4096,true);}else if(chosen.equals("number")){if(!(data instanceof Number n))throw new IllegalArgumentException("单元格number必须为数值");BigDecimal number=decimal(n);if(number.precision()>30||Math.abs(number.scale())>100)throw new IllegalArgumentException("数值精度或指数超过范围");}else if(!(data instanceof Boolean))throw new IllegalArgumentException("单元格boolean必须为布尔值");}
                    String style=value.has("numberFormat")?text(value,"numberFormat",16,false):"general";
                    if(!Set.of("general","integer","decimal","percent").contains(style))throw new IllegalArgumentException("不支持的数字格式："+style);
                    values.add(new Cell(ref,data,formula,style));
                }
                values.sort(Comparator.comparingInt((Cell c)->row(c.ref())).thenComparingInt(c->column(c.ref())));
                work.add(new Sheet(name,List.copyOf(values)));
            }
        } else if(format.equals("pptx")) {
            JSONArray rows=input.getJSONArray("slides");count(rows,1,32,"演示页");Set<String> ids=new HashSet<>();
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);keys(row,Set.of("id","title","paragraphs","notes","shapes"));String id=identifier(row,"id");if(!ids.add(id))throw new IllegalArgumentException("演示页id重复");
                String title=text(row,"title",200,false),notes=row.has("notes")?text(row,"notes",8000,true):"";
                List<String> paragraphs=new ArrayList<>();if(row.has("paragraphs")){JSONArray ps=row.getJSONArray("paragraphs");count(ps,0,32,"段落");for(int p=0;p<ps.length();p++){Object v=ps.get(p);if(!(v instanceof String s))throw new IllegalArgumentException("演示段落必须是文本");validateText(s,2000,true);paragraphs.add(s);}}
                List<Shape> shapes=new ArrayList<>();Set<String> shapeIds=new HashSet<>(Set.of("title","body","chart"));
                if(row.has("shapes")){JSONArray ss=row.getJSONArray("shapes");count(ss,0,32,"文本对象");for(int s=0;s<ss.length();s++){
                    JSONObject shape=ss.getJSONObject(s);keys(shape,Set.of("id","text","x","y","width","height"));String shapeId=identifier(shape,"id");if(!shapeIds.add(shapeId))throw new IllegalArgumentException("同页对象id重复或保留");
                    double x=number(shape,"x"),y=number(shape,"y"),w=number(shape,"width"),h=number(shape,"height");
                    if(x<0||y<0||w<=0||h<=0||x+w>SLIDE_WIDTH+0.000001||y+h>SLIDE_HEIGHT+0.000001)throw new IllegalArgumentException("文本对象必须完整位于演示页内，坐标单位为英寸");
                    shapes.add(new Shape(shapeId,text(shape,"text",4000,true),x,y,w,h));
                }}
                deck.add(new Slide(id,title,List.copyOf(paragraphs),notes,List.copyOf(shapes)));
            }
        } else throw new IllegalArgumentException("结构Office源仅支持xlsx或pptx");
        sheets=List.copyOf(work);slides=List.copyOf(deck);
    }
    static void keys(JSONObject input,Set<String> allowed){for(Iterator<String> keys=input.keys();keys.hasNext();){String key=keys.next();if(!allowed.contains(key))throw new IllegalArgumentException("未支持的Office结构参数："+key);}}
    static void count(JSONArray input,int min,int max,String label){if(input.length()<min||input.length()>max)throw new IllegalArgumentException(label+"数量须为"+min+"至"+max+"，未截断生成");}
    static String text(JSONObject input,String key,int max,boolean empty)throws Exception{Object v=input.get(key);if(!(v instanceof String s))throw new IllegalArgumentException(key+"必须为文本");validateText(s,max,empty);return s;}
    static void validateText(String s,int max,boolean empty)throws Exception{if(s.length()>max||!empty&&s.isBlank()||s.codePoints().anyMatch(c->c<32&&c!='\n'&&c!='\r'&&c!='\t'||c==0xfffe||c==0xffff))throw new IllegalArgumentException("Office文本为空、过长或包含控制字符");StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));}
    static BigDecimal decimal(Number n){try{return new BigDecimal(n.toString());}catch(NumberFormatException invalid){throw new IllegalArgumentException("Office数值必须有限",invalid);}}
    private static double number(JSONObject input,String key)throws Exception{Object v=input.get(key);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException(key+"必须为有限数值");return n.doubleValue();}
    private static String identifier(JSONObject input,String key)throws Exception{String id=text(input,key,48,false);if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,47}"))throw new IllegalArgumentException("Office对象id无效");return id;}
    static boolean validRef(String ref){if(!ref.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))return false;return column(ref)<=16384&&row(ref)<=1048576;}
    static int row(String ref){return Integer.parseInt(ref.replaceFirst("^[A-Z]+",""));}
    static int column(String ref){int n=0;for(int i=0;i<ref.length()&&Character.isLetter(ref.charAt(i));i++)n=n*26+ref.charAt(i)-'A'+1;return n;}
    static String ref(int column,int row){StringBuilder out=new StringBuilder();while(column>0){column--;out.insert(0,(char)('A'+column%26));column/=26;}return out.toString()+row;}
}
