package dev.pocketengine.features.attachments.documents;

import dev.pocketengine.core.RunControl;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Reads only a verified immutable byte snapshot supplied by AttachmentDocuments. */
public final class DocumentReader {
    public static final int MAX_INPUT_BYTES=20*1024*1024;
    /** Native implementation must emit actual text; no filename or metadata substitution. */
    public interface PdfTextPort {
        boolean available();
        default String engineVersion() {return "mounted_pdf_text_engine";}
        void read(byte[] bytes, RequestSink sink, RunControl control) throws Exception;
    }
    public interface PdfOcrPort {
        boolean available();
        void recognizePages(byte[] bytes,OcrPort ocr,RequestSink sink,RunControl control)throws Exception;
    }
    public interface OcrPort {
        boolean available();
        String engineVersion();
        /** Recognize this one image locally. No implicit remote upload or model download. */
        void recognize(byte[] image,String format,RequestSink sink,RunControl control) throws Exception;
    }
    public interface RequestSink {
        DocumentContent.Request request();
        void totalUnits(int count);
        void text(int unit,String kind,Map<String,String> locator,String text) throws InterruptedException;
        void warning(String message);
        default boolean saturated() {return false;}
    }
    public static final class EncryptedDocumentException extends Exception {
        public EncryptedDocumentException() { super("Password required or encryption unsupported"); }
    }
    public static final class DocumentLimitException extends Exception {
        public DocumentLimitException(String message){super(message);}
    }
    public static final class InvalidDocumentException extends Exception {
        public InvalidDocumentException(String message){super(message);}
    }
    public static final class EngineUnavailableException extends Exception {
        public EngineUnavailableException(String message){super(message);}
    }
    private final PdfTextPort pdf;
    private final OcrPort ocr;
    private final PdfOcrPort pdfOcr;
    public DocumentReader(PdfTextPort pdf,OcrPort ocr) {this(pdf,ocr,null);}
    public DocumentReader(PdfTextPort pdf,OcrPort ocr,PdfOcrPort pdfOcr) {this.pdf=pdf;this.ocr=ocr;this.pdfOcr=pdfOcr;}
    public boolean pdfTextAvailable(){return pdf!=null&&pdf.available();}
    public boolean imageOcrAvailable(){return ocr!=null&&ocr.available();}
    public boolean pdfOcrAvailable(){return imageOcrAvailable()&&pdfOcr!=null&&pdfOcr.available();}
    public String engineVersion(String format,String extraction){
        if("ocr".equals(extraction)||format.equals("png")||format.equals("jpeg")||format.equals("webp"))return imageOcrAvailable()?ocr.engineVersion():"unavailable";
        return format.equals("pdf")?(pdfTextAvailable()?pdf.engineVersion():"unavailable"):DocumentContent.PARSER_VERSION;
    }
    public DocumentContent.Result read(byte[] bytes,DocumentContent.Request request,RunControl control)throws Exception {
        if(control==null)throw new IllegalArgumentException("Live RunControl required");
        if(bytes==null||request==null)throw new IllegalArgumentException("Document bytes and range required");
        control.checkpoint();
        if(bytes.length>MAX_INPUT_BYTES)return unavailable("unknown","limit_exceeded","Input exceeds 20 MiB");
        String format=identify(bytes);
        DocumentContent.Collector out=new DocumentContent.Collector(request);
        RequestSink sink=new RequestSink() {
            public DocumentContent.Request request(){return request;}
            public void totalUnits(int n){if(n<0||n>1000000)throw new IllegalArgumentException("Invalid unit count");out.unit(n);}
            public void text(int u,String k,Map<String,String> l,String t)throws InterruptedException {
                control.checkpoint();if(u<1||u>1000000||u<out.lastUnit||t==null||l==null)throw new IllegalArgumentException("Invalid document provenance");
                out.add(u,k,l,t);
            }
            public void warning(String warning){out.warning(warning);}
            public boolean saturated(){return out.nextUnit>0;}
        };
        try {
            if(request.extraction.equals("ocr")&&!format.equals("pdf")&&!format.equals("png")&&!format.equals("jpeg")&&!format.equals("webp"))return unavailable(format,"unsupported","OCR accepts image attachments or explicitly rendered PDF pages only");
            if(format.equals("zip"))return new OfficeDocumentReader().read(bytes,request,control);
            if(format.equals("pdf")) {
                if(request.extraction.equals("ocr")) {
                    if(!pdfOcrAvailable())return unavailable("pdf","unavailable","PDF OCR requires a mounted local OCR engine and PDF page renderer");
                    pdfOcr.recognizePages(bytes,ocr,ocrSink(sink),control);control.checkpoint();
                    out.warning("OCR is recognized text from rendered pages, not the PDF text layer; engine="+ocr.engineVersion());
                    return out.result("pdf","partial","rendered_page_ocr");
                }
                if(!pdfTextAvailable())return unavailable("pdf","unavailable","PDF text extraction requires the mounted Android 35 PDF engine");
                pdf.read(bytes,sink,control);control.checkpoint();
                out.warning("PDF text layer only; images, visual layout, annotations and forms are not extracted; use explicit OCR for image-only pages");
                return out.result("pdf","partial","pdf_text_layer");
            }
            if(format.equals("png")||format.equals("jpeg")||format.equals("webp")) {
                if(request.extraction.equals("text"))return unavailable(format,"unsupported","Image has no document text layer; request OCR explicitly or use auto");
                if(!imageOcrAvailable())return unavailable(format,"unavailable","OCR engine is not installed; no text was recognized");
                sink.totalUnits(1);if(request.startUnit==1)ocr.recognize(bytes,format,ocrSink(sink),control);control.checkpoint();out.warning("OCR is recognized text, not the original document text; engine="+ocr.engineVersion());
                return out.result(format,"partial","ocr");
            }
            if(format.equals("ole"))return unavailable("legacy_office_or_encrypted_ooxml","unsupported","Binary Office and OLE-wrapped encrypted Office are unsupported; encryption cannot be determined safely");
            if(!format.equals("text"))return unavailable(format,"unsupported","Unsupported binary format");
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if(text.indexOf('\0')>=0)return unavailable("unknown","unsupported","Binary input is not UTF-8 text");
            int unit=0,start=text.startsWith("\uFEFF")?1:0;
            for(int i=start;i<=text.length();i++) {if(i==text.length()||text.charAt(i)=='\n') {
                control.checkpoint();unit++;int end=i;if(end>start&&text.charAt(end-1)=='\r')end--;
                if(unit>1000000)throw new DocumentLimitException("Text has more than one million lines");
                out.unit(unit);out.add(unit,"text",DocumentContent.location("line",Integer.toString(unit)),text.substring(start,end));start=i+1;
            }}
            return out.result("text","ok","utf8_lines");
        }catch(EncryptedDocumentException encrypted) {return unavailable(format,"encrypted",encrypted.getMessage());}
        catch(DocumentLimitException limit) {return unavailable(format,"limit_exceeded",limit.getMessage());}
        catch(InvalidDocumentException invalid) {return unavailable(format,"invalid",invalid.getMessage());}
        catch(EngineUnavailableException unavailable) {return unavailable(format,"unavailable",unavailable.getMessage());}
        catch(CharacterCodingException binary) {return unavailable("unknown","unsupported","Input is not strict UTF-8");}
        catch(IOException invalid) {control.checkpoint();return unavailable(format,"invalid","Document could not be decoded completely");}
    }
    private static RequestSink ocrSink(RequestSink sink) {
        return new RequestSink(){
            public DocumentContent.Request request(){return sink.request();}
            public void totalUnits(int n){sink.totalUnits(n);}
            public void text(int u,String k,Map<String,String> l,String t)throws InterruptedException {sink.text(u,"ocr_text",l,t);}
            public void warning(String m){sink.warning(m);}
            public boolean saturated(){return sink.saturated();}
        };
    }
    static DocumentContent.Result unavailable(String format,String status,String reason) {
        return new DocumentContent.Result(format,status,"none",Collections.emptyList(),Collections.singletonList(reason),0,0,0);
    }
    static String identify(byte[] b) {
        if(starts(b,new int[]{0x25,0x50,0x44,0x46,0x2d}))return "pdf";
        if(starts(b,new int[]{0x50,0x4b,3,4})||starts(b,new int[]{0x50,0x4b,5,6}))return "zip";
        if(starts(b,new int[]{0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1}))return "ole";
        if(starts(b,new int[]{0x89,0x50,0x4e,0x47,13,10,26,10}))return "png";
        if(starts(b,new int[]{0xff,0xd8,0xff}))return "jpeg";
        if(b.length>=12 && new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "webp";
        return "text";
    }
    private static boolean starts(byte[] b,int[] prefix) {if(b.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if((b[i]&255)!=prefix[i])return false;return true;}
}
