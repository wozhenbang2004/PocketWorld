package dev.pocketengine.store;

import java.text.Normalizer;
import java.util.Locale;

/** Transient predicate on a separately verified, local original-goal string.
 * Not an SQL expression, database index, remote query or input to the model. */
final class LocalPhoneGoalSearch {
    private final String term;
    LocalPhoneGoalSearch(String value) {
        if(value==null||value.codePointCount(0,value.length())>64||value.codePoints().anyMatch(cp ->
                Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT||Character.getType(cp)==Character.SURROGATE))
            throw new IllegalArgumentException("仅支持最多 64 个字的单行原目标关键词");
        term=fold(value.trim());
        if(term.codePointCount(0,term.length())>96)throw new IllegalArgumentException("原目标关键词过长");
    }
    private static String fold(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    boolean matches(String goal){return term.isEmpty()||fold(goal).contains(term);}
    boolean active(){return !term.isEmpty();}
    /** Full normalization decides membership; page windows only locate the first nearby passage.
     * A keyword spanning a page boundary opens at the earlier section, never a guessed text snippet. */
    int firstMatchingPage(String source,int size){
        if(!active()||!matches(source))return -1;
        int left=source.codePointCount(0,source.length()),start=0,previous=0,page=0;
        while(left>0){
            int n=Math.min(size,left),end=source.offsetByCodePoints(start,n);
            if(matches(source.substring(start,end)))return page;
            if(page>0&&matches(source.substring(previous,end)))return page-1;
            previous=start;start=end;left-=n;page++;
        }
        return -1; // No demonstrable local passage; do not pretend page zero matched.
    }
    boolean beyondPreview(String preview){return !term.isEmpty()&&!matches(preview);}
}
