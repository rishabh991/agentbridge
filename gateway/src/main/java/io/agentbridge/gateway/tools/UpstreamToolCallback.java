package io.agentbridge.gateway.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentbridge.gateway.audit.AuditEvent;
import io.agentbridge.gateway.audit.AuditService;
import io.agentbridge.gateway.policy.ToolPolicy;
import io.agentbridge.gateway.policy.UpstreamCircuitBreakers;
import io.agentbridge.gateway.security.CallerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * Executes one generated tool against its upstream, and records what happened.
 *
 * <p>Two things here are the whole point of the gateway:
 * <ul>
 *   <li>every mutating call carries an idempotency key, derived from the tool name
 *       and the arguments, so a model that retries the identical call gets the
 *       original result rather than a second order;</li>
 *   <li>every call, successful or not, produces exactly one audit event.</li>
 * </ul>
 *
 * <p>Upstream errors are returned to the model as JSON rather than thrown. A model
 * that is told "409, the order is already paid" can recover; an exception that
 * surfaces as a transport failure teaches it nothing.
 */
public class UpstreamToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(UpstreamToolCallback.class);

    private final UpstreamTool tool;
    private final String baseUrl;
    private final String upstreamToken;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final ToolPolicy policy;
    private final UpstreamCircuitBreakers breakers;

    public UpstreamToolCallback(UpstreamTool tool, String baseUrl, RestClient restClient,
                                ObjectMapper objectMapper, AuditService audit,
                                ToolPolicy policy, UpstreamCircuitBreakers breakers) {
        this(tool, baseUrl, null, restClient, objectMapper, audit, policy, breakers);
    }

    public UpstreamToolCallback(UpstreamTool tool, String baseUrl, String upstreamToken, RestClient restClient,
                                ObjectMapper objectMapper, AuditService audit,
                                ToolPolicy policy, UpstreamCircuitBreakers breakers) {
        this.tool = tool;
        this.baseUrl = baseUrl;
        this.upstreamToken = upstreamToken;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.policy = policy;
        this.breakers = breakers;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        JsonNode arguments;
        try {
            arguments = toolInput == null || toolInput.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(toolInput);
        } catch (Exception e) {
            audit.record(tool, null, AuditEvent.OUTCOME_GATEWAY_ERROR, null, 0L, null);
            return error("invalid_arguments", "Tool arguments were not valid JSON: " + e.getMessage(), null);
        }

        var decision = policy.check(tool);
        if (!decision.allowed()) {
            audit.record(tool, arguments, decision.outcome(), null, 0L, null);
            return error(decision.outcome(), decision.reason(), null);
        }

        var idempotencyKey = tool.mutating() ? idempotencyKey(arguments) : null;
        var started = System.nanoTime();
        try {
            var uri = buildUri(arguments);
            var request = restClient.method(org.springframework.http.HttpMethod.valueOf(tool.method())).uri(uri);
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            if (upstreamToken != null && !upstreamToken.isBlank()) {
                request.header("X-Upstream-Token", upstreamToken);
            }

            var body = requestBody(arguments);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body.toString());
            }

            var response = breakers.call(tool.upstream(), () -> request.retrieve().toEntity(String.class));
            var millis = elapsedMillis(started);
            audit.record(tool, arguments, AuditEvent.OUTCOME_OK,
                    response.getStatusCode().value(), millis, idempotencyKey);
            return response.getBody() == null ? "{}" : response.getBody();

        } catch (RestClientResponseException e) {
            var millis = elapsedMillis(started);
            audit.record(tool, arguments, AuditEvent.OUTCOME_UPSTREAM_ERROR,
                    e.getStatusCode().value(), millis, idempotencyKey);
            return error("upstream_error",
                    "Upstream returned " + e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (Exception e) {
            var millis = elapsedMillis(started);
            if (UpstreamCircuitBreakers.isCircuitOpen(e)) {
                audit.record(tool, arguments, AuditEvent.OUTCOME_UPSTREAM_UNAVAILABLE, null, millis, idempotencyKey);
                return error(AuditEvent.OUTCOME_UPSTREAM_UNAVAILABLE,
                        "Upstream '" + tool.upstream() + "' is failing, so calls to it are being refused for a "
                                + "short period. Try again shortly.", null);
            }
            log.warn("tool {} failed before reaching {}: {}", tool.name(), tool.upstream(), e.getMessage());
            audit.record(tool, arguments, AuditEvent.OUTCOME_GATEWAY_ERROR, null, millis, idempotencyKey);
            return error("gateway_error", e.getMessage(), null);
        }
    }

    private String buildUri(JsonNode arguments) {
        var path = tool.pathTemplate();
        for (String param : tool.pathParams()) {
            var value = arguments.get(param);
            if (value == null || value.isNull()) {
                throw new IllegalArgumentException("Missing required path argument '" + param + "'");
            }
            path = path.replace("{" + param + "}",
                    UriUtils.encodePathSegment(value.asText(), StandardCharsets.UTF_8));
        }

        var builder = UriComponentsBuilder.fromUriString(baseUrl + path);
        for (String param : tool.queryParams()) {
            var value = arguments.get(param);
            if (value != null && !value.isNull()) {
                builder.queryParam(param, value.asText());
            }
        }
        return builder.build(true).toUriString();
    }

    private ObjectNode requestBody(JsonNode arguments) {
        if (tool.bodyParams().isEmpty()) {
            return null;
        }
        var body = objectMapper.createObjectNode();
        for (String param : tool.bodyParams()) {
            var value = arguments.get(param);
            if (value != null && !value.isNull()) {
                body.set(param, value);
            }
        }
        return body;
    }

    /**
     * Derived, not random: the same tool called with the same arguments produces the
     * same key, so an agent retrying after a timeout replays rather than duplicates.
     * Field order in the model's JSON is not stable, so the fields are sorted first.
     */
    String idempotencyKey(JsonNode arguments) {
        var canonical = new TreeMap<String, String>();
        arguments.fields().forEachRemaining(entry -> canonical.put(entry.getKey(), entry.getValue().toString()));

        var material = new StringBuilder(tool.name());
        canonical.forEach((key, value) -> material.append('|').append(key).append('=').append(value));

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(material.toString().getBytes(StandardCharsets.UTF_8));
            return "ab-" + HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String error(String code, String message, String upstreamBody) {
        var node = objectMapper.createObjectNode();
        node.put("error", code);
        node.put("message", message == null ? "" : message);
        if (upstreamBody != null && !upstreamBody.isBlank()) {
            try {
                node.set("upstream", objectMapper.readTree(upstreamBody));
            } catch (Exception ignored) {
                node.put("upstream", upstreamBody);
            }
        }
        return node.toString();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public UpstreamTool tool() {
        return tool;
    }
}
