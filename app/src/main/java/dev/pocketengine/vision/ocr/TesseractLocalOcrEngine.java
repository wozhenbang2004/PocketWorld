package dev.pocketengine.vision.ocr;

import android.content.Context;
import android.os.Looper;
import com.googlecode.tesseract.android.ResultIterator;
import com.googlecode.tesseract.android.TessBaseAPI;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Published Tesseract4Android API, independently authored integration. No cloud/ML Kit fallback. */
public final class TesseractLocalOcrEngine implements LocalImageOcr {
    public enum Chinese { SIMPLIFIED, TRADITIONAL }
    private static final long TIMEOUT_MS = 30_000;
    private final OcrJobRunner jobs = new OcrJobRunner();
    private final BundledTesseractModels models;
    private final boolean traditional;
    private volatile boolean nativeUnavailable;

    public TesseractLocalOcrEngine(Context context) { this(context, Chinese.SIMPLIFIED); }
    /** A native owner's explicit language selection; never derived from OCR text or a model tool argument. */
    public TesseractLocalOcrEngine(Context context, Chinese chinese) {
        models = new BundledTesseractModels(java.util.Objects.requireNonNull(context));
        traditional = java.util.Objects.requireNonNull(chinese) == Chinese.TRADITIONAL;
    }
    @Override public boolean available() { return jobs.open() && models.packaged() && !nativeUnavailable; }
    @Override public String engineVersion() {
        return "tesseract4android-4.9.0/tesseract-5.5.1/tessdata-fast-" + BundledTesseractModels.REVISION
                + (traditional ? "/eng+chi_tra" : "/eng+chi_sim");
    }

    @Override public OcrPage recognizeImage(byte[] bytes, String format, RunControl control) throws Exception {
        if (control == null) throw new IllegalArgumentException("Live RunControl required");
        control.checkpoint();
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Local OCR requires a native worker caller");
        if (!available()) throw new DocumentReader.EngineUnavailableException("Bundled local OCR engine or models are unavailable");
        return jobs.run(signal -> recognize(bytes, format, control, signal), control, TIMEOUT_MS);
    }

    private OcrPage recognize(byte[] bytes, String format, RunControl control, OcrJobRunner.StopSignal signal) throws Exception {
        signal.check();
        File modelRoot;
        try { modelRoot = models.prepare(signal, traditional); }
        catch (IOException | SecurityException unavailable) {
            signal.check();
            throw new DocumentReader.EngineUnavailableException("Private local OCR model storage is unavailable");
        }
        try (OcrImageDecoder.Raster raster = OcrImageDecoder.decode(bytes, format, control)) {
            signal.check();
            final TessBaseAPI api;
            try { api = new TessBaseAPI(progress -> { if (signal.stopped() || signal.expired() || control.isCancelled()) signal.stop(); }); }
            catch (LinkageError unavailable) {
                nativeUnavailable = true;
                throw new DocumentReader.EngineUnavailableException("Local OCR native library is not loadable for this device ABI");
            }
            try {
                if (!api.init(modelRoot.getAbsolutePath(), traditional ? "eng+chi_tra" : "eng+chi_sim", TessBaseAPI.OEM_LSTM_ONLY))
                    throw new DocumentReader.EngineUnavailableException("Local OCR could not initialize its verified language models");
                signal.installStop(api::stop); signal.check();
                api.setDebug(false);
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                api.setImage(raster.bitmap); signal.check();
                // Only this recognition entry supports native stop. The returned markup is never rendered or exposed.
                String hocr = api.getHOCRText(0); signal.check();
                if (hocr == null) throw new DocumentReader.EngineUnavailableException("Local OCR did not complete recognition");
                if (hocr.length() > 2 * 1024 * 1024) throw new DocumentReader.DocumentLimitException("Local OCR layout output exceeds its memory budget");
                List<OcrPage.Line> lines = new ArrayList<>();
                ResultIterator iterator = api.getResultIterator();
                if (iterator != null) try {
                    iterator.begin(); int characters = 0, inspected = 0;
                    do {
                        signal.check();
                        if (++inspected > OcrPage.MAX_LINES) throw new DocumentReader.DocumentLimitException("Local OCR detected too many text lines");
                        String text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (text == null || text.trim().isEmpty()) continue;
                        text = withoutTrailingWhitespace(text); characters += text.length();
                        if (characters > OcrPage.MAX_TEXT_CHARS) throw new DocumentReader.DocumentLimitException("Local OCR text exceeds its character budget");
                        int[] box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE);
                        if (box == null || box.length != 4 || box[0] < 0 || box[1] < 0 || box[2] <= box[0] || box[3] <= box[1]
                                || box[2] > raster.bitmap.getWidth() || box[3] > raster.bitmap.getHeight())
                            throw new DocumentReader.InvalidDocumentException("Local OCR returned invalid image-local text bounds");
                        lines.add(new OcrPage.Line(lines.size() + 1, text, box[0], box[1], box[2], box[3]));
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE));
                } finally { iterator.delete(); }
                signal.check();
                List<OcrPage.Word> words=new ArrayList<>();boolean wordsTruncated=false;
                ResultIterator wordIterator=api.getResultIterator();
                if(wordIterator!=null){
                    try{
                        wordIterator.begin();int inspected=0,characters=0;
                        do{
                            signal.check();if(++inspected>OcrPage.MAX_WORDS){wordsTruncated=true;break;}
                            String wordText=wordIterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD);
                            if(wordText==null||wordText.trim().isEmpty())continue;
                            OcrPage.Word word=projectedWord(inspected,wordText,wordIterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD),wordIterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD),
                                    wordIterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE),wordIterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE),lines);
                            if(word==null){wordsTruncated=true;continue;}
                            if(characters+word.text.length()>OcrPage.MAX_WORD_TEXT_CHARS){wordsTruncated=true;break;}
                            words.add(word);characters+=word.text.length();
                        }while(wordIterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD));
                    }finally{wordIterator.delete();}
                }else if(!lines.isEmpty())wordsTruncated=true;
                signal.check();
                return new OcrPage(raster.sourceWidth, raster.sourceHeight, raster.bitmap.getWidth(), raster.bitmap.getHeight(), lines,words,wordsTruncated);
            } finally { signal.release(api::recycle); }
        } catch (LinkageError unavailable) {
            nativeUnavailable = true;
            throw new DocumentReader.EngineUnavailableException("Local OCR native runtime is unavailable");
        } catch (RuntimeException failed) {
            control.checkpoint();
            throw new DocumentReader.EngineUnavailableException("Local OCR failed to process this image; no recognized result was delivered");
        }
    }
    /** Exact iterator line anchor only. Missing/ambiguous geometry is omitted, never guessed by splitting text. */
    static OcrPage.Word projectedWord(int index,String text,int[] box,float confidence,String lineText,int[] lineBox,List<OcrPage.Line> lines){
        if(text==null||lineText==null||box==null||box.length!=4||lineBox==null||lineBox.length!=4)return null;
        OcrPage.Line parent=null;String expected=withoutTrailingWhitespace(lineText);
        for(OcrPage.Line line:lines)if(line.left==lineBox[0]&&line.top==lineBox[1]&&line.right==lineBox[2]&&line.bottom==lineBox[3]&&line.text.equals(expected)){
            if(parent!=null)return null;parent=line;
        }
        if(parent==null||box[0]<parent.left||box[1]<parent.top||box[2]>parent.right||box[3]>parent.bottom)return null;
        try{return new OcrPage.Word(index,parent.index,withoutTrailingWhitespace(text),box[0],box[1],box[2],box[3],confidence);}catch(IllegalArgumentException invalid){return null;}
    }
    private static String withoutTrailingWhitespace(String text) {
        // String.stripTrailing is not part of the Android 30 API contract.
        int end = text.length();
        while (end > 0) {
            int codePoint = text.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }
    @Override public void close() { jobs.close(); }
}
