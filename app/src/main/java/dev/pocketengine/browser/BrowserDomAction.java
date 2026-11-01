package dev.pocketengine.browser;

import org.json.JSONObject;

/** Builds only the existing single page-world action. No native-input fallback or action retry. */
public final class BrowserDomAction {
    private BrowserDomAction() { }
    public static String script(String command,JSONObject args,String expectedUrl,String observationId,String pageKey)throws Exception {
        return script(command,args,expectedUrl,observationId,pageKey,null);
    }
    public static String script(String command,JSONObject args,String expectedUrl,String observationId,String pageKey,String formReadbackToken)throws Exception {
        BrowserActionReceipt.validatePostcondition(args,command);
        String prefix="if(location.href!=="+JSONObject.quote(expectedUrl)+")return {accepted:false,error:'page_changed'};const state=window["+JSONObject.quote(pageKey)+"];if(!state||state.id!=="+JSONObject.quote(observationId)+"||state.dirty||state.observer.takeRecords().length)return {accepted:false,error:'observation_changed'};";
        String target="",action;
        if(command.equals("scroll")){
            Object raw=args.opt("dy");if(!(raw instanceof Number)||((Number)raw).doubleValue()!=Math.rint(((Number)raw).doubleValue())||Math.abs(((Number)raw).doubleValue())>3000)throw new IllegalArgumentException("滚动距离无效");
            action="window.scrollBy(0,"+args.getInt("dy")+");";
        }else{
            String ref=args.optString("ref");if(!ref.matches("n[0-9]{1,3}"))throw new IllegalArgumentException("需要当前页面ref");
            target="const e=state.nodes["+Integer.parseInt(ref.substring(1))+"];if(!e||!e.isConnected||e.ownerDocument!==document)return {accepted:false,error:'element_changed'};if(e.disabled||e.getAttribute('aria-disabled')==='true')return {accepted:false,error:'element_disabled'};const rect=e.getBoundingClientRect();if(!e.getClientRects().length||rect.bottom<=0||rect.right<=0||rect.top>=innerHeight||rect.left>=innerWidth)return {accepted:false,error:'element_not_visible'};const x=Math.max(0,Math.min(innerWidth-1,(rect.left+rect.right)/2)),y=Math.max(0,Math.min(innerHeight-1,(rect.top+rect.bottom)/2));const hit=document.elementFromPoint(x,y);if(!hit||!(hit===e||e.contains(hit)))return {accepted:false,error:'element_occluded'};if(e.type==='password'||e.type==='file'||e.autocomplete==='one-time-code')return {accepted:false,error:'owner_input_required'};";
            JSONObject approved=args.optJSONObject("_nativeTargetPreview");
            if(approved!=null){
                if(!ref.equals(approved.optString("ref")))throw new IllegalArgumentException("原生目标pin不属于本次ref");
                for(String field:new String[]{"href","formAction","formMethod","submitterFormAction"}){
                    String current=field.equals("href")?"(e.tagName==='A'?e.href:'')":field.equals("formAction")?"(e.form?e.form.action:'')":field.equals("formMethod")?"(e.form?e.form.method:'')":"(e.hasAttribute('formaction')?e.formAction:'')";
                    target+="if("+current+"!=="+JSONObject.quote(approved.optString(field))+ ")return {accepted:false,error:'destination_changed'};";
                }
            }
            if(command.equals("click"))action="e.click();";
            else if(command.equals("type")){
                if(!(args.opt("text") instanceof String)||args.getString("text").length()>12000)throw new IllegalArgumentException("输入文字无效");
                target+="if(e.readOnly)return {accepted:false,error:'element_readonly'};if(!(e instanceof HTMLTextAreaElement)&&(!(e instanceof HTMLInputElement)||!['text','search','email','url','tel','number'].includes(e.type)))return {accepted:false,error:'not_editable'};";
                action="const setter=Object.getOwnPropertyDescriptor(e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;setter.call(e,"+JSONObject.quote(args.getString("text"))+");e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));";
            }else if(BrowserFormControls.command(command)){
                target+=BrowserFormControls.guard(command,args,approved);
                action=BrowserFormControls.action(command,args,formReadbackToken);
            }else throw new IllegalArgumentException("未知DOM动作");
        }
        boolean form=BrowserFormControls.command(command);
        String before="const before={url:location.href,text:(document.body?document.body.innerText:'').slice(0,12000),x:scrollX,y:scrollY,value:"+(command.equals("type")||command.equals("select_option")?"e.value":"null")+",checked:"+(command.equals("set_checked")?"e.checked":"null")+",selectedIndex:"+(command.equals("select_option")?"e.selectedIndex":"null")+"};state.observer.disconnect();";
        JSONObject expected=args.optJSONObject("postcondition");String condition="'not_checked'",kind="''";
        if(expected!=null){kind=JSONObject.quote(expected.getString("kind"));String value=JSONObject.quote(expected.getString("value"));
            String test=expected.getString("kind").equals("url")?"location.href==="+value:expected.getString("kind").equals("text")?"(document.body?document.body.innerText:'').includes("+value+")":"e.value==="+value;
            condition="("+test+"?'met':'unmet')";
        }
        return "JSON.stringify((()=>{"+prefix+target+before+action+"return {accepted:true,noOp:"+(form?"noOp":"false")+",domEvidence:{urlChanged:before.url!==location.href,textChanged:before.text!==(document.body?document.body.innerText:'').slice(0,12000),scrollChanged:before.x!==scrollX||before.y!==scrollY,valueChanged:"+(command.equals("type")||command.equals("select_option")?"before.value!==e.value":"false")+",checkedChanged:"+(command.equals("set_checked")?"before.checked!==e.checked":"false")+",selectedIndexChanged:"+(command.equals("select_option")?"before.selectedIndex!==e.selectedIndex":"false")+",postconditionKind:"+kind+",postcondition:"+condition+"}};})())";
    }
}
