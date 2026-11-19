package dev.pocketengine.features.local.project;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.ModelRoutePlan;
import org.json.JSONObject;

/** Binds a review to the actual captured destination. The web page gets only the hostname, provider and model label. */
public final class OwnerRevisionRecipient {
    private OwnerRevisionRecipient(){}
    public static String pin(ModelConfig configuration)throws Exception{
        JSONObject exact=new JSONObject().put("configuration",ModelRoutePlan.configurationDigest(configuration)).put("credentialIdentity",configuration.credentialIdentityDigest());
        if(configuration.usesAccount())exact.put("account",configuration.accountModel.toJson());
        if(configuration.capabilityBinding!=null)exact.put("binding",configuration.capabilityBinding.toJson());
        return ProjectSources.digest(exact);
    }
    public static String destination(ModelConfig configuration)throws Exception{
        if(configuration.usesAccount())return "ChatGPT · "+new java.net.URI(dev.pocketengine.features.proxy.providers.ChatGptProvider.RESOURCE).getHost()+" · 本机账号 "+configuration.accountModel.accountId;
        // Use the same protocol adapter as ModelGateway, including Gemini and regional Bedrock.
        java.net.URI uri=dev.pocketengine.runtime.providers.ProviderAdapters.selected(configuration.provider).endpoint(configuration).toURI();
        if(uri.getHost()==null||uri.getHost().isBlank())throw new SecurityException("尚不能核对这个模型的接收地址");
        return configuration.provider+" · "+uri.getHost()+(uri.getPort()>=0?":"+uri.getPort():"");
    }
}
