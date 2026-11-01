package dev.pocketengine.features.attachments;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** A foreground reader of an original host-issued source. It reuses the original bounded,
 * expiring AttachmentViewSnapshots cache; never another artifact catalog or model URI tool. */
public final class NativeOriginalFileSession implements AutoCloseable {
    public static final int MAX_RASTER_PIXELS=4*1024*1024;
    private final Context context;private final AttachmentViewSnapshots views;private final String token;
    private final OwnerFilePreviewSource original;private final RunControl lifetime;private final Runnable cancel=this::close;
    private boolean closed;private PdfRenderer pdf;private final List<ParcelFileDescriptor> descriptors=new ArrayList<>();
    private NativeOriginalFileSession(Context context,AttachmentViewSnapshots views,String token,OwnerFilePreviewSource original,RunControl lifetime){this.context=context.getApplicationContext();this.views=views;this.token=token;this.original=original;this.lifetime=lifetime;}
    public static NativeOriginalFileSession open(Context context,OwnerFilePreviewSource original,RunControl lifetime)throws Exception {
        worker();original.requireCurrent(lifetime);if(original.tombstone)throw new IllegalStateException("原删除修订没有文件正文，不打开空文件");byte[] bytes=original.bytes(lifetime);AttachmentViewSnapshots views=new AttachmentViewSnapshots(context);Intent prepared=null;
        try{
            prepared=views.prepare(original.name,original.mimeType,original.sha256,bytes,lifetime);original.requireCurrent(lifetime);
            NativeOriginalFileSession session=new NativeOriginalFileSession(context,views,prepared.getData().getLastPathSegment(),original,lifetime);lifetime.onCancel(session.cancel);session.current();return session;
        }catch(Exception failure){if(prepared!=null)try{views.discard(prepared.getData().getLastPathSegment());}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}finally{Arrays.fill(bytes,(byte)0);}
    }
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("原文件解码必须在工作线程执行");}
    public OwnerFilePreviewSource source(){return original;}
    public void current()throws Exception{lifetime.check();synchronized(this){if(closed)throw new IllegalStateException("原文件查看已关闭");}original.requireCurrent(lifetime);}
    private File file()throws Exception{current();File file=views.file(token);if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()!=original.sizeBytes)throw new SecurityException("固定查看副本不再是原文件");return file;}
    /** Native media decoder receives a local FD only, never an external URL/playlist. Caller closes it. */
    public ParcelFileDescriptor openMediaDescriptor()throws Exception {
        worker();File file=file();byte[] signature=new byte[16];try(InputStream in=new FileInputStream(file)){if(in.read(signature)<4)throw new IllegalArgumentException("媒体文件头不完整");}
        boolean mp4=signature[4]=='f'&&signature[5]=='t'&&signature[6]=='y'&&signature[7]=='p';boolean webm=(signature[0]&255)==0x1a&&(signature[1]&255)==0x45&&(signature[2]&255)==0xdf&&(signature[3]&255)==0xa3;
        boolean ogg=signature[0]=='O'&&signature[1]=='g'&&signature[2]=='g'&&signature[3]=='S';boolean wave=signature[0]=='R'&&signature[1]=='I'&&signature[2]=='F'&&signature[3]=='F'&&signature[8]=='W'&&signature[9]=='A'&&signature[10]=='V'&&signature[11]=='E';boolean mp3=signature[0]=='I'&&signature[1]=='D'&&signature[2]=='3'||(signature[0]&255)==255&&(signature[1]&0xe0)==0xe0;
        if(!mp4&&!webm&&!ogg&&!wave&&!mp3)throw new IllegalArgumentException("此文件不是已支持的本地MP4/WebM/Ogg/WAV/MP3容器；不解释播放列表或外链");
        ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);synchronized(this){if(closed){descriptor.close();throw new IllegalStateException("原文件查看已关闭");}descriptors.add(descriptor);}current();return descriptor;
    }
    private PdfRenderer pdf()throws Exception {
        worker();if(!"application/pdf".equals(original.mimeType))throw new IllegalArgumentException("所选原件不是PDF");File file=file();
        synchronized(this){if(closed)throw new IllegalStateException("原PDF已关闭");if(pdf==null){ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);try{pdf=new PdfRenderer(descriptor);}catch(Exception failure){descriptor.close();throw failure;}if(pdf.getPageCount()>1000000){pdf.close();pdf=null;throw new IllegalArgumentException("PDF页数超过阅读范围");}}return pdf;}
    }
    public int pdfPages()throws Exception{PdfRenderer renderer=pdf();int count;synchronized(this){count=renderer.getPageCount();}current();return count;}
    public Bitmap pdfPage(int number,int requestedWidth)throws Exception {
        PdfRenderer renderer=pdf();Bitmap image=null;
        try{synchronized(this){if(number<1||number>renderer.getPageCount()||requestedWidth<64||requestedWidth>4096)throw new IllegalArgumentException("原PDF页码或显示尺寸无效");try(PdfRenderer.Page page=renderer.openPage(number-1)){
                int sourceWidth=page.getWidth(),sourceHeight=page.getHeight();if(sourceWidth<1||sourceHeight<1)throw new IllegalArgumentException("原PDF页尺寸无效");double scale=Math.min((double)requestedWidth/sourceWidth,Math.sqrt((double)MAX_RASTER_PIXELS/((double)sourceWidth*sourceHeight)));int width=Math.max(1,(int)Math.floor(sourceWidth*scale)),height=Math.max(1,(int)Math.floor(sourceHeight*scale));image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }}current();return image;}catch(Exception invalid){if(image!=null)image.recycle();throw invalid;}
    }
    public boolean pdfSearchAvailable(){return Build.VERSION.SDK_INT>=35;}
    /** Native PDF search over at most twenty original pages. No text layer is not an OCR result. */
    public JSONObject pdfSearch(String query,int firstPage,RunControl control)throws Exception {
        worker();if(!pdfSearchAvailable())throw new IllegalStateException("此设备未提供PDF原文搜索API；原页查看仍可用，也可明确选择原OCR入口");if(query==null||query.isEmpty()||query.length()>256)throw new IllegalArgumentException("PDF查找文字为1至256字符");
        PdfRenderer renderer=pdf();int pages=pdfPages();if(firstPage<1||firstPage>pages)throw new IllegalArgumentException("原PDF查找页码无效");JSONArray hits=new JSONArray();int next=firstPage;boolean omitted=false;long until=System.nanoTime()+20_000_000_000L;
        for(;next<=pages&&next<firstPage+20;next++){
            current();control.check();if(System.nanoTime()>until)break;
            synchronized(this){try(PdfRenderer.Page page=renderer.openPage(next-1)){Api35.search(page,next,query,hits);}}
            if(hits.length()>=100){omitted=true;next++;break;}
        }
        current();control.check();return new JSONObject().put("query",query).put("matches",hits).put("firstPage",firstPage).put("scannedThrough",next-1).put("nextPage",next>pages?JSONObject.NULL:next).put("atEnd",next>pages&&!omitted).put("pageMatchesOmitted",omitted).put("sha256",original.sha256).put("textLayerOnly",true);
    }
    private static final class Api35 {
        static void search(PdfRenderer.Page page,int number,String query,JSONArray hits)throws Exception {
            for(var match:page.searchText(query)){JSONArray boxes=new JSONArray();for(RectF box:match.getBounds()){if(boxes.length()>=16)break;boxes.put(new JSONArray().put(box.left/page.getWidth()).put(box.top/page.getHeight()).put(box.right/page.getWidth()).put(box.bottom/page.getHeight()));}hits.put(new JSONObject().put("page",number).put("textStart",match.getTextStartIndex()).put("boxes",boxes));if(hits.length()>=100)break;}
        }
    }
    public static final class ImageFrame {
        public final Bitmap bitmap;public final int originalWidth,originalHeight;public final boolean sampled;
        private ImageFrame(Bitmap image,int width,int height){bitmap=image;originalWidth=width;originalHeight=height;sampled=image.getWidth()!=width||image.getHeight()!=height;}
    }
    public ImageFrame image()throws Exception {
        worker();if(!original.mimeType.startsWith("image/"))throw new IllegalArgumentException("原件不是图片");File file=file();final int[] dimensions=new int[2];Bitmap image=null;
        try{image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,source)->{int width=info.getSize().getWidth(),height=info.getSize().getHeight();if(width<1||height<1||width>65536||height>65536)throw new IllegalArgumentException("原图片尺寸超出显示范围");dimensions[0]=width;dimensions[1]=height;decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);double scale=Math.min(1d,Math.sqrt((double)MAX_RASTER_PIXELS/((double)width*height)));if(scale<1)decoder.setTargetSize(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)));decoder.setOnPartialImageListener(error->false);});current();return new ImageFrame(image,dimensions[0],dimensions[1]);}catch(Exception invalid){if(image!=null)image.recycle();throw invalid;}
    }
    public JSONObject text(String encoding,int offset,int maxChars,RunControl control)throws Exception {worker();File selected=file();JSONObject page=new OwnerTextDocument(()->new FileInputStream(selected)).page(encoding,offset,maxChars,control);current();return page.put("sha256",original.sha256).put("sourceIdentity",original.identity);}
    public JSONObject searchText(String encoding,String query,int offset,RunControl control)throws Exception {worker();File selected=file();JSONObject found=new OwnerTextDocument(()->new FileInputStream(selected)).search(encoding,query,offset,control);current();return found.put("sha256",original.sha256).put("sourceIdentity",original.identity);}
    public DocumentContent.Result document(int unit,int chunk,RunControl control)throws Exception {
        worker();current();byte[] bytes=original.bytes(control);try{DocumentReader reader=new DocumentReader(new AndroidPdfDocumentPort(context),null);boolean word=original.mimeType.contains("wordprocessingml")||original.name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx");DocumentContent.Result result=reader.read(bytes,new DocumentContent.Request(unit,chunk,word?16:1,16000,128,"text"),control);current();return result;}finally{Arrays.fill(bytes,(byte)0);}
    }
    @Override public void close(){
        synchronized(this){if(closed)return;closed=true;try{if(pdf!=null)pdf.close();}finally{pdf=null;for(ParcelFileDescriptor descriptor:descriptors)try{descriptor.close();}catch(IOException ignored){}descriptors.clear();}}
        lifetime.removeCancelAction(cancel);try{views.discard(token);}catch(Exception ignored){/* Same expiring cache is retained for its original bounded cleanup, never canonical data. */}
    }
}
