package dev.pocketengine.phone.observation;

import dev.pocketengine.core.NativeToolPlan;
import dev.pocketengine.core.RunControl;
import java.util.Set;
import org.json.JSONObject;

/** Run-local intent and observations. Only the original Runner dispatches and settles the returned single steps. */
public final class PhoneBoundedOperation {
    public static final String FIND="phone_find",WHEEL="phone_prepare_wheel";
    public static final int MAX_STEPS=30;
    public static final long TTL_MS=180_000;
    private PhoneBoundedOperation() { }
    public static boolean supports(String tool){return FIND.equals(tool)||WHEEL.equals(tool);}

    public static void validate(String tool,JSONObject args)throws Exception {
        if(!supports(tool))throw new IllegalArgumentException("bounded_phone_tool_unknown");
        PhoneTreeExtract.keys(args,FIND.equals(tool)?new String[]{"observationId","containerId","selector","direction","maxScrolls","source","scrollMode","axis"}
                :new String[]{"observationId","nodeId","value","direction","maxSteps","method"});
        PhoneTreeExtract.text(args,"observationId",100);
        PhoneTreeExtract.text(args,FIND.equals(tool)?"containerId":"nodeId",100);
        if(!Set.of("forward","backward").contains(PhoneTreeExtract.text(args,"direction",10)))throw new IllegalArgumentException("bounded_phone_direction_invalid");
        PhoneTreeExtract.integer(args,FIND.equals(tool)?"maxScrolls":"maxSteps",12,1,MAX_STEPS);
        if(FIND.equals(tool)){
            JSONObject selector=args.getJSONObject("selector");PhoneTreeExtract.selector(selector);
            if(selector.length()==0)throw new IllegalArgumentException("find_selector_required");
            option(args,"source","auto",Set.of("auto","tree","ocr"));
            option(args,"scrollMode","auto",Set.of("auto","native","gesture"));
            option(args,"axis","vertical",Set.of("vertical","horizontal"));
            if("ocr".equals(args.optString("source"))&&!PhoneTargetSearch.canUseOcr(selector))throw new IllegalArgumentException("ocr_find_requires_text_only_selector");
        }else{
            PhoneTreeExtract.text(args,"value",100);option(args,"method","auto",Set.of("auto","native","drag"));
        }
    }
    private static String option(JSONObject args,String key,String fallback,Set<String> allowed)throws Exception {
        String value=args.has(key)?PhoneTreeExtract.text(args,key,20):fallback;
        if(!allowed.contains(value))throw new IllegalArgumentException("invalid_"+key);return value;
    }

    public static final class State {
        public final String tool,initialObservation;
        private final String scope,packageName,structure,direction,target,source,method,axis,path;
        private final JSONObject selector;
        private final int window,maxSteps;
        private final long created,pause,appPolicyRevision;
        private String previous="",lastMethod="",pendingTool="";
        private boolean started,typed,pendingSettled=true,gestureFallback;
        private JSONObject pendingArgs,pendingDrag,previousDrag;

        private State(String tool,JSONObject args,JSONObject actual,long now)throws Exception {
            validate(tool,args);this.tool=tool;initialObservation=actual.getString("observationId");
            if(!initialObservation.equals(args.getString("observationId")))throw new SecurityException("bounded_phone_start_changed");
            JSONObject node=PhoneRegionGesture.node(actual,args.getString(FIND.equals(tool)?"containerId":"nodeId"));
            structure=node.getString("structuralFingerprint");window=node.getInt("windowId");path=node.getJSONArray("path").toString();
            scope=PhoneTreeSequence.scope(actual);packageName=actual.optString("packageName");
            if(packageName.isEmpty())throw new SecurityException("bounded_phone_app_unknown");
            direction=args.getString("direction");target=args.optString("value");selector=args.optJSONObject("selector")==null?null:new JSONObject(args.getJSONObject("selector").toString());
            source=args.optString("source","auto");method=args.optString(wheel()?"method":"scrollMode","auto");axis=args.optString("axis","vertical");
            maxSteps=args.optInt(FIND.equals(tool)?"maxScrolls":"maxSteps",12);created=now;pause=actual.getLong("pauseRevision");appPolicyRevision=actual.optLong("appPolicyRevision",-1);
        }

        public int stepLimit(){return maxSteps+1;}
        public boolean wheel(){return WHEEL.equals(tool);}
        public boolean started(){return started;}
        public void start(String observation){if(started||!initialObservation.equals(observation))throw new SecurityException("bounded_phone_start_changed");started=true;}
        public void requireScope(JSONObject actual,long now)throws Exception {
            if(actual==null||now<created||now-created>TTL_MS||pause!=actual.optLong("pauseRevision",-1)
                    ||appPolicyRevision!=actual.optLong("appPolicyRevision",-1)
                    ||!scope.equals(PhoneTreeSequence.scope(actual))||!packageName.equals(actual.optString("packageName")))throw new SecurityException("bounded_phone_scope_changed");
        }
        public JSONObject targetNode(JSONObject actual)throws Exception {
            JSONObject found=null;for(JSONObject node:PhoneTreeExtract.index(actual).values())
                if(node.optInt("windowId",-1)==window&&path.equals(node.getJSONArray("path").toString())&&structure.equals(node.optString("structuralFingerprint"))){if(found!=null)throw new SecurityException("bounded_phone_target_ambiguous");found=node;}
            if(found==null)throw new SecurityException("bounded_phone_target_changed");return PhoneRegionGesture.node(actual,found.getString("id"));
        }
        private String progress(JSONObject actual,JSONObject node)throws Exception {
            if(!wheel())return PhoneTargetSearch.fingerprint(actual,node);
            JSONObject reading=PhoneWheelValue.read(actual,node);
            return reading.optBoolean("readable")?"value:"+reading.optString("value"):"content:"+PhoneTargetSearch.fingerprint(actual,node);
        }
        private boolean gesture(JSONObject actual,JSONObject node)throws Exception {
            if(method.equals("native"))return false;
            if(method.equals(wheel()?"drag":"gesture")||!PhoneRegionGesture.nativeScrollAvailable(node,direction))return true;
            if(pendingSettled&&"native".equals(lastMethod)&&!previous.isEmpty()&&previous.equals(progress(actual,node)))gestureFallback=true;
            return gestureFallback;
        }
        public boolean needsOcr(JSONObject actual)throws Exception {
            JSONObject node=targetNode(actual);
            if(!wheel())return PhoneTargetSearch.needsOcr(actual,node,selector,source);
            JSONObject reading=PhoneWheelValue.read(actual,node);
            if(reading.optBoolean("readable")&&PhoneWheelValue.matches(reading.optString("value"),target))return false;
            return !reading.optBoolean("readable")||gesture(actual,node)&&!reading.optBoolean("columnReady");
        }
        public boolean needsLocalFrame(JSONObject actual)throws Exception {
            JSONObject node=targetNode(actual);
            if(wheel()){
                JSONObject reading=PhoneWheelValue.read(actual,node);
                if(reading.optBoolean("readable")&&PhoneWheelValue.matches(reading.optString("value"),target))return false;
                if(!typed&&!"drag".equals(method)&&PhoneWheelValue.editor(actual,node)!=null)return false;
            }else if(PhoneTargetSearch.read(actual,node,selector,source).optInt("total")>0)return false;
            return gesture(actual,node);
        }

        /** Called only after the original Runner has saved this exact child receipt. It supplies no execution authority. */
        public void settled(String name,JSONObject args,JSONObject receipt)throws Exception {
            if(pendingArgs==null||!pendingTool.equals(name)||!sameArguments(pendingArgs,args))return;
            pendingSettled=receipt!=null&&Boolean.TRUE.equals(receipt.opt("accepted"))&&receipt.optBoolean("ok")
                    &&!receipt.optBoolean("requiresReconciliation")&&!ObservationSafety.unknownEffect(receipt.optString("error",receipt.optString("code")));
            if(pendingSettled&&pendingDrag!=null)previousDrag=new JSONObject(pendingDrag.getJSONObject("attempt").toString());
        }
        JSONObject plannedDrag(JSONObject args)throws Exception {
            return pendingDrag!=null&&pendingArgs!=null&&pendingTool.equals("phone_set_wheel")&&sameArguments(pendingArgs,args)
                    ?new JSONObject(pendingDrag.toString()):null;
        }
        JSONObject previousDrag(JSONObject args)throws Exception {
            return previousDrag!=null&&pendingArgs!=null&&pendingTool.equals("phone_set_wheel")&&sameArguments(pendingArgs,args)
                    ?new JSONObject(previousDrag.toString()):null;
        }
        void dispatchedDrag(JSONObject args,JSONObject plan)throws Exception {
            if(pendingArgs!=null&&pendingTool.equals("phone_set_wheel")&&sameArguments(pendingArgs,args))pendingDrag=new JSONObject(plan.toString());
        }
        private static boolean sameArguments(JSONObject a,JSONObject b)throws Exception {
            return dev.pocketengine.store.TaskStore.capabilityFingerprint(a).equals(dev.pocketengine.store.TaskStore.capabilityFingerprint(b));
        }

        public NativeToolPlan.Resolved resolve(int index,JSONObject actual,long now,RunControl.AuthorityCheck current)throws Exception {
            requireScope(actual,now);JSONObject node=targetNode(actual),evidence;
            if(index>0&&!pendingSettled)return finish("original_receipt_unavailable",index,new JSONObject(),current);
            if(!wheel()){
                evidence=PhoneTargetSearch.read(actual,node,selector,source);
                if(!evidence.optBoolean("ok"))return finish("observation_unavailable",index,evidence,current);
                if(evidence.optInt("total")>0)return finish("target_found",index,evidence.put("found",true),current);
            }else{
                evidence=PhoneWheelValue.read(actual,node).put("target",target).put("observationId",actual.getString("observationId"))
                        .put("observation",PhoneTreeExtract.actionObservation(actual,node.getString("id")));
                if(evidence.optBoolean("readable")&&PhoneWheelValue.matches(evidence.optString("value"),target))return finish("target_observed",index,evidence.put("reached",true),current);
            }
            if(index>=maxSteps)return finish("step_limit",index,evidence,current);
            boolean drag=gesture(actual,node);String fingerprint=progress(actual,node);
            if(index>0&&fingerprint.equals(previous)){
                boolean changedMethod=drag&&"native".equals(lastMethod)||"type".equals(lastMethod);
                if(!changedMethod&&(!wheel()||!drag))return finish("no_progress_observed",index,evidence,current);
            }
            previous=fingerprint;pendingDrag=null;
            JSONObject args=new JSONObject().put("observationId",actual.getString("observationId"));NativeToolPlan.Tool next;
            JSONObject editor=wheel()&&!typed&& !"drag".equals(method)?PhoneWheelValue.editor(actual,node):null;
            if(editor!=null){typed=true;next=NativeToolPlan.Tool.TYPE;args.put("nodeId",editor.getString("id")).put("text",target);lastMethod="type";}
            else{
                args.put("nodeId",node.getString("id")).put("direction",direction);
                if(wheel()){
                    if(!evidence.optBoolean("readable"))return finish("value_unavailable",index,evidence,current);
                    next=NativeToolPlan.Tool.WHEEL_STEP;args.put("value",target).put("method",drag?"drag":"native");
                    if(drag){
                        pendingDrag=PhoneWheelDrag.plan(actual,node,target,direction,previousDrag);
                        if(!pendingDrag.optBoolean("available"))return finish("drag_unavailable",index,evidence.put("drag",pendingDrag),current);
                    }else if(evidence.optBoolean("columnReady")){
                        JSONObject guidance=PhoneWheelDrag.plan(actual,node,target,direction,null);
                        if(guidance.optBoolean("available")){
                            org.json.JSONArray path=guidance.getJSONObject("gesture").getJSONArray("paths").getJSONArray(0);
                            String observedDirection=path.getJSONArray(path.length()-1).getInt(1)<path.getJSONArray(0).getInt(1)?"forward":"backward";
                            if(PhoneRegionGesture.nativeScrollAvailable(node,observedDirection))args.put("direction",observedDirection);
                        }
                    }
                }else{next=drag?NativeToolPlan.Tool.SCROLL_REGION:NativeToolPlan.Tool.SCROLL_NODE;if(drag)args.put("axis",axis);}
                if(!drag&&!node.optBoolean("scrollable"))return finish("target_not_scrollable",index,evidence,current);
                lastMethod=drag?"gesture":"native";
            }
            pendingTool=next.name;pendingArgs=new JSONObject(args.toString());pendingSettled=false;
            return new NativeToolPlan.Resolved(next,args,PhoneTreeExtract.actionObservation(actual,args.getString("nodeId")),current);
        }
        private NativeToolPlan.Resolved finish(String status,int steps,JSONObject evidence,RunControl.AuthorityCheck current)throws Exception {
            JSONObject result=new JSONObject().put("ok",true).put("status",status).put("tool",tool).put("stepsPerformed",steps)
                    .put("found",FIND.equals(tool)&&"target_found".equals(status)).put("reached",WHEEL.equals(tool)&&"target_observed".equals(status))
                    .put("partial",!Set.of("target_found","target_observed").contains(status)).put("wholeListComplete",false)
                    .put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false).put("evidence",evidence);
            return NativeToolPlan.Resolved.complete(result,current);
        }
    }
    public static State prepare(String tool,JSONObject args,JSONObject actual,long now)throws Exception{return new State(tool,args,actual,now);}
}
