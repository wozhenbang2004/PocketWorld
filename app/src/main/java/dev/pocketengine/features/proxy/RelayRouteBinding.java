package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import java.util.Objects;

/** Immutable native service selection, never an alias supplied by an HTTP client. */
final class RelayRouteBinding {
    private final ModelConfig selected;
    RelayRouteBinding(ModelConfig selected){this.selected=Objects.requireNonNull(selected);}
    boolean matches(ModelConfig current){
        if(current==null||selected.usesAccount()!=current.usesAccount()||!samePlan(selected,current))return false;
        if(selected.usesAccount()){
            // Renewal/authorization versions remain fenced by the existing native account port.
            return selected.accountModel.sameModel(current.accountModel);
        }
        return selected.provider.equals(current.provider)&&selected.baseUrl.equals(current.baseUrl)&&selected.model.equals(current.model)
                &&selected.sameCredentials(current)
                &&Objects.equals(selected.capabilityBinding,current.capabilityBinding)&&selected.catalogEnforced==current.catalogEnforced
                &&selected.reasoning.equals(current.reasoning)&&selected.maxOutputTokens==current.maxOutputTokens
                &&selected.imageTokenReserve==current.imageTokenReserve&&selected.supportsImageInput==current.supportsImageInput
                &&selected.requestTimeoutSeconds==current.requestTimeoutSeconds&&selected.cleartextConsent.equals(current.cleartextConsent);
    }
    private static boolean samePlan(ModelConfig left,ModelConfig right){
        var a=left.routePlan;var b=right.routePlan;if(a==null||b==null)return a==b;
        if(a.groupRevision!=b.groupRevision||!a.requestedAlias.equals(b.requestedAlias)||!a.purpose.equals(b.purpose)||!a.audience.equals(b.audience)||a.maxAttempts!=b.maxAttempts||a.maxRouteSwitches!=b.maxRouteSwitches||a.candidates.size()!=b.candidates.size())return false;
        try{for(int i=0;i<a.candidates.size();i++){var x=a.candidates.get(i);var y=b.candidates.get(i);
            if(!x.candidateId().equals(y.candidateId())||!x.exclusion().equals(y.exclusion()))return false;
            if(x.config()==null||y.config()==null){if(x.config()!=y.config())return false;continue;}
            if(!Objects.equals(x.config().capabilityBinding,y.config().capabilityBinding)||!dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(x.config()).equals(dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(y.config())))return false;
        }return true;}catch(Exception unavailable){return false;}
    }
    @Override public String toString(){return "RelayRouteBinding[native selection]";}
}
