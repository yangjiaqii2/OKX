package com.example.quant.account;

public interface OkxCredentialCodec {
    String encode(String value);

    String decode(String value);
}
