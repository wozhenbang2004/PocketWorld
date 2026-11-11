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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    public static final int MAX_TOOLS=100,MAX_PAGE_TOOLS=12,MAX_PAGE_BYTES=48*1024,MAX_CATALOG_BYTES=24*1024*1024;
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
        private Page(JSONObject value,Set<String> identities){this.value=value.toString();this.identities=Set.copyOf(identities);}
        public JSONObject view()throws Exception{return new JSONObject(value);}
        public Set<String> identities(){return identities;}
    }
    private record Entry(String name,String description,String definition,String metadata,String identity,boolean resident) { }
    private record SearchMatch(Entry entry,int score) { }
    private final CapabilitySnapshot scope;
    private final String taskId,runId,revision;
    private final int epoch;
    private final RunControl original;
    private final Map<String,Entry> entries;
    private volatile boolean retired;

    private ScopedToolCatalog(CapabilitySnapshot scope,String taskId,String runId,int epoch,RunControl original,Map<String,Entry> entries,String revision){this.scope=scope;this.taskId=taskId;this.runId=runId;this.epoch=epoch;this.original=original;this.entries=Map.copyOf(entries);this.revision=revision;}

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
            entries.put(name,new Entry(name,description,value,metadata.toString(),identity,resident));
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
    public boolean containsIdentity(String identity){return entries.values().stream().anyMatch(row->row.identity.equals(identity));}
    public boolean exposes(String name,Set<String> activated)throws Exception{check();if(SEARCH_TOOL.equals(name))return hasHidden(activated);Entry row=entries.get(name);return row!=null&&(row.resident||activated.contains(row.identity));}
    public JSONArray requestTools(Set<String> activated)throws Exception {
        check();JSONArray tools=new JSONArray();for(Entry row:ordered())if(row.resident||activated.contains(row.identity))tools.put(new JSONObject(row.definition));if(hasHidden(activated))tools.put(searchDefinition());check();return tools;
    }
    private boolean hasHidden(Set<String> activated){for(Entry row:entries.values())if(!row.resident&&!activated.contains(row.identity))return true;return false;}
    public JSONObject summary(Set<String> activated)throws Exception {
        check();JSONArray rows=new JSONArray();int hidden=0;for(Entry row:ordered())if(!row.resident&&!activated.contains(row.identity)){hidden++;if(rows.length()<12)rows.put(new JSONObject(row.metadata));}
        JSONObject out=new JSONObject().put("format","pocketworld.tool-catalog-summary.v1").put("catalogRevision",revision).put("tools",rows).put("hiddenCount",hidden).put("listedCount",rows.length()).put("truncated",hidden>rows.length()).put("executionAuthorized",false);check();return out;
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
    public Page search(SearchRequest request)throws Exception {
        check();String query=normalizeSearch(request.query);String[] terms=query.split(" ");List<SearchMatch> ranked=new ArrayList<>();
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
        int offset=cursorOffset(request,matches.size());JSONArray rows=new JSONArray();Set<String> found=new HashSet<>();int next=offset;
        while(next<matches.size()&&rows.length()<request.limit){Entry row=matches.get(next);JSONObject result=new JSONObject(row.metadata).put("definition",new JSONObject(row.definition));
            JSONArray candidate=new JSONArray(rows.toString()).put(result);if(utf8(pageValue(request,candidate,matches.size(),next+1,publishedMatches,publishedCount).toString()).length>MAX_PAGE_BYTES)break;
            rows.put(result);found.add(row.identity);next++;
        }
        if(next==offset&&next<matches.size())throw new IllegalStateException("tool_schema_exceeds_discovery_transport");
        JSONObject value=pageValue(request,rows,matches.size(),next,publishedMatches,publishedCount);check();return new Page(value,found);
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
    private JSONObject pageValue(SearchRequest request,JSONArray rows,int total,int next,JSONArray publishedMatches,int publishedCount)throws Exception {
        boolean more=next<total;String status=total==0?(publishedCount>0?"already_available":"no_match"):rows.length()==0?"end_of_results":"matched";
        return new JSONObject().put("format","pocketworld.tool-discovery.v1").put("catalogRevision",revision).put("query",request.query).put("matching","all_keywords_name_description_v2").put("status",status).put("tools",rows)
                .put("alreadyPublishedToolNames",publishedMatches).put("alreadyPublishedCount",publishedCount).put("alreadyPublishedTruncated",publishedCount>publishedMatches.length())
                .put("matchedCount",total).put("returnedCount",rows.length()).put("truncated",more).put("nextCursor",more?cursor(request,next):JSONObject.NULL).put("executionAuthorized",false).put("scopeExpanded",false);
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
        return new JSONObject().put("type","function").put("function",new JSONObject().put("name",SEARCH_TOOL).put("description","Search the current native scope using a tool name or a few name/description keywords separated by spaces. Every keyword must match; exact names and phrases rank first. Matching ignores case, surrounding/repeated spaces and full-width variants; it is lexical, not semantic. tools returns exact deferred schemas; alreadyPublishedToolNames identifies matching resident tools with schemas already in this request. already_available does not mean missing. Discovery is metadata, never permission. Keep the same query and limit with a continuation cursor; restart with an empty cursor after changing them or the catalog. New schemas are available on the next model request after the original receipt checkpoint.")
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
