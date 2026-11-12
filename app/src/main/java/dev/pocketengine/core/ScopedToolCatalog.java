package dev.pocketengine.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Read-only definitions inside one original native capability scope. It owns no grants or executor. */
public final class ScopedToolCatalog {
    public static final String SEARCH_TOOL="tools_search";
    public static final int MAX_TOOLS=512,MAX_PAGE_TOOLS=12,MAX_PAGE_BYTES=48*1024,MAX_CATALOG_BYTES=24*1024*1024;
    public static final int MAX_REQUEST_TOOLS=64,MAX_REQUEST_BYTES=192*1024,MAX_MODEL_TOOLS=96,MAX_MODEL_BYTES=256*1024;
    /** No partial schemas or full-catalog fallback when mandatory definitions cannot fit. */
    public static final class RequestBudgetExceeded extends IllegalStateException {
        public final int toolCount,definitionBytes,maximumTools,maximumBytes;
        private RequestBudgetExceeded(int count,int bytes,int maximumTools,int maximumBytes){
            super("本次完整工具定义需要 "+count+" 个 / "+bytes+" 字节，超过单轮 "+maximumTools+" 个 / "+maximumBytes+" 字节的范围。请缩小本次工具选择；未截断参数定义或改用全量工具请求。");
            this.toolCount=count;this.definitionBytes=bytes;this.maximumTools=maximumTools;this.maximumBytes=maximumBytes;
        }
    }
    private record RequestSize(int count,int bytes) {
        boolean fits(){return count<=MAX_REQUEST_TOOLS&&bytes<=MAX_REQUEST_BYTES;}
        void requireFits(){if(!fits())throw new RequestBudgetExceeded(count,bytes,MAX_REQUEST_TOOLS,MAX_REQUEST_BYTES);}
    }
    public static boolean withinRequestBudget(JSONArray definitions){return definitions.length()<=MAX_REQUEST_TOOLS&&utf8(definitions.toString()).length<=MAX_REQUEST_BYTES;}
    public static void requireRequestBudget(JSONArray definitions){new RequestSize(definitions.length(),utf8(definitions.toString()).length).requireFits();}
    /** Called after the original Runner has merged and filtered its own resident control tools. */
    public static void requireModelRequestBudget(JSONArray definitions){
        int bytes=utf8(definitions.toString()).length;
        if(definitions.length()>MAX_MODEL_TOOLS||bytes>MAX_MODEL_BYTES)throw new RequestBudgetExceeded(definitions.length(),bytes,MAX_MODEL_TOOLS,MAX_MODEL_BYTES);
    }
    public enum SourceKind { NATIVE, PLUGIN, EXTENSION, UNKNOWN }
    /** Supplied by the original native route/installation owner; never decoded from model arguments. */
    public record SourcePin(SourceKind kind,String installationId,String version,String identityDigest) {
        public SourcePin {
            java.util.Objects.requireNonNull(kind);
            if(kind==SourceKind.UNKNOWN){if(installationId!=null||version!=null||identityDigest!=null)throw new IllegalArgumentException("unknown_tool_source_cannot_claim_identity");}
            else{if(installationId==null||!installationId.matches("[A-Za-z0-9_.:-]{1,200}")||version==null||version.isBlank()||version.length()>200||version.codePoints().anyMatch(Character::isISOControl)
                    ||identityDigest==null||!identityDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("tool_source_pin_required");
                utf8(version);
            }
        }
        public static SourcePin unknown(){return new SourcePin(SourceKind.UNKNOWN,null,null,null);}
    }
    public record SearchRequest(String query,int limit,String cursor) {
        public SearchRequest {
            if(query==null||query.isBlank()||query.length()>500||query.codePoints().anyMatch(Character::isISOControl)||limit<1||limit>MAX_PAGE_TOOLS||cursor==null||cursor.length()>1600)throw new IllegalArgumentException("invalid_tool_search_request");
            utf8(query);
            if(normalizeSearch(query).isEmpty())throw new IllegalArgumentException("invalid_tool_search_request");
        }
        public static SearchRequest fromJson(JSONObject args)throws Exception {
            Set<String> keys=new HashSet<>();args.keys().forEachRemaining(keys::add);if(!Set.of("query","limit","cursor").containsAll(keys)||!(args.opt("query") instanceof String))throw new IllegalArgumentException("tool_search_accepts_only_query_limit_cursor");
            int limit=8;if(args.has("limit")){Object raw=args.get("limit");if(!(raw instanceof Integer||raw instanceof Long)||((Number)raw).longValue()<1||((Number)raw).longValue()>MAX_PAGE_TOOLS)throw new IllegalArgumentException("integer_search_limit_required");limit=((Number)raw).intValue();}
            String cursor="";if(args.has("cursor")){if(!(args.opt("cursor") instanceof String))throw new IllegalArgumentException("search_cursor_required");cursor=args.getString("cursor");}
            return new SearchRequest(args.getString("query"),limit,cursor);
        }
    }
    public static final class Page {
        private final String value;
        private final Set<String> identities;
        private Page(JSONObject value,Set<String> identities){this.value=value.toString();this.identities=java.util.Collections.unmodifiableSet(new LinkedHashSet<>(identities));}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public Set<String> identities(){return identities;}
    }
    private record Entry(String name,String description,String definition,String metadata,String identity,boolean resident,int bytes) { }
    private record SearchMatch(Entry entry,int score) { }
    private final CapabilitySnapshot scope;
    private final String taskId,runId,revision;
    private final int epoch;
    private final RunControl original;
    private final Map<String,Entry> entries;
    private final Map<String,Entry> identities;
    private final int searchBytes;
    private volatile boolean retired;

    private ScopedToolCatalog(CapabilitySnapshot scope,String taskId,String runId,int epoch,RunControl original,Map<String,Entry> entries,String revision)throws Exception{
        this.scope=scope;this.taskId=taskId;this.runId=runId;this.epoch=epoch;this.original=original;this.entries=Map.copyOf(entries);this.revision=revision;
        Map<String,Entry> byIdentity=new TreeMap<>();for(Entry row:entries.values())byIdentity.put(row.identity,row);identities=Map.copyOf(byIdentity);searchBytes=utf8(searchDefinition().toString()).length;
    }

    public static ScopedToolCatalog capture(CapabilitySnapshot scope,String taskId,String runId,int epoch,RunControl original,
                                            JSONArray definitions,Map<String,SourcePin> sources,Set<String> residentNames)throws Exception {
        java.util.Objects.requireNonNull(scope);java.util.Objects.requireNonNull(sources);java.util.Objects.requireNonNull(residentNames);
        Set<String> allowed=scope.catalogTools(taskId,runId,epoch,original);
        if(definitions.length()>MAX_TOOLS)throw new SecurityException("tool_catalog_not_in_original_scope");
        Map<String,Entry> entries=new TreeMap<>();JSONArray identities=new JSONArray();long bytes=0;
        for(int i=0;i<definitions.length();i++){
            JSONObject definition=definitions.getJSONObject(i),function=definition.getJSONObject("function");String name=function.getString("name");
            if(!"function".equals(definition.optString("type"))||!name.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||name.equals(SEARCH_TOOL)||!allowed.contains(name)||entries.containsKey(name))throw new SecurityException("tool_catalog_name_scope_or_collision");
            SourcePin pin=sources.get(name);if(pin==null)throw new SecurityException("exact_tool_source_missing");
            JSONObject parameters=function.getJSONObject("parameters");String canonical=canonical(definition);bytes+=utf8(canonical).length;if(bytes>MAX_CATALOG_BYTES)throw new IllegalArgumentException("tool_catalog_bytes_exceeded");
            String description=function.opt("description") instanceof String?function.getString("description"):"";
            JSONObject metadata=new JSONObject().put("toolName",name).put("installationId",pin.installationId()==null?JSONObject.NULL:pin.installationId()).put("version",pin.version()==null?JSONObject.NULL:pin.version()).put("sourceKind",pin.kind().name())
                    .put("sourceIdentity",pin.identityDigest()==null?JSONObject.NULL:pin.identityDigest()).put("sourceIdentityKnown",pin.kind()!=SourceKind.UNKNOWN).put("schemaHash",fingerprint(parameters)).put("definitionHash",digest(canonical));
            String identity=fingerprint(metadata);metadata.put("identity",identity).put("description",excerpt(description,160));
            String value=definition.toString();boolean resident=pin.kind()==SourceKind.UNKNOWN||residentNames.contains(name)||utf8(value).length>MAX_PAGE_BYTES-8192||requiresOriginalTransport(definition);
            metadata.put("resident",resident);
            entries.put(name,new Entry(name,description,value,metadata.toString(),identity,resident,utf8(value).length));
        }
        if(!entries.keySet().equals(sources.keySet()))throw new SecurityException("tool_source_map_not_exact");
        if(!sources.equals(scope.sourcePinsFor(taskId,runId,epoch,original,entries.keySet())))throw new SecurityException("tool_sources_not_original_snapshot_pins");
        for(Entry entry:entries.values())identities.put(new JSONObject(entry.metadata));
        String revision=fingerprint(new JSONObject().put("taskId",taskId).put("runId",runId).put("epoch",epoch).put("scopeDigest",scope.catalogScopeDigest()).put("pauseRevision",original.pauseRevision()).put("tools",identities));
        scope.catalogTools(taskId,runId,epoch,original);return new ScopedToolCatalog(scope,taskId,runId,epoch,original,entries,revision);
    }
    public void check()throws Exception {
        if(retired)throw new SecurityException("tool_catalog_retired");
        try{scope.catalogTools(taskId,runId,epoch,original);}catch(Exception stale){retired=true;throw stale;}
    }
    public String revision(){return revision;}
    /** Apply only when discovery is selected; a complete small surface may be cheaper than search. */
    public void requireDiscoveryBudget()throws Exception {
        check();requestSize(Set.of()).requireFits();
        // Every discoverable complete schema must fit with the required resident surface.
        // Otherwise paging could strand a real tool behind a cursor that can never advance.
        for(Entry row:entries.values())if(!row.resident)requestSize(Set.of(row.identity)).requireFits();
        check();
    }
    public boolean containsIdentity(String identity){return identities.containsKey(identity);}
    /** Metadata lookup only; original admission is still required before publication or execution. */
    public String deferredIdentity(String name){Entry row=entries.get(name);return row==null||row.resident?null:row.identity;}
    /** All current-batch disclosures are compulsory next turn; older activations use the remaining budget. */
    public Set<String> selectPublished(Set<String> requiredNext,Collection<String> oldestToNewest)throws Exception {
        check();Set<String> selected=new LinkedHashSet<>(requiredNext);requireIdentities(selected);requestSize(selected).requireFits();
        List<String> recent=new ArrayList<>(oldestToNewest);
        for(int i=recent.size()-1;i>=0;i--){String identity=recent.get(i);Entry row=identities.get(identity);if(row==null||row.resident||selected.contains(identity))continue;
            selected.add(identity);if(!requestSize(selected).fits())selected.remove(identity);
        }
        check();return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(selected));
    }
    private void requireIdentities(Set<String> selected){for(String identity:selected){Entry row=identities.get(identity);if(row==null||row.resident)throw new SecurityException("tool_publication_identity_not_deferred_in_original_catalog");}}
    private RequestSize requestSize(Set<String> selected){
        int count=0,bytes=2;boolean hidden=false;
        for(Entry row:entries.values())if(row.resident||selected.contains(row.identity)){if(count++>0)bytes++;bytes+=row.bytes;}else hidden=true;
        if(hidden){if(count++>0)bytes++;bytes+=searchBytes;}
        return new RequestSize(count,bytes);
    }
    public boolean exposes(String name,Set<String> activated)throws Exception{check();if(SEARCH_TOOL.equals(name))return hasHidden(activated);Entry row=entries.get(name);return row!=null&&(row.resident||activated.contains(row.identity));}
    public JSONArray requestTools(Set<String> activated)throws Exception {
        check();requireIdentities(activated);requestSize(activated).requireFits();JSONArray tools=new JSONArray();for(Entry row:ordered())if(row.resident||activated.contains(row.identity))tools.put(new JSONObject(row.definition));if(hasHidden(activated))tools.put(searchDefinition());requireRequestBudget(tools);check();return tools;
    }
    private boolean hasHidden(Set<String> activated){for(Entry row:entries.values())if(!row.resident&&!activated.contains(row.identity))return true;return false;}
    public JSONObject summary(Set<String> activated)throws Exception {
        check();JSONArray rows=new JSONArray();int hidden=0;for(Entry row:ordered())if(!row.resident&&!activated.contains(row.identity)){hidden++;if(rows.length()<12)rows.put(new JSONObject(row.metadata));}
        RequestSize size=requestSize(activated);
        JSONObject out=new JSONObject().put("format","pocketworld.tool-catalog-summary.v1").put("catalogRevision",revision).put("tools",rows).put("hiddenCount",hidden).put("listedCount",rows.length()).put("truncated",hidden>rows.length()).put("executionAuthorized",false)
                .put("publishedDefinitionCount",size.count).put("publishedDefinitionBytes",size.bytes).put("requestToolLimit",MAX_REQUEST_TOOLS).put("requestByteLimit",MAX_REQUEST_BYTES).put("olderSchemasMayNeedRediscovery",true);check();return out;
    }
    /** Reject obsolete paging arguments before the native read claim is created. */
    public void validateSearch(SearchRequest request)throws Exception {
        check();
        if(!request.cursor.isEmpty()){
            String query=normalizeSearch(request.query);String[] terms=query.split(" ");int total=0;
            for(Entry row:entries.values())if(!row.resident&&searchScore(row,query,terms)>0)total++;
            cursorOffset(request,total);
        }
        check();
    }
    public Page search(SearchRequest request)throws Exception {return search(request,Set.of());}
    /** Reservations include only original issued pages, never model-supplied activation claims. */
    public Page search(SearchRequest request,Set<String> requiredNext)throws Exception {
        check();requireIdentities(requiredNext);requestSize(requiredNext).requireFits();String query=normalizeSearch(request.query);String[] terms=query.split(" ");List<SearchMatch> ranked=new ArrayList<>();
        for(Entry row:ordered()){
            int score=searchScore(row,query,terms);if(score>0)ranked.add(new SearchMatch(row,score));
        }
        ranked.sort(Comparator.comparingInt(SearchMatch::score).reversed().thenComparing(match->match.entry.name));
        List<Entry> matches=new ArrayList<>();JSONArray publishedMatches=new JSONArray();int publishedCount=0;
        for(SearchMatch match:ranked){
            Entry row=match.entry;
            if(row.resident){publishedCount++;if(publishedMatches.length()<MAX_PAGE_TOOLS)publishedMatches.put(row.name);}
            else matches.add(row);
        }
        int offset=cursorOffset(request,matches.size());JSONArray rows=new JSONArray();Set<String> found=new LinkedHashSet<>(),reserved=new LinkedHashSet<>(requiredNext);int next=offset;boolean nextRequestRequired=false;
        while(next<matches.size()&&rows.length()<request.limit){Entry row=matches.get(next);JSONObject result=new JSONObject(row.metadata).put("definition",new JSONObject(row.definition));
            Set<String> candidateIdentities=new LinkedHashSet<>(reserved);candidateIdentities.add(row.identity);
            if(!requestSize(candidateIdentities).fits()){nextRequestRequired=true;break;}
            JSONArray candidate=new JSONArray(rows.toString()).put(result);
            // Leave room for the original session's task/run/discovery receipt envelope.
            if(Math.max(utf8(pageValue(request,candidate,matches.size(),next+1,publishedMatches,publishedCount,false).toString()).length,
                    utf8(pageValue(request,candidate,matches.size(),next+1,publishedMatches,publishedCount,true).toString()).length)>MAX_PAGE_BYTES-1024)break;
            rows.put(result);found.add(row.identity);reserved.add(row.identity);next++;
        }
        if(next==offset&&next<matches.size()&&!nextRequestRequired)throw new IllegalStateException("tool_schema_exceeds_discovery_transport");
        JSONObject value=pageValue(request,rows,matches.size(),next,publishedMatches,publishedCount,nextRequestRequired);
        if(nextRequestRequired&&next==offset&&!request.cursor.isEmpty())value.put("nextCursor",request.cursor);
        check();return new Page(value,found);
    }
    /** Match every keyword across the existing name/description, then prefer exact names
     * and phrases. This is bounded lexical discovery, not inferred capability or authority. */
    private static int searchScore(Entry row,String query,String[] terms){
        String name=normalizeSearch(row.name),description=normalizeSearch(row.description);int score=0;
        for(String term:terms){
            if(name.contains(term))score+=4;
            else if(description.contains(term))score++;
            else return 0;
        }
        if(name.equals(query))return 10000+score;
        if(name.contains(query))return 2000+score;
        if(description.contains(query))return 1000+score;
        return score;
    }
    private static String normalizeSearch(String value){
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{javaWhitespace}\\p{Z}]+"," ").trim();
    }
    private JSONObject pageValue(SearchRequest request,JSONArray rows,int total,int next,JSONArray publishedMatches,int publishedCount,boolean nextRequestRequired)throws Exception {
        boolean more=next<total;String status=nextRequestRequired&&rows.length()==0?"next_request_required":total==0?(publishedCount>0?"already_available":"no_match"):rows.length()==0?"end_of_results":"matched";
        return new JSONObject().put("format","pocketworld.tool-discovery.v1").put("catalogRevision",revision).put("query",request.query).put("matching","all_keywords_name_description_v2").put("status",status).put("tools",rows)
                .put("alreadyPublishedToolNames",publishedMatches).put("alreadyPublishedCount",publishedCount).put("alreadyPublishedTruncated",publishedCount>publishedMatches.length())
                .put("matchedCount",total).put("returnedCount",rows.length()).put("truncated",more).put("nextCursor",more?cursor(request,next):JSONObject.NULL).put("executionAuthorized",false).put("scopeExpanded",false)
                .put("nextRequestRequired",nextRequestRequired).put("requestToolLimit",MAX_REQUEST_TOOLS).put("requestByteLimit",MAX_REQUEST_BYTES)
                .put("continuation",nextRequestRequired?"Use the checkpointed schemas on the next model request before continuing this unchanged cursor; no unread tool was skipped.":"New schemas become usable only on the next model request after the original receipt checkpoint.");
    }
    private String cursor(SearchRequest request,int offset)throws Exception {
        JSONObject value=new JSONObject().put("v",2).put("catalog",revision).put("query",digest(normalizeSearch(request.query))).put("offset",offset).put("limit",request.limit);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(value.toString()));
    }
    private int cursorOffset(SearchRequest request,int total)throws Exception {
        if(request.cursor.isEmpty())return 0;
        try{
            byte[] decoded=Base64.getUrlDecoder().decode(request.cursor);String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
            JSONTokener parser=new JSONTokener(text);Object raw=parser.nextValue();if(!(raw instanceof JSONObject)||parser.nextClean()!=0)throw new IllegalArgumentException();JSONObject cursor=(JSONObject)raw;
            Set<String> keys=new HashSet<>();cursor.keys().forEachRemaining(keys::add);Object offset=cursor.opt("offset"),limit=cursor.opt("limit");
            if(!keys.equals(Set.of("v","catalog","query","offset","limit"))||cursor.optInt("v")!=2||!revision.equals(cursor.opt("catalog"))||!digest(normalizeSearch(request.query)).equals(cursor.opt("query"))
                    ||!(offset instanceof Integer||offset instanceof Long)||((Number)offset).longValue()<0||((Number)offset).longValue()>total||!(limit instanceof Integer||limit instanceof Long)||((Number)limit).longValue()!=request.limit)throw new IllegalArgumentException();
            return ((Number)offset).intValue();
        }catch(Exception invalid){throw new IllegalArgumentException("tool_search_cursor_changed_restart_without_cursor",invalid);}
    }
    private List<Entry> ordered(){return new ArrayList<>(new TreeMap<>(entries).values());}
    public static JSONObject searchDefinition()throws Exception {
        JSONObject properties=new JSONObject().put("query",new JSONObject().put("type","string").put("minLength",1).put("maxLength",500))
                .put("limit",new JSONObject().put("type","integer").put("minimum",1).put("maximum",MAX_PAGE_TOOLS)).put("cursor",new JSONObject().put("type","string").put("maxLength",1600));
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",SEARCH_TOOL).put("description","Search the current native scope using a tool name or a few name/description keywords separated by spaces. Every keyword must match; exact names and phrases rank first. Matching ignores case, surrounding/repeated spaces and full-width variants; it is lexical, not semantic. tools returns exact deferred schemas; alreadyPublishedToolNames identifies matching resident tools with schemas already in this request. already_available does not mean missing. Discovery is metadata, never permission. Keep the same query and limit with a continuation cursor; restart with an empty cursor after changing them or the catalog. New schemas are available only on the next model request after the original receipt checkpoint. Definitions have count and byte budgets: nextRequestRequired or next_request_required means stop searching in this batch, use the newly published tools next turn, then continue the unchanged cursor. Older schemas may leave a later request and can be rediscovered by exact name; never call a tool absent from this request's definitions.")
                .put("parameters",new JSONObject().put("type","object").put("properties",properties).put("required",new JSONArray().put("query")).put("additionalProperties",false)));
    }
    private static boolean requiresOriginalTransport(Object value)throws Exception {
        // A definition that the existing Runner or SQL receipt sanitizer would change must
        // stay on its original schema transport, never become a partial discovery result.
        if(value instanceof JSONObject object){var keys=object.keys();while(keys.hasNext()){String key=keys.next();if(key.equalsIgnoreCase("apiKey")||key.equalsIgnoreCase("authorization")||key.equalsIgnoreCase("token")||key.equalsIgnoreCase("secret")||key.equals("imageBase64")||key.equals("imageDataUrl")||key.equals("image_url")||requiresOriginalTransport(object.get(key)))return true;}}
        else if(value instanceof JSONArray array){if(array.length()>200)return true;for(int i=0;i<array.length();i++)if(requiresOriginalTransport(array.get(i)))return true;}
        else if(value instanceof String text&&text.length()>12000)return true;
        return false;
    }
    private static String excerpt(String text,int max){String normalized=text.replaceAll("[\\p{Cntrl}]"," ");int end=normalized.offsetByCodePoints(0,Math.min(max,normalized.codePointCount(0,normalized.length())));return normalized.substring(0,end);}
    public static String fingerprint(Object value)throws Exception{return digest(canonical(value));}
    private static String digest(String value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(utf8(value));StringBuilder out=new StringBuilder(64);for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
    }
    private static String canonical(Object value)throws Exception{return canonical(value,0);}
    private static String canonical(Object value,int depth)throws Exception {
        if(depth>64)throw new IllegalArgumentException("tool_schema_depth_exceeded");if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject object){Map<String,Object> sorted=new TreeMap<>();var keys=object.keys();while(keys.hasNext()){String key=keys.next();utf8(key);sorted.put(key,object.get(key));}StringBuilder out=new StringBuilder("{");for(var item:sorted.entrySet()){if(out.length()>1)out.append(',');out.append(JSONObject.quote(item.getKey())).append(':').append(canonical(item.getValue(),depth+1));}return out.append('}').toString();}
        if(value instanceof JSONArray array){StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i),depth+1));}return out.append(']').toString();}
        if(value instanceof String text){utf8(text);return JSONObject.quote(text);}if(value instanceof Boolean)return value.toString();if(value instanceof Number){new java.math.BigDecimal(value.toString());return value.toString();}throw new IllegalArgumentException("tool_schema_json_required");
    }
    private static byte[] utf8(String text){try{ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));byte[] out=new byte[bytes.remaining()];bytes.get(out);return out;}catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("tool_catalog_unicode_invalid",invalid);}}
}
