package dev.pocketengine.features.local.project;

import java.text.Normalizer;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded inventory of existing checkout paths; no root, command, file content or writer is retained. */
public final class CheckoutPathMatches {
    public record Hit(String path,String sha,long bytes,boolean binary,String change,int rank) {}
    private final String project,checkout,digest,query,filter;
    private final List<Hit> hits;private final int inventory;
    public final String handle;
    private CheckoutPathMatches(String p,String c,String d,String q,String f,List<Hit> h,int existing){
        project=p;checkout=c;digest=d;query=q;filter=f;hits=List.copyOf(h);inventory=existing;handle=UUID.randomUUID().toString();
    }
    private static String folded(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static long number(JSONObject o,String key){Object v=o.opt(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<0)throw new IllegalStateException("文件元数据不完整");return ((Number)v).longValue();}
    public static CheckoutPathMatches capture(String project,String checkout,JSONObject source,String query,String filter)throws Exception{
        if(project.isBlank()||checkout.isBlank()||!project.equals(source.opt("projectId"))||!checkout.equals(source.opt("checkoutId"))||
            !source.optString("sourceDigest").matches("[a-f0-9]{64}"))throw new SecurityException("不是原私人副本的文件清单");
        if(query==null||query.length()>128||query.chars().anyMatch(Character::isISOControl)||!Set.of("all","changed").contains(filter))throw new IllegalArgumentException("请用简短的文件名或路径查找");
        String q=folded(query.trim());if(q.isBlank()&&filter.equals("all"))throw new IllegalArgumentException("请填写名称或选择查看已变化文件");
        String[] words=q.isBlank()?new String[0]:q.split("\\s+");if(words.length>8)throw new IllegalArgumentException("查询最多使用八个词");
        JSONArray list=source.getJSONArray("files");if(list.length()>2*ProjectSources.MAX_FILES)throw new IllegalStateException("原副本清单超过有界范围，未提供不完整搜索");
        Set<String> paths=new HashSet<>();List<Hit> matches=new ArrayList<>();int existing=0;
        for(int i=0;i<list.length();i++){
            JSONObject r=list.getJSONObject(i);String path=r.getString("path"),state=r.getString("change");
            if(path.isBlank()||path.length()>240||path.indexOf(0)>=0||!paths.add(path))throw new IllegalStateException("原副本路径不唯一或无法核对");
            if(state.equals("deleted"))continue;if(!Set.of("unchanged","modified","added").contains(state))throw new IllegalStateException("原副本文件状态未知");
            Object binary=r.opt("binary");long bytes=number(r,"bytes");String sha=r.getString("sha256");
            if(!(binary instanceof Boolean)||!sha.matches("[a-f0-9]{64}"))throw new IllegalStateException("原副本文件缺少可核对的版本");
            existing++;if(filter.equals("changed")&&state.equals("unchanged"))continue;
            String name=folded(path),base=name.substring(name.lastIndexOf('/')+1);boolean hit=true;for(String word:words)if(!name.contains(word)){hit=false;break;}if(!hit)continue;
            int rank=q.isEmpty()?4:name.equals(q)?0:base.equals(q)?1:base.startsWith(q)?2:base.contains(q)?3:4;
            matches.add(new Hit(path,sha,bytes,(Boolean)binary,state,rank));
        }
        if(existing!=number(source,"fileCount")||existing>ProjectSources.MAX_FILES)throw new IllegalStateException("没有取得完整的原副本文件清单");
        matches.sort(Comparator.comparingInt(Hit::rank).thenComparing(Hit::path));return new CheckoutPathMatches(project,checkout,source.getString("sourceDigest"),query,filter,matches,existing);
    }
    public JSONObject page(String expected,int start)throws Exception{
        if(!handle.equals(expected)||start<0||start>hits.size()||start%40!=0)throw new SecurityException("请从原查询继续翻页");
        JSONArray rows=new JSONArray();int end=Math.min(start+40,hits.size());for(int i=start;i<end;i++){
            Hit h=hits.get(i);rows.put(new JSONObject().put("hit",i).put("path",h.path()).put("sha256",h.sha()).put("bytes",h.bytes()).put("binary",h.binary()).put("change",h.change()));
        }
        return new JSONObject().put("searchHandle",handle).put("projectId",project).put("checkoutId",checkout).put("sourceDigest",digest)
            .put("query",query).put("filter",filter).put("fileCount",inventory).put("total",hits.size()).put("start",start).put("next",end<hits.size()?end:-1).put("files",rows);
    }
    public Hit selected(String expected,int index){if(!handle.equals(expected)||index<0||index>=hits.size())throw new SecurityException("未选择原查询中的文件");
        Hit hit=hits.get(index);if(hit.binary()||hit.bytes()>262144)throw new SecurityException("二进制或超过 256 KiB 的原件仅列信息，不作为文本草稿打开");return hit;}
    public static void requireStillExact(Hit hit,JSONObject actual){
        if(hit==null||actual.opt("deleted")!=Boolean.FALSE||!hit.path().equals(actual.opt("path"))||!hit.sha().equals(actual.opt("expectedHash")))
            throw new IllegalStateException("命中后原文件已变化或移除，请重新查找；没有为旧路径准备新文件");
    }
}
