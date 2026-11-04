package dev.pocketengine.features;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.TaskStore;
import org.json.JSONObject;
/** Destination consent metadata only, encrypted with the existing vault; never task/run/grant truth. */
public final class AssistantAttachmentRoutePins {
 private final SecretVault vault;
 public AssistantAttachmentRoutePins(SecretVault vault){this.vault=java.util.Objects.requireNonNull(vault);}
 public synchronized void require(String taskId,ModelConfig config,boolean allowInitialPin)throws Exception{
  String slot="assistant-attachment-route:"+taskId;JSONObject current=new JSONObject().put("route",config.usesAccount()?"account":"api");
  if(config.usesAccount())current.put("accountId",config.accountModel.accountId).put("modelId",config.accountModel.modelId).put("version",config.accountModel.accountVersion);
  else current.put("fingerprint",TaskStore.capabilityFingerprint(new JSONObject().put("provider",config.provider).put("baseUrl",config.baseUrl).put("model",config.model).put("credential",config.usesBedrock()?config.credentialIdentityDigest():config.apiKey).put("cleartextConsent",config.cleartextConsent)));
  String stored=vault.get(slot);
  if(stored.isEmpty()){if(!allowInitialPin)throw denied();vault.put(slot,current.toString());return;}
  JSONObject pin=new JSONObject(stored);boolean same=pin.optString("route").equals(current.getString("route"));
  if(config.usesAccount())same=same&&pin.optString("accountId").equals(config.accountModel.accountId)&&pin.optString("modelId").equals(config.accountModel.modelId)&&config.accountModel.accountVersion>=pin.optLong("version",Long.MAX_VALUE);
  else same=same&&pin.optString("fingerprint").equals(current.getString("fingerprint"));
  if(!same)throw denied();
 }
 private static SecurityException denied(){return new SecurityException("本任务附件的原模型目的地或凭据已变化/不可核对，未发送附件；请恢复原配置或在新消息中明确重选附件");}
}
