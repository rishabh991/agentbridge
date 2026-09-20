package io.agentbridge.gateway.audit;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "api_key_id", length = 64)
    private String apiKeyId;

    @Column(name = "tool", nullable = false, length = 128)
    private String tool;

    @Column(name = "upstream", nullable = false, length = 64)
    private String upstream;

    @Column(name = "arguments", nullable = false, columnDefinition = "text")
    private String arguments;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_millis", nullable = false)
    private long latencyMillis;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "tokens_in", nullable = false)
    private long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private long tokensOut;

    @Column(name = "cost_micros", nullable = false)
    private long costMicros;

    protected AuditEventEntity() {
    }

    AuditEventEntity(AuditEvent event, String argumentsJson) {
        this.eventId = event.eventId();
        this.occurredAt = event.at();
        this.apiKeyId = event.apiKeyId();
        this.tool = event.tool();
        this.upstream = event.upstream();
        this.arguments = argumentsJson;
        this.outcome = event.outcome();
        this.httpStatus = event.httpStatus();
        this.latencyMillis = event.latencyMillis();
        this.idempotencyKey = event.idempotencyKey();
        this.provider = event.provider();
        this.tokensIn = event.tokensIn();
        this.tokensOut = event.tokensOut();
        this.costMicros = event.costMicros();
    }

    public UUID getEventId() { return eventId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getApiKeyId() { return apiKeyId; }
    public String getTool() { return tool; }
    public String getUpstream() { return upstream; }
    public String getArguments() { return arguments; }
    public String getOutcome() { return outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public long getLatencyMillis() { return latencyMillis; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
