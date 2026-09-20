package io.agentbridge.gateway.security;

import java.util.Set;

/** The authenticated caller, as everything downstream needs to see it. */
public record ApiKeyPrincipal(String keyId, String label, Set<String> scopes, int requestsPerMinute) {

    public static ApiKeyPrincipal of(ApiKey key) {
        return new ApiKeyPrincipal(key.getKeyId(), key.getLabel(), key.getScopes(), key.getRequestsPerMinute());
    }
}
