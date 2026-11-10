package dev.pocketengine.phone.observation;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded rectangle selected from an actually delivered frame. It is never a native node or a grant. */
final class PhoneVisualRegion {
    static final String TOOL="phone_visual_region";
    static final long TTL_MS=180_000;
    final String id=UUID.randomUUID().toString(),sourceObservation;
    private final JSONObject bounds;
    private final String scope,packageName,windows;
    private final int screenWidth,screenHeight,rasterWidth,rasterHeight,windowId;
    private final long created,pause,policy;
    private final List<int[]> anchors;
    private final List<int[]> initialSurface;
    private String currentFrame,invalid="";
    private boolean contentChangeAdmitted;

    PhoneVisualRegion(JSONObject actual,JSONObject rectangle,long now)throws Exception {
        validateBounds(rectangle);
        if(!ObservationReport.hasImage(actual)||actual.optBoolean("changedDuringRead")||!actual.optBoolean("coordinateActionsAvailable",false))
            throw new SecurityException("visual_region_delivered_frame_required");
        bounds=new JSONObject(rectangle.toString());sourceObservation=actual.getString("observationId");
        scope=PhoneTreeSequence.scope(actual);packageName=actual.getString("packageName");
        screenWidth=actual.getInt("screenWidth");screenHeight=actual.getInt("screenHeight");
        rasterWidth=actual.getInt("imageWidth");rasterHeight=actual.getInt("imageHeight");
        pause=actual.getLong("pauseRevision");policy=actual.optLong("appPolicyRevision",-1);created=now;
        JSONObject window=applicationWindow(actual);windowId=window.getInt("windowId");
        JSONObject outer=window.getJSONObject("bounds");
        if(!inside(bounds,outer))throw new SecurityException("visual_region_outside_application_window");
        windows=windowIdentity(actual);
        Bitmap bitmap=decode(actual);
        try{anchors=anchors(bitmap,bounds,outer);initialSurface=anchors(bitmap,null,outer);}finally{bitmap.recycle();}
        if(anchors.size()<16)throw new SecurityException("visual_region_needs_visible_surroundings");
        int first=anchors.get(0)[2],contrast=0;for(int[] point:anchors)if(distance(first,point[2])>24)contrast++;
        if(contrast<4)throw new SecurityException("visual_region_surroundings_not_distinct");
        currentFrame=sourceObservation;
    }
    static void validateBounds(JSONObject bounds)throws Exception {
        PhoneTreeExtract.keys(bounds,"left","top","right","bottom");
        int left=PhoneTreeExtract.integer(bounds,"left",-1,4,996),top=PhoneTreeExtract.integer(bounds,"top",-1,4,996);
        int right=PhoneTreeExtract.integer(bounds,"right",-1,4,996),bottom=PhoneTreeExtract.integer(bounds,"bottom",-1,4,996);
        if(right-left<24||bottom-top<60)throw new IllegalArgumentException("visual_region_too_small");
    }
    JSONObject bounds()throws Exception{return new JSONObject(bounds.toString());}
    synchronized void requireScope(JSONObject actual,long now)throws Exception {
        if(!invalid.isEmpty())throw new SecurityException(invalid);
        if(now<created||now-created>TTL_MS||actual==null||!scope.equals(PhoneTreeSequence.scope(actual))
                ||!packageName.equals(actual.optString("packageName"))||pause!=actual.optLong("pauseRevision",-1)
                ||policy!=actual.optLong("appPolicyRevision",-1)||screenWidth!=actual.optInt("screenWidth")||screenHeight!=actual.optInt("screenHeight")
                ||!windows.equals(windowIdentity(actual)))throw new SecurityException("visual_region_scope_changed");
    }
    /** Called while real frame bytes still exist; only the original adapter calls this method. */
    synchronized void observeFrame(JSONObject actual,long now){
        if(!invalid.isEmpty())return;
        try{
            requireScope(actual,now);
            if(actual.optBoolean("changedDuringRead")||!actual.optBoolean("coordinateActionsAvailable",false)
                    ||!ObservationReport.hasImage(actual)||rasterWidth!=actual.optInt("imageWidth")||rasterHeight!=actual.optInt("imageHeight")){
                currentFrame=null;return;
            }
            Bitmap bitmap=decode(actual);int changed=0,initialChanged=0;
            try{
                for(int[] point:anchors)if(distance(point[2],sample(bitmap,point[0],point[1]))>24)changed++;
                if(!contentChangeAdmitted)for(int[] point:initialSurface)if(distance(point[2],sample(bitmap,point[0],point[1]))>24)initialChanged++;
            }
            finally{bitmap.recycle();}
            // Small capture noise is tolerated; an altered surrounding surface invalidates this original region permanently.
            if(changed>Math.max(1,anchors.size()/50))throw new SecurityException("visual_region_surroundings_changed");
            if(!contentChangeAdmitted&&initialChanged>Math.max(1,initialSurface.size()/100))throw new SecurityException("visual_region_initial_surface_changed");
            currentFrame=actual.getString("observationId");
        }catch(Exception unavailable){currentFrame=null;invalid="visual_region_reanchor_required";}
    }
    synchronized void requireFrame(JSONObject actual,long now)throws Exception {
        requireScope(actual,now);
        if(currentFrame==null||!currentFrame.equals(actual.optString("observationId")))throw new SecurityException("visual_region_fresh_frame_required");
    }
    synchronized void acceptedGesture(JSONObject originalFrame,JSONObject receipt)throws Exception {
        if(currentFrame==null||!currentFrame.equals(originalFrame.optString("observationId"))){invalid="visual_region_original_input_frame_changed";return;}
        if(Boolean.TRUE.equals(receipt.opt("accepted"))&&receipt.optBoolean("ok")&&!receipt.optBoolean("requiresReconciliation")
                &&!ObservationSafety.unknownEffect(receipt.optString("error",receipt.optString("code"))))contentChangeAdmitted=true;
    }
    JSONObject reference()throws Exception {
        return new JSONObject().put("regionId",id).put("bounds",bounds()).put("windowId",windowId)
                .put("sourceObservationId",sourceObservation).put("source","delivered_screenshot_region")
                .put("nativeNode",false).put("visualAnchor","original_window_geometry_and_local_surroundings")
                .put("visualIdentityIsHeuristic",true).put("expiresInMs",TTL_MS).put("automaticReplayAllowed",false);
    }
    JSONObject actionObservation(JSONObject actual)throws Exception {
        JSONObject out=PhoneTreeQuery.modelObservation(actual);
        for(String key:new String[]{"imageBase64","image_url","ocr"})out.remove(key);
        return out.put("visualRegion",reference());
    }
    private static JSONObject applicationWindow(JSONObject actual)throws Exception {
        JSONArray windows=actual.getJSONArray("windows");JSONObject found=null;
        for(int i=0;i<windows.length();i++){
            JSONObject w=windows.getJSONObject(i);
            if(w.optInt("type")!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION||!w.optBoolean("focused"))continue;
            if(found!=null||!actual.optString("packageName").equals(w.optString("packageName"))||actual.optInt("displayId",-1)!=w.optInt("displayId",-2))
                throw new SecurityException("visual_region_window_ambiguous");
            found=w;
        }
        if(found==null||found.optString("packageName").isEmpty())throw new SecurityException("visual_region_window_unavailable");return found;
    }
    private static String windowIdentity(JSONObject actual)throws Exception {
        applicationWindow(actual);JSONArray identity=new JSONArray(),windows=actual.getJSONArray("windows");
        for(int i=0;i<windows.length();i++){
            JSONObject source=windows.getJSONObject(i),item=new JSONObject();
            for(String key:new String[]{"windowId","displayId","type","layer","active","focused","bounds","packageName"})if(source.has(key))item.put(key,source.get(key));
            identity.put(item);
        }
        return dev.pocketengine.store.TaskStore.capabilityFingerprint(new JSONObject().put("windows",identity));
    }
    private static Bitmap decode(JSONObject actual)throws Exception {
        byte[] bytes=java.util.Base64.getDecoder().decode(actual.getString("imageBase64"));
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        if(bitmap==null||bitmap.getWidth()!=actual.getInt("imageWidth")||bitmap.getHeight()!=actual.getInt("imageHeight")){
            if(bitmap!=null)bitmap.recycle();throw new SecurityException("visual_region_frame_unavailable");
        }
        return bitmap;
    }
    private static List<int[]> anchors(Bitmap bitmap,JSONObject region,JSONObject outer)throws Exception {
        List<int[]> out=new ArrayList<>();
        for(int y=0;y<32;y++)for(int x=0;x<24;x++){
            int nx=(2*x+1)*1000/48,ny=(2*y+1)*1000/64;
            if(nx<outer.getDouble("left")+8||nx>outer.getDouble("right")-8||ny<outer.getDouble("top")+8||ny>outer.getDouble("bottom")-8)continue;
            if(region!=null&&nx>=region.getInt("left")-10&&nx<=region.getInt("right")+10&&ny>=region.getInt("top")-10&&ny<=region.getInt("bottom")+10)continue;
            int px=nx*(bitmap.getWidth()-1)/1000,py=ny*(bitmap.getHeight()-1)/1000;
            out.add(new int[]{px,py,sample(bitmap,px,py)});
        }
        return out;
    }
    private static int sample(Bitmap bitmap,int x,int y){
        int r=0,g=0,b=0,n=0;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            int p=bitmap.getPixel(Math.max(0,Math.min(bitmap.getWidth()-1,x+dx)),Math.max(0,Math.min(bitmap.getHeight()-1,y+dy)));
            r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;n++;
        }
        return ((r/n)<<16)|((g/n)<<8)|(b/n);
    }
    private static int distance(int a,int b){return (Math.abs(((a>>16)&255)-((b>>16)&255))+Math.abs(((a>>8)&255)-((b>>8)&255))+Math.abs((a&255)-(b&255)))/3;}
    private static boolean inside(JSONObject inner,JSONObject outer)throws Exception {
        return inner.getInt("left")>=outer.getDouble("left")&&inner.getInt("right")<=outer.getDouble("right")
                &&inner.getInt("top")>=outer.getDouble("top")&&inner.getInt("bottom")<=outer.getDouble("bottom");
    }
}
