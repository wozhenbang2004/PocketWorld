package dev.pocketengine.browser;

import org.json.JSONObject;

/** The exact observed main-document file input, rechecked before its native chooser receives bytes. */
public final class BrowserArtifactInput {
    private BrowserArtifactInput() { }

    public static void requireTarget(JSONObject target) throws Exception {
        if (target == null || !target.optString("ref").matches("n[0-9]{1,3}")
                || !target.optString("nodeKey").matches("k[1-9][0-9]{0,6}")
                || !target.optString("tag").equals("INPUT") || !target.optString("type").equals("file"))
            throw new SecurityException("上传须使用普通观察中实际展示的原文件输入控件");
    }

    public static String script(String pageKey, String url, String observation, JSONObject target, String token, boolean open) throws Exception {
        requireTarget(target);BrowserTaskSpace.uuid(token);
        String prefix = "const key=" + JSONObject.quote(pageKey) + ",url=" + JSONObject.quote(url)
                + ",observation=" + JSONObject.quote(observation) + ",target=" + target.toString()
                + ",token=" + JSONObject.quote(token) + ",index=" + Integer.parseInt(target.getString("ref").substring(1)) + ";";
        String action = open
                ? "e.focus({preventScroll:true});if(document.activeElement!==e&&e.getRootNode().activeElement!==e)return fail('file_input_focus_unavailable');state.artifactChooser={token,element:e,accept:e.accept,multiple:e.multiple};return JSON.stringify({accepted:true,status:'file_input_ready',siteConfirmed:false});"
                : "const choice=state.artifactChooser;if(!choice||choice.token!==token||choice.element!==e||choice.accept!==e.accept||choice.multiple!==e.multiple)return fail('file_input_identity_changed');if(document.activeElement!==e&&e.getRootNode().activeElement!==e)return fail('file_input_focus_changed');return JSON.stringify({accepted:true,status:'same_file_input',siteConfirmed:false});";
        return "(()=>{" + prefix + GUARD + action + "})()";
    }

    private static final String GUARD = """
const fail=code=>JSON.stringify({accepted:false,status:'unavailable',code});
if(location.href!==url)return fail('upload_document_changed');
const state=window[key];if(!state||state.id!==observation||state.dirty||!state.observer||state.observer.takeRecords().length)return fail('upload_observation_changed');
const e=state.nodes[index];
if(!e||!e.isConnected||e.ownerDocument!==document||e.tagName!=='INPUT'||e.type!=='file'||!state.nodeKeys||state.nodeKeys.get(e)!==target.nodeKey)return fail('upload_input_changed');
if(e.disabled||e.getAttribute('aria-disabled')==='true'||e.closest('[data-private=true]')||!e.getClientRects().length)return fail('upload_input_unavailable');
for(const field of ['tag','type','role','id','name'])if(Object.prototype.hasOwnProperty.call(target,field)){
 const actual=field==='tag'?e.tagName:field==='role'?(e.getAttribute('role')||''):(e[field]||'');if(actual!==target[field])return fail('upload_target_identity_changed');
}
const form=e.form||e.closest('form');
if((form?form.action:'')!==(target.formAction||'')||(form?form.method:'')!==(target.formMethod||'')||(e.hasAttribute('formaction')?e.formAction:'')!==(target.submitterFormAction||''))return fail('upload_destination_changed');
const rect=e.getBoundingClientRect();if(rect.bottom<=0||rect.right<=0||rect.top>=innerHeight||rect.left>=innerWidth)return fail('upload_input_not_visible');
const x=Math.max(0,Math.min(innerWidth-1,(rect.left+rect.right)/2)),y=Math.max(0,Math.min(innerHeight-1,(rect.top+rect.bottom)/2));
const root=e.getRootNode(),hit=(root.elementFromPoint?root:document).elementFromPoint(x,y);if(!hit||!(hit===e||e.contains(hit)))return fail('upload_input_occluded');
""";
}
