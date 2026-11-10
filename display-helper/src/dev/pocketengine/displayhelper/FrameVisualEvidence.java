package dev.pocketengine.displayhelper;

import java.security.MessageDigest;
import org.json.JSONObject;

/** Bounded pixel statistics. These hints neither identify a target nor explain why a frame is dark. */
public final class FrameVisualEvidence {
    private static final int COLUMNS=24,ROWS=32,POINTS_PER_CELL=4;
    @FunctionalInterface public interface Pixels {int argb(int x,int y);}
    private String scope="";
    private int width,height;
    private int[] anchor;
    private long timestamp,frames,nearBlackFrames,revision,changedAt;
    private String latest;

    /** Only a distinct producer timestamp advances frame counters; metadata polls do not. */
    public synchronized JSONObject record(String scope,int width,int height,long timestamp,Pixels pixels)throws Exception {
        if(scope==null||scope.isEmpty()||width<=0||height<=0||timestamp<=0)throw new IllegalArgumentException("invalid_frame_evidence");
        if(!scope.equals(this.scope)||width!=this.width||height!=this.height){
            this.scope=scope;this.width=width;this.height=height;this.timestamp=0;frames=0;nearBlackFrames=0;revision=0;changedAt=0;anchor=null;latest=null;
        }
        if(timestamp<=this.timestamp)return snapshot();
        int[] grid=new int[COLUMNS*ROWS*3];int dark=0,min=255,max=0;long lumaSum=0;
        for(int row=0;row<ROWS;row++)for(int column=0;column<COLUMNS;column++){
            int red=0,green=0,blue=0;
            for(int point=0;point<POINTS_PER_CELL;point++){
                int x=Math.min(width-1,(int)((column+(point%2==0?.25:.75))*width/COLUMNS));
                int y=Math.min(height-1,(int)((row+(point<2?.25:.75))*height/ROWS));
                int color=pixels.argb(x,y),r=color>>>16&255,g=color>>>8&255,b=color&255;
                int luma=(r*54+g*183+b*19)/256;
                if(luma<=16)dark++;min=Math.min(min,luma);max=Math.max(max,luma);lumaSum+=luma;
                red+=r;green+=g;blue+=b;
            }
            int at=(row*COLUMNS+column)*3;grid[at]=red/POINTS_PER_CELL;grid[at+1]=green/POINTS_PER_CELL;grid[at+2]=blue/POINTS_PER_CELL;
        }
        boolean changed=anchor==null||differs(anchor,grid);
        if(changed){anchor=grid;revision++;changedAt=timestamp;}
        int sampled=COLUMNS*ROWS*POINTS_PER_CELL;boolean nearBlack=dark*100L>=sampled*98L;
        frames++;nearBlackFrames=nearBlack?nearBlackFrames+1:0;this.timestamp=timestamp;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(int value:grid)digest.update((byte)value);
        StringBuilder fingerprint=new StringBuilder();for(byte value:digest.digest())fingerprint.append(Character.forDigit(value>>>4&15,16)).append(Character.forDigit(value&15,16));
        latest=new JSONObject().put("status","available").put("samplingScope",scope).put("width",width).put("height",height)
                .put("frameTimestampNanos",timestamp).put("fingerprint",fingerprint.toString()).put("stabilityRevision",revision).put("lastVisualChangeNanos",changedAt)
                .put("sampledFrames",frames).put("consecutiveNearBlackFrames",nearBlackFrames).put("nearBlack",nearBlack)
                .put("darkPixelRatio",(double)dark/sampled).put("meanLuminance",(double)lumaSum/sampled).put("luminanceRange",max-min)
                .put("sampledPixels",sampled).put("diagnosis",nearBlack?(nearBlackFrames>=2?"repeated_near_black_samples":"near_black_sample"):"visible_pixel_content")
                .put("cause","not_determined").put("sampling","coarse_rgb_grid").put("targetEvidence",false).put("verifiedBusinessSuccess",false).toString();
        return snapshot();
    }

    public synchronized JSONObject snapshot()throws Exception {return latest==null?null:new JSONObject(latest);}

    private static boolean differs(int[] before,int[] after){
        long total=0;int changedCells=0;
        for(int i=0;i<after.length;i+=3){int largest=0;
            for(int channel=0;channel<3;channel++){int delta=Math.abs(before[i+channel]-after[i+channel]);total+=delta;largest=Math.max(largest,delta);}
            if(largest>12)changedCells++;
        }
        // Compare against the last meaningful change, so slow motion cannot disappear
        // by taking many individually small steps. Small caret/clock changes are hints only.
        return total>after.length*2L||changedCells>=Math.max(2,COLUMNS*ROWS/100);
    }
}
