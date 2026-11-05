package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.models.ModelConnectionService;
import dev.pocketengine.runtime.models.AccountCatalogFacts;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic same-account model bindings, localhost transport, and private metadata only. */
public final class AccountModelContinuityTest {
    private static final String PNG="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kC9sAAAAASUVORK5CYII=";
    private JSONObject model(String id,boolean images,long window)throws Exception {
        JSONArray modalities=new JSONArray().put("text");if(images)modalities.put("image");
        return new JSONObject().put("slug",id).put("display_name",id).put("visibility","list")
                .put("context_window",window).put("max_output_tokens",4096).put("input_modalities",modalities);
    }
    private void catalog(AccountGatewayTest.Rig rig,JSONObject...models)throws Exception {
        JSONArray rows=new JSONArray();for(JSONObject model:models)rows.put(model);
        rig.fake.modelsBody=new JSONObject().put("models",rows).toString();
    }
    private long pinBoth(AccountGatewayTest.Rig rig,String id)throws Exception {
        long version=rig.book.row(id).getLong("version");
        catalog(rig,model("model-a",true,100000),model("model-b",true,80000));
        rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));
        rig.gateway.selectModelReviewed(id,version,"model-a");rig.gateway.selectModelReviewed(id,version,"model-b");
        JSONObject done=ResponsesFixtures.message("m","ok",false,true);
        rig.fake.stream=new ResponsesFixtures().item(0,ResponsesFixtures.message("m","",false,false),false)
                .text(0,"m","ok",false).item(0,done,true).complete(new JSONArray().put(done)).toString();
        return version;
    }
    private ResponsesRequest request(String model,boolean image)throws Exception {
        Object content=image?new JSONArray().put(new JSONObject().put("type","input_text").put("text","hello"))
                .put(new JSONObject().put("type","input_image").put("image_url",PNG)):"hello";
        return new ResponsesRequest(model,"",new JSONArray().put(new JSONObject().put("role","user").put("content",content)),
                new JSONArray(),image,ReasoningOptions.DEFAULT,4096,image?1024:0);
    }
    private void send(AccountGateway gateway,String id,long version,String model,boolean image)throws Exception {
        gateway.streamResponsesReviewed(id,version,request(model,image),new GatewayCall(5000),actual->assertEquals(version,actual),event->{});
    }
    @Test public void nextModelSelectionKeepsBothCapturedModelsAndImagesUsable()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=pinBoth(rig,id);
            assertEquals(version,rig.book.row(id).getLong("version"));
            assertEquals(100000,rig.gateway.modelFacts(id,version,"model-a").facts.contextWindow().value.tokens());
            assertEquals(80000,rig.gateway.modelFacts(id,version,"model-b").facts.contextWindow().value.tokens());
            for(String model:new String[]{"model-a","model-b"}){assertTrue(rig.gateway.supportsImageInput(id,version,model));send(rig.gateway,id,version,model,false);send(rig.gateway,id,version,model,true);}
            assertEquals(4,rig.fake.responses.get());
        }
    }
    @Test public void changingDefaultDuringAnImageResponseDoesNotRevokeItsOriginalModel()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=pinBoth(rig,id);rig.gateway.selectModelReviewed(id,version,"model-a");
            rig.fake.allowResponse=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
            try{var pending=worker.submit(()->{send(rig.gateway,id,version,"model-a",true);return true;});
                assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));rig.gateway.selectModelReviewed(id,version,"model-b");
                rig.fake.allowResponse.countDown();assertTrue(pending.get(2,TimeUnit.SECONDS));assertEquals(1,rig.fake.responses.get());
            }finally{rig.fake.allowResponse.countDown();worker.shutdownNow();}
        }
    }
    @Test public void refreshedCatalogUpdatesEveryPinnedModelAndRejectsRemovedOnes()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=pinBoth(rig,id);
            catalog(rig,model("model-a",false,20000),model("model-b",true,30000));
            rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));
            assertEquals(20000,rig.gateway.modelFacts(id,version,"model-a").facts.contextWindow().value.tokens());
            assertEquals(30000,rig.gateway.modelFacts(id,version,"model-b").facts.contextWindow().value.tokens());
            assertFalse(rig.gateway.supportsImageInput(id,version,"model-a"));assertTrue(rig.gateway.supportsImageInput(id,version,"model-b"));
            assertEquals("ACCOUNT_IMAGE_CAPABILITY_REQUIRED",assertThrows(GatewayFailure.class,()->send(rig.gateway,id,version,"model-a",true)).code);
            catalog(rig,model("model-b",true,30000));rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));
            assertEquals("STALE_MODEL_CATALOG",assertThrows(GatewayFailure.class,()->send(rig.gateway,id,version,"model-a",false)).code);
            assertFalse(rig.gateway.supportsImageInput(id,version,"model-a"));send(rig.gateway,id,version,"model-b",true);assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void evidenceRestoresWithoutBorrowingAcrossAccountsOrVersions()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=pinBoth(rig,id);rig.gateway.close();
            AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");
            try(AccountGateway gateway=new AccountGateway(restored,rig.fake.provider())){
                send(gateway,id,version,"model-a",true);send(gateway,id,version,"model-b",true);
                assertEquals("STALE_ACCOUNT_VERSION",assertThrows(GatewayFailure.class,()->gateway.modelFacts(id,version+1,"model-a")).code);
                assertEquals("STALE_MODEL_CATALOG",assertThrows(GatewayFailure.class,()->gateway.modelFacts(id,version,"unselected-model")).code);
                gateway.setEnabled(id,false);
                assertEquals("STALE_ACCOUNT_VERSION",assertThrows(GatewayFailure.class,()->send(gateway,id,version,"model-a",false)).code);
                assertFalse(gateway.inferenceIdentityCurrent(id,version));assertFalse(gateway.supportsImageInput(id,version,"model-b"));
                gateway.setEnabled(id,true);long changed=restored.row(id).getLong("version");
                assertNull(gateway.modelFacts(id,changed,"model-a"));assertFalse(gateway.supportsImageInput(id,changed,"model-a"));
                assertEquals(2,rig.fake.responses.get());
            }
        }
    }
    @Test public void freshOtherModelDoesNotHideExpiredOrFutureDatedEvidence()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=pinBoth(rig,id);
            for(long at:new long[]{System.currentTimeMillis()-ModelConnectionService.MAX_CATALOG_AGE_MILLIS-1000,System.currentTimeMillis()+60000}){
                JSONObject evidence=rig.book.row(id).getJSONObject("modelEvidence");
                evidence.getJSONObject("models").getJSONObject("model-a").put("catalogFacts",AccountCatalogFacts.parse(model("model-a",true,100000),"model-a",at).toJson());
                rig.book.metadata(id,version,new JSONObject().put("modelEvidence",evidence));
                assertEquals("STALE_MODEL_CATALOG",assertThrows(GatewayFailure.class,()->rig.gateway.modelFacts(id,version,"model-a")).code);
                assertFalse(rig.gateway.supportsImageInput(id,version,"model-a"));send(rig.gateway,id,version,"model-b",true);
                send(rig.gateway,id,version,"model-a",false);assertTrue(rig.gateway.supportsImageInput(id,version,"model-a"));
            }
            assertEquals(4,rig.fake.responses.get());
        }
    }
    @Test public void legacySingleModelEvidenceSurvivesSelectingASecondModel()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();long version=pinBoth(rig,id);
            JSONObject entry=rig.book.row(id).getJSONObject("modelEvidence").getJSONObject("models").getJSONObject("model-a");
            JSONObject legacy=new JSONObject().put("accountVersion",version).put("modelId","model-a").put("catalogFacts",entry.getJSONObject("catalogFacts"));
            JSONObject image=entry.getJSONObject("imageEvidence").put("accountVersion",version).put("modelId","model-a");
            rig.book.metadata(id,version,new JSONObject().put("modelEvidence",legacy).put("imageEvidence",image));
            assertTrue(rig.gateway.supportsImageInput(id,version,"model-a"));
            rig.book.metadata(id,version,new JSONObject().put("imageEvidence",new JSONObject(image.toString()).put("accountVersion",version-1)));
            assertFalse(rig.gateway.supportsImageInput(id,version,"model-a"));
            rig.book.metadata(id,version,new JSONObject().put("imageEvidence",new JSONObject(image.toString()).put("modelId","model-b")));
            assertFalse(rig.gateway.supportsImageInput(id,version,"model-a"));
            rig.book.metadata(id,version,new JSONObject().put("imageEvidence",image));
            rig.gateway.selectModelReviewed(id,version,"model-b");send(rig.gateway,id,version,"model-a",true);send(rig.gateway,id,version,"model-b",true);
            assertEquals(2,rig.fake.responses.get());
        }
    }
}
