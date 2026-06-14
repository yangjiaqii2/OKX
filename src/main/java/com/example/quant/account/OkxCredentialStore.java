package com.example.quant.account;

import java.util.Optional;

public interface OkxCredentialStore {
    Optional<StoredOkxCredential> findActive();

    void saveActive(StoredOkxCredential credential);

    void deleteActive();
}
