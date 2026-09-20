package io.agentbridge.orders;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per Idempotency-Key. A replay with the same key returns the stored
 * resource instead of creating a second one; a replay with a different body is
 * rejected, which is what stops an agent retry from double-charging a customer.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String key;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String key, String requestFingerprint, UUID resourceId) {
        this.key = key;
        this.requestFingerprint = requestFingerprint;
        this.resourceId = resourceId;
        this.createdAt = Instant.now();
    }

    public String getKey() { return key; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getResourceId() { return resourceId; }
    public Instant getCreatedAt() { return createdAt; }
}
