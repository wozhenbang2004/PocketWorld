package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.store.SecretVault;

/** Reuses the host's encrypted private storage; does not create a second keystore. */
public final class SecretVaultAdapter implements CredentialVault {
    private final SecretVault vault;
    public SecretVaultAdapter(SecretVault vault){this.vault=java.util.Objects.requireNonNull(vault);}
    public String get(String slot)throws Exception{return vault.get(slot);}
    public void put(String slot,String value)throws Exception{vault.put(slot,value);}
    public void remove(String slot)throws Exception{vault.remove(slot);}
}
