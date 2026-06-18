package com.example.quant.account;

import java.util.Optional;

public interface OkxCredentialStore {
    String SYSTEM_USER = "local-admin";

    default Optional<StoredOkxCredential> findActive() {
        return findActive(SYSTEM_USER);
    }

    Optional<StoredOkxCredential> findActive(String username);

    default void saveActive(StoredOkxCredential credential) {
        saveActive(SYSTEM_USER, credential);
    }

    void saveActive(String username, StoredOkxCredential credential);

    default void deleteActive() {
        deleteActive(SYSTEM_USER);
    }

    void deleteActive(String username);
}
