package dev.pocketengine.browser;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.features.memory.ToolOutputCapture;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sources are durable events in the original TaskStore, never a second task/source database. */
public final class BrowserTaskSourcePort implements BrowserTaskSpace.SourcePort {
    private static final String TOOL="browser_task_retain_source",TYPE="browser_source_retained";
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final RunControl control;
    private final TaskStoreCapabilityAuthority authority;
    private final ModelCapabilityAdapters.ResourceCheck resource;
    private final EventSink changed;private final ToolOutputCapture.Factory outputCapture;
    public BrowserTaskSourcePort(TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl originalControl,
            TaskStoreCapabilityAuthority authority,ModelCapabilityAdapters.ResourceCheck resource,EventSink changed) {this(tasks,binding,originalControl,authority,resource,changed,null);}
    public BrowserTaskSourcePort(TaskStore tasks,ModelCapabilityAdapters.Binding binding,RunControl originalControl,
            TaskStoreCapabilityAuthority authority,ModelCapabilityAdapters.ResourceCheck resource,EventSink changed,ToolOutputCapture.Factory outputCapture) {
        this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);control=Objects.requireNonNull(originalControl);
        this.authority=Objects.requireNonNull(authority);this.resource=Objects.requireNonNull(resource);this.changed=Objects.requireNonNull(changed);this.outputCapture=outputCapture;
    }
    @Override public boolean publicHttpRetention(){return true;}
    @Override public JSONObject retainPublic(ModelCapabilityAdapters.Binding supplied,RunControl original,String operationId,String sourceId,JSONObject source,PublicHttpReader.Budget budget)throws Exception {
        if(budget==null||!budget.boundTo(original))throw new SecurityException("公共读取需原native预算");
        return retain(supplied,original,operationId,sourceId,source,budget,null);
    }
    @Override public JSONObject retain(ModelCapabilityAdapters.Binding supplied,RunControl original,String operationId,String sourceId,JSONObject lowTrustSource)throws Exception{return retain(supplied,original,operationId,sourceId,lowTrustSource,null,null);}
    @Override public JSONObject retainDelivered(ModelCapabilityAdapters.Binding supplied,RunControl original,String operationId,String sourceId,JSONObject source,BrowserTaskSpace.DeliveredSource delivered)throws Exception {return retain(supplied,original,operationId,sourceId,source,null,delivered);}
    private JSONObject retain(ModelCapabilityAdapters.Binding supplied,RunControl original,String operationId,String sourceId,JSONObject lowTrustSource,PublicHttpReader.Budget budget,BrowserTaskSpace.DeliveredSource delivered)throws Exception {
        same(supplied);if(original!=control||!control.matchesHostRun(binding.taskId,binding.runId,binding.epoch))throw new SecurityException("来源写入需要同一个原任务控制器");
        BrowserTaskSpace.uuid(operationId);BrowserTaskSpace.uuid(sourceId);JSONObject source=validated(sourceId,lowTrustSource);boolean http=source.getString("kind").equals("public_http_observation");String tool=http?PublicHttpReader.TOOL:TOOL;
        if(http&&budget==null||!http&&budget!=null)throw new SecurityException("来源类型与原读取路径不匹配");
        if(budget!=null)budget.check();control.check();resource.check();tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,tool);
        JSONObject safeSubset=delivered==null?null:delivered.forSource(source);ToolOutputCapture.Claim claim=null;
        if(safeSubset!=null&&outputCapture!=null){
            try{claim=outputCapture.begin(TOOL,operationId,ToolOutputCapture.Kind.NATIVE_RESULT_DATA_JSON,original);}
            catch(ToolOutputCapture.OutputSourceUnavailable revoked){throw revoked;}
            catch(Exception unavailable){control.check();resource.check();tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,tool);}
        }
        JSONObject receipt;
        synchronized(tasks){
            if(budget!=null)budget.checkInsideStore();
            JSONObject task=privateTask(binding.taskId),known=find(task,binding,sourceId);
            if(known!=null){requireSameSource(known,source);return receipt(known);}
            JSONObject arguments=null;JSONArray operations=task.getJSONArray("operations");
            for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);if(operationId.equals(operation.optString("operationId")))arguments=operation.getJSONObject("arguments");}
            if(arguments==null)throw new SecurityException("缺少原AgentRunner来源操作");authority.requirePrepared(operationId,tool,arguments);
            if(http){PublicHttpReader.requireRetainedPolicy(arguments,source.getJSONObject("http"),source.getString("excerpt"));}
            else if(!source.getString("tabId").equals(arguments.optString("tabId"))||!source.getString("observationId").equals(arguments.optString("observationId"))||arguments.has("excerpt")&&!source.getString("excerpt").equals(arguments.getString("excerpt")))throw new SecurityException("来源不属于原操作的观察与摘录");
            int count=0;JSONArray events=task.getJSONArray("events");for(int i=0;i<events.length();i++)if(TYPE.equals(events.getJSONObject(i).optString("type")))count++;
            if(count>=100)throw new IllegalStateException("此任务保存来源已达100个");
            // All TaskStore mutations use this same monitor. Do not call a main-queue resource
            // callback while holding it: the queue may itself be reading TaskStore.
            if(control.isCancelled()||control.isPaused())throw new SecurityException("来源写入前原任务已停止");
            tasks.checkCapabilityCurrent(binding.taskId,binding.runId,binding.epoch,tool);
            tasks.checkBrowserReadScopeOperationIfPresent(binding.taskId,binding.runId,binding.epoch,control,operationId,tool);tasks.checkBrowserActionOperationIfPresent(binding.taskId,binding.runId,binding.epoch,control,operationId,tool);
            if(budget!=null)budget.checkInsideStore();
            JSONObject event=new JSONObject().put("type",TYPE).put("sourceId",sourceId).put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch)
                    .put("operationId",operationId).put("sourceFingerprint",TaskStore.capabilityFingerprint(source)).put("source",source);
            if(safeSubset!=null)event.put("safeProjectionFormat","pocketworld.browser-retained-safe-delivery.v1").put("safeProjectionFingerprint",TaskStore.capabilityFingerprint(safeSubset));
            tasks.event(binding.taskId,binding.runId,binding.epoch,event);
            JSONObject persisted=find(privateTask(binding.taskId),binding,sourceId);if(persisted==null)throw new IllegalStateException("来源写入回执未确认");requireSameSource(persisted,source);if(budget!=null)budget.checkInsideStore();receipt=receipt(persisted);
        }
        if(safeSubset!=null){
            ToolOutputCapture.Result capture;
            if(claim==null)capture=ToolOutputCapture.unavailable(outputCapture==null?"native_output_not_configured":"native_output_claim_unavailable");
            else try{capture=claim.record(safeSubset,null);}catch(Exception unavailable){capture=ToolOutputCapture.unavailable("native_output_capture_unavailable");}
            receipt.put("outputCapture",capture.metadata());
        }
        try{changed.emit(new JSONObject().put("type","browser_sources_changed").put("taskId",binding.taskId).put("sourceId",sourceId));}catch(Exception unavailable){/* The committed source remains discoverable in TaskStore. */}
        return receipt;
    }
    @Override public JSONObject lookup(ModelCapabilityAdapters.Binding supplied,String sourceId)throws Exception {
        same(supplied);BrowserTaskSpace.uuid(sourceId);synchronized(tasks){JSONObject found=find(privateTask(binding.taskId),binding,sourceId);return found==null?new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch).put("sourceId",sourceId).put("status","not_found"):receipt(found);}
    }
    /** Typed native-owner projection; all runs retain their original identities and low-trust flags. */
    public static JSONArray sourcesForOwner(TaskStore tasks,String taskId)throws Exception {
        BrowserTaskSpace.uuid(taskId);synchronized(tasks){JSONObject task=tasks.get(taskId,false);requirePrivate(task);JSONArray sources=new JSONArray(),events=task.getJSONArray("events");
            for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(TYPE.equals(event.optString("type"))){JSONObject source=new JSONObject(event.getJSONObject("source").toString());if(!taskId.equals(source.getString("taskId"))||!taskId.equals(event.getString("taskId"))||!event.getString("sourceId").equals(source.getString("sourceId"))||!event.getString("runId").equals(source.getString("runId"))||event.getInt("epoch")!=source.getInt("epoch")||!event.getString("sourceFingerprint").equals(TaskStore.capabilityFingerprint(source)))throw new SecurityException("已保存网页来源版本不一致，未用当前网页代替");BrowserStructuredSource.addReferences(source,source);source.put("sourceRef",sourceRef(taskId,event.getString("sourceId"))).put("sourceFingerprint",event.getString("sourceFingerprint")).put("retainedAt",event.getLong("at")).put("operationId",event.getString("operationId"));sources.put(source);}}
            return sources;
        }
    }
    /** Native explicit project-reference picker: keeps provenance without copying private URLs/cookies. */
    public static JSONObject projectReferenceForOwner(TaskStore tasks,String taskId,String sourceId)throws Exception {
        BrowserTaskSpace.uuid(sourceId);JSONArray sources=sourcesForOwner(tasks,taskId);for(int i=0;i<sources.length();i++){JSONObject source=sources.getJSONObject(i);if(!sourceId.equals(source.getString("sourceId")))continue;JSONObject reference=new JSONObject().put("kind","browser_source_reference").put("privateUrlIncluded",false).put("untrusted",true).put("untrustedPageContent",true).put("verified",false).put("redactedUrl",source.getString("url"));
            for(String key:new String[]{"sourceRef","sourceId","sourceFingerprint","taskId","runId","epoch","urlQueryOmitted","observedAt","retainedAt"})reference.put(key,source.get(key));
            String privateUrl=source.getString("privateUrl"),displayUrl=source.getString("url"),title=source.getString("title");
            // Some WebViews use the URL as a provisional title, including a truncated URL.
            reference.put("title",!title.isEmpty()&&privateUrl.startsWith(title)?displayUrl:title.replace(privateUrl,displayUrl));
            reference.put("excerpt",source.getString("excerpt").replace(privateUrl,displayUrl));return reference;
        }throw new IllegalArgumentException("原任务没有此浏览来源");
    }
    private void same(ModelCapabilityAdapters.Binding supplied){if(supplied!=binding)throw new SecurityException("来源端口不能更换原任务绑定");}
    private JSONObject privateTask(String taskId)throws Exception {JSONObject task=tasks.get(taskId,false);requirePrivate(task);return task;}
    private static void requirePrivate(JSONObject task){if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))throw new SecurityException("来源只属于原主人私人任务");}
    private JSONObject validated(String id,JSONObject value)throws Exception {
        JSONObject source=new JSONObject(Objects.requireNonNull(value).toString());boolean http="public_http_observation".equals(source.optString("kind")),semantic=BrowserSemanticRead.KIND.equals(source.optString("kind")),live="browser_live_observation".equals(source.optString("kind"));Set<String> fields=new java.util.HashSet<>(Set.of("sourceId","taskId","runId","epoch","profileId","profileRevision","browserSessionId","profileIsolation","browserSpaceId","tabId","tabGeneration","openedBy","url","urlQueryOmitted","privateUrl","urlScope","title","excerpt","observedAt","observationId","navigation","frameId","acquisitionMethod","dataScope","kind","untrustedPageContent","verified"));if(source.has("logicalPageId")){fields.add("logicalPageId");BrowserTaskSpace.uuid(source.getString("logicalPageId"));}if(http)fields.add("http");if(semantic)fields.addAll(Set.of("semantic","documentId","frameCoverage"));if(live)fields.addAll(Set.of("liveEvidence","documentId","frameCoverage"));
        if(semantic&&source.has("structuredProjection"))fields.add("structuredProjection");if(semantic&&source.has("readRecipe"))fields.add("readRecipe");
        for(java.util.Iterator<String> keys=source.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new SecurityException("来源含未授权字段");
        if(source.length()!=fields.size()||!id.equals(source.optString("sourceId"))||!binding.taskId.equals(source.optString("taskId"))||!binding.runId.equals(source.optString("runId"))||binding.epoch!=source.optInt("epoch"))throw new SecurityException("来源身份不属于原任务");
        for(String field:http?new String[]{"browserSessionId","browserSpaceId","observationId"}:new String[]{"browserSessionId","browserSpaceId","tabId","observationId"})BrowserTaskSpace.uuid(source.getString(field));
        if(!source.getString("profileId").matches("[A-Za-z0-9_-]{1,80}")||source.getLong("profileRevision")<1||!Set.of("androidx-multi-profile","default-webview-only").contains(source.getString("profileIsolation"))||!source.getString("openedBy").equals(http?"task_public_http":"task"))throw new SecurityException("来源浏览身份无效");
        URI url=new URI(source.getString("url"));if(url.getHost()==null||url.getUserInfo()!=null||url.getRawQuery()!=null||url.getRawFragment()!=null||!Set.of("http","https").contains(url.getScheme())||source.getString("url").length()>2048)throw new SecurityException("来源地址必须是省略查询与片段的原网页地址");
        URI originalUrl=new URI(source.getString("privateUrl"));if(originalUrl.getHost()==null||originalUrl.getUserInfo()!=null||!Set.of("http","https").contains(originalUrl.getScheme())||source.getString("privateUrl").length()>8192||!(http?"owner_private_task":"owner_private_profile").equals(source.getString("urlScope")))throw new SecurityException("原出处地址需要原主人私人Profile范围");
        String projection=new URI(originalUrl.getScheme(),null,originalUrl.getHost(),originalUrl.getPort(),originalUrl.getPath(),null,null).toASCIIString();if(!projection.equals(source.getString("url"))||source.getBoolean("urlQueryOmitted")!=(originalUrl.getRawQuery()!=null||originalUrl.getRawFragment()!=null))throw new SecurityException("出处展示地址与精确原地址不一致");
        if(!(source.get("urlQueryOmitted") instanceof Boolean)||source.getString("title").length()>240||source.getString("excerpt").length()>6000||source.getLong("observedAt")<=0
                ||!Boolean.TRUE.equals(source.get("untrustedPageContent"))||!Boolean.FALSE.equals(source.get("verified")))throw new SecurityException("来源正文或低信任标记无效");
        if(http){
            if(!source.isNull("tabId")||!source.isNull("tabGeneration")||!source.isNull("navigation")||!source.getString("frameId").equals("not_rendered")
                    ||!source.getString("acquisitionMethod").equals("public_http")||!source.getString("dataScope").equals("public_anonymous_response"))throw new SecurityException("公共HTTP来源不可伪造tab/document/ref");
            validateHttpFacts(source.getJSONObject("http"),source);
        }else if(source.getLong("navigation")<0||!(source.get("tabGeneration") instanceof Number)||(!semantic&&!live&&!source.getString("frameId").equals("main"))
                ||!source.getString("acquisitionMethod").equals(semantic?"rendered_semantic_dom":live?"rendered_live_dom":"rendered_dom")||!source.getString("dataScope").equals("owner_private_profile")||!source.getString("kind").equals(semantic?BrowserSemanticRead.KIND:live?"browser_live_observation":"browser_observation"))throw new SecurityException("浏览来源类型无效");
        if(live){JSONObject facts=source.getJSONObject("liveEvidence");if(facts.length()!=5||facts.toString().length()>4000||!Boolean.TRUE.equals(facts.opt("bodyExcerptOnly"))||!Boolean.FALSE.equals(facts.opt("businessSuccess"))||!(facts.getJSONObject("coverage").get("partial") instanceof Boolean)||!Boolean.FALSE.equals(facts.getJSONObject("stability").opt("entirePageStable"))||!Boolean.FALSE.equals(facts.getJSONObject("stability").opt("networkIdleProven")))throw new SecurityException("live来源不能夸大覆盖或业务结果");BrowserTaskSpace.uuid(source.getString("documentId"));validateFrameCoverage(source.getJSONObject("frameCoverage"),source.getString("frameId"),source.getString("documentId"),facts.getString("scope"),source.getString("privateUrl"));}
        if(semantic&&source.has("readRecipe")){BrowserReadRecipe.validateEvidence(source.getJSONObject("readRecipe"));if(!source.getString("observationId").equals(source.getJSONObject("readRecipe").getString("sourceObservationId")))throw new SecurityException("配方不是这个实际来源观察");}
        if(semantic){BrowserSemanticRead.validateSourceFacts(source.getJSONObject("semantic"));BrowserTaskSpace.uuid(source.getString("documentId"));validateFrameCoverage(source.getJSONObject("frameCoverage"),source.getString("frameId"),source.getString("documentId"),source.getJSONObject("semantic").getString("scope"),source.getString("privateUrl"));if(source.has("structuredProjection")){JSONObject savedProjection=source.getJSONObject("structuredProjection");BrowserStructuredSource.validate(savedProjection);JSONObject facts=source.getJSONObject("semantic");if(!savedProjection.getString("mode").equals(facts.getString("mode"))||savedProjection.getInt("deliveredRows")!=facts.getInt("returnedItems")||!savedProjection.getString("deliveredReadRevision").equals(facts.getJSONObject("delivery").getString("revision")))throw new SecurityException("结构化正文不是该原语义观察的交付范围");if(!source.getString("excerpt").equals(BrowserStructuredSource.excerpt(savedProjection)))throw new SecurityException("结构化来源摘录与实际保存字段不同");}}
        return source;
    }
    /** Source producer and validator agree on the selected native document, not just a top-page URL. */
    static void validateFrameCoverage(JSONObject value,String frameId,String documentId,String factsScope,String sourceUrl)throws Exception {
        boolean current=value.has("schemaVersion");Set<String> fields=new java.util.HashSet<>(Set.of("featureSupported","execution","fallbackReason","knownOriginMatchedFrames","frameTreeComplete","childFrameContentsRead","automaticReload"));
        if(current)fields.addAll(Set.of("schemaVersion","selectedFrameId","selectedDocumentId","selectedSourceOrigin","topDocumentId","parentPageIdentityDigest","selectedDocumentKind","sourceUrlRole"));
        if(value.length()!=fields.size())throw new SecurityException("frame覆盖字段不完整");for(java.util.Iterator<String> keys=value.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new SecurityException("frame覆盖含额外字段");
        if(!(value.get("featureSupported") instanceof Boolean)||!Set.of("isolated_world","page_world_fallback").contains(value.getString("execution"))||!Boolean.FALSE.equals(value.get("frameTreeComplete"))||!(value.get("childFrameContentsRead") instanceof Boolean)||!Boolean.FALSE.equals(value.get("automaticReload")))throw new SecurityException("语义来源不可声称整树或自动重载");
        boolean child=!frameId.equals("main");if(child)BrowserTaskSpace.uuid(frameId);
        if(value.getString("execution").equals("isolated_world")){if(!value.getBoolean("featureSupported")||!value.isNull("fallbackReason"))throw new SecurityException("独立world来源缺少真实能力");}
        else if(child||!Set.of("no_current_document_start_handshake","provider_feature_unsupported").contains(value.getString("fallbackReason")))throw new SecurityException("降级路径不可伪造子frame读取");
        if(!factsScope.equals(child?"selected_frame_rendered_light_and_open_shadow_dom":"main_document_rendered_light_and_open_shadow_dom"))throw new SecurityException("语义范围与实际所选frame不符");
        if(current){
            if(!(value.get("schemaVersion") instanceof Integer)||value.getInt("schemaVersion")!=2||!frameId.equals(value.getString("selectedFrameId"))||!documentId.equals(value.getString("selectedDocumentId"))||value.getBoolean("childFrameContentsRead")!=child||!value.getString("parentPageIdentityDigest").matches("[a-f0-9]{64}"))throw new SecurityException("子frame来源版本/身份不符");
            BrowserTaskSpace.uuid(value.getString("topDocumentId"));String documentKind=value.getString("selectedDocumentKind");
            if(documentKind.equals("http_document")){URI original=new URI(sourceUrl);String actualOrigin=BrowserFrameOrigins.exact(new URI(original.getScheme(),null,original.getHost(),original.getPort(),null,null,null).toASCIIString());if(!value.getString("sourceUrlRole").equals("selected_document_url")||!actualOrigin.equals(value.getString("selectedSourceOrigin")))throw new SecurityException("来源URL与原生frame origin不符");}
            else if(!child||!Set.of("inline_srcdoc","inline_about_blank").contains(documentKind)||!value.getString("sourceUrlRole").equals("inline_document_host_page")||!value.getString("parentPageIdentityDigest").equals(TaskStore.capabilityFingerprint(new JSONObject().put("url",sourceUrl))))throw new SecurityException("内联frame必须明确指向原宿主页，不可冒充独立HTTP资源");
        }else if(child||value.getBoolean("childFrameContentsRead"))throw new SecurityException("历史主frame覆盖不可被提升为子frame证据");
        JSONArray known=value.getJSONArray("knownOriginMatchedFrames");if(known.length()>16)throw new SecurityException("frame来源超限");Set<String> ids=new java.util.HashSet<>();int readCount=0;
        for(int i=0;i<known.length();i++){
            JSONObject f=known.getJSONObject(i);if(f.length()!=(current?5:4)||!(f.get("mainFrame") instanceof Boolean)||!(f.get("read") instanceof Boolean))throw new SecurityException("frame来源身份无效");String id=f.getString("frameId");if(!ids.add(id))throw new SecurityException("frame来源重复");
            if(f.getBoolean("mainFrame")){if(!id.equals("main"))throw new SecurityException("主frame身份无效");}else BrowserTaskSpace.uuid(id);
            String nativeOrigin=BrowserFrameOrigins.exact(f.getString("sourceOrigin"));if(current&&!nativeOrigin.equals(f.getString("sourceOrigin")))throw new SecurityException("frame原生origin不是规范值");
            if(current)BrowserTaskSpace.uuid(f.getString("documentId"));
            if(f.getBoolean("read")){readCount++;if(!id.equals(frameId)||current&&(!documentId.equals(f.getString("documentId"))||!value.getString("selectedSourceOrigin").equals(nativeOrigin))||!current&&!f.getBoolean("mainFrame"))throw new SecurityException("实际读取frame与保存目标不符");}
        }
        if(current&&(value.getString("execution").equals("isolated_world")?readCount!=1:readCount!=0))throw new SecurityException("实际frame读取覆盖不能猜测");
    }
    private static void validateHttpFacts(JSONObject facts,JSONObject source)throws Exception {
        Set<String> fields=Set.of("requestedUrl","finalUrl","statusCode","retrievedAt","redirects","bodyBytesRead","bodyByteLimit","bodyComplete","profileCredentialsUsed","systemProxyCredentialsUsed","credentialHeadersSent","method","rendered","connectAddressValidated","mime","charset","bodySha256","bodyTruncated","byteCounting","projection","projectionTruncated","projectedChars");
        if(facts.length()!=fields.size())throw new SecurityException("HTTP来源字段不完整");for(java.util.Iterator<String> keys=facts.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new SecurityException("HTTP来源含未知字段");
        PublicHttpReader.url(facts.getString("requestedUrl"));PublicHttpReader.url(facts.getString("finalUrl"));
        for(String field:new String[]{"statusCode","retrievedAt","bodyBytesRead","bodyByteLimit","projectedChars"}){Object number=facts.get(field);if(!(number instanceof Number)||!Double.isFinite(((Number)number).doubleValue())||((Number)number).doubleValue()!=Math.rint(((Number)number).doubleValue()))throw new SecurityException("HTTP来源数字类型无效");}
        if(!facts.getString("finalUrl").equals(source.getString("privateUrl"))||facts.getLong("retrievedAt")!=source.getLong("observedAt")
                ||facts.getInt("statusCode")<200||facts.getInt("statusCode")>=300||facts.getInt("bodyBytesRead")<=0||facts.getInt("bodyBytesRead")>facts.getInt("bodyByteLimit")
                ||facts.getInt("bodyByteLimit")<4096||facts.getInt("bodyByteLimit")>PublicHttpReader.MAX_BYTES||facts.getJSONArray("redirects").length()>3
                ||!facts.getString("method").equals("GET")||!facts.getString("bodySha256").matches("[a-f0-9]{64}")||facts.getInt("projectedChars")!=source.getString("excerpt").length()
                ||!PublicHttpReader.supportedMime(facts.getString("mime"))||!java.nio.charset.Charset.isSupported(facts.getString("charset"))
                ||!facts.getString("byteCounting").equals("uncompressed_identity_response_body")||!facts.getString("projection").equals(PublicHttpReader.htmlMime(facts.getString("mime"))?"server_dom_text_not_rendered":"unrendered_text_body"))throw new SecurityException("HTTP来源范围不成立");
        for(String key:new String[]{"profileCredentialsUsed","systemProxyCredentialsUsed","credentialHeadersSent","rendered"})if(!Boolean.FALSE.equals(facts.get(key)))throw new SecurityException("HTTP来源不得带浏览器凭据");
        if(!Boolean.TRUE.equals(facts.get("connectAddressValidated"))||!(facts.get("bodyComplete") instanceof Boolean)||!(facts.get("bodyTruncated") instanceof Boolean)
                ||facts.getBoolean("bodyComplete")==facts.getBoolean("bodyTruncated")||!(facts.get("projectionTruncated") instanceof Boolean))throw new SecurityException("HTTP来源覆盖状态无效");
    }
    private static JSONObject find(JSONObject task,ModelCapabilityAdapters.Binding binding,String id)throws Exception {
        JSONObject found=null;JSONArray events=task.getJSONArray("events");for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(!TYPE.equals(event.optString("type"))||!id.equals(event.optString("sourceId")))continue;
            if(!binding.taskId.equals(event.optString("taskId"))||!binding.runId.equals(event.optString("runId"))||binding.epoch!=event.optInt("epoch"))throw new SecurityException("同一来源ID已绑定另一个原执行");
            if(found!=null&&!found.getString("sourceFingerprint").equals(event.getString("sourceFingerprint")))throw new SecurityException("同一来源存在冲突版本");found=event;
        }return found;
    }
    private static void requireSameSource(JSONObject event,JSONObject source)throws Exception {if(!event.getString("sourceFingerprint").equals(TaskStore.capabilityFingerprint(source)))throw new SecurityException("来源ID不能覆盖原内容");}
    private static String sourceRef(String task,String id){return "task:"+task+":browser-source:"+id;}
    private static JSONObject receipt(JSONObject event)throws Exception {return BrowserStructuredSource.addReferences(new JSONObject().put("taskId",event.getString("taskId")).put("runId",event.getString("runId")).put("epoch",event.getInt("epoch")).put("sourceId",event.getString("sourceId")).put("status","retained").put("exactUrlRetainedPrivately",event.getJSONObject("source").has("privateUrl")).put("sourceRef",sourceRef(event.getString("taskId"),event.getString("sourceId"))).put("retainedAt",event.getLong("at")),event.getJSONObject("source"));}
}
