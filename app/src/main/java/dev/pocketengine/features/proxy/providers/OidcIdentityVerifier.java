package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Narrow RS256 OIDC verifier using platform cryptography. Unsupported algorithms fail closed. */
final class OidcIdentityVerifier {
    static final class Identity {
        final String issuer,subject,email;
        Identity(String issuer,String subject,String email){this.issuer=issuer;this.subject=subject;this.email=email;}
    }
    static Identity verify(String token,JSONObject jwks,String issuer,String clientId,String nonce,String access,long now)throws GatewayFailure {
        try{
            if(token.length()>16_384)throw new Exception();String[] parts=token.split("\\.",-1);if(parts.length!=3)throw new Exception();
            JSONObject header=new JSONObject(decodeText(parts[0]));
            if(!"RS256".equals(header.getString("alg"))||header.has("crit")||header.has("jku")||header.has("jwk")||header.has("x5u"))throw new Exception();
            String kid=header.getString("kid");if(kid.isEmpty()||kid.length()>160)throw new Exception();
            JSONArray keys=jwks.getJSONArray("keys");if(keys.length()>32)throw new Exception();JSONObject selected=null;
            for(int i=0;i<keys.length();i++){
                JSONObject key=keys.getJSONObject(i);if(!kid.equals(key.optString("kid")))continue;if(selected!=null)throw new Exception();selected=key;
            }
            if(selected==null||!"RSA".equals(selected.optString("kty"))||!"sig".equals(selected.optString("use","sig"))||!"RS256".equals(selected.optString("alg","RS256")))throw new Exception();
            JSONArray operations=selected.optJSONArray("key_ops");if(operations!=null&&(operations.length()!=1||!"verify".equals(operations.getString(0))))throw new Exception();
            BigInteger modulus=new BigInteger(1,decode(selected.getString("n"))),exponent=new BigInteger(1,decode(selected.getString("e")));
            if(modulus.bitLength()<2048||modulus.bitLength()>4096||!exponent.equals(BigInteger.valueOf(65537)))throw new Exception();
            Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus,exponent)));
            signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));if(!signature.verify(decode(parts[2])))throw new Exception();
            JSONObject claims=new JSONObject(decodeText(parts[1]));
            if(!issuer.equals(claims.getString("iss")))throw new Exception();
            Object audience=claims.get("aud");boolean audienceMatches=clientId.equals(audience);
            if(audience instanceof JSONArray){JSONArray list=(JSONArray)audience;for(int i=0;i<list.length();i++)audienceMatches|=clientId.equals(list.getString(i));if(list.length()>1&&!clientId.equals(claims.optString("azp")))throw new Exception();}
            if(!audienceMatches||(claims.has("azp")&&!clientId.equals(claims.getString("azp"))))throw new Exception();
            long seconds=now/1000;long expires=claims.getLong("exp"),issued=claims.getLong("iat");
            if(expires<=seconds-5||issued>seconds+5||issued<0||expires<=issued||(claims.has("nbf")&&claims.getLong("nbf")>seconds+5))throw new Exception();
            if(nonce!=null&&!constant(nonce,claims.getString("nonce")))throw new Exception();
            String subject=claims.getString("sub"),email=claims.optString("email","");if(subject.isEmpty()||subject.length()>256||email.length()>320)throw new Exception();
            if(claims.has("at_hash")){
                byte[] hash=MessageDigest.getInstance("SHA-256").digest(access.getBytes(StandardCharsets.US_ASCII));
                if(!constant(Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(hash,hash.length/2)),claims.getString("at_hash")))throw new Exception();
            }
            return new Identity(issuer,subject,email);
        }catch(Exception invalid){throw new GatewayFailure("INVALID_IDENTITY_TOKEN");}
    }
    static boolean constant(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static byte[] decode(String value)throws Exception{if(value.isEmpty()||!value.matches("[A-Za-z0-9_-]+"))throw new Exception();return Base64.getUrlDecoder().decode(value);}
    private static String decodeText(String value)throws Exception{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(decode(value))).toString();}
}
