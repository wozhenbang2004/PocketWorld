package dev.pocketengine.features.memory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONObject;

/** Disposable retrieval terms over already admitted formal rows, not an identity registry.
 * Callers must filter namespace, exact audience/revision, temporal state and live sources first.
 * Equal display names never join IDs; only an explicit equal subject type + stable ID can. */
final class MemoryEntityAliasProjection {
    static final int MAX_LABELS_PER_ID=8;
    private final Map<String,TreeSet<String>> labels=new TreeMap<>();
    private MemoryEntityAliasProjection(){}
    static MemoryEntityAliasProjection from(Collection<JSONObject> admitted)throws Exception {
        MemoryEntityAliasProjection projection=new MemoryEntityAliasProjection();
        for(JSONObject row:admitted){
            JSONObject assertion=row.optJSONObject("assertion");if(assertion==null)continue;
            String label=LexicalMemoryRetrievalBackend.normalize(assertion.getString("subjectLabel"));if(label.isEmpty())continue;
            TreeSet<String> aliases=projection.labels.computeIfAbsent(key(assertion.getString("subjectType"),assertion.getString("subjectId")),unused->new TreeSet<>());
            aliases.add(label);if(aliases.size()>MAX_LABELS_PER_ID)aliases.pollLast();
        }
        return projection;
    }
    boolean mayDonateToQuery(JSONObject row,String query)throws Exception {
        JSONObject assertion=row.optJSONObject("assertion");if(assertion==null||query.isEmpty())return false;
        String label=LexicalMemoryRetrievalBackend.normalize(assertion.getString("subjectLabel"));
        Set<String> kept=labels.get(key(assertion.getString("subjectType"),assertion.getString("subjectId")));
        return kept!=null&&kept.contains(label)&&LexicalMemoryRetrievalBackend.score(new MemoryRetrievalBackend.Document("","","",1,label,0,0),query)>=0;
    }
    String terms(JSONObject assertion)throws Exception {
        if(assertion==null)return "";Set<String> selected=new LinkedHashSet<>();
        add(selected,assertion.getString("subjectType"),assertion.getString("subjectId"));
        if(assertion.has("objectId"))add(selected,assertion.getString("objectType"),assertion.getString("objectId"));
        return String.join("\n",selected);
    }
    private void add(Set<String> into,String type,String id){Set<String> aliases=labels.get(key(type,id));if(aliases!=null)into.addAll(aliases);}
    private static String key(String type,String id){return type+'\0'+id;}
}
