package dev.pocketengine.phone.observation;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small owner-facing description of one existing original read, never a new read or a claim of model receipt. */
final class OwnerReadBasis {
    private OwnerReadBasis() { }
    private static boolean yes(JSONObject row,String field){return row!=null&&Boolean.TRUE.equals(row.opt(field));}
    private static boolean no(JSONObject row,String field){return row!=null&&Boolean.FALSE.equals(row.opt(field));}
    private static boolean rows(JSONObject row,String key){JSONArray list=row==null?null:row.optJSONArray(key);return list!=null&&list.length()>0;}

    static JSONObject from(JSONObject read)throws Exception {
        JSONObject choice=read.optJSONObject("observationDecision"),parts=read.optJSONObject("observationSources");
        if(choice==null||parts==null)return null;
        String request=choice.optString("request"),why=choice.optString("reason"),method,reason="";
        boolean local="native_local_geometry".equals(why)&&no(choice,"imageUploaded")&&no(parts,"imageIncluded")
                &&"native_local_geometry_only".equals(parts.optString("imageReason"));
        if("ocr".equals(request)){
            JSONObject ocr=read.optJSONObject("ocr");
            if(!yes(parts,"ocrTextIncluded")||!no(parts,"imageIncluded")||ocr==null||!"ocr_text".equals(ocr.optString("kind"))
                    ||!read.getString("observationId").equals(ocr.optString("observationId"))||!rows(ocr,"lines"))return null;
            method="local_text";
        }else if("nodes".equals(request)&&local)method="local_geometry";
        else if("nodes".equals(request)&&yes(parts,"treeAvailable")&&no(parts,"imageIncluded")&&rows(read,"nodes"))method="structure";
        else if("vision".equals(request)&&yes(parts,"imageIncluded")&&yes(choice,"uploadImageAllowed")&&yes(choice,"visionAvailable"))method="frame";
        else return null;
        if("structure".equals(method)){
            if("image_unavailable_tree_retained".equals(why))reason="image_unavailable";
            else if(yes(read,"truncated"))reason="partial";
        }else if("frame".equals(method)){
            JSONObject fallback=read.optJSONObject("ocrFallback");
            if(yes(fallback,"sameObservation")&&no(fallback,"newCapture")&&"vision".equals(fallback.optString("request"))
                    &&java.util.Set.of("ocr_no_text","ocr_unavailable","ocr_limit_exceeded").contains(fallback.optString("reason")))reason="same_frame_after_ocr";
        }
        if(reason.isEmpty()&&("frame".equals(method)||"local_text".equals(method))&&
                java.util.Set.of("tree_unavailable","tree_truncated","no_useful_nodes","tree_partial").contains(why))reason="limited_structure";
        JSONObject view=new JSONObject().put("method",method);
        return reason.isEmpty()?view:view.put("reason",reason);
    }
}
