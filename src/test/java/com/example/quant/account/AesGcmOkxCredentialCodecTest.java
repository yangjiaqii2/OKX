package com.example.quant.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmOkxCredentialCodecTest {
    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        AesGcmOkxCredentialCodec codec = new AesGcmOkxCredentialCodec(KEY);

        String first = codec.encode("okx-secret");
        String second = codec.encode("okx-secret");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(codec.decode(first)).isEqualTo("okx-secret");
        assertThat(codec.decode(second)).isEqualTo("okx-secret");
    }

    @Test
    void rejectsMissingAndWrongMasterKeys() {
        AesGcmOkxCredentialCodec missing = new AesGcmOkxCredentialCodec("");
        AesGcmOkxCredentialCodec writer = new AesGcmOkxCredentialCodec(KEY);
        String otherKey = Base64.getEncoder().encodeToString(
                "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> missing.encode("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUANT_CREDENTIAL_MASTER_KEY");
        assertThatThrownBy(() -> new AesGcmOkxCredentialCodec(otherKey).decode(writer.encode("secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void readsLegacyBase64OnlyWhenMasterKeyIsConfigured() {
        String legacy = Base64.getEncoder().encodeToString("legacy-secret".getBytes(StandardCharsets.UTF_8));

        assertThat(new AesGcmOkxCredentialCodec(KEY).decode(legacy)).isEqualTo("legacy-secret");
        assertThatThrownBy(() -> new AesGcmOkxCredentialCodec("").decode(legacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUANT_CREDENTIAL_MASTER_KEY");
    }
}
