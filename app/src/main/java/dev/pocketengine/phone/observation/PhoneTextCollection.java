package dev.pocketengine.phone.observation;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded page evidence, not an executor. Advancing consumes a settled operation from the original journal. */
public final class PhoneTextCollection {
    public static final String TOOL="phone_collect_text";
    public static final int MAX_PAGES=30,MAX_LINES=1000;
    public static final long TTL_MS=600_000;
    private PhoneTextCollection() { }

    public static void validate(JSONObject args)throws Exception {
        String action=PhoneTreeExtract.text(args,"action",10);
        if(action.equals("start")){
            PhoneTreeExtract.keys(args,"action","observationId","containerId","source","direction","maxPages","maxLines");
            PhoneTreeExtract.text(args,"observationId",100);PhoneTreeExtract.text(args,"containerId",100);
            if(!Set.of("auto","tree","ocr").contains(args.optString("source","auto")))throw new IllegalArgumentException("text_collection_source_invalid");
            if(!Set.of("forward","backward").contains(PhoneTreeExtract.text(args,"direction",10)))throw new IllegalArgumentException("text_collection_direction_invalid");
            PhoneTreeExtract.integer(args,"maxPages",10,1,MAX_PAGES);PhoneTreeExtract.integer(args,"maxLines",500,1,MAX_LINES);
        }else if(Set.of("advance","read","finish").contains(action)){
            PhoneTreeExtract.keys(args,"action","cursorId","afterOperationId","observationId","offset","limit");PhoneTreeExtract.text(args,"cursorId",100);
            if(action.equals("advance"))PhoneTreeExtract.text(args,"afterOperationId",100);else if(args.has("afterOperationId"))throw new IllegalArgumentException("text_collection_operation_not_expected");
            if(!action.equals("read")&&(args.has("observationId")||args.has("offset")||args.has("limit")))throw new IllegalArgumentException("text_collection_read_arguments_required");
            if(args.has("observationId"))PhoneTreeExtract.text(args,"observationId",100);
            PhoneTreeExtract.integer(args,"offset",0,0,MAX_LINES);PhoneTreeExtract.integer(args,"limit",100,1,100);
        }else throw new IllegalArgumentException("text_collection_action_invalid");
    }

    public static final class State {
        public final String id=UUID.randomUUID().toString(),source;
        private final String scope,packageName,structure,direction,path;
        private final int window,maxPages,maxLines;
        private final long created;
        private final JSONArray lines=new JSONArray(),pages=new JSONArray();
        private final Set<String> operations=new HashSet<>();
        private JSONObject latest,container;
        private String stopped="",lastText="";
        private int retainedBytes;

        public State(JSONObject args,JSONObject initial,JSONObject target,String nativeScope,long now)throws Exception {
            validate(args);source=args.optString("source","auto");scope=nativeScope;created=now;
            packageName=initial.optString("packageName");if(packageName.isEmpty())throw new SecurityException("text_collection_app_unknown");
            structure=target.optString("structuralFingerprint");if(structure.isEmpty()||target.optBoolean("password"))throw new SecurityException("text_collection_native_container_required");
            window=target.getInt("windowId");path=target.getJSONArray("path").toString();direction=args.getString("direction");
            maxPages=args.optInt("maxPages",10);maxLines=args.optInt("maxLines",500);
        }
        public boolean stopped(){return !stopped.isEmpty();}
        public void requireScope(String current,long now)throws Exception {if(!scope.equals(current)||now<created||now-created>TTL_MS)throw new SecurityException("text_collection_scope_expired");}
        public JSONObject readStored(String current,long now,int offset,int limit)throws Exception {
            if(!scope.equals(current))throw new SecurityException("text_collection_scope_changed");
            if(now<created||now-created>TTL_MS)stopped="cursor_expired";
            JSONObject out=result(offset,limit);out.remove("observation");out.remove("nextScroll");out.remove("nextScrollRegion");out.remove("nextAction");out.remove("refreshCursorBeforeScroll");
            return out.put("historicalEvidenceOnly",true);
        }
        public JSONObject target(JSONObject actual)throws Exception {
            if(!packageName.equals(actual.optString("packageName")))throw new SecurityException("text_collection_app_changed");
            JSONObject found=null;for(JSONObject node:PhoneTreeExtract.index(actual).values())if(node.optInt("windowId",-1)==window&&path.equals(node.getJSONArray("path").toString())&&structure.equals(node.optString("structuralFingerprint"))){if(found!=null)throw new SecurityException("text_collection_container_ambiguous");found=node;}
            if(found==null||found.optBoolean("password")||!found.optBoolean("visible",true))throw new SecurityException("text_collection_container_changed");return found;
        }
        public void requireScroll(JSONObject record)throws Exception {
            if(stopped()||latest==null||record==null||!"receipt".equals(record.optString("state")))throw new SecurityException("text_collection_original_receipt_required");
            String operation=record.getString("operationId"),tool=record.optString("tool");JSONObject receipt=record.getJSONObject("receipt"),args=record.getJSONObject("arguments");
            if(operations.contains(operation)||!Boolean.TRUE.equals(receipt.opt("accepted"))||!latest.getString("observationId").equals(args.optString("observationId")))throw new SecurityException("text_collection_scroll_receipt_changed");
            if(NativeTreeScrollPort.TOOL.equals(tool)||PhoneRegionGesture.TOOL.equals(tool)){
                if(!container.getString("id").equals(args.optString("nodeId"))||!direction.equals(args.optString("direction")))throw new SecurityException("text_collection_wrong_scroll_target");
                if(PhoneRegionGesture.TOOL.equals(tool)){
                    JSONObject gesture=receipt.optJSONObject("gesture");JSONArray paths=gesture==null?null:gesture.optJSONArray("paths");
                    if(paths==null||paths.length()!=1)throw new SecurityException("text_collection_region_evidence_unavailable");
                    requirePath(paths.getJSONArray(0));
                }
            }else if("phone_swipe".equals(tool)){
                requirePath(new JSONArray().put(new JSONArray().put(args.getInt("x1")).put(args.getInt("y1"))).put(new JSONArray().put(args.getInt("x2")).put(args.getInt("y2"))));
            }else if("phone_gesture".equals(tool)){
                JSONArray paths=args.getJSONArray("paths");if(paths.length()!=1)throw new SecurityException("text_collection_single_scroll_required");requirePath(paths.getJSONArray(0));
            }else throw new SecurityException("text_collection_not_a_scroll");
        }
        private void requirePath(JSONArray points)throws Exception {
            JSONObject bounds=container.getJSONObject("bounds");
            for(int i=0;i<points.length();i++){JSONArray p=points.getJSONArray(i);if(p.getInt(0)<bounds.getInt("left")||p.getInt(0)>bounds.getInt("right")||p.getInt(1)<bounds.getInt("top")||p.getInt(1)>bounds.getInt("bottom"))throw new SecurityException("text_collection_scroll_outside_container");}
            JSONArray a=points.getJSONArray(0),b=points.getJSONArray(points.length()-1);int dx=b.getInt(0)-a.getInt(0),dy=b.getInt(1)-a.getInt(1),delta=Math.abs(dy)>=Math.abs(dx)?dy:dx;
            if(delta==0||direction.equals("forward")!=(delta<0))throw new SecurityException("text_collection_scroll_direction_changed");
        }
        public JSONObject accept(JSONObject actual,JSONObject record,boolean refresh)throws Exception {
            JSONObject current=target(actual);JSONArray page=page(actual,current,source);String signature=textSignature(page);
            if(refresh){if(!signature.equals(lastText)){stopped="scene_changed_without_admitted_scroll";return result(0,100);}latest=new JSONObject(actual.toString());container=new JSONObject(current.toString());return result(0,100);}
            if(record!=null){requireScroll(record);operations.add(record.getString("operationId"));}
            latest=new JSONObject(actual.toString());container=new JSONObject(current.toString());
            if(!lastText.isEmpty()&&signature.equals(lastText)){stopped="no_progress_observed";return result(0,100);}lastText=signature;
            int pageIndex=pages.length()+1,kept=0;
            for(int i=0;i<page.length();i++){
                if(lines.length()>=maxLines){stopped="line_limit";break;}
                JSONObject row=new JSONObject(page.getJSONObject(i).toString()).put("sourceObservationId",actual.getString("observationId")).put("page",pageIndex).put("evidenceOnly",true);
                int bytes=row.toString().getBytes(StandardCharsets.UTF_8).length;if(retainedBytes+bytes>256*1024){stopped="retained_text_budget";break;}
                lines.put(row);retainedBytes+=bytes;kept++;
            }
            pages.put(new JSONObject().put("page",pageIndex).put("observationId",actual.getString("observationId")).put("frameTimestampNanos",actual.opt("frameTimestampNanos"))
                    .put("source",actual.optJSONObject("ocr")!=null&&!source.equals("tree")?"ocr":"accessibility").put("visibleLines",page.length()).put("retainedLines",kept)
                    .put("scrollOperationId",record==null?JSONObject.NULL:record.getString("operationId")).put("overlapNotDeduplicated",true));
            if(stopped.isEmpty()&&page.length()==0)stopped="no_readable_text";
            if(stopped.isEmpty()&&pages.length()>=maxPages)stopped="page_limit";
            if(stopped.isEmpty()&&lines.length()>=maxLines)stopped="line_limit";
            return result(0,100);
        }
        public JSONObject stop(String reason)throws Exception {if(stopped.isEmpty())stopped=reason;return result(0,100);}
        public JSONObject result(int offset,int limit)throws Exception {
            if(offset<0||offset>lines.length())return PhoneTreeExtract.unavailable("text_collection_range_unavailable");
            JSONArray visible=new JSONArray();int next=offset;
            while(next<lines.length()&&visible.length()<limit){visible.put(lines.get(next));if(visible.toString().getBytes(StandardCharsets.UTF_8).length>24*1024){visible.remove(visible.length()-1);break;}next++;}
            JSONObject out=PhoneTreeExtract.base().put("ok",true).put("cursorId",id).put("status",stopped()?"stopped":"needs_scroll").put("stoppedBecause",stopped.isEmpty()?JSONObject.NULL:stopped)
                    .put("lines",visible).put("lineCount",lines.length()).put("offset",offset).put("nextOffset",next<lines.length()?next:JSONObject.NULL).put("pages",new JSONArray(pages.toString()))
                    .put("partial",true).put("wholeListComplete",false).put("overlapMayRepeat",true).put("recognitionMayBeWrong",!source.equals("tree")).put("expiresAfterMs",TTL_MS);
            if(latest!=null&&!stopped()){
                out.put("observation",PhoneTreeExtract.actionObservation(latest,container.getString("id")));
                if(container.optBoolean("scrollable"))out.put("nextScroll",new JSONObject().put("observationId",latest.getString("observationId")).put("nodeId",container.getString("id")).put("direction",direction));
                else out.put("nextScrollRegion",new JSONObject().put("observationId",latest.getString("observationId")).put("nodeId",container.getString("id")).put("direction",direction).put("axis","vertical"))
                        .put("nextAction","phone_scroll_region_requires_local_capture").put("refreshCursorBeforeScroll",false);
            }
            return out;
        }
    }
    public static boolean nativeTextAvailable(JSONObject actual,JSONObject container)throws Exception {return page(actual,container,"tree").length()>0;}
    private static JSONArray page(JSONObject actual,JSONObject container,String source)throws Exception {
        JSONArray result=new JSONArray();JSONObject ocr=actual.optJSONObject("ocr");
        if(ocr!=null&&!source.equals("tree")){
            if(actual.optBoolean("changedDuringRead"))throw new SecurityException("text_collection_ocr_geometry_changed");
            int width=ocr.getInt("rasterWidth"),height=ocr.getInt("rasterHeight");JSONObject b=container.getJSONObject("bounds");JSONArray lines=ocr.getJSONArray("lines");
            for(int i=0;i<lines.length();i++){JSONObject line=lines.getJSONObject(i);JSONArray r=line.getJSONArray("boundsPixels");double x=(r.getInt(0)+r.getInt(2))*500d/width,y=(r.getInt(1)+r.getInt(3))*500d/height;
                if(x>=b.getInt("left")&&x<=b.getInt("right")&&y>=b.getInt("top")&&y<=b.getInt("bottom"))appendText(result,line.getString("text"),new JSONObject().put("source","ocr").put("line",line.getInt("line")).put("boundsPixels",new JSONArray(r.toString())).put("coordinateSpace",ocr.getString("coordinateSpace")));
            }
        }else if(!source.equals("ocr")){
            Map<String,JSONObject> nodes=PhoneTreeExtract.index(actual);
            for(JSONObject node:nodes.values())if(!node.optBoolean("password")&&node.optBoolean("visible",true)&&PhoneTreeExtract.descendant(node,container.getString("id"),nodes)){
                String text=node.optString("text"),description=node.optString("description");if(text.isEmpty())text=description;
                if(!text.isBlank())appendText(result,text,new JSONObject().put("textTruncated",node.optBoolean("textTruncated")).put("source","accessibility").put("nodeRef",PhoneTreeExtract.ref(node)));
            }
        }
        return result;
    }
    private static void appendText(JSONArray out,String text,JSONObject source)throws Exception {
        int offset=0,part=0;
        while(offset<text.length()){
            // Leave room for JSON escaping and the original native source reference within a page.
            int end=Math.min(text.length(),offset+2000);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;
            out.put(new JSONObject(source.toString()).put("text",text.substring(offset,end)).put("textOffset",offset).put("textPart",++part).put("sourceTextChars",text.length()));offset=end;
        }
    }
    private static String textSignature(JSONArray rows)throws Exception {JSONArray texts=new JSONArray();for(int i=0;i<rows.length();i++)texts.put(rows.getJSONObject(i).getString("text"));return dev.pocketengine.store.TaskStore.capabilityFingerprint(new JSONObject().put("lines",texts));}
}
