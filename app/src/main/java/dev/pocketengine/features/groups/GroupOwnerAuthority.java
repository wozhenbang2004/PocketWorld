package dev.pocketengine.features.groups;

/** Supplied by the native owner route. Never populated from feature/message JSON. */
public interface GroupOwnerAuthority {
    void check() throws Exception;
}
