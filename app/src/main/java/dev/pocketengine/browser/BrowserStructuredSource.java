package dev.pocketengine.browser;

import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the original Runner's safe-JSON delivery projection; not another source store or DOM capture. */
public final class BrowserStructuredSource {
    public static final int MAX_BYTES=12288,MAX_REFS=24;
    private static final String U="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Pattern FIELD=Pattern.compile("task:("+U+"):browser-source:("+U+")#field:([a-f0-9]{64}):(r[0-9]{1,3}c[0-9]{1,2}|i[0-9]{1,3})");
    private BrowserStructuredSource() { }
    private static JSONObject copy(JSONObject v)throws Exception{return new JSONObject(v.toString());}
    private static int bytes(JSONObject v){return v.toString().getBytes(StandardCharsets.UTF_8).length;}
    private static void keys(JSONObject v,Set<String> expected)throws Exception {if(v.length()!=expected.size())throw new SecurityException("结构化来源字段不完整");for(java.util.Iterator<String> it=v.keys();it.hasNext();)if(!expected.contains(it.next()))throw new SecurityException("结构化来源含额外字段");}
    private static int integer(JSONObject v,String k,int min,int max)throws Exception {Object n=v.get(k);if(!(n instanceof Number)||((Number)n).doubleValue()!=Math.rint(((Number)n).doubleValue())||((Number)n).doubleValue()<min||((Number)n).doubleValue()>max)throw new SecurityException("结构化来源位置或数量无效");return ((Number)n).intValue();}
    private static void text(JSONObject v,String k,int max)throws Exception {if(!(v.get(k) instanceof String)||v.getString(k).length()>max)throw new SecurityException("结构化来源文字超限");}
    private static void links(JSONArray links)throws Exception {if(links.length()>8)throw new SecurityException("来源链接超限");for(int i=0;i<links.length();i++){JSONObject link=links.getJSONObject(i);keys(link,Set.of("text","href"));text(link,"text",160);text(link,"href",2048);}}
    private static void row(JSONObject row,String mode)throws Exception {
        integer(row,"index",0,159);
        if(mode.equals("list")){keys(row,Set.of("index","kind","text","links","partial"));if(!Set.of("listitem","term","definition").contains(row.getString("kind")))throw new SecurityException("列表项类型无效");text(row,"text",2000);links(row.getJSONArray("links"));if(!(row.get("partial") instanceof Boolean))throw new SecurityException("列表覆盖标记无效");return;}
        keys(row,Set.of("index","cells"));JSONArray cells=row.getJSONArray("cells");if(cells.length()>24)throw new SecurityException("来源列数超限");int previous=-1;
        for(int i=0;i<cells.length();i++){JSONObject cell=cells.getJSONObject(i);keys(cell,Set.of("column","role","text","links","colSpan","rowSpan","partial"));int column=integer(cell,"column",0,23);if(column<=previous)throw new SecurityException("来源列次序无效");previous=column;
            if(!Set.of("cell","gridcell","columnheader","rowheader").contains(cell.getString("role")))throw new SecurityException("来源单元格类型无效");text(cell,"text",1500);links(cell.getJSONArray("links"));integer(cell,"colSpan",1,1000);integer(cell,"rowSpan",1,1000);if(!(cell.get("partial") instanceof Boolean))throw new SecurityException("单元格覆盖标记无效");}
    }
    /** Whole displayed rows only. Oversized rows are never sliced into a fabricated valid cell. */
    public static JSONObject fromDelivered(JSONObject receipt)throws Exception {
        if(!receipt.optBoolean("ok")||!receipt.optBoolean("semanticRead")||!receipt.optBoolean("readOnlyEvidence")||!receipt.optString("status").equals("observed"))throw new SecurityException("缺少原模型实际交付的语义观察");
        JSONObject read=receipt.getJSONObject("read");String mode=read.getString("mode");if(!Set.of("table","list").contains(mode))throw new IllegalArgumentException("仅表格或列表有结构化字段来源");
        BrowserSemanticRead.sourceFacts(read);JSONArray delivered=read.getJSONArray("data");if(delivered.length()>24||delivered.length()!=read.getJSONObject("facts").getInt("returnedItems"))throw new SecurityException("模型交付行数不一致");
        JSONArray data=new JSONArray();JSONObject out=new JSONObject().put("version",1).put("mode",mode).put("basis","runner_safe_json_delivery")
                .put("deliveredReadRevision",read.getJSONObject("delivery").getString("revision")).put("deliveredRows",delivered.length())
                .put("omittedDeliveredRows",delivered.length()).put("partial",true).put("data",data);
        int previous=-1;for(int i=0;i<delivered.length();i++){JSONObject value=copy(delivered.getJSONObject(i));row(value,mode);if(value.getInt("index")<=previous)throw new SecurityException("模型交付行次序无效");previous=value.getInt("index");data.put(value);out.put("omittedDeliveredRows",delivered.length()-data.length());
            if(bytes(out)+80>MAX_BYTES){data.remove(data.length()-1);out.put("omittedDeliveredRows",delivered.length()-data.length());break;}}
        if(data.length()==0)throw new IllegalStateException("这次交付没有限额内可保存的结构化行，请缩小读取范围；未改存未交付正文");
        boolean partial=read.getJSONObject("facts").getBoolean("partial")||data.length()!=delivered.length();for(int i=0;i<data.length();i++){JSONObject r=data.getJSONObject(i);if(mode.equals("list"))partial|=r.getBoolean("partial");else for(int j=0;j<r.getJSONArray("cells").length();j++)partial|=r.getJSONArray("cells").getJSONObject(j).getBoolean("partial");}
        out.put("partial",partial).put("revision",TaskStore.capabilityFingerprint(out));validate(out);return out;
    }
    public static void validate(JSONObject projection)throws Exception {
        keys(projection,Set.of("version","mode","basis","deliveredReadRevision","deliveredRows","omittedDeliveredRows","partial","data","revision"));integer(projection,"version",1,1);
        String mode=projection.getString("mode");if(!Set.of("table","list").contains(mode)||!projection.getString("basis").equals("runner_safe_json_delivery")||!(projection.get("partial") instanceof Boolean))throw new SecurityException("结构化来源不是实际模型交付投影");
        if(!projection.getString("revision").matches("[a-f0-9]{64}")||!projection.getString("deliveredReadRevision").matches("[a-f0-9]{64}")||bytes(projection)>MAX_BYTES)throw new SecurityException("结构化来源版本或字节预算无效");
        JSONArray data=projection.getJSONArray("data");int delivered=integer(projection,"deliveredRows",1,24),omitted=integer(projection,"omittedDeliveredRows",0,24);if(data.length()<1||data.length()+omitted!=delivered||omitted>0&&!projection.getBoolean("partial"))throw new SecurityException("结构化来源不能伪称完整");
        int previous=-1;boolean partialCell=false;for(int i=0;i<data.length();i++){JSONObject r=data.getJSONObject(i);row(r,mode);if(r.getInt("index")<=previous)throw new SecurityException("结构化来源行顺序无效");previous=r.getInt("index");if(mode.equals("list"))partialCell|=r.getBoolean("partial");else for(int j=0;j<r.getJSONArray("cells").length();j++)partialCell|=r.getJSONArray("cells").getJSONObject(j).getBoolean("partial");}
        if(partialCell&&!projection.getBoolean("partial"))throw new SecurityException("字段有遗漏，不能把保存投影标为完整");
        JSONObject body=copy(projection);body.remove("revision");if(!TaskStore.capabilityFingerprint(body).equals(projection.getString("revision")))throw new SecurityException("已保存结构化来源的正文版本不一致");
    }
    public static String excerpt(JSONObject projection)throws Exception {validate(projection);StringBuilder out=new StringBuilder();JSONArray rows=projection.getJSONArray("data");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(out.length()>0)out.append('\n');if(projection.getString("mode").equals("list"))out.append(row.getString("text"));else{JSONArray cells=row.getJSONArray("cells");for(int j=0;j<cells.length();j++){if(j>0)out.append(" | ");out.append(cells.getJSONObject(j).getString("text"));}}}int end=Math.min(6000,out.length());if(end>0&&end<out.length()&&Character.isHighSurrogate(out.charAt(end-1))&&Character.isLowSurrogate(out.charAt(end)))end--;return out.substring(0,end);}
    private static String ref(String task,String source,JSONObject p,String locator)throws Exception{return BrowserResearchResult.reference(task,source)+"#field:"+p.getString("revision")+":"+locator;}
    public static JSONArray references(String task,String source,JSONObject projection)throws Exception {BrowserTaskSpace.uuid(task);BrowserTaskSpace.uuid(source);validate(projection);JSONArray refs=new JSONArray();JSONArray rows=projection.getJSONArray("data");for(int i=0;i<rows.length()&&refs.length()<MAX_REFS;i++){JSONObject row=rows.getJSONObject(i);if(projection.getString("mode").equals("list"))refs.put(ref(task,source,projection,"i"+row.getInt("index")));else{JSONArray cells=row.getJSONArray("cells");for(int j=0;j<cells.length()&&refs.length()<MAX_REFS;j++)refs.put(ref(task,source,projection,"r"+row.getInt("index")+"c"+cells.getJSONObject(j).getInt("column")));}}return refs;}
    public static JSONObject addReferences(JSONObject out,JSONObject source)throws Exception {if(!source.has("structuredProjection"))return out;JSONObject p=source.getJSONObject("structuredProjection");JSONArray refs=references(source.getString("taskId"),source.getString("sourceId"),p);int fields=0;for(int i=0;i<p.getJSONArray("data").length();i++)fields+=p.getString("mode").equals("list")?1:p.getJSONArray("data").getJSONObject(i).getJSONArray("cells").length();return out.put("structuredRevision",p.getString("revision")).put("fieldRefs",refs).put("fieldRefsOmitted",Math.max(0,fields-refs.length()));}
    public static String sourceId(String task,String fieldRef){Matcher m=FIELD.matcher(fieldRef==null?"":fieldRef);if(!m.matches()||!m.group(1).equals(task))throw new SecurityException("字段引用不属于这个原任务的保存版本");return m.group(2);}
    public static JSONObject field(String task,String fieldRef,JSONObject source)throws Exception {
        String id=sourceId(task,fieldRef);if(!task.equals(source.getString("taskId"))||!id.equals(source.getString("sourceId")))throw new SecurityException("字段不属于这份已保存来源");
        Matcher m=FIELD.matcher(fieldRef);if(!m.matches())throw new SecurityException("字段引用格式无效");JSONObject p=source.getJSONObject("structuredProjection");validate(p);if(!m.group(3).equals(p.getString("revision")))throw new SecurityException("字段引用的历史正文版本已变化，未换成新网页");String locator=m.group(4),mode=p.getString("mode");JSONObject selected=null,selectedRow=null;JSONArray headers=new JSONArray();
        for(int i=0;i<p.getJSONArray("data").length();i++){JSONObject row=p.getJSONArray("data").getJSONObject(i);if(mode.equals("list")){if(locator.equals("i"+row.getInt("index"))){selected=row;selectedRow=row;}}else for(int j=0;j<row.getJSONArray("cells").length();j++){JSONObject cell=row.getJSONArray("cells").getJSONObject(j);if(cell.getString("role").equals("columnheader"))headers.put(copy(cell).put("row",row.getInt("index")));if(locator.equals("r"+row.getInt("index")+"c"+cell.getInt("column"))){selected=cell;selectedRow=row;}}}
        if(selected==null)throw new SecurityException("这份历史来源未保存该字段，未用摘要、hash或现网页代替");return new JSONObject().put("fieldRef",fieldRef).put("sourceId",id).put("taskId",task).put("revision",p.getString("revision")).put("mode",mode).put("text",selected.getString("text")).put("field",copy(selected)).put("row",copy(selectedRow)).put("headers",headers).put("partial",p.getBoolean("partial")).put("title",source.optString("title")).put("url",source.optString("url")).put("historicalSavedProjection",true).put("verifiedBusinessSuccess",false);
    }
}
