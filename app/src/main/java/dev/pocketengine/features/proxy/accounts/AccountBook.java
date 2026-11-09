package dev.pocketengine.features.proxy.accounts;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One in-process metadata authority per private file. Host creates one instance, never one per Activity. */
final class AccountBook {
    interface Writer {void write(File file,JSONObject value)throws Exception;}
    private final File file;
    private final CredentialVault vault;
    private final Writer writer;
    private JSONObject data;
    /** Immutable read-only projection of this authority's last successful commit, never credentials. */
    private volatile java.util.Map<String,InferenceIdentity> inferenceIdentities=java.util.Map.of();
    private record InferenceIdentity(long version,long authorizationEpoch,boolean authorized,boolean eligible,long cooldownUntil,
            java.util.Map<String,dev.pocketengine.runtime.models.AccountCatalogFacts> facts,boolean catalogObserved){}
    /** One immutable commit projection; no Gateway/Book monitor, vault access, or I/O. */
    dev.pocketengine.runtime.models.AccountCatalogFacts modelFacts(String id,long version,String model)throws GatewayFailure {
        InferenceIdentity current=inferenceIdentities.get(id);
        if(current==null||current.version!=version)throw new GatewayFailure("STALE_ACCOUNT_VERSION");
        // Facts are not eligibility: leave quota/cooldown recovery to the existing native admission.
        dev.pocketengine.runtime.models.AccountCatalogFacts facts=current.facts.get(model);
        if(current.catalogObserved&&facts==null)throw new GatewayFailure("STALE_MODEL_CATALOG");
        if(facts!=null&&(facts.observedAtMillis>System.currentTimeMillis()||System.currentTimeMillis()-facts.observedAtMillis>dev.pocketengine.runtime.models.ModelConnectionService.MAX_CATALOG_AGE_MILLIS))throw new GatewayFailure("STALE_MODEL_CATALOG");
        return facts;
    }
    boolean inferenceIdentityCurrent(String id,long version){InferenceIdentity value=inferenceIdentities.get(id);return value!=null&&value.version==version&&value.eligible&&value.cooldownUntil<=System.currentTimeMillis();}
    /** Native authorization continuity only. This never authorizes a new POST or exposes credentials. */
    boolean authorizationCurrent(String id,long version){InferenceIdentity value=inferenceIdentities.get(id);return value!=null&&value.authorized&&version>=value.authorizationEpoch&&version<=value.version;}
    boolean authorizationEpochCurrent(String id,long epoch){InferenceIdentity value=inferenceIdentities.get(id);return value!=null&&value.authorized&&value.authorizationEpoch==epoch;}
    private boolean cleanupRequired;
    AccountBook(File file,CredentialVault vault,String owner)throws GatewayFailure {this(file,vault,owner,AccountBook::atomicWrite);}
    AccountBook(File file,CredentialVault vault,String owner,Writer writer)throws GatewayFailure {
        this.file=file;this.vault=vault;this.writer=writer;
        if(owner==null||owner.isEmpty()||owner.length()>160)throw new GatewayFailure("OWNER_SCOPE_REQUIRED");
        try {
            if(file.isFile()){
                if(file.length()>128*1024)throw new GatewayFailure("ACCOUNT_STORE_INVALID");
                data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                if(data.getInt("schema")!=1||!owner.equals(data.getString("owner"))||!data.getString("hostId").matches("urn:uuid:[0-9a-f-]{36}")||data.getJSONObject("accounts").length()>16)throw new GatewayFailure("ACCOUNT_STORE_INVALID");
                JSONObject next=copy(data);JSONObject rows=next.getJSONObject("accounts");
                java.util.List<String> ids=new java.util.ArrayList<>();rows.keys().forEachRemaining(ids::add);
                for(String id:ids){
                    JSONObject row=rows.getJSONObject(id);validate(id,row);
                    // Older stores prove only their current version. Never infer an earlier grant lineage.
                    if(!row.has("authorizationEpoch"))row.put("authorizationEpoch",row.getLong("version"));
                    if("authorizing".equals(row.getString("status"))&&unregistered(row)){rows.remove(id);cleanup(id,"");continue;}
                    if("refreshing".equals(row.getString("status"))||"authorizing".equals(row.getString("status"))){
                        boolean resumable="refreshing".equals(row.getString("status"))&&row.optJSONObject("pendingRefresh")!=null;
                        row.put("status",resumable?"connected":"reauthorization_required").put("error",resumable?"REFRESH_VERIFICATION_PENDING":"PROCESS_INTERRUPTED");
                        if(!resumable){long nextVersion=row.getLong("version")+1;row.put("version",nextVersion).put("authorizationEpoch",nextVersion);}}
                    cleanup(id,row.optString("slot"),pendingSlot(row));
                }
                JSONObject login=next.optJSONObject("login");
                if(login!=null&&("pending".equals(login.optString("status"))||"exchanging".equals(login.optString("status"))))login.put("status","interrupted").put("error","PROCESS_INTERRUPTED");
                commit(next);
            }else{
                data=new JSONObject().put("schema",1).put("owner",owner).put("hostId","urn:uuid:"+UUID.randomUUID()).put("selected","").put("accounts",new JSONObject());
                writer.write(file,data);
            }
            inferenceIdentities=inferenceIdentities(data);
        }catch(GatewayFailure error){throw error;}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    synchronized String hostId(){return data.optString("hostId");}
    synchronized JSONObject row(String id)throws GatewayFailure {
        JSONObject row=data.optJSONObject("accounts").optJSONObject(id);
        if(row==null)throw new GatewayFailure("ACCOUNT_NOT_FOUND");return safeCopy(row);
    }
    synchronized String selected(){return data.optString("selected");}
    synchronized JSONObject snapshot()throws GatewayFailure {
        try{
            JSONArray accounts=new JSONArray();JSONObject rows=data.getJSONObject("accounts");
            for(java.util.Iterator<String> ids=rows.keys();ids.hasNext();){String id=ids.next();
                JSONObject row=rows.getJSONObject(id);
                accounts.put(new JSONObject().put("id",id).put("provider","chatgpt_plan").put("label",row.getString("label"))
                    .put("email",row.optString("email")).put("enabled",row.getBoolean("enabled")).put("status",row.getString("status"))
                    .put("selected",id.equals(selected())).put("version",row.getLong("version")).put("expiresAt",row.optLong("expiresAt"))
                    .put("planUsageEnabled",row.optBoolean("planUsageEnabled")).put("cooldownUntil",row.optLong("cooldownUntil"))
                    .put("error",row.optString("error")).put("usageLimited",row.optBoolean("usageLimited"))
                    .put("diagnostics",row.optJSONObject("diagnostics")==null?new JSONObject():copy(row.getJSONObject("diagnostics")))
                    .put("remoteRevocationConfirmed",row.optBoolean("remoteRevocationConfirmed"))
                    .put("source",dev.pocketengine.runtime.models.InferenceSourceDescriptor.forChatGptAccount(row.getString("status"),row.optBoolean("usageLimited"))));
            }
            JSONObject result=new JSONObject().put("accounts",accounts).put("selectedAccountId",selected()).put("credentialCleanupRequired",cleanupRequired).put("planWelcomeRequired",!data.optBoolean("planWelcomeAcknowledged")&&hasPlanConnection());
            if(data.optJSONObject("login")!=null)result.put("login",copy(data.getJSONObject("login")));
            return result;
        }catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    synchronized JSONObject create(String label)throws GatewayFailure {
        if(label==null||label.trim().isEmpty()||label.length()>80)throw new GatewayFailure("INVALID_ACCOUNT_LABEL");
        try{
            JSONObject next=copy(data);JSONObject rows=next.getJSONObject("accounts");if(rows.length()>=16)throw new GatewayFailure("ACCOUNT_LIMIT");
            String id=UUID.randomUUID().toString();JSONObject row=new JSONObject().put("id",id).put("label",label.trim()).put("enabled",true)
                .put("status","signed_out").put("version",0L).put("authorizationEpoch",0L).put("clientId","").put("issuer","").put("subject","").put("slot","");
            rows.put(id,row);commit(next);return copy(row);
        }catch(GatewayFailure error){throw error;}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    synchronized JSONObject update(String id,long expected,JSONObject changes)throws GatewayFailure {
        try{
            JSONObject row=row(id);fence(row,expected);
            for(java.util.Iterator<String> keys=changes.keys();keys.hasNext();){String key=keys.next();row.put(key,changes.get(key));}row.put("version",expected+1).put("authorizationEpoch",expected+1);
            JSONObject next=copy(data);next.getJSONObject("accounts").put(id,row);commit(next);return copy(row);
        }catch(GatewayFailure error){throw error;}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    // Credential generations are unchanged by catalog evidence, diagnostics, or explicit usage acknowledgement.
    synchronized void metadata(String id,long expected,JSONObject changes)throws GatewayFailure {
        try{JSONObject row=row(id);fence(row,expected);for(java.util.Iterator<String> keys=changes.keys();keys.hasNext();){String key=keys.next();
                if(!java.util.Set.of("imageEvidence","modelEvidence","diagnostics","usageLimited","error","cooldownUntil","status").contains(key))throw new GatewayFailure("INVALID_ARGUMENT");row.put(key,changes.get(key));}
            JSONObject next=copy(data);next.getJSONObject("accounts").put(id,row);commit(next);
        }catch(GatewayFailure failure){throw failure;}catch(Exception invalid){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    synchronized void acknowledgeWelcome(String id,long version)throws GatewayFailure {
        JSONObject row=row(id);fence(row,version);if(!row.optBoolean("planUsageEnabled"))throw new GatewayFailure("PLAN_USAGE_PERMISSION_REQUIRED");
        try{JSONObject next=copy(data);next.put("planWelcomeAcknowledged",true);commit(next);}catch(GatewayFailure failure){throw failure;}catch(Exception invalid){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    private boolean hasPlanConnection(){JSONObject rows=data.optJSONObject("accounts");for(java.util.Iterator<String> keys=rows.keys();keys.hasNext();)if(rows.optJSONObject(keys.next()).optBoolean("planUsageEnabled"))return true;return false;}
    synchronized void invalidateCredentials(String id,long expected,String error)throws GatewayFailure {
        update(id,expected,json("status","reauthorization_required","error",error,"slot","","expiresAt",0,"planUsageEnabled",false,"cooldownUntil",0,"pendingRefresh",JSONObject.NULL));cleanup(id,"");
    }
    synchronized void login(JSONObject state)throws GatewayFailure {
        try{JSONObject next=copy(data);next.put("login",copy(state));commit(next);}catch(GatewayFailure error){throw error;}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    synchronized void finishAuthorization(String id,long expected,JSONObject login,String error)throws GatewayFailure {
        finishAuthorization(id,expected,login,error,false);
    }
    synchronized void finishAuthorization(String id,long expected,JSONObject login,String error,boolean preserveExisting)throws GatewayFailure {
        try{
            JSONObject next=copy(data),rows=next.getJSONObject("accounts"),row=rows.optJSONObject(id);
            if(!preserveExisting&&row!=null&&row.optLong("version")==expected){
                if(unregistered(row)){rows.remove(id);if(id.equals(next.optString("selected")))next.put("selected","");}
                else row.put("version",expected+1).put("authorizationEpoch",expected+1).put("status","reauthorization_required").put("error",error);
            }
            next.put("login",copy(login));commit(next);
        }catch(GatewayFailure failure){throw failure;}catch(Exception failure){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    private static boolean unregistered(JSONObject row){return row.optString("clientId").isEmpty()&&row.optString("subject").isEmpty()&&row.optString("slot").isEmpty();}
    synchronized void select(String id)throws GatewayFailure {
        JSONObject row=row(id);if(!row.optBoolean("enabled"))throw new GatewayFailure("ACCOUNT_DISABLED");
        try{JSONObject next=copy(data);next.put("selected",id);commit(next);}catch(GatewayFailure error){throw error;}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    synchronized Credentials credentials(String id,long expected)throws GatewayFailure {
        JSONObject row=row(id);fence(row,expected);String slot=row.optString("slot");if(slot.isEmpty())throw new GatewayFailure("REAUTHORIZATION_REQUIRED");
        try{return Credentials.decode(vault.get(slot));}catch(Exception error){throw new GatewayFailure("CREDENTIALS_UNAVAILABLE");}
    }
    record PendingRefresh(Credentials credentials,long receivedAt,boolean identityRequired){}
    synchronized PendingRefresh pendingRefresh(String id,long expected)throws GatewayFailure {
        JSONObject row=row(id);fence(row,expected);JSONObject pending=row.optJSONObject("pendingRefresh");if(pending==null)return null;
        try{return new PendingRefresh(Credentials.decode(vault.get(pending.getString("slot"))),pending.getLong("receivedAt"),pending.getBoolean("identityRequired"));}
        catch(Exception invalid){throw new GatewayFailure("CREDENTIALS_UNAVAILABLE");}
    }
    synchronized void checkpointRefresh(String id,long expected,Credentials tokens,long receivedAt,boolean identityRequired)throws GatewayFailure {
        JSONObject row=row(id);fence(row,expected);
        if(!row.optBoolean("enabled")||!"refreshing".equals(row.optString("status")))throw new GatewayFailure("STALE_ACCOUNT_VERSION");
        String candidate=slot(id,0).equals(row.optString("slot"))?slot(id,1):slot(id,0);boolean saved=false;
        try{
            vault.put(candidate,tokens.encode());JSONObject next=copy(data);
            next.getJSONObject("accounts").getJSONObject(id).put("pendingRefresh",json("slot",candidate,"receivedAt",receivedAt,"identityRequired",identityRequired));
            commit(next);saved=true;
        }catch(Exception unavailable){throw new GatewayFailure("REFRESH_CHECKPOINT_UNAVAILABLE");}
        finally{if(!saved)remove(candidate);}
    }
    synchronized JSONObject publish(String id,long expected,Credentials tokens,String issuer,String subject,String email)throws GatewayFailure {
        return publish(id,expected,tokens,issuer,subject,email,null,false);
    }
    synchronized JSONObject publish(String id,long expected,Credentials tokens,String issuer,String subject,String email,JSONObject finalLogin)throws GatewayFailure {
        return publish(id,expected,tokens,issuer,subject,email,finalLogin,false);
    }
    synchronized JSONObject publishRenewal(String id,long expected,Credentials tokens,String issuer,String subject,String email)throws GatewayFailure {
        return publish(id,expected,tokens,issuer,subject,email,null,true);
    }
    private JSONObject publish(String id,long expected,Credentials tokens,String issuer,String subject,String email,JSONObject finalLogin,boolean renewal)throws GatewayFailure {
        JSONObject row=row(id);fence(row,expected);
        if(!row.optBoolean("enabled"))throw new GatewayFailure("ACCOUNT_DISABLED");
        if(!row.optString("subject").isEmpty()&&(!row.optString("subject").equals(subject)||!row.optString("issuer").equals(issuer)))throw new GatewayFailure("ACCOUNT_IDENTITY_MISMATCH");
        String old=row.optString("slot"),candidate=slot(id,0).equals(old)?slot(id,1):slot(id,0);
        boolean published=false,checkpointed=candidate.equals(pendingSlot(row));
        try{
            // A pending rotation owns the inactive slot until identity verification and publication finish.
            vault.put(candidate,tokens.encode());
            JSONObject next=copy(data);JSONObject changed=copy(row).put("version",expected+1).put("slot",candidate)
                .put("issuer",issuer).put("subject",subject).put("email",email).put("expiresAt",tokens.expiresAt)
                .put("planUsageEnabled",tokens.permitsInference()).put("status","connected").put("cooldownUntil",0).put("error","").put("remoteRevocationConfirmed",false).put("usageLimited",false).put("diagnostics",new JSONObject());
            boolean sameGrant=renewal&&!row.optString("subject").isEmpty()&&row.optBoolean("planUsageEnabled")==tokens.permitsInference();
            changed.put("authorizationEpoch",sameGrant?row.optLong("authorizationEpoch",expected):expected+1);
            changed.remove("pendingRefresh");
            next.getJSONObject("accounts").put(id,changed);
            if(next.optString("selected").isEmpty())next.put("selected",id);
            if(finalLogin!=null)next.put("login",copy(finalLogin));
            commit(next);published=true;cleanup(id,candidate);return copy(changed);
        }catch(GatewayFailure error){if(!published&&!checkpointed)remove(candidate);throw error;}catch(Exception error){if(!published&&!checkpointed)remove(candidate);throw new GatewayFailure("CREDENTIAL_COMMIT_FAILED");}
    }
    synchronized JSONObject signOut(String id)throws GatewayFailure {
        JSONObject row=row(id);
        JSONObject changed=update(id,row.optLong("version"),json("status","signed_out","slot","","expiresAt",0,"planUsageEnabled",false,"cooldownUntil",0,"error","","remoteRevocationConfirmed",false,"pendingRefresh",JSONObject.NULL));
        cleanup(id,"");return changed;
    }
    synchronized void cleanupAll(){cleanupRequired=false;JSONObject rows=data.optJSONObject("accounts");for(java.util.Iterator<String> ids=rows.keys();ids.hasNext();){String id=ids.next();JSONObject row=rows.optJSONObject(id);cleanup(id,row.optString("slot"),pendingSlot(row));}}
    private static String pendingSlot(JSONObject row){JSONObject pending=row.optJSONObject("pendingRefresh");return pending==null?"":pending.optString("slot");}
    private void cleanup(String id,String active){cleanup(id,active,"");}
    private void cleanup(String id,String active,String pending){for(int n=0;n<2;n++){String name=slot(id,n);if(!name.equals(active)&&!name.equals(pending))remove(name);}}
    private void remove(String slot){try{vault.remove(slot);}catch(Exception error){cleanupRequired=true;}}
    private static String slot(String id,int n){return "proxy.account."+id+"."+n;}
    private static void fence(JSONObject row,long expected)throws GatewayFailure {if(row.optLong("version",-1)!=expected)throw new GatewayFailure("STALE_ACCOUNT_VERSION");}
    private void commit(JSONObject next)throws GatewayFailure {
        try{java.util.Map<String,InferenceIdentity> projection=inferenceIdentities(next);writer.write(file,next);data=next;inferenceIdentities=projection;}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    private static java.util.Map<String,InferenceIdentity> inferenceIdentities(JSONObject source)throws Exception {
        java.util.Map<String,InferenceIdentity> result=new java.util.HashMap<>();JSONObject rows=source.optJSONObject("accounts");
        if(rows!=null)for(java.util.Iterator<String> ids=rows.keys();ids.hasNext();){String id=ids.next();JSONObject row=rows.optJSONObject(id);if(row==null)continue;
            boolean authorized=row.optBoolean("enabled")&&row.optBoolean("planUsageEnabled")
                    &&row.optString("status").matches("connected|cooldown|refreshing")
                    &&!row.optString("clientId").isEmpty()&&!row.optString("issuer").isEmpty()&&!row.optString("subject").isEmpty()&&!row.optString("slot").isEmpty();
            boolean eligible=authorized&&!row.optBoolean("usageLimited")&&row.optJSONObject("pendingRefresh")==null&&row.optString("status").matches("connected|cooldown");
            JSONObject evidence=modelEvidence(row,row.optLong("version",-2));
            java.util.Map<String,dev.pocketengine.runtime.models.AccountCatalogFacts> facts=new java.util.HashMap<>();
            if(evidence!=null)for(java.util.Iterator<String> models=evidence.keys();models.hasNext();){String model=models.next();JSONObject entry=evidence.getJSONObject(model);
                if(!model.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException("invalid_account_catalog_facts");
                if(!entry.optBoolean("unavailable")){dev.pocketengine.runtime.models.AccountCatalogFacts fact=dev.pocketengine.runtime.models.AccountCatalogFacts.fromJson(entry.getJSONObject("catalogFacts"));
                    if(!model.equals(fact.modelId))throw new IllegalArgumentException("invalid_account_catalog_facts");facts.put(model,fact);}
            }
            result.put(id,new InferenceIdentity(row.optLong("version",-1),row.optLong("authorizationEpoch",row.optLong("version",-1)),authorized,eligible,row.optLong("cooldownUntil"),java.util.Map.copyOf(facts),evidence!=null));
        }return java.util.Map.copyOf(result);
    }
    /** Per-model evidence for one credential generation; also reads the original single-model format. */
    static JSONObject modelEvidence(JSONObject row,long version)throws GatewayFailure {
        JSONObject evidence=row.optJSONObject("modelEvidence");
        if(evidence==null||evidence.optLong("accountVersion",-1)!=version)return null;
        try{
            if(evidence.has("models")){JSONObject models=evidence.getJSONObject("models");if(models.length()>512)throw new IllegalArgumentException();return models;}
            String model=evidence.getString("modelId");JSONObject image=row.optJSONObject("imageEvidence"),entry=copy(evidence);
            entry.put("imageEvidence",image!=null&&image.optLong("accountVersion",-1)==version&&model.equals(image.optString("modelId"))?image:null);
            return new JSONObject().put(model,entry);
        }catch(Exception invalid){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}
    }
    private static void validate(String id,JSONObject row)throws Exception {
        if(!id.matches("[0-9a-f-]{36}")||!id.equals(row.getString("id"))||row.getLong("version")<0||!(row.get("enabled") instanceof Boolean)||row.getString("label").length()>80)throw new GatewayFailure("ACCOUNT_STORE_INVALID");
        if(row.has("authorizationEpoch")){Object epoch=row.get("authorizationEpoch");if(!(epoch instanceof Integer||epoch instanceof Long)||((Number)epoch).longValue()<0||((Number)epoch).longValue()>row.getLong("version"))throw new GatewayFailure("ACCOUNT_STORE_INVALID");}
        String slot=row.getString("slot");if(!slot.isEmpty()&&!slot.equals(slot(id,0))&&!slot.equals(slot(id,1)))throw new GatewayFailure("ACCOUNT_STORE_INVALID");
        JSONObject pending=row.optJSONObject("pendingRefresh");if(pending!=null){String candidate=pending.getString("slot");
            if(slot.isEmpty()||candidate.equals(slot)||!candidate.equals(slot(id,0))&&!candidate.equals(slot(id,1))||pending.getLong("receivedAt")<1||!(pending.get("identityRequired") instanceof Boolean))throw new GatewayFailure("ACCOUNT_STORE_INVALID");}
        row.getString("status");
    }
    static JSONObject json(Object... pairs)throws GatewayFailure {
        try{JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}catch(Exception error){throw new GatewayFailure("INVALID_ARGUMENT");}
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject safeCopy(JSONObject value)throws GatewayFailure {try{return copy(value);}catch(Exception error){throw new GatewayFailure("ACCOUNT_STORE_UNAVAILABLE");}}
    static void atomicWrite(File file,JSONObject value)throws Exception {
        File parent=file.getAbsoluteFile().getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new java.io.IOException();
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>128*1024)throw new java.io.IOException();
        File temporary=File.createTempFile("accounts-",".pending",parent);
        try{
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
            // Fail closed on a filesystem without atomic replacement; never delete the authoritative file first.
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary.toPath());}
    }
}
