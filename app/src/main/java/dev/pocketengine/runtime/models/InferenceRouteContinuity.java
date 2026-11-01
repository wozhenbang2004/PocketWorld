package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Native captured-request continuity, not model selection, credential access or a new inference grant. */
public final class InferenceRouteContinuity {
    private InferenceRouteContinuity(){}
    public static void requireCurrent(ModelConfig capturedInference,ModelConfig selectedSettings,RunControl original)throws Exception {
        Objects.requireNonNull(capturedInference);Objects.requireNonNull(selectedSettings);Objects.requireNonNull(original);original.check();
        boolean same=capturedInference.usesAccount()==selectedSettings.usesAccount()
                &&capturedInference.provider.equals(selectedSettings.provider)&&capturedInference.model.equals(selectedSettings.model)
                &&capturedInference.baseUrl.equals(selectedSettings.baseUrl)
                &&MessageDigest.isEqual(capturedInference.apiKey.getBytes(StandardCharsets.UTF_8),selectedSettings.apiKey.getBytes(StandardCharsets.UTF_8))
                &&capturedInference.cleartextConsent.equals(selectedSettings.cleartextConsent)
                &&Objects.equals(capturedInference.capabilityBinding,selectedSettings.capabilityBinding)
                &&capturedInference.catalogEnforced==selectedSettings.catalogEnforced
                &&capturedInference.reasoning.equals(selectedSettings.reasoning)
                &&capturedInference.maxOutputTokens==selectedSettings.maxOutputTokens
                &&capturedInference.requestTimeoutSeconds==selectedSettings.requestTimeoutSeconds
                &&capturedInference.sendScreenshots==selectedSettings.sendScreenshots
                &&capturedInference.supportsImageInput==selectedSettings.supportsImageInput
                &&capturedInference.imageTokenReserve==selectedSettings.imageTokenReserve;
        // maxSteps is the original task's allocation, not a different connection. It remains
        // enforced by TaskStore/Runner and is deliberately not compared with a new default.
        if(same&&capturedInference.usesAccount())same=ModelGateway.accountSelectionContinues(original,capturedInference.accountModel,selectedSettings.accountModel);
        if(!same)throw new SecurityException("原模型路由、受准入的账号版本或当前授权已变化或暂不可核对；未换号、重放或使用旧来源。");
        original.check();
    }
}
