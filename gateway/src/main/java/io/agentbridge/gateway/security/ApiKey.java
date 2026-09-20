package io.agentbridge.gateway.security;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An API key as stored: the secret itself is never here, only its SHA-256 hash.
 * A leaked database dump must not be a set of working credentials.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @Column(name = "key_id", length = 64)
    private String keyId;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(name = "label", nullable = false, length = 128)
    private String label;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "api_key_scopes", joinColumns = @JoinColumn(name = "key_id"))
    @Column(name = "scope", nullable = false, length = 64)
    private Set<String> scopes = new LinkedHashSet<>();

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApiKey() {
    }

    public ApiKey(String keyId, String keyHash, String label, Set<String> scopes, int requestsPerMinute) {
        this.keyId = keyId;
        this.keyHash = keyHash;
        this.label = label;
        this.scopes = new LinkedHashSet<>(scopes);
        this.requestsPerMinute = requestsPerMinute;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public String getKeyId() { return keyId; }
    public String getKeyHash() { return keyHash; }
    public String getLabel() { return label; }
    public Set<String> getScopes() { return Set.copyOf(scopes); }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    public void setScopes(Set<String> scopes) { this.scopes = new LinkedHashSet<>(scopes); }
    public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setLabel(String label) { this.label = label; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    /**
     * Scopes are {@code upstream:read}, {@code upstream:write} or {@code admin},
     * with {@code *:read} / {@code *:write} as the cross-upstream wildcards.
     */
    public boolean hasScope(String required) {
        if (scopes.contains(required) || scopes.contains("admin")) {
            return true;
        }
        var colon = required.indexOf(':');
        return colon > 0 && scopes.contains("*:" + required.substring(colon + 1));
    }
}
