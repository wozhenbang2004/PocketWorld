package dev.pocketengine.phone.observation;

import dev.pocketengine.core.NativeToolPlan;
import java.util.Set;
import org.json.JSONObject;

/** Run-local intent and observation pins. Only the original Runner can dispatch the returned single steps. */
public final class PhoneBoundedOperation {
    public static final String FIND="phone_find",WHEEL="phone_prepare_wheel";
    public static final int MAX_STEPS=30;
    public static final long TTL_MS=180_000;
    private PhoneBoundedOperation() { }
    public static boolean supports(String tool){return FIND.equals(tool)||WHEEL.equals(tool);}

    public static void validate(String tool,JSONObject args)throws Exception {
        if(!supports(tool))throw new IllegalArgumentException("bounded_phone_tool_unknown");
        PhoneTreeExtract.keys(args,FIND.equals(tool)?new String[]{"observationId","containerId","selector","direction","maxScrolls"}
                :new String[]{"observationId","nodeId","value","direction","maxSteps"});
        PhoneTreeExtract.text(args,"observationId",100);
        PhoneTreeExtract.text(args,FIND.equals(tool)?"containerId":"nodeId",100);
        if(!Set.of("forward","backward").contains(PhoneTreeExtract.text(args,"direction",10)))throw new IllegalArgumentException("bounded_phone_direction_invalid");
        PhoneTreeExtract.integer(args,FIND.equals(tool)?"maxScrolls":"maxSteps",12,1,MAX_STEPS);
        if(FIND.equals(tool)){
            JSONObject selector=args.getJSONObject("selector");PhoneTreeExtract.selector(selector);
            if(selector.length()==0)throw new IllegalArgumentException("find_selector_required");
        }else PhoneTreeExtract.text(args,"value",100);
    }

    public static final class State {
        public final String tool,initialObservation;
        private final String scope,packageName,structure,direction,target;
        private final JSONObject selector;
        private final int window,maxSteps;
        private final String path;
        private final long created,pause;
        private String previous="";
        private boolean started,typed;

        private State(String tool,JSONObject args,JSONObject actual,long now)throws Exception {
            validate(tool,args);this.tool=tool;initialObservation=actual.getString("observationId");
            if(!initialObservation.equals(args.getString("observationId")))throw new SecurityException("bounded_phone_start_changed");
            JSONObject node=PhoneTreeExtract.index(actual).get(args.getString(FIND.equals(tool)?"containerId":"nodeId"));
            if(node==null||node.optBoolean("password")||!node.optBoolean("visible",true)||!node.optBoolean("enabled",true)
                    ||!node.optBoolean("scrollable")&&!node.optBoolean("editable"))throw new SecurityException("bounded_phone_target_unavailable");
            structure=node.optString("structuralFingerprint");if(structure.isEmpty())throw new SecurityException("bounded_phone_native_structure_required");
            window=node.getInt("windowId");path=node.getJSONArray("path").toString();scope=PhoneTreeSequence.scope(actual);
            packageName=actual.optString("packageName");if(packageName.isEmpty())throw new SecurityException("bounded_phone_app_unknown");
            direction=args.getString("direction");target=args.optString("value");selector=args.optJSONObject("selector")==null?null:new JSONObject(args.getJSONObject("selector").toString());
            maxSteps=args.optInt(FIND.equals(tool)?"maxScrolls":"maxSteps",12);created=now;pause=actual.getLong("pauseRevision");
        }

        public int stepLimit(){return maxSteps+1;}
        public boolean wheel(){return WHEEL.equals(tool);}
        public boolean started(){return started;}
        public void start(String observation){if(started||!initialObservation.equals(observation))throw new SecurityException("bounded_phone_start_changed");started=true;}
        public void requireScope(JSONObject actual,long now)throws Exception {
            if(actual==null||now<created||now-created>TTL_MS||pause!=actual.optLong("pauseRevision",-1)
                    ||!scope.equals(PhoneTreeSequence.scope(actual))||!packageName.equals(actual.optString("packageName")))throw new SecurityException("bounded_phone_scope_changed");
        }
        public JSONObject targetNode(JSONObject actual)throws Exception {
            JSONObject found=null;for(JSONObject node:PhoneTreeExtract.index(actual).values())
                if(node.optInt("windowId",-1)==window&&path.equals(node.getJSONArray("path").toString())&&structure.equals(node.optString("structuralFingerprint"))){if(found!=null)throw new SecurityException("bounded_phone_target_ambiguous");found=node;}
            if(found==null||found.optBoolean("password")||!found.optBoolean("visible",true)||!found.optBoolean("enabled",true))throw new SecurityException("bounded_phone_target_changed");return found;
        }
        public boolean needsOcr(JSONObject actual)throws Exception {return wheel()&&!PhoneWheelValue.read(actual,targetNode(actual)).optBoolean("readable");}
        public NativeToolPlan.Resolved resolve(int index,JSONObject actual,long now,dev.pocketengine.core.RunControl.AuthorityCheck current)throws Exception {
            requireScope(actual,now);JSONObject node=targetNode(actual),evidence;String fingerprint;
            if(FIND.equals(tool)){
                JSONObject query=new JSONObject().put("observationId",actual.getString("observationId")).put("selector",new JSONObject(selector.toString()).put("parentId",node.getString("id"))).put("limit",40);
                evidence=PhoneTreeQuery.query(actual,query);fingerprint=actual.optString("treeFingerprint");
                if(!evidence.optBoolean("ok"))return finish("observation_unavailable",index,evidence,current);
                if(evidence.optInt("total")>0)return finish("target_found",index,evidence.put("found",true),current);
            }else{
                evidence=PhoneWheelValue.read(actual,node).put("target",target).put("observationId",actual.getString("observationId")).put("observation",PhoneTreeExtract.actionObservation(actual,node.getString("id")));
                fingerprint=evidence.optBoolean("readable")?"value:"+evidence.optString("value"):"tree:"+actual.optString("treeFingerprint");
                if(evidence.optBoolean("readable")&&target.equals(evidence.optString("value")))return finish("target_observed",index,evidence.put("reached",true),current);
            }
            if(index>=maxSteps)return finish("step_limit",index,evidence,current);
            if(index>0&&fingerprint.equals(previous))return finish("no_progress_observed",index,evidence,current);
            previous=fingerprint;
            JSONObject args=new JSONObject().put("observationId",actual.getString("observationId"));NativeToolPlan.Tool next;
            JSONObject editor=wheel()&&!typed?PhoneWheelValue.editor(actual,node):null;
            if(editor!=null){typed=true;next=NativeToolPlan.Tool.TYPE;args.put("nodeId",editor.getString("id")).put("text",target);}
            else{
                if(wheel()&&!evidence.optBoolean("readable"))return finish("value_unavailable",index,evidence,current);
                if(!node.optBoolean("scrollable"))return finish("target_not_scrollable",index,evidence,current);
                next=wheel()?NativeToolPlan.Tool.WHEEL_STEP:NativeToolPlan.Tool.SCROLL_NODE;
                args.put("nodeId",node.getString("id")).put("direction",direction);if(wheel())args.put("value",target);
            }
            return new NativeToolPlan.Resolved(next,args,PhoneTreeExtract.actionObservation(actual,args.getString("nodeId")),current);
        }
        private NativeToolPlan.Resolved finish(String status,int steps,JSONObject evidence,dev.pocketengine.core.RunControl.AuthorityCheck current)throws Exception {
            JSONObject result=new JSONObject().put("ok",true).put("status",status).put("tool",tool).put("stepsPerformed",steps)
                    .put("found",FIND.equals(tool)&&"target_found".equals(status)).put("reached",WHEEL.equals(tool)&&"target_observed".equals(status))
                    .put("partial",!Set.of("target_found","target_observed").contains(status)).put("wholeListComplete",false)
                    .put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false).put("evidence",evidence);
            return NativeToolPlan.Resolved.complete(result,current);
        }
    }
    public static State prepare(String tool,JSONObject args,JSONObject actual,long now)throws Exception{return new State(tool,args,actual,now);}
}
