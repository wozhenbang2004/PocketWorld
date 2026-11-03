package dev.pocketengine.browser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Sanitized, process-local evidence for one authenticated target/document. Never a permission ledger. */
final class BrowserNetworkRecords {
    static final int MAX_ROWS=128,MAX_BODY_BYTES=16384,MAX_NATIVE_BODY_BYTES=65536,MAX_BODY_TOTAL=65536;
    private static final String SENSITIVE="(?i).*(password|passwd|secret|credential|authorization|cookie|token|session|api.?key|csrf|xsrf|private.?key|access.?key|refresh|jwt|saml|assertion|signature|auth.?code|verification.?code|one.?time|otp).*";
    final String captureId=UUID.randomUUID().toString();
    private final JSONObject identity;
    private final String frame,loader;
    private final Map<String,Row> requests=new LinkedHashMap<>(),references=new LinkedHashMap<>();
    private final Set<String> inflight=new HashSet<>();
    private final Map<String,Long> presented=new LinkedHashMap<>();
    private final Map<String,JSONObject> offered=new LinkedHashMap<>();
    private String observation="";
    private long lastActivity,sequence,enabledAt;
    private int bodyReserved,bodyReads,events,ignored;
    private boolean active=true,historyTruncated,coverageLost;
    private String unavailable="";
    private static final class Row {
        final String ref=UUID.randomUUID().toString(),id,url,method,type;
        final boolean sensitiveUrl;
        String state="requested",mime="",overlappedOperation="";int status,redirects;long started,finished,revision,bytes=-1,retryAfterMs;
        boolean requestedBody;
        Row(String id,String url,String method,String type,boolean sensitive){this.id=id;this.url=url;this.method=method;this.type=type;sensitiveUrl=sensitive;}
        JSONObject safe()throws Exception{return new JSONObject().put("requestRef",ref).put("responseRevision",revision).put("url",url).put("method",method).put("resourceType",type).put("phase",state).put("status",status).put("mimeType",mime).put("startedAtElapsedMs",started).put("finishedAtElapsedMs",finished).put("encodedDataLength",bytes).put("redirects",redirects).put("overlappedOperationId",overlappedOperation).put("causalityProven",false).put("retryAfterMs",retryAfterMs).put("bodyEligible",bodyEligible()).put("bodyAlreadyRequested",requestedBody);}
        boolean bodyEligible(){return state.equals("finished")&&!sensitiveUrl&&!requestedBody&&jsonMime(mime)&&status>=200&&status<400;}
    }
    static final class BodyPermit {
        private final Row row;final String requestId,requestRef,captureId;final int maxBytes;private final long revision;private boolean materialized;
        BodyPermit(Row row,String capture,int max){this.row=row;requestId=row.id;requestRef=row.ref;captureId=capture;maxBytes=max;revision=row.revision;}
    }
    BrowserNetworkRecords(JSONObject identity,String frame,String loader,long now)throws Exception {this.identity=new JSONObject(identity.toString());if(frame.isEmpty()||loader.isEmpty())throw new SecurityException("native_network_document_identity_missing");this.frame=frame;this.loader=loader;lastActivity=enabledAt=now;}
    synchronized void networkEnabled(long now){if(active){enabledAt=now;lastActivity=Math.max(lastActivity,now);}}
    synchronized void event(JSONObject event,String overlappedOperation,long now)throws Exception {
        if(!active)return;if(++events>4096){invalidate("native_network_event_budget");return;}String method=event.optString("method");JSONObject p=event.optJSONObject("params");if(p==null)return;
        if(method.equals("Network.requestWillBeSent")){
            if(!frame.equals(p.optString("frameId"))||!loader.equals(p.optString("loaderId"))){ignored++;return;}
            String id=p.getString("requestId");if(id.length()>200){coverageLost=true;return;}JSONObject raw=p.getJSONObject("request");String rawUrl=raw.getString("url");
            Row prior=requests.get(id);int redirects=0;if(prior!=null){if(!p.has("redirectResponse")){coverageLost=true;return;}prior.state="redirected";prior.finished=now;prior.revision=++sequence;prior.status=p.getJSONObject("redirectResponse").optInt("status");redirects=prior.redirects+1;inflight.remove(id);}
            Row row=new Row(id,safeUrl(rawUrl),safeToken(raw.optString("method"),16),safeToken(p.optString("type"),32),sensitiveUrl(rawUrl));row.started=now;row.revision=++sequence;row.redirects=redirects;
            row.overlappedOperation=overlappedOperation==null?"":overlappedOperation;requests.put(id,row);references.put(row.ref,row);inflight.add(id);lastActivity=now;
            while(references.size()>MAX_ROWS){Row removed=references.remove(references.keySet().iterator().next());presented.remove(removed.ref);if(requests.get(removed.id)==removed){requests.remove(removed.id);if(inflight.remove(removed.id))coverageLost=true;}historyTruncated=true;}return;
        }
        if(!method.matches("Network\\.(responseReceived|loadingFinished|loadingFailed)"))return;
        Row row=requests.get(p.optString("requestId"));if(row==null){ignored++;return;}
        if(method.equals("Network.responseReceived")){
            if(!frame.equals(p.optString("frameId"))||!loader.equals(p.optString("loaderId"))){coverageLost=true;return;}
            JSONObject response=p.getJSONObject("response");row.status=Math.max(0,Math.min(599,response.optInt("status")));row.mime=mime(response.optString("mimeType"));row.state="response";
            if(row.status==429||row.status==503){JSONObject headers=response.optJSONObject("headers");if(headers!=null)for(String key:headers.keySet())if(key.equalsIgnoreCase("retry-after")){String value=String.valueOf(headers.opt(key));if(value.matches("[0-9]{1,6}"))row.retryAfterMs=Math.min(300000,Long.parseLong(value)*1000);}}
        }else{row.state=method.equals("Network.loadingFinished")?"finished":"failed";row.finished=now;row.bytes=Math.max(-1,p.optLong("encodedDataLength",-1));inflight.remove(row.id);}
        row.revision=++sequence;lastActivity=now;
    }
    synchronized JSONObject snapshot(long now)throws Exception {
        JSONObject out=base().put("status",active?"observed":"unavailable").put("accepted",active).put("code",unavailable);JSONArray rows=new JSONArray();offered.clear();observation=UUID.randomUUID().toString();
        ArrayList<Row> values=new ArrayList<>(references.values());for(int i=values.size()-1;i>=0;i--){Row row=values.get(i);JSONObject safe=row.safe();rows.put(safe);if(rows.toString().getBytes(StandardCharsets.UTF_8).length>24000){rows.remove(rows.length()-1);break;}offered.put(row.ref,new JSONObject(safe.toString()));}
        return out.put("networkObservationId",observation).put("captureEnabledAtElapsedMs",enabledAt).put("requests",rows).put("rowsOmitted",references.size()-rows.length()).put("historyTruncated",historyTruncated).put("coverageLost",coverageLost).put("inflightObserved",inflight.size()).put("quietForMs",Math.max(0,now-lastActivity)).put("ignoredOutsideCoverage",ignored).put("bodyBytesRemaining",MAX_BODY_TOTAL-bodyReserved).put("bodyReadsRemaining",8-bodyReads).put("message","已读取该原文档真实CDP元数据；覆盖从启用后开始的主frame请求。正文、凭据头和查询参数未交付，没有补发请求。");
    }
    /** Only the original Runner afterTool safe-JSON delivery can make a response object known. */
    synchronized void delivered(JSONObject actual)throws Exception {
        if(!active||!captureId.equals(actual.optString("captureId"))||!observation.equals(actual.optString("networkObservationId"))||!actual.optString("backend").equals("webview_cdp_local_socket"))throw new SecurityException("native_network_delivery_not_current");
        for(String key:identity.keySet())if(!String.valueOf(identity.get(key)).equals(String.valueOf(actual.opt(key))))throw new SecurityException("native_network_delivery_identity");
        JSONArray rows=actual.getJSONArray("requests");if(rows.length()>MAX_ROWS)throw new SecurityException("native_network_delivery_limit");
        Map<String,Long> proven=new LinkedHashMap<>();
        for(int i=0;i<rows.length();i++){JSONObject shown=rows.getJSONObject(i);String ref=shown.getString("requestRef");JSONObject original=offered.get(ref);if(original==null||!original.toString().equals(shown.toString())||proven.containsKey(ref))throw new SecurityException("native_network_unshown_response");Row row=references.get(ref);if(row!=null&&row.revision==shown.getLong("responseRevision"))proven.put(ref,row.revision);}
        presented.putAll(proven);offered.clear();observation="";
    }
    synchronized JSONObject bodyPin(JSONObject args)throws Exception {
        Row row=bodyRow(args);int max=args.optInt("maxBytes",8192);if(max<256||max>MAX_BODY_BYTES)throw new IllegalArgumentException("native_network_body_budget");
        return new JSONObject().put("captureId",captureId).put("requestRef",row.ref).put("responseRevision",row.revision).put("url",row.url).put("status",row.status).put("mimeType",row.mime).put("maxDisclosureBytes",max).put("requiresSeparateApproval",true).put("credentialHeadersIncluded",false).put("bodyPolicy","json_only_redacted_sensitive_fields_no_refetch");
    }
    private Row bodyRow(JSONObject args)throws Exception {
        if(!active||!captureId.equals(args.optString("captureId")))throw new SecurityException("native_network_capture_not_current");Row row=references.get(args.optString("requestRef"));
        if(row==null||presented.getOrDefault(row.ref,-1L)!=row.revision||!row.bodyEligible()||requests.get(row.id)!=row)throw new SecurityException("native_network_response_not_presented_or_readable");return row;
    }
    synchronized BodyPermit reserveBody(JSONObject args)throws Exception {
        Row row=bodyRow(args);int max=bodyPin(args).getInt("maxDisclosureBytes");if(bodyReads>=8||bodyReserved+max>MAX_BODY_TOTAL)throw new SecurityException("native_network_body_disclosure_exhausted");
        row.requestedBody=true;bodyReads++;bodyReserved+=max;return new BodyPermit(row,captureId,max);
    }
    synchronized void requireBodyCurrent(BodyPermit permit)throws Exception {if(!active||!captureId.equals(permit.captureId)||references.get(permit.requestRef)!=permit.row||requests.get(permit.requestId)!=permit.row||permit.row.revision!=permit.revision||!permit.row.state.equals("finished"))throw new SecurityException("native_network_response_changed");}
    synchronized JSONObject body(BodyPermit permit,JSONObject response)throws Exception {
        requireBodyCurrent(permit);if(permit.materialized)throw new SecurityException("native_network_body_already_materialized");permit.materialized=true;String supplied=response.getString("body");byte[] bytes;
        if(supplied.length()>MAX_NATIVE_BODY_BYTES*2)return bodyUnavailable(permit,"body_exceeds_native_buffer");
        try{bytes=response.optBoolean("base64Encoded")?Base64.getDecoder().decode(supplied):supplied.getBytes(StandardCharsets.UTF_8);}catch(IllegalArgumentException invalid){return bodyUnavailable(permit,"body_encoding_unavailable");}
        try{
            if(bytes.length>MAX_NATIVE_BODY_BYTES)return bodyUnavailable(permit,"body_exceeds_native_buffer");Object parsed;
            try{String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(!boundedJsonDepth(decoded))return bodyUnavailable(permit,"body_json_depth_limit");JSONTokener tokener=new JSONTokener(decoded);parsed=tokener.nextValue();if(!(parsed instanceof JSONObject||parsed instanceof JSONArray)||tokener.nextClean()!=0)return bodyUnavailable(permit,"body_not_complete_json");}catch(Exception invalid){return bodyUnavailable(permit,"body_not_complete_json");}
            int[] nodes={0},redactions={0};Object safe=scrub(parsed,0,nodes,redactions);String text=safe.toString();boolean truncated=text.getBytes(StandardCharsets.UTF_8).length>permit.maxBytes;String bounded=clipBytes(text,permit.maxBytes);
            return base().put("status","body_observed").put("accepted",true).put("requestRef",permit.requestRef).put("body",bounded).put("bodyFormat","redacted_json_text").put("bodyTruncated",truncated).put("redactedFieldsOrValues",redactions[0]).put("bytesDisclosed",bounded.getBytes(StandardCharsets.UTF_8).length).put("maxDisclosureBytes",permit.maxBytes).put("automaticReplayAllowed",false).put("bodyRefetched",false);
        }finally{java.util.Arrays.fill(bytes,(byte)0);}
    }
    synchronized JSONObject bodyUnavailable(BodyPermit permit,String code)throws Exception {return base().put("status","unavailable").put("accepted",false).put("requestRef",permit.requestRef).put("code",code).put("bodyRefetched",false).put("automaticReplayAllowed",false);}
    synchronized JSONObject quiet(long now)throws Exception {return new JSONObject().put("available",active&&!coverageLost).put("captureId",captureId).put("inflight",inflight.size()).put("quietForMs",Math.max(0,now-lastActivity)).put("sequence",sequence).put("coverageLost",coverageLost).put("scope","main_frame_requests_started_after_capture").put("preexistingRequestsKnown",false).put("networkIdleProven",false);}
    synchronized void invalidate(String reason){active=false;unavailable=reason;references.clear();requests.clear();presented.clear();offered.clear();observation="";inflight.clear();}
    synchronized boolean active(){return active;}
    private JSONObject base()throws Exception{return new JSONObject(identity.toString()).put("captureId",captureId).put("backend","webview_cdp_local_socket").put("partial",true).put("coverage","authenticated_main_frame_loader_requests_started_after_enable").put("excludedCoverage",new JSONArray().put("preexisting_requests").put("child_frames").put("workers").put("websocket_frames").put("downloads_outside_target")).put("untrustedPageContent",true).put("transportObservation",true).put("responseBodiesReadByDefault",false).put("credentialHeadersRetained",false).put("credentialHeadersExported",false).put("urlRedaction","userinfo_query_fragment_and_sensitive_path_segments_removed").put("networkIdleProven",false).put("automaticRequestsPerformed",false).put("serverBusinessOutcomeVerified",false);}
    static String safeUrl(String value)throws Exception {
        URI u;try{u=new URI(value);}catch(Exception invalid){return "[invalid resource address]";}if(u.getHost()==null||!Set.of("http","https").contains(u.getScheme()))return "[non-http resource]";
        String origin=new URI(u.getScheme(),null,u.getHost(),u.getPort(),null,null,null).toASCIIString();String path=u.getRawPath()==null?"/":u.getRawPath();StringBuilder out=new StringBuilder(origin);boolean hideNext=false;
        for(String segment:path.split("/",-1)){if(out.length()==origin.length()&&segment.isEmpty())continue;out.append('/');boolean sensitive=hideNext||segment.length()>48||segment.matches(SENSITIVE)||segment.matches("[A-Za-z0-9_-]{32,}")||segment.contains("%")||segment.contains(";")||segment.contains("@");out.append(sensitive?"[redacted]":segment);hideNext=segment.matches(SENSITIVE)||segment.matches("(?i)auth|oauth|login|signin|code");if(out.length()>400){out.setLength(Math.min(400,out.length()));out.append("[truncated]");break;}}return out.toString();
    }
    private static boolean sensitiveUrl(String value){String lowered=value.toLowerCase(Locale.ROOT);return lowered.matches(".*(?:/|[?&])(?:login|signin|auth|oauth|password|token|session|secret|credential|code|api.?key)(?:[/=&?._-].*|$)")||lowered.contains("%");}
    private static String safeToken(String value,int max){String safe=value.replaceAll("[^A-Za-z0-9_-]","");return safe.substring(0,Math.min(max,safe.length()));}
    private static String mime(String value){String safe=value.toLowerCase(Locale.ROOT).split(";",2)[0].trim();return safe.length()<=100&&safe.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")?safe:"";}
    private static boolean jsonMime(String value){return value.equals("application/json")||value.matches("application/[a-z0-9.-]+\\+json");}
    /** Check before the recursive JSON parser, not after private input has already exhausted its stack. */
    private static boolean boundedJsonDepth(String text){int depth=0;boolean quoted=false,escaped=false;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c==39)return false;else if(c=='{'||c=='['){if(++depth>32)return false;}else if(c=='}'||c==']'){if(--depth<0)return false;}}return !quoted&&depth==0;}
    private static Object scrub(Object value,int depth,int[] nodes,int[] redactions)throws Exception {
        if(depth>12||++nodes[0]>1024){redactions[0]++;return "[structure omitted]";}
        if(value instanceof JSONObject){JSONObject out=new JSONObject();JSONObject object=(JSONObject)value;for(String key:object.keySet()){if(out.length()>=128){out.put("_omitted",true);break;}boolean privateKey=key.length()>120||key.matches(".*[\\r\\n%?&=:@].*")||key.matches("[A-Za-z0-9_+/=-]{32,}")||key.matches(SENSITIVE)||key.matches("(?i)auth|key|code|ticket|pass|pin");if(privateKey){out.put("[redacted-field-"+out.length()+"]","[redacted]");redactions[0]++;}else out.put(key,scrub(object.get(key),depth+1,nodes,redactions));}return out;}
        if(value instanceof JSONArray){JSONArray out=new JSONArray(),array=(JSONArray)value;for(int i=0;i<Math.min(array.length(),128);i++)out.put(scrub(array.get(i),depth+1,nodes,redactions));if(array.length()>128)out.put("[items omitted]");return out;}
        if(value instanceof String){String text=(String)value;if(text.matches("(?is).*(?:bearer\\s+|basic\\s+|eyJ[a-zA-Z0-9_-]+\\.|-----BEGIN |(?:password|passwd|token|api.?key|secret|authorization|cookie|session|otp)\\s*[:=]).*")||text.matches("[A-Za-z0-9_+/=-]{48,}")){redactions[0]++;return "[redacted]";}if(text.startsWith("https://")||text.startsWith("http://")){redactions[0]++;return safeUrl(text);}return clipBytes(text,2048);}
        return value;
    }
    static String clipBytes(String value,int max){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length<=max)return value;int end=max;while(end>0&&(bytes[end]&192)==128)end--;return new String(bytes,0,end,StandardCharsets.UTF_8);}
}
