package io.agentbridge.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Upstreams are declared, not discovered. M1 turns each declared OpenAPI spec into
 * MCP tools; M0 only proves the gateway can see them.
 */
@ConfigurationProperties(prefix = "agentbridge")
public record GatewayProperties(List<Upstream> upstreams) {

    public record Upstream(
            String name,
            String baseUrl,
            String openapiPath,
            String healthPath,
            /**
             * Shared secret sent as X-Upstream-Token, for deployments where the
             * upstream has a public address and must refuse everything that is not
             * this gateway. Empty on a private network.
             */
            String authToken) {

        public boolean hasAuthToken() {
            return authToken != null && !authToken.isBlank();
        }

        public String openapiUrl() {
            return baseUrl + (openapiPath == null ? "/v3/api-docs" : openapiPath);
        }

        public String healthUrl() {
            return baseUrl + (healthPath == null ? "/actuator/health" : healthPath);
        }
    }

    public List<Upstream> upstreams() {
        return upstreams == null ? List.of() : upstreams;
    }
}
