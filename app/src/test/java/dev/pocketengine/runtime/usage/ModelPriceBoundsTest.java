package dev.pocketengine.runtime.usage;

import dev.pocketengine.runtime.models.ModelBinding;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Compact hostile exponents must be rejected before any decimal expansion; values are synthetic. */
public final class ModelPriceBoundsTest {
    private static ModelBinding binding(){return new ModelBinding("native-quote","openrouter",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENROUTER,URI.create("https://openrouter.ai/api/v1/models"),"native-credential",1,"native-install",1,false);}
    private static ModelPrice owner(BigDecimal value)throws Exception{return new ModelPrice(binding(),"actual-model","USD",ModelPrice.Source.OWNER_REVIEWED,"https://price.fixture/source","fixture",1000,0,0,Map.of(ModelPrice.Rate.INPUT,value));}
    private static ModelPrice provider(String prompt,String completion)throws Exception{return ModelPrice.provider(binding(),"actual-model",new JSONObject().put("pricing",new JSONObject().put("prompt",prompt).put("completion",completion)),"https://openrouter.ai/api/v1/models#page=1/data/0",1000);}
    @Test public void hugeProviderExponentsAreUnknownPerFieldWithoutExpandingOrDroppingOtherRealFields()throws Exception{
        for(String value:new String[]{"1e100000000","1e-100000000","1e2147483647","1e-2147483647","1e2147483648","1e-2147483648"}){
            ModelPrice price=provider(value,"0.000004");assertNotNull(value,price);assertNull(value,price.rate(ModelPrice.Rate.INPUT));assertEquals(0,new BigDecimal("4").compareTo(price.rate(ModelPrice.Rate.OUTPUT)));assertFalse(price.toJson().getString("priceVersion").isEmpty());
        }
        assertNull(provider("1e100000000","1e-100000000"));
    }
    @Test public void constructorChecksScaleExtremesBeforeStripOrPlainString()throws Exception{
        assertThrows(IllegalArgumentException.class,()->owner(new BigDecimal(BigInteger.ONE,Integer.MIN_VALUE)));
        assertThrows(IllegalArgumentException.class,()->owner(new BigDecimal(BigInteger.ONE,Integer.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class,()->owner(new BigDecimal("1e100000000")));
        assertThrows(IllegalArgumentException.class,()->owner(new BigDecimal("1e-100000000")));
    }
    @Test public void convertedProviderValueIsCheckedAgainAndLegalSmallDecimalsStayExact()throws Exception{
        ModelPrice tooLarge=provider("1000000000000000000000000","0.000004");assertNotNull(tooLarge);assertNull(tooLarge.rate(ModelPrice.Rate.INPUT));
        ModelPrice tiny=provider("0.000000000000000000000001","0.123456789012345678");assertNotNull(tiny);assertEquals(0,new BigDecimal("0.000000000000000001").compareTo(tiny.rate(ModelPrice.Rate.INPUT)));assertEquals(0,new BigDecimal("123456.789012345678").compareTo(tiny.rate(ModelPrice.Rate.OUTPUT)));assertEquals(tiny.priceVersion,ModelPrice.fromJson(tiny.toJson()).priceVersion);
        assertEquals(0,new BigDecimal("0.000000000000000000000001").compareTo(owner(new BigDecimal("1e-24")).rate(ModelPrice.Rate.INPUT)));
    }
    @Test public void ownerAndNativeCacheDecimalParsersRejectCompactHugeScaleAndPrecision()throws Exception{
        JSONObject input=new JSONObject().put("currency","USD").put("sourceRef","https://price.fixture/source").put("sourceVersion","fixture").put("rates",new JSONObject().put("INPUT","1e100000000"));assertThrows(IllegalArgumentException.class,()->ModelPrice.owner(binding(),"actual-model",input,1000));
        JSONObject saved=owner(BigDecimal.ONE).toJson();saved.getJSONObject("rates").put("INPUT","1e-2147483647");assertThrows(IllegalArgumentException.class,()->ModelPrice.fromJson(saved));
        input.getJSONObject("rates").put("INPUT","1234567890123456789012345678901");assertThrows(IllegalArgumentException.class,()->ModelPrice.owner(binding(),"actual-model",input,1000));
    }
    @Test public void malformedMoneyProjectionExponentCannotExpandOrBecomeAZeroSubtotal()throws Exception{
        ModelPrice price=owner(BigDecimal.ONE);JSONObject route=new JSONObject().put("kind","api_key").put("requestedModel",price.model).put("connectionId",price.binding.connectionId()).put("credentialId",price.binding.credentialId()).put("credentialRevision",price.binding.credentialRevision()).put("installationId",price.binding.installationId()).put("installationRevision",price.binding.installationRevision());
        JSONObject estimate=new JSONObject().put("algorithmVersion","pocketworld.token-estimate.v1").put("priceVersion",price.priceVersion).put("currency","USD").put("knownTokenSubtotal","1e100000000");JSONObject receipt=new JSONObject().put("route",route).put("usage",new JSONObject().put("pricing",new JSONObject().put("known",true).put("basis",price.toJson())).put("estimate",estimate));
        JSONObject projected=InferenceMoneyProjection.fromReceipts(new JSONArray().put(receipt));assertEquals(0,projected.getJSONArray("currencies").length());assertEquals(1,projected.getInt("unknownCalls"));assertTrue(projected.isNull("actualCost"));
    }
}
