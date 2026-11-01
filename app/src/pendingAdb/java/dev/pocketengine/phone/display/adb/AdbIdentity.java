package dev.pocketengine.phone.display.adb;

import dev.pocketengine.store.SecretVault;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONObject;

/** Main-process-only encrypted software RSA identity; no pairing code is ever stored. */
final class AdbIdentity {
    private static final String SLOT="display.adb.identity.v1";
    private final SecretVault secrets;
    AdbIdentity(SecretVault secrets){this.secrets=secrets;}
    boolean exists(){return secrets.contains(SLOT);}
    Material getOrCreate()throws Exception {
        if(exists()){
            JSONObject saved=new JSONObject(secrets.get(SLOT));byte[] key=Base64.getDecoder().decode(saved.getString("privateKey")),cert=Base64.getDecoder().decode(saved.getString("certificate"));
            try{return decode(key,cert);}finally{java.util.Arrays.fill(key,(byte)0);java.util.Arrays.fill(cert,(byte)0);}
        }
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048,new SecureRandom());KeyPair pair=generator.generateKeyPair();
        byte[] encoded=pair.getPrivate().getEncoded();if(encoded==null)throw new IllegalStateException("当前平台未提供可用于ADB认证的软件RSA密钥");
        long now=System.currentTimeMillis();BouncyCastleProvider provider=new BouncyCastleProvider();X500Name subject=new X500Name("CN=Pocket World Android ADB");
        java.security.cert.X509Certificate certificate=new JcaX509CertificateConverter().setProvider(provider).getCertificate(
                new JcaX509v3CertificateBuilder(subject,new BigInteger(128,new SecureRandom()).add(BigInteger.ONE),new Date(now-86400000L),new Date(now+315360000000L),subject,pair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.getPrivate())));
        byte[] cert=certificate.getEncoded();JSONObject saved=new JSONObject().put("privateKey",Base64.getEncoder().encodeToString(encoded)).put("certificate",Base64.getEncoder().encodeToString(cert));
        // One encrypted slot commits the pair together; an interrupted save cannot mix two identities.
        try{secrets.put(SLOT,saved.toString());return decode(encoded,cert);}finally{java.util.Arrays.fill(encoded,(byte)0);java.util.Arrays.fill(cert,(byte)0);}
    }
    void forget()throws Exception {secrets.remove(SLOT);}
    static Material decode(byte[] privateKey,byte[] certificate)throws Exception {
        if(privateKey==null||certificate==null||privateKey.length>16384||certificate.length>16384)throw new SecurityException("ADB身份数据无效");
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKey));
        Certificate cert=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));
        if(!(key instanceof RSAPrivateKey)||!(cert.getPublicKey() instanceof RSAPublicKey)||!((RSAPrivateKey)key).getModulus().equals(((RSAPublicKey)cert.getPublicKey()).getModulus()))throw new SecurityException("ADB私钥与证书不匹配，需由主人明确重置");
        return new Material(privateKey.clone(),certificate.clone());
    }
    static final class Material implements AutoCloseable {
        final byte[] key,certificate;
        Material(byte[] key,byte[] certificate){this.key=key;this.certificate=certificate;}
        @Override public void close(){java.util.Arrays.fill(key,(byte)0);java.util.Arrays.fill(certificate,(byte)0);}
    }
}
