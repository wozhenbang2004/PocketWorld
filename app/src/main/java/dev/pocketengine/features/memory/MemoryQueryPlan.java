package dev.pocketengine.features.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Bounded retrieval suggestions, never a source/scope grant or an embedding index. The
 * original query is always retained so a rewrite cannot silently replace an exact ID/path. */
public final class MemoryQueryPlan {
    public static final int MAX_QUERIES=4,MAX_QUERY_CHARS=500,MAX_TOTAL_CHARS=1500;
    private static final Set<String> FALLBACKS=Set.of("not_requested","empty_query");
    private final List<String> queries;private final String status,reason;
    private MemoryQueryPlan(List<String> queries,String status,String reason){this.queries=List.copyOf(queries);this.status=status;this.reason=reason;}
    public static MemoryQueryPlan lexical(String original,String reason){
        String query=checked(original);if(!FALLBACKS.contains(reason))throw new IllegalArgumentException("未知检索降级原因");
        return new MemoryQueryPlan(List.of(query),"lexical",reason);
    }
    /** The existing metered MAIN may suggest retrieval phrases. They remain untrusted query text. */
    public static MemoryQueryPlan agentSuggested(String original,JSONArray suggestions)throws Exception{
        java.util.Objects.requireNonNull(suggestions);if(suggestions.length()==0)return lexical(original,"not_requested");
        return rewritten(original,new JSONObject().put("queries",new JSONArray(suggestions.toString())).toString());
    }
    static MemoryQueryPlan rewritten(String original,String response)throws Exception{
        String query=checked(original);if(query.isEmpty())throw new IllegalArgumentException("空主查询不能由建议替代");
        if(response==null||response.length()>4096)throw new IllegalArgumentException("改写结果超过本次预算");
        JSONTokener parser=new JSONTokener(response);Object parsed=parser.nextValue();
        if(!(parsed instanceof JSONObject)||parser.nextClean()!=0)throw new IllegalArgumentException("改写结果不是单个JSON对象");
        JSONObject value=(JSONObject)parsed;if(value.length()!=1||!value.has("queries"))throw new IllegalArgumentException("改写只能提供查询建议，不能提供来源或权限");
        JSONArray input=value.getJSONArray("queries");if(input.length()<1||input.length()>MAX_QUERIES-1)throw new IllegalArgumentException("最多追加三个短查询");
        LinkedHashSet<String> unique=new LinkedHashSet<>();unique.add(query);int total=query.length();
        for(int i=0;i<input.length();i++){
            Object raw=input.get(i);if(!(raw instanceof String))throw new IllegalArgumentException("改写查询必须为文字");String next=checked((String)raw);
            if(next.isEmpty())throw new IllegalArgumentException("改写查询不能为空");if(unique.add(next))total=Math.addExact(total,next.length());
        }
        if(total>MAX_TOTAL_CHARS)throw new IllegalArgumentException("改写查询总长度超过预算");
        return new MemoryQueryPlan(List.copyOf(unique),"agent_query_rewrite","bounded_query_suggestions");
    }
    public List<String> queries(){return queries;}
    public JSONObject metadata()throws Exception{return new JSONObject().put("strategy",status).put("reason",reason).put("queryCount",queries.size()).put("scopeChanged",false).put("executionAuthorized",false).put("modelUseVerified",false);}
    private static String checked(String value){
        if(value==null||value.length()>MAX_QUERY_CHARS)throw new IllegalArgumentException("检索查询最多500字");MemoryBook.validateUnicode(value);
        String result=value.trim();for(int i=0;i<result.length();i++){char c=result.charAt(i);if(Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t')throw new IllegalArgumentException("检索查询含控制字符");}return result;
    }
}
