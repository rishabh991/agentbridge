package io.agentbridge.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Reachability check for the declared upstreams. Deliberately dumb in M0: one GET
 * against each upstream's health endpoint, no retries, no circuit breaker. M2 puts
 * Resilience4j in front of upstream calls.
 */
@Component
public class UpstreamProbe {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProbe.class);

    private final GatewayProperties properties;
    private final RestClient restClient;

    UpstreamProbe(GatewayProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public List<UpstreamStatus> probeAll() {
        return properties.upstreams().stream().map(this::probe).toList();
    }

    public UpstreamStatus probe(GatewayProperties.Upstream upstream) {
        var start = System.nanoTime();
        try {
            restClient.get().uri(upstream.healthUrl()).retrieve().toBodilessEntity();
            return UpstreamStatus.up(upstream, elapsedMillis(start));
        } catch (Exception e) {
            log.warn("upstream {} is unreachable at {}: {}", upstream.name(), upstream.healthUrl(), e.getMessage());
            return UpstreamStatus.down(upstream, elapsedMillis(start), e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    public record UpstreamStatus(
            String name,
            String baseUrl,
            String openapiUrl,
            boolean reachable,
            long latencyMillis,
            String detail) {

        static UpstreamStatus up(GatewayProperties.Upstream u, long millis) {
            return new UpstreamStatus(u.name(), u.baseUrl(), u.openapiUrl(), true, millis, null);
        }

        static UpstreamStatus down(GatewayProperties.Upstream u, long millis, String detail) {
            return new UpstreamStatus(u.name(), u.baseUrl(), u.openapiUrl(), false, millis, detail);
        }
    }
}
