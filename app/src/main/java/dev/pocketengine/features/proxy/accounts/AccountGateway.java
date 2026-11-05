package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.core.RuntimeDiagnostics;
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
    private final AccountModelCatalog modelCatalog;
    private final Map<CatalogKey,CatalogFlight> catalogFlights=new HashMap<>();
    private final java.util.concurrent.ThreadPoolExecutor catalogWorkers=new java.util.concurrent.ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(16),work->{Thread thread=new Thread(work,"account-catalog");thread.setDaemon(true);return thread;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final Map<String,Set<GatewayCall>> operations=new HashMap<>();
    private LoopbackAuthorization login;
    private volatile boolean closed;
    synchronized int catalogWaiterCount(String id){int count=0;for(var entry:catalogFlights.entrySet())if(entry.getKey().id.equals(id))count+=entry.getValue().waiters;return count;}
    /** Lock-free last-commit projection; no credential getter or inference/refresh admission. */
    public boolean inferenceIdentityCurrent(String accountId,long accountVersion){return !closed&&book.inferenceIdentityCurrent(accountId,accountVersion);}
    /** Revocation check for an original run or admitted response, not admission for another request. */
    public boolean authorizationCurrent(String accountId,long accountVersion){return !closed&&book.authorizationCurrent(accountId,accountVersion);}
    /** Lock-free capability projection from this same native metadata commit, never an inference grant. */
    public dev.pocketengine.runtime.models.AccountCatalogFacts modelFacts(String id,long version,String model)throws GatewayFailure {
        if(closed)throw new GatewayFailure("GATEWAY_CLOSED");return book.modelFacts(id,version,model);
    }
    public AccountGateway(File metadata,CredentialVault vault,String hostOwnerScope)throws GatewayFailure {
        this(new AccountBook(metadata,vault,hostOwnerScope),new ChatGptProvider());
    }
    // Internal fixture seam. Production callers cannot select provider endpoints or inject host identity in a request.
    AccountGateway(AccountBook book,ChatGptProvider provider){this.book=book;this.provider=provider;this.modelCatalog=new AccountModelCatalog(book);}
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
        RuntimeDiagnostics.Span diagnostic=RuntimeDiagnostics.begin("login",null,accountId);
        try{
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
            diagnostic.finish("accepted","AUTHORIZATION_PENDING");
            return AccountBook.json("sessionId",next.id,"accountId",id,"status","pending","expiresAt",next.expiresAt,"authorizationUrl",url);
        }catch(GatewayFailure error){next.status="failed";next.error=error.code;next.close();if(login==next)login=null;try{book.finishAuthorization(id,next.accountVersion,loginView(next),error.code);}catch(GatewayFailure ignored){}throw error;}
        }catch(GatewayFailure failure){diagnostic.failure(failure.code,failure);throw failure;}finally{diagnostic.close();}
    }
    private void completeLogin(LoopbackAuthorization session,Map<String,String> callback)throws GatewayFailure {
        RuntimeDiagnostics.Span diagnostic=RuntimeDiagnostics.begin("login",null,session.accountId);
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
            diagnostic.success();
        }catch(GatewayFailure error){
            diagnostic.failure(error.code,error);
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
            diagnostic.close();
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
            try{AccountBook.PendingRefresh pending=book.pendingRefresh(accountId,row.optLong("version"));prior=pending==null?book.credentials(accountId,row.optLong("version")):pending.credentials();}catch(GatewayFailure unavailable){}
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
        JSONArray models=readCatalog(accountId,tokens,operation);
        synchronized(this){checkStreamFence(accountId,tokens.version,operation);return AccountBook.json("accountId",accountId,"version",tokens.version,"models",models);}
    }
    public JSONArray models(String accountId,GatewayCall operation)throws GatewayFailure {
        String id=resolve(accountId);Authorized tokens=validCredentials(id,operation,false);
        return readCatalog(id,tokens,operation);
    }
    /** One bounded metadata request per exact credential generation. Each waiter owns its cancellation. */
    private JSONArray readCatalog(String id,Authorized authorized,GatewayCall caller)throws GatewayFailure {
        CatalogFlight flight;CatalogKey key;
        synchronized(this){open();caller.check();JSONObject row=book.row(id);checkFence(row,authorized.version);usable(row);
            key=new CatalogKey(id,row.optLong("authorizationEpoch",authorized.version),authorized.version);
            flight=catalogFlights.get(key);
            if(flight==null){flight=new CatalogFlight(key,authorized);catalogFlights.put(key,flight);}
            if(flight.waiters>=8)throw new GatewayFailure("GATEWAY_BUSY");
            flight.waiters++;
            if(!flight.started){
                flight.started=true;CatalogFlight start=flight;flight.work=()->loadCatalog(start);
                try{catalogWorkers.execute(flight.work);}
                catch(java.util.concurrent.RejectedExecutionException full){
                    flight.failure=new GatewayFailure(closed?"GATEWAY_CLOSED":"GATEWAY_BUSY");flight.done.countDown();catalogFlights.remove(key,flight);flight.operation.close();
                    RuntimeDiagnostics.event("model_catalog","rejected",flight.failure.code,null,null);
                }
            }
        }
        try{
            while(!flight.done.await(50,TimeUnit.MILLISECONDS)){caller.check();if(closed)throw new GatewayFailure("GATEWAY_CLOSED");}caller.check();
            if(flight.failure!=null)throw flight.failure;
            synchronized(this){checkStreamFence(id,authorized.version,caller);return AccountModelCatalog.copyArray(flight.models);}
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new GatewayFailure("CANCELLED");}
        finally{synchronized(this){if(--flight.waiters==0){catalogFlights.remove(key,flight);if(flight.done.getCount()!=0){flight.operation.cancel();if(catalogWorkers.remove(flight.work)){flight.failure=new GatewayFailure("CANCELLED");flight.done.countDown();}}}}}
    }
    private void loadCatalog(CatalogFlight flight){
        boolean admitted=false;String id=flight.key.id;long version=flight.key.version;
        RuntimeDiagnostics.Span diagnostic=RuntimeDiagnostics.begin("model_catalog",null,id);
        try{
            beginRequest(id,flight.operation,version);admitted=true;
            JSONArray models=provider.models(flight.authorized.tokens,flight.operation);
            synchronized(this){checkStreamFence(id,version,flight.operation);
                modelCatalog.observed(id,version,models,System.currentTimeMillis());
                flight.models=AccountModelCatalog.copyArray(models);}diagnostic.success();
        }catch(GatewayFailure failure){flight.failure=failure;diagnostic.failure(failure.code,failure);recordFailure(id,version,failure);}
        catch(RuntimeException failure){flight.failure=new GatewayFailure("INVALID_MODEL_CATALOG");diagnostic.failure(flight.failure.code,failure);}
        finally{if(admitted)endRequest(id,flight.operation);synchronized(this){flight.done.countDown();if(flight.waiters==0)catalogFlights.remove(flight.key,flight);}flight.operation.close();diagnostic.close();}
    }
    /** Pin only facts from this gateway's completed same-token catalog; UI cannot supply capability booleans. */
    public synchronized JSONObject selectModelReviewed(String accountId,long version,String modelId)throws GatewayFailure {
        open();JSONObject row=reviewedRow(accountId,version);usable(row);
        modelCatalog.pinReviewed(accountId,version,modelId);book.select(accountId);return snapshot();
    }
    public synchronized boolean supportsImageInput(String accountId,long version,String modelId){
        try{open();JSONObject row=reviewedRow(accountId,version);usable(row);
            if(!row.optBoolean("planUsageEnabled"))return false;
            JSONObject models=AccountBook.modelEvidence(row,version);
            if(models!=null){JSONObject entry=models.optJSONObject(modelId);if(entry==null||entry.optBoolean("unavailable"))return false;
                book.modelFacts(accountId,version,modelId);return dev.pocketengine.features.proxy.providers.AccountImageCapability.supported(entry.optJSONObject("imageEvidence"));}
            JSONObject fact=row.optJSONObject("imageEvidence");return fact!=null&&fact.optLong("accountVersion",-1)==version&&modelId.equals(fact.optString("modelId"))&&
                dev.pocketengine.features.proxy.providers.AccountImageCapability.supported(fact);
        }catch(GatewayFailure unavailable){return false;}
    }
    public synchronized boolean planUsageLimited(String accountId,long version){
        try{open();JSONObject row=reviewedRow(accountId,version);return row.optBoolean("enabled")&&row.optBoolean("planUsageEnabled")&&row.optBoolean("usageLimited");}
        catch(GatewayFailure unavailable){return false;}
    }
    private void requireImageEvidence(String id,long version,ResponsesRequest request)throws GatewayFailure {
        if(request.hasImages()&&!supportsImageInput(id,version,request.modelId()))throw new GatewayFailure("ACCOUNT_IMAGE_CAPABILITY_REQUIRED");
    }
    /** Public native preparation metadata only. Credentials and the dispatch lease remain private. */
    public static final class PreparedModel {
        public final long accountVersion;
        public final dev.pocketengine.runtime.models.AccountCatalogFacts facts;
        private PreparedModel(long version,dev.pocketengine.runtime.models.AccountCatalogFacts facts){accountVersion=version;this.facts=facts;}
        public boolean supportsImages(){return facts!=null&&facts.facts.imageInput().known&&Boolean.TRUE.equals(facts.facts.imageInput().value);}
    }
    /** Explicit worker-side preparation. No POST, model substitution, or I/O in a metadata getter. */
    public PreparedModel prepareForInference(String id,long expectedVersion,String model,GatewayCall operation)throws GatewayFailure {
        return prepareForInference(id,expectedVersion,model,operation,true);
    }
    private PreparedModel prepareForInference(String id,long expectedVersion,String model,GatewayCall operation,boolean requireCatalog)throws GatewayFailure {
        if(operation==null||model==null||!model.matches("[A-Za-z0-9_.:-]{1,160}"))throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        for(int attempt=0;attempt<2;attempt++){
            operation.check();
            try{
                Authorized actual=validCredentials(id,operation,false,expectedVersion,true);
                synchronized(this){checkStreamFence(id,actual.version,operation);
                    if(actual.tokens.expiresAt<=System.currentTimeMillis()+60_000)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
                    try{var facts=book.modelFacts(id,actual.version,model);if(facts!=null)return new PreparedModel(actual.version,facts);}
                    catch(GatewayFailure stale){if(!"STALE_MODEL_CATALOG".equals(stale.code))throw stale;}
                    // Preserve the original unobserved, default text-only compatibility seam.
                    // Native selected-model preparation always requires the catalog; observed
                    // evidence, images and explicit reasoning can never fall back to unknown.
                    if(!requireCatalog&&book.row(id).optJSONObject("modelEvidence")==null)return new PreparedModel(actual.version,null);
                    if(modelCatalog.pinFresh(id,actual.version,model,System.currentTimeMillis()))
                        return new PreparedModel(actual.version,book.modelFacts(id,actual.version,model));
                }
                JSONArray models=readCatalog(id,actual,operation);
                synchronized(this){checkStreamFence(id,actual.version,operation);modelCatalog.pin(id,actual.version,model,models);return new PreparedModel(actual.version,book.modelFacts(id,actual.version,model));}
            }catch(GatewayFailure failure){
                // A concurrent same-grant rotation can obsolete a read. Rebuild only preparation,
                // at most once, while the caller's original deadline and cancellation remain active.
                if(attempt==0&&(failure.code.equals("STALE_ACCOUNT_VERSION")||failure.code.equals("ACCOUNT_REFRESH_REQUIRED"))&&authorizationCurrent(id,expectedVersion))continue;
                throw failure;
            }
        }
        throw new GatewayFailure("STALE_ACCOUNT_VERSION");
    }
    public void streamText(String accountId,String model,String instructions,JSONArray input,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {
        String id=resolve(accountId);Authorized tokens=validCredentials(id,operation,false);
        beginRequest(id,operation,tokens.version);
        try{new ResponsesRequest(model,instructions,input,new JSONArray()).admit(modelFacts(id,tokens.version,model));
            ResponseLease lease=responseLease(id,tokens.version,operation);
            provider.stream(tokens.tokens,model,instructions,input,operation,event->{checkResponseLease(lease,operation);sink.event(event);});}
        catch(GatewayFailure failure){recordFailure(id,tokens.version,failure);throw failure;}finally{endRequest(id,operation);}
    }
    /** Completed output describes a model turn, not a completed user task or a tool execution grant. */
    public void streamResponses(String accountId,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {
        if(request==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        String id=resolve(accountId);Authorized tokens=validCredentials(id,operation,false);
        beginRequest(id,operation,tokens.version);
        try{ResponseLease lease;synchronized(this){requireImageEvidence(id,tokens.version,request);request.admit(modelFacts(id,tokens.version,request.modelId()));lease=responseLease(id,tokens.version,operation);}provider.streamResponses(tokens.tokens,request,operation,fencedSink(lease,operation,sink));}
        catch(GatewayFailure failure){recordFailure(id,tokens.version,failure);throw failure;}finally{endRequest(id,operation);}
    }
    /**
     * A reviewed native selection is exact, not an alias for the currently selected account.
     * Token renewal changes account version: require owner refresh/reselection instead of silently
     * following a new authorization generation or retrying a possibly charged model request.
     */
    public void streamResponsesReviewed(String accountId,long expectedVersion,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure {
        if(request==null||operation==null||sink==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        Credentials tokens;ResponseLease lease;
        synchronized(this){
            open();operation.check();JSONObject row=reviewedRow(accountId,expectedVersion);usable(row);
            if(refreshes.containsKey(accountId)||"refreshing".equals(row.optString("status"))||row.optJSONObject("pendingRefresh")!=null)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
            tokens=book.credentials(accountId,expectedVersion);
            if(tokens.expiresAt<=System.currentTimeMillis()+60_000)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
            requireImageEvidence(accountId,expectedVersion,request);request.admit(modelFacts(accountId,expectedVersion,request.modelId()));
            lease=responseLease(accountId,expectedVersion,operation);
            beginRequest(accountId,operation,expectedVersion);
        }
        try{
            provider.streamResponses(tokens,request,operation,fencedSink(lease,operation,sink));
            checkResponseLease(lease,operation);
        }catch(GatewayFailure failure){recordFailure(accountId,expectedVersion,failure);throw failure;}
        finally{endRequest(accountId,operation);}
    }
    /** Receives only native authorization identity, never tokens or model-controlled fields. */
    public interface InferenceVersionSink {void authorized(long actualVersion)throws GatewayFailure;}
    /** Reviewed entry with one existing singleflight token renewal; never retries inference. */
    public void streamResponsesReviewed(String accountId,long expectedVersion,ResponsesRequest request,GatewayCall operation,
                                        InferenceVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure {
        if(request==null||operation==null||versions==null||sink==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        Credentials tokens=null;ResponseLease lease=null;
        for(int attempt=0;attempt<2&&lease==null;attempt++){
            PreparedModel prepared=prepareForInference(accountId,expectedVersion,request.modelId(),operation,request.hasImages()||request.hasExplicitReasoning());
            synchronized(this){
                JSONObject row=book.row(accountId);
                if(row.optLong("version")!=prepared.accountVersion&&authorizationCurrent(accountId,expectedVersion)&&attempt==0)continue;
                checkStreamFence(accountId,prepared.accountVersion,operation);tokens=book.credentials(accountId,prepared.accountVersion);
                if(tokens.expiresAt<=System.currentTimeMillis()+60_000)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
                requireImageEvidence(accountId,prepared.accountVersion,request);request.admit(prepared.facts);
                // Persist/update the trusted selection before any possibly charged model request.
                versions.authorized(prepared.accountVersion);
                lease=responseLease(accountId,prepared.accountVersion,operation);
                beginRequest(accountId,operation,prepared.accountVersion);
            }
        }
        if(lease==null)throw new GatewayFailure("STALE_ACCOUNT_VERSION");
        try{
            provider.streamResponses(tokens,request,operation,fencedSink(lease,operation,sink));
            checkResponseLease(lease,operation);
        }catch(GatewayFailure failure){recordFailure(accountId,lease.version,failure);throw failure;}
        finally{endRequest(accountId,operation);}
    }
    private ChatGptProvider.EventSink fencedSink(ResponseLease lease,GatewayCall operation,ChatGptProvider.EventSink sink){
        return new ChatGptProvider.EventSink(){
            @Override public void event(JSONObject event)throws Exception{synchronized(AccountGateway.this){checkResponseLease(lease,operation);sink.event(event);}}
            // Already parsed counters remain evidence about this exact old request after revocation.
            // They bypass no response/action fence and can never grant execution.
            @Override public void usage(JSONObject counters)throws Exception{sink.usage(counters);}
        };
    }
    private record ResponseLease(String id,long version,long authorizationEpoch){}
    private synchronized ResponseLease responseLease(String id,long version,GatewayCall operation)throws GatewayFailure {
        checkStreamFence(id,version,operation);JSONObject row=book.row(id);return new ResponseLease(id,version,row.optLong("authorizationEpoch",version));
    }
    private synchronized void checkResponseLease(ResponseLease lease,GatewayCall operation)throws GatewayFailure {
        open();operation.check();if(!book.authorizationEpochCurrent(lease.id,lease.authorizationEpoch))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
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
                row=book.row(id);
                if(row.optLong("version")!=expectedVersion&&!book.authorizationCurrent(id,expectedVersion))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
            }else row=reviewedRow(id,expectedVersion);
            usable(row);flight=refreshes.get(id);
            if(joinReviewedInference&&!row.optBoolean("planUsageEnabled"))throw new GatewayFailure("PLAN_USAGE_PERMISSION_REQUIRED");
            if(flight==null&&"refreshing".equals(row.optString("status")))throw new GatewayFailure("REAUTHORIZATION_REQUIRED");
            if(flight!=null){if(flight.waiters>=8)throw new GatewayFailure("REFRESH_QUEUE_FULL");flight.waiters++;}
            else{
                Credentials old=book.credentials(id,row.optLong("version"));AccountBook.PendingRefresh pending=book.pendingRefresh(id,row.optLong("version"));
                if(pending==null&&!force&&old.expiresAt>System.currentTimeMillis()+60_000)return new Authorized(old,row.optLong("version"));
                if(!networkSlots.tryAcquire())throw new GatewayFailure("GATEWAY_BUSY");
                try{
                    long admittedVersion=row.optLong("version");
                    book.metadata(id,admittedVersion,AccountBook.json("status","refreshing","error",""));row=book.row(id);
                    flight=new Flight(row,old,operation,admittedVersion,pending);refreshes.put(id,flight);track(id,operation);leader=true;
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
        RuntimeDiagnostics.Span refreshDiagnostic=RuntimeDiagnostics.begin("account_refresh",null,id);
        try{
            Flight original=flight;String account=id;AccountBook.PendingRefresh pending=flight.pending;
            ChatGptProvider.Grant fresh=pending==null?provider.refresh(flight.row.optString("clientId"),flight.prior,flight.row.optString("subject"),operation,System.currentTimeMillis(),(tokens,at,identityRequired)->{
                // The token response already consumed the old grant. Cancellation may stop
                // verification, but must not discard this bundle while its exact account still exists.
                synchronized(this){JSONObject row=book.row(account);checkFence(row,original.row.optLong("version"));enabled(row);book.checkpointRefresh(account,original.row.optLong("version"),tokens,at,identityRequired);}
            }):provider.resumeRefresh(flight.row.optString("clientId"),pending.credentials(),flight.row.optString("subject"),pending.identityRequired(),pending.receivedAt(),operation);
            synchronized(this){open();operation.check();String email=fresh.email.isEmpty()?flight.row.optString("email"):fresh.email;
                JSONObject saved=book.publishRenewal(id,flight.row.optLong("version"),fresh.credentials,fresh.issuer,fresh.subject,email);
                // A long verification outage may outlive the checkpoint's access token.
                // Retain its verified rotated refresh token, but never dispatch expired access.
                if(fresh.credentials.expiresAt<=System.currentTimeMillis()+60_000)throw new GatewayFailure("ACCOUNT_REFRESH_REQUIRED");
                flight.result=new Authorized(fresh.credentials,saved.optLong("version"));refreshDiagnostic.success();return flight.result;}
        }catch(GatewayFailure failure){
            synchronized(this){flight.failure=failure;try{
                JSONObject row=book.row(id);if(row.optLong("version")==flight.row.optLong("version")){
                    if(Set.of("REAUTHORIZATION_REQUIRED","INVALID_IDENTITY_TOKEN","ACCOUNT_IDENTITY_MISMATCH","INVALID_TOKEN_RESPONSE","REFRESH_CHECKPOINT_UNAVAILABLE").contains(failure.code))book.invalidateCredentials(id,row.optLong("version"),failure.code);
                    else book.metadata(id,row.optLong("version"),AccountBook.json("status",failure.retryAt>0?"cooldown":"connected","error",failure.code,"cooldownUntil",failure.retryAt,"diagnostics",failure.diagnostics()));
                }
            }catch(GatewayFailure storage){flight.failure=storage;}}
            refreshDiagnostic.failure(flight.failure.code,flight.failure);throw flight.failure;
        }finally{synchronized(this){refreshes.remove(id);untrack(id,operation);flight.done.countDown();}networkSlots.release();refreshDiagnostic.close();}
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
        for(Set<GatewayCall> calls:operations.values())for(GatewayCall call:calls)call.cancel();
        for(CatalogFlight flight:catalogFlights.values()){
            flight.operation.cancel();if(flight.done.getCount()!=0){flight.failure=new GatewayFailure("GATEWAY_CLOSED");flight.done.countDown();}
        }
        catalogFlights.clear();catalogWorkers.shutdownNow();provider.close();book.cleanupAll();
    }
    private record CatalogKey(String id,long authorizationEpoch,long version){}
    private static final class CatalogFlight {
        final CatalogKey key;final Authorized authorized;final GatewayCall operation=new GatewayCall(60_000);final CountDownLatch done=new CountDownLatch(1);
        int waiters;boolean started;Runnable work;volatile GatewayFailure failure;volatile JSONArray models;
        CatalogFlight(CatalogKey key,Authorized authorized){this.key=key;this.authorized=authorized;}
    }
    private static final class Authorized {
        final Credentials tokens;final long version;Authorized(Credentials tokens,long version){this.tokens=tokens;this.version=version;}
    }
    private static final class Flight {
        final JSONObject row;final Credentials prior;final GatewayCall owner;final long admittedVersion;final AccountBook.PendingRefresh pending;final CountDownLatch done=new CountDownLatch(1);int waiters;GatewayFailure failure;Authorized result;
        Flight(JSONObject row,Credentials prior,GatewayCall owner,long admittedVersion,AccountBook.PendingRefresh pending){this.row=row;this.prior=prior;this.owner=owner;this.admittedVersion=admittedVersion;this.pending=pending;}
    }
}
