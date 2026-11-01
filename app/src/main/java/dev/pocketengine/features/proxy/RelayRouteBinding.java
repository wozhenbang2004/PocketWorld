package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Immutable native service selection, never an alias supplied by an HTTP client. */
final class RelayRouteBinding {
    private final ModelConfig selected;
    RelayRouteBinding(ModelConfig selected){this.selected=Objects.requireNonNull(selected);}
    boolean matches(ModelConfig current){
        if(current==null||selected.usesAccount()!=current.usesAccount())return false;
        if(selected.usesAccount()){
            // Renewal/authorization versions remain fenced by the existing native account port.
            return selected.accountModel.sameModel(current.accountModel);
        }
        return selected.provider.equals(current.provider)&&selected.baseUrl.equals(current.baseUrl)&&selected.model.equals(current.model)
                &&MessageDigest.isEqual(selected.apiKey.getBytes(StandardCharsets.UTF_8),current.apiKey.getBytes(StandardCharsets.UTF_8))
                &&Objects.equals(selected.capabilityBinding,current.capabilityBinding)&&selected.catalogEnforced==current.catalogEnforced
                &&selected.reasoning.equals(current.reasoning)&&selected.maxOutputTokens==current.maxOutputTokens
                &&selected.imageTokenReserve==current.imageTokenReserve&&selected.supportsImageInput==current.supportsImageInput
                &&selected.requestTimeoutSeconds==current.requestTimeoutSeconds&&selected.cleartextConsent.equals(current.cleartextConsent);
    }
    @Override public String toString(){return "RelayRouteBinding[native selection]";}
}
