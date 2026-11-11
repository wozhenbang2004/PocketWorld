package dev.pocketengine.features.web;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RuntimeDiagnostics;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Session counters; persisted per-task evidence remains in TaskStore. No queries or URLs in logs. */
public final class WebSearchDiagnostics {
    private static final AtomicLong SEARCH_REQUESTS=new AtomicLong(),READ_REQUESTS=new AtomicLong();
    private static final AtomicLong HOSTED_REQUESTS=new AtomicLong(),HOSTED_INPUT_TOKENS=new AtomicLong();
    private static final AtomicLong HOSTED_OUTPUT_TOKENS=new AtomicLong(),PAGES_READ=new AtomicLong();
    private static final AtomicLong FAILURES=new AtomicLong(),SOURCE_REFS=new AtomicLong();
    private static volatile String lastStatus="not_checked",lastCode="";
    private WebSearchDiagnostics(){}
    public static void observed(String tool,JSONObject response,RunControl original){
        if(response==null)return;
        int search=Math.max(0,Math.min(4,response.optInt("searchRequests","web_search".equals(tool)?1:0)));
        int reads=Math.max(0,Math.min(4,response.optInt("readRequests","web_fetch".equals(tool)?1:0)));
        if(search>0)SEARCH_REQUESTS.addAndGet(search);
        if(reads>0)READ_REQUESTS.addAndGet(reads);
        PAGES_READ.addAndGet(Math.max(0,Math.min(4,response.optInt("pagesRead"))));
        SOURCE_REFS.addAndGet(Math.max(0,Math.min(24,response.optInt("sourceCount"))));
        if(ProviderHostedWebSearch.TOOL.equals(tool)){
            HOSTED_REQUESTS.incrementAndGet();
            JSONObject usage=response.optJSONObject("usage");
            if(usage!=null){
                HOSTED_INPUT_TOKENS.addAndGet(Math.max(0,usage.optLong("inputTokens")));
                HOSTED_OUTPUT_TOKENS.addAndGet(Math.max(0,usage.optLong("outputTokens")));
            }
        }
        boolean success=response.optBoolean("ok");
        if(!success)FAILURES.incrementAndGet();
        lastStatus=response.optString("status","unknown");
        String code=response.optString("code","");
        lastCode=code.matches("[a-zA-Z0-9_.-]{0,80}")?code:"unclassified";
        RuntimeDiagnostics.event("web_research",success?"success":"failed",
                "WEB_"+tool.toUpperCase(java.util.Locale.ROOT)+(success?"_OK":"_UNAVAILABLE"),original,null);
    }
    public static JSONObject snapshot()throws Exception {
        return new JSONObject().put("scope","current_process_only")
                .put("searchRequests",SEARCH_REQUESTS.get()).put("fetchRequests",READ_REQUESTS.get())
                .put("pagesRead",PAGES_READ.get()).put("sourceRefs",SOURCE_REFS.get())
                .put("hostedModelRequests",HOSTED_REQUESTS.get())
                .put("hostedInputTokens",HOSTED_INPUT_TOKENS.get())
                .put("hostedOutputTokens",HOSTED_OUTPUT_TOKENS.get())
                .put("failedCalls",FAILURES.get()).put("lastStatus",lastStatus)
                .put("lastCode",lastCode).put("vendorFeesUsd",JSONObject.NULL)
                .put("providerRatesUnknown",true)
                .put("message","仅当前进程可观测计数；每个原任务的历史来源和计数保存在原TaskStore事件。服务商费用仍须以官方账单为准。");
    }
}
