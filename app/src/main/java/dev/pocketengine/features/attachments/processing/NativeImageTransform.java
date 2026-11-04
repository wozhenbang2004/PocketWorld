package dev.pocketengine.features.attachments.processing;

import android.graphics.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.OwnerFilePreviewSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Mature Android image codec/Canvas over this exact native source. No project JS/shell,
 * sampled-thumbnail edit, hidden file path, network, or implicit private publication. */
public final class NativeImageTransform {
    public static final class Review {
        private final OwnerFilePreviewSource source;public final ImageTransformPlan plan;
        private Review(OwnerFilePreviewSource source,ImageTransformPlan plan){this.source=source;this.plan=plan;}
        public OwnerFilePreviewSource source(){return source;}
        public JSONObject metadata()throws Exception{return plan.json();}
    }
    private NativeImageTransform(){}
    /** Actual codec facts only; supplied bytes grant neither source access nor model delivery. */
    public record EncodedImage(String mimeType,int width,int height) {}
    public static EncodedImage inspectEncodedForHost(byte[] bytes,RunControl original)throws Exception{
        if(bytes==null||bytes.length<1||bytes.length>2*1024*1024)throw new IllegalArgumentException("工程图片须为2MiB以内完整原件");
        try(var budget=OriginalFileProcessingBudget.acquire(original)){original.check();String magic=dev.pocketengine.media.MediaTransport.imagePayload(bytes).mimeType;EncodedImage actual=encodedImage(bytes);original.check();if(!magic.equals(actual.mimeType()))throw new SecurityException("图片magic与真实解码格式不同");return actual;}
    }
    public static JSONObject inspect(OwnerFilePreviewSource source,RunControl control)throws Exception{try(var budget=OriginalFileProcessingBudget.acquire(control)){byte[] bytes=source.bytes(control);try{int[] size=dimensions(bytes);source.requireCurrent(control);return new JSONObject().put("sourceIdentity",source.identity).put("sha256",source.sha256).put("width",size[0]).put("height",size[1]).put("editable",(long)size[0]*size[1]<=8_000_000L).put("coordinateSpace","oriented_original_pixels");}finally{java.util.Arrays.fill(bytes,(byte)0);}}}
    public static Review review(OwnerFilePreviewSource source,JSONObject options,RunControl control)throws Exception{
        try(var budget=OriginalFileProcessingBudget.acquire(control)){byte[] bytes=source.bytes(control);try{int[] actual=dimensions(bytes);source.requireCurrent(control);return new Review(source,new ImageTransformPlan(source.identity,source.sha256,actual[0],actual[1],options));}finally{java.util.Arrays.fill(bytes,(byte)0);}}
    }
    public static ProcessedOriginalFile execute(Review original,RunControl control)throws Exception{
        if(original==null)throw new SecurityException("请先审阅原图与精确加工范围");OwnerFilePreviewSource source=original.source;ImageTransformPlan plan=original.plan;byte[] bytes=null;Bitmap bitmap=null,crop=null,output=null;
        try(var budget=OriginalFileProcessingBudget.acquire(control)){
            bytes=source.bytes(control);int[] size=dimensions(bytes);if(size[0]!=plan.sourceWidth||size[1]!=plan.sourceHeight||!source.identity.equals(plan.sourceIdentity)||!source.sha256.equals(plan.sourceSha256))throw new SecurityException("原图实际尺寸或版本已变化");
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),(decoder,info,input)->{if(info.isAnimated())throw new IllegalArgumentException("动画原图不能在静态加工中丢失帧");decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setMutableRequired(false);});control.check();
            if(bitmap.getWidth()!=plan.sourceWidth||bitmap.getHeight()!=plan.sourceHeight)throw new SecurityException("解码后的原像素方向与审阅尺寸不同，未猜测裁剪坐标");
            output=Bitmap.createBitmap(plan.finalWidth(),plan.finalHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);if(plan.format.equals("jpeg"))canvas.drawColor(Color.WHITE);switch(plan.rotation){case 90:canvas.translate(plan.finalWidth(),0);canvas.rotate(90);break;case 180:canvas.translate(plan.finalWidth(),plan.finalHeight());canvas.rotate(180);break;case 270:canvas.translate(0,plan.finalHeight());canvas.rotate(270);break;default:break;}
            // Isolate the selected original rectangle before filtered scaling. Otherwise the
            // codec may sample neighboring pixels outside the owner-reviewed crop.
            crop=Bitmap.createBitmap(bitmap,plan.left,plan.top,plan.right-plan.left,plan.bottom-plan.top);
            canvas.drawBitmap(crop,new Rect(0,0,crop.getWidth(),crop.getHeight()),new RectF(0,0,plan.width,plan.height),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));control.check();ByteArrayOutputStream saved=new ByteArrayOutputStream();
            OutputStream bounded=new FilterOutputStream(saved){int count;@Override public void write(int v)throws IOException{write(new byte[]{(byte)v},0,1);}@Override public void write(byte[] b,int off,int n)throws IOException{if((count+=n)>8*1024*1024)throw new IOException("加工原件超过8MiB，未保存部分图片");try{control.check();}catch(Exception stopped){throw new InterruptedIOException("原图加工已取消");}out.write(b,off,n);}};
            if(!output.compress(plan.format.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,plan.quality,bounded))throw new IOException("原图编码没有成功回执");byte[] actual=saved.toByteArray();int[] checked=dimensions(actual);if(checked[0]!=plan.finalWidth()||checked[1]!=plan.finalHeight())throw new SecurityException("实际编码图像尺寸与加工方案不同");source.requireCurrent(control);return new ProcessedOriginalFile(actual,plan.format.equals("png")?"image/png":"image/jpeg",plan.format.equals("png")?"png":"jpg",source.identity,source.sha256,plan.identity,plan.json().put("outputWidth",checked[0]).put("outputHeight",checked[1]).put("lossy",plan.format.equals("jpeg")).put("encodedDimensionsChecked",true));
        }finally{if(bytes!=null)java.util.Arrays.fill(bytes,(byte)0);if(crop!=null&&crop!=bitmap)crop.recycle();if(bitmap!=null)bitmap.recycle();if(output!=null)output.recycle();}
    }
    private static int[] dimensions(byte[] bytes)throws Exception{
        EncodedImage image=encodedImage(bytes);return new int[]{image.width(),image.height()};
    }
    private static EncodedImage encodedImage(byte[] bytes)throws Exception{
        AtomicReference<EncodedImage> size=new AtomicReference<>();Bitmap thumbnail=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),(decoder,info,input)->{if(info.isAnimated()||!java.util.Set.of("image/png","image/jpeg","image/webp").contains(info.getMimeType()))throw new IllegalArgumentException("此入口支持静态PNG/JPEG/WebP，动画和其他编码保留原件，不丢帧转换");int w=info.getSize().getWidth(),h=info.getSize().getHeight();if(w<1||h<1||w>65536||h>65536)throw new IllegalArgumentException("原图尺寸无效");size.set(new EncodedImage(info.getMimeType(),w,h));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSize(1,1);});try{return java.util.Objects.requireNonNull(size.get());}finally{thumbnail.recycle();}
    }
}
