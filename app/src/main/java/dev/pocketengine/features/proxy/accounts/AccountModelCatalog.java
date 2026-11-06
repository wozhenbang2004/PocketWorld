package dev.pocketengine.features.proxy.accounts;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native model observations and pinned evidence, scoped to an exact credential version.
 * All instance methods run under the owning AccountGateway monitor. This class owns no
 * authorization, credentials, network work, retries or locks; a catalog never grants dispatch.
 */
final class AccountModelCatalog {
    private final AccountBook book;
    private final Map<String,Observation> observations=new HashMap<>();
    private record Observation(long version,long observedAt,JSONArray models){}

    AccountModelCatalog(AccountBook book){this.book=book;}

    void observed(String id,long version,JSONArray models,long now)throws GatewayFailure {
        JSONArray snapshot=copyArray(models);
        // A failed durable evidence refresh must not publish a usable observation.
        // Selection and inference preparation both consume this cache under the same
        // gateway monitor; publish only after the matching pinned evidence is saved.
        // Copy first so malformed input cannot change durable evidence either.
        refreshPinnedEvidence(id,version,snapshot);
        observations.put(id,new Observation(version,now,snapshot));
    }

    void pinReviewed(String id,long version,String model)throws GatewayFailure {
        Observation observed=observations.get(id);
        if(observed==null||observed.version!=version)throw new GatewayFailure("STALE_MODEL_CATALOG");
        pin(id,version,model,observed.models);
    }

    boolean pinFresh(String id,long version,String model,long now)throws GatewayFailure {
        Observation observed=observations.get(id);
        if(observed==null||observed.version!=version||observed.observedAt<=0||observed.observedAt>now||
                now-observed.observedAt>dev.pocketengine.runtime.models.ModelConnectionService.MAX_CATALOG_AGE_MILLIS)return false;
        pin(id,version,model,observed.models);return true;
    }
    /**
     * Interprets only native capability evidence from the caller's already-reviewed row.
     * The gateway retains account usability and plan-permission admission under its monitor.
     * A missing/stale per-model fact cannot borrow another model's image permission.
     */
    boolean supportsImages(String accountId,long version,String modelId,JSONObject row)throws GatewayFailure {
        JSONObject models=AccountBook.modelEvidence(row,version);
        if(models!=null){JSONObject entry=models.optJSONObject(modelId);if(entry==null||entry.optBoolean("unavailable"))return false;
            book.modelFacts(accountId,version,modelId);return dev.pocketengine.features.proxy.providers.AccountImageCapability.supported(entry.optJSONObject("imageEvidence"));}
        JSONObject fact=row.optJSONObject("imageEvidence");return fact!=null&&fact.optLong("accountVersion",-1)==version&&modelId.equals(fact.optString("modelId"))&&
            dev.pocketengine.features.proxy.providers.AccountImageCapability.supported(fact);
    }

    private void refreshPinnedEvidence(String id,long version,JSONArray models)throws GatewayFailure {
        JSONObject row=book.row(id),prior=AccountBook.modelEvidence(row,version);
        if(prior==null){JSONObject image=row.optJSONObject("imageEvidence");if(image==null||image.optLong("accountVersion",-1)!=version)return;prior=AccountBook.json(image.optString("modelId"),new JSONObject());}
        JSONObject refreshed=new JSONObject();
        for(java.util.Iterator<String> names=prior.keys();names.hasNext();){String model=names.next();JSONObject entry=catalogEntry(model,models);
            try{refreshed.put(model,entry==null?AccountBook.json("unavailable",true):entry);}catch(org.json.JSONException invalid){throw new GatewayFailure("INVALID_MODEL_CATALOG");}}
        saveCatalogEvidence(id,version,refreshed);
    }
    void pin(String id,long version,String model,JSONArray models)throws GatewayFailure {
        JSONObject entry=catalogEntry(model,models);if(entry==null)throw new GatewayFailure("STALE_MODEL_CATALOG");
        JSONObject pinned=AccountBook.modelEvidence(book.row(id),version);if(pinned==null)pinned=new JSONObject();
        try{pinned.put(model,entry);}catch(org.json.JSONException invalid){throw new GatewayFailure("INVALID_MODEL_CATALOG");}
        saveCatalogEvidence(id,version,pinned);
    }
    private void saveCatalogEvidence(String id,long version,JSONObject models)throws GatewayFailure {
        // The default selection is not the identity of other tasks using this account/version.
        book.metadata(id,version,AccountBook.json("imageEvidence",new JSONObject(),"modelEvidence",AccountBook.json("accountVersion",version,"models",models)));
    }
    private static JSONObject catalogEntry(String model,JSONArray models)throws GatewayFailure {
        JSONObject found=null;
        if(models!=null)for(int i=0;i<models.length();i++){JSONObject item=models.optJSONObject(i);if(item!=null&&model.equals(item.optString("id"))){if(found!=null)throw new GatewayFailure("INVALID_MODEL_CATALOG");found=item;}}
        if(found==null)return null;JSONObject fact=found.optJSONObject("imageInput");
        if(fact==null)throw new GatewayFailure("INVALID_MODEL_CATALOG");
        JSONObject modelEvidence=AccountBook.json("catalogFacts",found.optJSONObject("catalogFacts"),"imageEvidence",fact);
        try{dev.pocketengine.runtime.models.AccountCatalogFacts.fromJson(modelEvidence.getJSONObject("catalogFacts"));}
        catch(Exception invalid){throw new GatewayFailure("INVALID_MODEL_CATALOG");}
        return modelEvidence;
    }
    static JSONArray copyArray(JSONArray array)throws GatewayFailure {try{return new JSONArray(array.toString());}catch(Exception invalid){throw new GatewayFailure("INVALID_MODEL_CATALOG");}}
}
