package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONObject;

/** Live DOM expression interpreter and explicitly effectful page programs. Neither is snapshot evaluation. */
public final class BrowserPagePrograms {
    private BrowserPagePrograms(){}
    private static final Set<String> OPS=Set.of("literal","item","query","queryAll","text","attr","count","map","filter","record","eq","contains","and","or","not","add","number","first","trim","lower","upper","sum","min","max","distinct","sort","slice","join","subtract","multiply","divide","gt","gte","lt","lte");
    public static void validateExpression(String source)throws Exception {if(source==null||source.length()>8000)throw new IllegalArgumentException("观察表达式过大");validate(new JSONObject(source),0,new int[]{0});}
    private static void validate(JSONObject node,int depth,int[] count)throws Exception {
        if(depth>16||++count[0]>256||!OPS.contains(node.optString("op")))throw new IllegalArgumentException("不支持或过深的只读表达式");
        for(java.util.Iterator<String> keys=node.keys();keys.hasNext();){String key=keys.next();if(!Set.of("op","value","input","selector","name","left","right","body","fields","start","limit","separator","descending").contains(key))throw new IllegalArgumentException("只读表达式字段不支持");Object value=node.get(key);if(value instanceof JSONObject){if(key.equals("fields")){JSONObject fields=(JSONObject)value;if(fields.length()>24)throw new IllegalArgumentException("字段过多");for(java.util.Iterator<String> names=fields.keys();names.hasNext();)validate(fields.getJSONObject(names.next()),depth+1,count);}else validate((JSONObject)value,depth+1,count);}else if(value instanceof org.json.JSONArray||String.valueOf(value).length()>2000)throw new IllegalArgumentException("表达式只支持有界标量与子表达式");}
        if(node.optString("op").equals("attr")&&!node.optString("name").matches("id|class|role|href|title|name|type|datetime|aria-[a-z-]+|data-[a-z0-9-]+"))throw new IllegalArgumentException("不读取值、事件处理器或私密属性");
        if(Set.of("query","queryAll").contains(node.getString("op")))validateSelector(node.getString("selector"));
        for(String key:new String[]{"start","limit"})if(node.has(key)&&(!(node.get(key) instanceof Number)||node.getDouble(key)!=node.getInt(key)||node.getInt(key)<0||node.getInt(key)>200))throw new IllegalArgumentException("只读切片仅接受0至200的整数量");
        if(node.has("descending")&&!(node.get("descending") instanceof Boolean)||node.has("separator")&&(!(node.get("separator") instanceof String)||node.getString("separator").length()>40))throw new IllegalArgumentException("只读排序或连接参数无效");
    }
    /** Reject selectors which infer a private control value without reading its value field. */
    static void validateSelector(String selector){
        if(selector==null||selector.isBlank()||selector.length()>500||selector.contains("\\")||selector.contains("/*")||selector.contains("|")||selector.matches("(?is).*:(?:checked|valid|invalid|user-valid|user-invalid|in-range|out-of-range|placeholder-shown|autofill|has)\\b.*"))throw new IllegalArgumentException("只读选择器含私密状态或不支持的语法");
        java.util.regex.Matcher attributes=java.util.regex.Pattern.compile("\\[\\s*([A-Za-z][A-Za-z0-9_-]*)").matcher(selector);
        while(attributes.find())if(!attributes.group(1).toLowerCase(java.util.Locale.ROOT).matches("id|class|role|href|title|name|type|datetime|aria-[a-z-]+|data-[a-z0-9-]+"))throw new IllegalArgumentException("只读选择器不能测试控件值或私密属性");
    }
    public static void validateScript(JSONObject args)throws Exception {
        String body=args.getString("source"),purpose=args.getString("effectReview");if(body.isBlank()||body.length()>12000||purpose.isBlank()||purpose.length()>1000)throw new IllegalArgumentException("请提供实际脚本及需主人审阅的效果说明");
        if(args.has("frameRef"))throw new IllegalArgumentException("通用页面脚本当前只支持明确主文档；不能猜测或降级子frame目标");
    }
    public static String expression(String source)throws Exception {
        validateExpression(source);String normalized=new JSONObject(source).toString();
        return "(()=>{'use strict';const expression="+normalized+";\n"+EXPRESSION+"\n})()";
    }
    private static final String EXPRESSION="""
let steps=0,partial=false;const began=performance.now();
const tick=()=>{if(++steps>4000||performance.now()-began>100)throw Error('expression_budget')};
const sensitive=n=>n&&n.nodeType===1&&(n.matches('input,textarea,select,script,style,noscript,template,[hidden]')||n.closest('[data-private=true],[hidden],input,textarea,select'));
const list=v=>Array.isArray(v)?v:[],bounded=v=>{if(v.length>200)partial=true;return v.slice(0,200)};
const scalar=v=>v===null||['string','number','boolean'].includes(typeof v);
const visible=n=>{if(sensitive(n)||!n.getClientRects().length)return false;const s=getComputedStyle(n);return s.display!=='none'&&s.visibility!=='hidden'&&s.contentVisibility!=='hidden'};
function text(n){if(!n||!n.nodeType||sensitive(n))return null;let out='',stack=[n];while(stack.length&&out.length<4000){tick();const e=stack.pop();if(e.nodeType===3){if(e.parentElement&&visible(e.parentElement))out+=String(e.nodeValue||'').slice(0,4000-out.length);continue}if(e.nodeType!==1||!visible(e))continue;const children=e.childNodes;for(let i=children.length-1;i>=0;i--){if(stack.length>=400){partial=true;break}stack.push(children[i])}}if(stack.length)partial=true;return out}
function numeric(v){if(typeof v==='boolean'||v===null||Array.isArray(v)||v&&typeof v==='object')throw Error('expression_number_required');const n=Number(v);if(!Number.isFinite(n))throw Error('expression_number_invalid');return n}
function go(x,item){tick();const a=()=>go(x.input,item),l=()=>go(x.left,item),r=()=>go(x.right,item);switch(x.op){
case'literal':return x.value;case'item':return item;
case'query':case'queryAll':{const root=x.input?a():document;if(!root||!root.querySelectorAll||sensitive(root))return x.op==='query'?null:[];const found=root.querySelectorAll(x.selector),nodes=[];if(found.length>200)partial=true;for(let i=0;i<Math.min(found.length,200);i++){tick();if(visible(found[i]))nodes.push(found[i])}return x.op==='query'?nodes[0]||null:nodes}
case'text':return text(a());case'attr':{const n=a();return n&&n.nodeType===1&&visible(n)?n.getAttribute(x.name):null}
case'count':return list(a()).length;case'first':return list(a())[0]??null;
case'map':return bounded(list(a())).map(n=>go(x.body,n));case'filter':return bounded(list(a())).filter(n=>!!go(x.body,n));
case'record':{const out=Object.create(null);for(const key of Object.keys(x.fields))out[key]=go(x.fields[key],item);return out}
case'eq':return l()===r();case'contains':return String(l()).includes(String(r()));case'and':return !!l()&&!!r();case'or':return !!l()||!!r();case'not':return !a();
case'number':return numeric(a());case'add':return numeric(l())+numeric(r());case'subtract':return numeric(l())-numeric(r());case'multiply':return numeric(l())*numeric(r());case'divide':{const left=numeric(l()),right=numeric(r());if(right===0)throw Error('expression_divide_zero');return left/right}
case'gt':return numeric(l())>numeric(r());case'gte':return numeric(l())>=numeric(r());case'lt':return numeric(l())<numeric(r());case'lte':return numeric(l())<=numeric(r());
case'trim':return String(a()??'').trim();case'lower':return String(a()??'').toLowerCase();case'upper':return String(a()??'').toUpperCase();
case'sum':case'min':case'max':{const values=bounded(list(a())).map(numeric);if(!values.length)return x.op==='sum'?0:null;return x.op==='sum'?values.reduce((p,n)=>p+n,0):x.op==='min'?Math.min(...values):Math.max(...values)}
case'distinct':{const values=bounded(list(a()));if(!values.every(scalar))throw Error('expression_scalar_list_required');return Array.from(new Set(values))}
case'sort':{const values=bounded(list(a()));if(!values.every(scalar))throw Error('expression_scalar_list_required');return values.sort((p,n)=>{tick();const c=typeof p==='number'&&typeof n==='number'?p-n:String(p).localeCompare(String(n));return x.descending?-c:c})}
case'slice':return bounded(list(a())).slice(x.start||0,(x.start||0)+(x.limit??200));
case'join':{const values=bounded(list(a()));if(!values.every(scalar))throw Error('expression_scalar_list_required');return values.join(x.separator??',')}
default:throw Error('invalid_operator')}}
try{const result=go(expression,null);const encoded=JSON.stringify(result,(k,v)=>v&&v.nodeType?{tag:v.tagName,text:text(v)}:v);if(encoded===undefined||new TextEncoder().encode(encoded).length>16000)return JSON.stringify({status:'unavailable',code:'expression_result_budget',partial:true});return JSON.stringify({status:'observed',url:String(location.href),title:document.title,value:JSON.parse(encoded),partial,steps,readOnlyEvidence:true,language:'bounded_live_dom_expression_v2',arbitraryJavascript:false,privateControlsRead:false,untrustedPageContent:true})}catch(e){return JSON.stringify({status:'unavailable',code:String(e.message||'expression_failed').slice(0,80),partial:true,automaticReplayAllowed:false})}
""";
    /** Actual page-world JS, not a DOM-action translation. Owner approval must include the exact source. */
    public static String script(String source,String token){return "(()=>{const stringify=JSON.stringify.bind(JSON);try{const value=(function(){'use strict';\n"+source+"\n}).call(undefined);if(value&&typeof value.then==='function')return stringify({status:'unknown',accepted:true,code:'async_script_completion_unknown',automaticReplayAllowed:false});let output;try{output=stringify(value===undefined?null:value)}catch(e){output=null}return stringify({status:'script_returned',accepted:true,verified:false,token:"+JSONObject.quote(token)+",value:output&&output.length<=16000?JSON.parse(output):null,resultOmitted:!output||output.length>16000,automaticReplayAllowed:false,executionWorld:'page_world',effectsMayContinue:true,serverOutcomeVerified:false})}catch(e){return stringify({status:'unknown',accepted:true,code:'page_script_threw_after_possible_effect',automaticReplayAllowed:false})}})()";}
    public static String inspect(){return "(()=>{let partial=false,visited=0,chars=0;const visibilityIncomplete=()=>partial=true;"+BrowserVisibleSubtree.SCRIPT+"const root=document.querySelector('main,article,[role=main]')||document.body;const queue=root?[root]:[],parts=[],next=[],obstacles=[];let lazyPending=0;while(queue.length&&visited<1200&&chars<12000){const n=queue.shift();visited++;if(n.nodeType===3){const t=(n.nodeValue||'').replace(/\\s+/g,' ').trim();if(t){parts.push(t.slice(0,12000-chars));chars+=t.length}continue}if(n.nodeType!==1||!visibleSubtree(n)||/^(SCRIPT|STYLE|NOSCRIPT|INPUT|TEXTAREA)$/.test(n.tagName))continue;const label=(n.getAttribute('aria-label')||n.innerText||'').trim().slice(0,120);if(n.tagName==='IMG'&&(!n.complete||n.loading==='lazy'))lazyPending++;if((n.tagName==='A'||n.tagName==='BUTTON')&&(/^(next|next page|load more|more|下一页|下页|加载更多|更多)$/i.test(label)||n.getAttribute('rel')==='next')&&next.length<12)next.push({tag:n.tagName,label,href:n.tagName==='A'?n.href:null,disabled:!!n.disabled,untrusted:true});if((n.getAttribute('role')==='dialog'||n.getAttribute('aria-busy')==='true'||/captcha|验证码|sign in|登录|access denied|访问受限|cookie consent/i.test(label))&&obstacles.length<12)obstacles.push({kind:n.getAttribute('role')==='dialog'?'dialog':n.getAttribute('aria-busy')==='true'?'busy':'text_hint',label,untrusted:true});for(const c of composedChildren(n)){if(queue.length<1200)queue.push(c);else partial=true}}if(queue.length)partial=true;const text=parts.join(' ').slice(0,12000);let hash=2166136261;for(let i=0;i<text.length;i++)hash=Math.imul(hash^text.charCodeAt(i),16777619);const sc=document.scrollingElement||document.documentElement;return JSON.stringify({status:'observed',url:String(location.href),title:document.title,text,bodyDigest:String(hash>>>0),coverage:{visited,partial,scope:'bounded_visible_main_or_body_composed_tree',childFrameContentsRead:false},scroll:{top:sc.scrollTop,height:sc.scrollHeight,viewport:sc.clientHeight,remaining:Math.max(0,sc.scrollHeight-sc.scrollTop-sc.clientHeight)},lazyLoad:{pendingImageHints:lazyPending,automaticScrollPerformed:false,networkIdleProven:false},pagination:next,obstacles,readOnlyEvidence:true,untrustedPageContent:true})})()";}
}
