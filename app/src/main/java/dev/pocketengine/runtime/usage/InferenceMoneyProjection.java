package dev.pocketengine.runtime.usage;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** One read projection over already deduplicated native receipts, not another spend ledger. */
public final class InferenceMoneyProjection {
    private InferenceMoneyProjection(){}
    public static JSONObject fromReceipts(JSONArray receipts)throws Exception{
        Map<String,Group> currencies=new LinkedHashMap<>();int unknown=0,conflict=0;
        for(int i=0;receipts!=null&&i<receipts.length();i++){
            JSONObject receipt=receipts.getJSONObject(i);if(receipt.optBoolean("projectionConflict")){conflict++;continue;}
            JSONObject usage=receipt.optJSONObject("usage"),estimate=usage==null?null:usage.optJSONObject("estimate");
            ModelPrice price=trustedPrice(receipt);if(price==null){unknown++;continue;}
            BigDecimal subtotal=decimal(estimate.opt("knownTokenSubtotal"));if(subtotal==null){unknown++;continue;}
            Group group=currencies.computeIfAbsent(price.currency,k->new Group());group.calls++;group.subtotal=group.subtotal.add(subtotal);
            if(estimate.optBoolean("allTokenComponentsPriced"))group.priced++;else group.partial++;
            if(!estimate.optBoolean("finalCounters"))group.nonfinal++;
        }
        JSONArray totals=new JSONArray();for(var entry:currencies.entrySet()){Group g=entry.getValue();totals.put(new JSONObject().put("currency",entry.getKey()).put("knownTokenSubtotal",g.subtotal.toPlainString()).put("pricedCalls",g.priced).put("partlyPricedCalls",g.partial).put("nonfinalCalls",g.nonfinal).put("calls",g.calls));}
        return new JSONObject().put("format","pocketworld.inference-money.v1").put("currencies",totals).put("unknownCalls",unknown).put("conflictingCalls",conflict)
            .put("actualCost",JSONObject.NULL).put("actualCostSource","not_observed").put("subscriptionRemaining",JSONObject.NULL).put("moneyLimitEnforced",false)
            .put("coverage","deduplicated_original_receipts_only_token_quote_not_bill");
    }
    static ModelPrice trustedPrice(JSONObject receipt){
        try{if(receipt.optBoolean("projectionConflict"))return null;JSONObject usage=receipt.getJSONObject("usage"),pricing=usage.getJSONObject("pricing"),estimate=usage.getJSONObject("estimate"),route=receipt.getJSONObject("route");
            if(!pricing.optBoolean("known")||!"api_key".equals(route.optString("kind"))||!"pocketworld.token-estimate.v1".equals(estimate.optString("algorithmVersion")))return null;
            ModelPrice price=ModelPrice.fromJson(pricing.getJSONObject("basis"));
            if(!price.model.equals(route.optString("requestedModel"))||!price.binding.connectionId().equals(route.optString("connectionId"))
                ||!price.binding.credentialId().equals(route.optString("credentialId"))||price.binding.credentialRevision()!=route.optLong("credentialRevision")
                ||!price.binding.installationId().equals(route.optString("installationId"))||price.binding.installationRevision()!=route.optLong("installationRevision")
                ||!price.priceVersion.equals(estimate.optString("priceVersion"))||!price.currency.equals(estimate.optString("currency")))return null;return price;
        }catch(Exception invalid){return null;}
    }
    private static BigDecimal decimal(Object raw){if(!(raw instanceof String)||((String)raw).length()>128)return null;try{BigDecimal value=new BigDecimal((String)raw);return value.signum()<0||value.precision()>60||(value.scale()< -36||value.scale()>36)?null:value;}catch(NumberFormatException invalid){return null;}}
    private static final class Group {BigDecimal subtotal=BigDecimal.ZERO;int calls,priced,partial,nonfinal;}
}
