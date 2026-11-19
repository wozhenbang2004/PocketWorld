package dev.pocketengine.features.local.project;

import java.io.File;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground owner, one ordinary checkout. Cursor and selection are captured
 * from the bounded, read-only source query, never supplied by the Web client. */
public final class CheckoutTextMatches {
    public static final int PAGE=16;
    public final String handle=UUID.randomUUID().toString(), project, checkout, literal;
    private final JSONObject result;
    private CheckoutTextMatches(String project,String checkout,String literal,JSONObject result){
        this.project=project;this.checkout=checkout;this.literal=literal;this.result=result;
    }
    public static CheckoutTextMatches find(File root,String project,String checkout,String literal,CheckoutTextMatches previous,ProjectFileQueries.Current current)throws Exception{
        if(literal==null||literal.isBlank()||literal.length()>128||literal.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("请填写 1 至 128 个字符的一行代码或报错原文");
        JSONObject input=new JSONObject().put("mode","text").put("syntax","literal").put("query",literal).put("hidden",false).put("caseSensitive",true).put("limit",PAGE);
        if(previous!=null){
            if(!project.equals(previous.project)||!checkout.equals(previous.checkout)||!literal.equals(previous.literal)||!previous.result.getBoolean("hasMore"))
                throw new SecurityException("请从原副本的上一次命中继续");
            input.put("cursor",previous.result.getJSONObject("nextCursor"));
        }
        JSONObject found=ProjectFileQueries.search(root,checkout,input,current);current.check();
        if(!checkout.equals(found.getString("checkoutId"))||found.getJSONArray("matches").length()>PAGE)throw new SecurityException("未读到原副本的搜索页");
        return new CheckoutTextMatches(project,checkout,literal,found);
    }
    public JSONObject page()throws Exception{
        return new JSONObject().put("projectId",project).put("checkoutId",checkout).put("textHandle",handle)
            .put("query",literal).put("manifestHash",result.getString("manifestHash")).put("matches",new JSONArray(result.getJSONArray("matches").toString()))
            .put("hasMore",result.getBoolean("hasMore")).put("coverageComplete",result.getBoolean("coverageComplete"))
            .put("scannedFiles",result.getInt("scannedFiles")).put("scannedBytes",result.getLong("scannedBytes"))
            .put("skippedCount",result.getLong("skippedCount")).put("binarySkipped",result.getInt("binaryFilesSkippedForText"));
    }
    public JSONObject selected(int index)throws Exception{
        JSONArray matches=result.getJSONArray("matches");
        if(index<0||index>=matches.length())throw new SecurityException("请选择这页命中的一行");
        JSONObject hit=matches.getJSONObject(index);
        if(hit.getLong("bytes")>262144)throw new IllegalStateException("原件超过 256 KiB 编辑范围，只显示所在行的片段");
        return new JSONObject(hit.toString());
    }
    /** Re-read the same bytes and literal at the same LF/UTF-16 position
     * before any draft is opened, including the regular missing-file path. */
    public JSONObject anchor(JSONObject hit,JSONObject actual)throws Exception{
        if(!project.equals(actual.opt("projectId"))||!checkout.equals(actual.opt("checkoutId"))||actual.opt("deleted")!=Boolean.FALSE||
            !hit.getString("path").equals(actual.opt("path"))||!hit.getString("sha256").equals(actual.opt("expectedHash"))||
            hit.getLong("bytes")!=actual.getLong("bytes"))throw new IllegalStateException("命中后原文件已改变或移除，请重新搜索。未打开替代文件");
        String text=actual.getString("content");int line=hit.getInt("line"),column=hit.getInt("column"),start=0;
        if(line<1||column<1)throw new IllegalStateException("原行位置无效");
        for(int n=1;n<line;n++){int lf=text.indexOf('\n',start);if(lf<0)throw new IllegalStateException("原行位置已变化");start=lf+1;}
        int lf=text.indexOf('\n',start),length=(lf<0?text.length():lf)-start,local=column-1;
        if(local>length||literal.length()>length-local||!text.startsWith(literal,start+local))throw new IllegalStateException("无法核对同一行的原文，未定位到替代位置");
        return new JSONObject().put("path",hit.getString("path")).put("sha256",hit.getString("sha256")).put("line",line).put("column",column)
            .put("start",start+local).put("end",start+local+literal.length()).put("query",literal);
    }
}
