package dev.pocketengine.displayhelper;

import java.nio.ByteBuffer;
import org.json.JSONObject;

/** One native display geometry, independent of pixels, task ownership and Android queues. */
public final class DisplayGeometry {
    public final long generation;
    public final int width,height,density,rotation,captureWidth,captureHeight;

    public DisplayGeometry(long generation,int width,int height,int density,int rotation) {
        if(generation<=0||width<=0||height<=0||(long)width*height>2_400_000
                ||density<=0||density>10_000||rotation<0||rotation>3)
            throw new IllegalArgumentException("Invalid native display geometry");
        this.generation=generation;this.width=width;this.height=height;this.density=density;this.rotation=rotation;
        captureWidth=(rotation&1)==0?width:height;
        captureHeight=(rotation&1)==0?height:width;
    }

    public DisplayGeometry withGeneration(long next) {
        return new DisplayGeometry(next,width,height,density,rotation);
    }
    public boolean sameConfiguration(DisplayGeometry other) {
        return other!=null&&width==other.width&&height==other.height&&density==other.density&&rotation==other.rotation;
    }
    public boolean sameObservation(DisplayGeometry other) {
        return sameConfiguration(other)&&generation==other.generation;
    }
    /** A delayed authenticated reply cannot roll a session back or redefine an existing generation. */
    public boolean canFollow(DisplayGeometry previous) {
        return previous==null||generation>previous.generation
                ||generation==previous.generation&&sameConfiguration(previous);
    }
    public JSONObject writeTo(JSONObject out,boolean ready)throws Exception {
        return out.put("geometryTracking",true).put("geometryReady",ready).put("geometryGeneration",generation)
                .put("width",width).put("height",height).put("density",density).put("rotation",rotation)
                .put("captureWidth",captureWidth).put("captureHeight",captureHeight);
    }
    /** Null is the old fixed-geometry protocol, never partial or downgraded new metadata. */
    public static DisplayGeometry read(JSONObject input) {
        if(!Boolean.TRUE.equals(input.opt("geometryTracking"))) {
            if(input.has("geometryGeneration")||input.has("rotation")||input.has("captureWidth")
                    ||input.has("captureHeight")||input.has("geometryReady")
                    ||input.has("geometryTracking")&&!Boolean.FALSE.equals(input.opt("geometryTracking")))
                throw new IllegalArgumentException("Incomplete native geometry identity");
            return null;
        }
        if(!(input.opt("geometryReady") instanceof Boolean))throw new IllegalArgumentException("Missing native geometry readiness");
        DisplayGeometry value=new DisplayGeometry(number(input,"geometryGeneration",Long.MAX_VALUE),
                (int)number(input,"width",Integer.MAX_VALUE),(int)number(input,"height",Integer.MAX_VALUE),
                (int)number(input,"density",Integer.MAX_VALUE),(int)number(input,"rotation",3));
        if(number(input,"captureWidth",Integer.MAX_VALUE)!=value.captureWidth
                ||number(input,"captureHeight",Integer.MAX_VALUE)!=value.captureHeight)
            throw new IllegalArgumentException("Logical and capture geometry differ");
        return value;
    }
    private static long number(JSONObject input,String key,long maximum) {
        Object raw=input.opt(key);
        if(!(raw instanceof Number))throw new IllegalArgumentException("Missing native geometry field: "+key);
        try{long exact=new java.math.BigDecimal(raw.toString()).longValueExact();
            if(exact<0||exact>maximum)throw new ArithmeticException();return exact;
        }catch(NumberFormatException|ArithmeticException invalid){throw new IllegalArgumentException("Invalid native geometry field: "+key);}
    }

    /**
     * SurfaceFlinger's display projection already rotates logical content into the natural
     * surface. Undo that projection exactly once; input and accessibility remain logical.
     * There is no scaling, crop or second Bitmap rotation in this path.
     */
    public byte[] logicalPixels(ByteBuffer data,int pixelStride,int rowStride,int imageWidth,int imageHeight) {
        if(data==null||imageWidth!=captureWidth||imageHeight!=captureHeight||pixelStride<4
                ||rowStride<(long)(captureWidth-1)*pixelStride+4)
            throw new IllegalArgumentException("Capture buffer does not match native geometry");
        int base=data.position();
        long end=(long)base+(long)(captureHeight-1)*rowStride+(long)(captureWidth-1)*pixelStride+4;
        if(end>data.limit())throw new IllegalArgumentException("Truncated native capture plane");
        byte[] pixels=new byte[width*height*4];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int rawX,rawY;
            switch(rotation) {
                case 1: rawX=height-1-y;rawY=x;break;
                case 2: rawX=width-1-x;rawY=height-1-y;break;
                case 3: rawX=y;rawY=width-1-x;break;
                default: rawX=x;rawY=y;
            }
            int offset=(int)((long)base+(long)rawY*rowStride+(long)rawX*pixelStride),dest=(y*width+x)*4;
            for(int channel=0;channel<4;channel++)pixels[dest+channel]=data.get(offset+channel);
        }
        return pixels;
    }
}
