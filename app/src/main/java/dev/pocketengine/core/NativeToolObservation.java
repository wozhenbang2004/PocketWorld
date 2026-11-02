package dev.pocketengine.core;

import java.util.Arrays;
import java.util.Objects;
import org.json.JSONObject;

/** One native in-memory visual observation. Never restored from tool JSON or historical references. */
public final class NativeToolObservation implements AutoCloseable {
    public enum Requirement { NONE, IMAGE }
    public record Crop(int x,int y,int width,int height) {
        public Crop {if(x<0||y<0||width<1||height<1)throw new IllegalArgumentException("invalid_native_crop");}
    }
    public record Viewport(int widthPx,int heightPx,int scrollXPx,int scrollYPx,double scale,Crop crop,int imageWidthPx,int imageHeightPx) {
        public Viewport {Objects.requireNonNull(crop);if(widthPx<1||heightPx<1||!Double.isFinite(scale)||scale<=0||imageWidthPx<1||imageHeightPx<1||crop.x()>widthPx-crop.width()||crop.y()>heightPx-crop.height())throw new IllegalArgumentException("invalid_native_viewport");}
    }
    public record Identity(String taskId,String runId,int epoch,String profileId,long profileRevision,String profileIsolation,
            String browserSessionId,String browserSpaceId,String tabId,long tabGeneration,long navigation,String documentId,
            String observationId,long capturedAt,Viewport viewport) {
        public Identity {for(String s:new String[]{taskId,runId,profileId,profileIsolation,browserSessionId,browserSpaceId,tabId,documentId,observationId})if(s==null||s.isEmpty()||s.length()>300)throw new IllegalArgumentException("invalid_native_visual_identity");if(epoch<1||profileRevision<1||tabGeneration<1||navigation<0||capturedAt<1)throw new IllegalArgumentException("invalid_native_visual_revision");Objects.requireNonNull(viewport);}
    }
    /** A device frame is not an Android browser tab. JSON references are immutable copies of
     * the actual verified frame; possession alone grants no action or observation authority. */
    public static final class DeviceFrameIdentity {
        private final String task,run,sourceOperation,target,instance,targetOperation,reference,resource,digest;
        private final int epoch,width,height;private final long capturedAt;
        public DeviceFrameIdentity(String task,String run,int epoch,String sourceOperation,String target,String instance,String targetOperation,
                JSONObject observationRef,JSONObject resourceBinding,String digest,int width,int height,long capturedAt)throws Exception{
            for(String value:new String[]{task,run,sourceOperation,target,instance,targetOperation})if(value==null||value.isEmpty()||value.length()>300)throw new IllegalArgumentException("invalid_device_frame_identity");
            if(epoch<1||width<1||height<1||width>16384||height>16384||(long)width*height>40_000_000||capturedAt<1||digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid_device_frame_dimensions_or_digest");
            this.task=task;this.run=run;this.epoch=epoch;this.sourceOperation=sourceOperation;this.target=target;this.instance=instance;this.targetOperation=targetOperation;
            reference=Objects.requireNonNull(observationRef).toString();resource=Objects.requireNonNull(resourceBinding).toString();this.digest=digest;this.width=width;this.height=height;this.capturedAt=capturedAt;
        }
        public String taskId(){return task;}public String runId(){return run;}public int epoch(){return epoch;}
        public JSONObject metadata()throws Exception{return new JSONObject().put("kind","paired_device_visual_snapshot").put("taskId",task).put("runId",run).put("epoch",epoch).put("sourceOperationId",sourceOperation).put("targetDeviceId",target).put("targetInstanceId",instance).put("targetOperationId",targetOperation).put("observationRef",new JSONObject(reference)).put("resourceBinding",new JSONObject(resource)).put("contentDigest",digest).put("imageWidthPx",width).put("imageHeightPx",height).put("capturedAt",capturedAt).put("captureMethod","paired_device_verified_artifact").put("coordinateAuthority",false).put("untrustedDeviceContent",true).put("verified",false);}
    }
    private final String tool,operation,mime;private final RunControl original;private final Identity identity;private final DeviceFrameIdentity device;private final RunControl.AuthorityCheck current;
    private byte[] bytes;private boolean taken,closed;private Image delivered;
    private NativeToolObservation(String tool,String operation,RunControl original,Identity identity,byte[] encoded,String mime,RunControl.AuthorityCheck current)throws Exception{
        if(tool==null||tool.isEmpty()||operation==null||operation.isEmpty()||operation.length()>200||encoded==null||encoded.length<1||encoded.length>6*1024*1024||!java.util.Set.of("image/png","image/jpeg","image/webp").contains(mime))throw new IllegalArgumentException("invalid_native_visual_capture");
        this.tool=tool;this.operation=operation;this.original=Objects.requireNonNull(original);this.identity=Objects.requireNonNull(identity);device=null;this.current=Objects.requireNonNull(current);this.mime=mime;
        require(original);this.bytes=encoded.clone();
    }
    private NativeToolObservation(String tool,String operation,RunControl original,DeviceFrameIdentity identity,byte[] encoded,String mime,RunControl.AuthorityCheck current)throws Exception{
        if(tool==null||tool.isEmpty()||operation==null||operation.isEmpty()||operation.length()>200||encoded==null||encoded.length<1||encoded.length>6*1024*1024||!java.util.Set.of("image/png","image/jpeg","image/webp").contains(mime))throw new IllegalArgumentException("invalid_native_visual_capture");
        this.tool=tool;this.operation=operation;this.original=Objects.requireNonNull(original);this.identity=null;device=Objects.requireNonNull(identity);this.current=Objects.requireNonNull(current);this.mime=mime;require(original);bytes=encoded.clone();
    }
    /** Java host only: the supplied Identity must come from the actual native frame, never model arguments. */
    public static NativeToolObservation capture(String tool,String operation,RunControl original,Identity identity,byte[] encoded,String mime,RunControl.AuthorityCheck current)throws Exception{return new NativeToolObservation(tool,operation,original,identity,encoded,mime,current);}
    public static NativeToolObservation captureDevice(String tool,String operation,RunControl original,DeviceFrameIdentity identity,byte[] encoded,String mime,RunControl.AuthorityCheck current)throws Exception{return new NativeToolObservation(tool,operation,original,identity,encoded,mime,current);}
    private void require(RunControl actual)throws Exception{if(actual!=original||!actual.matchesHostRun(device==null?identity.taskId():device.taskId(),device==null?identity.runId():device.runId(),device==null?identity.epoch():device.epoch()))throw new SecurityException("native_visual_original_run_changed");actual.check();current.check();actual.check();}
    /** Only the matching original tool result can consume this image; a second take fails closed. */
    public Image consume(String tool,String operation,RunControl actual)throws Exception{
        require(actual);final Image image;
        synchronized(this){if(closed||taken||!this.tool.equals(tool)||!this.operation.equals(operation))throw new SecurityException("native_visual_capture_not_current");taken=true;image=new Image(bytes);bytes=null;delivered=image;}
        try{require(actual);synchronized(this){if(closed)throw new SecurityException("native_visual_capture_closed");}return image;}
        catch(Exception invalid){image.close();throw invalid;}
    }
    public final class Image implements AutoCloseable {
        private byte[] encoded;private Image(byte[] encoded){this.encoded=encoded;}
        public String base64(RunControl actual)throws Exception{
            final byte[] copy;synchronized(this){if(encoded==null)throw new IllegalStateException("native_visual_already_closed");copy=encoded.clone();}
            try{require(actual);String result=java.util.Base64.getEncoder().encodeToString(copy);require(actual);synchronized(NativeToolObservation.this){if(closed)throw new SecurityException("native_visual_capture_closed");}synchronized(this){if(encoded==null)throw new IllegalStateException("native_visual_already_closed");}return result;}
            finally{Arrays.fill(copy,(byte)0);}
        }
        public String mimeType(){return mime;}
        public JSONObject metadata()throws Exception{
            if(device!=null)return device.metadata().put("operationId",operation);
            Viewport v=identity.viewport();Crop c=v.crop();return new JSONObject().put("kind","browser_page_visual_snapshot").put("frameId","main").put("taskId",identity.taskId()).put("runId",identity.runId()).put("epoch",identity.epoch()).put("operationId",operation)
                .put("profileId",identity.profileId()).put("profileRevision",identity.profileRevision()).put("profileIsolation",identity.profileIsolation()).put("browserSessionId",identity.browserSessionId()).put("browserSpaceId",identity.browserSpaceId())
                .put("tabId",identity.tabId()).put("tabGeneration",identity.tabGeneration()).put("navigation",identity.navigation()).put("documentId",identity.documentId()).put("observationId",identity.observationId()).put("capturedAt",identity.capturedAt())
                .put("viewport",new JSONObject().put("widthPx",v.widthPx()).put("heightPx",v.heightPx()).put("scrollXPx",v.scrollXPx()).put("scrollYPx",v.scrollYPx()).put("scale",v.scale()).put("crop",new JSONObject().put("x",c.x()).put("y",c.y()).put("width",c.width()).put("height",c.height())).put("imageWidthPx",v.imageWidthPx()).put("imageHeightPx",v.imageHeightPx()))
                .put("captureMethod","android_window_pixelcopy").put("coordinateAuthority",false).put("untrustedPageContent",true).put("verified",false);
        }
        private synchronized void erase(){if(encoded!=null){Arrays.fill(encoded,(byte)0);encoded=null;}}
        @Override public void close(){erase();NativeToolObservation.this.close();}
    }
    @Override public void close(){final Image image;synchronized(this){closed=true;if(bytes!=null){Arrays.fill(bytes,(byte)0);bytes=null;}image=delivered;delivered=null;}if(image!=null)image.erase();}
}
