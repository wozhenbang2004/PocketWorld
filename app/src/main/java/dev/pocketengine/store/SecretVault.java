package dev.pocketengine.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted feature credentials. Slot names identify a purpose, never a credential value. */
public final class SecretVault implements SettingsStore.ApiSecrets {
    private static final Object ENTRY_LOCK=new Object();
    private static final String ALIAS="pocketengine.feature-secrets.v1";
    private final SharedPreferences prefs;
    public SecretVault(Context context){prefs=context.getSharedPreferences("feature_secrets",Context.MODE_PRIVATE);}
    public synchronized boolean contains(String slot){synchronized(ENTRY_LOCK){validate(slot);return prefs.contains(slot);}}
    public synchronized String get(String slot)throws Exception{synchronized(ENTRY_LOCK){
        validate(slot);String value=prefs.getString(slot,"");if(value.isEmpty())return "";
        String[] pieces=value.split(":",2);if(pieces.length!=2)throw new IllegalStateException("已保存的连接凭据无法读取，请重新填写");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(pieces[0],Base64.NO_WRAP)));
        cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.decode(pieces[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }}
    public synchronized void put(String slot,String plain)throws Exception{synchronized(ENTRY_LOCK){
        validate(slot);if(plain==null||plain.isEmpty()){remove(slot);return;}if(plain.length()>65_536)throw new IllegalArgumentException("连接凭据过长");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
        String value=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString(slot,value).commit())throw new IllegalStateException("无法保存连接凭据");
    }}
    public synchronized void remove(String slot)throws Exception{synchronized(ENTRY_LOCK){validate(slot);if(!prefs.edit().remove(slot).commit())throw new IllegalStateException("无法清除连接凭据");}}
    /** All original App-process Vault instances share this atomic entry lock. No plaintext or async apply. */
    public synchronized void create(String slot,String plain)throws Exception{synchronized(ENTRY_LOCK){validate(slot);if(plain==null||plain.isEmpty()||prefs.contains(slot))throw new SecurityException("凭据用途已存在或内容为空");put(slot,plain);}}
    /** Commit removal BEFORE returning a one-use secret. A failed commit never returns it for exchange. */
    public synchronized String take(String slot)throws Exception{synchronized(ENTRY_LOCK){String value=get(slot);if(!value.isEmpty())remove(slot);return value;}}
    byte[] sealTaskScreenshot(byte[] plain,byte[] aad)throws Exception{synchronized(ENTRY_LOCK){return TaskScreenshotStorage.seal(key(),plain,aad);}}
    byte[] openTaskScreenshot(byte[] encrypted,byte[] aad)throws Exception{synchronized(ENTRY_LOCK){return TaskScreenshotStorage.open(key(),encrypted,aad);}}
    private SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    private static void validate(String slot){if(slot==null||!slot.matches("[A-Za-z0-9_.:-]{1,160}"))throw new IllegalArgumentException("无效的凭据用途");}
}
