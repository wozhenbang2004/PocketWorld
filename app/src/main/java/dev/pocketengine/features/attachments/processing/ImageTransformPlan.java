package dev.pocketengine.features.attachments.processing;

import dev.pocketengine.features.local.project.ProjectSources;
import java.util.Set;
import org.json.JSONObject;

/** Original oriented pixel coordinates, explicit output dimensions and loss/alpha policy. */
public final class ImageTransformPlan {
    public final int sourceWidth,sourceHeight,left,top,right,bottom,width,height,rotation,quality;
    public final String format,sourceIdentity,sourceSha256,identity;private final JSONObject value;
    public ImageTransformPlan(String sourceIdentity,String sha,int actualWidth,int actualHeight,JSONObject options)throws Exception{
        if(sourceIdentity==null||sourceIdentity.isEmpty()||sha==null||!sha.matches("[a-f0-9]{64}")||actualWidth<1||actualHeight<1||(long)actualWidth*actualHeight>8_000_000L)throw new IllegalArgumentException("此加工入口只支持原尺寸合计不超过800万像素的静态原图；未将采样图冒充原图");
        for(var k=options.keys();k.hasNext();){String key=k.next();if(!Set.of("left","top","right","bottom","width","height","rotation","format","quality").contains(key))throw new IllegalArgumentException("不支持的图片加工参数："+key);}
        this.sourceIdentity=sourceIdentity;sourceSha256=sha;sourceWidth=actualWidth;sourceHeight=actualHeight;left=integer(options,"left",0);top=integer(options,"top",0);right=integer(options,"right",actualWidth);bottom=integer(options,"bottom",actualHeight);
        width=integer(options,"width",right-left);height=integer(options,"height",bottom-top);rotation=integer(options,"rotation",0);quality=integer(options,"quality",90);format=options.optString("format","png");
        if(left<0||top<0||right<=left||bottom<=top||right>actualWidth||bottom>actualHeight||width<1||height<1||width>4096||height>4096||(long)width*height>4_000_000L||!Set.of(0,90,180,270).contains(rotation)||!Set.of("png","jpeg").contains(format)||quality<50||quality>95)throw new IllegalArgumentException("裁剪必须在原图内，输出最大4096边/400万像素；旋转0/90/180/270，PNG或JPEG质量50至95");
        value=new JSONObject().put("format","pocketworld.image-transform.v1").put("sourceIdentity",sourceIdentity).put("sourceSha256",sha).put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight).put("left",left).put("top",top).put("right",right).put("bottom",bottom).put("width",width).put("height",height).put("rotation",rotation).put("outputFormat",format).put("quality",quality).put("coordinateSpace","oriented_original_pixels").put("jpegAlphaPolicy","white_background").put("sourceUnchanged",true);identity=ProjectSources.digest(value);
    }
    public int finalWidth(){return rotation==90||rotation==270?height:width;}public int finalHeight(){return rotation==90||rotation==270?width:height;}
    public JSONObject json()throws Exception{return new JSONObject(value.toString()).put("planDigest",identity);}
    private static int integer(JSONObject value,String key,int fallback)throws Exception{Object x=value.opt(key);if(x==null)return fallback;if(!(x instanceof Integer||x instanceof Long)||((Number)x).longValue()<Integer.MIN_VALUE||((Number)x).longValue()>Integer.MAX_VALUE)throw new IllegalArgumentException(key+"需为精确整数");return ((Number)x).intValue();}
}
