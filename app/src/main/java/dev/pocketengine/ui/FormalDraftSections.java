package dev.pocketengine.ui;

import dev.pocketengine.features.workspace.VersionedWorkspace;
import dev.pocketengine.features.workspace.WorkspaceModule;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact, owner-selected line edits from a kept draft. Never opens another file or writes a version. */
public final class FormalDraftSections {
    private FormalDraftSections(){}
    private static final int LINES=760,CELLS=400_000,BLOCKS=32,REVIEW_CHARS=32768;
    private static IllegalStateException limited(){return new IllegalStateException("改动超出手机逐段核对范围；原稿仍完整保留，未忽略部分改动或自动合并");}
    private static SecurityException stale(){return new SecurityException("原项目、保留稿或当前正式版本已变化，请重新核对");}
    private static String body(VersionedWorkspace.FileSnapshot f)throws Exception{
        if(f.tombstone||!"text".equals(f.kind)||f.sizeBytes>FormalDraftShelf.MAX_BYTES)throw stale();
        byte[] b=f.bytes();
        try{String actual=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
            if(actual.length()>FormalDraftShelf.MAX_TEXT)throw limited();return actual;
        }finally{java.util.Arrays.fill(b,(byte)0);}
    }
    private record Edit(int first,int end,int next,int nextEnd){}
    private record Block(Edit edit,int position,String status,String oldText,String newText){}
    private static List<String> lines(String text){
        List<String> out=new ArrayList<>();int first=0;
        for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n'){
            if(out.size()>=LINES)throw limited();out.add(text.substring(first,i+1));first=i+1;
        }
        if(first<text.length())out.add(text.substring(first));if(out.size()>LINES)throw limited();return out;
    }
    private static List<Edit> edits(List<String> a,List<String> b){
        int n=a.size(),m=b.size();if((long)(n+1)*(m+1)>CELLS)throw limited();
        short[][] same=new short[n+1][m+1];
        for(int i=n-1;i>=0;i--)for(int j=m-1;j>=0;j--)
            same[i][j]=a.get(i).equals(b.get(j))?(short)(1+same[i+1][j+1]):(short)Math.max(same[i+1][j],same[i][j+1]);
        List<Edit> all=new ArrayList<>();int i=0,j=0;
        while(i<n||j<m){
            if(i<n&&j<m&&a.get(i).equals(b.get(j))){i++;j++;continue;}
            int first=i,next=j;
            while((i<n||j<m)&&!(i<n&&j<m&&a.get(i).equals(b.get(j)))){
                if(j<m&&(i==n||same[i][j+1]>=same[i+1][j]))j++;else i++;
            }
            all.add(new Edit(first,i,next,j));if(all.size()>BLOCKS)throw limited();
        }return all;
    }
    private static boolean touches(Edit a,Edit b){
        if(a.first==a.end)return b.first<=a.first&&a.first<=b.end;
        if(b.first==b.end)return a.first<=b.first&&b.first<=a.end;
        return a.first<b.end&&b.first<a.end;
    }
    /** Stops on the second hit: never use a fuzzy match, nearest offset, basename or first duplicate. */
    private static final class Budget{int remaining=600_000;boolean equal(String a,String b){if(--remaining<0)throw limited();return a.equals(b);}}
    private static int only(List<String> hay,List<String> needle,Budget budget){
        if(needle.isEmpty())return -1;int found=-1;
        for(int i=0;i<=hay.size()-needle.size();i++){
            int j=0;while(j<needle.size()&&budget.equal(hay.get(i+j),needle.get(j)))j++;
            if(j==needle.size()){if(found>=0)return -1;found=i;}
        }return found;
    }
    private static boolean atUniqueOriginal(List<String> base,List<String> now,Edit change,int position,Budget budget){
        int span=change.end-change.first;if(position<0||position+span>now.size())return false;
        for(int left=0;left<=Math.min(2,Math.min(change.first,position));left++)
            for(int right=0;right<=Math.min(2,Math.min(base.size()-change.end,now.size()-position-span));right++){
                List<String> exact=base.subList(change.first-left,change.end+right);
                if(!exact.isEmpty()&&only(base,exact,budget)==change.first-left&&only(now,exact,budget)==position-left)return true;
            }
        return false;
    }
    public static final class Plan{
        public final String project,path,baseSha,baseRevision,draftSha,headSha,headRevision;
        public final int baseVersion,headVersion;
        private final List<String> original;private final List<Block> blocks;
        private Plan(VersionedWorkspace.FileSnapshot base,VersionedWorkspace.FileSnapshot head,String kept,List<String> original,List<Block> blocks){
            project=head.projectId;path=head.path;baseVersion=base.version;baseSha=base.sha256;baseRevision=base.revisionId;headVersion=head.version;
            headRevision=head.revisionId;headSha=head.sha256;draftSha=kept;this.original=List.copyOf(original);this.blocks=List.copyOf(blocks);
        }
        /** A window retains only this digest of exact native identities, never any file text. */
        public String fingerprint()throws Exception{return ProjectFileDeliveryBytes.sha(String.join("\0","formal-sections-v1",project,path,baseRevision,baseSha,
                Integer.toString(baseVersion),draftSha,headRevision,headSha,Integer.toString(headVersion)).getBytes(StandardCharsets.UTF_8));}
        public boolean current(VersionedWorkspace.HeadObservation head){return head!=null&&head.authorityKnown&&!head.deleted&&project.equals(head.projectId)&&path.equals(head.path)
                &&headVersion==head.version&&headRevision.equals(head.revisionId)&&headSha.equals(head.sha256);}
        public JSONObject view()throws Exception{
            JSONArray rows=new JSONArray();for(int i=0;i<blocks.size();i++){Block b=blocks.get(i);rows.put(new JSONObject().put("id",i).put("status",b.status)
                .put("baseLine",b.edit.first+1).put("oldLines",b.edit.end-b.edit.first).put("newLines",b.edit.nextEnd-b.edit.next).put("currentLine","ready".equals(b.status)?b.position+1:JSONObject.NULL)
                .put("before",b.oldText).put("kept",b.newText));}
            return new JSONObject().put("baseVersion",baseVersion).put("baseRevision",baseRevision).put("baseSha",baseSha).put("headVersion",headVersion)
                .put("headRevision",headRevision).put("headSha",headSha).put("draftSha",draftSha).put("changes",rows);
        }
        public String selected(JSONArray ids)throws Exception{
            if(ids.length()<1||ids.length()>blocks.size())throw stale();HashSet<Integer> seen=new HashSet<>();List<Block> chosen=new ArrayList<>();
            for(int i=0;i<ids.length();i++){Object n=ids.get(i);if(!(n instanceof Integer||n instanceof Long))throw stale();long p=((Number)n).longValue();
                if(p<0||p>=blocks.size()||!seen.add((int)p)||!"ready".equals(blocks.get((int)p).status))throw stale();chosen.add(blocks.get((int)p));}
            chosen.sort(Comparator.comparingInt(Block::position));int cursor=0;StringBuilder next=new StringBuilder();
            for(Block b:chosen){int end=b.position+b.edit.end-b.edit.first;
                if(b.position<cursor||end>original.size()||!String.join("",original.subList(b.position,end)).equals(b.oldText))throw stale();
                for(int i=cursor;i<b.position;i++)next.append(original.get(i));next.append(b.newText);cursor=end;}
            for(int i=cursor;i<original.size();i++)next.append(original.get(i));
            String result=next.toString();if(result.length()>FormalDraftShelf.MAX_TEXT||result.getBytes(StandardCharsets.UTF_8).length>FormalDraftShelf.MAX_BYTES)throw limited();
            return result;
        }
    }
    /** No continuation through a deleted/recreated path, retired base, binary revision or another current head. */
    public static Plan inspect(WorkspaceModule workspace,String project,String path,int shown,JSONObject kept)throws Exception{
        if(!project.equals(kept.opt("projectId"))||!path.equals(kept.opt("path")))throw stale();int version=kept.getInt("baseVersion");
        if(version<1||shown<=version||((long)shown-version)>=8)throw new IllegalStateException("所选草稿的连续正式历史已不可核对；没有按同名文件套用");
        VersionedWorkspace.FileSnapshot base=workspace.fileSnapshotForHost(project,path,version),head=null;
        if(!base.projectId.equals(project)||!base.path.equals(path)||base.version!=version||!base.revisionId.equals(kept.opt("baseRevisionId"))||!base.sha256.equals(kept.opt("baseSha256")))throw stale();
        String before=body(base);for(int step=1;step<=shown-version;step++){int v=version+step;head=workspace.fileSnapshotForHost(project,path,v);
            if(!head.projectId.equals(project)||!head.path.equals(path)||head.version!=v||head.tombstone||!"text".equals(head.kind))throw new IllegalStateException("这段历史中原文件已被移除或改为非文字；没有把旧稿套用到同名文件");}
        String saved=kept.getString("text"),current=body(head),draftSha=ProjectFileDeliveryBytes.sha(saved.getBytes(StandardCharsets.UTF_8));
        if(!draftSha.equals(kept.opt("sha256")))throw stale();List<String> baseLines=lines(before),draft=lines(saved),now=lines(current);
        List<Edit> mine=edits(baseLines,draft),theirs=edits(baseLines,now);List<Block> blocks=new ArrayList<>();int chars=0;Budget budget=new Budget();
        for(Edit m:mine){
            String old=String.join("",baseLines.subList(m.first,m.end)),wanted=String.join("",draft.subList(m.next,m.nextEnd));chars+=old.length()+wanted.length();
            if(chars>REVIEW_CHARS)throw limited();int shift=0;String state="ready";
            for(Edit t:theirs){
                if(touches(m,t)){state=(m.first==t.first&&m.end==t.end&&draft.subList(m.next,m.nextEnd).equals(now.subList(t.next,t.nextEnd)))?"present":"conflict";break;}
                if(t.end<=m.first)shift+=(t.nextEnd-t.next)-(t.end-t.first);
            }
            int at=m.first+shift;if("ready".equals(state)&&!atUniqueOriginal(baseLines,now,m,at,budget))state="ambiguous";
            blocks.add(new Block(m,at,state,old,wanted));
        }
        Plan plan=new Plan(base,head,draftSha,now,blocks);if(!plan.current(workspace.refreshFileHeadForHost(project,path)))throw stale();return plan;
    }
}
