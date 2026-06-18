package com.example.quant.account;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JpaOkxCredentialStore implements OkxCredentialStore {
    private final JpaOkxCredentialRepository repository;

    public JpaOkxCredentialStore(JpaOkxCredentialRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StoredOkxCredential> findActive() {
        return findActive(OkxCredentialStore.SYSTEM_USER);
    }

    @Override
    public Optional<StoredOkxCredential> findActive(String username) {
        return repository.findFirstByUserNameAndActiveTrueOrderByUpdatedAtDesc(normalizeUsername(username))
                .map(this::toStoredCredential);
    }

    @Override
    @Transactional
    public void saveActive(StoredOkxCredential credential) {
        saveActive(OkxCredentialStore.SYSTEM_USER, credential);
    }

    @Override
    @Transactional
    public void saveActive(String username, StoredOkxCredential credential) {
        String owner = normalizeUsername(username);
        deleteActive(owner);
        Instant now = Instant.now();
        JpaOkxCredentialEntity entity = new JpaOkxCredentialEntity();
        entity.setUserName(owner);
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
        deleteActive(OkxCredentialStore.SYSTEM_USER);
    }

    @Override
    @Transactional
    public void deleteActive(String username) {
        Instant now = Instant.now();
        repository.findAllByUserNameAndActiveTrue(normalizeUsername(username)).forEach(entity -> {
            entity.setActive(false);
            entity.setUpdatedAt(now);
            repository.save(entity);
        });
    }

    private StoredOkxCredential toStoredCredential(JpaOkxCredentialEntity entity) {
        return new StoredOkxCredential(
                entity.getApiKeyEncoded(),
                entity.getSecretEncoded(),
                entity.getPassphraseEncoded(),
                entity.getMaskedApiKey()
        );
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return OkxCredentialStore.SYSTEM_USER;
        }
        return username.trim();
    }
}
