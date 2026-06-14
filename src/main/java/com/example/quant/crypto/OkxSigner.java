package com.example.quant.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class OkxSigner {
    public String sign(String timestamp, String method, String requestPath, String body, String secret) {
        try {
            String payload = timestamp + method.toUpperCase() + requestPath + (body == null ? "" : body);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("OKX sign failed", ex);
        }
    }
}
