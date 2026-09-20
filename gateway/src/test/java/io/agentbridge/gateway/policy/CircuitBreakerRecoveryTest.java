package io.agentbridge.gateway.policy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerRecoveryTest {

    private final UpstreamCircuitBreakers breakers = new UpstreamCircuitBreakers();

    @Test
    void opensAfterRepeatedUpstreamFailuresAndThenRefusesToCallAtAll() {
        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> breakers.call("flaky", () -> {
                throw new ResourceAccessException("connection refused");
            })).isInstanceOf(ResourceAccessException.class);
        }

        assertThat(breakers.stateOf("flaky")).isEqualTo("OPEN");

        assertThatThrownBy(() -> breakers.call("flaky", () -> "never runs"))
                .isInstanceOf(CallNotPermittedException.class)
                .satisfies(e -> assertThat(UpstreamCircuitBreakers.isCircuitOpen(e)).isTrue());
    }

    @Test
    void aClientErrorIsTheUpstreamWorkingCorrectlyAndMustNotOpenTheBreaker() {
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> breakers.call("strict", () -> {
                throw HttpClientErrorException.create(org.springframework.http.HttpStatus.CONFLICT,
                        "Conflict", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null);
            })).isInstanceOf(HttpClientErrorException.class);
        }

        assertThat(breakers.stateOf("strict")).isEqualTo("CLOSED");
    }

    @Test
    void closesAgainOnceTheUpstreamRecovers() {
        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> breakers.call("recovering", () -> {
                throw new ResourceAccessException("down");
            })).isInstanceOf(ResourceAccessException.class);
        }
        assertThat(breakers.stateOf("recovering")).isEqualTo("OPEN");

        // Skip the wait rather than sleep through it; the point under test is that
        // successful trial calls close the breaker, not the duration of the wait.
        breakers.breakerFor("recovering").transitionToHalfOpenState();
        for (int i = 0; i < 3; i++) {
            assertThat(breakers.call("recovering", () -> "ok")).isEqualTo("ok");
        }

        assertThat(breakers.stateOf("recovering")).isEqualTo("CLOSED");
    }
}
