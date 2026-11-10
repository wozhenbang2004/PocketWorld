package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, deterministic rendered-DOM reading for the original model loop. Never an action executor. */
public final class BrowserSemanticRead {
    public static final String KIND="browser_semantic_observation";
    public static final int OUTPUT_BYTES=32768;
    private BrowserSemanticRead() { }
    public static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("mode"))
                .put("properties",new JSONObject().put("mode",new JSONObject().put("type","string").put("enum",new JSONArray().put("outline").put("text").put("table").put("list")))
                        .put("query",string(160)).put("settle",BrowserReadSettle.schema()).put("frameRef",string(36)).put("regionRef",string(100)).put("cursor",string(100)).put("baseRevision",string(64)));
    }
    private static JSONObject string(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    public static void validate(JSONObject read)throws Exception {
        if(read==null)throw new IllegalArgumentException("需要明确的网页只读请求");
        Set<String> keys=Set.of("mode","query","frameRef","regionRef","cursor","baseRevision","settle");for(java.util.Iterator<String> it=read.keys();it.hasNext();)if(!keys.contains(it.next()))throw new IllegalArgumentException("网页只读请求含额外字段");
        if(!Set.of("outline","text","table","list").contains(read.getString("mode")))throw new IllegalArgumentException("不支持的语义读取模式");
        for(String key:new String[]{"query","regionRef","cursor"})if(read.has(key)){Object value=read.get(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>(key.equals("query")?160:100))throw new IllegalArgumentException("网页只读选择为空或过长");}
        if(read.has("regionRef")&&(read.has("query")||read.getString("mode").equals("outline")))throw new IllegalArgumentException("outline先用query建立区域，regionRef只在text/table/list选择且不叠加query");
        if(read.has("cursor")&&(read.has("query")||read.has("regionRef")))throw new IllegalArgumentException("续读只接受原cursor和相同mode，不重新选区域");
        if(read.has("baseRevision")&&(!read.has("regionRef")||read.has("cursor")||!read.getString("baseRevision").matches("[a-f0-9]{64}")))throw new IllegalArgumentException("区域刷新需已展示的原区域与64位原read revision，不接受未知基线或续读混用");
        BrowserReadSettle.validate(read);
        if(read.has("frameRef"))BrowserTaskSpace.uuid(read.getString("frameRef"));
        for(String key:new String[]{"regionRef","cursor"})if(read.has(key)&&!read.getString(key).matches("[0-9a-f-]{36}:[rc][0-9]{1,6}"))throw new IllegalArgumentException("只接受本页原生只读区域/续读引用");
    }
    /** Script is independent product code. The key/epoch/nonce are supplied by the native page, never by page/model authority. */
    public static String script(JSONObject read,String key,String epoch,String nonce)throws Exception {
        return script(read,key,epoch,nonce,nonce);
    }
    public static String script(JSONObject read,String key,String epoch,String nonce,String regionNamespace)throws Exception {return script(read,key,epoch,nonce,regionNamespace,false);}
    public static String script(JSONObject read,String key,String epoch,String nonce,String regionNamespace,boolean childFrame)throws Exception {
        validate(read);BrowserTaskSpace.uuid(nonce);BrowserTaskSpace.uuid(regionNamespace);
        return "JSON.stringify((()=>{const request="+read.toString()+",nativeKey="+JSONObject.quote(key)+",nativeEpoch="+JSONObject.quote(epoch)+",nonce="+JSONObject.quote(nonce)+",regionNamespace="+JSONObject.quote(regionNamespace)+",frameScope="+JSONObject.quote(childFrame?"selected_frame_rendered_light_and_open_shadow_dom":"main_document_rendered_light_and_open_shadow_dom")+";\n"+SCRIPT+"\n})())";
    }
    /** Keep semantic reads separate from actionable nN references. No clipping structured cells into invented valid data. */
    public static JSONObject result(JSONObject raw)throws Exception {
        if(!raw.optBoolean("semanticRead"))throw new IllegalStateException("网页只读回执缺少正式类型");
        if(raw.optString("status").equals("unavailable"))return new JSONObject(raw.toString()).put("accepted",true).put("verified",false).put("untrustedPageContent",true);
        JSONObject read=raw.getJSONObject("read");if(read.has("dynamic"))BrowserReadSettle.validateDynamic(read.getJSONObject("dynamic"));if(read.has("settle"))BrowserReadSettle.validateEvidence(read.getJSONObject("settle"));validateFacts(read.getJSONObject("facts"));if(!read.getString("mode").equals(read.getJSONObject("facts").getString("mode"))||read.getJSONArray("data").length()!=read.getJSONObject("facts").getInt("returnedItems"))throw new IllegalStateException("语义数据与覆盖计数不一致");
        if(!(raw.opt("text") instanceof String)||raw.getString("text").length()>6000||raw.getJSONArray("elements").length()!=0)throw new IllegalStateException("语义读取不可伪造动作refs或超出正文预算");
        String digest=hex(MessageDigest.getInstance("SHA-256").digest(new JSONObject().put("text",raw.getString("text")).put("data",read.get("data")).toString().getBytes(StandardCharsets.UTF_8)));
        String revision=hex(MessageDigest.getInstance("SHA-256").digest(new JSONObject().put("text",raw.getString("text")).put("data",read.get("data")).put("facts",read.get("facts")).toString().getBytes(StandardCharsets.UTF_8)));
        Object baseline=read.opt("baseRevision");if(baseline!=null&&baseline!=JSONObject.NULL&&(!(baseline instanceof String)||!((String)baseline).matches("[a-f0-9]{64}")))throw new SecurityException("网页读取基线无效");
        read.put("delivery",new JSONObject().put("format","full").put("revision",revision).put("baseRevision",baseline==null?JSONObject.NULL:baseline).put("comparison","not_attempted_full_projection").put("deletionsInferred",false));
        read.put("projectionSha256",digest);raw.put("readOnlyEvidence",true).put("accepted",true).put("verified",false).put("untrustedPageContent",true)
                .put("message",read.getJSONObject("facts").getBoolean("partial")?"已读取预算内的指定网页内容；未覆盖部分与续读条件见coverage，不代表整页或业务完成。":"已读取指定DOM范围；这是只读语义证据，区域/续读id不能用于点击，仍不声称整页或业务完成。");
        if(raw.toString().getBytes(StandardCharsets.UTF_8).length>OUTPUT_BYTES)return new JSONObject().put("semanticRead",true).put("status","unavailable").put("code","semantic_output_budget").put("url",raw.optString("url")).put("title",raw.optString("title")).put("accepted",true).put("verified",false).put("readOnlyEvidence",true).put("untrustedPageContent",true).put("automaticReplayAllowed",false);return raw;
    }
    public static JSONObject sourceFacts(JSONObject read)throws Exception {
        JSONObject facts=new JSONObject(read.getJSONObject("facts").toString());validateFacts(facts);String digest=read.getString("projectionSha256");if(!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("语义读取缺少投影摘要");facts.put("projectionSha256",digest);for(String key:new String[]{"dynamic","settle"})if(read.has(key)){if(key.equals("dynamic"))BrowserReadSettle.validateDynamic(read.getJSONObject(key));else BrowserReadSettle.validateEvidence(read.getJSONObject(key));facts.put(key,new JSONObject(read.getJSONObject(key).toString()));}if(read.has("delivery")){validateDelivery(read.getJSONObject("delivery"));facts.put("delivery",new JSONObject(read.getJSONObject("delivery").toString()));}return facts;
    }
    public static void validateSourceFacts(JSONObject facts)throws Exception {
        JSONObject copy=new JSONObject(facts.toString());String digest=copy.getString("projectionSha256");copy.remove("projectionSha256");for(String key:new String[]{"dynamic","settle"})if(copy.has(key)){if(key.equals("dynamic"))BrowserReadSettle.validateDynamic(copy.getJSONObject(key));else BrowserReadSettle.validateEvidence(copy.getJSONObject(key));copy.remove(key);}if(copy.has("delivery")){validateDelivery(copy.getJSONObject("delivery"));copy.remove("delivery");}if(!digest.matches("[a-f0-9]{64}"))throw new SecurityException("语义来源摘要无效");validateFacts(copy);
    }
    private static void validateDelivery(JSONObject value)throws Exception {BrowserReadDelta.validateDelivery(value);}
    private static void validateFacts(JSONObject facts)throws Exception {
        Set<String> fields=Set.of("mode","regionRef","projectionVersion","from","to","totalCaptured","returnedItems","omittedItems","partial","reasons","wholePageComplete","scope","textCharsScanned","nodesScanned","childFramesOmitted","openShadowRootsRead","closedShadowCoverage","nameComputation","textProjection");
        if(facts.has("captureWork")){
            JSONObject work=facts.getJSONObject("captureWork");Set<String> expected=Set.of("nodesVisited","textCharsVisited","layoutChecks","queuedPeak","nodeLimit","textCharLimit","queueLimit","budgetMs");if(work.length()!=expected.size())throw new SecurityException("采集工作预算字段不完整");for(String k:expected){Object value=work.get(k);if(!(value instanceof Number)||((Number)value).doubleValue()!=work.getInt(k)||work.getInt(k)<0)throw new SecurityException("采集预算计数无效");}
            if(work.getInt("nodesVisited")>6000||work.getInt("textCharsVisited")>180000||work.getInt("queuedPeak")>6000||work.getInt("layoutChecks")>6000||work.getInt("nodeLimit")!=6000||work.getInt("textCharLimit")!=180000||work.getInt("queueLimit")!=6000||work.getInt("budgetMs")!=150)throw new SecurityException("采集工作预算越界");
            fields=new java.util.HashSet<>(fields);fields.add("captureWork");
        }
        if(facts.length()!=fields.size())throw new SecurityException("语义读取覆盖字段不完整");for(java.util.Iterator<String> it=facts.keys();it.hasNext();)if(!fields.contains(it.next()))throw new SecurityException("语义读取覆盖含额外字段");
        if(!Set.of("outline","text","table","list").contains(facts.getString("mode"))||facts.getString("regionRef").length()>100||!facts.getString("projectionVersion").matches("[a-f0-9]{8,16}")
                ||!Boolean.FALSE.equals(facts.get("wholePageComplete"))||!(facts.get("partial") instanceof Boolean)||!Set.of("main_document_rendered_light_and_open_shadow_dom","selected_frame_rendered_light_and_open_shadow_dom").contains(facts.getString("scope"))
                ||!facts.getString("closedShadowCoverage").equals("not_asserted")||!facts.getString("nameComputation").equals("bounded_dom_labels_not_browser_accessibility_tree")||!facts.getString("textProjection").equals("whitespace_normalized_dom_text"))throw new SecurityException("语义读取范围不成立");
        for(String key:new String[]{"from","to","totalCaptured","returnedItems","omittedItems","textCharsScanned","nodesScanned","childFramesOmitted","openShadowRootsRead"}){Object n=facts.get(key);if(!(n instanceof Number)||((Number)n).doubleValue()!=facts.getInt(key)||facts.getInt(key)<0||facts.getInt(key)>180000)throw new SecurityException("语义读取计数无效");}
        if(facts.getInt("from")>facts.getInt("to")||facts.getInt("to")>facts.getInt("totalCaptured")||facts.getInt("returnedItems")+facts.getInt("omittedItems")!=facts.getInt("to")-facts.getInt("from")||facts.getInt("nodesScanned")>6000||facts.getInt("textCharsScanned")>180000)throw new SecurityException("语义读取范围/预算无效");
        JSONArray reasons=facts.getJSONArray("reasons");if(reasons.length()>16)throw new SecurityException("语义读取缺失原因超限");for(int i=0;i<reasons.length();i++)if(!reasons.getString(i).matches("[a-z_]{1,60}"))throw new SecurityException("语义读取缺失原因无效");
        if(!facts.getBoolean("partial")&&reasons.length()>0)throw new SecurityException("有缺失的语义读取不可标完整");
    }
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}

    private static final String SCRIPT="const visibilityIncomplete=()=>{layoutLimited=true};\n"+BrowserVisibleSubtree.SCRIPT+"""
const now=Date.now(),deadline=now+150,MAX_NODES=6000,MAX_CHARS=180000,MAX_QUEUE=6000;
const work={nodesVisited:0,textCharsVisited:0,layoutChecks:0,queuedPeak:0,nodeLimit:MAX_NODES,textCharLimit:MAX_CHARS,queueLimit:MAX_QUEUE,budgetMs:150};
const fail=(code,coverage)=>({semanticRead:true,status:'unavailable',code,...(coverage?{coverage}:{}),url:location.href,title:document.title||'',readOnlyEvidence:true,automaticReplayAllowed:false});
const compact=s=>String(s||'').replace(/\\s+/g,' ').trim();
const clip=(s,n)=>{s=String(s||'');return s.length>n?s.slice(0,n).replace(/[\\uD800-\\uDBFF]$/,''):s};
const high=c=>c>=0xD800&&c<=0xDBFF,low=c=>c>=0xDC00&&c<=0xDFFF;
const contextFrom=(s,start)=>s.slice(start>0&&low(s.charCodeAt(start))&&high(s.charCodeAt(start-1))?start-1:start);
const hashAdd=(h,s)=>{s+='\\u001e';for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)}return h};const hashHex=h=>(h>>>0).toString(16).padStart(8,'0');const bytes=s=>{let n=0;for(let i=0;i<s.length;i++){const c=s.charCodeAt(i);if(c<128)n++;else if(c<2048)n+=2;else if(c>=0xD800&&c<=0xDBFF&&i+1<s.length&&s.charCodeAt(i+1)>=0xDC00&&s.charCodeAt(i+1)<=0xDFFF){n+=4;i++}else n+=3}return n};
const role=e=>{const r=compact(e.getAttribute('role')).split(' ')[0];if(r)return r;return ({BODY:'document',MAIN:'main',ARTICLE:'article',SECTION:'region',NAV:'navigation',HEADER:'banner',FOOTER:'contentinfo',FORM:'form',TABLE:'table',UL:'list',OL:'list',DL:'list',LI:'listitem',DT:'term',DD:'definition',TR:'row',TH:'columnheader',TD:'cell',A:'link',BUTTON:'button',INPUT:e.type==='checkbox'?'checkbox':'textbox',SELECT:'combobox',TEXTAREA:'textbox',H1:'heading',H2:'heading',H3:'heading',H4:'heading',H5:'heading',H6:'heading'})[e.tagName]||''};
const available=()=>work.nodesVisited<MAX_NODES&&work.textCharsVisited<MAX_CHARS&&Date.now()<=deadline;
const budgetReason=()=>work.nodesVisited>=MAX_NODES?'node_budget':work.textCharsVisited>=MAX_CHARS?'text_scan_budget':'capture_time_budget';
const labelText=el=>{let out='',count=0,stack=[el];while(stack.length&&count++<32&&out.length<512&&available()){const n=stack.pop();work.nodesVisited++;if(n.nodeType===3){const part=clip(n.nodeValue||'',Math.min(512-out.length,MAX_CHARS-work.textCharsVisited));out+=part;work.textCharsVisited+=part.length}else if(n.nodeType===1){for(let i=Math.min(n.childNodes.length,Math.max(0,32-stack.length))-1;i>=0;i--)stack.push(n.childNodes[i])}}return clip(compact(out),512)};
const name=e=>{let s='';const ids=compact(e.getAttribute('aria-labelledby')).split(' ').filter(Boolean).slice(0,8),root=e.getRootNode();for(const id of ids){const n=root.getElementById?root.getElementById(id):null;if(n)s+=' '+clip(labelText(n),160)}if(!compact(s))s=e.getAttribute('aria-label')||e.getAttribute('alt')||e.getAttribute('title')||'';if(!compact(s)&&e.tagName==='TABLE'){const c=e.caption;if(c)s=labelText(c)}if(!compact(s)&&/^H[1-6]$/.test(e.tagName))s=labelText(e);return clip(compact(s),160)};
const stateOf=e=>({checked:e.getAttribute('aria-checked')||(e.type==='checkbox'||e.type==='radio'?String(!!e.checked):''),selected:e.getAttribute('aria-selected')||'',expanded:e.getAttribute('aria-expanded')||'',disabled:e.getAttribute('aria-disabled')||(e.disabled?'true':''),required:e.getAttribute('aria-required')||(e.required?'true':'')});
const release=s=>{if(!s||!s.track)return;const t=s.track;if(t.observer)t.observer.disconnect();for(const root of t.roots)if(root.removeEventListener)root.removeEventListener('slotchange',t.changed,true);if(t.timer&&typeof clearTimeout==='function')clearTimeout(t.timer);t.active=false};
if(!document.body&&!document.documentElement)return fail('document_unavailable');
let state=window[nativeKey];if(!state||state.epoch!==nativeEpoch||state.url!==location.href||state.namespace!==regionNamespace||state.document!==document){if(state)release(state.cursor&&state.cursor.session);state={epoch:nativeEpoch,url:location.href,document,namespace:regionNamespace,nextRegion:0,nodeRefs:new WeakMap(),regions:new Map(),cursor:null};window[nativeKey]=state}
let layoutLimited=false;const hasRect=e=>{if(work.layoutChecks>=MAX_NODES){layoutLimited=true;return false}work.layoutChecks++;return e.getClientRects().length>0};
const regionsRole=new Set(['document','main','article','region','navigation','banner','contentinfo','form','table','grid','list','heading']);
const fresh=el=>!!el&&el.isConnected&&el.ownerDocument===document;
const refFor=e=>{let ref=state.nodeRefs.get(e);if(!ref&&state.nextRegion<999999){ref=state.namespace+':r'+(++state.nextRegion);state.nodeRefs.set(e,ref)}return ref};
function tracking(root){const t={root,chain:[],version:0,active:false,complete:true,roots:[],observer:null,timer:null};t.changed=()=>{t.version++;};if(typeof MutationObserver==='function')try{
 t.observer=new MutationObserver(t.changed);let p=root,depth=0;
 for(;p&&depth++<128;p=composedParent(p)){const tree=p.getRootNode?p.getRootNode():null;t.chain.push({el:p,tree,shadow:p.shadowRoot});const subtree=p===root||!!p.shadowRoot;t.observer.observe(p,{attributes:true,childList:true,subtree,characterData:subtree});if(tree&&tree.host)watch(t,tree)}
 if(p)t.complete=false;if(document.head&&root!==document.head)t.observer.observe(document.head,{subtree:true,childList:true,characterData:true,attributes:true});t.active=true;
 }catch(_){t.complete=false}return t}
function watch(t,root){if(!t||!root||t.roots.includes(root))return;if(t.roots.length>=128){t.complete=false;return}try{t.observer.observe(root,{subtree:true,childList:true,characterData:true,attributes:true});if(root.addEventListener)root.addEventListener('slotchange',t.changed,true);t.roots.push(root)}catch(_){t.complete=false}}
const sameChain=t=>{let p=t.root;for(const old of t.chain){if(p!==old.el||(p.getRootNode?p.getRootNode():null)!==old.tree||p.shadowRoot!==old.shadow)return false;p=composedParent(p)}return !p};
const flush=t=>{if(t.observer&&t.observer.takeRecords().length||t.active&&!sameChain(t))t.changed();return t.version};
const walker=root=>({stack:[{node:root,anc:[],entered:false,index:0,offset:0}],hash:2166136261,regionOrder:0,rootRegion:null});
// A parent/index frame retains every unvisited child without copying or truncating a wide NodeList.
// The retained path is at most 129 frames; each captured batch is still <=6000 nodes /180k chars.
function scan(root,makeRegions,walk=walker(root),options={}){
 const out={nodes:0,chars:0,text:[],elements:[],regions:[],frames:0,shadows:0,reasons:new Set(),walk,clipped:false},vis=new WeakMap(),query=options.query||'';
 const shown=e=>{if(vis.has(e))return vis.get(e);const yes=visibleSubtree(e)&&(getComputedStyle(e).display==='contents'||hasRect(e));vis.set(e,yes);return yes};
 const include=r=>{if(!out.regions.includes(r))out.regions.push(r)};
 while(walk.stack.length){
  if(!available()){out.reasons.add(budgetReason());break}if(out.regions.length>=256){out.reasons.add('region_batch_budget');break}
  const f=walk.stack[walk.stack.length-1],node=f.node;
  if(!f.entered){f.entered=true;out.nodes++;work.nodesVisited++;if(walk.stack.length>129){out.reasons.add('depth_budget');walk.stack.pop();continue}}
  if(node.nodeType===3){const p=node.parentElement;if(!p||!shown(p)){walk.stack.pop();continue}const raw=String(node.nodeValue||''),cap=options.captureLimit===undefined?MAX_CHARS:Math.max(0,options.captureLimit-out.chars),remaining=Math.min(MAX_CHARS-work.textCharsVisited,cap);if(remaining<=0){if(cap<=0)out.clipped=true;else out.reasons.add('text_scan_budget');break}
   let end=Math.min(raw.length,f.offset+remaining);if(end<raw.length&&high(raw.charCodeAt(end-1))&&low(raw.charCodeAt(end)))end--;const part=raw.slice(f.offset,end);f.offset+=part.length;work.textCharsVisited+=part.length;const text=compact(part);out.chars+=text.length;if(text){const projected=(walk.textStarted?(walk.lastText===node?(walk.trailingSpace||/^\\s/.test(part)?' ':''):'\\n'):'')+text;walk.textStarted=true;walk.lastText=node;walk.trailingSpace=/\\s$/.test(part);if(options.captureText!==false)out.text.push(projected);walk.hash=hashAdd(walk.hash,text);for(const r of f.anc){r.hash=hashAdd(r.hash,text);if(!r.preview)r.preview=clip(text,140);if(query){const joined=(r.tail||'')+projected,at=joined.toLocaleLowerCase().indexOf(query.toLocaleLowerCase());if(at>=0){r.matched=true;r.preview=clip(contextFrom(joined,Math.max(0,at-60)),220);if(!r.queryEmitted){include(r);r.queryEmitted=true}}r.tail=joined.slice(-160)}}}else if(part.length&&walk.lastText===node&&/\\s$/.test(part))walk.trailingSpace=true;
   if(f.offset>=raw.length)walk.stack.pop();else if(options.captureLimit!==undefined&&out.chars>=options.captureLimit){out.clipped=true;break}else if(!part.length){out.reasons.add('text_scan_budget');break}continue;
  }
  if(node.nodeType!==1){walk.stack.pop();continue}const e=node;
  if(!f.ready){f.ready=true;if(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE'].includes(e.tagName)||e.type==='password'||!visibleSubtree(e)){walk.stack.pop();continue}
   out.elements.push(e);const r=role(e),n=name(e),href=e.tagName==='A'?clip(e.href,2048):'',identity=e.tagName+'|'+r+'|'+n+'|'+href+'|'+JSON.stringify(stateOf(e));walk.hash=hashAdd(walk.hash,identity);for(const a of f.anc)a.hash=hashAdd(a.hash,identity);
   if(e.tagName==='IFRAME'||e.tagName==='FRAME'){out.frames++;out.reasons.add('child_frames_not_read');walk.stack.pop();continue}if(e.tagName==='CANVAS')out.reasons.add('canvas_pixels_not_read');f.nextAnc=f.anc;
   if(makeRegions&&(e===root||regionsRole.has(r))){const ref=refFor(e);if(ref){const item={el:e,ref,role:r||'region',name:n,preview:'',matched:!!query&&n.toLocaleLowerCase().includes(query.toLocaleLowerCase()),hash:hashAdd(2166136261,identity),order:walk.regionOrder++,tail:''};f.nextAnc=f.anc.concat(item);if(e===root)walk.rootRegion=item;if(!query||item.matched){include(item);if(query)item.queryEmitted=true}}else out.reasons.add('region_identity_budget')}
   if(e.shadowRoot){out.shadows++;watch(options.track,e.shadowRoot)}
  }
  const nodes=composedChildren(e);if(walk.stack.length>=129&&nodes.length){out.reasons.add('depth_budget');walk.stack.pop();continue}if(f.index>=nodes.length){walk.stack.pop();continue}const child=nodes[f.index++];walk.stack.push({node:child,anc:f.nextAnc,entered:false,index:0,offset:0});work.queuedPeak=Math.max(work.queuedPeak,walk.stack.length);
 }
 out.version=hashHex(walk.hash);for(const r of out.regions)r.version=hashHex(r.hash);out.rootRegion=walk.rootRegion;return out;
}
let kind=request.mode,session;
if(request.cursor){const c=state.cursor;if(!c||c.id!==request.cursor||now-c.session.created>120000||now<c.session.created||c.session.mode!==kind)return fail('read_cursor_expired_or_unknown');session=c.session;if(!fresh(session.target)||!visibleSubtree(session.target)||!session.track.active||!session.track.complete||flush(session.track)!==session.mutationVersion){release(session);state.cursor=null;return fail('read_region_changed')}}
else{
 release(state.cursor&&state.cursor.session);state.cursor=null;
 const root=document.body||document.documentElement,old=request.regionRef?state.regions.get(request.regionRef):null;
 if(request.regionRef&&(!old||now-old.created>120000||!fresh(old.el)))return fail('read_region_expired_or_unknown');
 const selected=old?old.el:root,discover=!old&&kind!=='outline'&&!(kind==='text'&&!request.query);
 session={mode:kind,query:request.query||'',created:now,target:selected,regionRef:old?request.regionRef:kind==='text'?refFor(root)||'':'',stage:discover?'discover':'capture',walk:walker(selected),track:tracking(selected),mutationVersion:0,version:'',items:[],next:0,regionRecords:[],rows:[],rowAt:0,row:null,rowIndex:0,matches:[],queryMatches:0,reasons:new Set(),expected:old&&!request.settle&&!request.baseRevision&&!old.partial?old.version:null};
}
const captured={nodes:0,chars:0,frames:0,shadows:0,reasons:new Set()},temporary=new Set();
const notFound=()=>fail(session.reasons.size?'read_region_search_incomplete':'read_region_not_found',{searchComplete:session.reasons.size===0,scope:frameScope,reasons:[...session.reasons]});
const resumable=new Set(['node_budget','text_scan_budget','capture_time_budget','region_batch_budget']);
function account(s){captured.nodes+=s.nodes;captured.chars+=s.chars;captured.frames+=s.frames;captured.shadows+=s.shadows;for(const reason of s.reasons){captured.reasons.add(reason);if(resumable.has(reason))temporary.add(reason);else session.reasons.add(reason)}if(!session.version)session.version=s.version}
const expected=kind==='table'?new Set(['table','grid']):new Set(['list']);
const ownedRow=row=>{let owner=composedParent(row),depth=0;while(owner&&depth++<128){if(owner===session.target)return true;if(expected.has(role(owner)))return false;owner=composedParent(owner)}if(owner)session.reasons.add('depth_budget');return false};
function partFor(el,max){return {el,max,walk:walker(el),text:'',links:[],partial:false,done:false}}
function readPart(part){const s=scan(part.el,false,part.walk,{track:session.track,captureLimit:part.max+1-part.text.length});account(s);part.text+=s.text.join('');for(const reason of s.reasons)if(!resumable.has(reason))part.partial=true;
 for(const a of s.elements){if(a.tagName!=='A'||!a.hasAttribute('href')||!hasRect(a))continue;try{const u=new URL(a.href,location.href);if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.href.length>2048||part.links.length>=8){part.partial=true;continue}part.links.push({text:clip(labelText(a),160),href:u.href})}catch(_){part.partial=true}}
 if(s.clipped||part.text.length>part.max||part.text.length>=part.max&&part.walk.stack.length){part.partial=true;part.done=true}else part.done=!part.walk.stack.length;if(part.done){part.text=clip(compact(part.text),part.max);if(part.partial)session.reasons.add('cell_text_budget')}return part.done;
}
function structured(){let cells=0;while(session.rowAt<session.rows.length&&session.items.length<24&&available()){
 let row=session.row;if(!row){const el=session.rows[session.rowAt];if(!fresh(el)||!ownedRow(el)||!hasRect(el)){session.rowAt++;continue}row={el,index:session.rowIndex++,cells:[],nextChild:0,part:null};session.row=row}
 if(kind==='list'){if(!row.part)row.part=partFor(row.el,2000);if(!readPart(row.part))break;session.items.push({index:row.index,kind:role(row.el),text:row.part.text,links:row.part.links,partial:row.part.partial});session.row=null;session.rowAt++;continue}
 let done=false;
 while(available()){
  if(!row.part){if(row.nextChild>=row.el.children.length){done=true;break}if(row.cells.length>=24){session.reasons.add('column_budget');done=true;break}if(cells>=1024){temporary.add('structured_capture_budget');break}const cell=row.el.children[row.nextChild++];if(!['TD','TH'].includes(cell.tagName)&&!['cell','gridcell','columnheader','rowheader'].includes(role(cell)))continue;row.cell=cell;row.part=partFor(cell,1500)}
  if(!readPart(row.part))break;const cell=row.cell,colSpan=Number(cell.getAttribute('colspan')||cell.getAttribute('aria-colspan')||1),rowSpan=Number(cell.getAttribute('rowspan')||cell.getAttribute('aria-rowspan')||1);row.cells.push({column:row.cells.length,role:role(cell),text:row.part.text,links:row.part.links,colSpan:Number.isInteger(colSpan)&&colSpan>0?Math.min(colSpan,1000):1,rowSpan:Number.isInteger(rowSpan)&&rowSpan>0?Math.min(rowSpan,1000):1,partial:row.part.partial});row.part=null;cells++;
 }
 if(!done)break;session.items.push({index:row.index,cells:row.cells});session.row=null;session.rowAt++;
 }}
if(session.next>=session.items.length){session.items=[];session.next=0;session.regionRecords=[];
 if(session.stage==='discover'){
  const s=scan(document.body||document.documentElement,true,session.walk,{query:session.query,track:session.track,captureText:false});account(s);
  for(const r of s.regions){if((kind==='text'?r.matched:expected.has(r.role)&&(!session.query||r.matched))&&!session.matches.some(x=>x.el===r.el)&&session.matches.length<2)session.matches.push(r)}
  if(!session.walk.stack.length){if(session.matches.length!==1){release(session);state.cursor=null;return session.matches.length?fail('read_region_ambiguous'):notFound()}const picked=session.matches[0];release(session);session.target=picked.el;session.regionRef=picked.ref;session.walk=walker(picked.el);session.track=tracking(picked.el);session.mutationVersion=0;session.stage='capture';session.version='';}
 }
 if(session.stage==='capture'){
  if(kind==='table'||kind==='list'){
   if(!expected.has(role(session.target))){release(session);state.cursor=null;return fail('read_region_kind_mismatch')}
   if(session.rowAt<session.rows.length)structured();
   if(!session.items.length&&session.rowAt>=session.rows.length&&session.walk.stack.length&&available()){const s=scan(session.target,false,session.walk,{track:session.track,captureText:false});account(s);session.rows=s.elements.filter(e=>kind==='table'?role(e)==='row':['listitem','term','definition'].includes(role(e)));session.rowAt=0;if(available())structured()}
   if(!session.walk.stack.length&&session.rowAt>=session.rows.length&&kind==='table'&&Number(session.target.getAttribute('aria-rowcount')||0)>session.rowIndex)session.reasons.add('virtual_rows_not_in_dom');
  }else if(session.walk.stack.length&&available()){
   const s=scan(session.target,kind==='outline',session.walk,{query:session.query,track:session.track});account(s);
   if(kind==='outline'){session.regionRecords=s.regions;session.items=s.regions.map(r=>({regionRef:r.ref,role:r.role,name:r.name,preview:r.preview,documentOrder:r.order,state:stateOf(r.el),readOnlyEvidence:true}));session.queryMatches+=session.items.length}
   else{const corpus=s.text.join('');for(let p=0;p<corpus.length;){let end=Math.min(p+2000,corpus.length);if(end<corpus.length&&high(corpus.charCodeAt(end-1))&&low(corpus.charCodeAt(end)))end--;session.items.push(corpus.slice(p,end));p=end}}
  }
 }
}
if(session.expected&&!session.walk.stack.length&&hashHex(session.walk.hash)!==session.expected){release(session);state.cursor=null;return fail('read_region_changed')}
if(kind==='outline'&&session.query&&!session.walk.stack.length&&!session.queryMatches){release(session);state.cursor=null;return notFound()}
let target=session.target,items=session.items,from=session.next,to=from,regionRef=session.regionRef,targetVersion=session.version||hashHex(session.walk.hash),baseReasons=new Set(session.reasons),presentableRegions=session.regionRecords;
if(request.cursor)baseReasons.add('continued_region_slice');for(const reason of temporary)baseReasons.add(reason);if(layoutLimited)baseReasons.add('layout_budget');
const data=[],limit=kind==='outline'?32:kind==='text'?200:24;let text='',used=0;
for(let i=from;i<items.length&&data.length<limit;i++){
 const item=items[i];if(kind==='text'){const str=String(item);if(text.length+str.length>6000||bytes(text+str)>11000)break;data.push(str);text+=str;to=i+1}
 else{const encoded=JSON.stringify(item);if(used+bytes(encoded)>8000){if(!data.length){baseReasons.add('structured_row_output_budget');to=i+1}break}data.push(item);used+=bytes(encoded);to=i+1;const plain=kind==='outline'?[item.name,item.preview].filter(Boolean).join(': '):kind==='list'?item.text:item.cells.map(c=>c.text).join(' | ');if(text.length+plain.length+(text?1:0)<=6000&&bytes(text+plain)<9000)text+=(text?'\\n':'')+plain}
}
session.next=to;
const more=to<items.length||session.stage==='discover'||session.walk.stack.length>0||session.rowAt<session.rows.length;
if(more)baseReasons.add('continuation_available');if(session.walk.stack.length)baseReasons.add('scan_continuation_available');if(session.stage==='discover'||kind==='outline'&&session.query&&session.walk.stack.length)baseReasons.add(session.query?'query_search_incomplete':'region_search_incomplete');if((kind==='table'||kind==='list')&&more)baseReasons.add('structured_row_range_continuation');
if(more&&(!session.track.active||!session.track.complete)){baseReasons.add('continuation_version_unavailable');release(session)}
if(flush(session.track)!==session.mutationVersion){release(session);state.cursor=null;return fail('read_region_changed')}
const cursor=more&&session.track.active&&session.track.complete?nonce+':c0':null;
if(cursor){state.cursor={id:cursor,session};if(!session.track.timer&&typeof setTimeout==='function')session.track.timer=setTimeout(()=>{release(session);if(state.cursor&&state.cursor.session===session)state.cursor=null},Math.max(0,120000-(now-session.created)))}else{state.cursor=null;release(session)}
if(kind==='outline'){const byRef=new Map(presentableRegions.map(r=>[r.ref,r]));for(const row of data){const r=byRef.get(row.regionRef);if(r)state.regions.set(r.ref,{el:r.el,version:hashHex(r.hash),created:now,partial:more||baseReasons.size>0})}}
else if(regionRef)state.regions.set(regionRef,{el:target,version:hashHex(session.walk.hash),created:now,partial:more||baseReasons.size>0});
while(state.regions.size>512)state.regions.delete(state.regions.keys().next().value);
const reasons=[...baseReasons].slice(0,16),facts={mode:kind,regionRef,projectionVersion:targetVersion,from,to,totalCaptured:items.length,returnedItems:data.length,omittedItems:Math.max(0,to-from-data.length),partial:reasons.length>0,reasons,wholePageComplete:false,scope:frameScope,textCharsScanned:captured.chars,nodesScanned:captured.nodes,childFramesOmitted:captured.frames,openShadowRootsRead:captured.shadows,closedShadowCoverage:'not_asserted',nameComputation:'bounded_dom_labels_not_browser_accessibility_tree',textProjection:'whitespace_normalized_dom_text',captureWork:work};
const dynamic=readDynamics(target);
return {semanticRead:true,status:'observed',url:location.href,title:document.title||'',text,elements:[],read:{mode:kind,data,cursor,facts,dynamic,baseRevision:request.baseRevision||null},readOnlyEvidence:true};
function readDynamics(root){
 // Only this document's selected region and nearest semantic container; never enter a child frame.
 let container=root;for(let p=root.parentElement;p;p=p.parentElement){container=p;if(['MAIN','ARTICLE','SECTION','BODY'].includes(p.tagName)||['region','main','article'].includes(role(p)))break}
 const out={scope:'selected_region_and_nearest_container',busy:false,contentItems:kind==='table'?items.filter(r=>r.cells&&r.cells.some(c=>!['columnheader'].includes(c.role))).length:kind==='list'?items.length:text.trim()?1:0,observerActive:false,mutationVersion:0,pagination:[],paginationPartial:false,nodesVisited:0,networkIdleProven:false,trust:'untrusted_rendered_dom'};
 if(kind==='outline')return out;
 let track=state.settling;if(request.settle){
  if(!track||track.nonce!==nonce||track.root!==root||track.container!==container){if(track){track.observer.disconnect();clearTimeout(track.timer)}track=null;
   if(typeof MutationObserver==='function'&&typeof setTimeout==='function'){
    try{const t={nonce,root,container,version:0,active:true};t.observer=new MutationObserver(()=>{t.version=Math.min(1000000000,t.version+1)});t.observer.observe(container,{subtree:true,childList:true,characterData:true,attributes:true});t.timer=setTimeout(()=>{t.active=false;t.observer.disconnect();if(state.settling===t)state.settling=null},Math.min(6000,request.settle.timeoutMs||4000)+250);track=t;state.settling=t}catch(_){track=null}
   }
  }
  if(track){if(track.observer.takeRecords().length)track.version=Math.min(1000000000,track.version+1);out.observerActive=track.active;out.mutationVersion=track.version}
 }else if(track){track.observer.disconnect();clearTimeout(track.timer);state.settling=null}
 const queue=[container],seen=new WeakSet(),end=Date.now()+15;
 while(queue.length&&out.nodesVisited<256&&Date.now()<=end){const n=queue.shift();if(!n||seen.has(n))continue;seen.add(n);out.nodesVisited++;if(n.nodeType!==1||['SCRIPT','STYLE','NOSCRIPT','TEMPLATE','IFRAME','FRAME','INPUT','TEXTAREA'].includes(n.tagName)||!visibleSubtree(n))continue;
  const r=role(n),rendered=getComputedStyle(n).display==='contents'||n.getClientRects().length>0;if(!rendered)continue;
  if(n.getAttribute('aria-busy')==='true'||r==='progressbar')out.busy=true;
  if(n.tagName==='A'||n.tagName==='BUTTON'||r==='link'||r==='button'||n.hasAttribute('aria-current')){
   const label=clip(compact(n.getAttribute('aria-label')||labelText(n)),120),current=n.hasAttribute('aria-current')&&!['false',''].includes(n.getAttribute('aria-current')),rel=compact(n.getAttribute('rel')).split(' ');
   const k=current?'current':rel.includes('next')||/^(next|next page|下一页|下页)$/i.test(label)?'next':rel.includes('prev')||/^(previous|previous page|prev|上一页|上页)$/i.test(label)?'previous':/^(load more|show more|加载更多|更多)$/i.test(label)?'load_more':null;
   if(k){if(out.pagination.length>=8)out.paginationPartial=true;else{let href=null;if(n.tagName==='A')try{const u=new URL(n.href,location.href);if(['http:','https:'].includes(u.protocol)&&!u.username&&!u.password&&u.href.length<=2048)href=u.href}catch(_){}out.pagination.push({kind:k,label,href,disabled:!!n.disabled||n.getAttribute('aria-disabled')==='true',current})}}
  }
  const children=composedChildren(n);for(let i=0;i<children.length;i++){if(queue.length>=256){out.paginationPartial=true;break}queue.push(children[i])}
 }
 if(queue.length)out.paginationPartial=true;return out;
}
""";
}
