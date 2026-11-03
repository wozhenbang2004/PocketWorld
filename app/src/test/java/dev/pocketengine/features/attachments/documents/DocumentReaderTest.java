package dev.pocketengine.features.attachments.documents;

import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DocumentReaderTest {
    private final DocumentReader reader=new DocumentReader(null,null);
    private DocumentContent.Result read(byte[] bytes)throws Exception{return reader.read(bytes,DocumentContent.Request.defaults(),new RunControl());}
    @Test public void fullOfficeGeneratedFilesExtractRealContentAndHonestCoverage()throws Exception {
        DocumentContent.Result word=read(DocumentFixtures.actual("minimal.docx"));assertEquals("docx",word.format);assertEquals("Original DOCX invoice 123",word.chunks.get(0).text);assertEquals("Actual table A",word.chunks.get(1).text);assertEquals("table_cell",word.chunks.get(1).kind);
        DocumentContent.Result sheet=read(DocumentFixtures.actual("minimal.xlsx"));assertEquals("xlsx",sheet.format);assertEquals("真实单元格",sheet.chunks.get(0).text);assertEquals("123.5",sheet.chunks.get(1).text);assertEquals("formula",sheet.chunks.get(2).kind);assertEquals("B2*2",sheet.chunks.get(2).text);assertEquals("Hidden sheet cell",sheet.chunks.get(3).text);assertEquals("hidden",sheet.chunks.get(3).locator.get("sheetState"));
        DocumentContent.Result slides=read(DocumentFixtures.actual("minimal.pptx"));assertEquals("pptx",slides.format);assertEquals("Original PPTX slide 1",slides.chunks.get(0).text);assertEquals("table_cell",slides.chunks.get(1).kind);assertEquals("Actual table A",slides.chunks.get(1).text);assertEquals("Original PPTX slide 2",slides.chunks.get(3).text);assertEquals(2,slides.totalUnits);
    }
    @Test public void realTextScanAndEncryptedPdfFixturesRequireAnActualMountedNativeEngine()throws Exception {
        for(String file:new String[]{"text.pdf","image-only.pdf","encrypted.pdf","scan.png"}) {
            DocumentContent.Result result=read(DocumentFixtures.actual(file));assertEquals("unavailable",result.status);assertTrue(result.chunks.isEmpty());
        }
    }
    @Test public void docxReadsBodyAndActualTableCellsWithoutDeletedOrDrawingText()throws Exception {
        String body=DocumentFixtures.paragraph("Hello &amp; 世界")+"<w:del>"+DocumentFixtures.paragraph("deleted")+"</w:del><w:tbl><w:tr><w:tc>"+DocumentFixtures.paragraph("Cell 9")+"</w:tc></w:tr></w:tbl><w:p><w:r><w:drawing>"+DocumentFixtures.paragraph("drawing")+"</w:drawing></w:r></w:p>";
        DocumentContent.Result result=read(DocumentFixtures.word(body));assertEquals("docx",result.format);assertEquals("partial",result.status);
        assertEquals(2,result.chunks.size());assertEquals("Hello & 世界",result.chunks.get(0).text);
        DocumentContent.Chunk cell=result.chunks.get(1);assertEquals("table_cell",cell.kind);assertEquals("Cell 9",cell.text);assertEquals("1",cell.locator.get("row"));assertEquals("1",cell.locator.get("column"));assertEquals("word/document.xml",cell.locator.get("part"));
    }
    @Test public void spreadsheetUsesStoredValuesAndFormulaProvenance()throws Exception {
        DocumentContent.Result result=read(DocumentFixtures.spreadsheet());assertEquals("xlsx",result.format);assertEquals(5,result.chunks.size());
        assertEquals("真实单元格",result.chunks.get(0).text);assertEquals("A2",result.chunks.get(0).locator.get("cell"));assertEquals("预算",result.chunks.get(0).locator.get("sheet"));assertEquals("hidden",result.chunks.get(0).locator.get("sheetState"));
        assertEquals("42",result.chunks.get(2).text);assertEquals("cached_formula",result.chunks.get(2).locator.get("valueSource"));assertEquals("内联",result.chunks.get(3).text);
        assertEquals("formula",result.chunks.get(4).kind);assertEquals("SUM(B2:C2)",result.chunks.get(4).text);
    }
    @Test public void presentationFollowsRelationshipOrderAndLabelsTableText()throws Exception {
        DocumentContent.Result result=read(DocumentFixtures.presentation());assertEquals("pptx",result.format);assertEquals(2,result.totalUnits);assertEquals("Shown first",result.chunks.get(0).text);assertEquals("ppt/slides/slide2.xml",result.chunks.get(0).locator.get("part"));
        assertEquals("table_cell",result.chunks.get(1).kind);assertEquals("1",result.chunks.get(1).locator.get("row"));assertEquals("Shown second",result.chunks.get(2).text);assertEquals(2,result.chunks.get(2).unit);
    }
    @Test public void repeatedSlidesKeepDistinctUnitsAndCursorsWithinTheWorkBudget()throws Exception {
        for(boolean distinctRelationships:new boolean[]{false,true}) {
            byte[] bytes=DocumentFixtures.repeatedPresentation(3,0,distinctRelationships);
            for(int unit=1;unit<=3;unit++) {
                DocumentContent.Result result=reader.read(bytes,new DocumentContent.Request(unit,0,1,2048,1),new RunControl());
                assertEquals("partial",result.status);assertEquals("slide_text",result.coverage);assertEquals(3,result.totalUnits);
                assertEquals(1,result.chunks.size());assertEquals(unit,result.chunks.get(0).unit);
                assertEquals("Repeated slide",result.chunks.get(0).text);assertEquals(Integer.toString(unit),result.chunks.get(0).locator.get("slide"));
                assertEquals("ppt/slides/slide1.xml",result.chunks.get(0).locator.get("part"));
                assertEquals(unit<3?unit+1:0,result.nextUnit);assertEquals(0,result.nextChunk);assertEquals(unit<3,result.truncated);
            }
        }
    }
    @Test public void repeatedSlideParsingChargesEveryReferenceAndAcceptsTheExactBudget()throws Exception {
        int slideBytes=1024*1024;
        byte[] within=DocumentFixtures.repeatedPresentation(32,slideBytes,false);
        DocumentContent.Result accepted=reader.read(within,new DocumentContent.Request(31,0,2,2048,2),new RunControl());
        assertEquals("partial",accepted.status);assertEquals(32,accepted.totalUnits);assertEquals(2,accepted.chunks.size());assertFalse(accepted.truncated);
        for(boolean distinctRelationships:new boolean[]{false,true}) {
            byte[] repeated=DocumentFixtures.repeatedPresentation(33,slideBytes,distinctRelationships);
            assertTrue("Adversarial ZIP remains small",repeated.length<64*1024);
            DocumentContent.Result result=reader.read(repeated,new DocumentContent.Request(1,0,1,2048,1),new RunControl());
            assertEquals("pptx",result.format);assertEquals("limit_exceeded",result.status);assertEquals("none",result.coverage);
            assertTrue(result.chunks.isEmpty());assertEquals(0,result.totalUnits);assertFalse(result.truncated);
            assertTrue(result.warnings.get(0).contains("32 MiB cumulative"));
        }
    }
    @Test public void presentationReferenceCountIsBoundedWithoutRejectingLegalRepeats()throws Exception {
        DocumentContent.Result accepted=reader.read(DocumentFixtures.repeatedPresentation(1024,0,false),new DocumentContent.Request(1024,0,1,2048,1),new RunControl());
        assertEquals("partial",accepted.status);assertEquals(1024,accepted.totalUnits);assertEquals(1024,accepted.chunks.get(0).unit);assertFalse(accepted.truncated);
        DocumentContent.Result result=read(DocumentFixtures.repeatedPresentation(1025,0,false));
        assertEquals("limit_exceeded",result.status);assertTrue(result.chunks.isEmpty());assertEquals(0,result.totalUnits);
        assertTrue(result.warnings.get(0).contains("1024 slide references"));
    }
    @Test public void chunkCursorContinuesWithoutDuplicatesOrBrokenSurrogates()throws Exception {
        String text="a".repeat(1023)+"😀"+"z".repeat(4600);byte[] bytes=DocumentFixtures.word(DocumentFixtures.paragraph(text));
        StringBuilder restored=new StringBuilder();int unit=1,chunk=0,requests=0;
        do {
            DocumentContent.Result result=reader.read(bytes,new DocumentContent.Request(unit,chunk,1,2048,2),new RunControl());
            for(DocumentContent.Chunk part:result.chunks){assertFalse(Character.isHighSurrogate(part.text.charAt(part.text.length()-1)));restored.append(part.text);assertTrue(part.locator.containsKey("charStart"));}
            unit=result.nextUnit;chunk=result.nextChunk;assertTrue(++requests<10);
        }while(unit>0);
        assertEquals(text,restored.toString());assertTrue(requests>1);
    }
    @Test public void dtdEntitiesUnsafePartsAndTruncatedZipAreRejected()throws Exception {
        Map<String,String> parts=DocumentFixtures.wordParts(DocumentFixtures.paragraph("safe"));
        parts.put("word/document.xml","<!DOCTYPE w:document [<!ENTITY x SYSTEM 'file:///should-not-be-read'>]><w:document xmlns:w=\""+DocumentFixtures.W+"\"><w:body>"+DocumentFixtures.paragraph("&x;")+"</w:body></w:document>");
        assertEquals("invalid",read(DocumentFixtures.zip(parts)).status);
        parts=DocumentFixtures.wordParts(DocumentFixtures.paragraph("safe"));parts.put("../escape.xml","bad");assertEquals("invalid",read(DocumentFixtures.zip(parts)).status);
        byte[] valid=DocumentFixtures.word(DocumentFixtures.paragraph("safe"));assertEquals("invalid",read(Arrays.copyOf(valid,valid.length-10)).status);
    }
    @Test public void zipBombIsRejectedEvenWhenCentralDirectoryLiesAboutExpandedSize()throws Exception {
        Map<String,String> parts=DocumentFixtures.wordParts(DocumentFixtures.paragraph("safe"));parts.put("huge.xml","x".repeat(8*1024*1024+1));byte[] bomb=DocumentFixtures.zip(parts);
        assertEquals("limit_exceeded",read(bomb).status);
        for(int i=0;i<bomb.length-46;i++)if(bomb[i]==0x50&&bomb[i+1]==0x4b&&bomb[i+2]==1&&bomb[i+3]==2){bomb[i+24]=1;bomb[i+25]=0;bomb[i+26]=0;bomb[i+27]=0;}
        assertEquals("limit_exceeded",read(bomb).status);
    }
    @Test public void encryptedFlagsAndBinaryOfficeHaveDifferentHonestStatuses()throws Exception {
        byte[] bytes=DocumentFixtures.word(DocumentFixtures.paragraph("secret"));
        for(int i=0;i<bytes.length-46;i++)if(bytes[i]==0x50&&bytes[i+1]==0x4b&&bytes[i+2]==1&&bytes[i+3]==2){bytes[i+8]|=1;break;}
        assertEquals("encrypted",read(bytes).status);
        assertEquals("unsupported",read(new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1}).status);
    }
    @Test public void actualPdfAndImageBytesNeverBecomeTextWithoutMountedEngine()throws Exception {
        assertEquals("pdf",read(DocumentFixtures.pdf(false)).format);assertEquals("unavailable",read(DocumentFixtures.pdf(false)).status);
        assertEquals("unavailable",read(DocumentFixtures.pdf(true)).status);DocumentContent.Result image=read(DocumentFixtures.png());assertEquals("png",image.format);assertEquals("unavailable",image.status);assertTrue(image.chunks.isEmpty());
    }
    @Test public void mountedPdfReceivesExactBytesAndReportsItsActualTextLayer()throws Exception {
        byte[] pdf=DocumentFixtures.pdf(false);
        DocumentReader mounted=new DocumentReader(new DocumentReader.PdfTextPort(){
            public boolean available(){return true;}
            public void read(byte[] input,DocumentReader.RequestSink sink,RunControl control)throws Exception {
                assertArrayEquals(pdf,input);sink.totalUnits(1);sink.text(1,"pdf_text",DocumentContent.location("page","1"),"engine text");
            }
        },null);
        DocumentContent.Result result=mounted.read(pdf,DocumentContent.Request.defaults(),new RunControl());assertEquals("engine text",result.chunks.get(0).text);assertEquals("pdf_text_layer",result.coverage);assertEquals("partial",result.status);
    }
    @Test public void localOcrPortOutputIsExplicitlyRecognizedAndNotOriginalText()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        DocumentReader mounted=new DocumentReader(null,new DocumentReader.OcrPort(){
            public boolean available(){return true;}public String engineVersion(){return "fixture-only-not-a-device-engine";}
            public void recognize(byte[] input,String format,DocumentReader.RequestSink sink,RunControl control)throws Exception {
                calls.incrementAndGet();
                assertArrayEquals(DocumentFixtures.png(),input);sink.totalUnits(1);sink.text(1,"text",DocumentContent.location("image","1","boundsPixels","0,0,1,1"),"recognized fixture");
            }
        });
        DocumentContent.Result textLayer=mounted.read(DocumentFixtures.png(),new DocumentContent.Request(1,0,1,12000,48,"text"),new RunControl());
        assertEquals("unsupported",textLayer.status);assertTrue(textLayer.chunks.isEmpty());assertEquals(0,calls.get());
        DocumentContent.Result explicit=mounted.read(DocumentFixtures.png(),new DocumentContent.Request(1,0,1,12000,48,"ocr"),new RunControl());
        assertEquals("ocr_text",explicit.chunks.get(0).kind);assertEquals("ocr",explicit.coverage);assertEquals(1,calls.get());
        DocumentContent.Result result=mounted.read(DocumentFixtures.png(),DocumentContent.Request.defaults(),new RunControl());assertEquals("ocr_text",result.chunks.get(0).kind);assertEquals("ocr",result.coverage);assertEquals("partial",result.status);
        assertEquals(2,calls.get());
    }
    @Test public void cancellationBeforeAndDuringXmlNeverPublishesPartialSuccess()throws Exception {
        RunControl before=new RunControl();before.cancel();assertThrows(InterruptedException.class,()->reader.read(DocumentFixtures.word(DocumentFixtures.paragraph("body")),DocumentContent.Request.defaults(),before));
        RunControl during=new RunControl();AtomicInteger calls=new AtomicInteger();during.setAuthorityCheck(()->{if(calls.incrementAndGet()==40)throw new SecurityException("revoked");});
        byte[] bytes=DocumentFixtures.word(DocumentFixtures.paragraph("body").repeat(100));assertThrows(InterruptedException.class,()->reader.read(bytes,DocumentContent.Request.defaults(),during));assertTrue(during.isCancelled());
    }
    @Test public void unknownBinaryIsNotMisrepresentedAsUtf8AndRangesAreBounded()throws Exception {
        assertEquals("unsupported",read(new byte[]{(byte)255,0}).status);assertEquals("ok",read("line 1\nline 2".getBytes(StandardCharsets.UTF_8)).status);
        assertThrows(IllegalArgumentException.class,()->new DocumentContent.Request(1,0,33,2048,1));assertThrows(IllegalArgumentException.class,()->new DocumentContent.Request(1,0,1,2048,1,"remote"));
    }
}
