package dev.pocketengine.phone.observation;

import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native evidence only. IDs and metadata never count as image pixels or OCR text. */
public final class ObservationReport {
    private ObservationReport() { }
    /** Only image acquisition failures may retain an independently refreshed tree. */
    public static boolean isCaptureUnavailable(String code) {
        return java.util.Set.of("screenshot_unavailable","screenshot_timeout","screenshot_throttled",
                "screenshot_secure_window","screenshot_too_large","frame_pending","frame_unavailable").contains(code);
    }
    public static JSONObject attach(JSONObject observation, boolean treeAvailable, String treeReason,
                                    boolean imageIncluded, String imageReason) throws Exception {
        return attach(observation, treeAvailable, treeReason, imageIncluded, imageReason, false);
    }
    /** OCR readiness comes only from the native host's mounted engine, not a frame's own metadata. */
    public static JSONObject attach(JSONObject observation, boolean treeAvailable, String treeReason,
                                    boolean imageIncluded, String imageReason, boolean ocrAvailable) throws Exception {
        observation.remove("ocr");
        JSONArray nodes = observation.optJSONArray("nodes");
        int useful = 0;
        if (treeAvailable && nodes != null) for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || node.optBoolean("password") || !node.optBoolean("enabled", true) || !node.optBoolean("visible",true)) continue;
            // A clickable image/canvas without a label does not identify a semantic target.
            if (node.optBoolean("editable")
                    || !node.optString("text").trim().isEmpty() || !node.optString("description").trim().isEmpty()) useful++;
        }
        JSONObject quality=quality(observation,treeAvailable,useful);
        boolean actualImage = imageIncluded && hasImage(observation);
        return observation.put("observationSources", new JSONObject()
                .put("treeAvailable", treeAvailable).put("treeReason", treeReason)
                .put("usefulNodes", useful).put("treeAdequate",quality.getBoolean("adequate")).put("treeQuality",quality).put("imageIncluded", actualImage).put("imageReason", imageReason)
                .put("ocrAvailable", ocrAvailable).put("ocrReason", ocrAvailable ? "mounted_local_engine" : "engine_not_installed")
                .put("ocrTextIncluded", false)
                .put("contentTrust", "untrusted_screen_content"));
    }
    private static JSONObject quality(JSONObject observation,boolean available,int useful)throws Exception {
        java.util.Map<String,JSONObject> index=new java.util.LinkedHashMap<>();JSONArray raw=observation.optJSONArray("nodes");
        if(raw!=null)for(int i=0;i<raw.length();i++){JSONObject n=raw.optJSONObject(i);if(n!=null)index.put(n.optString("id","row"+i),n);}
        int controls=0,labeled=0,blind=0,unreadable=0,visible=0;
        for(JSONObject node:index.values()){
            if(!node.optBoolean("visible",true)||node.optBoolean("password"))continue;visible++;
            boolean named=hasLabel(node,index,new java.util.HashSet<>());
            if(node.optBoolean("enabled",true)&&(node.optBoolean("clickable")||node.optBoolean("editable"))){controls++;if(named)labeled++;}
            // A large explicitly exposed raster/web leaf without any descendant text is a concrete blind region.
            String role=node.optString("role");JSONObject b=node.optJSONObject("bounds");
            if(!named&&b!=null&&role.matches("image|web")&&(long)Math.max(0,b.optInt("right")-b.optInt("left"))*Math.max(0,b.optInt("bottom")-b.optInt("top"))>=330000)blind++;
        }
        JSONArray windows=observation.optJSONArray("windows");
        if(windows!=null)for(int i=0;i<windows.length();i++){JSONObject w=windows.optJSONObject(i);if(w!=null&&!w.optBoolean("rootAvailable")&&(w.optBoolean("active")||w.optBoolean("focused")))unreadable++;}
        JSONArray reasons=new JSONArray();
        if(!available)reasons.put("no_accessible_root");if(observation.optBoolean("truncated"))reasons.put("structure_or_identity_truncated");
        if(useful==0)reasons.put("no_useful_nodes");if(controls>0&&labeled==0)reasons.put("unlabeled_controls");
        if(unreadable>0)reasons.put("unreadable_active_window");if(blind>0)reasons.put("unlabeled_large_visual_region");
        boolean adequate=available&&reasons.length()==0;
        return new JSONObject().put("adequate",adequate).put("status",adequate?"adequate_for_tree_navigation":available?"partial":"unavailable")
                .put("reasons",reasons).put("visibleNodes",visible).put("actionableNodes",controls).put("labeledActionableNodes",labeled)
                .put("unlabeledActionableNodes",controls-labeled).put("unreadableActiveWindows",unreadable).put("blindRegions",blind)
                .put("taskTargetCoverage","not_asserted").put("bodyTruncated",observation.optJSONObject("treeCoverage")!=null&&observation.getJSONObject("treeCoverage").optBoolean("bodyTruncated"));
    }
    private static boolean hasLabel(JSONObject node,java.util.Map<String,JSONObject> nodes,java.util.Set<String> path){
        if(node.optBoolean("password")||!path.add(node.optString("id")))return false;
        try{
            if(!node.optString("text").trim().isEmpty()||!node.optString("description").trim().isEmpty())return true;
            JSONArray children=node.optJSONArray("children");if(children!=null)for(int i=0;i<children.length();i++){
                JSONObject child=nodes.get(children.optString(i));if(child!=null&&hasLabel(child,nodes,path))return true;
            }
            return false;
        }finally{path.remove(node.optString("id"));}
    }
    /** Checks bounded encoded image bytes from a trusted native capture, not a screenshot identifier. */
    public static boolean hasImage(JSONObject observation) {
        String bytes = observation.optString("imageBase64");
        String mime = observation.optString("imageMimeType");
        int width = observation.optInt("imageWidth"), height = observation.optInt("imageHeight");
        if (bytes.isEmpty() || bytes.length() > 8 * 1024 * 1024 || bytes.length() % 4 != 0
                || width <= 0 || height <= 0 || width > 8192 || height > 8192
                || (long) width * height > 16_000_000) return false;
        try {
            byte[] image = Base64.getDecoder().decode(bytes);
            if ("image/jpeg".equals(mime)) return image.length >= 4
                    && (image[0] & 255) == 255 && (image[1] & 255) == 216
                    && (image[image.length - 2] & 255) == 255 && (image[image.length - 1] & 255) == 217;
            if ("image/png".equals(mime)) return image.length >= 24 && image[0] == (byte) 137
                    && image[1] == 80 && image[2] == 78 && image[3] == 71 && image[4] == 13
                    && image[5] == 10 && image[6] == 26 && image[7] == 10
                    && image[12] == 73 && image[13] == 72 && image[14] == 68 && image[15] == 82
                    && unsignedInt(image, 16) == width && unsignedInt(image, 20) == height;
            return "image/webp".equals(mime) && image.length >= 16 && image[0] == 82 && image[1] == 73
                    && image[2] == 70 && image[3] == 70 && image[8] == 87 && image[9] == 69
                    && image[10] == 66 && image[11] == 80;
        } catch (IllegalArgumentException invalidBase64) { return false; }
    }
    private static long unsignedInt(byte[] bytes, int at) {
        return ((bytes[at] & 255L) << 24) | ((bytes[at + 1] & 255L) << 16)
                | ((bytes[at + 2] & 255L) << 8) | (bytes[at + 3] & 255L);
    }
    public static JSONObject annotateFailure(JSONObject result) throws Exception {
        String code = result.optString("error", result.optString("code"));
        boolean unknown = "reconciliation_required".equals(code) || ObservationSafety.unknownEffect(code) || (result.has("accepted") && result.isNull("accepted"));
        return result.put("requiresPause", unknown || ObservationSafety.requiresPause(code))
                .put("nextObservationAction", unknown ? "reconcile" : ObservationSafety.requiresPause(code) ? "pause" : "reobserve")
                .put("automaticReplayAllowed", false);
    }
}
