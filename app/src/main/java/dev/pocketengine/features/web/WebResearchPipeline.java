package dev.pocketengine.features.web;

import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded multi-query public research inside one already-admitted native tool.
 * The model selects only query text. Provider, credentials and execution control
 * remain host-owned. This class never opens a new task or model loop.
 */
public final class WebResearchPipeline {
    public static final String TOOL="web_research";
    private static final int MAX_QUERIES=4, MAX_PAGES=4, MAX_RESULTS=16;
    private final WebSearchModule web;

    public WebResearchPipeline(WebSearchModule web){this.web=java.util.Objects.requireNonNull(web);}

    public static JSONObject schema()throws Exception {
        JSONObject properties=new JSONObject()
                .put("queries",new JSONObject().put("type","array").put("minItems",1)
                        .put("maxItems",MAX_QUERIES).put("items",new JSONObject()
                                .put("type","string").put("minLength",1).put("maxLength",300)))
                .put("maxPages",new JSONObject().put("type","integer").put("minimum",0).put("maximum",MAX_PAGES))
                .put("maxResults",new JSONObject().put("type","integer").put("minimum",1).put("maximum",MAX_RESULTS));
        return new JSONObject().put("type","object").put("properties",properties)
                .put("required",new JSONArray().put("queries")).put("additionalProperties",false);
    }

    public static void validate(JSONObject args)throws Exception {
        if(args==null)throw new IllegalArgumentException("缺少研究查询");
        for(var keys=args.keys();keys.hasNext();)if(!Set.of("queries","maxPages","maxResults").contains(keys.next()))
            throw new SecurityException("研究查询只接受查询词与有界预算");
        JSONArray queries=args.getJSONArray("queries");
        if(queries.isEmpty()||queries.length()>MAX_QUERIES)throw new IllegalArgumentException("每次只能检索1至4条查询");
        Set<String> unique=new LinkedHashSet<>();
        for(int i=0;i<queries.length();i++){
            String query=queries.getString(i);
            if(query.isBlank()||query.length()>300||query.codePoints().anyMatch(Character::isISOControl)
                    ||!unique.add(query.trim().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("研究查询词过长、重复或包含控制字符");
        }
        strictInteger(args,"maxPages",2,0,MAX_PAGES);
        strictInteger(args,"maxResults",8,1,MAX_RESULTS);
    }
    private static int strictInteger(JSONObject args,String field,int fallback,int min,int max)throws Exception {
        if(!args.has(field))return fallback;
        Object value=args.get(field);
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min
                ||((Number)value).longValue()>max)throw new IllegalArgumentException(field+"超出预算");
        return ((Number)value).intValue();
    }

    public JSONObject research(JSONObject args,RunControl original)throws Exception {
        validate(args);
        int pageLimit=strictInteger(args,"maxPages",2,0,MAX_PAGES);
        int resultLimit=strictInteger(args,"maxResults",8,1,MAX_RESULTS);
        JSONArray queries=args.getJSONArray("queries"),searches=new JSONArray(),pages=new JSONArray();
        LinkedHashMap<String,JSONObject> selected=new LinkedHashMap<>();
        int searchCalls=0,readAttempts=0,readSuccess=0;
        for(int i=0;i<queries.length();i++){
            original.check();
            String query=queries.getString(i);
            JSONObject answer=web.search(new JSONObject().put("query",query)
                    .put("limit",Math.min(8,resultLimit)),original);
            searchCalls++;
            JSONObject turn=new JSONObject().put("queryIndex",i).put("query",query)
                    .put("ok",answer.optBoolean("ok")).put("status",answer.optString("status"))
                    .put("provider",answer.optString("provider"))
                    .put("code",answer.optString("code")).put("resultCount",answer.optInt("resultCount"));
            searches.put(turn);
            JSONArray found=answer.optJSONArray("results");
            if(found==null)continue;
            for(int j=0;j<found.length()&&selected.size()<resultLimit;j++){
                JSONObject item=found.optJSONObject(j);
                if(item==null||!item.has("url"))continue;
                String url=item.getString("url");
                // A search hit is not yet a verified document. The read path enforces
                // DNS/public address/redirect policy independently.
                if(!selected.containsKey(url))selected.put(url,new JSONObject(item.toString())
                        .put("queryIndex",i).put("rank",j+1)
                        .put("sourceRole","search_snippet_unverified"));
            }
        }
        JSONArray results=new JSONArray();
        for(JSONObject item:selected.values())results.put(item);
        for(JSONObject candidate:selected.values()){
            if(readAttempts>=pageLimit)break;
            original.check();
            readAttempts++;
            String url=candidate.getString("url");
            JSONObject page;
            try {
                page=web.fetch(new JSONObject().put("url",url),original);
            }catch(IllegalArgumentException|SecurityException forbidden){
                page=new JSONObject().put("ok",false).put("status","rejected")
                        .put("code","public_url_rejected");
            }
            JSONObject item=new JSONObject().put("url",url).put("title",candidate.optString("title"))
                    .put("status",page.optString("status","unavailable"))
                    .put("ok",page.optBoolean("ok")).put("verified",false)
                    .put("retrievedAt",page.optJSONObject("http")==null?JSONObject.NULL:
                            page.getJSONObject("http").opt("retrievedAt"));
            if(page.optBoolean("ok")&&"read".equals(page.optString("status"))){
                item.put("title",page.optString("title",candidate.optString("title")))
                        .put("text",page.optString("text"))
                        .put("http",page.getJSONObject("http"))
                        .put("rendered",false).put("sourceRole","public_http_text");
                readSuccess++;
            }else{
                item.put("code",page.optString("code","read_unavailable"))
                        .put("sourceRole","unreadable_search_hit")
                        .put("nextAction",page.optString("status").equals("needs_webview")
                                ?"requires_authorized_browser_task":"review_source_or_try_another_query")
                        .put("browserPermissionInherited",false);
            }
            pages.put(item);
        }
        original.check();
        return new JSONObject().put("ok",searches.length()>0&&results.length()>0)
                .put("status",results.length()>0?(readSuccess>0?"partially_read":"search_results_only"):"unavailable")
                .put("searches",searches).put("results",results).put("pages",pages)
                .put("searchRequests",searchCalls).put("readRequests",readAttempts)
                .put("pagesRead",readSuccess).put("untrustedPublicResearch",true)
                .put("originalTaskOnly",true).put("retrievedAt",System.currentTimeMillis())
                .put("message",readSuccess>0
                        ?"正文仅来自本次已实际读取的匿名HTTP响应，摘要不冒充正文。"
                        :"本轮未取得可读正文；如需动态/登录页面，须使用原任务实际批准的浏览器工具，不能自动扩大权限。");
    }
}
