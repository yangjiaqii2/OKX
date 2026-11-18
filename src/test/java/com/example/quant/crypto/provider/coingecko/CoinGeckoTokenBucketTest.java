package com.example.quant.crypto.provider.coingecko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CoinGeckoTokenBucketTest {

    @Test
    void newBucketStartsFull() {
        // 20 rpm 桶初始 = 20 tokens
        CoinGeckoTokenBucket bucket = new CoinGeckoTokenBucket(20);
        assertThat(bucket.available()).isEqualTo(20.0);
    }

    @Test
    void tryAcquireDrainsTokensOneByOne() {
        // 5 rpm 桶，5 次 tryAcquire 都应成功，第 6 次必须 false
        CoinGeckoTokenBucket bucket = new CoinGeckoTokenBucket(5);
        for (int i = 0; i < 5; i++) {
            assertThat(bucket.tryAcquire()).as("attempt " + (i + 1)).isTrue();
        }
        assertThat(bucket.tryAcquire()).as("6th should fail").isFalse();
    }

    @Test
    void rejectZeroOrNegativeRate() {
        assertThatThrownBy(() -> new CoinGeckoTokenBucket(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ratePerMinute");
        assertThatThrownBy(() -> new CoinGeckoTokenBucket(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refillIsThrottledByConfiguredRate() throws InterruptedException {
        // 60 rpm = 1 token/秒；等待 1.2 秒应至少补充 1 token
        CoinGeckoTokenBucket bucket = new CoinGeckoTokenBucket(60);
        // 耗尽
        while (bucket.tryAcquire()) { /* drain */ }
        assertThat(bucket.tryAcquire()).isFalse();
        Thread.sleep(1_200);
        assertThat(bucket.tryAcquire()).as("after 1.2s of 60rpm, should refill >=1").isTrue();
    }

    @Test
    void capacityIsCappedAtRatePerMinute() throws InterruptedException {
        // 100 rpm 桶：即便 idle 10 秒也不应超过 100 tokens
        CoinGeckoTokenBucket bucket = new CoinGeckoTokenBucket(100);
        Thread.sleep(500);
        assertThat(bucket.available()).isLessThanOrEqualTo(100.0);
    }
}
