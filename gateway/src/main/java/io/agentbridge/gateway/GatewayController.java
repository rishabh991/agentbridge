package io.agentbridge.gateway;

import io.agentbridge.gateway.audit.AuditEventEntity;
import io.agentbridge.gateway.audit.AuditStore;
import io.agentbridge.gateway.policy.ToolPolicy;
import io.agentbridge.gateway.policy.UpstreamCircuitBreakers;
import io.agentbridge.gateway.tools.ToolRegistry;
import io.agentbridge.gateway.tools.UpstreamTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class GatewayController {

    private final UpstreamProbe probe;
    private final ToolRegistry registry;
    private final AuditStore auditStore;
    private final UpstreamCircuitBreakers breakers;
    private final String version;
    private final String auditSink;

    GatewayController(UpstreamProbe probe, ToolRegistry registry, AuditStore auditStore,
                      UpstreamCircuitBreakers breakers,
                      @Value("${agentbridge.version:0.3.0-SNAPSHOT}") String version,
                      @Value("${agentbridge.audit.sink}") String auditSink) {
        this.probe = probe;
        this.registry = registry;
        this.auditStore = auditStore;
        this.breakers = breakers;
        this.version = version;
        this.auditSink = auditSink;
    }

    /**
     * Honest capability report: what is actually wired, milestone by milestone.
     * The demo page reads this, so the public demo can never claim more than ships.
     */
    @GetMapping("/info")
    Map<String, Object> info() {
        return Map.of(
                "name", "AgentBridge",
                "version", version,
                "milestone", "M2",
                "mcpEndpoint", "/mcp",
                "authentication", "API key in Authorization: Bearer or X-API-Key",
                "toolCount", registry.tools().size(),
                "auditSink", auditSink,
                "auditedCalls", auditStore.count(),
                "capabilities", Map.of(
                        "upstreamRegistry", true,
                        "openapiToMcpTools", true,
                        "auditStream", true,
                        "apiKeysAndRbac", true,
                        "rateLimits", true,
                        "circuitBreakers", true,
                        "costTracking", false,
                        "evals", false));
    }

    @GetMapping("/upstreams")
    List<Map<String, Object>> upstreams() {
        return probe.probeAll().stream().map(status -> {
            var view = new java.util.LinkedHashMap<String, Object>();
            view.put("name", status.name());
            view.put("baseUrl", status.baseUrl());
            view.put("openapiUrl", status.openapiUrl());
            view.put("reachable", status.reachable());
            view.put("latencyMillis", status.latencyMillis());
            view.put("circuitBreaker", breakers.stateOf(status.name()));
            view.put("detail", status.detail());
            return (Map<String, Object>) view;
        }).toList();
    }

    /** The generated tool surface, with the scope each tool demands. */
    @GetMapping("/tools")
    List<Map<String, Object>> tools() {
        return registry.tools().stream().map(tool -> {
            var view = new java.util.LinkedHashMap<String, Object>();
            view.put("name", tool.name());
            view.put("upstream", tool.upstream());
            view.put("method", tool.method());
            view.put("pathTemplate", tool.pathTemplate());
            view.put("mutating", tool.mutating());
            view.put("requiredScope", ToolPolicy.requiredScope(tool));
            view.put("description", tool.description());
            return (Map<String, Object>) view;
        }).toList();
    }

    /** Re-import every upstream spec. M2 puts this behind an admin scope. */
    @PostMapping("/tools/refresh")
    Map<String, Object> refreshTools() {
        var callbacks = registry.refresh();
        return Map.of("toolCount", callbacks.size());
    }

    @GetMapping("/audit")
    List<Map<String, Object>> audit(@RequestParam(defaultValue = "25") int limit) {
        return auditStore.recent(limit).stream().map(GatewayController::toView).toList();
    }

    private static Map<String, Object> toView(AuditEventEntity e) {
        var view = new java.util.LinkedHashMap<String, Object>();
        view.put("eventId", e.getEventId());
        view.put("at", e.getOccurredAt());
        view.put("apiKeyId", e.getApiKeyId());
        view.put("tool", e.getTool());
        view.put("upstream", e.getUpstream());
        view.put("outcome", e.getOutcome());
        view.put("httpStatus", e.getHttpStatus());
        view.put("latencyMillis", e.getLatencyMillis());
        view.put("idempotencyKey", e.getIdempotencyKey());
        view.put("arguments", e.getArguments());
        return view;
    }
}
