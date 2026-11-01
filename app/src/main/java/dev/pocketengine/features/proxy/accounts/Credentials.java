package dev.pocketengine.features.proxy.accounts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Base64;

/** Internal native credential value. Not a bridge DTO; never serialize this to JSON or diagnostics. */
public final class Credentials {
    private final String access, refresh, identity;
    public final String scopes;
    public final long expiresAt;
    public Credentials(String access,String refresh,String identity,String scopes,long expiresAt)throws GatewayFailure {
        if(access==null||access.isEmpty()||access.length()>16_384||refresh==null||refresh.length()>16_384||identity==null||identity.length()>16_384||scopes==null||scopes.length()>2048||expiresAt<=0)
            throw new GatewayFailure("INVALID_TOKEN_RESPONSE");
        this.access=access;this.refresh=refresh;this.identity=identity;this.scopes=scopes;this.expiresAt=expiresAt;
    }
    public String accessToken(){return access;}
    public String refreshToken(){return refresh;}
    public String idToken(){return identity;}
    public boolean permitsInference(){return java.util.Arrays.asList(scopes.split(" +")).contains("chatgpt.tokens.use.direct");}
    String encode()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(1);out.writeUTF(access);out.writeUTF(refresh);out.writeUTF(identity);out.writeUTF(scopes);out.writeLong(expiresAt);}
        String result=Base64.getEncoder().encodeToString(bytes.toByteArray());
        if(result.length()>65_536)throw new GatewayFailure("TOKEN_STORAGE_LIMIT");return result;
    }
    static Credentials decode(String value)throws GatewayFailure {
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)))){
            if(in.readInt()!=1)throw new GatewayFailure("CREDENTIALS_UNAVAILABLE");
            Credentials result=new Credentials(in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readLong());
            if(in.available()!=0)throw new GatewayFailure("CREDENTIALS_UNAVAILABLE");return result;
        }catch(Exception bad){throw new GatewayFailure("CREDENTIALS_UNAVAILABLE");}
    }
    @Override public String toString(){return "Credentials[redacted]";}
}
