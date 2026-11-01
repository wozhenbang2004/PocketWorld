package dev.pocketengine.features.memory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Existing lexical/CJK-pair behavior, retained as an explicit dependency-free fallback. */
public final class LexicalMemoryRetrievalBackend implements MemoryRetrievalBackend {
    private List<Document> documents=new ArrayList<>();
    private final String reason;
    public LexicalMemoryRetrievalBackend(){this("default_compatibility_backend");}
    public LexicalMemoryRetrievalBackend(String reason){this.reason=reason;}
    @Override public synchronized void synchronize(List<Document> current){documents=new ArrayList<>(current);}
    @Override public synchronized List<Match> find(String owner,String space,String query,int limit){
        String normalized=normalize(query);List<Hit> hits=new ArrayList<>();
        for(Document document:documents)if(owner.equals(document.ownerId)&&space.equals(document.spaceId)){
            double score=score(document,normalized);if(score>=0)hits.add(new Hit(document,score));
        }
        hits.sort(Comparator.comparingDouble((Hit h)->h.score).reversed().thenComparing(Comparator.comparingLong((Hit h)->h.document.updatedAt).reversed()));
        List<Match> result=new ArrayList<>();for(int i=0;i<Math.min(limit,hits.size());i++)result.add(new Match(hits.get(i).document.id,hits.get(i).document.revision));return result;
    }
    static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();}
    static List<String> terms(String value){
        List<String> result=new ArrayList<>();for(String part:value.split("[^\\p{L}\\p{N}]+")){
            if(part.isEmpty())continue;int[] points=part.codePoints().toArray();boolean cjk=part.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN);
            if(!cjk||points.length==1)result.add(part);else for(int i=0;i+1<points.length;i++)result.add(new String(points,i,2));
        }return result;
    }
    static double score(Document document,String query){
        if(query.isEmpty())return 0;String text=normalize(document.text);double score=text.contains(query)?8:0;List<String> terms=terms(query);int matched=0;
        for(String term:terms)if(text.contains(term))matched++;
        if(matched==0&&score==0)return -1;
        return score+(terms.isEmpty()?0:4d*matched/terms.size())+document.confidence*.5;
    }
    @Override public synchronized void invalidate(){documents.clear();}
    @Override public JSONObject status()throws Exception{return new JSONObject().put("backend","lexical_cjk_pairs").put("fts5",false).put("fts4",false).put("fallbackReason",reason).put("indexAuthority",false).put("persisted",false);}
    @Override public void close(){invalidate();}
    private static final class Hit{final Document document;final double score;Hit(Document d,double score){document=d;this.score=score;}}
}
