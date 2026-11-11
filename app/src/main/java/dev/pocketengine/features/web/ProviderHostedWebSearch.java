package dev.pocketengine.features.web;

import dev.pocketengine.browser.PublicHttpReader;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Explicit billable vendor-hosted search as a native, approved tool. Never
 * borrows the main model request or a user account. A single capped physical
 * request is made with the original pinned API model and no private history.
 * Usage is returned separately rather than fabricated into model token totals.
 */
public final class ProviderHostedWebSearch {
    public static final String TOOL="web_provider_search";
    private static final int MAX_RESPONSE=512*1024,MAX_TEXT=24000,MAX_SOURCES=20;
    private static final OkHttpClient HTTP=new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .dns(host->{
                List<InetAddress> addresses=Dns.SYSTEM.lookup(host);
                if(addresses.isEmpty())throw new UnknownHostException("Vendor search address unavailable");
                for(InetAddress address:addresses)if(!PublicHttpReader.publicAddress(address))
                    throw new UnknownHostException("Vendor search address is not public");
                return addresses;
            }).connectTimeout(5,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .callTimeout(25,TimeUnit.SECONDS).build();

    public static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("query",new JSONObject().put("type","string").put("minLength",1).put("maxLength",500))
                .put("maxUses",new JSONObject().put("type","integer").put("minimum",1).put("maximum",3)))
                .put("required",new JSONArray().put("query")).put("additionalProperties",false);
    }
    /** Only first-party native HTTPS endpoints, never an OpenAI-compatible relay URL. */
    public static boolean supports(ModelConfig config) {
        if(config==null||config.usesAccount()||config.usesBedrock()||config.apiKey.isEmpty())return false;
        String host=switch(config.provider){
            case "openai_responses"->"api.openai.com";
            case "anthropic"->"api.anthropic.com";
            case "gemini"->"generativelanguage.googleapis.com";
            default->"";
        };
        try {
            URI uri=new URI(config.baseUrl);
            return !host.isEmpty()&&uri.getScheme().equals("https")&&host.equalsIgnoreCase(uri.getHost())
                    &&uri.getPort()==-1&&uri.getUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null;
        }catch(Exception invalid){return false;}
    }

    public JSONObject search(JSONObject args,ModelConfig selected,RunControl original,
            RunControl.AuthorityCheck current)throws Exception {
        if(!supports(selected))throw new SecurityException("厂商搜索只能使用原生准确官方HTTPS API连接");
        if(args==null||!args.has("query")||!(args.opt("query") instanceof String))
            throw new IllegalArgumentException("缺少厂商检索词");
        for(var keys=args.keys();keys.hasNext();)if(!Set.of("query","maxUses").contains(keys.next()))
            throw new SecurityException("厂商搜索不接受请求身份、端点、凭据或费用参数");
        String query=WebQueryGuard.requirePublic(args.getString("query"),500);
        int maxUses=2;
        if(args.has("maxUses")){
            Object raw=args.get("maxUses");
            if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1
                    ||((Number)raw).longValue()>3)throw new IllegalArgumentException("搜索预算只接受1至3");
            maxUses=((Number)raw).intValue();
        }
        current.check();original.check();
        JSONObject payload=body(selected,query,maxUses);
        byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);
        Request.Builder outgoing=new Request.Builder().url(ProviderAdapters.selected(selected.provider).endpoint(selected))
                .header("Content-Type","application/json").header("Accept","application/json");
        switch(selected.provider){
            case "openai_responses"->outgoing.header("Authorization","Bearer "+selected.apiKey);
            case "anthropic"->{outgoing.header("x-api-key",selected.apiKey);
                               outgoing.header("anthropic-version","2023-06-01");}
            case "gemini"->outgoing.header("x-goog-api-key",selected.apiKey);
            default->throw new SecurityException("不支持的官方搜索协议");
        }
        Call call=HTTP.newCall(outgoing.post(RequestBody.create(bytes,okhttp3.MediaType.parse("application/json"))).build());
        Runnable cancel=call::cancel;original.onCancel(cancel);
        try {
            current.check();original.check();
            try(Response response=call.execute()){
                current.check();original.check();
                if(!response.isSuccessful())return unavailable(selected.provider,"vendor_http_"+response.code(),true);
                byte[] responseBytes=read(response.body()==null?null:response.body().byteStream(),original);
                JSONObject raw=new JSONObject(new String(responseBytes,StandardCharsets.UTF_8));
                JSONObject result=normalize(selected.provider,raw);
                current.check();original.check();
                return result.put("model",selected.model).put("provider",selected.provider)
                        .put("requestAttempted",true).put("retrievedAt",System.currentTimeMillis())
                        .put("vendorBilledSeparately",true).put("tokenUsageNotMergedIntoMain",true)
                        .put("originalTaskOnly",true).put("untrustedPublicSearchResults",true);
            }
        }catch(IOException unavailable){
            original.check();current.check();
            return unavailable(selected.provider,"vendor_search_network_unknown",true);
        }finally{
            call.cancel();original.removeCancelAction(cancel);
        }
    }

    static JSONObject body(ModelConfig model,String query,int uses)throws Exception {
        switch(model.provider){
            case "openai_responses":
                // Responses does not expose a hard max_uses budget. One opt-in native
                // call is the bounded unit; final reported server search actions are counted.
                return new JSONObject().put("model",model.model).put("store",false).put("stream",false)
                        .put("max_output_tokens",Math.min(4096,model.maxOutputTokens))
                        .put("input",new JSONArray().put(new JSONObject().put("role","user")
                                .put("content","Search current public sources and answer this specific question. "
                                        +"Use at most "+uses+" web searches, cite the URLs actually used. Question: "+query)))
                        .put("tools",new JSONArray().put(new JSONObject().put("type","web_search")))
                        .put("include",new JSONArray().put("web_search_call.action.sources"));
            case "anthropic":
                return new JSONObject().put("model",model.model).put("max_tokens",Math.min(4096,model.maxOutputTokens))
                        .put("messages",new JSONArray().put(new JSONObject().put("role","user")
                                .put("content",query)))
                        .put("tools",new JSONArray().put(new JSONObject()
                                .put("type","web_search_20250305").put("name","web_search").put("max_uses",uses)));
            case "gemini":
                return new JSONObject().put("contents",new JSONArray().put(new JSONObject()
                                .put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",query)))))
                        .put("tools",new JSONArray().put(new JSONObject().put("googleSearch",new JSONObject())))
                        .put("generationConfig",new JSONObject().put("maxOutputTokens",
                                Math.min(4096,model.maxOutputTokens)));
            default:throw new SecurityException("当前协议没有厂商托管搜索");
        }
    }

    static JSONObject normalize(String provider,JSONObject raw)throws Exception {
        JSONArray sources=new JSONArray();Set<String> seen=new LinkedHashSet<>();StringBuilder answer=new StringBuilder();
        int calls=0,reportedInput=0,reportedOutput=0;boolean billingReported=false;JSONObject usage=new JSONObject();
        if("openai_responses".equals(provider)){
            if(!"completed".equals(raw.optString("status")))return unavailable(provider,"vendor_turn_not_completed",true);
            JSONArray items=raw.optJSONArray("output");
            if(items==null)throw new IllegalArgumentException("Missing provider output");
            for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i);if(item==null)continue;
                if("web_search_call".equals(item.optString("type"))){
                    if("completed".equals(item.optString("status","completed"))){
                        JSONObject action=item.optJSONObject("action");
                        if(action!=null&&"search".equals(action.optString("type")))calls++;
                        JSONArray links=action==null?null:action.optJSONArray("sources");
                        sourceArray(sources,seen,links,"url","title");
                    }
                }else if("message".equals(item.optString("type"))){
                    JSONArray content=item.optJSONArray("content");
                    if(content==null)continue;
                    for(int j=0;j<content.length();j++){
                        JSONObject part=content.optJSONObject(j);
                        if(part==null||!"output_text".equals(part.optString("type")))continue;
                        append(answer,part.optString("text"));
                        sourceArray(sources,seen,part.optJSONArray("annotations"),"url","title");
                    }
                }
            }
            JSONObject bill=raw.optJSONObject("usage");
            if(bill!=null){billingReported=true;reportedInput=bill.optInt("input_tokens");reportedOutput=bill.optInt("output_tokens");}
        }else if("anthropic".equals(provider)){
            if(!"end_turn".equals(raw.optString("stop_reason")))return unavailable(provider,"vendor_turn_not_completed",true);
            JSONArray blocks=raw.optJSONArray("content");if(blocks==null)throw new IllegalArgumentException("Missing Claude content");
            for(int i=0;i<blocks.length();i++){
                JSONObject item=blocks.optJSONObject(i);if(item==null)continue;
                switch(item.optString("type")){
                    case "server_tool_use"->{if("web_search".equals(item.optString("name")))calls++;}
                    case "web_search_tool_result"->{
                        JSONArray hits=item.optJSONArray("content");sourceArray(sources,seen,hits,"url","title");
                    }
                    case "text"->{append(answer,item.optString("text"));
                                 sourceArray(sources,seen,item.optJSONArray("citations"),"url","title");}
                }
            }
            JSONObject bill=raw.optJSONObject("usage");
            if(bill!=null){billingReported=true;reportedInput=bill.optInt("input_tokens");reportedOutput=bill.optInt("output_tokens");
                JSONObject server=bill.optJSONObject("server_tool_use");
                if(server!=null)calls=server.optInt("web_search_requests",calls);}
        }else if("gemini".equals(provider)){
            JSONArray candidates=raw.optJSONArray("candidates");
            JSONObject choice=candidates==null?null:candidates.optJSONObject(0);
            if(choice==null||!"STOP".equals(choice.optString("finishReason")))return unavailable(provider,"vendor_turn_not_completed",true);
            JSONObject content=choice.optJSONObject("content");
            JSONArray parts=content==null?null:content.optJSONArray("parts");
            if(parts!=null)for(int i=0;i<parts.length();i++){
                JSONObject part=parts.optJSONObject(i);
                if(part!=null&&!part.optBoolean("thought"))append(answer,part.optString("text"));
            }
            JSONObject grounding=choice.optJSONObject("groundingMetadata");
            if(grounding!=null){
                JSONArray queries=grounding.optJSONArray("webSearchQueries");
                if(queries!=null)calls=queries.length();
                JSONArray chunks=grounding.optJSONArray("groundingChunks");
                if(chunks!=null)for(int i=0;i<chunks.length();i++){
                    JSONObject chunk=chunks.optJSONObject(i),web=chunk==null?null:chunk.optJSONObject("web");
                    if(web!=null)addSource(sources,seen,web.optString("uri"),web.optString("title"),"vendor_grounding");
                }
            }
            JSONObject bill=raw.optJSONObject("usageMetadata");
            if(bill!=null){billingReported=true;reportedInput=bill.optInt("promptTokenCount");reportedOutput=bill.optInt("candidatesTokenCount");}
        }else throw new SecurityException("Unknown hosted provider");

        usage.put("inputTokens",Math.max(0,reportedInput)).put("outputTokens",Math.max(0,reportedOutput))
                .put("searchRequests",Math.max(0,calls)).put("reportedByProvider",billingReported)
                .put("externalCostUsd",JSONObject.NULL).put("pricingUnknown",true);
        boolean searched=calls>0&&sources.length()>0;
        return new JSONObject().put("ok",searched).put("status",searched?"completed":"unavailable")
                .put("code",searched?"":"vendor_search_no_citable_sources")
                .put("text",answer.toString()).put("results",sources)
                .put("sourceRefs",new JSONArray(sources.toString())).put("usage",usage)
                .put("providerSearchRequests",calls).put("searchRequests",calls).put("pagesRead",0)
                .put("modelRelianceVerified",false)
                .put("message",searched?"厂商已返回搜索来源及文字，未独立验证引用内容。"
                        :"厂商响应未证明真实搜索或未返回可引用URL，不能声称联网完成。");
    }

    private static void append(StringBuilder out,String text) {
        if(text==null||text.isEmpty()||out.length()>=MAX_TEXT)return;
        int length=Math.min(MAX_TEXT-out.length(),text.length());
        if(length<text.length()&&length>0&&Character.isHighSurrogate(text.charAt(length-1)))length--;
        out.append(text,0,length);
    }
    private static void sourceArray(JSONArray out,Set<String> seen,JSONArray src,String urlKey,String titleKey)throws Exception {
        if(src==null)return;
        for(int i=0;i<src.length()&&out.length()<MAX_SOURCES;i++){
            JSONObject value=src.optJSONObject(i);if(value==null)continue;
            if("url_citation".equals(value.optString("type"))||"web_search_result_location".equals(value.optString("type"))
                    ||"web_search_result".equals(value.optString("type"))||"url".equals(value.optString("type"))||!value.has("type")){
                addSource(out,seen,value.optString(urlKey),value.optString(titleKey),"vendor_citation");
            }
        }
    }
    private static void addSource(JSONArray out,Set<String> seen,String rawUrl,String title,String kind)throws Exception {
        if(rawUrl==null||rawUrl.length()>2048||out.length()>=MAX_SOURCES)return;
        String url;
        try{
            URI uri=new URI(rawUrl);url=uri.toASCIIString();
            PublicHttpReader.validate(new JSONObject().put("url",url));
        }catch(Exception invalid){return;}
        if(!seen.add(url))return;
        out.put(new JSONObject().put("url",url)
                .put("title",title==null?"":title.substring(0,Math.min(240,title.length())))
                .put("snippet","").put("sourceRole",kind).put("verified",false));
    }
    private static JSONObject unavailable(String provider,String code,boolean attempted)throws Exception{
        return new JSONObject().put("ok",false).put("status","unavailable")
                .put("provider",provider).put("code",code).put("requestAttempted",attempted)
                .put("results",new JSONArray()).put("sourceRefs",new JSONArray())
                .put("vendorBilledSeparately",attempted).put("externalCostUsd",JSONObject.NULL)
                .put("message","厂商搜索没有取得带来源的完整回执；不能当成实时答案。");
    }
    private static byte[] read(InputStream input,RunControl control)throws Exception {
        if(input==null)throw new IOException("empty hosted search");
        try(InputStream stream=input;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];
            for(int n;(n=stream.read(chunk))!=-1;){
                control.check();if(bytes.size()+n>MAX_RESPONSE)throw new IOException("hosted search response exceeded budget");
                bytes.write(chunk,0,n);
            }
            return bytes.toByteArray();
        }
    }
}
