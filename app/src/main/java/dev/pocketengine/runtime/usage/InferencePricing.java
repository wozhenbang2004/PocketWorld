package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import java.math.BigDecimal;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.pocketengine.runtime.usage.ModelPrice.Rate;

/** Read-only quote arithmetic over original cumulative counters. It never grants credit or enforces a money limit. */
public final class InferencePricing {
    public interface Source { ModelPrice resolve(ModelConfig captured)throws Exception; }
    private static volatile Source source;
    private InferencePricing(){}
    public static void installHostSource(Source host){source=Objects.requireNonNull(host);}
    /** Called once at the original physical dispatch. Price changes cannot reprice an old receipt. */
    static JSONObject freeze(ModelConfig config,long at)throws Exception{
        if(config.usesAccount())return unavailable("subscription_is_not_api_pricing");
        if(config.usesBedrock())return unavailable("bedrock_price_not_observed");
        Source current=source;if(current==null)return unavailable("price_host_not_installed");
        try{ModelPrice price=current.resolve(config);if(price==null)return unavailable("price_not_observed");
            if(config.capabilityBinding==null||!price.binding.equals(config.capabilityBinding)||!price.model.equals(config.model))return unavailable("price_route_mismatch");
            if(!price.validAt(at))return unavailable("price_not_effective");
            JSONObject basis=price.toJson();if(config.containsSensitive(basis.toString()))return unavailable("price_identity_redacted");
            return new JSONObject().put("known",true).put("capturedAt",at).put("basis",basis).put("historicalRepricing",false);
        }catch(InterruptedException stopped){throw stopped;}catch(Exception unavailable){return unavailable("price_unavailable");}
    }
    private static JSONObject unavailable(String reason)throws Exception{return new JSONObject().put("known",false).put("reason",reason).put("basis",JSONObject.NULL).put("historicalRepricing",false);}
    /** Optional diagnostic enrichment; original provider counters are not mutated or replaced. */
    static JSONObject apply(JSONObject original,JSONObject frozen)throws Exception{
        JSONObject usage=new JSONObject(original.toString()),price=frozen==null?unavailable("no_frozen_price"):new JSONObject(frozen.toString());
        JSONObject basis=price.optJSONObject("basis"),estimate;
        try{estimate=basis==null?unknown(price.optString("reason","price_unknown")):estimate(original,ModelPrice.fromJson(basis));}
        catch(Exception optionalFailure){price=unavailable("estimate_unavailable");basis=null;estimate=unknown("estimate_unavailable");}
        return usage.put("pricing",price).put("estimate",estimate).put("priceVersion",basis==null?JSONObject.NULL:basis.getString("priceVersion"))
            .put("estimatedCost",estimate.opt("estimatedTokenCost")).put("estimatedCostSource",basis==null?"unknown":"native_frozen_quote_token_components")
            .put("estimatedCostMeaning","token_components_only_not_bill_or_hard_budget");
    }
    private static JSONObject unknown(String reason)throws Exception{return new JSONObject().put("currency",JSONObject.NULL).put("estimatedTokenCost",JSONObject.NULL).put("knownTokenSubtotal",JSONObject.NULL).put("allTokenComponentsPriced",false).put("finalCounters",false).put("lines",new JSONArray()).put("reason",reason).put("actualCost",JSONObject.NULL);}
    public static JSONObject estimate(JSONObject usage,ModelPrice price)throws Exception{
        Calc c=new Calc(usage,price);String protocol=usage.optString("protocol");
        String expected=switch(price.binding.protocol()){case OPENAI_CHAT->"openai_chat";case OPENAI_RESPONSES->"openai_responses";case ANTHROPIC->"anthropic";case GEMINI->"gemini";case BEDROCK_ANTHROPIC->"bedrock_anthropic";};
        if(!expected.equals(protocol))return unknown("price_protocol_mismatch");
        switch(protocol){
            case "openai_chat":case "openai_responses":
                inclusiveInput(c);inclusiveOutput(c);break;
            case "anthropic":
                c.token("普通输入",c.count(ProviderUsage.Counter.INPUT),Rate.INPUT);c.token("缓存读取",c.count(ProviderUsage.Counter.CACHE_READ),Rate.CACHE_READ);anthropicWrites(c);inclusiveOutput(c);break;
            case "gemini":
                // Official total = prompt + tool-use prompt + thoughts + response candidates.
                inclusiveReadOnlyInput(c);c.token("工具提示输入",c.count(ProviderUsage.Counter.TOOL_PROMPT),Rate.TOOL_PROMPT,Rate.INPUT);
                c.token("响应候选输出",c.count(ProviderUsage.Counter.OUTPUT),Rate.OUTPUT);c.token("推理输出（未含在候选输出中）",c.count(ProviderUsage.Counter.REASONING),Rate.REASONING,Rate.OUTPUT);break;
            default:return unknown("unsupported_pricing_protocol");
        }
        return new JSONObject().put("algorithmVersion","pocketworld.token-estimate.v1").put("currency",price.currency).put("priceVersion",price.priceVersion).put("source",price.source.name()).put("sourceRef",price.sourceRef)
            .put("estimatedTokenCost",c.complete?c.subtotal.toPlainString():JSONObject.NULL).put("knownTokenSubtotal",c.known==0?JSONObject.NULL:c.subtotal.toPlainString())
            .put("allTokenComponentsPriced",c.complete).put("finalCounters",c.complete&&c.finalCounters).put("lines",c.lines)
            .put("coverage","observed_token_components_only_excludes_request_media_tools_taxes_discounts").put("actualCost",JSONObject.NULL)
            .put("moneyLimitEnforced",false).put("subscriptionRemaining",JSONObject.NULL);
    }
    private static void inclusiveInput(Calc c)throws Exception{
        Long input=c.count(ProviderUsage.Counter.INPUT),read=c.count(ProviderUsage.Counter.CACHE_READ),write=c.count(ProviderUsage.Counter.CACHE_WRITE);
        BigDecimal base=c.price.rate(Rate.INPUT),readPrice=c.price.rate(Rate.CACHE_READ),writePrice=c.price.rate(Rate.CACHE_WRITE);
        boolean readUniform=equal(base,readPrice),writeUniform=equal(base,writePrice);
        if(input!=null&&(read!=null&&read>input||write!=null&&write>input||read!=null&&write!=null&&read>input-write)){c.token("输入/缓存计数冲突",null,Rate.INPUT);return;}
        if(readUniform&&writeUniform){c.token("输入合计（缓存同价，不再另加）",input,Rate.INPUT);return;}
        if(input==null||!readUniform&&read==null||!writeUniform&&write==null){c.token("输入分项尚不可拆分（不猜缓存为0）",null,Rate.INPUT);return;}
        long plain=input-(readUniform?0:read)-(writeUniform?0:write);
        c.token("普通输入（已扣不同价缓存）",plain,Rate.INPUT);
        if(!readUniform)c.token("缓存读取",read,Rate.CACHE_READ);
        if(!writeUniform)c.token("缓存写入",write,Rate.CACHE_WRITE);
    }
    private static void inclusiveReadOnlyInput(Calc c)throws Exception{
        Long input=c.count(ProviderUsage.Counter.INPUT),read=c.count(ProviderUsage.Counter.CACHE_READ);
        if(equal(c.price.rate(Rate.INPUT),c.price.rate(Rate.CACHE_READ))){c.token("输入合计（缓存同价，不再另加）",input,Rate.INPUT);return;}
        if(input==null||read==null||read>input){c.token("输入与缓存尚不可拆分",null,Rate.INPUT);return;}
        c.token("非缓存输入",input-read,Rate.INPUT);c.token("缓存读取",read,Rate.CACHE_READ);
    }
    private static void inclusiveOutput(Calc c)throws Exception{
        Long output=c.count(ProviderUsage.Counter.OUTPUT),reasoning=c.count(ProviderUsage.Counter.REASONING);
        BigDecimal separate=c.price.rate(Rate.REASONING),base=c.price.rate(Rate.OUTPUT);
        if(separate==null||equal(base,separate)){c.token("输出合计（已含推理，不再相加）",output,Rate.OUTPUT);return;}
        if(output==null||reasoning==null||reasoning>output){c.token("输出与推理尚不可拆分",null,Rate.OUTPUT);return;}
        c.token("普通输出",output-reasoning,Rate.OUTPUT);c.token("推理输出",reasoning,Rate.REASONING);
    }
    private static void anthropicWrites(Calc c)throws Exception{
        Long all=c.count(ProviderUsage.Counter.CACHE_WRITE),five=c.count(ProviderUsage.Counter.CACHE_WRITE_5M),hour=c.count(ProviderUsage.Counter.CACHE_WRITE_1H);
        if(all!=null&&all==0){if(five!=null&&five>0||hour!=null&&hour>0)c.token("缓存写入计数冲突",null,Rate.CACHE_WRITE);else c.token("缓存写入（服务明确返回0）",0L,Rate.CACHE_WRITE);return;}
        if(five!=null&&hour!=null){
            long sum;try{sum=Math.addExact(five,hour);}catch(ArithmeticException overflow){c.token("缓存写入计数溢出",null,Rate.CACHE_WRITE);return;}
            if(all!=null&&sum!=all){c.token("缓存写入TTL合计冲突",null,Rate.CACHE_WRITE);return;}
            c.token("缓存写入5m",five,Rate.CACHE_WRITE_5M);c.token("缓存写入1h",hour,Rate.CACHE_WRITE_1H);return;
        }
        // A generic rate must be explicit; missing TTL never silently means 5m.
        c.token("缓存写入（TTL未完整返回）",all,Rate.CACHE_WRITE);
    }
    private static boolean equal(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}
    private static final class Calc {
        final JSONObject usage;final ModelPrice price;final JSONArray lines=new JSONArray();BigDecimal subtotal=BigDecimal.ZERO;int known;boolean complete=true,finalCounters=true;
        Calc(JSONObject usage,ModelPrice price){this.usage=usage;this.price=price;}
        Long count(ProviderUsage.Counter key){
            JSONObject counters=usage.optJSONObject("counters"),row=counters==null?null:counters.optJSONObject(key.name());
            if(row==null||!"provider_reported".equals(row.optString("source")))return null;
            Object raw=row.opt("value");if(!(raw instanceof Number))return null;
            try{long value=new BigDecimal(raw.toString()).longValueExact();if(value<0)return null;if(!"FINAL".equals(row.optString("stage")))finalCounters=false;return value;}catch(ArithmeticException|NumberFormatException invalid){return null;}
        }
        void token(String label,Long count,Rate rate)throws Exception{token(label,count,rate,null);}
        void token(String label,Long count,Rate rate,Rate fallback)throws Exception{
            BigDecimal unit=price.rate(rate);if(unit==null&&fallback!=null)unit=price.rate(fallback);
            BigDecimal cost=count==null||count>0&&unit==null?null:count==0?BigDecimal.ZERO:unit.multiply(BigDecimal.valueOf(count)).movePointLeft(6);
            if(cost==null)complete=false;else{subtotal=subtotal.add(cost);known++;}
            lines.put(new JSONObject().put("label",label).put("counter",rate.name()).put("tokens",count==null?JSONObject.NULL:count)
                .put("pricePerMillion",unit==null?JSONObject.NULL:unit.toPlainString()).put("estimatedCost",cost==null?JSONObject.NULL:cost.toPlainString())
                .put("source","frozen_quote_times_provider_counter").put("zeroMeaning",count!=null&&count==0?"provider_counter_explicit_zero_not_unknown_price_is_free":JSONObject.NULL));
        }
    }
}
