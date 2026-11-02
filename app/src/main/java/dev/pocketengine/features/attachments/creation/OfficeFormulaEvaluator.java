package dev.pocketengine.features.attachments.creation;

import dev.pocketengine.core.RunControl;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finite native calculation subset. Unsupported expressions retain their formula with no
 * cached value. No supplied cache, eval, network, external workbook, macro or hidden fallback. */
final class OfficeFormulaEvaluator {
    static final String VERSION="pocketworld-decimal64-formula-v1";
    private static final MathContext MATH=MathContext.DECIMAL64;
    private static final Pattern NUMBER=Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Pattern REFERENCE=Pattern.compile("(?:(?:'((?:[^']|'')+)'|([\\p{L}_][\\p{L}0-9_.]*))!)?(\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6})(?![A-Za-z0-9_])");
    record Key(String sheet,String ref) {}
    record Value(BigDecimal number,String status) {boolean current(){return number!=null&&status.equals("current");}}
    private static final class Unresolved extends Exception {final String code;Unresolved(String code){this.code=code;}}
    private final Map<Key,StructuredOfficeDraft.Cell> cells=new LinkedHashMap<>();
    private final Map<Key,Value> computed=new LinkedHashMap<>();
    private final Set<Key> active=new HashSet<>();
    private final RunControl control;
    private int steps;

    OfficeFormulaEvaluator(StructuredOfficeDraft source,RunControl control){this.control=control;for(var sheet:source.sheets)for(var cell:sheet.cells())cells.put(key(sheet.name(),cell.ref()),cell);}
    Value value(String sheet,StructuredOfficeDraft.Cell cell)throws Exception {
        Key key=key(sheet,cell.ref());Value old=computed.get(key);if(old!=null)return old;
        try {Value value=new Value(evaluate(key,0),"current");computed.put(key,value);return value;}
        catch(Unresolved problem){Value value=new Value(null,problem.code);computed.put(key,value);return value;}
    }
    JSONObject report(StructuredOfficeDraft draft)throws Exception {
        JSONArray issues=new JSONArray();int checked=0,formulas=0,current=0;
        for(var sheet:draft.sheets)for(var cell:sheet.cells()){checked++;if(cell.formula()==null)continue;formulas++;Value value=value(sheet.name(),cell);if(value.current())current++;else issues.put(new JSONObject().put("sheet",sheet.name()).put("cell",cell.ref()).put("code",value.status()).put("cachedValueWritten",false).put("formulaWritten",false).put("outputType","literal_text"));}
        return new JSONObject().put("evaluatorVersion",VERSION).put("numericPrecision","DECIMAL64").put("dependencyCoverage","declared_cells_arithmetic_and_SUM_AVERAGE_MIN_MAX")
                .put("cellsChecked",checked).put("formulaCount",formulas).put("currentFormulaCount",current).put("activeFormulaCount",current).put("literalFormulaCount",formulas-current).put("issues",issues)
                .put("calcStatus",issues.length()==0?"current":"recalc_required").put("externalRecalculationPerformed",false).put("declaredFormulaCoverageComplete",issues.length()==0);
    }
    private BigDecimal evaluate(Key key,int depth)throws Exception {
        tick();if(depth>96)throw new Unresolved("dependency_limit");Value old=computed.get(key);if(old!=null){if(old.current())return old.number();throw new Unresolved(old.status());}
        StructuredOfficeDraft.Cell cell=cells.get(key);if(cell==null)throw new Unresolved("missing_reference");
        if(!active.add(key))throw new Unresolved("circular_reference");
        try {
            BigDecimal result;
            if(cell.formula()==null){if(!(cell.value() instanceof Number n))throw new Unresolved("non_numeric_reference");result=StructuredOfficeDraft.decimal(n);}
            else {Parser parser=new Parser(cell.formula(),key.sheet(),depth+1);result=parser.expression(0);parser.space();if(parser.at!=parser.text.length())throw new Unresolved("unsupported_formula");}
            if(result.precision()>64||Math.abs(result.scale())>200)throw new Unresolved("numeric_limit");
            computed.put(key,new Value(result,"current"));return result;
        } catch(ArithmeticException arithmetic){throw new Unresolved("numeric_error");}
        finally{active.remove(key);}
    }
    private void tick()throws Exception{control.checkpoint();if(++steps>250000)throw new Unresolved("calculation_budget");}
    private static Key key(String sheet,String ref){return new Key(sheet.toLowerCase(Locale.ROOT),ref.toUpperCase(Locale.ROOT));}
    private final class Parser {
        final String text,sheet;final int dependencyDepth;int at;
        Parser(String text,String sheet,int dependencyDepth)throws Unresolved{this.text=text;this.sheet=sheet;this.dependencyDepth=dependencyDepth;if(text.contains("[")||text.contains("]")||text.contains("://"))throw new Unresolved("external_reference_unsupported");}
        void space(){while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}
        boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        BigDecimal expression(int depth)throws Exception {
            tick();if(depth>96)throw new Unresolved("expression_limit");BigDecimal value=term(depth+1);
            while(true){if(take('+'))value=value.add(term(depth+1),MATH);else if(take('-'))value=value.subtract(term(depth+1),MATH);else return value;}
        }
        BigDecimal term(int depth)throws Exception {
            BigDecimal value=primary(depth+1);while(true){if(take('*'))value=value.multiply(primary(depth+1),MATH);else if(take('/')){BigDecimal denominator=primary(depth+1);if(denominator.signum()==0)throw new Unresolved("division_by_zero");value=value.divide(denominator,MATH);}else return value;}
        }
        BigDecimal primary(int depth)throws Exception {
            tick();if(depth>96)throw new Unresolved("expression_limit");if(take('+'))return primary(depth+1);if(take('-'))return primary(depth+1).negate(MATH);
            if(take('(')){BigDecimal value=expression(depth+1);if(!take(')'))throw new Unresolved("invalid_formula");return value;}
            space();Matcher n=NUMBER.matcher(text);n.region(at,text.length());if(n.lookingAt()){at=n.end();try{return new BigDecimal(n.group(),MATH);}catch(NumberFormatException failure){throw new Unresolved("numeric_limit");}}
            Key reference=reference();if(reference!=null)return evaluate(reference,dependencyDepth);
            space();int start=at;while(at<text.length()&&(Character.isLetter(text.charAt(at))||text.charAt(at)=='_'))at++;
            String function=text.substring(start,at).toUpperCase(Locale.ROOT);
            if(!Set.of("SUM","AVERAGE","MIN","MAX").contains(function))throw new Unresolved("unsupported_function");
            if(!take('('))throw new Unresolved("invalid_formula");List<BigDecimal> args=new ArrayList<>();
            if(take(')'))throw new Unresolved("empty_function");
            do {int before=at;Key first=reference();if(first!=null&&take(':')){
                    Key last=reference(first.sheet());if(last==null||!first.sheet().equals(last.sheet()))throw new Unresolved("invalid_range");
                    int c1=StructuredOfficeDraft.column(first.ref()),c2=StructuredOfficeDraft.column(last.ref()),r1=StructuredOfficeDraft.row(first.ref()),r2=StructuredOfficeDraft.row(last.ref());
                    if(c2<c1||r2<r1||(long)(c2-c1+1)*(r2-r1+1)>4096)throw new Unresolved("range_limit");
                    for(int r=r1;r<=r2;r++)for(int c=c1;c<=c2;c++)args.add(evaluate(new Key(first.sheet(),StructuredOfficeDraft.ref(c,r)),dependencyDepth));
                } else {at=before;args.add(expression(depth+1));}
                if(args.size()>4096)throw new Unresolved("range_limit");
            }while(take(','));
            if(!take(')'))throw new Unresolved("invalid_formula");BigDecimal result=args.get(0);
            if(function.equals("SUM")||function.equals("AVERAGE")){result=BigDecimal.ZERO;for(BigDecimal arg:args)result=result.add(arg,MATH);if(function.equals("AVERAGE"))result=result.divide(BigDecimal.valueOf(args.size()),MATH);}
            else for(BigDecimal arg:args)result=function.equals("MIN")?result.min(arg):result.max(arg);
            return result;
        }
        Key reference()throws Unresolved {return reference(sheet);}
        Key reference(String defaultSheet)throws Unresolved {
            space();Matcher ref=REFERENCE.matcher(text);ref.region(at,text.length());if(!ref.lookingAt())return null;
            if(ref.group(1)==null&&ref.group(2)==null&&ref.end()<text.length()&&text.charAt(ref.end())=='(')return null;
            String cell=ref.group(3).replace("$","").toUpperCase(Locale.ROOT);if(!StructuredOfficeDraft.validRef(cell))throw new Unresolved("invalid_reference");
            at=ref.end();String target=ref.group(1)!=null?ref.group(1).replace("''","'"):ref.group(2)!=null?ref.group(2):defaultSheet;return key(target,cell);
        }
    }
}
