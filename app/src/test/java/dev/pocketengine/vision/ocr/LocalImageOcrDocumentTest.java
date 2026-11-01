package dev.pocketengine.vision.ocr;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.documents.DocumentContent;
import dev.pocketengine.features.attachments.documents.DocumentFixtures;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Port provenance/cursor checks, with fixture output clearly separate from device recognition. */
public final class LocalImageOcrDocumentTest {
    private static class FixtureOcr implements LocalImageOcr {
        final AtomicInteger calls=new AtomicInteger();
        public boolean available(){return true;}
        public String engineVersion(){return "fixture-not-native-recognition";}
        public void close(){}
        public OcrPage recognizeImage(byte[] bytes,String format,RunControl control)throws Exception {
            assertArrayEquals(DocumentFixtures.actual("scan.png"),bytes);assertEquals("png",format);calls.incrementAndGet();
            return new OcrPage(100,80,100,80,Arrays.asList(new OcrPage.Line(1,"first "+"😀".repeat(700),1,2,90,20),new OcrPage.Line(2,"真实第二行",2,30,99,50)));
        }
    }
    @Test public void existingDocumentReaderReceivesOneEngineWithCursorAndImageLocalProvenance()throws Exception {
        FixtureOcr ocr=new FixtureOcr();DocumentReader reader=new DocumentReader(null,ocr);StringBuilder recovered=new StringBuilder();int unit=1,chunk=0;
        do {
            DocumentContent.Result result=reader.read(DocumentFixtures.actual("scan.png"),new DocumentContent.Request(unit,chunk,1,2048,1,"ocr"),new RunControl());
            assertEquals("ocr",result.coverage);
            for(DocumentContent.Chunk item:result.chunks){assertEquals("ocr_text",item.kind);assertEquals(OcrPage.COORDINATE_SPACE,item.locator.get("coordinateSpace"));assertFalse(item.locator.containsKey("screenX"));recovered.append(item.text);}
            unit=result.nextUnit;chunk=result.nextChunk;
        }while(unit>0);
        assertEquals("first "+"😀".repeat(700)+"真实第二行",recovered.toString());assertEquals(3,ocr.calls.get());
    }
    @Test public void beyondSingleImageRangeDoesNotRunRecognition()throws Exception {
        FixtureOcr ocr=new FixtureOcr();DocumentContent.Result result=new DocumentReader(null,ocr).read(DocumentFixtures.actual("scan.png"),new DocumentContent.Request(2,0,1,2048,1,"ocr"),new RunControl());
        assertTrue(result.chunks.isEmpty());assertEquals(0,ocr.calls.get());
    }
    @Test public void renderedPdfPageAfterPageOneStillUsesTheSameImageEngine()throws Exception {
        FixtureOcr ocr=new FixtureOcr();
        DocumentReader.PdfOcrPort renderer=new DocumentReader.PdfOcrPort(){
            public boolean available(){return true;}
            public void recognizePages(byte[] pdf,DocumentReader.OcrPort engine,DocumentReader.RequestSink sink,RunControl control)throws Exception{
                sink.totalUnits(7);
                engine.recognize(DocumentFixtures.actual("scan.png"),"png",new DocumentReader.RequestSink(){
                    public DocumentContent.Request request(){return sink.request();}
                    public void totalUnits(int n){}
                    public void text(int unit,String kind,java.util.Map<String,String> locator,String text)throws InterruptedException{
                        java.util.Map<String,String> source=new java.util.LinkedHashMap<>(locator);source.put("page","4");source.put("source","rendered_pdf_page");sink.text(4,kind,source,text);
                    }
                    public void warning(String message){sink.warning(message);}
                    public boolean saturated(){return sink.saturated();}
                },control);
            }
        };
        DocumentContent.Result result=new DocumentReader(null,ocr,renderer).read(DocumentFixtures.actual("image-only.pdf"),new DocumentContent.Request(4,0,1,12000,10,"ocr"),new RunControl());
        assertEquals(1,ocr.calls.get());assertFalse(result.chunks.isEmpty());assertEquals(4,result.chunks.get(0).unit);assertEquals("4",result.chunks.get(0).locator.get("page"));assertEquals("rendered_pdf_page",result.chunks.get(0).locator.get("source"));
    }
    @Test public void cancellationAndNativeUnavailableNeverReturnSyntheticChunks()throws Exception {
        FixtureOcr stopping=new FixtureOcr(){public OcrPage recognizeImage(byte[] b,String f,RunControl c)throws Exception{c.cancel();return new OcrPage(1,1,1,1,Collections.singletonList(new OcrPage.Line(1,"must not publish",0,0,1,1)));}};
        assertThrows(InterruptedException.class,()->new DocumentReader(null,stopping).read(DocumentFixtures.actual("scan.png"),DocumentContent.Request.defaults(),new RunControl()));
        FixtureOcr unavailable=new FixtureOcr(){public OcrPage recognizeImage(byte[] b,String f,RunControl c)throws Exception{throw new DocumentReader.EngineUnavailableException("native missing");}};
        DocumentContent.Result result=new DocumentReader(null,unavailable).read(DocumentFixtures.actual("scan.png"),DocumentContent.Request.defaults(),new RunControl());
        assertEquals("unavailable",result.status);assertTrue(result.chunks.isEmpty());
    }
}
