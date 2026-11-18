package com.example.quant.crypto.provider.coingecko;

/**
 * Classifies request cancellation/timeout so a local scan deadline is not
 * mistaken for an upstream empty response or rate limit.
 */
final class CoinGeckoRequestException extends RuntimeException {

    enum Kind {
        INTERRUPTED,
        TIMEOUT,
        RATE_LIMITED
    }

    private final Kind kind;

    CoinGeckoRequestException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    CoinGeckoRequestException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }
}
