package dev.pocketengine.browser;

import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A finite plan for the original Runner. This class never reads a page or dispatches a tool. */
public final class BrowserToolSequence {
    public static final String TOOL="browser_task_prepare_sequence";
    private BrowserToolSequence() { }
    private static JSONObject text(int length)throws Exception{return new JSONObject().put("type","string").put("maxLength",length);}
    public static JSONObject schema()throws Exception {
        JSONObject step=new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("action"))
                .put("properties",new JSONObject().put("action",text(12).put("enum",new JSONArray().put("observe").put("click").put("type").put("scroll").put("wait"))).put("ref",text(4)).put("text",text(4000))
                        .put("dy",new JSONObject().put("type","integer").put("minimum",-3000).put("maximum",3000)).put("condition",text(8).put("enum",new JSONArray().put("load").put("text"))).put("value",text(2048))
                        .put("timeoutMs",new JSONObject().put("type","integer").put("minimum",100).put("maximum",6000)));
        // The original PluginRules schema subset supports maxItems; validate() enforces the nonempty floor.
        return new JSONObject().put("type","array").put("maxItems",4).put("items",step);
    }
    public static void validate(JSONObject args)throws Exception {
        JSONArray steps=args.getJSONArray("steps");if(steps.length()<1||steps.length()>4)throw new IllegalArgumentException("浏览组合仅支持1至4个原工具步骤");boolean observed=false;
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i);String action=step.getString("action");Set<String> fields=switch(action){case "observe"->Set.of("action");case "click"->Set.of("action","ref");case "type"->Set.of("action","ref","text");case "scroll"->Set.of("action","dy");case "wait"->step.optString("condition").equals("load")?Set.of("action","condition","timeoutMs"):Set.of("action","condition","timeoutMs","value");default->throw new IllegalArgumentException("不支持的组合步骤");};
            for(var keys=step.keys();keys.hasNext();)if(!fields.contains(keys.next()))throw new IllegalArgumentException("组合步骤含额外字段");
            if(Set.of("click","type","scroll").contains(action)){if(!observed)throw new IllegalArgumentException("每个组合写动作前必须安排一次原observe");observed=false;}
            if(action.equals("observe"))observed=true;
            if(Set.of("click","type").contains(action)&&!step.getString("ref").matches("n[0-9]{1,3}"))throw new IllegalArgumentException("组合目标必须来自准备时实际已展示的ref");
            if(action.equals("type"))BrowserTextControls.validate("type",step);
            if(action.equals("scroll")&&(!(step.get("dy") instanceof Number)||step.getDouble("dy")!=step.getInt("dy")||Math.abs(step.getInt("dy"))>3000))throw new IllegalArgumentException("组合滚动距离无效");
            if(action.equals("wait")){if(!Set.of("load","text").contains(step.getString("condition"))||step.getString("condition").equals("text")&&(step.getString("value").isEmpty()||step.getString("value").length()>2048))throw new IllegalArgumentException("组合等待只支持原文档load/text条件");if(step.has("timeoutMs")&&(!(step.get("timeoutMs") instanceof Number)||step.getDouble("timeoutMs")!=step.getInt("timeoutMs")||step.getInt("timeoutMs")<100||step.getInt("timeoutMs")>6000))throw new IllegalArgumentException("组合等待时限无效");}
        }
    }
    private static JSONObject target(JSONObject observed,String ref)throws Exception {
        JSONObject found=null;JSONArray rows=observed.getJSONArray("elementPreviews");for(int i=0;i<rows.length();i++)if(ref.equals(rows.getJSONObject(i).getString("ref"))){if(found!=null)throw new SecurityException("组合目标引用不唯一");found=rows.getJSONObject(i);}
        if(found==null||!found.optString("nodeKey").matches("k[1-9][0-9]{0,6}")||found.optBoolean("ownerRequired"))throw new SecurityException("组合目标未实际展示、没有原节点身份或需要主人处理");return new JSONObject(found.toString());
    }
    public static JSONArray pinTargets(JSONObject args,JSONObject presented)throws Exception {
        validate(args);if(presented.optBoolean("readOnlyEvidence")||!presented.optBoolean("runnerDelivered")||!presented.has("presentedElementCount")||presented.optJSONObject("actionFrame")!=null)throw new SecurityException("浏览组合首版只支持原主文档实际展示的普通观察");
        JSONArray out=new JSONArray(),steps=args.getJSONArray("steps");for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(step.has("ref"))out.put(new JSONObject().put("step",i).put("target",target(presented,step.getString("ref"))));}return out;
    }
    private static JSONObject targetIdentity(JSONObject target)throws Exception {JSONObject out=new JSONObject();for(String key:new String[]{"nodeKey","tag","type","role","id","name","text","href","formAction","formMethod","submitterFormAction"})out.put(key,target.optString(key));return out;}
    public static NativeToolPlan.BrowserIdentity identity(JSONObject pin)throws Exception {
        return new NativeToolPlan.BrowserIdentity(pin.getString("taskId"),pin.getString("runId"),pin.getInt("epoch"),pin.getString("profileId"),pin.getLong("profileRevision"),pin.getString("browserSessionId"),pin.getString("browserSpaceId"),pin.getString("tabId"),pin.getLong("tabGeneration"),pin.getString("documentId"),pin.getLong("navigation"));
    }
    /** Resolve only an original target against a newly delivered native observation, with no relocation fallback. */
    public static NativeToolPlan.Resolved resolve(JSONObject args,JSONObject originalPin,int index,JSONObject presented,RunControl.AuthorityCheck current)throws Exception {
        JSONObject step=args.getJSONArray("steps").getJSONObject(index),call=new JSONObject().put("tabId",args.getString("tabId"));String action=step.getString("action");NativeToolPlan.Tool tool;
        if(action.equals("observe"))return new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_OBSERVE,call,current);
        if(action.equals("wait")){call.put("condition",step.getString("condition"));for(String key:new String[]{"value","timeoutMs"})if(step.has(key))call.put(key,step.get(key));return new NativeToolPlan.Resolved(NativeToolPlan.Tool.BROWSER_WAIT,call,current);}
        if(presented==null||presented.optBoolean("readOnlyEvidence")||!presented.optBoolean("runnerDelivered")||!presented.has("presentedElementCount")||presented.optJSONObject("actionFrame")!=null)throw new SecurityException("组合动作没有刚完成的普通主文档观察");
        if(originalPin.getLong("navigation")!=presented.getLong("navigation"))throw new SecurityException("组合文档已导航，剩余步骤未派发");
        call.put("observationId",presented.getString("observationId"));
        if(action.equals("scroll")){tool=NativeToolPlan.Tool.BROWSER_SCROLL;call.put("dy",step.getInt("dy"));}
        else {
            JSONObject initial=null;JSONArray targets=originalPin.getJSONArray("sequenceTargets");for(int i=0;i<targets.length();i++)if(targets.getJSONObject(i).getInt("step")==index)initial=targets.getJSONObject(i).getJSONObject("target");if(initial==null)throw new SecurityException("组合缺少原审批目标");
            JSONObject actual=null;JSONArray rows=presented.getJSONArray("elementPreviews");for(int i=0;i<rows.length();i++){JSONObject candidate=rows.getJSONObject(i);if(initial.getString("nodeKey").equals(candidate.optString("nodeKey"))){if(actual!=null)throw new SecurityException("组合原节点不唯一");actual=candidate;}}
            if(actual==null||!targetIdentity(initial).toString().equals(targetIdentity(actual).toString())||actual.optBoolean("ownerRequired"))throw new SecurityException("组合原节点已替换或身份变化，不按同名目标补跑");
            call.put("ref",actual.getString("ref"));tool=action.equals("click")?NativeToolPlan.Tool.BROWSER_CLICK:NativeToolPlan.Tool.BROWSER_TYPE;if(action.equals("type"))call.put("text",step.getString("text"));
        }
        JSONObject evidence=new JSONObject().put("tabId",args.getString("tabId")).put("observationId",presented.getString("observationId")).put("url",presented.getString("url")).put("text",presented.optString("text")).put("elements",new JSONArray(presented.getJSONArray("elementPreviews").toString())).put("untrustedPageContent",true).put("kind","browser_sequence_original_delivered_observation");
        return new NativeToolPlan.Resolved(tool,call,evidence,current);
    }
}
