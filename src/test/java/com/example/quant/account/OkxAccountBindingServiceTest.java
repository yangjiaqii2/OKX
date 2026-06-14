package com.example.quant.account;

import com.example.quant.account.dto.OkxAccountBindRequest;
import com.example.quant.config.TradingProperties;
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
        private StoredOkxCredential credential;

        @Override
        public Optional<StoredOkxCredential> findActive() {
            return Optional.ofNullable(credential);
        }

        @Override
        public void saveActive(StoredOkxCredential credential) {
            this.credential = credential;
        }

        @Override
        public void deleteActive() {
            this.credential = null;
        }
    }
}
