package dev.pocketengine.features.devices;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.pocketengine.core.NativeToolObservation;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import dev.pocketengine.features.devices.transport.DeviceArtifactFetch;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native visual delivery on the existing Runner. Descriptors never stand in for image bytes.
 * Four bounded in-memory frames maximum; actions require the latest frame actually in a
 * completed model request, not merely downloaded, checkpointed or described by a tool. */
final class DeviceVisualFrames implements AutoCloseable {
    static final int MAX_SCREENSHOT_BYTES=6*1024*1024,MAX_DIMENSION=16384,MAX_PIXELS=16_000_000;
    private final DeviceCapabilityPort.VerifiedImages images;private final RunControl original;private final dev.pocketengine.store.TaskStore ownerTasks;
    private final LinkedHashMap<String,Frame> frames=new LinkedHashMap<>();
    private final LinkedHashMap<String,Set<Frame>> requests=new LinkedHashMap<>();
    private Frame pending;private boolean closed;private final java.util.Map<String,Long> latestCaptured=new LinkedHashMap<>();private final Set<String> retired=new LinkedHashSet<>();
    private final class Frame {
        final String route,function,referenceHash;final TaskStoreDeviceTasks.DeviceImageEvidence evidence;byte[] raw;String actualUrl;boolean seen;
        Frame(String route,String function,TaskStoreDeviceTasks.DeviceImageEvidence evidence,byte[] raw)throws Exception{referenceHash=DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(evidence.observationRef()));this.route=route;this.function=function;this.evidence=evidence;this.raw=raw;actualUrl="data:"+evidence.mimeType()+";base64,"+java.util.Base64.getEncoder().encodeToString(raw);}
        void require()throws Exception{if(closed||frames.get(route)!=this)throw new SecurityException("设备视觉帧已替换或关闭");evidence.requireCurrent();}
        void erase(){retired.add(referenceHash);if(raw!=null){Arrays.fill(raw,(byte)0);raw=null;}actualUrl=null;seen=false;}
    }
    DeviceVisualFrames(DeviceCapabilityPort.VerifiedImages images,RunControl original){this(images,original,null);}
    DeviceVisualFrames(DeviceCapabilityPort.VerifiedImages images,RunControl original,dev.pocketengine.store.TaskStore ownerTasks){this.images=images;this.original=original;this.ownerTasks=ownerTasks;}
    boolean installed(){return images!=null;}
    synchronized void invalidate(String route){Frame old=frames.remove(route);if(old!=null){if(pending==old)pending=null;old.erase();}}
    void capture(String route,String function,TaskStoreDeviceTasks.DeviceImageEvidence nativeEvidence)throws Exception{
        if(images==null)throw new SecurityException("真实设备图像接收器未安装");nativeEvidence.requireCurrent();String referenceHash=DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(nativeEvidence.observationRef()));long captured=nativeEvidence.observationRef().getLong("capturedAt");synchronized(this){Frame active=frames.get(route);if(retired.contains(referenceHash)||retired.size()>=4096)throw new SecurityException("历史/已消费画面不再成为动作授权，请重新观察");if(active!=null&&active.referenceHash.equals(referenceHash)){active.require();return;}if(captured<=latestCaptured.getOrDefault(route,0L))throw new SecurityException("历史查询不能覆盖更新的原画面");}DeviceArtifactFetch.Verified verified=null;byte[] raw=null;Bitmap decoded=null;
        try{
            verified=images.fetch(nativeEvidence);nativeEvidence.requireCurrent();if(verified==null)throw new SecurityException("未取得真实完整截图字节");
            JSONObject reference=verified.reference().json(),descriptor=nativeEvidence.descriptor();if(!descriptor.getString("artifactId").equals(reference.getString("artifactId"))||!descriptor.getString("contentDigest").equals(reference.getString("sha256"))||descriptor.getLong("size")!=reference.getLong("bytes"))throw new SecurityException("接收的截图不是原SQL固定内容");
            raw=verified.readImageBytes(MAX_SCREENSHOT_BYTES);nativeEvidence.requireCurrent();if(!descriptor.getString("contentDigest").equals(DeviceProtocol.sha256(raw)))throw new SecurityException("截图实际哈希不符");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
            if(bounds.outWidth!=nativeEvidence.imageWidth()||bounds.outHeight!=nativeEvidence.imageHeight()||bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>MAX_DIMENSION||bounds.outHeight>MAX_DIMENSION||(long)bounds.outWidth*bounds.outHeight>MAX_PIXELS||!nativeEvidence.mimeType().equals(bounds.outMimeType))throw new SecurityException("真实图像格式/像素尺寸与原观察不符");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.RGB_565;
            decoded=BitmapFactory.decodeByteArray(raw,0,raw.length,options);if(decoded==null||decoded.getWidth()!=bounds.outWidth||decoded.getHeight()!=bounds.outHeight)throw new SecurityException("实际截图无法完整解码");nativeEvidence.requireCurrent();
            // The owner's historical picture is not another capture, model delivery or action
            // reference. Optional encrypted retention must not turn a real tool receipt unknown.
            synchronized(this){if(closed||retired.contains(referenceHash)||captured<=latestCaptured.getOrDefault(route,0L))throw new SecurityException("原视觉接缝/画面代次已失效");invalidate(route);latestCaptured.put(route,captured);while(frames.size()>=4)invalidate(frames.keySet().iterator().next());Frame frame=new Frame(route,function,nativeEvidence,raw);frames.put(route,frame);pending=frame;raw=null;
                if(ownerTasks!=null)try{ownerTasks.retainOwnerDevicePhoneSnapshot(nativeEvidence,frame.raw);}catch(Exception unavailable){/* Original image/model delivery and receipt remain unchanged. */}
            }
        }finally{if(decoded!=null)decoded.recycle();if(raw!=null)Arrays.fill(raw,(byte)0);if(verified!=null)try{verified.discardTemporaryImage();}catch(Exception unavailableCleanup){/* Only a temporary received screenshot, never a Book/user file. Transport retries no business call. */}}
    }
    synchronized NativeToolObservation take(String tool,String consumptionOperation,RunControl actual)throws Exception{
        if(actual!=original)throw new SecurityException("设备截图不能交给另一个Runner");Frame selected=pending;if(selected==null||!selected.function.equals(tool)||selected.raw==null)return null;selected.require();pending=null;
        try{return NativeToolObservation.captureDevice(tool,consumptionOperation,original,selected.evidence.frameIdentity(),selected.raw,selected.evidence.mimeType(),()->{synchronized(DeviceVisualFrames.this){selected.require();}});}
        finally{Arrays.fill(selected.raw,(byte)0);selected.raw=null;}
    }
    synchronized void requireAction(String route,JSONObject reference)throws Exception{
        Frame selected=frames.get(route);if(selected==null||!selected.seen||reference==null||!DeviceProtocol.resourceEquals(selected.evidence.observationRef(),reference))throw new SecurityException("桌面动作必须引用最新且已实际送入当前模型的画面");selected.require();selected.seen=false;retired.add(selected.referenceHash); // Consume before prepare; unknown/refused actions need a fresh frame.
    }
    private boolean exactMessageFrame(JSONObject message,Frame frame)throws Exception{
        JSONArray parts=message.optJSONArray("content");boolean pixels=false,identity=false;for(int p=0;parts!=null&&p<parts.length();p++){JSONObject part=parts.optJSONObject(p);if(part==null)continue;JSONObject image=part.optJSONObject("image_url");if("image_url".equals(part.optString("type"))&&image!=null&&frame.actualUrl.equals(image.optString("url")))pixels=true;
            String text=part.optString("text"),prefix=dev.pocketengine.features.memory.TaskMemoryHistory.OBSERVATION_PREFIX;if("text".equals(part.optString("type"))&&text.startsWith(prefix)&&text.length()<64000)try{JSONObject data=new JSONObject(text.substring(prefix.length())),fixed=frame.evidence.descriptor();identity="paired_device_visual_snapshot".equals(data.optString("kind"))&&frame.evidence.sourceOperationId().equals(data.optString("sourceOperationId"))&&fixed.getString("executorDeviceId").equals(data.optString("targetDeviceId"))&&fixed.getString("instanceId").equals(data.optString("targetInstanceId"))&&fixed.getString("contentDigest").equals(data.optString("contentDigest"))&&DeviceProtocol.resourceEquals(frame.evidence.observationRef(),data.getJSONObject("observationRef"))&&DeviceProtocol.resourceEquals(fixed.getJSONObject("resourceBinding"),data.getJSONObject("resourceBinding"));}catch(Exception clippedOrDifferent){identity=false;}
        }return pixels&&identity;
    }
    synchronized void beforeRequest(String request,JSONArray projected,RunControl actual)throws Exception{
        if(actual!=original||closed)throw new SecurityException("原设备视觉请求已关闭");Set<Frame> visible=new LinkedHashSet<>();if(projected.length()>512)throw new SecurityException("原模型投影越界");
        for(Frame frame:frames.values()){if(frame.actualUrl==null)continue;frame.require();boolean found=false;for(int i=0;i<projected.length()&&!found;i++){JSONObject message=projected.optJSONObject(i);if(message==null||!"user".equals(message.optString("role")))continue;if(exactMessageFrame(message,frame))found=true;}if(found)visible.add(frame);}
        requests.put(request,java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(visible)));while(requests.size()>2)requests.remove(requests.keySet().iterator().next());
    }
    synchronized JSONObject afterResponse(String request,RunControl actual)throws Exception{
        if(actual!=original||closed)throw new SecurityException("原设备视觉响应已关闭");Set<Frame> visible=requests.remove(request);int provided=0;if(visible!=null)for(Frame frame:visible){frame.require();frame.seen=true;frame.actualUrl=null;provided++;}return provided==0?null:new JSONObject().put("kind","paired_device_visual_delivery").put("requestId",request).put("actualImagesProvided",provided).put("businessVerified",false);
    }
    synchronized void abandoned(String request){requests.remove(request);}
    @Override public synchronized void close(){closed=true;for(Frame frame:frames.values())frame.erase();frames.clear();requests.clear();latestCaptured.clear();retired.clear();pending=null;}
}
