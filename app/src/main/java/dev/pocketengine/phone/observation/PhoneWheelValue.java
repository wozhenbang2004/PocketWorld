package dev.pocketengine.phone.observation;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads the displayed value of one current picker. It never infers a value from a scroll count. */
final class PhoneWheelValue {
    private PhoneWheelValue() { }

    static JSONObject read(JSONObject observation,JSONObject wheel)throws Exception {
        Map<String,JSONObject> nodes=PhoneTreeExtract.index(observation);
        Set<String> selected=new LinkedHashSet<>(),editors=new LinkedHashSet<>();
        for(JSONObject node:nodes.values()){
            if(node==wheel||node.optBoolean("password")||!node.optBoolean("visible",true)
                    ||!PhoneTreeExtract.descendant(node,wheel.getString("id"),nodes))continue;
            String text=node.optString("text").trim();if(text.isEmpty())continue;
            if(node.optBoolean("selected"))selected.add(text);
            if(node.optBoolean("editable"))editors.add(text);
        }
        if(selected.size()==1)return value(selected.iterator().next(),"selected_descendant");
        if(selected.size()>1)return unavailable("multiple_selected_values");
        if(editors.size()==1)return value(editors.iterator().next(),"editable_descendant");
        if(editors.size()>1)return unavailable("multiple_editable_values");
        String own=wheel.optString("text").trim();
        if(!own.isEmpty()&&!own.contains("\n"))return value(own,"node_text");
        JSONObject ocr=observation.optJSONObject("ocr"),bounds=wheel.optJSONObject("bounds");
        if(ocr==null||bounds==null)return unavailable("value_not_exposed");
        if(observation.optBoolean("changedDuringRead"))return unavailable("ocr_geometry_changed");
        int width=ocr.optInt("rasterWidth"),height=ocr.optInt("rasterHeight");
        if(width<=0||height<=0||ocr.optBoolean("truncated"))return unavailable("ocr_coverage_incomplete");
        double left=bounds.getInt("left")*width/1000d,right=bounds.getInt("right")*width/1000d;
        double top=bounds.getInt("top")*height/1000d,bottom=bounds.getInt("bottom")*height/1000d;
        double middle=(top+bottom)/2d,band=Math.max(2d,(bottom-top)*0.12d);
        JSONArray lines=ocr.optJSONArray("lines");Set<String> center=new LinkedHashSet<>();
        for(int i=0;lines!=null&&i<lines.length();i++){
            JSONObject line=lines.getJSONObject(i);JSONArray box=line.getJSONArray("boundsPixels");
            double y=(box.getDouble(1)+box.getDouble(3))/2d;
            if(box.getDouble(0)>=left&&box.getDouble(2)<=right&&box.getDouble(1)>=top&&box.getDouble(3)<=bottom&&Math.abs(y-middle)<=band)
                center.add(line.getString("text").trim());
        }
        center.remove("");
        return center.size()==1?value(center.iterator().next(),"ocr_center_band_not_semantic_proof"):unavailable(center.isEmpty()?"ocr_center_unreadable":"ocr_center_ambiguous");
    }

    static JSONObject editor(JSONObject observation,JSONObject wheel)throws Exception {
        if(wheel.optBoolean("editable")&&!wheel.optBoolean("password"))return wheel;
        Map<String,JSONObject> nodes=PhoneTreeExtract.index(observation);JSONObject found=null;
        for(JSONObject node:nodes.values())if(node.optBoolean("editable")&&!node.optBoolean("password")&&node.optBoolean("visible",true)&&node.optBoolean("enabled",true)
                &&PhoneTreeExtract.descendant(node,wheel.getString("id"),nodes)){
            if(found!=null)return null;found=node;
        }
        return found;
    }

    private static JSONObject value(String value,String source)throws Exception {
        return new JSONObject().put("readable",true).put("value",value).put("source",source).put("verifiedBusinessSuccess",false);
    }
    private static JSONObject unavailable(String reason)throws Exception {
        return new JSONObject().put("readable",false).put("value",JSONObject.NULL).put("reason",reason).put("verifiedBusinessSuccess",false);
    }
}
