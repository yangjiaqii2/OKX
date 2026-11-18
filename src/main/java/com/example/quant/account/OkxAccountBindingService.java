package com.example.quant.account;

import com.example.quant.account.dto.OkxAccountBindRequest;
import com.example.quant.account.dto.OkxAccountBindingStatus;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import com.example.quant.config.TradingMode;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class OkxAccountBindingService {
    private final TradingMode tradingMode;
    private final OkxCredentialStore credentialStore;
    private final OkxCredentialCodec credentialCodec;
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    public OkxAccountBindingService(TradingProperties tradingProperties, OkxCredentialStore credentialStore,
                                    OkxCredentialCodec credentialCodec) {
        this.tradingMode = tradingProperties.mode();
        this.credentialStore = credentialStore;
        this.credentialCodec = credentialCodec;
    }

    public OkxAccountBindingStatus bind(OkxAccountBindRequest request) {
        validate(request);
        String apiKey = request.apiKey().trim();
        credentialStore.saveActive(currentUsername(), new StoredOkxCredential(
                credentialCodec.encode(apiKey),
                credentialCodec.encode(request.secret().trim()),
                credentialCodec.encode(request.passphrase().trim()),
                mask(apiKey)
        ));
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new OkxAccountBoundEvent(currentUsername()));
        }
        return status();
    }

    @Autowired(required = false)
    void configureEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public OkxAccountBindingStatus status() {
        return credentialStore.findActive(currentUsername())
                .map(value -> new OkxAccountBindingStatus(true, value.maskedApiKey(), tradingMode))
                .orElseGet(() -> OkxAccountBindingStatus.unbound(tradingMode));
    }

    public OkxAccountBindingStatus unbind() {
        credentialStore.deleteActive(currentUsername());
        return status();
    }

    public Optional<OkxCredentials> credentials() {
        return credentialStore.findActive(currentUsername())
                .map(this::decodeAndMigrate);
    }

    private OkxCredentials decodeAndMigrate(StoredOkxCredential value) {
        OkxCredentials credentials = new OkxCredentials(
                credentialCodec.decode(value.encodedApiKey()),
                credentialCodec.decode(value.encodedSecret()),
                credentialCodec.decode(value.encodedPassphrase())
        );
        if (!value.encodedApiKey().startsWith("v1:")
                || !value.encodedSecret().startsWith("v1:")
                || !value.encodedPassphrase().startsWith("v1:")) {
            credentialStore.saveActive(currentUsername(), new StoredOkxCredential(
                    credentialCodec.encode(credentials.apiKey()),
                    credentialCodec.encode(credentials.secret()),
                    credentialCodec.encode(credentials.passphrase()),
                    value.maskedApiKey()
            ));
        }
        return credentials;
    }

    private static void validate(OkxAccountBindRequest request) {
        Objects.requireNonNull(request, "绑定请求不能为空");
        requireText(request.apiKey(), "API Key不能为空");
        requireText(request.secret(), "Secret不能为空");
        requireText(request.passphrase(), "Passphrase不能为空");
    }

    private static void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String mask(String apiKey) {
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    public record OkxCredentials(String apiKey, String secret, String passphrase) {
    }
}
