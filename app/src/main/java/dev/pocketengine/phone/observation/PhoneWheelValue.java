package dev.pocketengine.phone.observation;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads current picker evidence. Geometric center readings are not semantic success proofs. */
final class PhoneWheelValue {
    private static final int MAX_ITEMS=64,MAX_ITEM_CHARS=100;
    private static final Pattern NUMBER=Pattern.compile("^([^0-9+\\-]*)([+\\-]?[0-9]+(?:\\.[0-9]+)?)([^0-9]*)$");
    private PhoneWheelValue() { }

    static JSONObject read(JSONObject observation,JSONObject wheel)throws Exception {
        return summary(column(observation,wheel));
    }

    static JSONObject summary(JSONObject column)throws Exception {
        JSONObject result=new JSONObject().put("readable",column.optBoolean("readable"))
                .put("value",column.opt("value")==null?JSONObject.NULL:column.get("value"))
                .put("columnReady",column.optBoolean("columnReady"))
                .put("verifiedBusinessSuccess",false);
        for(String key:new String[]{"source","reason","columnReason"})if(column.has(key))result.put(key,column.get(key));
        return result;
    }

    /** Complete visible rows in one explicitly bounded column; never fills in an unseen center value. */
    static JSONObject column(JSONObject observation,JSONObject wheel)throws Exception {
        JSONObject box=wheel.optJSONObject("bounds");
        if(wheel.optBoolean("password")||!wheel.optBoolean("visible",true)||!validBox(box))return unavailable("wheel_bounds_unavailable");
        Map<String,JSONObject> nodes=PhoneTreeExtract.index(observation);
        List<JSONObject> selected=new ArrayList<>(),editors=new ArrayList<>(),items=new ArrayList<>();
        boolean complete=PhoneTreeQuery.completeSubtree(observation,wheel,true);
        for(JSONObject node:nodes.values()){
            if(!PhoneTreeExtract.descendant(node,wheel.getString("id"),nodes)||hidden(node,wheel,nodes))continue;
            String label=label(node);JSONObject bounds=node.optJSONObject("bounds");
            if(label.isEmpty()||!inside(bounds,box))continue;
            JSONObject item=item(label,bounds,node.optBoolean("selected"),node.getString("id"),0);
            if(node.optBoolean("selected"))addNative(selected,item,nodes);
            if(node.optBoolean("editable"))addNative(editors,item,nodes);
            if(node.getString("id").equals(wheel.getString("id"))&&node.optJSONArray("children")!=null&&node.getJSONArray("children").length()>0)continue;
            if(!hasLabelledDescendant(node,wheel,nodes))add(items,item);
            if(items.size()>MAX_ITEMS)return unavailable("too_many_visible_wheel_items");
        }
        if(selected.size()>1)return unavailable("multiple_selected_values");
        if(selected.isEmpty()&&editors.size()>1)return unavailable("multiple_editable_values");
        JSONObject explicit=selected.isEmpty()?(editors.isEmpty()?null:editors.get(0)):selected.get(0);
        String explicitSource=selected.isEmpty()?"editable_descendant":"selected_descendant";
        if(!complete&&explicit!=null&&!wheel.getString("id").equals(explicit.optString("nodeId")))explicit=null;
        JSONObject nativeColumn=complete?geometry(items,box,explicit,"native",explicitSource):unavailable("wheel_tree_incomplete");
        if(nativeColumn.optBoolean("columnReady"))return nativeColumn;
        // A known multi-column native view must not become one column merely because OCR missed text.
        String nativeReason=nativeColumn.optString("columnReason",nativeColumn.optString("reason"));
        if(nativeReason.equals("multiple_wheel_columns")||nativeReason.equals("overlapping_wheel_rows"))return withExplicit(nativeColumn,explicit,explicitSource);

        JSONObject ocr=observation.optJSONObject("ocr");
        if(ocr!=null){
            JSONObject fromOcr=ocrColumn(observation,ocr,box,explicit,explicitSource);
            if(fromOcr.optBoolean("columnReady")||fromOcr.optBoolean("readable"))return fromOcr;
            if(explicit!=null)return withExplicit(fromOcr,explicit,explicitSource);
            if(nativeColumn.optBoolean("readable"))return nativeColumn;
            return fromOcr;
        }
        if(explicit!=null)return withExplicit(nativeColumn,explicit,explicitSource);
        if(nativeColumn.optBoolean("readable"))return nativeColumn;
        String own=label(wheel);
        if(!own.isEmpty()&&items.size()<=1)return unavailable(nativeReason.isEmpty()?"visible_rows_unavailable":nativeReason)
                .put("readable",true).put("value",own).put("source","node_text");
        return nativeColumn;
    }

    private static JSONObject ocrColumn(JSONObject observation,JSONObject ocr,JSONObject box,JSONObject explicit,String explicitSource)throws Exception {
        if(observation.optBoolean("changedDuringRead"))return unavailable("ocr_geometry_changed");
        if(!observation.optString("observationId").equals(ocr.optString("observationId")))return unavailable("ocr_observation_changed");
        int width=ocr.optInt("rasterWidth"),height=ocr.optInt("rasterHeight");
        if(width<=0||height<=0||ocr.optBoolean("truncated")
                ||ocr.has("coordinateSpace")&&!"upright_ocr_raster_pixels".equals(ocr.optString("coordinateSpace")))return unavailable("ocr_coverage_incomplete");
        List<JSONObject> items=ocrItems(ocr.optJSONArray("lines"),box,width,height,false);
        if(items.size()>MAX_ITEMS)return unavailable("too_many_visible_wheel_items");
        JSONObject result=geometry(items,box,explicit,"ocr",explicitSource);
        if(result.optBoolean("columnReady")||"multiple_wheel_columns".equals(result.optString("columnReason")))return result;
        // Whole OCR lines may span adjacent pickers; actual word boxes can still fit this column.
        if(!ocr.optBoolean("wordBoxesTruncated")&&ocr.optJSONArray("words")!=null){
            List<JSONObject> words=ocrItems(ocr.getJSONArray("words"),box,width,height,true);
            if(words.size()>MAX_ITEMS)return unavailable("too_many_visible_wheel_items");
            JSONObject wordResult=geometry(words,box,explicit,"ocr_word",explicitSource);
            if(wordResult.optBoolean("columnReady")||!result.optBoolean("readable"))return wordResult;
        }
        return result;
    }

    private static List<JSONObject> ocrItems(JSONArray raw,JSONObject box,int width,int height,boolean words)throws Exception {
        List<JSONObject> items=new ArrayList<>();
        for(int i=0;raw!=null&&i<raw.length();i++){
            JSONObject line=raw.getJSONObject(i);String text=shortLabel(line.optString("text"));JSONArray pixels=line.optJSONArray("boundsPixels");
            if(text.isEmpty()||pixels==null||pixels.length()!=4)continue;
            double left=pixels.optDouble(0,Double.NaN),top=pixels.optDouble(1,Double.NaN),right=pixels.optDouble(2,Double.NaN),bottom=pixels.optDouble(3,Double.NaN);
            if(!Double.isFinite(left)||!Double.isFinite(top)||!Double.isFinite(right)||!Double.isFinite(bottom)
                    ||left<0||top<0||right>width||bottom>height||right<=left||bottom<=top)continue;
            JSONObject bounds=new JSONObject().put("left",left*1000d/width).put("top",top*1000d/height)
                    .put("right",right*1000d/width).put("bottom",bottom*1000d/height);
            if(!inside(bounds,box))continue;
            JSONObject item=item(text,bounds,false,"",line.optInt(words?"word":"line",i+1));
            if(words&&line.has("line"))item.put("ocrLineIndex",line.get("line"));
            add(items,item);if(items.size()>MAX_ITEMS)break;
        }
        return items;
    }

    private static JSONObject geometry(List<JSONObject> items,JSONObject box,JSONObject explicit,String source,String explicitSource)throws Exception {
        if(items.isEmpty())return unavailable("visible_rows_unavailable");
        items.sort(Comparator.comparingDouble(i->i.optDouble("centerY")));
        double left=box.getDouble("left"),right=box.getDouble("right");
        List<Double> gaps=new ArrayList<>();
        for(int i=0;i<items.size();i++){
            JSONObject item=items.get(i),bounds=item.getJSONObject("bounds");left=Math.max(left,bounds.getDouble("left"));right=Math.min(right,bounds.getDouble("right"));
            if(i>0){JSONObject before=items.get(i-1);double gap=item.getDouble("centerY")-before.getDouble("centerY");
                if(gap<Math.max(2,Math.min(item.getDouble("height"),before.getDouble("height"))*.5))return unavailable("overlapping_wheel_rows");
                gaps.add(gap);
            }
        }
        if(right-left<2)return unavailable("multiple_wheel_columns");
        double rowHeight=median(gaps),middle=(box.getDouble("top")+box.getDouble("bottom"))/2;
        int selected=-1;
        if(explicit!=null){
            int matchCount=0,closest=-1;double closestDistance=Double.POSITIVE_INFINITY,secondDistance=Double.POSITIVE_INFINITY;
            for(int i=0;i<items.size();i++)if(matches(items.get(i).getString("text"),explicit.getString("text"))){
                matchCount++;selected=i;double distance=Math.abs(items.get(i).getDouble("centerY")-explicit.getDouble("centerY"));
                if(distance<closestDistance){secondDistance=closestDistance;closestDistance=distance;closest=i;}else secondDistance=Math.min(secondDistance,distance);
            }
            if(selected<0)return unavailable("native_and_visible_value_disagree");
            if(matchCount>1){
                if(closestDistance>Math.max(2,rowHeight*.4)||secondDistance-closestDistance<Math.max(2,rowHeight*.1))return unavailable("current_value_ambiguous");
                selected=closest;
            }
        }else{
            double best=Double.POSITIVE_INFINITY,second=Double.POSITIVE_INFINITY;
            for(int i=0;i<items.size();i++){double distance=Math.abs(items.get(i).getDouble("centerY")-middle);
                if(distance<best){second=best;best=distance;selected=i;}else second=Math.min(second,distance);}
            double tolerance=rowHeight>0?rowHeight*.4:Math.min(items.get(selected).getDouble("height")*.6,(box.getDouble("bottom")-box.getDouble("top"))*.12);
            if(best>tolerance||second-best<Math.max(2,rowHeight*.1))return unavailable("center_value_unreadable_or_ambiguous");
        }
        JSONObject result=new JSONObject().put("readable",true).put("value",items.get(selected).getString("text"))
                .put("source",explicit==null?source+"_center_band_not_semantic_proof":explicitSource)
                .put("columnSource",source).put("selectedIndex",selected).put("items",new JSONArray(items))
                .put("bounds",new JSONObject(box.toString())).put("dragX",(left+right)/2)
                .put("rowHeight",rowHeight).put("verifiedBusinessSuccess",false);
        if(items.size()<2||rowHeight<4)return result.put("columnReady",false).put("columnReason","row_spacing_unavailable");
        for(double gap:gaps)if(Math.abs(gap-rowHeight)>rowHeight*.3)return result.put("columnReady",false).put("columnReason","row_spacing_irregular");
        return result.put("columnReady",true);
    }

    private static JSONObject withExplicit(JSONObject result,JSONObject explicit,String source)throws Exception {
        if(explicit!=null)result.put("readable",true).put("value",explicit.getString("text")).put("source",source);
        return result;
    }

    static JSONObject editor(JSONObject observation,JSONObject wheel)throws Exception {
        if(wheel.optBoolean("editable")&&!wheel.optBoolean("password"))return wheel;
        if(!PhoneTreeQuery.completeSubtree(observation,wheel,false))return null;
        Map<String,JSONObject> nodes=PhoneTreeExtract.index(observation);JSONObject found=null;
        for(JSONObject node:nodes.values())if(node.optBoolean("editable")&&!hidden(node,wheel,nodes)&&node.optBoolean("enabled",true)
                &&PhoneTreeExtract.descendant(node,wheel.getString("id"),nodes)){
            if(found!=null)return null;found=node;
        }
        return found;
    }

    static boolean matches(String actual,String wanted){
        if(actual==null||wanted==null)return false;
        if(actual.trim().equals(wanted.trim()))return true;
        Numeric a=numeric(actual),b=numeric(wanted);return a!=null&&a.sameFormat(b)&&a.value.compareTo(b.value)==0;
    }
    static Numeric numeric(String text){
        Matcher match=NUMBER.matcher(text==null?"":text.trim());if(!match.matches())return null;
        try{return new Numeric(match.group(1),new BigDecimal(match.group(2)),match.group(3));}catch(NumberFormatException invalid){return null;}
    }
    static final class Numeric {
        final String prefix,suffix;final BigDecimal value;
        Numeric(String prefix,BigDecimal value,String suffix){this.prefix=prefix;this.value=value;this.suffix=suffix;}
        boolean sameFormat(Numeric other){return other!=null&&prefix.equals(other.prefix)&&suffix.equals(other.suffix);}
    }
    private static String label(JSONObject node){
        if(node.optBoolean("textTruncated"))return "";
        String text=node.optString("text");if(text.isBlank())text=node.optString("description");return shortLabel(text);
    }
    private static String shortLabel(String value){String text=value.trim();return text.length()>MAX_ITEM_CHARS||text.contains("\n")||text.contains("\r")?"":text;}
    private static boolean hidden(JSONObject node,JSONObject wheel,Map<String,JSONObject> nodes){
        for(JSONObject at=node;at!=null;at=nodes.get(at.optString("parentId"))){
            if(at.optBoolean("password")||!at.optBoolean("visible",true))return true;if(at.optString("id").equals(wheel.optString("id")))break;
        }return false;
    }
    private static boolean hasLabelledDescendant(JSONObject node,JSONObject wheel,Map<String,JSONObject> nodes){
        ArrayDeque<JSONObject> pending=new ArrayDeque<>();pending.add(node);
        while(!pending.isEmpty()){
            JSONArray children=pending.removeFirst().optJSONArray("children");for(int i=0;children!=null&&i<children.length();i++){
                JSONObject child=nodes.get(children.optString(i));if(child==null||hidden(child,wheel,nodes))continue;
                if(!label(child).isEmpty())return true;pending.addLast(child);
            }
        }return false;
    }
    private static JSONObject item(String text,JSONObject bounds,boolean selected,String nodeId,int ocrIndex)throws Exception {
        return new JSONObject().put("text",text).put("bounds",new JSONObject(bounds.toString())).put("selected",selected)
                .put("nodeId",nodeId).put("ocrIndex",ocrIndex)
                .put("centerX",(bounds.getDouble("left")+bounds.getDouble("right"))/2)
                .put("centerY",(bounds.getDouble("top")+bounds.getDouble("bottom"))/2)
                .put("height",bounds.getDouble("bottom")-bounds.getDouble("top"));
    }
    private static void add(List<JSONObject> items,JSONObject item)throws Exception {
        for(JSONObject before:items)if(before.getString("text").equals(item.getString("text"))
                &&Math.abs(before.getDouble("centerX")-item.getDouble("centerX"))<2
                &&Math.abs(before.getDouble("centerY")-item.getDouble("centerY"))<2){
            if(item.optBoolean("selected"))before.put("selected",true);return;
        }items.add(item);
    }
    private static void addNative(List<JSONObject> items,JSONObject item,Map<String,JSONObject> nodes)throws Exception {
        JSONObject node=nodes.get(item.getString("nodeId"));
        for(int i=0;i<items.size();i++){
            JSONObject before=items.get(i);if(!before.getString("text").equals(item.getString("text")))continue;
            JSONObject parent=nodes.get(before.getString("nodeId"));
            if(PhoneTreeExtract.descendant(node,parent.getString("id"),nodes)){items.set(i,item);return;}
            if(PhoneTreeExtract.descendant(parent,node.getString("id"),nodes))return;
        }
        add(items,item);
    }
    private static boolean inside(JSONObject item,JSONObject outer){return validBox(item)&&validBox(outer)
            &&item.optDouble("left")>=outer.optDouble("left")-.5&&item.optDouble("top")>=outer.optDouble("top")-.5
            &&item.optDouble("right")<=outer.optDouble("right")+.5&&item.optDouble("bottom")<=outer.optDouble("bottom")+.5;}
    private static boolean validBox(JSONObject box){if(box==null)return false;double left=box.optDouble("left",Double.NaN),top=box.optDouble("top",Double.NaN),right=box.optDouble("right",Double.NaN),bottom=box.optDouble("bottom",Double.NaN);
        return Double.isFinite(left)&&Double.isFinite(top)&&Double.isFinite(right)&&Double.isFinite(bottom)&&left>=0&&top>=0&&right<=1000&&bottom<=1000&&right>left&&bottom>top;}
    private static double median(List<Double> values){if(values.isEmpty())return 0;values.sort(Double::compare);int middle=values.size()/2;return values.size()%2==0?(values.get(middle-1)+values.get(middle))/2:values.get(middle);}
    private static JSONObject unavailable(String reason)throws Exception {
        return new JSONObject().put("readable",false).put("value",JSONObject.NULL).put("reason",reason)
                .put("columnReady",false).put("columnReason",reason).put("verifiedBusinessSuccess",false);
    }
}
