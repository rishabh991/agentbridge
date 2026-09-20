package io.agentbridge.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentbridge.gateway.GatewayProperties;
import io.agentbridge.gateway.audit.AuditService;
import io.agentbridge.gateway.policy.ToolPolicy;
import io.agentbridge.gateway.policy.UpstreamCircuitBreakers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Imports each declared upstream's OpenAPI document once at startup and holds the
 * tools it produced.
 *
 * <p>An upstream that is unreachable at startup costs its tools, not the gateway:
 * the others still register and {@link #refresh()} can be called again. Failing to
 * boot because one upstream is slow would make the gateway the weakest link in a
 * chain it exists to strengthen.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final GatewayProperties properties;
    private final OpenApiToolFactory factory;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final ToolPolicy policy;
    private final UpstreamCircuitBreakers breakers;

    private volatile List<UpstreamToolCallback> callbacks = List.of();

    ToolRegistry(GatewayProperties properties, OpenApiToolFactory factory, RestClient.Builder builder,
                 ObjectMapper objectMapper, AuditService audit, ToolPolicy policy,
                 UpstreamCircuitBreakers breakers) {
        this.properties = properties;
        this.factory = factory;
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.policy = policy;
        this.breakers = breakers;
    }

    public synchronized List<UpstreamToolCallback> refresh() {
        var imported = new ArrayList<UpstreamToolCallback>();
        for (GatewayProperties.Upstream upstream : properties.upstreams()) {
            try {
                var request = restClient.get().uri(upstream.openapiUrl());
                if (upstream.hasAuthToken()) {
                    request.header("X-Upstream-Token", upstream.authToken());
                }
                var document = request.retrieve().body(String.class);
                if (document == null || document.isBlank()) {
                    log.warn("upstream {} returned an empty OpenAPI document", upstream.name());
                    continue;
                }
                var tools = factory.toolsFrom(upstream.name(), document);
                tools.forEach(tool -> imported.add(
                        new UpstreamToolCallback(tool, upstream.baseUrl(), upstream.authToken(), restClient,
                                objectMapper, audit, policy, breakers)));
                log.info("registered {} tools from upstream {}", tools.size(), upstream.name());
            } catch (Exception e) {
                log.error("could not import tools from upstream {} at {}: {}",
                        upstream.name(), upstream.openapiUrl(), e.getMessage());
            }
        }
        this.callbacks = List.copyOf(imported);
        return this.callbacks;
    }

    /**
     * Keeps trying while the tool list is empty.
     *
     * <p>On a host that suspends idle services, the upstream is often asleep when the
     * gateway boots, so the first import returns nothing. Without this the gateway
     * would serve an empty tool list until someone noticed and called refresh — the
     * failure would be silent, and an MCP client would simply see a server with no
     * tools rather than an error.
     */
    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT60S")
    void refreshWhileEmpty() {
        if (callbacks.isEmpty() && !properties.upstreams().isEmpty()) {
            log.info("no tools registered yet; retrying the upstream import");
            refresh();
        }
    }

    public List<UpstreamToolCallback> callbacks() {
        return callbacks;
    }

    public List<UpstreamTool> tools() {
        return callbacks.stream().map(UpstreamToolCallback::tool).toList();
    }

    public Optional<UpstreamToolCallback> byName(String name) {
        return callbacks.stream().filter(c -> c.tool().name().equals(name)).findFirst();
    }
}
