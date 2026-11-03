package dev.pocketengine.features.local.project;

/** Read-only approximate substring locations. Never supplies replacement text or write authority. */
public final class ProjectFuzzyPattern {
    public static final int MAX_EDITS=2;
    public static final long MAX_CELLS=8_000_000L,MAX_MATCH_MILLIS=3000;
    public static final String ENGINE="bounded_levenshtein_substring_v1";
    private final int[] query;
    private final int maxEdits;
    private final boolean sensitive;
    private long cells,started;

    public ProjectFuzzyPattern(String text,boolean sensitive,int maxEdits){
        if(text==null||text.length()>240||text.indexOf('\n')>=0||text.indexOf('\r')>=0||text.indexOf('\0')>=0)
            throw new IllegalArgumentException("模糊定位需要单行、最多240字符的文字");
        query=text.codePoints().toArray();
        if(query.length<4||maxEdits<1||maxEdits>MAX_EDITS)
            throw new IllegalArgumentException("模糊定位至少4个Unicode码点；maxEdits需1至2");
        this.sensitive=sensitive;this.maxEdits=maxEdits;
    }

    /** UTF-16 start/end (exclusive), followed by Unicode-code-point edit distance.
     * One best candidate per line; ties use the first start, then the longest span.
     * Time and cell budgets are shared across all lines/files in this search page. */
    public int[] find(String text,ProjectFileQueries.Current current)throws Exception {
        current.check();if(started==0)started=System.nanoTime();deadline();if(text.isEmpty())return null;
        int size=query.length;
        int[] previous=new int[size+1],next=new int[size+1],previousStart=new int[size+1],nextStart=new int[size+1];
        for(int at=0;at<=size;at++)previous[at]=at;
        int bestDistance=maxEdits+1,bestStart=-1,bestEnd=-1,points=0;
        for(int offset=0;offset<text.length();){
            if((points++&127)==0){current.check();deadline();}
            if(cells>MAX_CELLS-size)throw budget();cells+=size;
            int value=text.codePointAt(offset),end=offset+Character.charCount(value);
            next[0]=0;nextStart[0]=end;
            for(int at=1;at<=size;at++){
                int distance=previous[at-1]+(same(value,query[at-1])?0:1),start=previousStart[at-1];
                int deletion=next[at-1]+1;
                if(deletion<distance||deletion==distance&&nextStart[at-1]<start){distance=deletion;start=nextStart[at-1];}
                int insertion=previous[at]+1;
                if(insertion<distance||insertion==distance&&previousStart[at]<start){distance=insertion;start=previousStart[at];}
                next[at]=distance;nextStart[at]=start;
            }
            int distance=next[size],start=nextStart[size];
            if(start<end&&distance<=maxEdits&&(distance<bestDistance||distance==bestDistance&&(start<bestStart||start==bestStart&&end>bestEnd))){
                bestDistance=distance;bestStart=start;bestEnd=end;
                if(distance==0){current.check();deadline();return new int[]{start,end,0};}
            }
            int[] swap=previous;previous=next;next=swap;swap=previousStart;previousStart=nextStart;nextStart=swap;
            offset=end;
        }
        current.check();deadline();return bestStart<0?null:new int[]{bestStart,bestEnd,bestDistance};
    }
    private void deadline(){if(System.nanoTime()-started>MAX_MATCH_MILLIS*1_000_000L)throw budget();}
    private boolean same(int a,int b){return a==b||!sensitive&&(Character.toLowerCase(a)==Character.toLowerCase(b)||Character.toUpperCase(a)==Character.toUpperCase(b));}
    private static IllegalArgumentException budget(){return new IllegalArgumentException("模糊定位超过本页匹配预算；请缩小path/include或先用精确搜索定位。未返回不完整候选或自动重试");}
}
