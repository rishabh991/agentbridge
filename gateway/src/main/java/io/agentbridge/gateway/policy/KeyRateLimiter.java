package io.agentbridge.gateway.policy;

import io.agentbridge.gateway.security.ApiKeyPrincipal;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * One limiter per key, created on first use from that key's own limit.
 *
 * <p>In-process and therefore per-instance: two gateway replicas allow twice the
 * configured rate. That is a deliberate M2 limit, stated rather than hidden — a
 * shared limiter needs Redis, and the demo does not run one. The README says so.
 */
@Component
public class KeyRateLimiter {

    private final RateLimiterRegistry registry;

    public KeyRateLimiter() {
        this.registry = RateLimiterRegistry.ofDefaults();
    }

    public boolean tryAcquire(ApiKeyPrincipal caller) {
        return limiterFor(caller).acquirePermission();
    }

    private RateLimiter limiterFor(ApiKeyPrincipal caller) {
        return registry.rateLimiter(caller.keyId(), () -> RateLimiterConfig.custom()
                .limitForPeriod(Math.max(1, caller.requestsPerMinute()))
                .limitRefreshPeriod(Duration.ofMinutes(1))
                // Fail fast: an agent should be told it is over budget, not blocked.
                .timeoutDuration(Duration.ZERO)
                .build());
    }
}
