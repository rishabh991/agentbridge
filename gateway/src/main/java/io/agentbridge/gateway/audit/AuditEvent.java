package io.agentbridge.gateway.audit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One tool call, as it will be answered for later.
 *
 * <p>Cost and token fields are carried from M1 but stay zero until M3 wires the
 * provider side: the shape of the record should not change when that lands, because
 * changing an audit schema after the fact is how audit trails lose their value.
 */
public record AuditEvent(
        UUID eventId,
        Instant at,
        String apiKeyId,
        String tool,
        String upstream,
        JsonNode arguments,
        String outcome,
        Integer httpStatus,
        long latencyMillis,
        String idempotencyKey,
        String provider,
        long tokensIn,
        long tokensOut,
        long costMicros) {

    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_UPSTREAM_ERROR = "upstream_error";
    public static final String OUTCOME_GATEWAY_ERROR = "gateway_error";
}
