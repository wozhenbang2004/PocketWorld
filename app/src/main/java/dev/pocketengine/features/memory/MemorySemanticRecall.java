package dev.pocketengine.features.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Request-local rank/relationship projection. Input is the Book's current namespace/audience
 * allowlist, not raw model IDs. No retained bodies, vector cache, network or source authority. */
final class MemorySemanticRecall {
    static final int MAX_CANDIDATES=64,MAX_SEEDS=16,MAX_LINK_VISITS=128;
    private static final Set<String> STOP_WORDS=Set.of("a","an","and","are","as","at","be","by","did","do","does","for","from","how","i","in","is","it","me","my","of","on","or","that","the","their","there","these","they","this","to","was","were","what","when","where","which","who","with");
    private MemorySemanticRecall(){}

    static final class Candidate {
        final String id;final long revision;final List<String> path;final double score;final boolean exact;
        Candidate(JSONObject row,List<String> path,double score,boolean exact)throws Exception{
            id=row.getString("id");revision=row.getLong("revision");this.path=List.copyOf(path);this.score=score;this.exact=exact;
        }
    }
    static final class Result {
        final List<Candidate> candidates;final Map<String,JSONObject> rows;final int links,seeds;final boolean limited;
        Result(List<Candidate> candidates,Map<String,JSONObject> rows,int links,int seeds,boolean limited){this.candidates=List.copyOf(candidates);this.rows=Map.copyOf(rows);this.links=links;this.seeds=seeds;this.limited=limited;}
        JSONObject diagnostics()throws Exception{return new JSONObject().put("strategy","bounded_query_rrf_entity_paths_v1").put("candidateLimit",MAX_CANDIDATES)
                .put("seedLimit",MAX_SEEDS).put("linkVisitLimit",MAX_LINK_VISITS).put("linkVisits",links).put("seedCount",seeds).put("candidateCount",candidates.size())
                .put("partial",limited).put("stopReason",limited?"candidate_or_link_budget":"no_new_current_identity_links")
                .put("indexPersisted",false).put("extraModelRequests",0).put("extraNetworkRequests",0).put("rankIsTruthOrConfidence",false);}
    }
    static Result rank(List<JSONObject> admitted,List<JSONArray> channels,MemoryQueryPlan plan)throws Exception {
        if(admitted.size()>1000||channels.size()>MemoryQueryPlan.MAX_QUERIES)throw new IllegalArgumentException("检索投影超出原Book边界");
        Map<String,JSONObject> rows=new LinkedHashMap<>();Map<String,List<String>> byEntity=new LinkedHashMap<>();
        for(JSONObject row:admitted){
            String id=row.getString("id");rows.put(id,row);
            for(String entity:identities(row))byEntity.computeIfAbsent(entity,unused->new ArrayList<>()).add(id);
        }
        MemoryEntityAliasProjection aliases=MemoryEntityAliasProjection.from(admitted),directOnly=MemoryEntityAliasProjection.from(List.of());
        Map<String,Double> fused=new LinkedHashMap<>();Map<String,List<String>> seedPaths=new LinkedHashMap<>();Set<String> exact=new HashSet<>();
        for(int channel=0;channel<channels.size();channel++){
            JSONArray matches=channels.get(channel);String query=plan.queries().get(channel);Set<String> seen=new HashSet<>();
            for(int rank=0;rank<Math.min(1000,matches.length());rank++){
                JSONObject match=matches.getJSONObject(rank),row=rows.get(match.getString("id"));
                if(row==null||row.getLong("revision")!=match.getLong("revision")||!seen.add(row.getString("id")))continue;
                // Function-word-only hits do not seed a graph or claim semantic relevance.
                if(!meaningfulMatch(row,query,aliases))continue;
                String id=row.getString("id");List<String> path=List.of(id);
                if(!meaningfulMatch(row,query,directOnly)){
                    String donor=aliasDonor(admitted,row,query,aliases);if(donor==null)continue;path=List.of(donor,id);
                }
                if(!seedPaths.containsKey(id)||path.size()<seedPaths.get(id).size())seedPaths.put(id,path);
                fused.merge(id,(channel==0?1d:.8d)/(60+rank+1),Double::sum);
                if(channel==0&&LexicalMemoryRetrievalBackend.normalize(row.getString("text")).contains(LexicalMemoryRetrievalBackend.normalize(query))&&!query.isBlank())exact.add(id);
            }
        }
        List<String> ordered=new ArrayList<>(fused.keySet());ordered.sort(Comparator.<String>comparingInt(id->exact.contains(id)?0:1)
                .thenComparing(Comparator.<String>comparingDouble(fused::get).reversed()).thenComparing(id->id));
        Map<String,Candidate> selected=new LinkedHashMap<>();List<Candidate> frontier=new ArrayList<>();boolean limited=ordered.size()>MAX_CANDIDATES;int seeds=0,visits=0;
        for(String id:ordered){
            Candidate candidate=new Candidate(rows.get(id),seedPaths.get(id),fused.get(id),exact.contains(id));
            if(selected.size()<MAX_CANDIDATES)selected.put(id,candidate);
            if(seeds<MAX_SEEDS){frontier.add(candidate);seeds++;}
        }
        for(int hop=1;hop<=plan.constraints().maxHops&&!frontier.isEmpty();hop++){
            List<Candidate> next=new ArrayList<>();
            for(Candidate parent:frontier){
                Set<String> adjacent=new LinkedHashSet<>();for(String entity:identities(rows.get(parent.id)))adjacent.addAll(byEntity.getOrDefault(entity,List.of()));
                for(String id:adjacent){
                    if(id.equals(parent.id)||parent.path.contains(id))continue;
                    if(visits>=MAX_LINK_VISITS){limited=true;break;}visits++;
                    if(selected.containsKey(id))continue;
                    if(selected.size()>=MAX_CANDIDATES){limited=true;break;}
                    List<String> path=new ArrayList<>(parent.path);path.add(id);
                    Candidate candidate=new Candidate(rows.get(id),path,parent.score*.7d,false);selected.put(id,candidate);next.add(candidate);
                }
                if(visits>=MAX_LINK_VISITS||selected.size()>=MAX_CANDIDATES)break;
            }
            frontier=next;if(visits>=MAX_LINK_VISITS||selected.size()>=MAX_CANDIDATES)break;
        }
        List<Candidate> result=new ArrayList<>();for(Candidate candidate:selected.values())if(plan.constraints().matches(rows.get(candidate.id)))result.add(candidate);
        result.sort(Comparator.<Candidate>comparingInt(c->c.exact?0:1).thenComparing(Comparator.comparingDouble((Candidate c)->c.score).reversed()).thenComparing(c->c.id));
        return new Result(result,rows,visits,seeds,limited);
    }
    /** Only typed stable IDs join edges. Display-name coincidence and the global owner hub
     * never connect arbitrary private facts. A missing explicit identity has no graph fallback. */
    private static Set<String> identities(JSONObject row)throws Exception {
        JSONObject a=row.optJSONObject("assertion");if(a==null)return Set.of();Set<String> result=new LinkedHashSet<>();
        addIdentity(result,a.optString("subjectType"),a.optString("subjectId"));
        if(a.has("objectId"))addIdentity(result,a.optString("objectType"),a.optString("objectId"));return result;
    }
    private static void addIdentity(Set<String> into,String type,String id){if(!id.isEmpty()&&(type.equals("person")||type.equals("entity")))into.add(type+'\0'+id);}
    private static boolean meaningfulMatch(JSONObject row,String query,MemoryEntityAliasProjection aliases)throws Exception {
        return meaningfulText(MemoryBook.retrievalDocument(row,aliases).text,query);
    }
    private static boolean meaningfulText(String source,String query){
        String normalized=LexicalMemoryRetrievalBackend.normalize(query);if(normalized.isEmpty())return false;
        String text=LexicalMemoryRetrievalBackend.normalize(source);
        if(text.contains(normalized)&&!STOP_WORDS.contains(normalized))return true;
        for(String term:LexicalMemoryRetrievalBackend.terms(normalized))if(!STOP_WORDS.contains(term)&&text.contains(term))return true;
        return false;
    }
    private static String aliasDonor(List<JSONObject> admitted,JSONObject row,String query,MemoryEntityAliasProjection aliases)throws Exception{
        Set<String> targets=identities(row);
        for(JSONObject donor:admitted){JSONObject assertion=donor.optJSONObject("assertion");if(assertion==null||donor.getString("id").equals(row.getString("id")))continue;
            String identity=assertion.getString("subjectType")+'\0'+assertion.getString("subjectId");
            if(targets.contains(identity)&&aliases.mayDonateToQuery(donor,query)&&meaningfulText(assertion.getString("subjectLabel"),query))return donor.getString("id");
        }return null;
    }
    static JSONArray pathRefs(Result result,Candidate candidate)throws Exception {
        JSONArray refs=new JSONArray();for(String id:candidate.path){JSONObject row=result.rows.get(id);refs.put(new JSONObject().put("id",id).put("revision",row.getLong("revision")));}return refs;
    }
}
