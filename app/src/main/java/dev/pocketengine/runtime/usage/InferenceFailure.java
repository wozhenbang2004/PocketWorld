package dev.pocketengine.runtime.usage;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONObject;

/** Native content-free failure observation; not a retry instruction, quota, health or charge proof. */
public final class InferenceFailure {
    public enum Kind { HTTP, NETWORK, TIMEOUT, INCOMPLETE_STREAM, CONTEXT_LIMIT, PROTOCOL, LOCAL_REFUSAL, ACCOUNT_GATE, CANCELLED, UNKNOWN }
    public enum RetryStatus { MISSING, VALID, INVALID }
    public enum RetryBasis { NONE, DELTA_SECONDS_LOCAL_RECEIPT, RESPONSE_DATE_ESTIMATE, LOCAL_CLOCK_ESTIMATE, NATIVE_ACCOUNT_GATE, INVALID_OR_AMBIGUOUS }
    public static final long MAX_WAIT_MILLIS=31_536_000_000L;
    private static final Set<String> NATIVE_CODES=Set.of("CANCELLED","DEADLINE_EXCEEDED","STALE_ACCOUNT_VERSION","STALE_MODEL_CATALOG","ACCOUNT_SELECTION_STORE_UNAVAILABLE","ACCOUNT_REFRESH_REQUIRED","ACCOUNT_DISABLED","ACCOUNT_NOT_FOUND","ACCOUNT_SELECTION_REQUIRED","REAUTHORIZATION_REQUIRED","PLAN_USAGE_PERMISSION_REQUIRED","PROVIDER_PERMISSION_DENIED","ACCOUNT_COOLDOWN","RATE_LIMITED","QUOTA_EXCEEDED","PROVIDER_RATE_LIMITED","GATEWAY_BUSY","REFRESH_QUEUE_FULL","GATEWAY_CLOSED","PROVIDER_UNAVAILABLE","PROVIDER_NETWORK_ERROR","PROVIDER_STREAM_INTERRUPTED","INVALID_PROVIDER_STREAM","INFERENCE_RECEIPT_STORE_UNAVAILABLE","ACCOUNT_MODEL_OPTION_UNAVAILABLE","ACCOUNT_CONTEXT_BUDGET_EXCEEDED","ACCOUNT_IMAGE_BUDGET_REQUIRED","ACCOUNT_TEXT_CAPABILITY_UNSUPPORTED","ACCOUNT_TOOL_CAPABILITY_UNSUPPORTED");
    private static final Set<String> KEYS=Set.of("format","kind","httpStatus","nativeCode","observedAt","partialTextReceived","retry","automaticRetry","healthVerified","providerProcessingKnown","chargeKnown");
    private static final Set<String> RETRY_KEYS=Set.of("status","basis","delaySeconds","reportedDateAt","responseDateAt","estimatedNotBefore","healthVerified","quotaResetKnown");
    public final Kind kind;
    public final int httpStatus;
    public final String nativeCode;
    public final long observedAt;
    public final boolean partialTextReceived;
    private final String retryJson;
    private InferenceFailure(Kind kind,int status,String code,long observedAt,boolean partial,JSONObject retry)throws Exception {
        if(observedAt<1||status!=0&&(status<100||status>599))throw new IllegalArgumentException("invalid_failure_observation");
        this.kind=java.util.Objects.requireNonNull(kind);httpStatus=status;nativeCode=code;this.observedAt=observedAt;partialTextReceived=partial;retryJson=retry.toString();
    }
    public static InferenceFailure http(int status,List<String> retryAfter,List<String> date,long receivedAt)throws Exception {
        if(status<100||status>599||status>=200&&status<300)throw new IllegalArgumentException("not_http_failure");
        return new InferenceFailure(Kind.HTTP,status,"",receivedAt,false,retryAfter(retryAfter,date,receivedAt));
    }
    public static InferenceFailure local(Kind kind,long at)throws Exception { return local(kind,0,at); }
    public static InferenceFailure local(Kind kind,int observedHttpStatus,long at)throws Exception {
        if(kind==Kind.HTTP||kind==Kind.ACCOUNT_GATE||observedHttpStatus!=0&&kind!=Kind.PROTOCOL&&kind!=Kind.NETWORK&&kind!=Kind.TIMEOUT&&kind!=Kind.INCOMPLETE_STREAM&&kind!=Kind.CONTEXT_LIMIT)throw new IllegalArgumentException("native_failure_source_required");
        return new InferenceFailure(kind,observedHttpStatus,"",at,false,emptyRetry(RetryStatus.MISSING,RetryBasis.NONE));
    }
    /** Stable original AccountGateway evidence only; no provider body/code guessing or state mutation. */
    public static InferenceFailure account(String code,int status,long retryAt,long observedAt)throws Exception {
        JSONObject retry=emptyRetry(RetryStatus.MISSING,RetryBasis.NONE);
        if(retryAt>0) {
            long delay;try{delay=Math.subtractExact(retryAt,observedAt);}catch(ArithmeticException invalid){delay=-1;}
            retry=delay>=0&&delay<=MAX_WAIT_MILLIS?emptyRetry(RetryStatus.VALID,RetryBasis.NATIVE_ACCOUNT_GATE).put("estimatedNotBefore",retryAt):emptyRetry(RetryStatus.INVALID,RetryBasis.INVALID_OR_AMBIGUOUS);
        }
        return new InferenceFailure(Kind.ACCOUNT_GATE,status>=100&&status<=599?status:0,code!=null&&NATIVE_CODES.contains(code)?code:"",observedAt,false,retry);
    }
    public InferenceFailure withPartialText(boolean partial)throws Exception {
        return new InferenceFailure(kind,httpStatus,nativeCode,observedAt,partialTextReceived||partial,retry());
    }
    public JSONObject retry()throws Exception { return new JSONObject(retryJson); }
    public JSONObject toJson()throws Exception {
        return new JSONObject().put("format","pocketworld.inference-failure.v1").put("kind",kind.name()).put("httpStatus",httpStatus==0?JSONObject.NULL:httpStatus)
                .put("nativeCode",nativeCode.isEmpty()?JSONObject.NULL:nativeCode).put("observedAt",observedAt).put("partialTextReceived",partialTextReceived).put("retry",retry())
                .put("automaticRetry",false).put("healthVerified",false).put("providerProcessingKnown",false).put("chargeKnown",false);
    }
    /** Strict bounded receipt decoding. Invalid/unknown legacy data is not promoted to an observation. */
    public static InferenceFailure checked(JSONObject value)throws Exception {
        if(value==null||!keys(value).equals(KEYS)||!"pocketworld.inference-failure.v1".equals(value.getString("format")))throw new IllegalArgumentException("invalid_failure_receipt");
        for(String key:new String[]{"automaticRetry","healthVerified","providerProcessingKnown","chargeKnown"})if(!Boolean.FALSE.equals(value.get(key)))throw new IllegalArgumentException("failure_is_not_authority");
        Kind kind=Kind.valueOf(value.getString("kind"));long at=integer(value.get("observedAt"),1,Long.MAX_VALUE);int status=value.isNull("httpStatus")?0:(int)integer(value.get("httpStatus"),100,599);
        if(kind==Kind.HTTP&&(status<100||status>=200&&status<300)||kind!=Kind.HTTP&&kind!=Kind.ACCOUNT_GATE&&kind!=Kind.PROTOCOL&&kind!=Kind.NETWORK&&kind!=Kind.TIMEOUT&&kind!=Kind.INCOMPLETE_STREAM&&kind!=Kind.CONTEXT_LIMIT&&status!=0)throw new IllegalArgumentException("invalid_failure_http_status");
        Object partial=value.get("partialTextReceived");if(!(partial instanceof Boolean))throw new IllegalArgumentException("invalid_failure_partial_flag");
        String code=value.isNull("nativeCode")?"":value.getString("nativeCode");if(!code.isEmpty()&&(kind!=Kind.ACCOUNT_GATE||!NATIVE_CODES.contains(code)))throw new IllegalArgumentException("invalid_native_failure_code");
        JSONObject retry=checkedRetry(value.getJSONObject("retry"),at,kind);
        return new InferenceFailure(kind,status,code,at,(Boolean)partial,retry);
    }
    private static JSONObject checkedRetry(JSONObject value,long at,Kind kind)throws Exception {
        if(!keys(value).equals(RETRY_KEYS)||!Boolean.FALSE.equals(value.get("healthVerified"))||!Boolean.FALSE.equals(value.get("quotaResetKnown")))throw new IllegalArgumentException("invalid_retry_observation");
        RetryStatus status=RetryStatus.valueOf(value.getString("status"));RetryBasis basis=RetryBasis.valueOf(value.getString("basis"));
        Long until=optional(value,"estimatedNotBefore"),delay=value.isNull("delaySeconds")?null:integer(value.get("delaySeconds"),0,MAX_WAIT_MILLIS/1000),reported=optional(value,"reportedDateAt"),response=optional(value,"responseDateAt");
        if(status==RetryStatus.VALID) {
            if(until==null||until<at||until-at>MAX_WAIT_MILLIS||basis==RetryBasis.NONE||basis==RetryBasis.INVALID_OR_AMBIGUOUS)throw new IllegalArgumentException("invalid_retry_range");
            if(basis==RetryBasis.NATIVE_ACCOUNT_GATE&&kind!=Kind.ACCOUNT_GATE||basis!=RetryBasis.NATIVE_ACCOUNT_GATE&&kind!=Kind.HTTP)throw new IllegalArgumentException("invalid_retry_source");
            boolean consistent=switch(basis) {
                case DELTA_SECONDS_LOCAL_RECEIPT -> delay!=null&&reported==null&&response==null&&until-at==delay*1000;
                case RESPONSE_DATE_ESTIMATE -> reported!=null&&response!=null&&delay==null&&reported>=response&&until-at==reported-response;
                case LOCAL_CLOCK_ESTIMATE -> reported!=null&&response==null&&delay==null&&until.equals(reported);
                case NATIVE_ACCOUNT_GATE -> reported==null&&response==null&&delay==null;
                default -> false;
            };
            if(!consistent)throw new IllegalArgumentException("invalid_retry_basis");
        } else if(until!=null||delay!=null||reported!=null||response!=null||status==RetryStatus.MISSING&&basis!=RetryBasis.NONE||status==RetryStatus.INVALID&&basis!=RetryBasis.INVALID_OR_AMBIGUOUS)throw new IllegalArgumentException("invalid_unknown_retry");
        return new JSONObject(value.toString());
    }
    private static JSONObject retryAfter(List<String> values,List<String> dates,long at)throws Exception {
        if(values==null||values.isEmpty())return emptyRetry(RetryStatus.MISSING,RetryBasis.NONE);
        if(values.size()!=1)return emptyRetry(RetryStatus.INVALID,RetryBasis.INVALID_OR_AMBIGUOUS);
        String value=header(values.get(0));if(value==null)return emptyRetry(RetryStatus.INVALID,RetryBasis.INVALID_OR_AMBIGUOUS);
        try {
            if(value.matches("[0-9]{1,10}")) {
                long seconds=Long.parseLong(value),millis=Math.multiplyExact(seconds,1000);if(millis>MAX_WAIT_MILLIS)throw new ArithmeticException();
                return emptyRetry(RetryStatus.VALID,RetryBasis.DELTA_SECONDS_LOCAL_RECEIPT).put("delaySeconds",seconds).put("estimatedNotBefore",Math.addExact(at,millis));
            }
            Long stated=httpDate(value,at);if(stated==null)return emptyRetry(RetryStatus.INVALID,RetryBasis.INVALID_OR_AMBIGUOUS);
            Long response=dates!=null&&dates.size()==1?httpDate(header(dates.get(0)),at):null;long delta=Math.subtractExact(stated,response==null?at:response);
            if(delta<0||delta>MAX_WAIT_MILLIS)return emptyRetry(RetryStatus.INVALID,RetryBasis.INVALID_OR_AMBIGUOUS);
            return emptyRetry(RetryStatus.VALID,response==null?RetryBasis.LOCAL_CLOCK_ESTIMATE:RetryBasis.RESPONSE_DATE_ESTIMATE).put("reportedDateAt",stated)
                    .put("responseDateAt",response==null?JSONObject.NULL:response).put("estimatedNotBefore",Math.addExact(at,delta));
        } catch(ArithmeticException|NumberFormatException invalid) { return emptyRetry(RetryStatus.INVALID,RetryBasis.INVALID_OR_AMBIGUOUS); }
    }
    private static JSONObject emptyRetry(RetryStatus status,RetryBasis basis)throws Exception {
        return new JSONObject().put("status",status.name()).put("basis",basis.name()).put("delaySeconds",JSONObject.NULL).put("reportedDateAt",JSONObject.NULL)
                .put("responseDateAt",JSONObject.NULL).put("estimatedNotBefore",JSONObject.NULL).put("healthVerified",false).put("quotaResetKnown",false);
    }
    private static String header(String value) {
        if(value==null||value.length()>128)return null;if(value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf(0)>=0)return null;String trimmed=value.trim();if(trimmed.isEmpty())return null;
        for(int i=0;i<trimmed.length();i++)if(trimmed.charAt(i)<32||trimmed.charAt(i)>126)return null;return trimmed;
    }
    /** RFC9110 HTTP-date formats; RFC850's two-digit year is not SimpleDateFormat's 80-year default. */
    private static Long httpDate(String value,long receivedAt) {
        if(value==null)return null;
        String[] patterns={"EEE, dd MMM yyyy HH:mm:ss 'GMT'","dd-MMM-yy HH:mm:ss 'GMT'","EEE MMM d HH:mm:ss yyyy"};
        String[] shapes={"[A-Z][a-z]{2}, [0-9]{2} [A-Z][a-z]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT","[A-Z][a-z]+, [0-9]{2}-[A-Z][a-z]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT","[A-Z][a-z]{2} [A-Z][a-z]{2} ( [0-9]|[0-9]{2}) [0-9]{2}:[0-9]{2}:[0-9]{2} [0-9]{4}"};
        for(int i=0;i<patterns.length;i++) {
            if(!value.matches(shapes[i]))continue;String parsedValue=i==1?value.substring(value.indexOf(',')+2):value;
            SimpleDateFormat format=new SimpleDateFormat(patterns[i],Locale.US);format.setLenient(false);format.setTimeZone(TimeZone.getTimeZone("GMT"));
            if(i==1){Calendar base=Calendar.getInstance(TimeZone.getTimeZone("GMT"),Locale.US);base.clear();base.set(2000,Calendar.JANUARY,1);format.set2DigitYearStart(base.getTime());}
            ParsePosition position=new ParsePosition(0);Date parsed=format.parse(parsedValue,position);
            if(parsed==null||position.getIndex()!=parsedValue.length()||parsed.getTime()<1)continue;
            long result=parsed.getTime();
            if(i==1) {
                long maxFuture;try{maxFuture=Instant.ofEpochMilli(receivedAt).atZone(ZoneOffset.UTC).plusYears(50).toInstant().toEpochMilli();}catch(RuntimeException invalid){return null;}
                if(result>maxFuture){Calendar calendar=Calendar.getInstance(TimeZone.getTimeZone("GMT"),Locale.US);calendar.setTimeInMillis(result);calendar.add(Calendar.YEAR,-100);result=calendar.getTimeInMillis();}
                SimpleDateFormat weekday=new SimpleDateFormat("EEEE",Locale.US);weekday.setTimeZone(TimeZone.getTimeZone("GMT"));if(!weekday.format(new Date(result)).equals(value.substring(0,value.indexOf(','))))continue;
            }
            return result;
        }
        return null;
    }
    private static Set<String> keys(JSONObject value){Set<String> result=new HashSet<>();value.keys().forEachRemaining(result::add);return result;}
    private static Long optional(JSONObject object,String key)throws Exception{return object.isNull(key)?null:integer(object.get(key),1,Long.MAX_VALUE);}
    private static long integer(Object value,long min,long max){if(!(value instanceof Integer||value instanceof Long))throw new IllegalArgumentException("native_integer_required");long number=((Number)value).longValue();if(number<min||number>max)throw new IllegalArgumentException("native_integer_out_of_range");return number;}
}
