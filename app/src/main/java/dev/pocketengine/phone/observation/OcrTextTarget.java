package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.vision.ocr.OcrPage;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native-only OCR geometry tied to the exact captured observation, not a model coordinate. */
public final class OcrTextTarget {
    public final String id=UUID.randomUUID().toString();
    public final String observationId,text;
    public final String granularity;public final int lineIndex;public final Float confidence;
    public final int sourceWidth,sourceHeight,rasterWidth,rasterHeight,left,top,right,bottom;
    private final RunControl original;
    private final long pauseRevision;
    OcrTextTarget(String observationId,long pauseRevision,RunControl original,OcrPage page,OcrPage.Line line) {
        this(observationId,pauseRevision,original,page,line.text,line.left,line.top,line.right,line.bottom,"line",line.index,null);
    }
    OcrTextTarget(String observationId,long pauseRevision,RunControl original,OcrPage page,OcrPage.Word word) {
        this(observationId,pauseRevision,original,page,word.text,word.left,word.top,word.right,word.bottom,"word",word.lineIndex,word.confidence);
    }
    private OcrTextTarget(String observationId,long pauseRevision,RunControl original,OcrPage page,String text,int left,int top,int right,int bottom,String granularity,int lineIndex,Float confidence) {
        this.observationId=java.util.Objects.requireNonNull(observationId);this.original=java.util.Objects.requireNonNull(original);this.pauseRevision=pauseRevision;
        sourceWidth=page.sourceWidth;sourceHeight=page.sourceHeight;rasterWidth=page.width;rasterHeight=page.height;
        this.text=text;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.granularity=granularity;this.lineIndex=lineIndex;this.confidence=confidence;
    }
    public boolean matches(RunControl control,String observationId,long revision) {
        return control==original&&this.observationId.equals(observationId)&&pauseRevision==revision
                &&control.pauseRevision()==pauseRevision&&!control.isPaused()&&!control.isCancelled();
    }
    /** Called only after a physical driver matches its privately retained full-frame geometry. */
    public JSONObject tapArguments(int displayWidth,int displayHeight)throws Exception {
        if(displayWidth<2||displayHeight<2)throw new IllegalArgumentException("OCR目标无法映射到当前显示");
        int x=axis(left,right,rasterWidth,displayWidth),y=axis(top,bottom,rasterHeight,displayHeight);
        return new JSONObject().put("observationId",observationId).put("x",x).put("y",y);
    }
    private static int axis(int from,int to,int raster,int display) {
        double low=(double)from*display/raster-.5,high=(double)to*display/raster-.5;
        double center=Math.max(0,Math.min(display-1,(low+high)/2));int normalized=(int)Math.round(center*1000/(display-1));
        double actual=(double)normalized*(display-1)/1000;
        if(actual<low||actual>=high)throw new IllegalArgumentException("OCR文字区域太小，不能可靠定位；请重新观察或由主人处理");
        return normalized;
    }
    JSONObject receiptEvidence()throws Exception {
        JSONObject out=new JSONObject().put("textTargetId",id).put("recognizedText",text).put("observationId",observationId).put("granularity",granularity).put("line",lineIndex)
                .put("boundsPixels",new JSONArray().put(left).put(top).put(right).put(bottom))
                .put("rasterWidth",rasterWidth).put("rasterHeight",rasterHeight).put("coordinateSpace",OcrPage.COORDINATE_SPACE)
                .put("recognitionMayBeWrong",true).put("verifiedBusinessSuccess",false).put("confidenceAvailable",confidence!=null);
        if(confidence!=null)out.put("confidence",confidence).put("confidenceMeaning","ocr_engine_score_not_semantic_or_clickability_guarantee");
        else out.put("precision","line_center_not_a_specific_word");return out;
    }
}
