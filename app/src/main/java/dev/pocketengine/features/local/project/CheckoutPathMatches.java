package dev.pocketengine.features.local.project;

import java.text.Normalizer;
import java.util.*;
import dev.pocketengine.features.workspace.VersionedWorkspace;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded inventory of existing checkout paths; no root, command, file content or writer is retained. */
public final class CheckoutPathMatches {
    private static final String REVIEW_CHANGES="review_changes";
    public static final int MAX_REVIEW_PATHS=2*ProjectSources.MAX_FILES;
    public record Hit(String path,String sha,long bytes,boolean binary,String change,int rank,String beforeHash) {
        public Hit(String path,String sha,long bytes,boolean binary,String change,int rank){this(path,sha,bytes,binary,change,rank,null);}
    }
    private final String project,checkout,digest,query,filter,baselineIdentity;
    private final List<Hit> hits;private final int inventory,removed;
    private final boolean directoryInventory;
    public final String handle;
    private CheckoutPathMatches(String p,String c,String d,String q,String f,List<Hit> h,int existing,boolean directories,
            String baseline,int removed){
        project=p;checkout=c;digest=d;query=q;filter=f;hits=List.copyOf(h);inventory=existing;directoryInventory=directories;
        baselineIdentity=baseline;this.removed=removed;handle=UUID.randomUUID().toString();
    }
    private static String folded(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    private static long number(JSONObject o,String key){Object v=o.opt(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<0)throw new IllegalStateException("文件元数据不完整");return ((Number)v).longValue();}
    public static CheckoutPathMatches capture(String project,String checkout,JSONObject source,String query,String filter)throws Exception{
        return capture(project,checkout,source,query,filter,false);
    }
    /** The same already-read ordinary file inventory; never re-enumerates the disk or retains bodies. */
    public static CheckoutPathMatches directoryInventory(String project,String checkout,JSONObject source)throws Exception{
        return capture(project,checkout,source,"","all",true);
    }
    private static CheckoutPathMatches capture(String project,String checkout,JSONObject source,String query,String filter,boolean directories)throws Exception{
        if(project.isBlank()||checkout.isBlank()||!project.equals(source.opt("projectId"))||!checkout.equals(source.opt("checkoutId"))||
            !source.optString("sourceDigest").matches("[a-f0-9]{64}"))throw new SecurityException("不是原私人副本的文件清单");
        if(query==null||query.length()>128||query.chars().anyMatch(Character::isISOControl)||!Set.of("all","changed",REVIEW_CHANGES).contains(filter))throw new IllegalArgumentException("请用简短的文件名或路径查找");
        boolean review=REVIEW_CHANGES.equals(filter);
        String baseline=review?source.optString("baselineIdentity"):null;
        if(review&&(directories||!query.isEmpty()||!baseline.matches("[a-f0-9]{64}")))
            throw new SecurityException("没有取得原副本的已核对基线");
        String q=folded(query.trim());if(!directories&&q.isBlank()&&filter.equals("all"))throw new IllegalArgumentException("请填写名称或选择查看已变化文件");
        String[] words=q.isBlank()?new String[0]:q.split("\\s+");if(words.length>8)throw new IllegalArgumentException("查询最多使用八个词");
        JSONArray list=source.getJSONArray("files");if(list.length()>MAX_REVIEW_PATHS)throw new IllegalStateException("原副本清单超过有界范围，未提供不完整搜索");
        Set<String> paths=new HashSet<>();List<Hit> matches=new ArrayList<>();int existing=0,removed=0;
        for(int i=0;i<list.length();i++){
            JSONObject r=list.getJSONObject(i);String path=r.getString("path"),state=r.getString("change");
            if(path.isBlank()||path.length()>240||path.indexOf(0)>=0||!paths.add(path))throw new IllegalStateException("原副本路径不唯一或无法核对");
            if(state.equals("deleted")){
                // Only files in this existing source inventory can be missing from it.
                if(review&&ProjectSources.sourcePath(path)){
                    VersionedWorkspace.path(path);
                    String before=r.optString("beforeHash");
                    if(!before.matches("[a-f0-9]{64}")||!"absent".equals(r.opt("sha256"))||number(r,"bytes")!=0||
                        !(r.opt("binary") instanceof Boolean binary))throw new SecurityException("原基线缺失项无法核对");
                    matches.add(new Hit(path,"absent",0,binary,state,4,before));removed++;
                }
                continue;
            }
            if(!Set.of("unchanged","modified","added").contains(state))throw new IllegalStateException("原副本文件状态未知");
            Object binary=r.opt("binary");long bytes=number(r,"bytes");String sha=r.getString("sha256");
            if(!(binary instanceof Boolean)||!sha.matches("[a-f0-9]{64}"))throw new IllegalStateException("原副本文件缺少可核对的版本");
            existing++;
            if(directories){matches.add(new Hit(path,sha,bytes,(Boolean)binary,state,0));continue;}
            if((review||filter.equals("changed"))&&state.equals("unchanged"))continue;
            String name=folded(path),base=name.substring(name.lastIndexOf('/')+1);boolean hit=true;for(String word:words)if(!name.contains(word)){hit=false;break;}if(!hit)continue;
            int rank=q.isEmpty()?4:name.equals(q)?0:base.equals(q)?1:base.startsWith(q)?2:base.contains(q)?3:4;
            matches.add(new Hit(path,sha,bytes,(Boolean)binary,state,rank));
        }
        if(existing!=number(source,"fileCount")||existing>ProjectSources.MAX_FILES)throw new IllegalStateException("没有取得完整的原副本文件清单");
        matches.sort(Comparator.comparingInt(Hit::rank).thenComparing(Hit::path));
        return new CheckoutPathMatches(project,checkout,source.getString("sourceDigest"),query,filter,matches,existing,directories,baseline,removed);
    }
    public JSONObject page(String expected,int start)throws Exception{
        if(!handle.equals(expected)||start<0||start>hits.size()||start%40!=0)throw new SecurityException("请从原查询继续翻页");
        JSONArray rows=new JSONArray();int end=Math.min(start+40,hits.size());for(int i=start;i<end;i++){
            Hit h=hits.get(i);JSONObject row=new JSONObject().put("hit",i).put("path",h.path()).put("sha256",h.sha()).put("bytes",h.bytes()).put("binary",h.binary()).put("change",h.change());
            if("deleted".equals(h.change()))row.put("beforeHash",h.beforeHash());
            rows.put(row);
        }
        JSONObject result=new JSONObject().put("searchHandle",handle).put("projectId",project).put("checkoutId",checkout).put("sourceDigest",digest)
            .put("query",query).put("filter",filter).put("fileCount",inventory).put("total",hits.size()).put("start",start).put("next",end<hits.size()?end:-1).put("files",rows);
        if(baselineIdentity!=null)result.put("baselineIdentity",baselineIdentity).put("removedCount",removed).put("readOnly",true);
        return result;
    }
    public Hit selected(String expected,int index){if(!handle.equals(expected)||index<0||index>=hits.size())throw new SecurityException("未选择原查询中的文件");
        Hit hit=hits.get(index);
        if("deleted".equals(hit.change()))throw new SecurityException("副本中缺失的基线文件只列信息，不打开文件");
        if(hit.binary()||hit.bytes()>262144)throw new SecurityException("二进制或超过 256 KiB 的原件仅列信息，不作为文本草稿打开");return hit;}
    /** Direct children of an actual path prefix; folders themselves are not file hits. */
    public DirectoryPage directoryPage(String directory,int start)throws Exception {
        if(!directoryInventory)throw new SecurityException("当前清单不是原副本目录");
        if(!directory.isEmpty())VersionedWorkspace.path(directory);
        String prefix=directory.isEmpty()?"":directory+"/";
        SortedSet<String> folders=new TreeSet<>();List<Hit> files=new ArrayList<>();
        for(Hit hit:hits){
            if(!hit.path().startsWith(prefix))continue;
            int slash=hit.path().indexOf('/',prefix.length());
            if(slash<0)files.add(hit);else folders.add(hit.path().substring(0,slash));
        }
        int total=folders.size()+files.size();
        if(start<0||start%40!=0||(start>0&&start>=total))throw new SecurityException("请从原目录继续翻页");
        int end=Math.min(total,start+40),i=0;
        JSONArray result=new JSONArray();List<Hit> offered=new ArrayList<>();
        for(String folder:folders){if(i>=end)break;if(i++>=start)result.put(new JSONObject().put("kind","directory").put("path",folder));}
        for(int j=Math.max(0,start-folders.size());j<end-folders.size();j++){
            Hit hit=files.get(j);result.put(new JSONObject().put("kind","file").put("path",hit.path()).put("sha256",hit.sha())
                .put("binary",hit.binary()).put("bytes",hit.bytes()).put("hit",offered.size()));offered.add(hit);
        }
        return new DirectoryPage(this,directory,start,end<total?end:-1,total,result,List.copyOf(offered));
    }
    public static final class DirectoryPage {
        public final String handle=UUID.randomUUID().toString();
        public final CheckoutPathMatches inventory;
        private final String directory;private final int start,next,total;
        private final JSONArray rows;private final List<Hit> offered;
        private DirectoryPage(CheckoutPathMatches inventory,String directory,int start,int next,int total,JSONArray rows,List<Hit> offered){
            this.inventory=inventory;this.directory=directory;this.start=start;this.next=next;this.total=total;this.rows=rows;this.offered=offered;
        }
        public JSONObject view(String filesHandle)throws Exception {
            return new JSONObject().put("format","pocketworld.checkout-directory.v1").put("filesHandle",filesHandle)
                .put("projectId",inventory.project).put("checkoutId",inventory.checkout).put("sourceDigest",inventory.digest)
                .put("inventoryHandle",inventory.handle).put("directoryHandle",handle).put("directory",directory)
                .put("start",start).put("next",next).put("total",total).put("rows",rows).put("readOnly",true).put("executionAuthorized",false);
        }
        public DirectoryPage move(String target,int index)throws Exception {
            if(directory.equals(target)){
                if(index!=next&&!(start>0&&index==start-40))throw new SecurityException("请从当前原目录页翻页");
            }else{
                int slash=directory.lastIndexOf('/');String parent=slash<0?"":directory.substring(0,slash);
                boolean shown=target.isEmpty()||target.equals(parent);
                for(int i=0;!shown&&i<rows.length();i++){
                    JSONObject row=rows.getJSONObject(i);
                    shown="directory".equals(row.opt("kind"))&&target.equals(row.opt("path"));
                }
                if(index!=0||!shown)throw new SecurityException("请选择当前清单里的目录");
            }
            return inventory.directoryPage(target,index);
        }
        public Hit selected(int index){
            if(index<0||index>=offered.size())throw new SecurityException("未选择这页原目录中的文件");
            Hit hit=offered.get(index);
            if(hit.binary()||hit.bytes()>262144)throw new SecurityException("二进制或超过 256 KiB 的原件仅列信息");
            return hit;
        }
    }
    public static void requireStillExact(Hit hit,JSONObject actual){
        if(hit==null||actual.opt("deleted")!=Boolean.FALSE||!hit.path().equals(actual.opt("path"))||!hit.sha().equals(actual.opt("expectedHash")))
            throw new IllegalStateException("命中后原文件已变化或移除，请重新查找；没有为旧路径准备新文件");
    }
}
