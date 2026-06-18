package com.example.quant.account;

import com.example.quant.account.dto.OkxAccountBindRequest;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkxAccountBindingServiceTest {
    @Test
    void bindsCredentialsIntoStoreAndReturnsMaskedStatus() {
        InMemoryOkxCredentialStore store = new InMemoryOkxCredentialStore();
        OkxAccountBindingService service = newService(store);

        var status = service.bind(new OkxAccountBindRequest(
                "abc123456789",
                "secret-value",
                "passphrase-value"
        ));

        assertTrue(status.bound());
        assertEquals("abc1****6789", status.maskedApiKey());
        assertFalse(status.liveTradingEnabled());
        assertTrue(store.findActive().isPresent());
    }

    @Test
    void rejectsBlankCredentialFields() {
        OkxAccountBindingService service = newService(new InMemoryOkxCredentialStore());

        assertThrows(IllegalArgumentException.class, () -> service.bind(new OkxAccountBindRequest(
                "abc123456789",
                " ",
                "passphrase-value"
        )));
    }

    @Test
    void credentialsSurviveNewServiceInstanceReadingSameStore() {
        InMemoryOkxCredentialStore store = new InMemoryOkxCredentialStore();
        newService(store).bind(new OkxAccountBindRequest("abc123456789", "secret-value", "passphrase-value"));

        OkxAccountBindingService restartedService = newService(store);

        assertTrue(restartedService.status().bound());
        assertEquals("abc1****6789", restartedService.status().maskedApiKey());
        assertEquals(new OkxAccountBindingService.OkxCredentials(
                "abc123456789",
                "secret-value",
                "passphrase-value"
        ), restartedService.credentials().orElseThrow());
    }

    @Test
    void bindsCredentialsOnlyForCurrentUser() {
        InMemoryOkxCredentialStore store = new InMemoryOkxCredentialStore();
        OkxAccountBindingService service = newService(store);

        AuthUserContext.runAs("alice", () -> service.bind(new OkxAccountBindRequest(
                "alice123456789",
                "alice-secret",
                "alice-passphrase"
        )));

        AuthUserContext.runAs("bob", () -> {
            assertFalse(service.status().bound());
            assertFalse(service.credentials().isPresent());
        });
        AuthUserContext.runAs("alice", () -> {
            assertTrue(service.status().bound());
            assertEquals("alic****6789", service.status().maskedApiKey());
            assertEquals("alice123456789", service.credentials().orElseThrow().apiKey());
        });
    }

    @Test
    void unbindClearsStoredCredentials() {
        InMemoryOkxCredentialStore store = new InMemoryOkxCredentialStore();
        OkxAccountBindingService service = newService(store);
        service.bind(new OkxAccountBindRequest("abc123456789", "secret-value", "passphrase-value"));

        var status = service.unbind();

        assertFalse(status.bound());
        assertEquals("", status.maskedApiKey());
        assertFalse(service.credentials().isPresent());
        assertFalse(store.findActive().isPresent());
    }

    private static OkxAccountBindingService newService(OkxCredentialStore store) {
        return new OkxAccountBindingService(
                new TradingProperties("SEMI_AUTO", true, false, 120, false),
                store
        );
    }

    private static final class InMemoryOkxCredentialStore implements OkxCredentialStore {
        private final Map<String, StoredOkxCredential> rows = new HashMap<>();

        @Override
        public Optional<StoredOkxCredential> findActive(String username) {
            return Optional.ofNullable(rows.get(username));
        }

        @Override
        public void saveActive(String username, StoredOkxCredential credential) {
            rows.put(username, credential);
        }

        @Override
        public void deleteActive(String username) {
            rows.remove(username);
        }
    }
}
