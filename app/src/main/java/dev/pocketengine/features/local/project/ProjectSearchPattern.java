package dev.pocketengine.features.local.project;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/** RE2/J performs matching; this adapter only preserves the original query's cancellation/budget. */
final class ProjectSearchPattern {
    private static final long MAX_MATCH_NANOS=java.util.concurrent.TimeUnit.SECONDS.toNanos(3),MAX_READS=64L*1024*1024;
    static final int MAX_COMPILE_EXPANSION=32768;
    private final Pattern pattern;
    private long spentNanos,reads,originalCheckNanos;
    ProjectSearchPattern(String expression,boolean caseSensitive)throws Exception {
        requireCompilationBudget(expression);
        try{pattern=Pattern.compile(expression,caseSensitive?0:Pattern.CASE_INSENSITIVE);}
        catch(PatternSyntaxException invalid){throw new IllegalArgumentException("RE2/J正则无效或不支持；不支持回溯引用/lookaround，不会改用Java回溯正则："+invalid.getDescription(),invalid);}
    }
    /** RE2/J bounds backtracking, not counted-repeat compilation expansion. This conservative
     * lexical ceiling is not a regex parser: the library still owns all syntax/semantics. */
    private static void requireCompilationBudget(String expression){
        long estimate=Math.max(1,expression.length());boolean inClass=false,quoted=false;
        for(int at=0;at<expression.length();at++){
            char value=expression.charAt(at);
            if(value=='\\'&&at+1<expression.length()){char escape=expression.charAt(++at);if(escape=='Q'&&!inClass)quoted=true;else if(escape=='E')quoted=false;continue;}
            if(quoted)continue;if(value=='['){inClass=true;continue;}if(value==']'){inClass=false;continue;}if(inClass||value!='{')continue;
            int end=at+1;while(end<expression.length()&&expression.charAt(end)>='0'&&expression.charAt(end)<='9')end++;if(end==at+1)continue;String first=expression.substring(at+1,end),maximum=first;boolean unbounded=false;
            if(end<expression.length()&&expression.charAt(end)==','){int from=++end;while(end<expression.length()&&expression.charAt(end)>='0'&&expression.charAt(end)<='9')end++;maximum=expression.substring(from,end);unbounded=maximum.isEmpty();}
            if(end>=expression.length()||expression.charAt(end)!='}')continue;long repeat;
            try{repeat=Long.parseLong(unbounded?first:maximum)+(unbounded?1:0);}catch(NumberFormatException huge){throw new IllegalArgumentException("正则计数重复超过本次编译范围",huge);}
            if(repeat<0||repeat>MAX_COMPILE_EXPANSION||estimate>MAX_COMPILE_EXPANSION/Math.max(1,repeat))throw new IllegalArgumentException("正则计数重复超过保守编译展开预算32768；请缩小重复次数/表达式，不会回退Java正则");estimate*=Math.max(1,repeat);at=end;
        }
    }
    int[] find(String text,ProjectFileQueries.Current original)throws Exception {
        original.check();long started=System.nanoTime();originalCheckNanos=0;if(spentNanos>=MAX_MATCH_NANOS)throw exhausted();
        try{
            Matcher matcher=pattern.matcher(new CheckedText(text,original,started,0,text.length()));boolean found=matcher.find();
            checkOriginal(original);if(System.nanoTime()-started-originalCheckNanos+spentNanos>MAX_MATCH_NANOS)throw exhausted();return found?new int[]{matcher.start(),matcher.end()}:null;
        }catch(OriginalStopped stopped){throw stopped.original;}
        finally{spentNanos+=Math.max(0,System.nanoTime()-started-originalCheckNanos);}
    }
    int groupCount(){return pattern.groupCount();}
    /** Full-input matching preserves anchors at a resumed offset. Only positions escape this
     * adapter: the caller bounds previews without allocating a complete captured substring. */
    int[][] findGroups(String text,int offset,ProjectFileQueries.Current original)throws Exception {
        if(offset<0||offset>text.length())throw new IllegalArgumentException("正则匹配起点超出原文件范围");
        original.check();long started=System.nanoTime();originalCheckNanos=0;if(spentNanos>=MAX_MATCH_NANOS)throw exhausted();
        try{
            Matcher matcher=pattern.matcher(new CheckedText(text,original,started,0,text.length()));
            if(!matcher.find(offset)){checkOriginal(original);if(System.nanoTime()-started-originalCheckNanos+spentNanos>MAX_MATCH_NANOS)throw exhausted();return null;}
            int[][] groups=new int[matcher.groupCount()+1][2];
            for(int i=0;i<groups.length;i++){groups[i][0]=matcher.start(i);groups[i][1]=matcher.end(i);}
            checkOriginal(original);if(System.nanoTime()-started-originalCheckNanos+spentNanos>MAX_MATCH_NANOS)throw exhausted();return groups;
        }catch(OriginalStopped stopped){throw stopped.original;}
        finally{spentNanos+=Math.max(0,System.nanoTime()-started-originalCheckNanos);}
    }
    private void checkOriginal(ProjectFileQueries.Current original)throws Exception{long before=System.nanoTime();try{original.check();}finally{originalCheckNanos+=System.nanoTime()-before;}}
    private static IllegalArgumentException exhausted(){return new IllegalArgumentException("正则计算超过本次3秒/6400万字符访问预算，请缩小查询/文件范围；未返回不完整的空命中");}
    private static final class OriginalStopped extends RuntimeException {
        final Exception original;
        OriginalStopped(Exception original){super(original);this.original=original;}
    }
    private final class CheckedText implements CharSequence {
        final String text;final ProjectFileQueries.Current current;final long started;final int start,end;
        CheckedText(String text,ProjectFileQueries.Current current,long started,int start,int end){this.text=text;this.current=current;this.started=started;this.start=start;this.end=end;}
        @Override public int length(){return end-start;}
        @Override public char charAt(int offset){
            if(offset<0||offset>=length())throw new IndexOutOfBoundsException();
            if((++reads&1023)==0){try{checkOriginal(current);}catch(Exception stopped){throw new OriginalStopped(stopped);}if(reads>MAX_READS||System.nanoTime()-started-originalCheckNanos+spentNanos>MAX_MATCH_NANOS)throw exhausted();}
            return text.charAt(start+offset);
        }
        @Override public CharSequence subSequence(int from,int to){if(from<0||to<from||to>length())throw new IndexOutOfBoundsException();return new CheckedText(text,current,started,start+from,start+to);}
        @Override public String toString(){return text.substring(start,end);}
    }
}
