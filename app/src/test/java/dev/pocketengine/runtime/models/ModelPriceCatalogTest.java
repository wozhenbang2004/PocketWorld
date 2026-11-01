package dev.pocketengine.runtime.models;

import dev.pocketengine.runtime.usage.ModelPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ModelPriceCatalogTest {
    private static final class Time extends Clock{long at=1000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(at);}public long millis(){return at;}}
    private static ModelBinding binding(String host,ModelBinding.Format format){return new ModelBinding("price-connection","openai_chat",ModelBinding.Protocol.OPENAI_CHAT,format,URI.create("https://"+host+"/models"),"price-key",1,"price-install",1,false);}
    private static ModelPrice quote(ModelBinding binding,long at)throws Exception{EnumMap<ModelPrice.Rate,BigDecimal> r=new EnumMap<>(ModelPrice.Rate.class);r.put(ModelPrice.Rate.INPUT,new BigDecimal("2"));return new ModelPrice(binding,"actual-model","USD",ModelPrice.Source.OWNER_REVIEWED,"https://price.fixture/actual","fixture-review",at,0,0,r);}
    private static String body(JSONObject prices)throws Exception{return new JSONObject().put("data",new JSONArray().put(new JSONObject().put("id","actual-model").put("pricing",prices))).toString();}
    @Test public void documentedProviderPricesStaySeparateFromCapabilitiesAndUnknownCurrencyIsNotAssumedUsd()throws Exception{
        JSONObject rates=new JSONObject().put("prompt","0.000002").put("completion","0.000004").put("input_cache_read","0.0000002");
        var official=CatalogParser.parse(binding("openrouter.ai",ModelBinding.Format.OPENROUTER),body(rates),1000,1);ModelPrice price=official.prices().get("actual-model");assertNotNull(price);assertEquals("USD",price.currency);assertEquals(new BigDecimal("2"),price.rate(ModelPrice.Rate.INPUT));assertFalse(official.models().get("actual-model").contextWindow().known);
        assertTrue(CatalogParser.parse(binding("compatible.fixture",ModelBinding.Format.OPENROUTER),body(rates),1000,1).prices().isEmpty());rates.put("currency","CNY").put("unit","per_token");assertEquals("CNY",CatalogParser.parse(binding("compatible.fixture",ModelBinding.Format.OPENROUTER),body(rates),1000,1).prices().get("actual-model").currency);
        assertTrue(CatalogParser.parse(binding("compatible.fixture",ModelBinding.Format.OPENAI),body(rates),1000,1).prices().isEmpty());
    }
    @Test public void ownerQuoteAndCapabilityFieldsShareOriginalCasWithoutErasingEachOtherAndSurviveReopen()throws Exception{
        Time time=new Time();Path file=Files.createTempDirectory("price-catalog").resolve("catalog.json");ModelCatalog catalog=new ModelCatalog(file,time);ModelBinding binding=binding("price.fixture",ModelBinding.Format.OPENAI);catalog.bind(binding);ModelCatalog.ModelKey key=new ModelCatalog.ModelKey(binding,"actual-model");
        long revision=catalog.updateOwner(key,0,Map.of(ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true)),Set.of());ModelPrice price=quote(binding,time.at);assertEquals(2,catalog.updateOwnerPrice(key,revision,price));assertTrue(catalog.get(key,6000).facts().toolCalls().value);assertThrows(IllegalStateException.class,()->catalog.updateOwner(key,revision,Map.of(),Set.of(ModelFacts.Field.TOOL_CALLS)));
        ModelCatalog reopened=new ModelCatalog(file,time);reopened.bind(binding);assertEquals(price.priceVersion,reopened.price(key,6000).priceVersion);assertTrue(reopened.get(key,6000).facts().toolCalls().value);assertEquals(3,reopened.updateOwnerPrice(key,2,null));assertNull(reopened.price(key,6000));assertTrue(reopened.get(key,6000).facts().toolCalls().value);
    }
    @Test public void providerPriceExpiresButLastRealObservationAndOwnerQuoteRemainExplainable()throws Exception{
        Time time=new Time();ModelBinding binding=binding("openrouter.ai",ModelBinding.Format.OPENROUTER);ModelCatalog catalog=new ModelCatalog(Files.createTempDirectory("price-age").resolve("cache"),time);catalog.bind(binding);var ticket=catalog.beginRefresh(binding);var page=CatalogParser.parse(binding,body(new JSONObject().put("prompt","0.000002")),1000,1);assertEquals(ModelCatalog.RefreshResult.APPLIED,catalog.commitRefresh(ticket,new CatalogHttpClient.Snapshot(binding,1000,page.models(),page.prices())));var key=new ModelCatalog.ModelKey(binding,"actual-model");assertNotNull(catalog.price(key,100));ModelPrice old=catalog.priceObservation(key);time.at=1101;assertNull(catalog.price(key,100));assertEquals(old.priceVersion,catalog.priceObservation(key).priceVersion);
        ModelPrice reviewed=quote(binding,time.at);catalog.updateOwnerPrice(key,0,reviewed);time.at=20000;assertEquals(reviewed.priceVersion,catalog.price(key,100).priceVersion);
    }
    @Test public void failedQuotePersistenceLeavesOriginalPriceAndOwnerRevisionUntouched()throws Exception{
        Time time=new Time();Path file=Files.createTempDirectory("price-save").resolve("cache"),held=file.resolveSibling("held");ModelCatalog catalog=new ModelCatalog(file,time);ModelBinding binding=binding("price.fixture",ModelBinding.Format.OPENAI);catalog.bind(binding);var key=new ModelCatalog.ModelKey(binding,"actual-model");ModelPrice p=quote(binding,time.at);catalog.updateOwnerPrice(key,0,p);Files.move(file,held);Files.createDirectory(file);Files.writeString(file.resolve("occupied"),"fixture");
        try{assertThrows(java.io.IOException.class,()->catalog.updateOwnerPrice(key,1,null));assertEquals(p.priceVersion,catalog.price(key,6000).priceVersion);assertEquals(1,catalog.get(key,6000).ownerRevision());}finally{Files.delete(file.resolve("occupied"));Files.delete(file);Files.move(held,file);}
    }
    @Test public void legacySchemaOneCacheWithoutPriceFieldsLoadsWithoutInventingAQuote()throws Exception{
        Time time=new Time();Path file=Files.createTempDirectory("old-price-cache").resolve("cache");ModelBinding binding=binding("price.fixture",ModelBinding.Format.OPENAI);ModelCatalog c=new ModelCatalog(file,time);c.bind(binding);var key=new ModelCatalog.ModelKey(binding,"actual-model");c.updateOwner(key,0,Map.of(ModelFacts.Field.TOOL_CALLS,ModelFacts.OwnerValue.known(true)),Set.of());JSONObject old=new JSONObject(Files.readString(file));old.getJSONArray("overrides").getJSONObject(0).remove("price");Files.writeString(file,old.toString());ModelCatalog reopened=new ModelCatalog(file,time);reopened.bind(binding);assertNull(reopened.price(key,6000));assertTrue(reopened.get(key,6000).facts().toolCalls().value);
    }
}
