package dev.pocketengine.browser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native presentation of a model-derived research result. Parsing never grants URL/file/tool authority. */
public final class BrowserResearchResult {
    public static final int MAX_CHARS=48000,MAX_LINES=512,MAX_BLOCKS=32,MAX_ROWS=32,MAX_COLUMNS=6,MAX_CELL=600;
    private static final String UUID_PART="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final String SUFFIX="(?:#[^\\s)\\]<>|]{0,240})?";
    private static final Pattern REFERENCE=Pattern.compile("task:"+UUID_PART+":browser-source:("+UUID_PART+")"+SUFFIX);
    private static final Pattern LINK=Pattern.compile("\\[([^\\]\\n]{1,160})\\]\\((task:"+UUID_PART+":browser-source:"+UUID_PART+SUFFIX+")\\)");
    private BrowserResearchResult() { }
    private static String clip(String text,int max){int end=Math.min(text.length(),max);if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;return text.substring(0,end);}
    /** Sources come only from BrowserTaskSourcePort's actual native owner projection, never from the model. */
    public static JSONObject project(String taskId,String summary,JSONArray nativeSources)throws Exception {
        BrowserTaskSpace.uuid(taskId);Objects.requireNonNull(summary);Objects.requireNonNull(nativeSources);
        Map<String,Integer> known=new LinkedHashMap<>();for(int i=0;i<nativeSources.length()&&i<100;i++){JSONObject source=nativeSources.optJSONObject(i);if(source==null||!taskId.equals(source.optString("taskId")))continue;String id=source.optString("sourceId"),ref=source.optString("sourceRef");if(!id.matches(UUID_PART)||!ref.equals(reference(taskId,id)))continue;known.putIfAbsent(ref,i+1);JSONArray fields=source.optJSONArray("fieldRefs");for(int j=0;fields!=null&&j<fields.length()&&j<BrowserStructuredSource.MAX_REFS;j++){String field=fields.optString(j);try{if(id.equals(BrowserStructuredSource.sourceId(taskId,field)))known.putIfAbsent(field,i+1);}catch(SecurityException unknown){/* Invalid fields never fall back to source-wide authority. */}}}
        String bounded=clip(summary,MAX_CHARS);String[] lines=bounded.split("\\r?\\n",-1);boolean partial=bounded.length()!=summary.length()||lines.length>MAX_LINES;JSONArray blocks=new JSONArray();int limit=Math.min(lines.length,MAX_LINES),i=0;
        while(i<limit&&blocks.length()<MAX_BLOCKS){String line=lines[i];List<String> header=cells(line);
            if(header.size()>0&&i+1<limit&&separator(cells(lines[i+1]))){
                int columns=Math.min(MAX_COLUMNS,header.size());if(header.size()>MAX_COLUMNS)partial=true;JSONArray headings=new JSONArray();for(int c=0;c<columns;c++){String heading=header.get(c).trim();if(heading.length()>MAX_CELL)partial=true;headings.put(clip(heading,MAX_CELL));}JSONArray rows=new JSONArray();i+=2;
                while(i<limit){List<String> values=cells(lines[i]);if(values.isEmpty())break;if(rows.length()>=MAX_ROWS){partial=true;while(i<limit&&!cells(lines[i]).isEmpty())i++;break;}JSONArray row=new JSONArray();for(int c=0;c<columns;c++){String value=c<values.size()?values.get(c).trim():"";if(value.length()>MAX_CELL)partial=true;row.put(cell(value,known,MAX_CELL));}if(values.size()>columns)partial=true;rows.put(row);i++;}
                blocks.put(new JSONObject().put("kind","table").put("headers",headings).put("rows",rows));
            }else{
                StringBuilder text=new StringBuilder();while(i<limit&&text.length()<2400){List<String> next=cells(lines[i]);if(text.length()>0&&next.size()>0&&i+1<limit&&separator(cells(lines[i+1])))break;if(text.length()>0)text.append('\n');text.append(lines[i++]);}if(text.length()>2400)partial=true;blocks.put(cell(text.toString(),known,2400).put("kind","text"));
            }
        }
        if(i<limit)partial=true;return new JSONObject().put("taskId",taskId).put("blocks",blocks).put("partialPresentation",partial).put("originalRetained",true).put("modelDerived",true).put("verifiedBusinessSuccess",false).put("sourceLinksRequireNativeRecords",true);
    }
    public static String reference(String taskId,String sourceId){return "task:"+taskId+":browser-source:"+sourceId;}
    /** Resolve on click against the exact current native record, not the text/URL inside a model answer. */
    public static JSONObject source(String taskId,String sourceId,JSONArray nativeSources)throws Exception {
        BrowserTaskSpace.uuid(taskId);BrowserTaskSpace.uuid(sourceId);for(int i=0;i<nativeSources.length()&&i<100;i++){JSONObject row=nativeSources.optJSONObject(i);if(row!=null&&taskId.equals(row.optString("taskId"))&&sourceId.equals(row.optString("sourceId"))&&reference(taskId,sourceId).equals(row.optString("sourceRef")))return new JSONObject(row.toString());}throw new SecurityException("此引用不属于原任务当前可回查的已保存依据");
    }
    /** Metadata binding only. AttachmentOutcomeHost still checks canonical ownership and actual bytes. */
    public static JSONObject completedAttachment(String taskId,String downloadId,JSONObject nativeReceipt,String expectedId,String expectedSha)throws Exception {
        BrowserTaskSpace.uuid(taskId);BrowserTaskSpace.uuid(downloadId);BrowserTaskSpace.uuid(expectedId);
        if(!downloadId.equals(nativeReceipt.optString("id"))||!taskId.equals(nativeReceipt.optString("taskId"))||!nativeReceipt.optString("status").equals("downloaded"))throw new SecurityException("原下载尚未完成或不属于此原任务");
        JSONObject file=nativeReceipt.optJSONObject("attachment");if(file==null||!expectedId.equals(file.optString("id"))||expectedSha==null||!expectedSha.matches("[a-f0-9]{64}")||!expectedSha.equals(file.optString("sha256")))throw new SecurityException("原下载附件的不可变版本已变化，请重新核对");
        if(!"owner".equals(file.optString("principalId"))||!"private".equals(file.optString("spaceId")))throw new SecurityException("不能从研究结果打开其他空间的文件");return new JSONObject(file.toString());
    }
    private static JSONObject cell(String original,Map<String,Integer> known,int max)throws Exception {
        String value=clip(original,max);Matcher links=LINK.matcher(value);StringBuffer plain=new StringBuffer();while(links.find()){String ref=links.group(2);links.appendReplacement(plain,Matcher.quoteReplacement(known.containsKey(ref)?links.group(1)+" · "+ref+" ":links.group(1)+(ref.contains("#")?"（字段依据未核对）":"（依据未核对）")));}links.appendTail(plain);Matcher refs=REFERENCE.matcher(plain.toString());StringBuffer shown=new StringBuffer();JSONArray ids=new JSONArray(),fields=new JSONArray();Set<String> seen=new LinkedHashSet<>();int count=0;
        while(refs.find()&&count++<128){String ref=refs.group(),id=refs.group(1);boolean field=ref.contains("#");Integer index=known.get(ref);if(index!=null){if(seen.add(field?ref:id)){if(field)fields.put(ref);else ids.put(id);}refs.appendReplacement(shown,Matcher.quoteReplacement((field?"字段依据 ":"依据 ")+index));}else refs.appendReplacement(shown,field?"（字段依据未核对）":"（依据未核对）");}refs.appendTail(shown);return new JSONObject().put("text",shown.toString()).put("sourceIds",ids).put("fieldRefs",fields);
    }
    private static boolean separator(List<String> row){if(row.isEmpty())return false;for(String cell:row)if(!cell.trim().matches(":?-{3,}:?"))return false;return true;}
    /** Small GFM-like table subset only; escaped pipes and inline code pipes are kept, HTML is plain text. */
    private static List<String> cells(String line){String text=line.trim();if(!text.contains("|"))return List.of();List<String> out=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean escape=false,code=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(escape){if(c!='|')cell.append('\\');cell.append(c);escape=false;continue;}if(c=='\\'){escape=true;continue;}if(c=='`'){code=!code;cell.append(c);continue;}if(c=='|'&&!code){out.add(cell.toString());cell.setLength(0);}else cell.append(c);}if(escape)cell.append('\\');out.add(cell.toString());if(text.startsWith("|")&&!out.isEmpty())out.remove(0);if(text.endsWith("|")&&!out.isEmpty())out.remove(out.size()-1);return out.size()>=2?out:List.of();}
}
