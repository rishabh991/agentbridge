package io.agentbridge.gateway.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The one way a tool call gets recorded.
 *
 * <p>Arguments are redacted by default. An audit log that quietly accumulates
 * customer identifiers and payment amounts is a liability; M2 adds the per-tool
 * allowlist that lets specific fields through on purpose.
 */
@Service
public class AuditService {

    static final String ANONYMOUS_KEY = "anonymous";

    private final AuditSink sink;
    private final ObjectMapper objectMapper;
    private final boolean logArguments;

    public AuditService(AuditSink sink, ObjectMapper objectMapper,
                 @Value("${agentbridge.audit.log-arguments:false}") boolean logArguments) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.logArguments = logArguments;
    }

    public void record(String tool, String upstream, JsonNode arguments, String outcome,
                       Integer httpStatus, long latencyMillis, String idempotencyKey) {
        sink.publish(new AuditEvent(
                UUID.randomUUID(),
                Instant.now(),
                ANONYMOUS_KEY,
                tool,
                upstream,
                redact(arguments),
                outcome,
                httpStatus,
                latencyMillis,
                idempotencyKey,
                null,
                0L,
                0L,
                0L));
    }

    private JsonNode redact(JsonNode arguments) {
        if (logArguments) {
            return arguments == null ? objectMapper.createObjectNode() : arguments;
        }
        var node = objectMapper.createObjectNode();
        node.put("redacted", true);
        if (arguments != null) {
            var names = objectMapper.createArrayNode();
            arguments.fieldNames().forEachRemaining(names::add);
            node.set("fields", names);
        }
        return node;
    }
}
