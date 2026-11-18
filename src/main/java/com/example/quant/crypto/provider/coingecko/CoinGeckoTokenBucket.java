package com.example.quant.crypto.provider.coingecko;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Token Bucket 速率限流（线程安全）。
 *
 * <p>配置：{@code ratePerMinute} 表示每分钟最多发请求数；桶容量 = ratePerMinute。
 * 调用 {@link #acquire()} 若有 token 则立即消费并返回；否则阻塞至 token 补齐。
 *
 * <p>设计原则：<ul>
 *   <li>本地先控速，不依赖远端 429 — 由 {@link CoinGeckoClientImpl} 在发送前 acquire，调用方不需要关心</li>
 *   <li>acquire 是阻塞的；测试场景使用 {@link #tryAcquire()} 以不阻塞</li>
 *   <li>桶按"每秒补充 (ratePerMinute/60) token"匀速补给</li>
 *   <li>使用 {@link ReentrantLock} 保护桶状态，保证多线程下的精确计数</li>
 * </ul>
 *
 * <p>线程安全：所有 public 方法线程安全。
 */
public final class CoinGeckoTokenBucket {

    private final long capacity;
    private final double refillTokensPerNano;
    private final ReentrantLock lock = new ReentrantLock();
    private double availableTokens;
    private long lastRefillNanos;

    public CoinGeckoTokenBucket(int ratePerMinute) {
        if (ratePerMinute <= 0) {
            throw new IllegalArgumentException("ratePerMinute must be > 0: " + ratePerMinute);
        }
        this.capacity = ratePerMinute;
        this.refillTokensPerNano = (double) ratePerMinute / 60_000_000_000L;
        this.availableTokens = ratePerMinute;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 测试用：可注入初始时间，避免 bucket 提前 refill 干扰断言。 */
    CoinGeckoTokenBucket(int ratePerMinute, long nowNanos) {
        if (ratePerMinute <= 0) {
            throw new IllegalArgumentException("ratePerMinute must be > 0: " + ratePerMinute);
        }
        this.capacity = ratePerMinute;
        this.refillTokensPerNano = (double) ratePerMinute / 60_000_000_000L;
        this.availableTokens = ratePerMinute;
        this.lastRefillNanos = nowNanos;
    }

    /**
     * 阻塞式获取 1 个 token。可用于生产。
     */
    public void acquire() {
        while (true) {
            long sleepNanos = acquireNanos();
            if (sleepNanos == 0L) {
                return;
            }
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 非阻塞获取：成功返回 true，桶空返回 false。测试用。
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 内部方法：尝试获取 1 token，返回需要 sleep 的纳秒数（0 表示成功）。
     */
    long acquireNanos() {
        lock.lock();
        try {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return 0L;
            }
            double need = 1.0 - availableTokens;
            long nanosToWait = (long) Math.ceil(need / refillTokensPerNano);
            return Math.max(0L, nanosToWait);
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double added = elapsed * refillTokensPerNano;
            availableTokens = Math.min(capacity, availableTokens + added);
            lastRefillNanos = now;
        }
    }

    /** 当前桶内 token 数量（测试断言用）。 */
    public double available() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }
}
