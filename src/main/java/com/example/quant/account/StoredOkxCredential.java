package com.example.quant.account;

public record StoredOkxCredential(
        String encodedApiKey,
        String encodedSecret,
        String encodedPassphrase,
        String maskedApiKey
) {
}
