package dev.pocketengine.features.memory;

import java.util.regex.Pattern;
import org.json.JSONObject;

/** Conservative exclusions, not a natural-language identity proof. Exact message authorship
 * remains the original repository's job; a user's quoted third person is not the owner. */
public final class PersonalMemoryAttribution {
    private static final Pattern HYPOTHETICAL=Pattern.compile("假设|假如|假定|设想|虚构|假装|如果我是|角色扮演|扮演一个|举个例子|例如我|比如我|翻译(?:一下|成|这)|改写(?:一下|这)|(?i:\\b(?:suppose|imagine|hypothetical|fictional|role[ -]?play|for example|if I were|translate|rewrite)\\b)");
    private static final Pattern REPORTED=Pattern.compile("(?:朋友|同事|同学|室友|家人|父亲|母亲|爸爸|妈妈|客户|老板|网友|作者|他|她|他们|她们)[^。！？!?\\n]{0,40}(?:说|告诉|表示|提到|写道|原话)|(?:引用|转述|原文|摘录|台词|这句话来自)[：:\\s]|(?i:\\b(?:he|she|they|my friend|my colleague|the author)\\b[^.!?\\n]{0,60}\\b(?:said|says|told|wrote|writes)\\b)");
    private static final Pattern OTHER_SUBJECT=Pattern.compile("^(?:我的?(?:朋友|同事|同学|室友|家人|父亲|母亲|爸爸|妈妈|客户|老板)|他(?:们)?|她(?:们)?|(?:朋友|同事|同学|室友|家人|父亲|母亲|爸爸|妈妈|客户|老板)(?:说|是|喜欢|不|爱|住|在))|(?i:^(?:my (?:friend|colleague|mother|father|partner)|he|she|they)\\b)");
    private PersonalMemoryAttribution(){}

    public static JSONObject checkOwnerClaim(String originalText,int offset,int chars,JSONObject assertion)throws Exception {
        String reason="";
        if(!PersonalMemorySemantics.ownerAssertion(assertion))reason="not_owner_assertion";
        else if(originalText==null||originalText.length()>1_048_576||offset<0||chars<1||chars>24000||(long)offset+chars>originalText.length())reason="source_range_unavailable";
        else {
            int end=offset+chars;MemoryBook.validateUnicode(originalText);
            if(splitsPair(originalText,offset)||splitsPair(originalText,end))reason="source_range_unavailable";
            else {
                int lineStart=originalText.lastIndexOf('\n',offset-1)+1;
                String linePrefix=originalText.substring(lineStart,offset),span=originalText.substring(offset,end).trim();
                int paragraphStart=originalText.lastIndexOf('\n',lineStart-2)+1;
                for(char separator:new char[]{'。','！','？','!','?'})paragraphStart=Math.max(paragraphStart,originalText.lastIndexOf(separator,offset-1)+1);
                String context=originalText.substring(Math.max(paragraphStart,offset-240),Math.min(originalText.length(),end+80));
                int clauseStart=paragraphStart;
                for(char separator:new char[]{'，',',','；',';','。','！','？','!','?','\n'})clauseStart=Math.max(clauseStart,originalText.lastIndexOf(separator,offset-1)+1);
                String clause=originalText.substring(clauseStart,end).stripLeading()
                        .replaceFirst("^(?:但是|不过|而且|至于|另外|其实|而|但|(?i:and\\b|but\\b))\\s*","");
                if(linePrefix.stripLeading().startsWith(">")||span.startsWith(">")||insideFence(originalText,offset)||quoted(originalText,offset,end))reason="quoted_or_code_text";
                else if(HYPOTHETICAL.matcher(context).find())reason="hypothetical_or_transformation";
                else if(REPORTED.matcher(context).find()||OTHER_SUBJECT.matcher(span).find()||OTHER_SUBJECT.matcher(clause).find())reason="third_person_or_reported_text";
            }
        }
        return new JSONObject().put("allowed",reason.isEmpty()).put("reason",reason).put("attributionVerified",false);
    }
    public static void requireOwnerClaim(String originalText,int offset,int chars,JSONObject assertion)throws Exception {
        JSONObject result=checkOwnerClaim(originalText,offset,chars,assertion);
        if(!result.getBoolean("allowed"))throw new SecurityException("这段内容是引用、转述、假设或主体不明，不能记为你的个人事实："+result.getString("reason"));
    }
    private static boolean splitsPair(String text,int offset){return offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset));}
    private static boolean insideFence(String text,int offset){
        boolean code=false;int start=0;
        while(start<=offset){int end=text.indexOf('\n',start);if(end<0)end=text.length();String line=text.substring(start,Math.min(end,offset)).stripLeading();if(line.startsWith("```")||line.startsWith("~~~"))code=!code;if(end>=offset||end==text.length())break;start=end+1;}return code;
    }
    private static boolean quoted(String text,int start,int end){
        String opens="\"'“‘「『`",closes="\"'”’」』`";
        for(int type=0;type<opens.length();type++){
            char open=opens.charAt(type),close=closes.charAt(type);int from=0;
            while(from<end){int left=text.indexOf(open,from);if(left<0||left>=end)break;
                if(open=='\''&&left>0&&Character.isLetterOrDigit(text.charAt(left-1))){from=left+1;continue;}
                int right=text.indexOf(close,left+1);while(open=='\''&&right>=0&&right+1<text.length()&&Character.isLetterOrDigit(text.charAt(right+1)))right=text.indexOf(close,right+1);if(right<0)break;
                if((start>left&&end<=right)||(start==left&&end==right+1))return true;from=right+1;}
        }
        return false;
    }
}
