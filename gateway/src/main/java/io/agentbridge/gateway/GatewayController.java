package io.agentbridge.gateway;

import io.agentbridge.gateway.audit.AuditEventEntity;
import io.agentbridge.gateway.audit.AuditStore;
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
    private final String version;
    private final String auditSink;

    GatewayController(UpstreamProbe probe, ToolRegistry registry, AuditStore auditStore,
                      @Value("${agentbridge.version:0.2.0-SNAPSHOT}") String version,
                      @Value("${agentbridge.audit.sink}") String auditSink) {
        this.probe = probe;
        this.registry = registry;
        this.auditStore = auditStore;
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
                "milestone", "M1",
                "mcpEndpoint", "/mcp",
                "toolCount", registry.tools().size(),
                "auditSink", auditSink,
                "auditedCalls", auditStore.count(),
                "capabilities", Map.of(
                        "upstreamRegistry", true,
                        "openapiToMcpTools", true,
                        "auditStream", true,
                        "apiKeysAndRbac", false,
                        "rateLimits", false,
                        "costTracking", false,
                        "evals", false));
    }

    @GetMapping("/upstreams")
    List<UpstreamProbe.UpstreamStatus> upstreams() {
        return probe.probeAll();
    }

    /** The generated tool surface, as an MCP client would see it. */
    @GetMapping("/tools")
    List<UpstreamTool> tools() {
        return registry.tools();
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
