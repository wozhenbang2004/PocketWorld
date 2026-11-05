package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import java.util.Objects;

/** Native captured-request continuity, not model selection, credential access or a new inference grant. */
public final class InferenceRouteContinuity {
    private InferenceRouteContinuity(){}
    public static void requireCurrent(ModelConfig capturedInference,ModelConfig selectedSettings,RunControl original)throws Exception {
        Objects.requireNonNull(capturedInference);Objects.requireNonNull(selectedSettings);Objects.requireNonNull(original);original.check();
        requireCurrentFromAuthorityCheck(capturedInference,selectedSettings,original);
        original.check();
    }
    /**
     * Route continuity inside the original controller's authority callback. The enclosing
     * RunControl.check retains execution admission and all source checks; re-entering it
     * here would recursively invoke this same callback. Ordinary callers use requireCurrent.
     */
    public static void requireCurrentFromAuthorityCheck(ModelConfig capturedInference,ModelConfig selectedSettings,RunControl original)throws Exception {
        Objects.requireNonNull(capturedInference);Objects.requireNonNull(selectedSettings);Objects.requireNonNull(original);requireNotStopped(original);
        boolean same=capturedInference.usesAccount()==selectedSettings.usesAccount()
                &&capturedInference.provider.equals(selectedSettings.provider)&&capturedInference.model.equals(selectedSettings.model)
                &&capturedInference.baseUrl.equals(selectedSettings.baseUrl)
                &&capturedInference.sameCredentials(selectedSettings)
                &&capturedInference.cleartextConsent.equals(selectedSettings.cleartextConsent)
                &&Objects.equals(capturedInference.capabilityBinding,selectedSettings.capabilityBinding)
                &&capturedInference.catalogEnforced==selectedSettings.catalogEnforced
                &&(capturedInference.compatibility==null?selectedSettings.compatibility==null:capturedInference.compatibility.matches(selectedSettings.compatibility))
                &&capturedInference.reasoning.equals(selectedSettings.reasoning)
                &&capturedInference.maxOutputTokens==selectedSettings.maxOutputTokens
                &&capturedInference.requestTimeoutSeconds==selectedSettings.requestTimeoutSeconds
                &&capturedInference.sendScreenshots==selectedSettings.sendScreenshots
                // Account image support is refreshed provider evidence, not an owner choice.
                // Owner screenshot consent/reserves still match; dispatch checks fresh facts.
                &&(capturedInference.usesAccount()||capturedInference.supportsImageInput==selectedSettings.supportsImageInput)
                &&capturedInference.imageTokenReserve==selectedSettings.imageTokenReserve;
        // maxSteps is the original task's allocation, not a different connection. It remains
        // enforced by TaskStore/Runner and is deliberately not compared with a new default.
        if(same&&capturedInference.usesAccount()){
            // Current settings may now choose another task's model. Only this run's actually
            // admitted account renewals can advance its frozen identity; the native port still
            // checks disable/logout/version revocation before any further request.
            same=capturedInference.accountModel.sameModel(selectedSettings.accountModel);
            if(same){ModelConfig renewed=ModelGateway.accountBudgetConfig(capturedInference,original);
                same=ModelGateway.accountSelectionContinues(original,capturedInference.accountModel,renewed.accountModel);}
        }
        if(!same)throw new SecurityException("原模型路由、受准入的账号版本或当前授权已变化或暂不可核对；未换号、重放或使用旧来源。");
        requireNotStopped(original);
    }
    private static void requireNotStopped(RunControl original)throws InterruptedException {
        if(original.isCancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException("Run stopped");
    }
}
