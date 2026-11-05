package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.FakeChatGpt;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.runtime.usage.InferenceFailure;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native account error classification via a localhost provider; no paid requests. */
public final class AccountContextLimitRecoveryTest {
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    @Test public void exactHttpContextCodesReachTheExistingCompactionGateWithoutRawRetry()throws Exception {
        for(String code:new String[]{"context_length_exceeded","context_window_exceeded","prompt_too_long"})for(int status:new int[]{400,413}){
            ModelGateway.GatewayException failure=failure(status,code,false);
            assertTrue(SafeModelRecovery.contextLimit(failure));assertEquals(InferenceFailure.Kind.CONTEXT_LIMIT,failure.observation().kind);
            assertEquals(status,failure.observation().httpStatus);
            assertEquals(-1,new SafeModelRecovery().delay(failure,0,System.currentTimeMillis()));
            assertEquals(InferenceFailure.Kind.CONTEXT_LIMIT,InferenceFailure.checked(failure.observation().toJson()).kind);
            assertFalse(failure.getMessage().contains("未发送"));assertFalse(failure.getMessage().contains("private-provider-prose"));
        }
    }
    @Test public void exactStreamingContextErrorUsesTheSameGate()throws Exception {
        for(String code:new String[]{"context_length_exceeded","context_window_exceeded","prompt_too_long"}){
            ModelGateway.GatewayException failure=failure(200,code,true);
            assertTrue(SafeModelRecovery.contextLimit(failure));assertEquals(0,failure.observation().httpStatus);
            assertEquals(-1,new SafeModelRecovery().delay(failure,0,System.currentTimeMillis()));
        }
    }
    @Test public void proseAndUnknownErrorsCannotAuthorizeCompaction()throws Exception {
        for(String code:new String[]{"unknown","invalid_request_error","context_length_exceeded_extra"})
            assertFalse(SafeModelRecovery.contextLimit(failure(400,code,false)));
        for(int status:new int[]{401,403,429,500})
            assertFalse(SafeModelRecovery.contextLimit(failure(status,"context_length_exceeded",false)));
        JSONObject body=new JSONObject().put("error",new JSONObject().put("code","context_length_exceeded"));
        for(String context:new String[]{"exchange","refresh"})assertEquals("PROVIDER_GRANT_REJECTED",
                dev.pocketengine.features.proxy.accounts.ChatGptRecovery.failure(400,body,"json_error","",context,0).code);
    }
    private ModelGateway.GatewayException failure(int status,String code,boolean stream)throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            JSONObject error=new JSONObject().put("code",code).put("message","private-provider-prose context_length_exceeded").put("param","input");
            fake.responseStatus=status;fake.responseErrorBody=new JSONObject().put("error",error).toString();
            if(stream)fake.stream=new ResponsesFixtures().add(new JSONObject().put("type","response.failed").put("response",
                    new JSONObject().put("id","resp_fixture").put("status","failed").put("error",error))).toString();
            Credentials credentials=new Credentials("fixture-access","fixture-refresh","fixture-id",ChatGptProvider.SCOPES,System.currentTimeMillis()+3600000);
            ModelGateway.installAccountInferencePort((ref,request,operation,sink)->provider.streamResponses(credentials,request,operation,sink));
            ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan")
                    .put("accountId","00000000-0000-0000-0000-000000000001").put("accountVersion",1).put("modelId","fixture-model"));
            ModelConfig config=new ModelConfig("","","",false,3,10).withAccountModel(ref);
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
            ModelGateway.GatewayException failure=assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.complete(config,messages,null,new RunControl()));
            assertEquals(1,fake.responses.get());return failure;
        }
    }
}
