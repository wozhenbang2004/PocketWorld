package dev.pocketengine.runtime.usage;

import dev.pocketengine.runtime.models.ModelBinding;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;

/** Optional quote in the original model catalog. Never a charge, credit balance or spending authority. */
public final class ModelPrice {
    public enum Rate { INPUT,OUTPUT,CACHE_READ,CACHE_WRITE,CACHE_WRITE_5M,CACHE_WRITE_1H,REASONING,TOOL_PROMPT,REQUEST }
    public enum Source { PROVIDER_CATALOG,OWNER_REVIEWED }
    public final ModelBinding binding;
    public final String model,currency,sourceRef,sourceVersion,priceVersion;
    public final long observedAt,effectiveFrom,effectiveUntil;
    public final Source source;
    private final Map<Rate,BigDecimal> rates;
    /** All token prices are exact currency / 1,000,000 tokens; REQUEST is currency / physical attempt. */
    public ModelPrice(ModelBinding binding,String model,String currency,Source source,String ref,String sourceVersion,
                      long observedAt,long effectiveFrom,long effectiveUntil,Map<Rate,BigDecimal> prices)throws Exception{
        this.binding=Objects.requireNonNull(binding);this.model=Objects.requireNonNull(model);this.currency=Objects.requireNonNull(currency);
        new dev.pocketengine.runtime.models.ModelCatalog.ModelKey(binding,model);
        if(!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("请填写三位币种代码");java.util.Currency.getInstance(currency);
        URI uri=URI.create(ref);if(ref.length()>2048||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null
            ||!("https".equals(uri.getScheme())||source==Source.PROVIDER_CATALOG&&"http".equals(uri.getScheme())))throw new IllegalArgumentException("价目来源需为无凭据的明确HTTPS文档地址");
        if(sourceVersion==null||sourceVersion.isEmpty()||sourceVersion.length()>160||sourceVersion.codePoints().anyMatch(Character::isISOControl)||observedAt<1||effectiveFrom<0||effectiveUntil<0||effectiveUntil>0&&effectiveUntil<=effectiveFrom)throw new IllegalArgumentException("价目版本/观察时间/有效期无效");
        this.source=Objects.requireNonNull(source);sourceRef=ref;this.sourceVersion=sourceVersion;this.observedAt=observedAt;this.effectiveFrom=effectiveFrom;this.effectiveUntil=effectiveUntil;
        EnumMap<Rate,BigDecimal> copy=new EnumMap<>(Rate.class);
        for(var entry:prices.entrySet()){BigDecimal value=bounded(entry.getValue());value=bounded(value.stripTrailingZeros());copy.put(Objects.requireNonNull(entry.getKey()),value);}
        if(copy.isEmpty())throw new IllegalArgumentException("至少填写一项已知单价；没有价目时保持未知");rates=Collections.unmodifiableMap(copy);
        priceVersion=sha(canonical(toJson(false)));
    }
    public BigDecimal rate(Rate key){return rates.get(key);}
    public boolean validAt(long now){return now>=observedAt&&(effectiveFrom==0||now>=effectiveFrom)&&(effectiveUntil==0||now<effectiveUntil);}
    public JSONObject toJson()throws Exception{return toJson(true);}
    private JSONObject toJson(boolean version)throws Exception{
        JSONObject prices=new JSONObject();for(Rate rate:Rate.values())prices.put(rate.name(),rates.containsKey(rate)?rates.get(rate).toPlainString():JSONObject.NULL);
        JSONObject out=new JSONObject().put("format","pocketworld.model-price.v1").put("binding",binding.toJson()).put("model",model).put("currency",currency)
            .put("source",source.name()).put("sourceRef",sourceRef).put("sourceVersion",sourceVersion).put("observedAt",observedAt)
            .put("effectiveFrom",effectiveFrom==0?JSONObject.NULL:effectiveFrom).put("effectiveUntil",effectiveUntil==0?JSONObject.NULL:effectiveUntil)
            .put("tokenUnit",1000000).put("requestUnit","physical_attempt_estimate_not_charge").put("rates",prices);
        if(version)out.put("priceVersion",priceVersion);return out;
    }
    /** Original native cache only. A model response cannot install a source. */
    public static ModelPrice fromJson(JSONObject value)throws Exception{
        if(!"pocketworld.model-price.v1".equals(value.getString("format"))||integer(value,"tokenUnit",1)!=1000000)throw new IllegalArgumentException("价目格式不可核对");
        EnumMap<Rate,BigDecimal> prices=new EnumMap<>(Rate.class);JSONObject rates=value.getJSONObject("rates");for(Rate rate:Rate.values())if(rates.has(rate.name())&&!rates.isNull(rate.name())){prices.put(rate,decimal(rates.get(rate.name())));}
        ModelPrice price=new ModelPrice(ModelBinding.fromJson(value.getJSONObject("binding")),value.getString("model"),value.getString("currency"),Source.valueOf(value.getString("source")),value.getString("sourceRef"),value.getString("sourceVersion"),integer(value,"observedAt",1),value.isNull("effectiveFrom")?0:integer(value,"effectiveFrom",0),value.isNull("effectiveUntil")?0:integer(value,"effectiveUntil",0),prices);
        if(!price.priceVersion.equals(value.getString("priceVersion")))throw new SecurityException("原价目版本摘要不匹配");return price;
    }
    /** Native owner form values; identity/time/source are bound by the existing catalog owner CAS. */
    public static ModelPrice owner(ModelBinding binding,String model,JSONObject supplied,long now)throws Exception{
        java.util.Set<String> allowed=java.util.Set.of("currency","sourceRef","sourceVersion","effectiveFrom","effectiveUntil","rates");for(var keys=supplied.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IllegalArgumentException("只允许机主明确的价目字段");
        EnumMap<Rate,BigDecimal> prices=new EnumMap<>(Rate.class);JSONObject rates=supplied.getJSONObject("rates");for(var keys=rates.keys();keys.hasNext();){String key=keys.next();Rate rate=Rate.valueOf(key);if(rates.isNull(key))continue;prices.put(rate,decimal(rates.get(key)));}
        return new ModelPrice(binding,model,supplied.getString("currency"),Source.OWNER_REVIEWED,supplied.getString("sourceRef"),supplied.getString("sourceVersion"),now,supplied.has("effectiveFrom")&&!supplied.isNull("effectiveFrom")?integer(supplied,"effectiveFrom",0):0,supplied.has("effectiveUntil")&&!supplied.isNull("effectiveUntil")?integer(supplied,"effectiveUntil",0):0,prices);
    }
    /** Only a documented schema/unit/currency is read; arbitrary compatible endpoints never imply USD. */
    public static ModelPrice provider(ModelBinding binding,String model,JSONObject row,String ref,long at){
        if(binding.format()!=ModelBinding.Format.OPENROUTER)return null;JSONObject pricing=row.optJSONObject("pricing");if(pricing==null)return null;
        try{
            boolean official="openrouter.ai".equalsIgnoreCase(binding.modelsEndpoint().getHost());
            String currency=official?"USD":pricing.getString("currency");
            if(official&&pricing.has("currency")&&!"USD".equals(pricing.opt("currency"))||pricing.has("unit")&&!"per_token".equals(pricing.opt("unit"))||!official&&!"per_token".equals(pricing.optString("unit")))return null;
            EnumMap<Rate,BigDecimal> rates=new EnumMap<>(Rate.class);
            String[][] fields={{"prompt","INPUT"},{"completion","OUTPUT"},{"input_cache_read","CACHE_READ"},{"input_cache_write","CACHE_WRITE"},{"request","REQUEST"}};
            for(String[] field:fields){Object raw=pricing.opt(field[0]);if(!(raw instanceof String)||((String)raw).length()>64)continue;
                try{BigDecimal value=decimal(raw);BigDecimal converted=field[0].equals("request")?value:bounded(value.movePointRight(6));rates.put(Rate.valueOf(field[1]),converted);}catch(IllegalArgumentException invalid){/* This field stays unknown. */}}
            if(rates.isEmpty())return null;
            return new ModelPrice(binding,model,currency,Source.PROVIDER_CATALOG,ref+"/pricing","openrouter-compatible-models-pricing-v1",at,0,0,rates);
        }catch(Exception unavailable){return null;}
    }
    /** Check the compact decimal representation before normalization/expansion, including scale MIN_VALUE. */
    private static BigDecimal bounded(BigDecimal value){
        Objects.requireNonNull(value);int scale=value.scale();
        if(value.signum()<0||value.precision()>30||scale< -24||scale>24)throw new IllegalArgumentException("单价需为非负且有界的十进制数，空值不是零");return value;
    }
    private static BigDecimal decimal(Object raw){
        if(!(raw instanceof String)||((String)raw).isEmpty()||((String)raw).length()>64)throw new IllegalArgumentException("单价需保留有界十进制文字；留空为未知");
        try{return bounded(new BigDecimal((String)raw));}catch(NumberFormatException invalid){throw new IllegalArgumentException("单价需为合法且有界的十进制数",invalid);}
    }
    private static long integer(JSONObject j,String key,long min)throws Exception{Object value=j.get(key);if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min)throw new IllegalArgumentException("价目整数无效");return ((Number)value).longValue();}
    private static String canonical(JSONObject object)throws Exception{
        java.util.List<String> keys=new java.util.ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");
        for(String key:keys){Object value=object.get(key);out.append(JSONObject.quote(key)).append(':');if(value instanceof JSONObject)out.append(canonical((JSONObject)value));else if(value instanceof String)out.append(JSONObject.quote((String)value));else if(value==JSONObject.NULL)out.append("null");else if(value instanceof Number||value instanceof Boolean)out.append(value);else throw new IllegalArgumentException("价目包含不支持的值");out.append(',');}return out.append('}').toString();
    }
    private static String sha(String value)throws Exception{StringBuilder text=new StringBuilder("sha256:");for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
}
