package dev.pocketengine.features.devices;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/** Public-key device identity. Private keys never leave AndroidKeyStore or enter wire JSON. */
public final class DeviceIdentity {
    private static final String ALIAS="pocketworld.device.identity.v1";
    private DeviceIdentity(){}
    public static final class AuthenticatedPeer {
        private final String deviceId,fingerprint,sessionId;
        private final byte[] certificate;
        private AuthenticatedPeer(X509Certificate certificate,String sessionId)throws Exception{this.certificate=certificate.getEncoded();deviceId=id(certificate);fingerprint=DeviceProtocol.sha256(this.certificate);this.sessionId=sessionId;}
        public String deviceId(){return deviceId;}
        public String certificateFingerprint(){return fingerprint;}
        public String sessionId(){return sessionId;}
        public byte[] certificateDer(){return certificate.clone();}
    }
    /** Server-authenticated invitation target on the ISOLATED onboarding lane, never a business peer. */
    public static final class VerifiedPairingTarget {
        private final String deviceId,fingerprint;private final byte[] certificate;
        private VerifiedPairingTarget(X509Certificate certificate)throws Exception{this.certificate=certificate.getEncoded();deviceId=id(certificate);fingerprint=DeviceProtocol.sha256(this.certificate);}
        public String deviceId(){return deviceId;}public String certificateFingerprint(){return fingerprint;}public byte[] certificateDer(){return certificate.clone();}
    }
    public static VerifiedPairingTarget verifiedPairingTarget(SSLSession session,byte[] invitationCertificateDer)throws Exception{
        if(session==null||!session.isValid()||!("TLSv1.2".equals(session.getProtocol())||"TLSv1.3".equals(session.getProtocol()))||"SSL_NULL_WITH_NULL_NULL".equals(session.getCipherSuite()))throw new SecurityException("Verified onboarding TLS required");Certificate[] chain=session.getPeerCertificates();if(chain.length!=1||!(chain[0] instanceof X509Certificate)||!java.security.MessageDigest.isEqual(chain[0].getEncoded(),invitationCertificateDer))throw new SecurityException("Onboarding target differs from the exact scanned certificate");X509Certificate certificate=(X509Certificate)chain[0];certificate.checkValidity();return new VerifiedPairingTarget(certificate);
    }
    /** Called only after the standard TLS engine has completed peer certificate authentication. */
    public static AuthenticatedPeer authenticatedPeer(SSLSession session)throws Exception{
        if(session==null||!session.isValid()||!("TLSv1.2".equals(session.getProtocol())||"TLSv1.3".equals(session.getProtocol()))||"SSL_NULL_WITH_NULL_NULL".equals(session.getCipherSuite()))throw new SecurityException("Authenticated TLS session required");
        Certificate[] local=session.getLocalCertificates();Certificate[] chain=session.getPeerCertificates();if(local==null||local.length<1||chain.length<1||!(chain[0] instanceof X509Certificate))throw new SecurityException("Mutually authenticated X509 peer required");
        // TLS 1.3 session tickets may not exist yet. This is a native handle nonce,
        // never a credential derived from an empty/reused SSLSession.getId().
        X509Certificate leaf=(X509Certificate)chain[0];leaf.checkValidity();return new AuthenticatedPeer(leaf,UUID.randomUUID().toString());
    }
    public static String id(X509Certificate certificate){requireP256(certificate);return "pw1_"+DeviceProtocol.sha256(certificate.getPublicKey().getEncoded());}
    /** Compare complete named-curve parameters, never only the EC field size. */
    public static java.security.interfaces.ECPublicKey requireP256(X509Certificate certificate){
        try{if(!(certificate.getPublicKey() instanceof java.security.interfaces.ECPublicKey)||!"1.2.840.10045.4.3.2".equals(certificate.getSigAlgOID()))throw new SecurityException("Device identity requires P-256 and SHA256withECDSA");java.security.interfaces.ECPublicKey key=(java.security.interfaces.ECPublicKey)certificate.getPublicKey();java.security.AlgorithmParameters parameters=java.security.AlgorithmParameters.getInstance("EC");parameters.init(new ECGenParameterSpec("secp256r1"));java.security.spec.ECParameterSpec expected=parameters.getParameterSpec(java.security.spec.ECParameterSpec.class),actual=key.getParams();if(actual==null||!expected.getCurve().equals(actual.getCurve())||!expected.getGenerator().equals(actual.getGenerator())||!expected.getOrder().equals(actual.getOrder())||expected.getCofactor()!=actual.getCofactor())throw new SecurityException("Device identity requires exact P-256 parameters");return key;}catch(SecurityException rejected){throw rejected;}catch(Exception invalid){throw new SecurityException("Platform P-256 identity validation unavailable",invalid);}
    }
    /** DER encoding guard only; cryptographic verification stays in the platform Signature. */
    public static void verifySignature(X509Certificate certificate,byte[] message,byte[] signature)throws Exception{
        java.security.interfaces.ECPublicKey key=requireP256(certificate);if(signature==null||signature.length<8||signature.length>72||signature[0]!=0x30||(signature[1]&255)!=signature.length-2)throw new SecurityException("Invalid canonical P-256 DER signature");int at=2;for(int part=0;part<2;part++){if(at+2>signature.length||signature[at++]!=2)throw new SecurityException("Invalid DER signature integer");int length=signature[at++]&255;if(length<1||length>33||at+length>signature.length||(signature[at]&128)!=0||length>1&&signature[at]==0&&(signature[at+1]&128)==0)throw new SecurityException("Non-canonical DER signature integer");BigInteger value=new BigInteger(Arrays.copyOfRange(signature,at,at+length));if(value.signum()<=0||value.compareTo(key.getParams().getOrder())>=0)throw new SecurityException("ECDSA integer outside P-256 order");at+=length;}if(at!=signature.length)throw new SecurityException("Trailing DER signature data");java.security.Signature verifier=java.security.Signature.getInstance("SHA256withECDSA");verifier.initVerify(key);verifier.update(message);if(!verifier.verify(signature))throw new SecurityException("Invalid device signature");
    }
    public static X509Certificate certificate(byte[] der)throws Exception{
        if(der==null||der.length<64||der.length>16384)throw new SecurityException("Invalid device certificate size");
        java.io.ByteArrayInputStream input=new java.io.ByteArrayInputStream(der);X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);if(input.available()!=0||!Arrays.equals(certificate.getEncoded(),der))throw new SecurityException("Device certificate must be one exact DER certificate");requireP256(certificate);return certificate;
    }
    /** Standard trust verification with one explicitly paired leaf plus an exact DER pin. */
    public static X509TrustManager pinnedTrustManager(byte[] expectedCertificateDer)throws Exception{
        final X509Certificate expected=certificate(expectedCertificateDer);expected.checkValidity();final byte[] pin=expected.getEncoded();
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null);trust.setCertificateEntry("paired-device",expected);
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);X509TrustManager found=null;for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)found=(X509TrustManager)manager;
        if(found==null)throw new SecurityException("Platform X509 trust manager unavailable");final X509TrustManager platform=found;
        return new X509TrustManager(){
            private void pin(X509Certificate[] chain)throws java.security.cert.CertificateException{if(chain==null||chain.length!=1||!java.security.MessageDigest.isEqual(pin,chain[0].getEncoded()))throw new java.security.cert.CertificateException("Paired device certificate changed");chain[0].checkValidity();}
            public void checkClientTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{pin(chain);platform.checkClientTrusted(chain,auth);}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{pin(chain);platform.checkServerTrusted(chain,auth);}
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[]{expected};}
        };
    }
    public static final class LocalIdentity {
        private final KeyStore store;private final X509Certificate certificate;private final String instance;
        private LocalIdentity(KeyStore store,X509Certificate certificate,String instance){this.store=store;this.certificate=certificate;this.instance=instance;}
        public String deviceId(){return id(certificate);}
        public String certificateFingerprint(){try{return DeviceProtocol.sha256(certificate.getEncoded());}catch(Exception impossible){throw new IllegalStateException(impossible);}}
        public byte[] certificateDer(){try{return certificate.getEncoded();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
        public String instanceId(){return instance;}
        public byte[] sign(byte[] bytes)throws Exception{java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign((PrivateKey)store.getKey(ALIAS,null));signer.update(bytes);return signer.sign();}
    }
    /** Explicit native onboarding action only; never called as a side effect of parsing a request. */
    public static synchronized LocalIdentity loadOrCreate(Context context)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        File instanceFile=new File(context.getNoBackupFilesDir(),"device-instance-v1");boolean created=false;
        if(!store.containsAlias(ALIAS)){
            if(instanceFile.exists())throw new SecurityException("原设备私钥已缺失；请由本人显式恢复或重建设备身份，不能静默替换旧配对");
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");long now=System.currentTimeMillis();
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).setCertificateSubject(new X500Principal("CN=PocketWorld Device")).setCertificateSerialNumber(new BigInteger(128,new java.security.SecureRandom())).setCertificateNotBefore(new Date(now-60000)).setCertificateNotAfter(new Date(now+5L*365*86400000)).setUserAuthenticationRequired(false).build());generator.generateKeyPair();
            created=true;
        }
        X509Certificate certificate=existingCertificate(store);AtomicFile file=new AtomicFile(instanceFile);String instance;
        if(instanceFile.exists())instance=readExistingInstance(instanceFile);
        else{if(!created)throw new SecurityException("原设备instance记录缺失，必须本人显式恢复，不能悄悄生成新代次");instance=UUID.randomUUID().toString();FileOutputStream output=file.startWrite();try{output.write(instance.getBytes(StandardCharsets.US_ASCII));file.finishWrite(output);}catch(Exception failure){file.failWrite(output);throw failure;}}
        return new LocalIdentity(store,certificate,instance);
    }
    /** Read-only diagnostics/startup path. Missing aliases return null; no key/instance is created. */
    public static synchronized LocalIdentity loadExisting(Context context)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias(ALIAS))return null;X509Certificate certificate=existingCertificate(store);File instance=new File(context.getNoBackupFilesDir(),"device-instance-v1");if(!instance.isFile())throw new SecurityException("已存在设备密钥但原instance缺失，需本人显式恢复");return new LocalIdentity(store,certificate,readExistingInstance(instance));
    }
    private static X509Certificate existingCertificate(KeyStore store)throws Exception{Certificate certificate=store.getCertificate(ALIAS);if(!(certificate instanceof X509Certificate)||!(store.getKey(ALIAS,null) instanceof PrivateKey))throw new SecurityException("原设备证书或不可导出私钥不可用，未重建身份");X509Certificate x509=(X509Certificate)certificate;x509.checkValidity();requireP256(x509);return x509;}
    private static String readExistingInstance(File file)throws Exception{if(java.nio.file.Files.isSymbolicLink(file.toPath()))throw new SecurityException("设备instance不能是符号链接");byte[] raw=new byte[129];int total=0;try(java.io.FileInputStream input=new java.io.FileInputStream(file)){while(total<raw.length){int count=input.read(raw,total,raw.length-total);if(count<0)break;if(count==0)continue;total+=count;}}if(total<1||total>128)throw new SecurityException("Invalid existing device instance identity");String instance=new String(raw,0,total,StandardCharsets.US_ASCII);DeviceProtocol.identifier(instance,"instanceId");return instance;}
    public static KeyManager[] keyManagers(LocalIdentity local)throws Exception{KeyManagerFactory factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(local.store,null);return factory.getKeyManagers();}
}
