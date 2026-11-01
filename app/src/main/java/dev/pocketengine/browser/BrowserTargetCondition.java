package dev.pocketengine.browser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read-only predicates in an already presented region. Not an action/ref resolver. */
public final class BrowserTargetCondition {
    public static final int MAX_PROBES=32, POLL_MS=200, OUTPUT_BYTES=8192;
    private static final Set<String> STATES=Set.of("present","absent","text","value","checked","enabled");
    private static final Set<String> ROLES=Set.of("button","link","textbox","combobox","checkbox","radio","heading","status","alert","progressbar","region","form","list","table");
    private BrowserTargetCondition() { }
    private static JSONObject text(int max)throws Exception{return new JSONObject().put("type","string").put("maxLength",max);}
    public static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("regionRef").put("state"))
                .put("properties",new JSONObject().put("regionRef",text(100)).put("state",new JSONObject().put("type","string").put("enum",new JSONArray().put("present").put("absent").put("text").put("value").put("checked").put("enabled")))
                        .put("selector",new JSONObject().put("type","object").put("additionalProperties",false).put("properties",new JSONObject().put("role",text(20).put("enum",new JSONArray().put("button").put("link").put("textbox").put("combobox").put("checkbox").put("radio").put("heading").put("status").put("alert").put("progressbar").put("region").put("form").put("list").put("table"))).put("name",text(160))))
                        .put("value",text(2048)).put("expected",new JSONObject().put("type","boolean"))
                        .put("stableMs",new JSONObject().put("type","integer").put("minimum",0).put("maximum",1000)));
    }
    public static void validate(JSONObject target)throws Exception {
        if(target==null||target.toString().length()>4000)throw new IllegalArgumentException("需要有界的原区域等待条件");
        for(var it=target.keys();it.hasNext();)if(!Set.of("regionRef","state","selector","value","expected","stableMs").contains(it.next()))throw new IllegalArgumentException("区域条件含额外字段");
        if(!target.getString("regionRef").matches("[a-f0-9-]{36}:r[0-9]{1,6}")||!STATES.contains(target.getString("state")))throw new IllegalArgumentException("等待只接受已呈现区域与受限状态");
        String state=target.getString("state");boolean string=state.equals("text")||state.equals("value"),bool=state.equals("checked")||state.equals("enabled");
        if(string!=target.has("value")||bool!=target.has("expected"))throw new IllegalArgumentException("text/value需value；checked/enabled需expected；present/absent不接受额外比较值");
        if(string&&(!(target.get("value") instanceof String)||target.getString("value").length()>2048||!unicode(target.getString("value"))||state.equals("text")&&target.getString("value").isEmpty()))throw new IllegalArgumentException("等待文字过长、为空或含不完整Unicode字符");
        if(bool&&!(target.get("expected") instanceof Boolean))throw new IllegalArgumentException("状态比较需明确布尔值");
        if(target.has("stableMs")){Object n=target.get("stableMs");if(!(n instanceof Number)||((Number)n).doubleValue()!=target.getInt("stableMs")||target.getInt("stableMs")<0||target.getInt("stableMs")>1000)throw new IllegalArgumentException("目标条件稳定窗口只接受0至1000毫秒");}
        if(target.has("selector")){
            JSONObject selector=target.getJSONObject("selector");if(selector.length()==0||selector.length()>2)throw new IllegalArgumentException("子目标需明确role或实际标签name");
            for(var it=selector.keys();it.hasNext();)if(!Set.of("role","name").contains(it.next()))throw new IllegalArgumentException("不接受脚本、CSS或其他选择器");
            if(selector.has("role")&&!ROLES.contains(selector.getString("role")))throw new IllegalArgumentException("不支持的子目标role");
            if(selector.has("name")&&(!(selector.get("name") instanceof String)||blank(selector.getString("name"))||selector.getString("name").length()>160||!unicode(selector.getString("name"))))throw new IllegalArgumentException("子目标name需为实际非空有界标签");
        }
    }
    private static boolean blank(String value){for(int i=0;i<value.length();i++)if(!Character.isWhitespace(value.charAt(i))&&!Character.isSpaceChar(value.charAt(i)))return false;return true;}
    private static boolean unicode(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    public static String script(JSONObject target,String key,String epoch,String document)throws Exception {
        validate(target);BrowserTaskSpace.uuid(document);
        if(!target.getString("regionRef").startsWith(document+":r"))throw new SecurityException("目标区域不属于原文档");
        return "JSON.stringify((()=>{const request="+target+",nativeKey="+JSONObject.quote(key)+",nativeEpoch="+JSONObject.quote(epoch)+";\n"+SCRIPT+"\n})())";
    }
    /** Allowlist the emitted evidence; page values are evidence, never trusted business results. */
    public static JSONObject project(JSONObject raw,JSONObject target,String document)throws Exception {
        validate(target);BrowserTaskSpace.uuid(document);
        if(!raw.optBoolean("targetProbe")||!Set.of("observed","unavailable").contains(raw.optString("status")))throw new IllegalStateException("目标条件未返回正式只读回执");
        Object matched=raw.opt("matched");if(!(matched instanceof Boolean)&&matched!=JSONObject.NULL)throw new IllegalStateException("目标条件缺少明确匹配/未知状态");
        if(!raw.optString("code").matches("[a-z_]{1,60}"))throw new IllegalStateException("目标条件原因无效");
        if(raw.getString("status").equals("observed")&&!(matched instanceof Boolean)||raw.getString("status").equals("unavailable")&&matched!=JSONObject.NULL)throw new IllegalStateException("不能把未知条件伪作已观察状态");
        JSONObject actual=new JSONObject(),input=raw.getJSONObject("actual");
        for(String field:new String[]{"instance","role","name","text","value"})if(input.has(field)){Object value=input.get(field);if(!(value instanceof String)||((String)value).length()>2048||!unicode((String)value))throw new IllegalStateException("目标实际值过大或Unicode不完整");actual.put(field,value);}
        for(String field:new String[]{"present","checked","enabled","valueTruncated"})if(input.has(field)){if(!(input.get(field) instanceof Boolean))throw new IllegalStateException("目标实际状态非布尔值");actual.put(field,input.get(field));}
        for(String field:new String[]{"matchCount","valueLength"})if(input.has(field)){Object n=input.get(field);if(!(n instanceof Number)||((Number)n).doubleValue()!=input.getLong(field)||input.getLong(field)<0)throw new IllegalStateException("目标实际计数无效");actual.put(field,n);}
        if(raw.getString("status").equals("observed")){
            if(!(actual.opt("present") instanceof Boolean))throw new IllegalStateException("目标实际存在状态缺失");
            boolean present=actual.getBoolean("present"),claimed=(Boolean)matched,computed=false;String state=target.getString("state");
            if(present&&!actual.optString("instance").matches("[1-9][0-9]{0,5}"))throw new IllegalStateException("原目标实例身份缺失");
            if(state.equals("present"))computed=present;else if(state.equals("absent"))computed=!present;
            else if(present&&state.equals("text")){if(!(actual.opt("text") instanceof String))throw new IllegalStateException("目标文字证据缺失");computed=actual.getString("text").contains(target.getString("value"));if(claimed&&!computed||!claimed&&computed)throw new IllegalStateException("目标文字匹配与实际证据矛盾");computed=claimed;}
            else if(present&&state.equals("value")){if(!(actual.opt("value") instanceof String)||!(actual.opt("valueTruncated") instanceof Boolean)||!actual.has("valueLength"))throw new IllegalStateException("目标字段证据缺失");computed=!actual.getBoolean("valueTruncated")&&actual.getLong("valueLength")==target.getString("value").length()&&actual.getString("value").equals(target.getString("value"));}
            else if(present){if(!(actual.opt(state) instanceof Boolean))throw new IllegalStateException("目标布尔状态证据缺失");computed=actual.getBoolean(state)==target.getBoolean("expected");}
            if(computed!=claimed)throw new IllegalStateException("目标条件与实际状态证据矛盾");
        }
        JSONObject work=raw.getJSONObject("coverage"),coverage=new JSONObject();
        for(String field:new String[]{"nodesVisited","textCharsVisited","queuedPeak","layoutChecks"}){Object n=work.get(field);if(!(n instanceof Number)||((Number)n).doubleValue()!=work.getInt(field)||work.getInt(field)<0||work.getInt(field)>(field.equals("textCharsVisited")?8000:600))throw new IllegalStateException("目标条件采集预算越界");coverage.put(field,n);}
        if(!(work.get("partial") instanceof Boolean))throw new IllegalStateException("目标条件覆盖不完整");
        JSONArray reasons=work.getJSONArray("reasons");if(reasons.length()>8||reasons.length()>0&&!work.getBoolean("partial"))throw new IllegalStateException("目标条件缺失原因过多或被伪作完整");for(int i=0;i<reasons.length();i++)if(!reasons.getString(i).matches("[a-z_]{1,60}"))throw new IllegalStateException("目标条件缺失原因无效");
        coverage.put("partial",work.getBoolean("partial")).put("reasons",new JSONArray(reasons.toString())).put("scope","presented_region_light_and_open_shadow_dom").put("textProjection","bounded_whitespace_normalized_dom_text").put("nameComputation","bounded_dom_labels_not_browser_accessibility_tree").put("visibility","dom_rect_and_style_not_pixel_occlusion").put("childFrameContentsRead",false).put("closedShadowCoverage","not_asserted").put("wholePageComplete",false).put("budgetMs",25).put("nodeLimit",600).put("textCharLimit",8000);
        JSONObject result=new JSONObject().put("status",raw.getString("status")).put("code",raw.getString("code")).put("matched",matched).put("actual",actual).put("coverage",coverage)
                .put("regionRef",target.getString("regionRef")).put("documentId",document).put("state",target.getString("state")).put("readOnlyEvidence",true).put("untrustedPageContent",true).put("verifiedBusinessSuccess",false);
        if(target.has("selector"))result.put("selector",new JSONObject(target.getJSONObject("selector").toString()));
        result.put("evidenceSha256",sha(new JSONObject().put("actual",actual).put("coverage",coverage).toString()));
        if(result.toString().getBytes(StandardCharsets.UTF_8).length>OUTPUT_BYTES)throw new IllegalStateException("目标条件证据超出预算");return result;
    }
    private static String sha(String value)throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte n:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",n&255));return out.toString();}
    /** One bounded native wait's predicate state, not an execution loop or authority store. */
    public static final class Tracker {
        private final int stableMs;private long since=-1;private String signature="";private int samples;private JSONObject last;
        public Tracker(JSONObject target)throws Exception {validate(target);stableMs=target.optInt("stableMs",0);}
        public boolean accept(JSONObject probe,long now)throws Exception {
            if(samples>=MAX_PROBES)throw new IllegalStateException("目标等待采样预算已满");samples++;last=new JSONObject(probe.toString());
            if(!probe.getString("status").equals("observed")||!Boolean.TRUE.equals(probe.opt("matched"))){since=-1;signature="";return false;}
            String next=probe.getJSONObject("actual").toString();if(since<0||now<since||!signature.equals(next)){since=now;signature=next;}return now-since>=stableMs;
        }
        public boolean exhausted(){return samples>=MAX_PROBES;}
        public JSONObject receipt(boolean met,boolean timedOut,long now)throws Exception {
            boolean known=last!=null&&last.getString("status").equals("observed");Object conditionMet=met?Boolean.TRUE:timedOut&&known?Boolean.FALSE:JSONObject.NULL;
            JSONObject out=new JSONObject().put("accepted",true).put("verified",false).put("status",met?"condition_observed":known&&timedOut?"failed":"unavailable")
                    .put("code",met?"target_condition_observed":known&&timedOut?"condition_timeout":last==null?"target_probe_unavailable":known?"target_wait_probe_budget":last.getString("code"))
                    .put("condition","target").put("conditionMet",conditionMet).put("samples",samples).put("stableMs",stableMs).put("conditionHeldMs",since<0?0:Math.max(0,now-since))
                    .put("readOnlyEvidence",true).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false).put("inputDispatched",false)
                    .put("message",met?"已在原区域实际观察到所需页面状态；不证明服务器业务完成。":known&&timedOut?"限定时间内未观察到所需目标状态；没有重发网页动作。":"目标条件暂不可完整核对，缺失不当作目标不存在；没有扩大范围或重发动作。");
            if(last!=null)out.put("targetEvidence",new JSONObject(last.toString()));return out;
        }
    }
    private static final String SCRIPT="""
const started=Date.now(),deadline=started+25,MAX_NODES=600,MAX_TEXT=8000;
const work={nodesVisited:0,textCharsVisited:0,queuedPeak:0,layoutChecks:0,partial:false,reasons:[]},reasons=new Set(),visibility=new WeakMap();
const clip=(s,n)=>{s=String(s||'');return s.length>n?s.slice(0,n).replace(/[\\uD800-\\uDBFF]$/,''):s},compact=(s,max=8000)=>clip(s,max).replace(/\\s+/g,' ').trim();
const contextFrom=(s,start)=>s.slice(start>0&&s.charCodeAt(start)>=0xDC00&&s.charCodeAt(start)<=0xDFFF&&s.charCodeAt(start-1)>=0xD800&&s.charCodeAt(start-1)<=0xDBFF?start-1:start);
const exhausted=()=>{if(work.nodesVisited>=MAX_NODES){reasons.add('node_budget');return true}if(work.textCharsVisited>=MAX_TEXT){reasons.add('text_budget');return true}if(Date.now()>deadline){reasons.add('probe_time_budget');return true}return false};
const output=(status,code,matched,actual={})=>{work.reasons=[...reasons];work.partial=reasons.size>0;return {targetProbe:true,status,code,matched,actual,coverage:work}};
const state=window[nativeKey];if(!state||state.epoch!==nativeEpoch||state.url!==location.href)return output('unavailable','read_reference_not_current',null);
const region=state.regions.get(request.regionRef);if(!region||!region.el||!region.el.isConnected||region.el.ownerDocument!==document)return output('unavailable','read_region_detached_or_unknown',null);
const shown=e=>{if(visibility.has(e))return visibility.get(e);if(work.layoutChecks>=MAX_NODES){reasons.add('layout_budget');return false}work.layoutChecks++;let yes=!e.closest('[hidden],[aria-hidden="true"]')&&e.getClientRects().length>0;if(yes){const c=getComputedStyle(e);yes=c.display!=='none'&&c.visibility!=='hidden'&&c.visibility!=='collapse'&&String(c.opacity)!=='0'}visibility.set(e,yes);return yes};
const privateInput=e=>{const autocomplete=String(e.getAttribute('autocomplete')||'');return autocomplete.length>512||e.tagName==='INPUT'&&['password','file','hidden'].includes(String(e.type).toLowerCase())||compact(autocomplete,512).toLowerCase().split(' ').some(token=>['current-password','new-password','one-time-code'].includes(token))};
const role=e=>{const r=compact(e.getAttribute('role'),80).split(' ')[0];return r||({BUTTON:'button',A:e.hasAttribute('href')?'link':'',TEXTAREA:'textbox',SELECT:'combobox',INPUT:e.type==='checkbox'?'checkbox':e.type==='radio'?'radio':['button','submit','reset'].includes(e.type)?'button':'textbox',H1:'heading',H2:'heading',H3:'heading',H4:'heading',H5:'heading',H6:'heading',FORM:'form',MAIN:'region',SECTION:'region',TABLE:'table',UL:'list',OL:'list'})[e.tagName]||''};
function children(e,stack){for(const root of [e,e.shadowRoot].filter(Boolean)){if(e.tagName==='IFRAME'||e.tagName==='FRAME'){reasons.add('child_frames_not_read');continue}const cap=Math.min(root.childNodes.length,MAX_NODES-work.nodesVisited,MAX_NODES-stack.length);if(cap<root.childNodes.length)reasons.add('queue_budget');for(let i=cap-1;i>=0;i--)stack.push(root.childNodes[i])}work.queuedPeak=Math.max(work.queuedPeak,stack.length)}
function textOf(root,max){let text='',stack=[root];while(stack.length&&!exhausted()&&text.length<max){const n=stack.pop();work.nodesVisited++;if(n.nodeType===3){if(!n.parentElement||!shown(n.parentElement))continue;const raw=String(n.nodeValue||''),part=clip(raw,Math.min(max-text.length,MAX_TEXT-work.textCharsVisited));text+=part;work.textCharsVisited+=part.length;if(part.length<raw.length)reasons.add('text_projection_budget')}else if(n.nodeType===1&&!['SCRIPT','STYLE','NOSCRIPT','TEMPLATE'].includes(n.tagName)&&!privateInput(n)&&shown(n))children(n,stack)}if(stack.length)reasons.add('text_projection_budget');return compact(text)}
function nameOf(e){let name='';const ids=compact(e.getAttribute('aria-labelledby'),512).split(' ').filter(Boolean).slice(0,8);for(const id of ids){const label=document.getElementById(id);if(label)name+=' '+textOf(label,160)}if(!compact(name,512))name=e.getAttribute('aria-label')||e.getAttribute('alt')||e.getAttribute('title')||'';if(!compact(name,512)&&e.labels)for(let i=0;i<Math.min(e.labels.length,4);i++)name+=' '+textOf(e.labels[i],160);if(!compact(name,512)&&e.tagName==='INPUT'&&['button','submit','reset'].includes(e.type))name=clip(e.value,160);if(!compact(name,512)&&['button','link','heading','status','alert'].includes(role(e)))name=textOf(e,160);return clip(compact(name,512),160)}
let target=region.el,count=1;
if(request.selector){let stack=[region.el],matches=[];while(stack.length&&!exhausted()){const n=stack.pop();work.nodesVisited++;if(n.nodeType!==1||['SCRIPT','STYLE','NOSCRIPT','TEMPLATE'].includes(n.tagName)||!shown(n))continue;const r=role(n);if((!request.selector.role||request.selector.role===r)&&(!request.selector.name||compact(request.selector.name)===nameOf(n))){matches.push(n);if(matches.length>1){reasons.add('ambiguous_scan_stopped');return output('unavailable','target_ambiguous',null,{matchCount:matches.length})}}children(n,stack)}if(stack.length||reasons.size)return output('unavailable','target_scope_incomplete',null,{matchCount:matches.length});count=matches.length;target=matches[0]||null;}
if(!target){if(request.state==='present'||request.state==='absent')return output('observed','target_state_read',request.state==='absent',{present:false,matchCount:0});return output('observed','target_not_present',false,{present:false,matchCount:0})}
if(!shown(target)){if(reasons.size)return output('unavailable','target_scope_incomplete',null,{present:false,matchCount:0});return output('observed','target_not_rendered',request.state==='absent',{present:false,matchCount:0})}
if(privateInput(target))return output('unavailable','target_requires_owner',null,{present:true,matchCount:count});
if(!state.conditionNodes){state.conditionNodes=new WeakMap();state.nextConditionNode=0}let instance=state.conditionNodes.get(target);if(!instance){if(state.nextConditionNode>=999999)return output('unavailable','target_identity_budget',null);instance=String(++state.nextConditionNode);state.conditionNodes.set(target,instance)}
const actual={present:true,matchCount:count,instance:instance,role:role(target),name:nameOf(target)};
if(request.state==='present'||request.state==='absent')return output('observed','target_state_read',request.state==='present',actual);
if(request.state==='text'){const text=textOf(target,Math.min(MAX_TEXT-work.textCharsVisited,8000)),at=text.indexOf(request.value);actual.text=at>=0?clip(contextFrom(text,Math.max(0,at-Math.min(60,Math.max(0,2048-request.value.length-1)))),2048):clip(text,2048);if(at>=0)return output('observed','target_state_read',true,actual);if(reasons.size)return output('unavailable','target_text_incomplete',null,actual);return output('observed','target_state_read',false,actual)}
if(request.state==='value'){if(!['INPUT','TEXTAREA','SELECT'].includes(target.tagName))return output('unavailable','target_value_unsupported',null,actual);const rawValue=target.value,value=String(rawValue==null?'':rawValue);actual.value=clip(value,2048);actual.valueLength=value.length;actual.valueTruncated=value.length>2048;return output('observed','target_state_read',value.length===request.value.length&&value===request.value,actual)}
if(request.state==='checked'){let value;if(target.tagName==='INPUT'&&['checkbox','radio'].includes(target.type))value=!!target.checked;else if(['checkbox','radio'].includes(role(target))&&['true','false'].includes(target.getAttribute('aria-checked')))value=target.getAttribute('aria-checked')==='true';else return output('unavailable','target_checked_unsupported',null,actual);actual.checked=value;return output('observed','target_state_read',value===request.expected,actual)}
if(request.state==='enabled'){if(!['BUTTON','INPUT','TEXTAREA','SELECT'].includes(target.tagName)&&!['button','link','textbox','combobox','checkbox','radio'].includes(role(target)))return output('unavailable','target_enabled_unsupported',null,actual);actual.enabled=!target.disabled&&!target.matches(':disabled')&&target.getAttribute('aria-disabled')!=='true';return output('observed','target_state_read',actual.enabled===request.expected,actual)}
return output('unavailable','target_state_unsupported',null,actual);
""";
}
