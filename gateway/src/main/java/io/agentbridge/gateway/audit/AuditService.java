package io.agentbridge.gateway.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentbridge.gateway.security.CallerContext;
import io.agentbridge.gateway.tools.UpstreamTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Map<String, List<String>> argumentAllowlist;

    public AuditService(AuditSink sink, ObjectMapper objectMapper,
                        @Value("${agentbridge.audit.log-arguments:false}") boolean logArguments,
                        AuditProperties properties) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.logArguments = logArguments;
        this.argumentAllowlist = properties.argumentAllowlist();
    }

    public void record(UpstreamTool tool, JsonNode arguments, String outcome,
                       Integer httpStatus, long latencyMillis, String idempotencyKey) {
        record(tool.name(), tool.upstream(), arguments, outcome, httpStatus, latencyMillis, idempotencyKey);
    }

    public void record(String tool, String upstream, JsonNode arguments, String outcome,
                       Integer httpStatus, long latencyMillis, String idempotencyKey) {
        var caller = CallerContext.current().map(c -> c.keyId()).orElse(ANONYMOUS_KEY);
        sink.publish(new AuditEvent(
                UUID.randomUUID(),
                Instant.now(),
                caller,
                tool,
                upstream,
                redact(tool, arguments),
                outcome,
                httpStatus,
                latencyMillis,
                idempotencyKey,
                null,
                0L,
                0L,
                0L));
    }

    /**
     * Field names always; values only for fields a tool has explicitly allowed.
     *
     * <p>The allowlist is per tool because "safe to log" is a property of the field in
     * context: an order id is useful in an audit trail, a customer email is a liability
     * in the same row.
     */
    private JsonNode redact(String tool, JsonNode arguments) {
        if (arguments == null) {
            return objectMapper.createObjectNode().put("redacted", true);
        }
        if (logArguments) {
            return arguments;
        }

        Set<String> allowed = Set.copyOf(argumentAllowlist.getOrDefault(tool, List.of()));
        var node = objectMapper.createObjectNode();
        // Always true: values are withheld by default, and the allowlist is the set of
        // named exceptions. Reporting redacted=false because one field was allowed
        // would misdescribe a row whose other values really were withheld.
        node.put("redacted", true);
        var names = objectMapper.createArrayNode();
        var shown = objectMapper.createObjectNode();
        arguments.fieldNames().forEachRemaining(name -> {
            names.add(name);
            if (allowed.contains(name)) {
                shown.set(name, arguments.get(name));
            }
        });
        node.set("fields", names);
        if (!shown.isEmpty()) {
            node.set("values", shown);
        }
        return node;
    }
}
