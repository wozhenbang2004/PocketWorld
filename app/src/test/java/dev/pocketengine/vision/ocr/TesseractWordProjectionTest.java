package dev.pocketengine.vision.ocr;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

/** Exercises the actual Java native-result projection, NOT Tesseract JNI or Chinese recognition. */
public final class TesseractWordProjectionTest {
    private static final int[] LINE={10,20,290,60},WORD={210,20,270,60};
    private static List<OcrPage.Line> lines(){return List.of(new OcrPage.Line(1,"取消 继续 保存",10,20,290,60));}
    private static OcrPage.Word project(String text,int[] box,float score,String line,int[] lineBox,List<OcrPage.Line> parents){return TesseractLocalOcrEngine.projectedWord(4,text,box,score,line,lineBox,parents);}
    @Test public void usesActualWordBoxRatherThanSplittingTheTextLineIntoGuessedSegments() {
        OcrPage.Word word=project("保存\n",WORD,73.25f,"取消 继续 保存\n",LINE,lines());
        assertNotNull(word);assertEquals(4,word.index);assertEquals(1,word.lineIndex);assertEquals("保存",word.text);
        assertEquals(210,word.left);assertEquals(270,word.right);assertEquals(73.25f,word.confidence,0f);
    }
    @Test public void missingOrDifferentNativeLineAnchorCannotInventAParent() {
        assertNull(project("保存",WORD,90,"different line",LINE,lines()));
        assertNull(project("保存",WORD,90,"取消 继续 保存",new int[]{11,20,290,60},lines()));
        assertNull(project("保存",WORD,90,null,LINE,lines()));assertNull(project("保存",WORD,90,"取消 继续 保存",null,lines()));
        assertNull(project("保存",null,90,"取消 继续 保存",LINE,lines()));assertNull(project(null,WORD,90,"取消 继续 保存",LINE,lines()));
    }
    @Test public void duplicateExactNativeLineAnchorIsAmbiguousRatherThanFirstMatchWins() {
        assertNull(project("保存",WORD,90,"取消 继续 保存",LINE,List.of(lines().get(0),new OcrPage.Line(2,"取消 继续 保存",10,20,290,60))));
    }
    @Test public void badNativeBoxScoreOrUnboundedWordBecomesAnOmittedWordNotGuessedGeometry() {
        for(int[] box:new int[][]{{0,20,270,60},{210,20,300,60},{210,19,270,60},{210,20,270,61},{210,20,210,60},{210,20,270}})
            assertNull(project("保存",box,90,"取消 继续 保存",LINE,lines()));
        assertNull(project("保存",WORD,Float.NaN,"取消 继续 保存",LINE,lines()));
        assertNull(project("保存",WORD,-1f,"取消 继续 保存",LINE,lines()));
        assertNull(project("x".repeat(OcrPage.MAX_WORD_CHARS+1),WORD,90,"取消 继续 保存",LINE,lines()));
        assertNull(project(" \n",WORD,90,"取消 继续 保存",LINE,lines()));
    }
}
