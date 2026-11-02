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
const fail=(code)=>({semanticRead:true,status:'unavailable',code,url:location.href,title:document.title||'',readOnlyEvidence:true,automaticReplayAllowed:false});
const compact=s=>String(s||'').replace(/\\s+/g,' ').trim();
const clip=(s,n)=>{s=String(s||'');return s.length>n?s.slice(0,n).replace(/[\\uD800-\\uDBFF]$/,''):s};
const high=c=>c>=0xD800&&c<=0xDBFF,low=c=>c>=0xDC00&&c<=0xDFFF;
const contextFrom=(s,start)=>s.slice(start>0&&low(s.charCodeAt(start))&&high(s.charCodeAt(start-1))?start-1:start);
const hashAdd=(h,s)=>{s+='\\u001e';for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619)}return h};const hashHex=h=>(h>>>0).toString(16).padStart(8,'0');const bytes=s=>{let n=0;for(let i=0;i<s.length;i++){const c=s.charCodeAt(i);if(c<128)n++;else if(c<2048)n+=2;else if(c>=0xD800&&c<=0xDBFF&&i+1<s.length&&s.charCodeAt(i+1)>=0xDC00&&s.charCodeAt(i+1)<=0xDFFF){n+=4;i++}else n+=3}return n};
const role=e=>{const r=compact(e.getAttribute('role')).split(' ')[0];if(r)return r;return ({BODY:'document',MAIN:'main',ARTICLE:'article',SECTION:'region',NAV:'navigation',HEADER:'banner',FOOTER:'contentinfo',FORM:'form',TABLE:'table',UL:'list',OL:'list',DL:'list',LI:'listitem',DT:'term',DD:'definition',TR:'row',TH:'columnheader',TD:'cell',A:'link',BUTTON:'button',INPUT:e.type==='checkbox'?'checkbox':'textbox',SELECT:'combobox',TEXTAREA:'textbox',H1:'heading',H2:'heading',H3:'heading',H4:'heading',H5:'heading',H6:'heading'})[e.tagName]||''};
const labelText=el=>{let out='',count=0,stack=[el];while(stack.length&&count++<32&&out.length<512&&work.nodesVisited<MAX_NODES&&work.textCharsVisited<MAX_CHARS&&Date.now()<=deadline){const n=stack.pop();work.nodesVisited++;if(n.nodeType===3){const part=clip(n.nodeValue||'',Math.min(512-out.length,MAX_CHARS-work.textCharsVisited));out+=part;work.textCharsVisited+=part.length}else if(n.nodeType===1){for(let i=Math.min(n.childNodes.length,Math.max(0,32-stack.length))-1;i>=0;i--)stack.push(n.childNodes[i])}}return clip(compact(out),512)};
const name=e=>{let s='';const ids=compact(e.getAttribute('aria-labelledby')).split(' ').filter(Boolean).slice(0,8);for(const id of ids){const n=document.getElementById(id);if(n)s+=' '+clip(labelText(n),160)}if(!compact(s))s=e.getAttribute('aria-label')||e.getAttribute('alt')||e.getAttribute('title')||'';if(!compact(s)&&e.tagName==='TABLE'){const c=e.caption;if(c)s=labelText(c)}if(!compact(s)&&/^H[1-6]$/.test(e.tagName))s=labelText(e);return clip(compact(s),160)};
const stateOf=e=>({checked:e.getAttribute('aria-checked')||(e.type==='checkbox'||e.type==='radio'?String(!!e.checked):''),selected:e.getAttribute('aria-selected')||'',expanded:e.getAttribute('aria-expanded')||'',disabled:e.getAttribute('aria-disabled')||(e.disabled?'true':''),required:e.getAttribute('aria-required')||(e.required?'true':'')});
if(!document.body&&!document.documentElement)return fail('document_unavailable');
let state=window[nativeKey];if(!state||state.epoch!==nativeEpoch||state.url!==location.href){state={epoch:nativeEpoch,url:location.href,namespace:regionNamespace,nextRegion:0,nodeRefs:new WeakMap(),regions:new Map(),cursor:null};window[nativeKey]=state}
let layoutLimited=false;const hasRect=e=>{if(work.layoutChecks>=MAX_NODES){layoutLimited=true;return false}work.layoutChecks++;return e.getClientRects().length>0};
const regionsRole=new Set(['document','main','article','region','navigation','banner','contentinfo','form','table','grid','list','heading']);
function scan(root,makeRegions){
 const out={nodes:0,chars:0,text:[],elements:[],regions:[],frames:0,shadows:0,reasons:new Set(),hash:2166136261},stack=[{node:root,anc:[]}],vis=new WeakMap(),seen=new WeakSet();
 const shown=e=>{if(vis.has(e))return vis.get(e);const yes=visibleSubtree(e)&&(getComputedStyle(e).display==='contents'||hasRect(e));vis.set(e,yes);return yes};
 let rootRegion=null;
 while(stack.length){
  if(work.nodesVisited>=MAX_NODES||Date.now()>deadline||work.textCharsVisited>=MAX_CHARS){out.reasons.add(work.nodesVisited>=MAX_NODES?'node_budget':work.textCharsVisited>=MAX_CHARS?'text_scan_budget':'capture_time_budget');break}
  const {node,anc,depth=0}=stack.pop();if(seen.has(node))continue;seen.add(node);out.nodes++;work.nodesVisited++;if(depth>128){out.reasons.add('depth_budget');continue}
  if(node.nodeType===3){const p=node.parentElement;if(!p||!shown(p))continue;const remaining=MAX_CHARS-work.textCharsVisited,raw=String(node.nodeValue||'');if(raw.length>remaining)out.reasons.add('text_scan_budget');let t=compact(clip(raw,remaining));if(!t)continue;out.chars+=t.length;work.textCharsVisited+=t.length;out.text.push(t);out.hash=hashAdd(out.hash,t);for(const r of anc){r.hash=hashAdd(r.hash,t);if(!r.preview)r.preview=clip(t,140);if(request.query){const at=t.toLocaleLowerCase().indexOf(request.query.toLocaleLowerCase());if(at>=0){r.matched=true;r.preview=clip(contextFrom(t,Math.max(0,at-60)),220)}}}if(work.textCharsVisited>=MAX_CHARS){out.reasons.add('text_scan_budget');break}continue}
  if(node.nodeType!==1)continue;const e=node;if(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE'].includes(e.tagName)||e.type==='password'||!visibleSubtree(e))continue;
  out.elements.push(e);const r=role(e),n=name(e),href=e.tagName==='A'?clip(e.href,2048):'';const identity=e.tagName+'|'+r+'|'+n+'|'+href+'|'+JSON.stringify(stateOf(e));out.hash=hashAdd(out.hash,identity);for(const a of anc)a.hash=hashAdd(a.hash,identity);
  if(e.tagName==='IFRAME'||e.tagName==='FRAME'){out.frames++;out.reasons.add('child_frames_not_read');continue}
  if(e.tagName==='CANVAS')out.reasons.add('canvas_pixels_not_read');
  let nextAnc=anc;if(makeRegions&&(e===root||regionsRole.has(r))){if(out.regions.length<256){let ref=state.nodeRefs.get(e);if(!ref){if(state.nextRegion>=999999){out.reasons.add('region_identity_budget');continue}ref=state.namespace+':r'+(++state.nextRegion);state.nodeRefs.set(e,ref)}const item={el:e,ref,role:r||'region',name:n,preview:'',matched:!!request.query&&n.toLocaleLowerCase().includes(request.query.toLocaleLowerCase()),hash:hashAdd(2166136261,identity),order:out.regions.length};out.regions.push(item);nextAnc=anc.concat(item);if(e===root)rootRegion=item}else out.reasons.add('region_budget')}
  const nodes=composedChildren(e);if(e.shadowRoot)out.shadows++;

  const cap=Math.min(nodes.length,Math.max(0,MAX_NODES-work.nodesVisited),Math.max(0,MAX_QUEUE-stack.length));if(cap<nodes.length)out.reasons.add('queue_budget');for(let i=cap-1;i>=0;i--)stack.push({node:nodes[i],anc:nextAnc,depth:depth+1});work.queuedPeak=Math.max(work.queuedPeak,stack.length);
 }
 out.version=hashHex(out.hash);for(const r of out.regions)r.version=hashHex(r.hash);out.rootRegion=rootRegion;return out;
}
const fresh=(el)=>!!el&&el.isConnected&&el.ownerDocument===document;
let captured,target,items,from=0,kind=request.mode,regionRef='',baseReasons=new Set(),targetVersion='',presentableRegions=[];
if(request.cursor){
 const c=state.cursor;if(!c||c.id!==request.cursor||now-c.created>120000||now<c.created||c.mode!==kind)return fail('read_cursor_expired_or_unknown');
 if(!fresh(c.el))return fail('read_region_detached');captured=scan(c.el,false);if(captured.version!==c.version)return fail('read_region_changed');
 target=c.el;items=c.items;presentableRegions=c.regionRecords||[];from=c.next;regionRef=c.regionRef;baseReasons=new Set(c.reasons);targetVersion=c.version;
}else{
 if(request.regionRef){const old=state.regions.get(request.regionRef);if(!old||now-old.created>120000||!fresh(old.el))return fail('read_region_expired_or_unknown');captured=scan(old.el,false);if(!request.settle&&!request.baseRevision&&!old.partial&&captured.version!==old.version)return fail('read_region_changed');target=old.el;regionRef=request.regionRef;targetVersion=captured.version}
 else{captured=scan(document.body||document.documentElement,true);// Discoveries are not presented knowledge; only final returned rows are committed below.
  if(kind==='outline'){presentableRegions=captured.regions;items=captured.regions.filter(r=>!request.query||r.matched).map(r=>({regionRef:r.ref,role:r.role,name:r.name,preview:r.preview,documentOrder:r.order,state:stateOf(r.el),readOnlyEvidence:true}));target=document.body||document.documentElement;targetVersion=captured.version}
  else{let possible=captured.regions.filter(r=>kind==='text'?(!request.query||r.matched):kind==='table'?(r.role==='table'||r.role==='grid'):(r.role==='list'));if(request.query)possible=possible.filter(r=>r.matched);if(kind==='text'&&!request.query)possible=captured.regions.filter(r=>r.el===(document.body||document.documentElement));if(possible.length!==1)return fail(possible.length?'read_region_ambiguous':'read_region_not_found');const picked=possible[0];target=picked.el;regionRef=picked.ref;if(target!==(document.body||document.documentElement))captured=scan(target,false);targetVersion=captured.version}
 }
 baseReasons=new Set(captured.reasons);
 if(kind==='text'){const corpus=captured.text.join('\\n');items=[];for(let p=0;p<corpus.length;){let end=Math.min(p+2000,corpus.length);if(end<corpus.length&&high(corpus.charCodeAt(end-1))&&low(corpus.charCodeAt(end)))end--;items.push(corpus.slice(p,end));p=end}}
 if(kind==='table'||kind==='list'){
  const expected=kind==='table'?new Set(['table','grid']):new Set(['list']);if(!expected.has(role(target)))return fail('read_region_kind_mismatch');
  items=[];const candidates=captured.elements.filter(e=>kind==='table'?role(e)==='row':['listitem','term','definition'].includes(role(e))).slice(0,601);let cells=0,chars=0;
  for(const row of candidates){if(items.length>=160||cells>=1024||Date.now()>deadline){baseReasons.add('structured_capture_budget');break}let owner=row.parentElement;while(owner&&owner!==target&&!expected.has(role(owner)))owner=owner.parentElement;if(owner!==target)continue;if(!hasRect(row))continue;
   if(kind==='list'){const part=readText(row,2000);chars+=part.text.length;items.push({index:items.length,kind:role(row),text:part.text,links:part.links,partial:part.partial});if(part.partial)baseReasons.add('cell_text_budget')}
   else{const data=[];for(let ci=0;ci<row.children.length;ci++){if(Date.now()>deadline){baseReasons.add('capture_time_budget');break}const cell=row.children[ci];if(!['TD','TH'].includes(cell.tagName)&&!['cell','gridcell','columnheader','rowheader'].includes(role(cell)))continue;if(data.length>=24||cells>=1024){baseReasons.add('column_budget');break}const part=readText(cell,1500),column=data.length;cells++;chars+=part.text.length;const colSpan=Number(cell.getAttribute('colspan')||cell.getAttribute('aria-colspan')||1),rowSpan=Number(cell.getAttribute('rowspan')||cell.getAttribute('aria-rowspan')||1);data.push({column,role:role(cell),text:part.text,links:part.links,colSpan:Number.isInteger(colSpan)&&colSpan>0?Math.min(colSpan,1000):1,rowSpan:Number.isInteger(rowSpan)&&rowSpan>0?Math.min(rowSpan,1000):1,partial:part.partial});if(part.partial)baseReasons.add('cell_text_budget')}items.push({index:items.length,cells:data})}
   if(chars>=120000){baseReasons.add('structured_text_budget');break}
  }
  if(candidates.length>600)baseReasons.add('structured_row_scan_budget');if(kind==='table'&&target.getAttribute('aria-rowcount')&&Number(target.getAttribute('aria-rowcount'))>items.length)baseReasons.add('virtual_rows_not_in_dom');
 }
}
function readText(el,max){const s=scan(el,false);let text=compact(s.text.join(' ')),partial=s.reasons.size>0||text.length>max;const links=[];for(const a of s.elements.filter(e=>e.tagName==='A'&&e.hasAttribute('href')).slice(0,9)){if(!hasRect(a))continue;try{const u=new URL(a.href,location.href);if(!['http:','https:'].includes(u.protocol)||u.username||u.password){partial=true;continue}if(u.href.length>2048){partial=true;continue}if(links.length>=8){partial=true;break}links.push({text:clip(labelText(a),160),href:u.href})}catch(_){}}return {text:clip(text,max),links,partial}}
for(const reason of captured.reasons)baseReasons.add(reason);if(layoutLimited)baseReasons.add('layout_budget');
const data=[],limit=kind==='outline'?32:kind==='text'?200:24;let text='',used=0,to=from;
for(let i=from;i<items.length&&data.length<limit;i++){
 let item=items[i];if(kind==='text'){let remaining=6000-text.length;if(remaining<=0)break;const str=String(item);if(str.length>remaining){if(!data.length){data.push(clip(str,remaining));text=clip(str,remaining);to=i+1;baseReasons.add('single_text_node_clipped')}break}const candidate=str;if(text.length+candidate.length>6000||bytes(text+candidate)>11000)break;data.push(str);text+=candidate;to=i+1}
 else{const encoded=JSON.stringify(item);if(used+bytes(encoded)>8000){if(!data.length){baseReasons.add('structured_row_output_budget');to=i+1}break}data.push(item);used+=bytes(encoded);to=i+1;const plain=kind==='outline'?[item.name,item.preview].filter(Boolean).join(': '):kind==='list'?item.text:item.cells.map(c=>c.text).join(' | ');if(text.length+plain.length+(text?1:0)<=6000&&bytes(text+plain)<9000)text+=(text?'\\n':'')+plain}
}
if(to<items.length)baseReasons.add('continuation_available');
const cursor=to<items.length&&to>from?nonce+':c0':null;if(cursor)state.cursor={id:cursor,el:target,version:targetVersion,items,regionRecords:presentableRegions,next:to,mode:kind,regionRef,created:request.cursor?state.cursor.created:now,reasons:[...baseReasons].filter(r=>r!=='continuation_available')};else state.cursor=null;
if(kind==='outline'){const byRef=new Map(presentableRegions.map(r=>[r.ref,r]));for(const row of data){const r=byRef.get(row.regionRef);if(r)state.regions.set(r.ref,{el:r.el,version:r.version,created:now,partial:captured.reasons.size>0})}}
else if(regionRef)state.regions.set(regionRef,{el:target,version:targetVersion,created:now,partial:captured.reasons.size>0});
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
