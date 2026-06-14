package com.example.quant.account;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaOkxCredentialStore implements OkxCredentialStore {
    private static final String LOCAL_USER = "local-admin";

    private final JpaOkxCredentialRepository repository;

    public JpaOkxCredentialStore(JpaOkxCredentialRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StoredOkxCredential> findActive() {
        return repository.findFirstByActiveTrueOrderByUpdatedAtDesc()
                .map(entity -> new StoredOkxCredential(
                        entity.getApiKeyEncoded(),
                        entity.getSecretEncoded(),
                        entity.getPassphraseEncoded(),
                        entity.getMaskedApiKey()
                ));
    }

    @Override
    @Transactional
    public void saveActive(StoredOkxCredential credential) {
        deleteActive();
        Instant now = Instant.now();
        JpaOkxCredentialEntity entity = new JpaOkxCredentialEntity();
        entity.setUserName(LOCAL_USER);
        entity.setApiKeyEncoded(credential.encodedApiKey());
        entity.setSecretEncoded(credential.encodedSecret());
        entity.setPassphraseEncoded(credential.encodedPassphrase());
        entity.setMaskedApiKey(credential.maskedApiKey());
        entity.setActive(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.save(entity);
    }

    @Override
    @Transactional
    public void deleteActive() {
        repository.findAll().stream()
                .filter(JpaOkxCredentialEntity::isActive)
                .forEach(entity -> {
                    entity.setActive(false);
                    entity.setUpdatedAt(Instant.now());
                    repository.save(entity);
                });
    }
}
