package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phone-local account/provider core. Host-owned singleton; all I/O methods run off the UI thread. */
public final class AccountGateway implements AutoCloseable {
    private final AccountBook book;
    private final ChatGptProvider provider;
    private final Semaphore networkSlots=new Semaphore(2);
    private final Map<String,Flight> refreshes=new HashMap<>();
    private final Map<String,JSONObject> catalogs=new HashMap<>();
    private final Map<String,Set<GatewayCall>> operations=new HashMap<>();
    private LoopbackAuthorization login;
    private volatile boolean closed;
    /** Lock-free last-commit projection; no credential getter or inference/refresh admission. */
    public boolean inferenceIdentityCurrent(String accountId,long accountVersion){return !closed&&book.inferenceIdentityCurrent(accountId,accountVersion);}
    /** Lock-free capability projection from this same native metadata commit, never an inference grant. */
    public dev.pocketengine.runtime.models.AccountCatalogFacts modelFacts(String id,long version,String model)throws GatewayFailure {
        if(closed)throw new GatewayFailure("GATEWAY_CLOSED");return book.modelFacts(id,version,model);
    }
    public AccountGateway(File metadata,CredentialVault vault,String hostOwnerScope)throws GatewayFailure {
        this(new AccountBook(metadata,vault,hostOwnerScope),new ChatGptProvider());
    }
    // Internal fixture seam. Production callers cannot select provider endpoints or inject host identity in a request.
    AccountGateway(AccountBook book,ChatGptProvider provider){this.book=book;this.provider=provider;}
    public synchronized JSONObject snapshot()throws GatewayFailure {
        JSONObject snapshot=book.snapshot();
        try{return snapshot.put("provider",ChatGptProvider.ID).put("authorizationMethod","system_browser_pkce_loopback")
            .put("inferenceProtocol","responses_sse").put("structuredInferenceProtocol","responses_text_image_function_sse").put("phoneExecution",false).put("maxConcurrentNetworkOperations",2)
            .put("maxRefreshWaitersPerAccount",8).put("requestTimeoutMaximumMs",120_000).put("closed",closed);}
        catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    public JSONObject beginLogin(String label,String accountId)throws GatewayFailure {return beginLogin(label,accountId,15*60_000);}
    /** Native owner action bound to the account version displayed by the host. Empty id creates a new account. */
    public synchronized JSONObject beginLoginReviewed(String label,String accountId,long expectedVersion)throws GatewayFailure {
        open();
        if(accountId==null||accountId.isEmpty()){
            if(expectedVersion!=0)throw new GatewayFailure("STALE_ACCOUNT_VERSION");
        }else reviewedRow(accountId,expectedVersion);
        return beginLogin(label,accountId);
    }
    synchronized JSONObject beginLogin(String label,String accountId,long ttlMillis)throws GatewayFailure {
        open();if(login!=null&&("pending".equals(login.status)||"exchanging".equals(login.status)))throw new GatewayFailure("LOGIN_ALREADY_PENDING");
        if(login!=null){login.close();login=null;}
        JSONObject row=accountId==null||accountId.isEmpty()?book.create(label):book.row(accountId);String id=row.optString("id");
        if(!row.optBoolean("enabled"))throw new GatewayFailure("ACCOUNT_DISABLED");
        if(refreshes.containsKey(id))throw new GatewayFailure("ACCOUNT_BUSY");
        LoopbackAuthorization next=new LoopbackAuthorization(id,row.optLong("version"),ttlMillis,new LoopbackAuthorization.Handler(){
            public void complete(LoopbackAuthorization session,Map<String,String> callback)throws GatewayFailure {completeLogin(session,callback);}
            public void expired(LoopbackAuthorization session){expireLogin(session);}
        });
        try{
            row=book.update(id,row.optLong("version"),AccountBook.json("status","authorizing","error","","cooldownUntil",0));next.accountVersion=row.optLong("version");
            String url=provider.authorizationUrl(book.hostId(),row.optString("clientId"),next.callback,next.state,next.nonce,next.challenge());
            login=next;book.login(loginView(next));next.start();
            return AccountBook.json("sessionId",next.id,"accountId",id,"status","pending","expiresAt",next.expiresAt,"authorizationUrl",url);
        }catch(GatewayFailure error){next.status="failed";next.error=error.code;next.close();if(login==next)login=null;try{book.finishAuthorization(id,next.accountVersion,loginView(next),error.code);}catch(GatewayFailure ignored){}throw error;}
    }
    private void completeLogin(LoopbackAuthorization session,Map<String,String> callback)throws GatewayFailure {
        boolean admitted=false;
        try{
            String client;
            synchronized(this){
                open();if(login!=session||!"pending".equals(session.status))throw new GatewayFailure("STALE_LOGIN_SESSION");
                JSONObject row=book.row(session.accountId);checkFence(row,session.accountVersion);enabled(row);
                if(callback.containsKey("error"))throw new GatewayFailure("access_denied".equals(callback.get("error"))?"AUTHORIZATION_DECLINED":
                    java.util.Set.of("temporarily_unavailable","server_error").contains(callback.get("error"))?"PROVIDER_UNAVAILABLE":"INVALID_AUTHORIZATION_CALLBACK");
                String code=callback.getOrDefault("code","");if(code.isEmpty()||code.length()>4096)throw new GatewayFailure("INVALID_AUTHORIZATION_CALLBACK");
                String saved=row.optString("clientId"),issued=callback.getOrDefault("client_id",saved);
                if(!issued.matches("oaiapp_[A-Za-z0-9_-]{1,200}")||(!saved.isEmpty()&&!saved.equals(issued)))throw new GatewayFailure("REGISTRATION_MISMATCH");
                // Retain the registration before exchange; a consumed/expired code is never tried twice.
                if(saved.isEmpty()){row=book.update(session.accountId,session.accountVersion,AccountBook.json("clientId",issued));session.accountVersion=row.optLong("version");}
                client=issued;session.status="exchanging";book.login(loginView(session));
                if(!networkSlots.tryAcquire())throw new GatewayFailure("GATEWAY_BUSY");admitted=true;
                session.operation=new GatewayCall(60_000);track(session.accountId,session.operation);
            }
            ChatGptProvider.Grant grant=provider.exchange(client,callback.get("code"),session.verifier,session.callback,session.nonce,session.operation,System.currentTimeMillis());
            synchronized(this){
                open();session.operation.check();if(login!=session||!"exchanging".equals(session.status))throw new GatewayFailure("STALE_LOGIN_SESSION");
                JSONObject completed=loginView(session);try{completed.put("status","complete");}catch(Exception invalid){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
                book.publish(session.accountId,session.accountVersion,grant.credentials,grant.issuer,grant.subject,grant.email,completed);
                session.status="complete";
            }
        }catch(GatewayFailure error){
            synchronized(this){
                if(login==session&&("pending".equals(session.status)||"exchanging".equals(session.status))){
                    session.status="failed";session.error=error.code;
                    try{book.finishAuthorization(session.accountId,session.accountVersion,loginView(session),error.code);}catch(GatewayFailure storage){session.error=storage.code;}
                }
            }
            throw error;
        }finally{
            synchronized(this){if(session.operation!=null)untrack(session.accountId,session.operation);}
            if(admitted)networkSlots.release();
        }
    }
    public synchronized JSONObject cancelLogin(String sessionId)throws GatewayFailure {
        open();if(login==null||!login.id.equals(sessionId))throw new GatewayFailure("LOGIN_NOT_FOUND");
        if("pending".equals(login.status)||"exchanging".equals(login.status))endLogin(login,"cancelled","CANCELLED");
        return loginView(login);
    }
    private synchronized void expireLogin(LoopbackAuthorization session){if(login==session&&("pending".equals(session.status)||"exchanging".equals(session.status)))try{endLogin(session,"expired","LOGIN_EXPIRED");}catch(GatewayFailure ignored){}}
    private void endLogin(LoopbackAuthorization session,String status,String error)throws GatewayFailure {
        session.status=status;session.error=error;session.close();
        book.finishAuthorization(session.accountId,session.accountVersion,loginView(session),error);
    }
    private static JSONObject loginView(LoopbackAuthorization session)throws GatewayFailure {
        return AccountBook.json("sessionId",session.id,"accountId",session.accountId,"status",session.status,"expiresAt",session.expiresAt,"error",session.error);
    }
    public synchronized JSONObject acknowledgePlanWelcome(String accountId,long expectedVersion)throws GatewayFailure {
        open();reviewedRow(accountId,expectedVersion);book.acknowledgeWelcome(accountId,expectedVersion);return snapshot();
    }
    /** Owner asks to try again after inspecting usage. This is not evidence of restored quota. */
    public synchronized JSONObject retryPlanUsageReviewed(String accountId,long expectedVersion)throws GatewayFailure {
        open();JSONObject row=reviewedRow(accountId,expectedVersion);enabled(row);
        book.metadata(accountId,expectedVersion,AccountBook.json("usageLimited",false,"error","","diagnostics",new JSONObject()));return snapshot();
    }
    public synchronized JSONObject select(String accountId)throws GatewayFailure {open();book.select(accountId);return snapshot();}
    public synchronized JSONObject selectReviewed(String accountId,long expectedVersion)throws GatewayFailure {
        open();reviewedRow(accountId,expectedVersion);return select(accountId);
    }
    public synchronized JSONObject setEnabledReviewed(String accountId,long expectedVersion,boolean enabled)throws GatewayFailure {
        open();reviewedRow(accountId,expectedVersion);return setEnabled(accountId,enabled);
    }
    public synchronized JSONObject setEnabled(String accountId,boolean enabled)throws GatewayFailure {
        open();JSONObject row=book.row(accountId);
        if(row.optBoolean("enabled")==enabled)return snapshot();
        JSONObject changes=AccountBook.json("enabled",enabled);
        if(!enabled&&("refreshing".equals(row.optString("status"))||"authorizing".equals(row.optString("status"))))changes=AccountBook.json("enabled",false,"status","reauthorization_required","error","ACCOUNT_DISABLED");
        book.update(accountId,row.optLong("version"),changes);cancelOperations(accountId);
        if(login!=null&&login.accountId.equals(accountId)&&("pending".equals(login.status)||"exchanging".equals(login.status)))endLogin(login,"cancelled","ACCOUNT_DISABLED");
        return snapshot();
    }
    /** Stop locally first. Revocation is one bounded best-effort request; no remote setting is changed at login/refresh. */
    public JSONObject logout(String accountId,GatewayCall operation)throws GatewayFailure {return logout(accountId,operation,null);}
    public JSONObject logoutReviewed(String accountId,long expectedVersion,GatewayCall operation)throws GatewayFailure {
        return logout(accountId,operation,expectedVersion);
    }
    private JSONObject logout(String accountId,GatewayCall operation,Long expectedVersion)throws GatewayFailure {
        Credentials prior=null;String client;long version;
        synchronized(this){
            open();JSONObject row=expectedVersion==null?book.row(accountId):reviewedRow(accountId,expectedVersion);
            if(expectedVersion!=null)operation.check();client=row.optString("clientId");
            try{prior=book.credentials(accountId,row.optLong("version"));}catch(GatewayFailure unavailable){}
            JSONObject signedOut=book.signOut(accountId);version=signedOut.optLong("version");cancelOperations(accountId);
            if(login!=null&&login.accountId.equals(accountId)&&("pending".equals(login.status)||"exchanging".equals(login.status)))endLogin(login,"cancelled","SIGNED_OUT");
        }
        boolean confirmed=false;
        if(prior!=null&&networkSlots.tryAcquire())try{provider.revoke(client,prior,operation);confirmed=true;}catch(GatewayFailure ignored){}finally{networkSlots.release();}
        synchronized(this){
            JSONObject row=book.row(accountId);if(row.optLong("version")==version)version=book.update(accountId,version,AccountBook.json("remoteRevocationConfirmed",confirmed)).optLong("version");
            return AccountBook.json("accountId",accountId,"accountVersion",version,"signedOutLocally",true,"remoteRevocationConfirmed",confirmed,"credentialCleanupRequired",book.snapshot().optBoolean("credentialCleanupRequired"));
        }
    }
    public JSONObject refresh(String accountId,GatewayCall operation)throws GatewayFailure {validCredentials(accountId,operation,true);synchronized(this){return snapshot();}}
    public JSONObject refreshReviewed(String accountId,long expectedVersion,GatewayCall operation)throws GatewayFailure {
        Authorized tokens=validCredentials(accountId,operation,true,expectedVersion);
        synchronized(this){checkStreamFence(accountId,tokens.version,operation);return snapshot();}
    }
    /** Catalog identity is the actual request identity, never a later snapshot stamped onto an old response. */
    public JSONObject modelsReviewed(String accountId,long expectedVersion,GatewayCall operation)throws GatewayFailure {
        Authorized tokens=validCredentials(accountId,operation,false,expectedVersion);
        beginRequest(accountId,operation,tokens.version);
        try{
            JSONArray models=provider.models(tokens.tokens,operation);
            synchronized(this){checkStreamFence(accountId,tokens.version,operation);
                catalogs.put(accountId,AccountBook.json("accountId",accountId,"version",tokens.version,"models",models));refreshPinnedEvidence(accountId,tokens.version,models);return AccountBook.json("accountId",accountId,"version",tokens.version,"models",copyArray(models));}
        }catch(GatewayFailure failure){recordFailure(accountId,tokens.version,failure);throw failure;}finally{endRequest(accountId,operation);}
    }
    public JSONArray models(String accountId,GatewayCall operation)throws GatewayFailure {
        String id=resolve(accountId);Authorized tokens=validCredentials(id,operation,false);
        beginRequest(id,operation,tokens.version);
        try{JSONArray models=provider.models(tokens.tokens,operation);synchronized(this){checkStreamFence(id,tokens.version,operation);
            catalogs.put(id,AccountBook.json("accountId",id,"version",tokens.version,"models",copyArray(models)));refreshPinnedEvidence(id,tokens.version,models);return models;}}catch(GatewayFailure failure){recordFailure(id,tokens.version,failure);throw failure;}finally{endRequest(id,operation);}
    }
    /** Pin only facts from this gateway's completed same-token catalog; UI cannot supply capability booleans. */
    public synchronized JSONObject selectModelReviewed(String accountId,long version,String modelId)throws GatewayFailure {
        open();JSONObject row=reviewedRow(accountId,version);usable(row);JSONObject catalog=catalogs.get(accountId);
        if(catalog==null||catalog.optLong("version",-1)!=version)throw new GatewayFailure("STALE_MODEL_CATALOG");
        pinCatalogEvidence(accountId,version,modelId,catalog.optJSONArray("models"));book.select(accountId);return snapshot();
    }
    public synchronized boolean supportsImageInput(String accountId,long version,String modelId){
        try{open();JSONObject row=reviewedRow(accountId,version);usable(row);
            if(!row.optBoolean("planUsageEnabled"))return false;
            JSONObject fact=row.optJSONObject("imageEvidence");return fact!=null&&fact.optLong("accountVersion",-1)==version&&modelId.equals(fact.optString("modelId"))&&
                dev.pocketengine.features.proxy.providers.AccountImageCapability.supported(fact);
        }catch(GatewayFailure unavailable){return false;}
    }
    public synchronized boolean planUsageLimited(String accountId,long version){
        try{open();JSONObject row=reviewedRow(accountId,version);return row.optBoolean("enabled")&&row.optBoolean("planUsageEnabled")&&row.optBoolean("usageLimited");}
        catch(GatewayFailure unavailable){return false;}
    }
    private void refreshPinnedEvidence(String id,long version,JSONArray models)throws GatewayFailure {
        JSONObject fact=book.row(id).optJSONObject("modelEvidence");if(fact==null)fact=book.row(id).optJSONObject("imageEvidence");if(fact==null||fact.optLong("accountVersion",-1)!=version)return;
        try{pinCatalogEvidence(id,version,fact.optString("modelId"),models);}
        catch(GatewayFailure missing){if(!missing.code.equals("STALE_MODEL_CATALOG"))throw missing;book.metadata(id,version,AccountBook.json("imageEvidence",new JSONObject(),"modelEvidence",AccountBook.json("accountVersion",version,"modelId",fact.optString("modelId"),"unavailable",true)));}
    }
    private void pinCatalogEvidence(String id,long version,String model,JSONArray models)throws GatewayFailure {
        JSONObject found=null;
        if(models!=null)for(int i=0;i<models.length();i++){JSONObject item=models.optJSONObject(i);if(item!=null&&model.equals(item.optString("id"))){if(found!=null)throw new GatewayFailure("INVALID_MODEL_CATALOG");found=item;}}
        if(found==null)throw new GatewayFailure("STALE_MODEL_CATALOG");JSONObject fact=found.optJSONObject("imageInput");
        if(fact==null)throw new GatewayFailure("INVALID_MODEL_CATALOG");
        JSONObject modelEvidence=AccountBook.json("accountVersion",version,"modelId",model,"catalogFacts",found.optJSONObject("catalogFacts"));
        try{dev.pocketengine.runtime.models.AccountCatalogFacts.fromJson(modelEvidence.getJSONObject("catalogFacts"));}
        catch(Exception invalid){throw new GatewayFailure("INVALID_MODEL_CATALOG");}
        book.metadata(id,version,AccountBook.json("imageEvidence",AccountBook.json("accountVersion",version,"modelId",model,
            "known",fact.optBoolean("known"),"value",fact.opt("value")==null?JSONObject.NULL:fact.opt("value"),"source",fact.optString("source"),"observedAt",fact.optLong("observedAt")),"modelEvidence",modelEvidence));
    }
    private static JSONArray copyArray(JSONArray array)throws GatewayFailure {try{return new JSONArray(array.toString());}catch(Exception invalid){throw new GatewayFailure("INVALID_MODEL_CATALOG");}}
    private void requireImageEvidence(String id,long version,ResponsesRequest request)throws GatewayFailure {
        if(request.hasImages()&&!supportsImageInput(id,version,request.modelId()))throw new GatewayFailure("ACCOUNT_IMAGE_CAPABILITY_REQUIRED");
    }
    /** A renewed token needs its own observed model facts before POST/receipt dispatch, not just old image flags. */
    private void renewCatalogEvidence(String id,long originalVersion,Authorized actual,ResponsesRequest request,GatewayCall operation)throws GatewayFailure {
        boolean observed;
        synchronized(this){observed=book.row(id).optJSONObject("modelEvidence")!=null;
            if(actual.version==originalVersion){requireImageEvidence(id,actual.version,request);request.admit(modelFacts(id,actual.version,request.modelId()));return;}}
        if(!request.hasImages()&&!observed&&!request.hasExplicitReasoning())return;
        beginRequest(id,operation,actual.version);
        try{JSONArray models=provider.models(actual.tokens,operation);synchronized(this){checkStreamFence(id,actual.version,operation);
                pinCatalogEvidence(id,actual.version,request.modelId(),models);requireImageEvidence(id,actual.version,request);request.admit(modelFacts(id,actual.version,request.modelId()));}}
        finally{endRequest(id,operation);}
    }
    public void streamText(String accountId,String model,String instructions,JSONArray input,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {
        String id=resolve(accountId);Authorized tokens=validCredentials(id,operation,false);
        beginRequest(id,operation,tokens.version);
        try{new ResponsesRequest(model,instructions,input,new JSONArray()).admit(modelFacts(id,tokens.version,model));
            provider.stream(tokens.tokens,model,instructions,input,operation,event->{checkStreamFence(id,tokens.version,operation);sink.event(event);});}
        catch(GatewayFailure failure){recordFailure(id,tokens.version,failure);throw failure;}finally{endRequest(id,operation);}
    }
    /** Completed output describes a model turn, not a completed user task or a tool execution grant. */
    public void streamResponses(String accountId,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {
        if(request==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        String id=resolve(accountId);Authorized tokens=validCredentials(id,operation,false);
        beginRequest(id,operation,tokens.version);
        try{synchronized(this){requireImageEvidence(id,tokens.version,request);request.admit(modelFacts(id,tokens.version,request.modelId()));}provider.streamResponses(tokens.tokens,request,operation,fencedSink(id,tokens.version,request,operation,sink));}
        catch(GatewayFailure failure){recordFailure(id,tokens.version,failure);throw failure;}finally{endRequest(id,operation);}
    }
    /**
     * A reviewed native selection is exact, not an alias for the currently selected account.
     * Token renewal changes account version: require owner refresh/reselection instead of silently
     * following a new authorization generation or retrying a possibly charged model request.
     */
    public void streamResponsesReviewed(String accountId,long expectedVersion,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {
        if(request==null||operation==null||sink==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        Credentials tokens;
        synchronized(this){
            open();operation.check();JSONObject row=reviewedRow(accountId,expectedVersion);usable(row);
            if(refreshes.containsKey(accountId)||"refreshing".equals(row.optString("status")))throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
            tokens=book.credentials(accountId,expectedVersion);
            if(tokens.expiresAt<=System.currentTimeMillis()+60_000)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
            requireImageEvidence(accountId,expectedVersion,request);request.admit(modelFacts(accountId,expectedVersion,request.modelId()));
            beginRequest(accountId,operation,expectedVersion);
        }
        try{
            provider.streamResponses(tokens,request,operation,fencedSink(accountId,expectedVersion,request,operation,sink));
            synchronized(this){checkStreamFence(accountId,expectedVersion,operation);requireImageEvidence(accountId,expectedVersion,request);}
        }catch(GatewayFailure failure){recordFailure(accountId,expectedVersion,failure);throw failure;}
        finally{endRequest(accountId,operation);}
    }
    /** Receives only native authorization identity, never tokens or model-controlled fields. */
    public interface InferenceVersionSink {void authorized(long actualVersion)throws GatewayFailure;}
    /** Reviewed entry with one existing singleflight token renewal; never retries inference. */
    public void streamResponsesReviewed(String accountId,long expectedVersion,ResponsesRequest request,GatewayCall operation,
                                        InferenceVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure {
        if(request==null||operation==null||versions==null||sink==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        Authorized authorized=validCredentials(accountId,operation,false,expectedVersion,true);
        renewCatalogEvidence(accountId,expectedVersion,authorized,request,operation);
        synchronized(this){
            checkStreamFence(accountId,authorized.version,operation);
            if(authorized.tokens.expiresAt<=System.currentTimeMillis()+60_000)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
            requireImageEvidence(accountId,authorized.version,request);request.admit(modelFacts(accountId,authorized.version,request.modelId()));
            // Persist/update the trusted selection before any possibly charged model request.
            versions.authorized(authorized.version);
            beginRequest(accountId,operation,authorized.version);
        }
        try{
            provider.streamResponses(authorized.tokens,request,operation,fencedSink(accountId,authorized.version,request,operation,sink));
            synchronized(this){checkStreamFence(accountId,authorized.version,operation);requireImageEvidence(accountId,authorized.version,request);}
        }catch(GatewayFailure failure){recordFailure(accountId,authorized.version,failure);throw failure;}
        finally{endRequest(accountId,operation);}
    }
    private ChatGptProvider.EventSink fencedSink(String id,long version,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink){
        return new ChatGptProvider.EventSink(){
            @Override public void event(JSONObject event)throws Exception{synchronized(AccountGateway.this){checkStreamFence(id,version,operation);requireImageEvidence(id,version,request);sink.event(event);}}
            // Already parsed counters remain evidence about this exact old request after revocation.
            // They bypass no response/action fence and can never grant execution.
            @Override public void usage(JSONObject counters)throws Exception{sink.usage(counters);}
        };
    }
    private synchronized void checkStreamFence(String id,long version,GatewayCall operation)throws GatewayFailure {
        open();operation.check();JSONObject row=book.row(id);checkFence(row,version);usable(row);
    }
    private synchronized String resolve(String id)throws GatewayFailure {open();String value=id==null||id.isEmpty()?book.selected():id;if(value.isEmpty())throw new GatewayFailure("ACCOUNT_SELECTION_REQUIRED");book.row(value);return value;}
    private Authorized validCredentials(String accountId,GatewayCall operation,boolean force)throws GatewayFailure {
        return validCredentials(accountId,operation,force,null);
    }
    private Authorized validCredentials(String accountId,GatewayCall operation,boolean force,Long expectedVersion)throws GatewayFailure {
        return validCredentials(accountId,operation,force,expectedVersion,false);
    }
    private Authorized validCredentials(String accountId,GatewayCall operation,boolean force,Long expectedVersion,boolean joinReviewedInference)throws GatewayFailure {
        String id;Flight flight;boolean leader=false;
        synchronized(this){
            open();operation.check();
            id=expectedVersion==null?resolve(accountId):accountId;
            JSONObject row;
            if(expectedVersion==null)row=book.row(id);
            else if(joinReviewedInference){
                if(id==null||id.isEmpty()||expectedVersion<0)throw new GatewayFailure("STALE_ACCOUNT_VERSION");
                row=book.row(id);Flight admitted=refreshes.get(id);
                if(row.optLong("version")!=expectedVersion&&!(admitted!=null&&admitted.admittedVersion==expectedVersion
                        &&row.optLong("version")==admitted.row.optLong("version")))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
            }else row=reviewedRow(id,expectedVersion);
            usable(row);flight=refreshes.get(id);
            if(flight==null&&"refreshing".equals(row.optString("status")))throw new GatewayFailure("REAUTHORIZATION_REQUIRED");
            if(flight!=null){if(flight.waiters>=8)throw new GatewayFailure("REFRESH_QUEUE_FULL");flight.waiters++;}
            else{
                Credentials old=book.credentials(id,row.optLong("version"));
                if(!force&&old.expiresAt>System.currentTimeMillis()+60_000)return new Authorized(old,row.optLong("version"));
                if(!networkSlots.tryAcquire())throw new GatewayFailure("GATEWAY_BUSY");
                try{
                    long admittedVersion=row.optLong("version");
                    row=book.update(id,admittedVersion,AccountBook.json("status","refreshing","error",""));
                    flight=new Flight(row,old,operation,admittedVersion);refreshes.put(id,flight);track(id,operation);leader=true;
                }catch(GatewayFailure failure){networkSlots.release();throw failure;}
            }
        }
        if(!leader){
            try{while(!flight.done.await(100,TimeUnit.MILLISECONDS))operation.check();operation.check();if(flight.failure!=null)throw flight.failure;
                synchronized(this){
                    open();operation.check();JSONObject current=book.row(id);checkFence(current,flight.result.version);usable(current);
                    return flight.result;
                }}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new GatewayFailure("CANCELLED");}
            finally{synchronized(this){flight.waiters--;}}
        }
        try{
            ChatGptProvider.Grant fresh=provider.refresh(flight.row.optString("clientId"),flight.prior,flight.row.optString("subject"),operation,System.currentTimeMillis());
            synchronized(this){open();operation.check();String email=fresh.email.isEmpty()?flight.row.optString("email"):fresh.email;
                JSONObject saved=book.publish(id,flight.row.optLong("version"),fresh.credentials,fresh.issuer,fresh.subject,email);
                flight.result=new Authorized(fresh.credentials,saved.optLong("version"));return flight.result;}
        }catch(GatewayFailure failure){
            synchronized(this){flight.failure=failure;try{
                JSONObject row=book.row(id);if(row.optLong("version")==flight.row.optLong("version")){
                    if(failure.code.equals("REAUTHORIZATION_REQUIRED"))book.invalidateCredentials(id,row.optLong("version"),failure.code);
                    else book.update(id,row.optLong("version"),AccountBook.json("status",failure.retryAt>0?"cooldown":"connected","error",failure.code,"cooldownUntil",failure.retryAt,"diagnostics",failure.diagnostics()));
                }
            }catch(GatewayFailure storage){flight.failure=storage;}}
            throw flight.failure;
        }finally{synchronized(this){refreshes.remove(id);untrack(id,operation);flight.done.countDown();}networkSlots.release();}
    }
    private synchronized void beginRequest(String id,GatewayCall operation,long version)throws GatewayFailure {
        open();operation.check();JSONObject row=book.row(id);checkFence(row,version);usable(row);if(!networkSlots.tryAcquire())throw new GatewayFailure("GATEWAY_BUSY");track(id,operation);
    }
    private synchronized void endRequest(String id,GatewayCall operation){untrack(id,operation);networkSlots.release();}
    private synchronized void recordFailure(String id,long version,GatewayFailure failure){
        if(failure.code.equals("CANCELLED")||failure.code.equals("STALE_ACCOUNT_VERSION"))return;
        try{JSONObject row=book.row(id);if(row.optLong("version")==version&&!row.optString("slot").isEmpty()&&row.optBoolean("enabled")&&!row.optString("status").equals("authorizing"))book.metadata(id,version,AccountBook.json("status",failure.code.equals("REAUTHORIZATION_REQUIRED")?"reauthorization_required":failure.retryAt>0?"cooldown":row.optString("status"),
                "cooldownUntil",failure.retryAt,"error",failure.code,"usageLimited",row.optBoolean("usageLimited")||failure.code.equals("CHATGPT_USAGE_LIMIT_REACHED"),"diagnostics",failure.diagnostics()));}catch(GatewayFailure ignored){}
    }
    /** Called only while holding this gateway's admission/state monitor. */
    private JSONObject reviewedRow(String accountId,long expectedVersion)throws GatewayFailure {
        if(accountId==null||accountId.isEmpty())throw new GatewayFailure("ACCOUNT_SELECTION_REQUIRED");
        if(expectedVersion<0)throw new GatewayFailure("STALE_ACCOUNT_VERSION");
        JSONObject row=book.row(accountId);checkFence(row,expectedVersion);return row;
    }
    private static void usable(JSONObject row)throws GatewayFailure {
        enabled(row);String status=row.optString("status");
        if(row.optBoolean("usageLimited"))throw new GatewayFailure("CHATGPT_USAGE_LIMIT_REACHED");
        if(row.optLong("cooldownUntil")>System.currentTimeMillis())throw new GatewayFailure("ACCOUNT_COOLDOWN",row.optLong("cooldownUntil"));
        if(!status.equals("connected")&&!status.equals("refreshing")&&!status.equals("cooldown"))throw new GatewayFailure("REAUTHORIZATION_REQUIRED");
    }
    private static void enabled(JSONObject row)throws GatewayFailure {if(!row.optBoolean("enabled"))throw new GatewayFailure("ACCOUNT_DISABLED");}
    private static void checkFence(JSONObject row,long version)throws GatewayFailure {if(row.optLong("version")!=version)throw new GatewayFailure("STALE_ACCOUNT_VERSION");}
    private void track(String id,GatewayCall operation){operations.computeIfAbsent(id,key->new HashSet<>()).add(operation);}
    private void untrack(String id,GatewayCall operation){Set<GatewayCall> calls=operations.get(id);if(calls!=null){calls.remove(operation);if(calls.isEmpty())operations.remove(id);}}
    private void cancelOperations(String id){Set<GatewayCall> calls=operations.get(id);if(calls!=null)for(GatewayCall call:calls)call.cancel();}
    private void open()throws GatewayFailure {if(closed)throw new GatewayFailure("GATEWAY_CLOSED");}
    @Override public synchronized void close(){
        if(closed)return;closed=true;
        if(login!=null&&("pending".equals(login.status)||"exchanging".equals(login.status)))try{endLogin(login,"interrupted","PROCESS_INTERRUPTED");}catch(GatewayFailure ignored){login.close();}
        for(Set<GatewayCall> calls:operations.values())for(GatewayCall call:calls)call.cancel();provider.close();book.cleanupAll();
    }
    private static final class Authorized {
        final Credentials tokens;final long version;Authorized(Credentials tokens,long version){this.tokens=tokens;this.version=version;}
    }
    private static final class Flight {
        final JSONObject row;final Credentials prior;final GatewayCall owner;final long admittedVersion;final CountDownLatch done=new CountDownLatch(1);int waiters;GatewayFailure failure;Authorized result;
        Flight(JSONObject row,Credentials prior,GatewayCall owner,long admittedVersion){this.row=row;this.prior=prior;this.owner=owner;this.admittedVersion=admittedVersion;}
    }
}
