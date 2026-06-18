package com.example.quant.account;

import com.example.quant.account.dto.OkxAccountBindRequest;
import com.example.quant.account.dto.OkxAccountBindingStatus;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OkxAccountBindingService {
    private final boolean liveTradingEnabled;
    private final OkxCredentialStore credentialStore;
    private final OkxCredentialCodec credentialCodec;

    OkxAccountBindingService(TradingProperties tradingProperties, OkxCredentialStore credentialStore) {
        this.liveTradingEnabled = tradingProperties.okxLiveEnabled();
        this.credentialStore = credentialStore;
        this.credentialCodec = new Base64OkxCredentialCodec();
    }

    @Autowired
    public OkxAccountBindingService(TradingProperties tradingProperties, OkxCredentialStore credentialStore,
                                    OkxCredentialCodec credentialCodec) {
        this.liveTradingEnabled = tradingProperties.okxLiveEnabled();
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
        return status();
    }

    public OkxAccountBindingStatus status() {
        return credentialStore.findActive(currentUsername())
                .map(value -> new OkxAccountBindingStatus(true, value.maskedApiKey(), liveTradingEnabled))
                .orElseGet(() -> OkxAccountBindingStatus.unbound(liveTradingEnabled));
    }

    public OkxAccountBindingStatus unbind() {
        credentialStore.deleteActive(currentUsername());
        return status();
    }

    public Optional<OkxCredentials> credentials() {
        return credentialStore.findActive(currentUsername())
                .map(value -> new OkxCredentials(
                        credentialCodec.decode(value.encodedApiKey()),
                        credentialCodec.decode(value.encodedSecret()),
                        credentialCodec.decode(value.encodedPassphrase())
                ));
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
