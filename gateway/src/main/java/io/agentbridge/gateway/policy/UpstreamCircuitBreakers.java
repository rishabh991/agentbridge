package io.agentbridge.gateway.policy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * One circuit breaker per upstream.
 *
 * <p>Without this, an upstream that has started timing out turns every agent into a
 * load generator against it: the model retries, the gateway forwards, the upstream
 * gets slower. An open breaker converts that into an immediate, honest error the
 * model can act on.
 *
 * <p>Client errors are not failures. A 404 or a 409 is the upstream working
 * correctly and saying no; counting those toward the breaker would open it because
 * an agent asked for something that does not exist.
 */
@Component
public class UpstreamCircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(UpstreamCircuitBreakers.class);

    private final CircuitBreakerRegistry registry;

    public UpstreamCircuitBreakers() {
        this.registry = buildRegistry();
        // Attached once, when a breaker is first created for an upstream. Registering
        // it per call — as this did at first — adds a listener on every tool call and
        // leaks them for the life of the process.
        registry.getEventPublisher().onEntryAdded(added -> {
            var breaker = added.getAddedEntry();
            breaker.getEventPublisher().onStateTransition(event ->
                    log.warn("upstream {} circuit breaker {}", breaker.getName(), event.getStateTransition()));
        });
    }

    private static CircuitBreakerRegistry buildRegistry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(8)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(70f)
                .ignoreException(UpstreamCircuitBreakers::isClientError)
                .build());
    }

    public <T> T call(String upstream, Supplier<T> action) {
        return registry.circuitBreaker(upstream).executeSupplier(action);
    }

    public String stateOf(String upstream) {
        return registry.circuitBreaker(upstream).getState().name();
    }

    public static boolean isCircuitOpen(Throwable t) {
        return t instanceof CallNotPermittedException;
    }

    private static boolean isClientError(Throwable t) {
        return t instanceof org.springframework.web.client.HttpClientErrorException;
    }

    public CircuitBreaker breakerFor(String upstream) {
        return registry.circuitBreaker(upstream);
    }
}
