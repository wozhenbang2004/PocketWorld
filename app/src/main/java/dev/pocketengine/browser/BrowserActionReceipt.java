package dev.pocketengine.browser;

import org.json.JSONArray;
import org.json.JSONObject;

/** Describes evidence from one existing dispatch. It cannot authorize, retry, or certify business success. */
public final class BrowserActionReceipt {
    private BrowserActionReceipt() { }
    public static JSONObject attach(String command,JSONObject args,JSONObject before,JSONObject after,boolean entered,JSONObject raw)throws Exception {
        JSONObject receipt=new JSONObject(raw.toString()),dom=receipt.optJSONObject("domEvidence");
        Object accepted=receipt.opt("accepted");
        String dispatch=Boolean.FALSE.equals(accepted)?"not_dispatched":Boolean.TRUE.equals(accepted)?"confirmed":"unknown";
        if(Boolean.TRUE.equals(accepted)&&(BrowserFormControls.command(command)||BrowserTextControls.command(command)||command.matches("site_mode|resize|back|forward|reload"))&&receipt.optBoolean("noOp"))dispatch="not_needed";
        if("unknown".equals(receipt.optString("status")))dispatch="unknown";
        String mechanism;
        switch(command){case "click":mechanism="synthetic_dom_click";break;case "type":mechanism="dom_value_setter_and_synthetic_events";break;
            case "focus":mechanism="dom_focus";break;case "press":mechanism="synthetic_dom_keyboard";break;case "set_date":mechanism="dom_date_setter_and_synthetic_events";break;case "select_option":mechanism="dom_select_setter_and_synthetic_events";break;case "set_checked":mechanism="synthetic_dom_checked_target";break;
            case "scroll":mechanism="dom_scroll_by";break;case "open":case "navigate":case "back":case "forward":case "reload":mechanism="webview_navigation_request";break;
            case "native_input":mechanism="android_webview_input";break;case "site_mode":mechanism="webview_user_agent_and_wide_viewport";break;case "resize":mechanism="actual_webview_layout";break;
            case "network":mechanism="webview_cdp_local_socket".equals(receipt.optString("backend"))?"native_cdp_metadata_observation":"page_world_network_witness";break;case "network_body":mechanism="native_cdp_original_response_read";break;
            case "download":mechanism="native_download_request";break;case "download_document":mechanism="original_document_blob_data_stream";break;case "upload_artifact":mechanism="native_exact_artifact_file_chooser";break;case "read_public":mechanism="anonymous_public_http_get";break;case "observe":case "wait":mechanism="page_read";break;case "unknown":mechanism="unknown_original_mechanism";break;default:mechanism="native_page_lifecycle";}
        JSONArray signals=new JSONArray();
        if(dom!=null)for(String key:new String[]{"urlChanged","textChanged","scrollChanged","valueChanged","checkedChanged","selectedIndexChanged"})if(dom.opt(key) instanceof Boolean&&dom.getBoolean(key))signals.put(key);
        String effect="not_observed";
        if(dispatch.equals("not_dispatched")||dispatch.equals("not_needed"))effect="not_applicable";
        else if(dom!=null)effect=signals.length()>0?"changed_unattributable":"no_observable_change";
        JSONObject expected=args==null?null:args.optJSONObject("postcondition");String post="not_checked";
        if(expected!=null&&(dispatch.equals("confirmed")||dispatch.equals("not_needed"))&&dom!=null&&expected.optString("kind").equals(dom.optString("postconditionKind"))
                &&dom.optString("postcondition").matches("met|unmet"))post=dom.getString("postcondition");
        JSONObject detail=new JSONObject().put("schemaVersion",1).put("mechanism",mechanism)
                .put("dispatch",new JSONObject().put("state",dispatch).put("nativeEntryObserved",entered)
                        .put("trustedInputEvent",command.equals("native_input")?JSONObject.NULL:false).put("inputReachability",command.equals("native_input")?"android_view_dispatch_with_separate_untrusted_page_event_witness":mechanism.startsWith("synthetic")||command.equals("type")||BrowserFormControls.command(command)?"not_independently_verified":"not_applicable"))
                .put("effect",new JSONObject().put("state",effect).put("signals",signals).put("causality","not_established")
                        .put("coverage",dom==null?"unavailable":"immediate_top_level_url_first_12000_text_chars_scroll_target_value"))
                .put("postcondition",new JSONObject().put("state",post).put("kind",expected==null?JSONObject.NULL:expected.optString("kind"))
                        .put("evidenceTrust","page_world_untrusted").put("businessSuccess",false))
                .put("before",identity(before)).put("after",identity(after))
                .put("navigation",new JSONObject().put("state",before==null||after==null?"unknown":before.optLong("navigation",-1)!=after.optLong("navigation",-1)?"navigation_epoch_changed":!before.optString("pageIdentityDigest").equals(after.optString("pageIdentityDigest"))?"same_document_url_changed":"same_navigation_epoch")
                        .put("domDocumentIdentity","unavailable"))
                .put("newTab",new JSONObject().put("state","not_instrumented")).put("downloads",new JSONObject().put("state",command.matches("download|download_document")?"native_request_receipt_only":"not_instrumented"))
                .put("freshObservationRequired",command.matches("click|type|set_date|focus|press|select_option|set_checked|scroll|open|navigate|native_input|site_mode|resize|back|forward|reload|upload_artifact"))
                .put("automaticReplayAllowed",false).put("verifiedBusinessSuccess",false);
        if(receipt.optString("message").isEmpty()){
            String message=dispatch.equals("unknown")?"网页操作是否发生尚未确认；先核对原页面，不要重复提交。"
                    :dispatch.equals("not_dispatched")?"本次网页动作未提交；请按拒绝原因重新观察，必要时请主人处理。"
                    :post.equals("unmet")?"已提交一次网页动作，但声明的页面条件尚未满足；先读取原页面，不要直接重发。"
                    :post.equals("met")?"已提交一次网页动作，声明的即时页面条件已满足；这还不证明业务完成。"
                    :effect.equals("no_observable_change")?"已提交一次网页动作，暂未观察到所覆盖范围变化；不代表未执行，请先回读而非重发。"
                    :"已收到网页请求回执；变化不证明业务成功，请读取原页面核对。";
            receipt.put("message",message);
        }
        receipt.remove("domEvidence");return receipt.put("actionReceipt",detail).put("verified",false).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
    }
    private static JSONObject identity(JSONObject raw)throws Exception {
        JSONObject out=new JSONObject().put("available",raw!=null).put("documentIdentityKind","native_top_level_navigation_epoch_only");
        for(String key:new String[]{"taskId","runId","epoch","profileId","profileRevision","browserSessionId","browserSpaceId","tabId","tabGeneration","navigation","pageIdentityDigest","observationId"})
            out.put(key,raw!=null&&raw.has(key)?raw.get(key):JSONObject.NULL);
        return out;
    }
    public static JSONObject postconditionSchema()throws Exception {
        return new JSONObject().put("type","object").put("additionalProperties",false)
                .put("properties",new JSONObject().put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("url").put("text").put("value")))
                        .put("value",new JSONObject().put("type","string").put("maxLength",2048)))
                .put("required",new JSONArray().put("kind").put("value"));
    }
    public static void validatePostcondition(JSONObject args,String command)throws Exception {
        if(!args.has("postcondition"))return;JSONObject expected=args.optJSONObject("postcondition");
        if(expected==null||expected.length()!=2||!(expected.opt("kind") instanceof String)||!(expected.opt("value") instanceof String)
                ||expected.getString("value").length()>2048||!expected.optString("kind").equals("value")&&expected.optString("value").isEmpty()||!expected.getString("kind").matches("url|text|value")
                ||expected.getString("kind").equals("value")&&!command.equals("type"))throw new IllegalArgumentException("预期条件仅支持URL、页面文字或本次输入目标value");
        if(expected.getString("kind").equals("url")){java.net.URI url=new java.net.URI(expected.getString("value"));if(url.getHost()==null||url.getUserInfo()!=null||!java.util.Set.of("http","https").contains(url.getScheme()))throw new IllegalArgumentException("预期URL无效");}
    }
}
