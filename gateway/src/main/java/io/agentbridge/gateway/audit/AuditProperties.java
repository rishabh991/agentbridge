package io.agentbridge.gateway.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * @param argumentAllowlist tool name to the argument fields whose values may be
 *                          recorded in full. Everything not listed is reduced to its
 *                          field name.
 */
@ConfigurationProperties(prefix = "agentbridge.audit")
public record AuditProperties(Map<String, List<String>> argumentAllowlist) {

    public Map<String, List<String>> argumentAllowlist() {
        return argumentAllowlist == null ? Map.of() : argumentAllowlist;
    }
}
