package com.wgd.wgdaiagent.security;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 定容令牌桶：容量固定，按固定速率惰性补充，不依赖后台线程或定时器。
 *
 * <p>所有可变状态都在 {@code this} 上同步；单个桶会被同一 key 的并发请求共享，
 * 但竞争只发生在同一 key 内部，不影响其它 key。</p>
 */
public final class TokenBucket {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0d;

    private final long capacity;

    private final double refillPerSecond;

    /** 注入的时钟（单调递增的纳秒），测试用它伪造时间流逝。 */
    private final LongSupplier nanoTime;

    private double tokens;

    private long lastRefillNanos;

    private long lastAccessNanos;

    public TokenBucket(long capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    public TokenBucket(long capacity, double refillPerSecond, LongSupplier nanoTime) {
        this.capacity = Math.max(1L, capacity);
        this.refillPerSecond = Math.max(0.0d, refillPerSecond);
        this.nanoTime = nanoTime;
        long now = nanoTime.getAsLong();
        this.tokens = this.capacity;
        this.lastRefillNanos = now;
        this.lastAccessNanos = now;
    }

    /**
     * 试取一个令牌：先按流逝时间补充，够 1 个则消费并返回 {@code true}。
     *
     * @return 是否放行
     */
    public synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        lastAccessNanos = now;
        refill(now);
        if (tokens >= 1.0d) {
            tokens -= 1.0d;
            return true;
        }
        return false;
    }

    /** 桶是否已满（只读语义：会补充但不会刷新访问时间，供清理策略判定）。 */
    public synchronized boolean isFull() {
        refill(nanoTime.getAsLong());
        return tokens >= capacity;
    }

    /** 距最近一次取令牌是否已超过 {@code idle}（用于回收闲置桶）。 */
    public synchronized boolean idleFor(Duration idle) {
        return nanoTime.getAsLong() - lastAccessNanos >= idle.toNanos();
    }

    /**
     * 补满 1 个令牌所需的秒数，向上取整且最小 1。
     *
     * <p>{@code refillPerSecond} 为 0 时永远不会补满，返回 60 秒作为兜底。</p>
     */
    public synchronized long retryAfterSeconds() {
        refill(nanoTime.getAsLong());
        if (tokens >= 1.0d) {
            return 0L;
        }
        if (refillPerSecond <= 0.0d) {
            return 60L;
        }
        double seconds = (1.0d - tokens) / refillPerSecond;
        return Math.max(1L, (long) Math.ceil(seconds));
    }

    /** 按距上次补充的纳秒数折算令牌；时钟回拨（{@code elapsed <= 0}）直接忽略。 */
    private void refill(long now) {
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0L) {
            return;
        }
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + elapsed / NANOS_PER_SECOND * refillPerSecond);
    }
}
