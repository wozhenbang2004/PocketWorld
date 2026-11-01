package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual same-token HTTP fixtures and native gateway. Images are synthetic, never device captures. */
public final class ChatGptImageJourneyTest {
    static final String PNG="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kC9sAAAAASUVORK5CYII=";
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private ModelConfig config(AccountGatewayTest.Rig rig,String id,boolean consent)throws Exception {
        return new ModelConfig("https://saved.example/v1","saved-api-key","saved-api-model",consent,3,10)
            .withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId",id)
                .put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model")));
    }
    private void catalog(AccountGatewayTest.Rig rig,boolean image)throws Exception {
        rig.fake.modelsBody=new JSONObject().put("models",new JSONArray().put(new JSONObject().put("slug","fixture-model").put("display_name","Neutral")
            .put("visibility","list").put("input_modalities",new JSONArray().put("text").put(image?"image":"audio")))).toString();
    }
    private void pin(AccountGatewayTest.Rig rig,String id)throws Exception {
        long version=rig.book.row(id).getLong("version");rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));rig.gateway.selectModelReviewed(id,version,"fixture-model");
    }
    private void bind(AccountGatewayTest.Rig rig){
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public boolean imageInputSupported(ModelConfig.AccountModelRef ref){return rig.gateway.supportsImageInput(ref.accountId,ref.accountVersion,ref.modelId);}
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall op,ChatGptProvider.EventSink sink)throws GatewayFailure{rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,req,op,sink);}
            public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest req,GatewayCall op,ModelGateway.AccountVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure{
                rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,req,op,versions::authorized,sink);
            }
        });
    }
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray()
        .put(new JSONObject().put("type","text").put("text","Describe this owner-approved fixture image"))
        .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",PNG)))));}
    private String answer()throws Exception {JSONObject item=ResponsesFixtures.message("msg","fixture answer",false,true);return new ResponsesFixtures()
        .item(0,ResponsesFixtures.message("msg","",false,false),false).text(0,"msg","fixture answer",false).item(0,item,true).complete(new JSONArray().put(item)).toString();}
    @Test public void exactSelectedCatalogEvidenceAndOwnerConsentProduceOneNativeImagePost()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,true);pin(rig,id);bind(rig);ModelConfig config=config(rig,id,true);rig.fake.stream=answer();
            assertTrue(ModelGateway.accountSupportsImageInput(config.accountModel));
            assertEquals("fixture answer",ModelGateway.complete(config,messages(),null,new RunControl()).getString("content"));
            JSONObject image=rig.fake.lastRequest.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1);
            assertEquals("input_image",image.getString("type"));assertEquals(PNG,image.getString("image_url"));assertFalse(rig.fake.lastRequest.toString().contains("saved-api-key"));
            assertEquals("Bearer fixture-access-secret-0",rig.fake.lastAuthorization);assertEquals(1,rig.fake.responses.get());assertEquals(1,rig.fake.models.get());
        }
    }
    @Test public void disabledScreenshotConsentDoesNotSendEvenKnownImageInput()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,true);pin(rig,id);bind(rig);
            try{ModelGateway.complete(config(rig,id,false),messages(),null,new RunControl());fail();}catch(ModelGateway.GatewayException denied){assertTrue(denied.getMessage().contains("关闭"));}
            assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void missingEvidenceAndForgedConfigFlagCannotGrantImages()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig config=config(rig,id,true).withAccountImageInput(true);
            assertFalse(ModelGateway.accountSupportsImageInput(config.accountModel));
            try{ModelGateway.complete(config,messages(),null,new RunControl());fail();}catch(ModelGateway.GatewayException denied){assertTrue(denied.getMessage().contains("图片"));}
            assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void evidencePersistsButCannotCrossAccountModelOrCredentialGeneration()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,true);pin(rig,id);long version=rig.book.row(id).getLong("version");
            AccountBook restoredBook=new AccountBook(rig.file,rig.vault,"host-owner");
            try(AccountGateway restored=new AccountGateway(restoredBook,rig.fake.provider())){
                assertTrue(restored.supportsImageInput(id,version,"fixture-model"));assertFalse(restored.supportsImageInput(id,version,"vision-model"));assertFalse(restored.supportsImageInput(id,version+1,"fixture-model"));
            }
            String other=rig.connect();assertFalse(rig.gateway.supportsImageInput(other,rig.book.row(other).getLong("version"),"fixture-model"));
            rig.gateway.refresh(id,new GatewayCall(5000));assertFalse(rig.gateway.supportsImageInput(id,rig.book.row(id).getLong("version"),"fixture-model"));
        }
    }
    @Test public void mutableUiCatalogCannotSupplyOrChangeThePinnedFact()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,false);long version=rig.book.row(id).getLong("version");
            JSONObject external=rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));external.getJSONArray("models").getJSONObject(0).getJSONObject("imageInput").put("value",true);
            rig.gateway.selectModelReviewed(id,version,"fixture-model");assertFalse(rig.gateway.supportsImageInput(id,version,"fixture-model"));
            try{rig.gateway.selectModelReviewed(id,version,"made-up-model");fail();}catch(GatewayFailure expected){assertEquals("STALE_MODEL_CATALOG",expected.code);}
        }
    }
    @Test public void aRefreshedCatalogCanRevokeImageEvidenceAndFenceTheAlreadyDispatchedReply()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,true);pin(rig,id);bind(rig);ModelConfig config=config(rig,id,true);
            rig.fake.stream=answer();rig.fake.allowResponse=new CountDownLatch(1);var worker=Executors.newSingleThreadExecutor();
            try{var pending=worker.submit(()->ModelGateway.complete(config,messages(),null,new RunControl()));assertTrue(rig.fake.responseEntered.await(2,TimeUnit.SECONDS));
                catalog(rig,false);rig.gateway.modelsReviewed(id,config.accountModel.accountVersion,new GatewayCall(5000));assertFalse(ModelGateway.accountSupportsImageInput(config.accountModel));
                rig.fake.allowResponse.countDown();try{pending.get(2,TimeUnit.SECONDS);fail("Revoked image route returned a decision");}catch(java.util.concurrent.ExecutionException denied){assertTrue(denied.getCause() instanceof ModelGateway.GatewayException);}
                assertEquals(1,rig.fake.responses.get());assertEquals(2,rig.fake.models.get());
            }finally{rig.fake.allowResponse.countDown();worker.shutdownNow();}
        }
    }
    private void reachRenewal(AccountGatewayTest.Rig rig,String id,long version)throws Exception {
        long until=rig.book.credentials(id,version).expiresAt-59_950;while(System.currentTimeMillis()<until)Thread.sleep(Math.max(1,Math.min(25,until-System.currentTimeMillis())));
    }
    @Test public void imageRenewalRechecksCatalogWithTheNewTokenBeforeTheOnlyModelPost()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=61;String id=rig.connect();catalog(rig,true);pin(rig,id);bind(rig);ModelConfig config=config(rig,id,true);
            reachRenewal(rig,id,config.accountModel.accountVersion);rig.fake.expiresIn=3600;rig.fake.stream=answer();
            JSONObject result=ModelGateway.complete(config,messages(),null,new RunControl());
            assertEquals(1,rig.fake.refreshes.get());assertEquals(2,rig.fake.models.get());assertEquals(1,rig.fake.responses.get());assertEquals("Bearer fixture-access-secret-1",rig.fake.lastAuthorization);
            long actual=result.getJSONObject("provider_data").getJSONObject("nativeRef").getLong("accountVersion");assertTrue(actual>config.accountModel.accountVersion);assertTrue(rig.gateway.supportsImageInput(id,actual,"fixture-model"));
        }
    }
    @Test public void newlyUnknownRenewedCatalogStopsBeforeInferenceOrVersionDispatch()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=61;String id=rig.connect();catalog(rig,true);pin(rig,id);bind(rig);ModelConfig config=config(rig,id,true);
            reachRenewal(rig,id,config.accountModel.accountVersion);rig.fake.expiresIn=3600;rig.fake.modelsBody="{\"models\":[{\"slug\":\"fixture-model\",\"display_name\":\"Neutral\",\"visibility\":\"list\"}]}";
            try{ModelGateway.complete(config,messages(),null,new RunControl());fail();}catch(ModelGateway.GatewayException denied){assertTrue(denied.getMessage().contains("图片"));}
            assertEquals(1,rig.fake.refreshes.get());assertEquals(2,rig.fake.models.get());assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void cancellationDuringRenewedCatalogCannotSendTheImage()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=61;String id=rig.connect();catalog(rig,true);pin(rig,id);bind(rig);ModelConfig config=config(rig,id,true);reachRenewal(rig,id,config.accountModel.accountVersion);
            rig.fake.expiresIn=3600;rig.fake.modelsEntered=new CountDownLatch(1);rig.fake.allowModels=new CountDownLatch(1);RunControl control=new RunControl();var worker=Executors.newSingleThreadExecutor();
            try{var pending=worker.submit(()->ModelGateway.complete(config,messages(),null,control));assertTrue(rig.fake.modelsEntered.await(2,TimeUnit.SECONDS));control.cancel();
                try{pending.get(2,TimeUnit.SECONDS);fail();}catch(java.util.concurrent.ExecutionException stopped){assertTrue(stopped.getCause() instanceof InterruptedException);}assertEquals(0,rig.fake.responses.get());
            }finally{rig.fake.allowModels.countDown();worker.shutdownNow();}
        }
    }
    @Test public void onlyBoundedInlineImageDataIsAcceptedAndTextBudgetDoesNotGrow()throws Exception {
        for(String url:new String[]{"https://example.invalid/private.png","data:image/png;base64,AAAA","data:image/gif;base64,R0lGODlh","data:image/jpeg;base64,"+PNG.substring(PNG.indexOf(',')+1)}){
            JSONArray input=new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_image").put("image_url",url))));
            try{new ResponsesRequest("fixture-model","",input,new JSONArray(),true);fail(url);}catch(GatewayFailure expected){}
        }
        JSONArray images=new JSONArray();for(int i=0;i<3;i++)images.put(new JSONObject().put("type","input_image").put("image_url",PNG));
        try{new ResponsesRequest("fixture-model","",new JSONArray().put(new JSONObject().put("role","user").put("content",images)),new JSONArray(),true);fail();}catch(GatewayFailure expected){}
        JSONArray input=new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","input_text").put("text","a".repeat(128*1024)))
            .put(new JSONObject().put("type","input_text").put("text","b".repeat(128*1024))).put(new JSONObject().put("type","input_image").put("image_url",PNG))));
        try{new ResponsesRequest("fixture-model","",input,new JSONArray(),true);fail("Image permission increased text budget");}catch(GatewayFailure expected){}
    }
}
