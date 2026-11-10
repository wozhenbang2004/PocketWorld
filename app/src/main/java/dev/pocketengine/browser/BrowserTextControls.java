package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONObject;

/** Exact-node text/date/focus and finite synthetic keyboard operations, never trusted-input emulation. */
public final class BrowserTextControls {
    private BrowserTextControls() { }
    public static boolean command(String command){return Set.of("type","set_date","focus","press").contains(command);}
    public static void validate(String command,JSONObject args)throws Exception {
        if(command.equals("set_date")){String value=args.getString("date");if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("日期需为YYYY-MM-DD");java.time.LocalDate.parse(value);}
        if(command.equals("press")&&!Set.of("Enter","Escape","ArrowUp","ArrowDown","ArrowLeft","ArrowRight","Home","End").contains(args.getString("key")))throw new IllegalArgumentException("只接受已声明的单个按键，不接受快捷键串或脚本");
        if(command.equals("type")&&(!(args.opt("text") instanceof String)||args.getString("text").length()>4000))throw new IllegalArgumentException("输入文字无效");
    }
    static String guard(String command,JSONObject args)throws Exception {
        validate(command,args);
        String guard="if(e.matches(':disabled')||e.readOnly||e.getAttribute('aria-readonly')==='true')return {accepted:false,error:'element_readonly_or_disabled'};const style=getComputedStyle(e);if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||style.pointerEvents==='none'||Number(style.opacity)===0)return {accepted:false,error:'element_not_visible'};";
        if(command.equals("type"))guard+="if(!e.isContentEditable&&!(e instanceof HTMLTextAreaElement)&&(!(e instanceof HTMLInputElement)||!['text','search','email','url','tel','number'].includes(e.type)))return {accepted:false,error:'not_editable'};";
        if(command.equals("set_date"))guard+="if(!(e instanceof HTMLInputElement)||e.type!=='date')return {accepted:false,error:'not_date_input'};";
        if(command.equals("press"))guard+="if((e.getRootNode().activeElement||document.activeElement)!==e)return {accepted:false,error:'target_not_focused'};";
        return guard;
    }
    static String action(String command,JSONObject args,String token)throws Exception {
        validate(command,args);if(token==null)throw new SecurityException("输入需要原节点回读身份");
        String wanted=command.equals("type")?JSONObject.quote(args.getString("text")):command.equals("set_date")?JSONObject.quote(args.getString("date")):"null";
        String current="(e.isContentEditable?e.textContent:('value' in e?String(e.value):null))";
        String effect;
        if(command.equals("focus"))effect="e.focus({preventScroll:true});";
        else if(command.equals("press"))effect="e.dispatchEvent(new KeyboardEvent('keydown',{key:"+JSONObject.quote(args.getString("key"))+",bubbles:true,cancelable:true}));e.dispatchEvent(new KeyboardEvent('keyup',{key:"+JSONObject.quote(args.getString("key"))+",bubbles:true,cancelable:true}));";
        // Match input's shadow-boundary propagation without claiming a trusted native event.
        else effect="if(e.isContentEditable)e.textContent="+wanted+";else{const setter=Object.getOwnPropertyDescriptor(e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;setter.call(e,"+wanted+");}e.dispatchEvent(new Event('input',{bubbles:true,composed:true}));e.dispatchEvent(new Event('change',{bubbles:true}));";
        String noop=command.equals("focus")?"(e.getRootNode().activeElement||document.activeElement)===e":command.equals("press")?"false":current+"==="+wanted;
        return "const noOp="+noop+";state.textReadback={token:"+JSONObject.quote(token)+",node:e,kind:"+JSONObject.quote(command)+",desired:"+wanted+",controlType:e.type||'',editable:!!e.isContentEditable};if(!noOp){"+effect+"}";
    }
    public static String readbackScript(String pageKey,String observation,String url,String token){
        return "JSON.stringify((()=>{const s=window["+JSONObject.quote(pageKey)+"];if(location.href!=="+JSONObject.quote(url)+"||!s||s.id!=="+JSONObject.quote(observation)+"||!s.textReadback||s.textReadback.token!=="+JSONObject.quote(token)+")return {status:'unavailable',code:'original_document_changed'};const p=s.textReadback,e=p.node;if(!e||!e.isConnected||e.ownerDocument!==document||e.type!==undefined&&e.type!==p.controlType||!!e.isContentEditable!==p.editable||e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code')return {status:'unavailable',code:'original_element_changed'};const actual=e.isContentEditable?String(e.textContent):('value' in e?String(e.value):''),focused=(e.getRootNode().activeElement||document.activeElement)===e;let end=Math.min(4000,actual.length);if(end&&end<actual.length&&actual.charCodeAt(end-1)>=0xd800&&actual.charCodeAt(end-1)<=0xdbff)end--;return {status:'observed',kind:p.kind,value:actual.slice(0,end),valueLength:actual.length,valueTruncated:end<actual.length,focused,matched:p.kind==='focus'?focused:p.kind==='press'?null:actual===p.desired};})())";
    }
    public static JSONObject readback(JSONObject dispatch,JSONObject probe,JSONObject args)throws Exception {
        JSONObject out=new JSONObject(dispatch.toString());
        if(!probe.optString("status").equals("observed"))return out.put("accepted",JSONObject.NULL).put("status","unknown").put("code","text_readback_unknown").put("automaticReplayAllowed",false).put("message","已派发一次，但原输入节点延迟回读不可核对；请核对原页，不要重发。");
        String kind=probe.getString("kind");if(!command(kind)||!(probe.get("value") instanceof String)||probe.getString("value").length()>4000||!(probe.get("focused") instanceof Boolean)||!(probe.get("valueTruncated") instanceof Boolean))throw new SecurityException("输入回读证据无效");
        boolean matched=kind.equals("focus")?probe.getBoolean("focused"):!kind.equals("press")&&!probe.getBoolean("valueTruncated")&&probe.getLong("valueLength")==probe.getString("value").length()&&probe.getString("value").equals(args.getString(kind.equals("set_date")?"date":"text"));
        if(!kind.equals("press")&&(!(probe.get("matched") instanceof Boolean)||probe.getBoolean("matched")!=matched))throw new SecurityException("输入值回读与证据矛盾");
        JSONObject evidence=new JSONObject().put("readback","same_original_node_after_600ms").put("actual",probe).put("trustedInput",false).put("businessSuccess",false);
        return out.put("status",kind.equals("press")?"synthetic_key_dispatched":matched?"form_state_read_back":"failed").put("code",kind.equals("press")?"synthetic_key_effect_not_guaranteed":matched?"form_target_state_met":"form_target_state_unmet").put("controlEvidence",evidence).put("automaticReplayAllowed",false).put("message",kind.equals("press")?"已向原聚焦节点派发一次合成按键并回读实际值；不保证站点响应，要求可信输入时请主人处理。":matched?"原字段延迟回读达到目标；不证明网站已保存。":"原字段延迟值未达到目标，没有再次输入；请核对原页。");
    }
}

