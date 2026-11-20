package dev.pocketengine.features.web;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Source pins live in the original TaskStore event stream. They do not grant a
 * browser session, rewrite model output, or claim the model relied on a source.
 */
public final class WebSourceLedger {
    public static final String EVENT="web_public_sources";
    private static final int MAX_EXCERPT=2000;
    @FunctionalInterface public interface ForgetFence {boolean forgotten(long sequence,String revision)throws Exception;}
    private WebSourceLedger(){}

    /** Deliberately excludes provider-hosted browsing and authenticated browser receipts. */
    public static boolean anonymousTool(String tool){
        return "web_search".equals(tool)||"web_fetch".equals(tool)||WebResearchPipeline.TOOL.equals(tool);
    }
    public static boolean anonymousKind(String tool,String kind){
        return "search_snippet".equals(kind)&&("web_search".equals(tool)||WebResearchPipeline.TOOL.equals(tool))
                ||"public_http_text".equals(kind)&&("web_fetch".equals(tool)||WebResearchPipeline.TOOL.equals(tool));
    }
    /** Native producer hint only. The original-answer reader still proves the ledger join. */
    public static boolean hasAnonymousSources(String tool,JSONObject receipt){
        if(!anonymousTool(tool)||!Boolean.TRUE.equals(receipt.opt("sourcePersisted")))return false;
        JSONArray refs=receipt.optJSONArray("sourceRefs");
        if(refs==null||refs.length()>22)return false;
        for(int i=0;i<refs.length();i++){
            JSONObject ref=refs.optJSONObject(i);
            if(ref!=null&&anonymousKind(tool,ref.optString("kind"))&&ref.optString("sourceId").matches("[0-9a-f-]{36}"))return true;
        }
        return false;
    }
    /** Prior answers and newer owner instructions end the evidence segment. Phone observations
     * and system projections are not owner input. This can only narrow a source view. */
    public static boolean answerBoundary(JSONObject row){
        if(row==null)return false;
        return "assistant".equals(row.optString("role"))&&!row.has("tool_calls")
                ||"user".equals(row.optString("role"))&&(row.has("ownerMessageId")||Boolean.TRUE.equals(row.opt("ownerRecallFeedback")));
    }

    public static JSONObject record(TaskStore tasks,String task,String run,int epoch,
            String operation,String tool,JSONObject actual,RunControl original)throws Exception {
        if(actual==null)throw new IllegalArgumentException("missing tool result");
        if(!anonymousTool(tool)&&!ProviderHostedWebSearch.TOOL.equals(tool))
            throw new SecurityException("not a web source tool");
        original.check();
        JSONArray sourceRows=new JSONArray(),links=new JSONArray();
        Set<String> urls=new LinkedHashSet<>();
        if(actual.optJSONArray("results")!=null){
            JSONArray rows=actual.getJSONArray("results");
            for(int i=0;i<rows.length()&&sourceRows.length()<16;i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                add(sourceRows,links,urls,ProviderHostedWebSearch.TOOL.equals(tool)?"vendor_citation":"search_snippet",row.optString("url"),
                        row.optString("title"),row.optString("snippet"),actual.optLong("retrievedAt",System.currentTimeMillis()),
                        false,"snippet_only");
            }
        }
        if(actual.optJSONArray("pages")!=null){
            JSONArray pages=actual.getJSONArray("pages");
            for(int i=0;i<pages.length()&&sourceRows.length()<22;i++){
                JSONObject page=pages.optJSONObject(i);
                if(page==null||!page.optBoolean("ok")||!"read".equals(page.optString("status")))continue;
                addBody(sourceRows,links,urls,page,page.optJSONObject("http"));
            }
        }else if("web_fetch".equals(tool)&&actual.optBoolean("ok")&&"read".equals(actual.optString("status"))){
            addBody(sourceRows,links,urls,actual,actual.optJSONObject("http"));
        }
        JSONObject providerUsage=actual.optJSONObject("usage");
        JSONObject counters=new JSONObject().put("searchRequests",Math.max(0,actual.optInt("searchRequests","web_search".equals(tool)?1:0)))
                .put("readRequests",Math.max(0,actual.optInt("readRequests","web_fetch".equals(tool)?1:0)))
                .put("pagesRead",Math.max(0,actual.optInt("pagesRead","web_fetch".equals(tool)&&actual.optBoolean("ok")?1:0)))
                .put("hostedModelRequests",ProviderHostedWebSearch.TOOL.equals(tool)?1:0)
                .put("hostedInputTokens",providerUsage==null?0:Math.max(0,providerUsage.optInt("inputTokens")))
                .put("hostedOutputTokens",providerUsage==null?0:Math.max(0,providerUsage.optInt("outputTokens")))
                .put("failedCalls",actual.optBoolean("ok")?0:1)
                .put("usageWasProviderReported",providerUsage!=null&&providerUsage.optBoolean("reportedByProvider"))
                .put("searchBillingKnown",false).put("externalProviderCost",JSONObject.NULL);
        JSONObject event=new JSONObject().put("type",EVENT).put("tool",tool).put("operationId",operation)
                .put("taskId",task).put("runId",run).put("epoch",epoch)
                .put("observedAt",System.currentTimeMillis()).put("status",actual.optString("status","unknown"))
                .put("provider",actual.optString("provider","")).put("sources",sourceRows).put("counters",counters)
                .put("untrusted",true).put("modelRelianceVerified",false);
        original.check();
        tasks.event(task,run,epoch,event);
        JSONObject result=new JSONObject(actual.toString()).put("sourceRefs",links)
                .put("sourceCount",links.length()).put("sourcePersisted",links.length()>0)
                .put("citationPolicy","Cite actual URLs from sourceRefs; sourceId identifies native provenance, not independent fact verification.");
        WebSearchDiagnostics.observed(tool,result,original);
        original.check();
        return result;
    }

    private static void addBody(JSONArray sources,JSONArray links,Set<String> seen,JSONObject page,JSONObject http)throws Exception{
        if(http==null)return;
        String text=page.optString("text");
        boolean complete=http.optBoolean("bodyComplete")&&!http.optBoolean("projectionTruncated")&&text.length()<=MAX_EXCERPT;
        add(sources,links,seen,"public_http_text",http.optString("finalUrl"),page.optString("title"),text,
                http.getLong("retrievedAt"),complete,complete?"bounded_body":"partial_body");
    }

    private static void add(JSONArray sources,JSONArray links,Set<String> seen,String kind,
            String rawUrl,String title,String body,long at,boolean complete,String coverage)throws Exception {
        if(rawUrl==null||rawUrl.length()>2048||!rawUrl.startsWith("https://"))return;
        URI uri;
        try{uri=new URI(rawUrl);}catch(Exception invalid){return;}
        if(uri.getHost()==null||uri.getRawUserInfo()!=null)return;
        String url=uri.toASCIIString();
        try{dev.pocketengine.browser.PublicHttpReader.validate(new JSONObject().put("url",url));}
        catch(IllegalArgumentException|SecurityException rejected){return;}
        String unique=kind+"\n"+url;
        if(!seen.add(unique))return;
        // Source excerpts are bounded even when the original HTTP body was larger.
        String excerpt=limit(body,MAX_EXCERPT),sourceId=UUID.randomUUID().toString();
        String display=new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null).toASCIIString();
        JSONObject citation=new JSONObject().put("sourceId",sourceId).put("title",limit(title,240))
                .put("url",url).put("displayUrl",display).put("retrievedAt",at).put("kind",kind)
                .put("coverage",coverage).put("bodyComplete",complete).put("verified",false);
        sources.put(new JSONObject(citation.toString())
                .put("excerpt",excerpt).put("bodySha256",hash(body==null?"":body))
                .put("rawUrlRetainedPrivately",!url.equals(display)));
        links.put(citation);
    }
    private static String limit(String value,int max){
        if(value==null)return "";
        String cleaned=value.replaceAll("[\\p{Cntrl}&&[^\n\t]]"," ");
        return cleaned.length()<=max?cleaned:cleaned.substring(0,max);
    }
    private static String hash(String value)throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder(64);for(byte b:hash)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
    }

    public static JSONObject sources(TaskStore tasks,String task,String run,int epoch,
            JSONObject args,RunControl original,ForgetFence forget)throws Exception {
        java.util.Objects.requireNonNull(forget);
        if(args==null)args=new JSONObject();
        for(var names=args.keys();names.hasNext();)if(!Set.of("sourceId","limit").contains(names.next()))
            throw new SecurityException("来源查询不能扩大原任务范围");
        String wanted=args.optString("sourceId","");
        if(!wanted.isEmpty()&&!wanted.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("sourceId格式无效");
        int limit=args.optInt("limit",20);
        if(limit<1||limit>40||args.has("limit")&&!(args.get("limit") instanceof Integer))
            throw new IllegalArgumentException("来源窗口超出40条");
        original.check();
        tasks.checkCapability(task,run,epoch,"web_sources",original);
        JSONObject active=tasks.get(task,false);
        if(active==null||!run.equals(active.optString("runId"))||epoch!=active.optInt("coordinatorEpoch"))
            throw new SecurityException("原任务来源已换代");
        JSONArray events=active.optJSONArray("events"),out=new JSONArray();
        int count=0,searchCalls=0,fetchCalls=0,readPages=0,hostedRequests=0,hostedInput=0,hostedOutput=0;
        if(events!=null)for(int i=0;i<events.length();i++){
            JSONObject e=events.optJSONObject(i);
            if(e==null||!EVENT.equals(e.optString("type"))||!task.equals(e.optString("taskId"))
                    ||!run.equals(e.optString("runId"))||epoch!=e.optInt("epoch"))continue;
            JSONObject metric=e.optJSONObject("counters");
            if(metric!=null){searchCalls+=metric.optInt("searchRequests");fetchCalls+=metric.optInt("readRequests");readPages+=metric.optInt("pagesRead");hostedRequests+=metric.optInt("hostedModelRequests");hostedInput+=metric.optInt("hostedInputTokens");hostedOutput+=metric.optInt("hostedOutputTokens");}
            JSONArray rows=e.optJSONArray("sources");if(rows==null||rows.length()==0)continue;
            original.check();
            if(forget.forgotten(e.getLong("seq"),TaskStore.capabilityFingerprint(e)))continue;
            for(int j=0;j<rows.length();j++){
                JSONObject row=rows.optJSONObject(j);
                if(row==null||!wanted.isEmpty()&&!wanted.equals(row.optString("sourceId")))continue;
                count++;if(out.length()<limit){
                    // The original reply reader, not the model's source list, owns retained
                    // excerpt access. A redacted destination is not a substitute citation URL.
                    JSONObject link=new JSONObject().put("sourceId",row.getString("sourceId"))
                            .put("displayUrl",row.getString("displayUrl")).put("retrievedAt",row.getLong("retrievedAt"))
                            .put("kind",row.getString("kind")).put("coverage",row.getString("coverage"))
                            .put("bodyComplete",row.getBoolean("bodyComplete")).put("verified",false);
                    String raw=row.getString("url"),display=row.getString("displayUrl");
                    link.put("title",row.getString("title").replace(raw,display));
                    if(raw.equals(display)&&Boolean.FALSE.equals(row.opt("rawUrlRetainedPrivately")))link.put("url",raw);
                    out.put(link);
                }
            }
        }
        original.check();
        return new JSONObject().put("ok",true).put("status","read").put("terminal",true).put("networkUsed",false)
                .put("message",count==0?"此原执行的可见记录没有匹配的联网来源；没有发送新网络请求。":
                        "已查到本次原执行的保留来源；这是本机来源目录，不代表网页正文已获取或核实。")
                .put("sources",out).put("totalSourcesObserved",count)
                .put("truncated",out.length()<count)
                .put("metrics",new JSONObject().put("searchRequests",searchCalls)
                        .put("fetchRequests",fetchCalls).put("pagesRead",readPages)
                        .put("hostedModelRequests",hostedRequests).put("hostedInputTokens",hostedInput)
                        .put("hostedOutputTokens",hostedOutput)
                        .put("providerFees","unknown").put("hostedTokensSeparateFromMain",true))
                .put("taskId",task).put("runId",run).put("epoch",epoch)
                .put("modelRelianceVerified",false).put("executionAuthorized",false);
    }
}
