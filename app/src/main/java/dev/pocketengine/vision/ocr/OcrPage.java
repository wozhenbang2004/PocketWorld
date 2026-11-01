package dev.pocketengine.vision.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Recognized, untrusted text. Image-local boxes are never phone input coordinates. */
public final class OcrPage {
    public static final String COORDINATE_SPACE = "upright_ocr_raster_pixels";
    public static final int MAX_PIXELS = 4 * 1024 * 1024, MAX_SIDE = 4096;
    public static final int MAX_LINES = 2048, MAX_TEXT_CHARS = 128 * 1024;
    public static final int MAX_WORDS = 4096, MAX_WORD_TEXT_CHARS = 64 * 1024, MAX_WORD_CHARS = 512;
    public final int sourceWidth, sourceHeight, width, height;
    public final List<Line> lines;
    public final List<Word> words;
    public final boolean wordReadAttempted, wordsTruncated;

    public OcrPage(int sourceWidth, int sourceHeight, int width, int height, List<Line> lines) {
        this(sourceWidth,sourceHeight,width,height,lines,Collections.emptyList(),false,false);
    }
    /** Optional actual word geometry. Existing document consumers continue using the unchanged lines. */
    public OcrPage(int sourceWidth,int sourceHeight,int width,int height,List<Line> lines,List<Word> words,boolean wordsTruncated) {
        this(sourceWidth,sourceHeight,width,height,lines,words,true,wordsTruncated);
    }
    private OcrPage(int sourceWidth,int sourceHeight,int width,int height,List<Line> lines,List<Word> words,boolean wordReadAttempted,boolean wordsTruncated) {
        if (sourceWidth < 1 || sourceHeight < 1 || width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                || (long) width * height > MAX_PIXELS || width > sourceWidth || height > sourceHeight)
            throw new IllegalArgumentException("Invalid OCR raster dimensions");
        Objects.requireNonNull(lines);
        if (lines.size() > MAX_LINES) throw new IllegalArgumentException("Too many OCR lines");
        int characters = 0, previous = 0;
        for (Line line : lines) {
            if (line.index <= previous || line.right > width || line.bottom > height)
                throw new IllegalArgumentException("Invalid OCR line provenance");
            characters += line.text.length(); previous = line.index;
            if (characters > MAX_TEXT_CHARS) throw new IllegalArgumentException("Too much OCR text");
        }
        Objects.requireNonNull(words);if(words.size()>MAX_WORDS)throw new IllegalArgumentException("Too many OCR words");
        int wordCharacters=0,previousWord=0;
        for(Word word:words){
            if(word.index<=previousWord||word.right>width||word.bottom>height)throw new IllegalArgumentException("Invalid OCR word provenance");
            Line parent=null;for(Line line:lines)if(line.index==word.lineIndex){parent=line;break;}
            if(parent==null||word.left<parent.left||word.top<parent.top||word.right>parent.right||word.bottom>parent.bottom)throw new IllegalArgumentException("OCR word has no exact enclosing text line");
            previousWord=word.index;wordCharacters+=word.text.length();if(wordCharacters>MAX_WORD_TEXT_CHARS)throw new IllegalArgumentException("Too much OCR word text");
        }
        this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        this.width = width; this.height = height;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.words=Collections.unmodifiableList(new ArrayList<>(words));this.wordReadAttempted=wordReadAttempted;this.wordsTruncated=wordsTruncated;
    }

    public boolean downsampled() { return width != sourceWidth || height != sourceHeight; }

    public static final class Line {
        public final int index, left, top, right, bottom;
        public final String text;
        public Line(int index, String text, int left, int top, int right, int bottom) {
            this.text = Objects.requireNonNull(text);
            if (index < 1 || index > MAX_LINES || text.isEmpty() || text.length() > MAX_TEXT_CHARS
                    || left < 0 || top < 0 || right <= left || bottom <= top)
                throw new IllegalArgumentException("Invalid OCR text line");
            this.index = index; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public String boundsPixels() { return left + "," + top + "," + right + "," + bottom; }
    }

    /** A native OCR observation, not a semantic/clickability or calibrated-accuracy guarantee. */
    public static final class Word {
        public final int index,lineIndex,left,top,right,bottom;
        public final String text;public final float confidence;
        public Word(int index,int lineIndex,String text,int left,int top,int right,int bottom,float confidence){
            this.text=Objects.requireNonNull(text);
            if(index<1||index>MAX_WORDS||lineIndex<1||lineIndex>MAX_LINES||text.isEmpty()||text.length()>MAX_WORD_CHARS||text.codePoints().allMatch(Character::isWhitespace)
                    ||left<0||top<0||right<=left||bottom<=top||!Float.isFinite(confidence)||confidence<0||confidence>100)throw new IllegalArgumentException("Invalid OCR word");
            this.index=index;this.lineIndex=lineIndex;this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.confidence=confidence;
        }
    }

    /** Computes a bounded output before a decoder allocates pixels; never upscales. */
    static int[] rasterSize(int width, int height) {
        if (width < 1 || height < 1 || width > 32768 || height > 32768 || (long) width * height > 100_000_000)
            throw new IllegalArgumentException("Image exceeds source dimension limit");
        double scale = Math.min(1d, Math.min((double) MAX_SIDE / Math.max(width, height),
                Math.sqrt((double) MAX_PIXELS / ((long) width * height))));
        return new int[]{Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale))};
    }
}
