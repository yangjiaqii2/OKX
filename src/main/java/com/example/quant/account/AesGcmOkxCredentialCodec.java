package com.example.quant.account;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmOkxCredentialCodec implements OkxCredentialCodec {
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmOkxCredentialCodec(@Value("${quant.security.credential-master-key:}") String configuredKey) {
        this.key = decodeKey(configuredKey);
    }

    @Override
    public String encode(String value) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            byte[] encrypted = cipher.doFinal(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("OKX凭据加密失败", ex);
        }
    }

    @Override
    public String decode(String value) {
        requireKey();
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!value.startsWith(PREFIX)) {
            try {
                return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("旧版OKX凭据格式无效", ex);
            }
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalStateException("OKX凭据密文无效");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, encrypted, 0, encrypted.length);
            return new String(cipher(Cipher.DECRYPT_MODE, iv).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("OKX凭据解密失败，请检查QUANT_CREDENTIAL_MASTER_KEY", ex);
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    private void requireKey() {
        if (key.length == 0) {
            throw new IllegalStateException("必须配置QUANT_CREDENTIAL_MASTER_KEY后才能保存或读取OKX凭据");
        }
    }

    private static byte[] decodeKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            return new byte[0];
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("key length");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("QUANT_CREDENTIAL_MASTER_KEY必须是Base64编码的32字节密钥", ex);
        }
    }
}
