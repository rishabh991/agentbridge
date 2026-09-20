package io.agentbridge.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class GatewayController {

    private final UpstreamProbe probe;
    private final String version;

    GatewayController(UpstreamProbe probe, @Value("${agentbridge.version:0.1.0-SNAPSHOT}") String version) {
        this.probe = probe;
        this.version = version;
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
                "milestone", "M0",
                "capabilities", Map.of(
                        "upstreamRegistry", true,
                        "openapiToMcpTools", false,
                        "auditStream", false,
                        "apiKeysAndRbac", false,
                        "rateLimits", false,
                        "costTracking", false,
                        "evals", false));
    }

    @GetMapping("/upstreams")
    List<UpstreamProbe.UpstreamStatus> upstreams() {
        return probe.probeAll();
    }
}
