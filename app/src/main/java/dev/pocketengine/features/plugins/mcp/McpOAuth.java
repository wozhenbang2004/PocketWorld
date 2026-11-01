package dev.pocketengine.features.plugins.mcp;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Public-client OAuth leaf. Browser launch, native callbacks and the encrypted SecretVault mount belong to the host. */
public final class McpOAuth {
    public enum Use { RESOURCE_METADATA, ISSUER_METADATA, AUTHORIZATION, TOKEN }
    public enum Registration { PRE_REGISTERED, CIMD }
    public static final class Binding {
        public final String installationId,resource,serverDigest,descriptorDigest;
        public Binding(String installationId,String resource,String serverDigest,String descriptorDigest)throws Exception {
            this.installationId=text(installationId,160);McpOAuthMetadata.https(resource);this.resource=resource;
            if(!serverDigest.matches("[a-f0-9]{64}")||!descriptorDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact installation digests required");
            this.serverDigest=serverDigest;this.descriptorDigest=descriptorDigest;
        }
        public String fingerprint()throws Exception{return McpJson.digest(new org.json.JSONArray().put(installationId).put(resource).put(serverDigest).put(descriptorDigest));}
    }
    public interface Trust {
        /** Live native installation/owner/descriptor check; model arguments are never authority. */
        void check(Binding binding)throws Exception;
        /** Native outbound policy; metadata does not automatically approve a new endpoint or issuer. */
        void endpoint(Binding binding,String endpoint,Use use)throws Exception;
        /** Exact native review for issuer, client, registered redirect, scopes and metadata digest. */
        void authorization(Binding binding,String issuer,String clientId,String redirectUri,String scopes,String metadataDigest)throws Exception;
    }
    public interface EncryptedSecrets {
        /** Must return true only for authenticated encryption at rest (the host's SecretVault); no plaintext fallback. */
        boolean encryptedAtRest();
        /** Atomic create-if-absent. Slot names contain no credential values. */
        void create(String slot,String secret)throws Exception;
        String read(String slot)throws Exception;
        /** Atomic read-and-delete BEFORE token exchange, shared by all instances/process callbacks. */
        String take(String slot)throws Exception;
        void remove(String slot)throws Exception;
    }
    public interface Http {
        /** No credentials on GET; form POST is one-shot. No redirects, retry, cookies or raw-data logging. */
        Reply request(String endpoint,Map<String,String> formOrNull,RunControl control)throws Exception;
        /** Native credential-free resource probe. Existing non-HTTP test transports may use well-known discovery. */
        default List<String> authenticationChallenge(String exactResource,RunControl control)throws Exception{return Collections.emptyList();}
    }
    public static final class Reply {
        public final int status;public final JSONObject json;
        public Reply(int status,JSONObject json)throws Exception{this.status=status;this.json=new JSONObject(json.toString());}
    }
    public static final class Discovery {
        public final String resource,scopes;public final List<String> issuers;
        private final String binding;
        private Discovery(String binding,String resource,String scopes,List<String> issuers){this.binding=binding;this.resource=resource;this.scopes=scopes;this.issuers=Collections.unmodifiableList(issuers);}
    }
    public static final class Server {
        public final String issuer,authorizationEndpoint,tokenEndpoint,metadataDigest;
        public final boolean responseIssuerRequired,cimdSupported;
        private final String binding;
        private Server(String binding,String issuer,String authorizationEndpoint,String tokenEndpoint,String metadataDigest,boolean responseIssuerRequired,boolean cimdSupported){
            this.binding=binding;this.issuer=issuer;this.authorizationEndpoint=authorizationEndpoint;this.tokenEndpoint=tokenEndpoint;this.metadataDigest=metadataDigest;
            this.responseIssuerRequired=responseIssuerRequired;this.cimdSupported=cimdSupported;
        }
    }
    public static final class Client {
        public final String issuer,clientId,redirectUri;public final Registration registration;
        public Client(String issuer,String clientId,String redirectUri,Registration registration)throws Exception {
            McpOAuthMetadata.https(issuer);this.issuer=issuer;this.clientId=text(clientId,2000);this.registration=Objects.requireNonNull(registration);
            URI redirect=new URI(redirectUri);
            if(redirect.getRawQuery()!=null||redirect.getRawFragment()!=null||redirect.getRawUserInfo()!=null||redirect.getHost()==null||redirectUri.length()>2000)throw new SecurityException("Invalid native callback URI");
            if(!"https".equals(redirect.getScheme())&&!("http".equals(redirect.getScheme())&&("127.0.0.1".equals(redirect.getHost())||"localhost".equals(redirect.getHost())||"[::1]".equals(redirect.getHost()))&&redirect.getPort()>0))
                throw new SecurityException("Callback requires registered HTTPS App Link or bound loopback listener");
            this.redirectUri=redirectUri;
            if(registration==Registration.CIMD){URI id=McpOAuthMetadata.https(clientId);if(id.getRawPath()==null||id.getRawPath().isEmpty()||id.getRawPath().equals("/"))throw new IllegalArgumentException("CIMD requires an existing HTTPS metadata document path");}
        }
    }
    /** Native-only launch object. Never put its URL (state) in task logs or model context. */
    public static final class AuthorizationRequest {
        public final String flowId,authorizationUrl,resource,issuer,scopes;public final long expiresAt;
        private AuthorizationRequest(String flowId,String authorizationUrl,String resource,String issuer,String scopes,long expiresAt){this.flowId=flowId;this.authorizationUrl=authorizationUrl;this.resource=resource;this.issuer=issuer;this.scopes=scopes;this.expiresAt=expiresAt;}
    }
    public static final class CredentialRef {
        public final String reference,resource,issuer;public final long expiresAt;
        private CredentialRef(String reference,String resource,String issuer,long expiresAt){this.reference=reference;this.resource=resource;this.issuer=issuer;this.expiresAt=expiresAt;}
    }
    /** Native host checkpoints on its ORIGINAL encrypted configuration, not a task grant or a second flow. */
    public interface Checkpoints {void flowPrepared(Binding binding,String originalFlowId)throws Exception;void credentialPrepared(Binding binding,String reference,long expiresAt)throws Exception;}
    private static final Checkpoints NO_CHECKPOINTS=new Checkpoints(){public void flowPrepared(Binding binding,String flow){}public void credentialPrepared(Binding binding,String ref,long expires){}};
    private final Trust trust;private final EncryptedSecrets secrets;private final Http http;private final SecureRandom random;private final LongSupplier clock;private final Checkpoints checkpoints;
    public McpOAuth(Trust trust,EncryptedSecrets secrets,Http http){this(trust,secrets,http,new SecureRandom(),System::currentTimeMillis,NO_CHECKPOINTS);}
    public McpOAuth(Trust trust,EncryptedSecrets secrets,Http http,Checkpoints checkpoints){this(trust,secrets,http,new SecureRandom(),System::currentTimeMillis,checkpoints);}
    public McpOAuth(Trust trust,EncryptedSecrets secrets,Http http,SecureRandom random,LongSupplier clock){this(trust,secrets,http,random,clock,NO_CHECKPOINTS);}
    private McpOAuth(Trust trust,EncryptedSecrets secrets,Http http,SecureRandom random,LongSupplier clock,Checkpoints checkpoints){
        this.checkpoints=Objects.requireNonNull(checkpoints);this.trust=Objects.requireNonNull(trust);this.secrets=Objects.requireNonNull(secrets);this.http=Objects.requireNonNull(http);this.random=Objects.requireNonNull(random);this.clock=Objects.requireNonNull(clock);
        if(!secrets.encryptedAtRest())throw new SecurityException("Encrypted credential port required");
    }
    public Discovery discover(Binding binding,List<String> authenticateHeaders,RunControl control)throws Exception {
        check(binding,control);McpOAuthMetadata.Challenge challenge=McpOAuthMetadata.challenge(authenticateHeaders);
        JSONObject metadata=fetch(binding,McpOAuthMetadata.resourceCandidates(binding.resource,challenge),Use.RESOURCE_METADATA,control);
        if(!binding.resource.equals(metadata.getString("resource")))throw new SecurityException("Protected resource identity mismatch");
        List<String> issuers=McpOAuthMetadata.strings(metadata,"authorization_servers",true);for(String issuer:issuers)McpOAuthMetadata.https(issuer);
        String scopes=challenge.scope;
        if(scopes.isEmpty())scopes=McpOAuthMetadata.scopes(String.join(" ",McpOAuthMetadata.strings(metadata,"scopes_supported",false)));
        return new Discovery(binding.fingerprint(),binding.resource,scopes,issuers);
    }
    public Server server(Binding binding,Discovery discovery,String selectedIssuer,RunControl control)throws Exception {
        check(binding,control);if(!binding.fingerprint().equals(discovery.binding)||!discovery.issuers.contains(selectedIssuer))throw new SecurityException("Issuer was not advertised for this resource");
        JSONObject metadata=fetch(binding,McpOAuthMetadata.issuerCandidates(selectedIssuer),Use.ISSUER_METADATA,control);
        if(!selectedIssuer.equals(metadata.getString("issuer")))throw new SecurityException("Authorization server issuer mismatch");
        if(!McpOAuthMetadata.strings(metadata,"code_challenge_methods_supported",true).contains("S256")||!McpOAuthMetadata.strings(metadata,"response_types_supported",true).contains("code")
                ||!McpOAuthMetadata.strings(metadata,"token_endpoint_auth_methods_supported",true).contains("none"))throw new SecurityException("Public-client code flow with S256 is required");
        if(metadata.has("grant_types_supported")&&!McpOAuthMetadata.strings(metadata,"grant_types_supported",true).contains("authorization_code"))throw new SecurityException("Authorization code grant unsupported");
        String authorize=metadata.getString("authorization_endpoint"),token=metadata.getString("token_endpoint");endpoint(binding,authorize,Use.AUTHORIZATION);endpoint(binding,token,Use.TOKEN);
        return new Server(binding.fingerprint(),selectedIssuer,authorize,token,McpJson.digest(metadata),flag(metadata,"authorization_response_iss_parameter_supported"),flag(metadata,"client_id_metadata_document_supported"));
    }
    public AuthorizationRequest begin(Binding binding,Discovery discovery,Server server,Client client,RunControl control)throws Exception {
        check(binding,control);String fingerprint=binding.fingerprint();
        if(!fingerprint.equals(discovery.binding)||!fingerprint.equals(server.binding)||!discovery.issuers.contains(server.issuer)||!client.issuer.equals(server.issuer))throw new SecurityException("OAuth installation or issuer changed");
        if(client.registration==Registration.CIMD&&!server.cimdSupported)throw new SecurityException("Authorization server did not advertise CIMD");
        trust.authorization(binding,server.issuer,client.clientId,client.redirectUri,discovery.scopes,server.metadataDigest);endpoint(binding,server.authorizationEndpoint,Use.AUTHORIZATION);
        String verifier=nonce(),state=nonce(),flowId=UUID.randomUUID().toString();long expires=Math.addExact(clock.getAsLong(),300_000);
        JSONObject pending=new JSONObject().put("binding",fingerprint).put("issuer",server.issuer).put("clientId",client.clientId).put("redirectUri",client.redirectUri).put("scope",discovery.scopes)
                .put("verifier",verifier).put("state",state).put("expiresAt",expires).put("tokenEndpoint",server.tokenEndpoint).put("resource",binding.resource)
                .put("authorizationEndpoint",server.authorizationEndpoint).put("metadataDigest",server.metadataDigest).put("requireIss",server.responseIssuerRequired);
        check(binding,control);checkpoints.flowPrepared(binding,flowId);check(binding,control);secrets.create(flowSlot(flowId),pending.toString());
        try{check(binding,control);}catch(Exception revoked){secrets.remove(flowSlot(flowId));throw revoked;}
        return launch(pending,flowId);
    }
    /** Restore the same pending browser request; this never redeems a code or creates a second flow. */
    public AuthorizationRequest resume(Binding binding,String flowId,RunControl control)throws Exception {
        check(binding,control);String raw=secrets.read(flowSlot(flowId));
        if(raw==null||raw.isEmpty())throw new SecurityException("Authorization flow unavailable");JSONObject pending=McpJson.parse(raw);
        if(!binding.fingerprint().equals(pending.getString("binding"))||!binding.resource.equals(pending.getString("resource"))||clock.getAsLong()>=pending.getLong("expiresAt"))throw new SecurityException("Authorization flow changed or expired");
        trust.authorization(binding,pending.getString("issuer"),pending.getString("clientId"),pending.getString("redirectUri"),pending.getString("scope"),pending.getString("metadataDigest"));
        endpoint(binding,pending.getString("authorizationEndpoint"),Use.AUTHORIZATION);check(binding,control);return launch(pending,flowId);
    }
    public CredentialRef complete(Binding binding,String flowId,String callbackUri,RunControl control)throws Exception {
        check(binding,control);String raw=secrets.take(flowSlot(flowId)); // Durable one-use fence also survives process restart.
        if(raw==null||raw.isEmpty())throw new SecurityException("Authorization flow was cancelled, consumed or lost");
        JSONObject pending=McpJson.parse(raw);
        if(!binding.fingerprint().equals(pending.getString("binding"))||!binding.resource.equals(pending.getString("resource")))throw new SecurityException("Authorization installation changed");
        if(clock.getAsLong()>=pending.getLong("expiresAt"))throw new SecurityException("Authorization state expired");
        URI callback=new URI(callbackUri);
        if(callbackUri.length()>16384||callback.getRawFragment()!=null||callback.getRawQuery()==null||!callbackUri.substring(0,callbackUri.indexOf('?')).equals(pending.getString("redirectUri")))throw new SecurityException("Callback URI mismatch");
        Map<String,String> response=query(callback.getRawQuery());
        if(!constant(pending.getString("state"),response.get("state")))throw new SecurityException("Authorization state mismatch");
        String issuer=pending.getString("issuer");
        if(pending.getBoolean("requireIss")&&!response.containsKey("iss")||response.containsKey("iss")&&!issuer.equals(response.get("iss")))throw new SecurityException("Authorization response issuer mismatch");
        if(response.containsKey("resource")&&!binding.resource.equals(response.get("resource")))throw new SecurityException("Authorization response resource mismatch");
        if(response.containsKey("error"))throw new IllegalStateException("Authorization was declined or failed"); // Never display unvalidated remote error data.
        String code=text(response.get("code"),4096);
        trust.authorization(binding,issuer,pending.getString("clientId"),pending.getString("redirectUri"),pending.getString("scope"),pending.getString("metadataDigest"));
        String tokenEndpoint=pending.getString("tokenEndpoint");endpoint(binding,tokenEndpoint,Use.TOKEN);check(binding,control);
        Map<String,String> params=new LinkedHashMap<>();params.put("grant_type","authorization_code");params.put("code",code);params.put("code_verifier",pending.getString("verifier"));
        params.put("client_id",pending.getString("clientId"));params.put("redirect_uri",pending.getString("redirectUri"));params.put("resource",binding.resource);
        Reply reply=http.request(tokenEndpoint,params,control);check(binding,control);
        if(reply.status!=200||reply.json.has("error"))throw new IllegalStateException("Token exchange failed; code will not be retried");
        JSONObject token=reply.json;
        if(!"Bearer".equalsIgnoreCase(token.getString("token_type")))throw new SecurityException("Unsupported token type");
        if(!(token.get("access_token") instanceof String))throw new SecurityException("Invalid access token type");
        String access=token(token.getString("access_token"));
        if(token.has("resource")&&!binding.resource.equals(token.getString("resource"))||token.has("iss")&&!issuer.equals(token.getString("iss")))throw new SecurityException("Token identity mismatch");
        if(!token.has("expires_in"))throw new SecurityException("Token expiry is required by this client");
        long seconds=McpJson.interval(token.get("expires_in"),0);if(seconds<1||seconds>31_536_000)throw new SecurityException("Unsupported token expiry");
        String scopes=token.has("scope")?McpOAuthMetadata.scopes(token.getString("scope")):pending.getString("scope");
        List<String> allowed=java.util.Arrays.asList(pending.getString("scope").split(" "));
        if(!scopes.isEmpty())for(String scope:scopes.split(" "))if(!allowed.contains(scope))throw new SecurityException("Token scope exceeds reviewed request");
        long expires=Math.addExact(clock.getAsLong(),Math.multiplyExact(seconds,1000));String reference="mcp-oauth-token:"+UUID.randomUUID();
        JSONObject credential=new JSONObject().put("binding",binding.fingerprint()).put("issuer",issuer).put("resource",binding.resource).put("clientId",pending.getString("clientId"))
                .put("accessToken",access).put("expiresAt",expires).put("scope",scopes).put("metadataDigest",pending.getString("metadataDigest"));
        // Refresh is deliberately not performed; do not retain unused refresh tokens.
        checkpoints.credentialPrepared(binding,reference,expires);check(binding,control);secrets.create(reference,credential.toString());
        try{check(binding,control);}catch(Exception revoked){secrets.remove(reference);throw revoked;}
        return new CredentialRef(reference,binding.resource,issuer,expires);
    }
    /** Native transport only. Never return this string to UI, model, task evidence or diagnostics. */
    public String accessToken(Binding binding,String reference,String expectedIssuer,String clientId,String exactEndpoint,RunControl control)throws Exception {
        check(binding,control);if(!reference.matches("mcp-oauth-token:[a-f0-9-]{36}")||!binding.resource.equals(exactEndpoint))throw new SecurityException("Credential endpoint mismatch");
        String raw=secrets.read(reference);if(raw==null||raw.isEmpty())throw new SecurityException("Credential unavailable");JSONObject saved=McpJson.parse(raw);
        if(!binding.fingerprint().equals(saved.getString("binding"))||!binding.resource.equals(saved.getString("resource"))||!expectedIssuer.equals(saved.getString("issuer"))||!clientId.equals(saved.getString("clientId")))throw new SecurityException("Credential identity mismatch");
        if(clock.getAsLong()>=saved.getLong("expiresAt"))throw new SecurityException("Credential expired; explicit reauthorization required");
        check(binding,control);return token(saved.getString("accessToken"));
    }
    /** Native owner cancellation, no network. It does not revoke already issued access tokens. */
    public void cancelFlow(String flowId)throws Exception{secrets.remove(flowSlot(flowId));}
    public void forgetCredential(String reference)throws Exception{if(!reference.matches("mcp-oauth-token:[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid credential reference");secrets.remove(reference);}
    private JSONObject fetch(Binding binding,List<String> candidates,Use use,RunControl control)throws Exception {
        for(String candidate:candidates){check(binding,control);endpoint(binding,candidate,use);Reply reply=http.request(candidate,null,control);check(binding,control);
            if(reply.status==404)continue;if(reply.status!=200)throw new IllegalStateException("OAuth metadata request rejected");
            if(reply.json.toString().length()>65536)throw new IllegalArgumentException("OAuth metadata limit");return reply.json;
        }throw new IllegalStateException("OAuth metadata unavailable");
    }
    private void endpoint(Binding binding,String url,Use use)throws Exception{McpOAuthMetadata.https(url);trust.endpoint(binding,url,use);}
    private void check(Binding binding,RunControl control)throws Exception{control.check();trust.check(binding);if(!secrets.encryptedAtRest())throw new SecurityException("Encrypted credential port unavailable");}
    private String nonce(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static AuthorizationRequest launch(JSONObject pending,String flowId)throws Exception {
        Map<String,String> params=new LinkedHashMap<>();params.put("response_type","code");params.put("client_id",pending.getString("clientId"));params.put("redirect_uri",pending.getString("redirectUri"));
        params.put("code_challenge",Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(pending.getString("verifier").getBytes(StandardCharsets.US_ASCII))));
        params.put("code_challenge_method","S256");params.put("state",pending.getString("state"));params.put("resource",pending.getString("resource"));if(!pending.getString("scope").isEmpty())params.put("scope",pending.getString("scope"));
        return new AuthorizationRequest(flowId,pending.getString("authorizationEndpoint")+"?"+form(params),pending.getString("resource"),pending.getString("issuer"),pending.getString("scope"),pending.getLong("expiresAt"));
    }
    private static boolean flag(JSONObject source,String name)throws Exception{if(!source.has(name))return false;if(!(source.get(name) instanceof Boolean))throw new IllegalArgumentException("Invalid metadata boolean");return source.getBoolean(name);}
    private static String flowSlot(String id){if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid authorization flow");return "mcp-oauth-flow:"+id;}
    private static String text(String value,int limit){if(value==null||value.isEmpty()||value.length()>limit||value.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("Invalid OAuth identity");return value;}
    private static String token(String value){text(value,4096);if(!value.chars().allMatch(c->c>=0x21&&c<=0x7e))throw new IllegalArgumentException("Invalid Bearer token");return value;}
    private static boolean constant(String expected,String actual){return actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));}
    static String form(Map<String,String> values)throws Exception{StringBuilder out=new StringBuilder();for(Map.Entry<String,String> value:values.entrySet()){if(out.length()>0)out.append('&');out.append(URLEncoder.encode(value.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(value.getValue(),"UTF-8"));}return out.toString();}
    static Map<String,String> query(String raw)throws Exception {
        Map<String,String> out=new LinkedHashMap<>();for(String part:raw.split("&",-1)){
            int equal=part.indexOf('=');if(equal<1)throw new SecurityException("Invalid OAuth response parameter");String key=decode(part.substring(0,equal)),value=decode(part.substring(equal+1));
            if(out.containsKey(key))throw new SecurityException("Duplicate OAuth response parameter");out.put(key,value);
        }return out;
    }
    private static String decode(String value)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(c=='%'){if(i+2>=value.length())throw new SecurityException("Invalid response encoding");int a=Character.digit(value.charAt(++i),16),b=Character.digit(value.charAt(++i),16);if(a<0||b<0)throw new SecurityException("Invalid response encoding");bytes.write((a<<4)|b);}
            else if(c=='+')bytes.write(' ');else {if(c>127)throw new SecurityException("Response must be URI encoded");bytes.write(c);}
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
}
