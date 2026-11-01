package dev.pocketengine.vision.ocr;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Geometry/compatibility contracts only; these strings are not native OCR recognition results. */
public final class OcrPageWordTest {
    private static OcrPage.Line line(){return new OcrPage.Line(1,"取消 继续 保存",0,0,300,30);}
    private static OcrPage.Word word(int index,int line,String text,int left,int right,float score){return new OcrPage.Word(index,line,text,left,0,right,30,score);}
    @Test public void oldLineOnlyConstructorRetainsDocumentContract() {
        OcrPage page=new OcrPage(600,120,300,60,List.of(line()));
        assertEquals("取消 继续 保存",page.lines.get(0).text);assertTrue(page.downsampled());
        assertFalse(page.wordReadAttempted);assertFalse(page.wordsTruncated);assertTrue(page.words.isEmpty());
    }
    @Test public void actualWordsAreImmutableAndKeepTheirReturnedLineAndConfidence() {
        ArrayList<OcrPage.Word> words=new ArrayList<>(List.of(word(1,1,"取消",0,80,91.5f),word(3,1,"保存",220,300,42f)));
        OcrPage page=new OcrPage(300,60,300,60,List.of(line()),words,false);words.clear();
        assertEquals(2,page.words.size());assertEquals(3,page.words.get(1).index);assertEquals(1,page.words.get(1).lineIndex);
        assertEquals(42f,page.words.get(1).confidence,0f);assertTrue(page.wordReadAttempted);assertFalse(page.wordsTruncated);
        assertThrows(UnsupportedOperationException.class,()->page.words.clear());
    }
    @Test public void missingWordGeometryNeverErasesOtherwiseValidDocumentLines() {
        OcrPage page=new OcrPage(300,60,300,60,List.of(line()),List.of(),true);
        assertTrue(page.wordReadAttempted);assertTrue(page.wordsTruncated);assertEquals(1,page.lines.size());assertTrue(page.words.isEmpty());
    }
    @Test public void rejectsUnanchoredOutsideOrReorderedWordGeometry() {
        for(List<OcrPage.Word> invalid:List.of(List.of(word(1,2,"继续",100,180,90)),List.of(word(1,1,"继续",250,301,90)),
                List.of(word(2,1,"继续",100,180,90),word(1,1,"取消",0,80,90)),List.of(word(1,1,"继续",100,180,90),word(1,1,"继续",100,180,90))))
            assertThrows(IllegalArgumentException.class,()->new OcrPage(400,60,400,60,List.of(line()),invalid,false));
        assertThrows(IllegalArgumentException.class,()->new OcrPage(300,60,300,60,List.of(line()),List.of(new OcrPage.Word(1,1,"x",1,29,3,31,90)),false));
    }
    @Test public void rejectsUnknownConfidenceAndUnboundedOrBlankWords() {
        for(float score:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,-1f,100.001f,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->word(1,1,"继续",1,9,score));
        for(String text:List.of("","\t \n\u2003","x".repeat(OcrPage.MAX_WORD_CHARS+1)))
            assertThrows(IllegalArgumentException.class,()->word(1,1,text,1,9,80));
        assertThrows(IllegalArgumentException.class,()->word(OcrPage.MAX_WORDS+1,1,"x",1,9,80));
    }
    @Test public void totalWordCharacterBudgetIsSeparateFromLegacyLineBudget() {
        ArrayList<OcrPage.Word> words=new ArrayList<>();
        for(int i=1;i<=129;i++)words.add(word(i,1,"x".repeat(OcrPage.MAX_WORD_CHARS),1,9,90));
        assertThrows(IllegalArgumentException.class,()->new OcrPage(300,60,300,60,List.of(line()),words,true));
    }
}
