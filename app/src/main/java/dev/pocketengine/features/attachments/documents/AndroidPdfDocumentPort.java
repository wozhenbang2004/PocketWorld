package dev.pocketengine.features.attachments.documents;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageTextContent;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform PDF API only. Mount on a worker, preferably in a minimal-permission isolated parser process. */
public final class AndroidPdfDocumentPort implements DocumentReader.PdfTextPort {
    private static final int MAX_PAGE_TEXT=1024*1024, MAX_RASTER_PIXELS=4*1024*1024;
    private final Context context;
    public AndroidPdfDocumentPort(Context context){this.context=context.getApplicationContext();}
    @Override public boolean available(){return Build.VERSION.SDK_INT>=35;}
    @Override public String engineVersion(){return "android-pdf-api35-sdk"+Build.VERSION.SDK_INT;}
    @Override public void read(byte[] bytes,DocumentReader.RequestSink sink,RunControl control)throws Exception {
        if(!available())throw new IllegalStateException("PDF text API requires Android 15");
        withPdf(bytes,control,renderer->{
            int total=renderer.getPageCount();checkPages(total);sink.totalUnits(total);
            DocumentContent.Request request=sink.request();
            for(int index=request.startUnit-1;index<total&&index<request.startUnit-1+request.maxUnits;index++) {
                control.checkpoint();
                try(PdfRenderer.Page page=renderer.openPage(index)) {Api35.text(page,index+1,sink,control);}
                control.checkpoint();if(sink.saturated())break;
            }
        });
    }
    /** PDF OCR is an explicit operation; default PDF text reads never silently start recognition. */
    public DocumentReader.PdfOcrPort ocrPages() {
        return new DocumentReader.PdfOcrPort(){
            public boolean available(){return Build.VERSION.SDK_INT>=30;}
            public void recognizePages(byte[] bytes,DocumentReader.OcrPort ocr,DocumentReader.RequestSink sink,RunControl control)throws Exception {
                if(sink.request().maxUnits>4)throw new DocumentReader.DocumentLimitException("PDF OCR reads at most four pages per request");
                withPdf(bytes,control,renderer->{
                    int total=renderer.getPageCount();checkPages(total);sink.totalUnits(total);
                    DocumentContent.Request request=sink.request();
                    for(int index=request.startUnit-1;index<total&&index<request.startUnit-1+request.maxUnits;index++) {
                        control.checkpoint();final int unit=index+1;
                        try(PdfRenderer.Page page=renderer.openPage(index)) {
                            int pageWidth=page.getWidth(),pageHeight=page.getHeight();
                            if(pageWidth<=0||pageHeight<=0)throw new DocumentReader.InvalidDocumentException("Invalid PDF page dimensions");
                            double scale=Math.min(2d,Math.min(4096d/Math.max(pageWidth,pageHeight),Math.sqrt((double)MAX_RASTER_PIXELS/((double)pageWidth*pageHeight))));
                            int width=Math.max(1,(int)Math.floor(pageWidth*scale)),height=Math.max(1,(int)Math.floor(pageHeight*scale));
                            Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                            try {
                                image.eraseColor(Color.WHITE);page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);control.checkpoint();
                                ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                                if(!image.compress(Bitmap.CompressFormat.PNG,100,encoded))throw new IOException("PDF page image could not be encoded");
                                control.checkpoint();if(encoded.size()>DocumentReader.MAX_INPUT_BYTES)throw new DocumentReader.DocumentLimitException("Rendered PDF page exceeds OCR input limit");
                                ocr.recognize(encoded.toByteArray(),"png",new DocumentReader.RequestSink(){
                                    public DocumentContent.Request request(){return request;}
                                    public void totalUnits(int ignored){}
                                    public void text(int ignored,String kind,Map<String,String> locator,String text)throws InterruptedException {
                                        Map<String,String> source=new LinkedHashMap<>(locator);
                                        source.put("page",Integer.toString(unit));source.put("source","rendered_pdf_page");
                                        source.put("pageWidthPoints",Integer.toString(pageWidth));source.put("pageHeightPoints",Integer.toString(pageHeight));
                                        source.put("rasterWidth",Integer.toString(width));source.put("rasterHeight",Integer.toString(height));
                                        sink.text(unit,"ocr_text",source,text);
                                    }
                                    public void warning(String message){sink.warning(message);}
                                    public boolean saturated(){return sink.saturated();}
                                },control);
                            }finally{image.recycle();}
                        }
                        control.checkpoint();if(sink.saturated())break;
                    }
                });
            }
        };
    }
    private interface PdfAction {void run(PdfRenderer renderer)throws Exception;}
    private void withPdf(byte[] bytes,RunControl control,PdfAction action)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("PDF decoding requires a native worker thread");
        if(bytes.length>DocumentReader.MAX_INPUT_BYTES)throw new DocumentReader.DocumentLimitException("PDF exceeds 20 MiB");
        control.checkpoint();File temporary=File.createTempFile("attachment-document-",".pdf",context.getCacheDir());
        try {
            try(FileOutputStream output=new FileOutputStream(temporary)) {
                for(int at=0;at<bytes.length;at+=32768){control.checkpoint();output.write(bytes,at,Math.min(32768,bytes.length-at));}
            }
            control.checkpoint();
            try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(temporary,ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer renderer=open(descriptor)) {action.run(renderer);control.checkpoint();}
        }finally{temporary.delete();}
    }
    private static PdfRenderer open(ParcelFileDescriptor descriptor)throws Exception {
        try{return new PdfRenderer(descriptor);}catch(SecurityException encrypted){throw new DocumentReader.EncryptedDocumentException();}
    }
    private static void checkPages(int total)throws DocumentReader.DocumentLimitException {
        if(total>1000000)throw new DocumentReader.DocumentLimitException("PDF has too many pages");
    }
    private static final class Api35 {
        static void text(PdfRenderer.Page page,int unit,DocumentReader.RequestSink sink,RunControl control)throws Exception {
            List<PdfPageTextContent> blocks=page.getTextContents();control.checkpoint();int characters=0,index=0;
            if(blocks.isEmpty())sink.warning("Page "+unit+" has no extractable text layer; it may be blank or image-only");
            for(PdfPageTextContent block:blocks) {
                control.checkpoint();String text=block.getText();characters+=text.length();
                if(characters>MAX_PAGE_TEXT)throw new DocumentReader.DocumentLimitException("PDF page text exceeds 1 MiB of UTF-16 characters");
                Map<String,String> locator=DocumentContent.location("page",Integer.toString(unit),"textBlock",Integer.toString(++index),"coordinateUnit","pdf_points");
                List<RectF> bounds=block.getBounds();
                if(!bounds.isEmpty()) {
                    RectF union=new RectF(bounds.get(0));for(int n=1;n<bounds.size();n++){control.checkpoint();union.union(bounds.get(n));}
                    locator.put("boundsUnion",union.left+","+union.top+","+union.right+","+union.bottom);
                }
                sink.text(unit,"pdf_text",locator,text);if(sink.saturated())break;
            }
        }
    }
}
