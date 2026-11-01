package dev.pocketengine.features.proxy.accounts;

/** Adapter seam for the existing SecretVault, and for a fault-injectable test vault. */
public interface CredentialVault {
    String get(String slot)throws Exception;
    void put(String slot,String value)throws Exception;
    void remove(String slot)throws Exception;
}
