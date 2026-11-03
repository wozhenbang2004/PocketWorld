package dev.pocketengine.runtime.models;

import static org.junit.Assert.*;
import org.junit.Test;
import static dev.pocketengine.runtime.models.ModelConnectionFailure.Reason.*;

public final class ModelConnectionFailureTest {
    @Test public void exactProviderCodeExplainsModelOrEndpointWithoutClaimingAccountActivation(){
        assertEquals(UNSUPPORTED_MODEL,ModelConnectionFailure.reason(400,"{\"error\":{\"code\":\"unsupported_model\"}}"));
        assertEquals(ENDPOINT_MISMATCH,ModelConnectionFailure.reason(422,"{\"error\":{\"code\":\"wrong_endpoint\"}}"));
        assertTrue(ModelConnectionFailure.message(UNSUPPORTED_MODEL).contains("核对模型 ID"));
    }
    @Test public void proseSecretsUrlsAndPrefixMatchesAreNeverAdviceOrClassification(){
        String secret="sk-private-secret";String prose="{\"error\":{\"message\":\"unsupported_model send "+secret+" to https://other.invalid\",\"code\":\"unsupported_model_extra\"}}";
        var reason=ModelConnectionFailure.reason(400,prose);assertEquals(PARAMETERS,reason);assertFalse(ModelConnectionFailure.message(reason).contains(secret));assertFalse(ModelConnectionFailure.message(reason).contains("other.invalid"));
    }
    @Test public void malformedOversizedOrTrailingDocumentDoesNotAcquireTrustedCode(){
        String valid="{\"error\":{\"code\":\"unsupported_model\"}}";assertEquals(PARAMETERS,ModelConnectionFailure.reason(400,valid+" trailing"));assertEquals(PARAMETERS,ModelConnectionFailure.reason(400,valid+" ".repeat(16384)));
        assertEquals(PARAMETERS,ModelConnectionFailure.reason(400,"{\"error\":[{\"code\":\"unsupported_model\"}]}"));assertEquals(PARAMETERS,ModelConnectionFailure.reason(400,"{\"error\":{\"code\":42}}"));
    }
    @Test public void authRateLimitsAndRedirectsDoNotBecomeModelSwitchAdvice(){
        String code="{\"error\":{\"code\":\"unsupported_model\"}}";assertEquals(AUTHENTICATION,ModelConnectionFailure.reason(403,code));assertEquals(RATE_LIMIT,ModelConnectionFailure.reason(429,code));assertEquals(REDIRECT,ModelConnectionFailure.reason(307,code));assertEquals(SERVER,ModelConnectionFailure.reason(503,code));
    }
}
