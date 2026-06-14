package com.example.quant.account;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class Base64OkxCredentialCodec implements OkxCredentialCodec {
    @Override
    public String encode(String value) {
        return Base64.getEncoder().encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
