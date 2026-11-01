package dev.pocketengine.browser;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded native-form protocol on the original action refs, not an arbitrary script/input API. */
public final class BrowserFormControls {
    public static final int MAX_OPTIONS=32, MAX_TOTAL_OPTIONS=64;
    private BrowserFormControls() { }
    /** Only fixed native form-boundary reasons may cross the generic authorization-error presentation. */
    public static final class OwnerRequired extends SecurityException {private OwnerRequired(String reason){super(reason);}}
    public static boolean command(String name){return name.equals("select_option")||name.equals("set_checked");}
    /** Native approval wording; optionRef is resolved only against the actual presented target. */
    public static String approvalSummary(JSONObject preview,JSONObject args)throws Exception {
        JSONObject choice=requireTarget(preview,args);String label=preview.optString("text");if(label.isEmpty())label=preview.optString("name");if(label.isEmpty())label="当前控件";
        if(args.has("optionRef"))return "在“"+label+"”中选择“"+(choice.getString("label").isEmpty()?choice.getString("value"):choice.getString("label"))+"”。不额外点击提交；页面自身的联动或自动保存仍可能发生。";
        return "将“"+label+"”"+(args.getBoolean("checked")?"设为已选":"设为未选")+"。已是目标状态时不再触发；不额外点击提交，页面自身的联动仍可能发生。";
    }

    /** Same ordinary main-document observation, with bounded actual option/state evidence. */
    public static String observationScript(String pageKey,String observationId) {
        return "JSON.stringify((()=>{"+BrowserVisibleSubtree.SCRIPT+"let partial=false,visited=0;const visibilityIncomplete=()=>{partial=true};const key="+JSONObject.quote(pageKey)+";const prior=window[key];if(prior&&prior.observer)prior.observer.disconnect();"
                +"const nodes=[],roots=[document],seen=new WeakSet();const scan=(e,depth)=>{if(!e||e.nodeType!==1||seen.has(e))return;seen.add(e);if(depth>64||++visited>5000||nodes.length>=250){partial=true;return}if(!visibleSubtree(e))return;if(e.matches('a[href],input,textarea,select,button,[role=button],[contenteditable=true],[contenteditable=plaintext-only]')&&e.getClientRects().length>0)nodes.push(e);if(e.shadowRoot)roots.push(e.shadowRoot);for(const n of composedChildren(e))scan(n,depth+1)};scan(document.documentElement,0);"
                +"const nodeKeys=prior&&prior.nodeKeys||new WeakMap();let nextNodeKey=prior&&prior.nextNodeKey||1;const nodeKey=e=>{let key=nodeKeys.get(e);if(!key){if(nextNodeKey>1000000){partial=true;return ''}key='k'+nextNodeKey++;state.nextNodeKey=nextNodeKey;nodeKeys.set(e,key)}return key};const state={nodeKeys,nextNodeKey,id:"+JSONObject.quote(observationId)+",nodes,optionNodes:[],dirty:false};let optionBudget="+MAX_TOTAL_OPTIONS+";"
                +"const clip=(s,n)=>{s=String(s||'');let end=Math.min(s.length,n);if(end&&end<s.length&&s.charCodeAt(end-1)>=0xd800&&s.charCodeAt(end-1)<=0xdbff)end--;return s.slice(0,end)};"
                +"const disabled=e=>!!e.disabled||e.matches(':disabled')||e.getAttribute('aria-disabled')==='true';"
                +"const control=(e,i)=>{if(e.tagName==='SELECT'){const options=[],registered=[];for(let j=0;j<e.options.length&&j<"+MAX_OPTIONS+"&&optionBudget>0;j++,optionBudget--){const o=e.options[j],value=String(o.value),label=String(o.label);registered[j]=o;options.push({optionRef:'o'+j,index:j,value:clip(value,256),label:clip(label,160),disabled:!!o.disabled||!!(o.parentElement&&o.parentElement.tagName==='OPTGROUP'&&o.parentElement.disabled)||!!o.hidden,selected:!!o.selected,complete:value.length<=256&&label.length<=160})}state.optionNodes[i]=registered;return {kind:'select',disabled:disabled(e),readOnly:e.getAttribute('aria-readonly')==='true',multiple:!!e.multiple,selectedIndex:e.selectedIndex,optionsCount:e.options.length,optionsOmitted:e.options.length-options.length,options}}"
                +"if(e.tagName==='INPUT'&&(e.type==='checkbox'||e.type==='radio'))return {kind:e.type,disabled:disabled(e),readOnly:!!e.readOnly||e.getAttribute('aria-readonly')==='true',checked:!!e.checked,indeterminate:!!e.indeterminate};if(e.isContentEditable)return {kind:'contenteditable',disabled:disabled(e),readOnly:e.getAttribute('aria-readonly')==='true'};if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'&&['text','search','email','url','tel','number','date'].includes(e.type))return {kind:e.type==='date'?'date':'text',disabled:disabled(e),readOnly:!!e.readOnly||e.getAttribute('aria-readonly')==='true'};return undefined};"
                +"state.observer=new MutationObserver(()=>{state.dirty=true});for(const root of roots)state.observer.observe(root,{subtree:true,childList:true,characterData:true,attributes:true});window[key]=state;"
                +"return {url:location.href,title:document.title,partial,limits:{maxNodes:250,maxVisited:5000,maxDepth:64},text:(document.body?document.body.innerText:'').slice(0,12000),elements:nodes.map((e,i)=>({ref:'n'+i,nodeKey:nodeKey(e),tag:e.tagName,type:e.type||'',role:e.getAttribute('role')||'',id:e.id||'',name:e.name||'',text:clip(e.getAttribute('aria-label')||(e.labels&&e.labels.length?e.labels[0].innerText:'')||e.innerText||e.placeholder||'',160),href:e.tagName==='A'?e.href:undefined,formAction:(e.form||e.closest('form'))?(e.form||e.closest('form')).action:undefined,formMethod:(e.form||e.closest('form'))?(e.form||e.closest('form')).method:undefined,submitterFormAction:e.hasAttribute('formaction')?e.formAction:undefined,ownerRequired:e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code',control:control(e,i)}))};})())";
    }

    /** Producer and presentation share one bounded shape; none of this is native authority from a page. */
    public static JSONObject project(JSONObject raw)throws Exception {
        if(raw==null)return null;
        String kind=raw.optString("kind");if(!kind.matches("select|checkbox|radio|text|date|contenteditable"))throw new IllegalArgumentException("未知网页控件状态");
        JSONObject out=new JSONObject().put("kind",kind);
        for(String key:new String[]{"disabled","readOnly"})out.put(key,bool(raw,key));
        if(kind.matches("text|date|contenteditable"))return out;
        if(!kind.equals("select"))return out.put("checked",bool(raw,"checked")).put("indeterminate",bool(raw,"indeterminate"));
        out.put("multiple",bool(raw,"multiple"));int total=integer(raw,"optionsCount",0,1_000_000),selected=integer(raw,"selectedIndex",-1,Math.max(-1,total-1));
        out.put("selectedIndex",selected).put("optionsCount",total);JSONArray source=raw.getJSONArray("options"),options=new JSONArray();
        if(source.length()>MAX_OPTIONS)throw new IllegalArgumentException("下拉选项超出本次读取范围");
        java.util.Set<String> refs=new java.util.HashSet<>();
        for(int i=0;i<source.length();i++){
            JSONObject option=source.getJSONObject(i);String ref=option.getString("optionRef");int index=integer(option,"index",0,Math.max(0,total-1));
            if(!ref.equals("o"+index)||!refs.add(ref)||total==0)throw new IllegalArgumentException("下拉选项身份无效");
            options.put(new JSONObject().put("optionRef",ref).put("index",index).put("value",text(option,"value",256)).put("label",text(option,"label",160))
                    .put("disabled",bool(option,"disabled")).put("selected",bool(option,"selected")).put("complete",bool(option,"complete")));
        }
        return out.put("options",options).put("optionsOmitted",total-options.length());
    }

    /** Registering a ref is not showing it: hidden/cropped options never become action choices. */
    public static JSONObject presented(JSONObject nativeControl,JSONObject delivered)throws Exception {
        JSONObject full=project(nativeControl),shown=project(delivered);
        for(String key:new String[]{"kind","disabled","readOnly","multiple","selectedIndex","optionsCount","checked","indeterminate"})
            if(full.has(key)&&!String.valueOf(full.get(key)).equals(String.valueOf(shown.opt(key))))throw new SecurityException("呈现控件状态不是本次原生观察");
        if(!full.getString("kind").equals("select"))return full;
        JSONArray original=full.getJSONArray("options"),kept=new JSONArray(),visible=shown.getJSONArray("options");
        for(int i=0;i<visible.length();i++){
            JSONObject row=visible.getJSONObject(i),found=null;for(int j=0;j<original.length();j++)if(row.getString("optionRef").equals(original.getJSONObject(j).getString("optionRef"))){found=original.getJSONObject(j);break;}
            if(found==null||!sameOption(found,row))throw new SecurityException("未展示的下拉选项不可获得动作权限");kept.put(found);
        }
        return full.put("options",kept).put("optionsOmitted",full.getInt("optionsCount")-kept.length());
    }
    private static boolean sameOption(JSONObject a,JSONObject b)throws Exception {
        for(String key:new String[]{"optionRef","index","value","label","disabled","selected","complete"})if(!String.valueOf(a.get(key)).equals(String.valueOf(b.get(key))))return false;return true;
    }

    /** Called before original approval, and again at dispatch; cannot accept model-supplied native pins. */
    public static JSONObject requireTarget(JSONObject preview,JSONObject args)throws Exception {
        JSONObject control=project(preview.optJSONObject("control"));if(control==null)throw new BrowserTaskSpace.StalePage("请先普通observe读取此表单控件与可选项");
        if(control.getBoolean("disabled")||control.getBoolean("readOnly")||preview.optBoolean("ownerRequired"))throw new OwnerRequired("此控件当前不可写，请主人在原页处理");
        if(args.has("optionRef")){
            if(!control.getString("kind").equals("select")||control.getBoolean("multiple"))throw new OwnerRequired("本工具仅选择原生单选下拉；多选或定制组件请主人处理");
            String ref=args.getString("optionRef");JSONArray options=control.getJSONArray("options");
            for(int i=0;i<options.length();i++){JSONObject row=options.getJSONObject(i);if(ref.equals(row.getString("optionRef"))){if(!row.getBoolean("complete")||row.getBoolean("disabled"))throw new OwnerRequired("该选项被禁用或仅显示了摘要，请主人核对原页");return row;}}
            throw new BrowserTaskSpace.StalePage("该选项未在本次观察中实际展示，不能猜测隐藏选项");
        }
        if(!(args.opt("checked") instanceof Boolean)||!control.getString("kind").matches("checkbox|radio"))throw new IllegalArgumentException("需要本次观察中的checkbox/radio与明确布尔目标");
        if(control.getBoolean("indeterminate"))throw new OwnerRequired("混合状态控件请主人处理，未盲目切换");
        if(control.getString("kind").equals("radio")&&!args.getBoolean("checked")&&control.getBoolean("checked"))throw new OwnerRequired("不能直接取消已选单选按钮；请明确选择同组另一项");
        return control;
    }

    static String guard(String command,JSONObject args,JSONObject preview)throws Exception {
        if(preview==null)throw new SecurityException("表单动作需要原生固定的已展示目标");JSONObject choice=requireTarget(preview,args),c=project(preview.getJSONObject("control"));
        String guard="if(e.matches(':disabled')||e.readOnly||e.getAttribute('aria-readonly')==='true')return {accepted:false,error:'element_readonly_or_disabled'};const style=getComputedStyle(e);if(style.visibility==='hidden'||style.visibility==='collapse'||style.display==='none'||style.pointerEvents==='none'||Number(style.opacity)===0)return {accepted:false,error:'element_not_visible'};";
        if(command.equals("select_option"))return guard+"if(!(e instanceof HTMLSelectElement)||e.multiple||e.selectedIndex!=="+c.getInt("selectedIndex")+"||e.options.length!=="+c.getInt("optionsCount")+")return {accepted:false,error:'form_state_changed'};const optionIndex="+choice.getInt("index")+",option=e.options[optionIndex];if(!option||!state.optionNodes||!state.optionNodes["+Integer.parseInt(args.getString("ref").substring(1))+"]||state.optionNodes["+Integer.parseInt(args.getString("ref").substring(1))+"][optionIndex]!==option||option.value!=="+JSONObject.quote(choice.getString("value"))+"||option.label!=="+JSONObject.quote(choice.getString("label"))+"||option.disabled||option.hidden||(option.parentElement&&option.parentElement.tagName==='OPTGROUP'&&option.parentElement.disabled))return {accepted:false,error:'option_changed'};";
        return guard+"if(!(e instanceof HTMLInputElement)||e.type!=="+JSONObject.quote(c.getString("kind"))+"||e.checked!=="+c.getBoolean("checked")+"||e.indeterminate!=="+c.getBoolean("indeterminate")+")return {accepted:false,error:'form_state_changed'};";
    }
    static String action(String command,JSONObject args,String readbackToken)throws Exception {
        if(readbackToken==null||readbackToken.isEmpty())throw new SecurityException("需要原生表单回查身份");
        String desired=command.equals("select_option")?"optionIndex":String.valueOf(args.getBoolean("checked"));
        String current=command.equals("select_option")?"e.selectedIndex":"e.checked";
        String effect=command.equals("select_option")?"const setter=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'selectedIndex').set;setter.call(e,optionIndex);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));":"e.click();";
        return "const noOp="+current+"==="+desired+";state.formReadback={token:"+JSONObject.quote(readbackToken)+",node:e,kind:"+JSONObject.quote(command)+",desired:"+desired+",option:"+(command.equals("select_option")?"option":"null")+",value:"+(command.equals("select_option")?"option.value":"null")+",controlType:e.type};if(!noOp){"+effect+"}";
    }
    public static String readbackScript(String pageKey,String observationId,String expectedUrl,String readbackToken) {
        return "JSON.stringify((()=>{const state=window["+JSONObject.quote(pageKey)+"];if(location.href!=="+JSONObject.quote(expectedUrl)+"||!state||state.id!=="+JSONObject.quote(observationId)+"||!state.formReadback||state.formReadback.token!=="+JSONObject.quote(readbackToken)+")return {status:'unavailable',code:'form_document_changed'};const p=state.formReadback,e=p.node;if(!e||!e.isConnected||e.ownerDocument!==document)return {status:'unavailable',code:'form_element_changed'};if(p.kind==='select_option'){if(!(e instanceof HTMLSelectElement)||e.multiple||e.options[p.desired]!==p.option)return {status:'unavailable',code:'form_option_changed'};const value=String(e.value);let end=Math.min(value.length,256);if(end&&end<value.length&&value.charCodeAt(end-1)>=0xd800&&value.charCodeAt(end-1)<=0xdbff)end--;return {status:'observed',kind:'select',selectedIndex:e.selectedIndex,value:value.slice(0,end),valueTruncated:value.length>256,matched:e.selectedIndex===p.desired&&value===p.value}}if(!(e instanceof HTMLInputElement)||!['checkbox','radio'].includes(e.type)||e.type!==p.controlType)return {status:'unavailable',code:'form_element_changed'};return {status:'observed',kind:e.type,checked:!!e.checked,indeterminate:!!e.indeterminate,matched:e.checked===p.desired&&!e.indeterminate};})())";
    }
    public static JSONObject readback(JSONObject dispatch,JSONObject probe,JSONObject args)throws Exception {
        if(!Boolean.TRUE.equals(dispatch.opt("accepted"))||!(dispatch.opt("noOp") instanceof Boolean))throw new IllegalArgumentException("表单原派发回执缺少明确状态");
        JSONObject out=new JSONObject(dispatch.toString()),evidence=new JSONObject().put("readback","same_node_after_100ms_not_stability_or_server_receipt").put("trust","page_world_untrusted").put("businessSuccess",false);
        if(!probe.optString("status").equals("observed")||!(probe.opt("matched") instanceof Boolean))return out.put("accepted",JSONObject.NULL).put("status","unknown").put("code","form_readback_unknown").put("controlEvidence",evidence.put("state","unknown")).put("message","已处理一次控件操作，但未能核对原控件状态；先查看原页，不要重复操作。");
        JSONObject approved=args.getJSONObject("_nativeTargetPreview"),target=project(approved.getJSONObject("control"));
        String kind=probe.getString("kind");if(!kind.equals(target.getString("kind")))throw new IllegalArgumentException("控件回查类型无效");
        JSONObject actual=new JSONObject().put("kind",kind);boolean matched=probe.getBoolean("matched");
        if(kind.equals("select")){JSONObject choice=requireTarget(approved,args);int selected=integer(probe,"selectedIndex",-1,1_000_000);String value=text(probe,"value",256);boolean truncated=bool(probe,"valueTruncated");if(matched!=(selected==choice.getInt("index")&&!truncated&&value.equals(choice.getString("value"))))throw new IllegalArgumentException("下拉回查目标不一致");actual.put("selectedIndex",selected).put("value",value).put("valueTruncated",truncated);evidence.put("desiredOptionRef",args.getString("optionRef"));}
        else {actual.put("checked",bool(probe,"checked")).put("indeterminate",bool(probe,"indeterminate"));if(!args.has("checked")||matched!=(probe.getBoolean("checked")==args.getBoolean("checked")&&!probe.getBoolean("indeterminate")))throw new IllegalArgumentException("控件回查目标不一致");evidence.put("desiredChecked",args.getBoolean("checked"));}
        return out.put("status",matched?(out.optBoolean("noOp")?"no_op":"form_state_read_back"):"failed").put("code",matched?"form_target_state_met":"form_target_state_unmet")
                .put("controlEvidence",evidence.put("state",matched?"met":"unmet").put("actual",actual)).put("message",matched?(out.optBoolean("noOp")?"控件原本已是目标状态，没有再次触发操作。":"已回读到目标控件状态；未额外点击提交；页面自身可能触发联动，服务器保存状态未核实。") : "已操作一次，但回读的控件状态未达到目标；没有重试，请检查原页或请主人处理。");
    }
    private static boolean bool(JSONObject value,String key)throws Exception {Object raw=value.get(key);if(!(raw instanceof Boolean))throw new IllegalArgumentException("网页控件布尔状态无效");return (Boolean)raw;}
    private static int integer(JSONObject value,String key,int min,int max)throws Exception {Object raw=value.get(key);if(!(raw instanceof Number)||((Number)raw).doubleValue()!=Math.rint(((Number)raw).doubleValue())||((Number)raw).doubleValue()<min||((Number)raw).doubleValue()>max)throw new IllegalArgumentException("网页控件索引无效");return ((Number)raw).intValue();}
    private static String text(JSONObject value,String key,int max)throws Exception {Object raw=value.get(key);if(!(raw instanceof String)||((String)raw).length()>max)throw new IllegalArgumentException("网页控件文字超出范围");return (String)raw;}
}
