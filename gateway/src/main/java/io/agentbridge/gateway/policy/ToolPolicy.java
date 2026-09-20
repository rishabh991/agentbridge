package io.agentbridge.gateway.policy;

import io.agentbridge.gateway.security.ApiKeyPrincipal;
import io.agentbridge.gateway.security.CallerContext;
import io.agentbridge.gateway.tools.UpstreamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides every tool call: is there a caller, does it hold the scope the tool needs,
 * and is it within its rate limit.
 *
 * <p>The scope a tool requires is derived, not configured: {@code <upstream>:read} for
 * safe operations and {@code <upstream>:write} for mutating ones. Derivation means a
 * newly imported tool is governed the moment it appears — a tool that arrives with no
 * policy attached is the hole this design exists to close.
 */
@Component
public class ToolPolicy {

    private static final Logger log = LoggerFactory.getLogger(ToolPolicy.class);

    private final KeyRateLimiter rateLimiter;

    public ToolPolicy(KeyRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public static String requiredScope(UpstreamTool tool) {
        return tool.upstream() + (tool.mutating() ? ":write" : ":read");
    }

    public PolicyDecision check(UpstreamTool tool) {
        var caller = CallerContext.current().orElse(null);
        if (caller == null) {
            // Reachable only if the MCP endpoint is ever served unauthenticated.
            log.warn("tool {} was called with no authenticated key in context", tool.name());
            return PolicyDecision.deny("No API key was presented.");
        }

        var required = requiredScope(tool);
        if (!hasScope(caller, required)) {
            return PolicyDecision.deny("This key does not hold the scope '" + required
                    + "' that tool '" + tool.name() + "' requires.");
        }

        if (!rateLimiter.tryAcquire(caller)) {
            return PolicyDecision.rateLimited("This key is over its limit of "
                    + caller.requestsPerMinute() + " tool calls per minute.");
        }

        return PolicyDecision.allow();
    }

    private boolean hasScope(ApiKeyPrincipal caller, String required) {
        if (caller.scopes().contains(required) || caller.scopes().contains("admin")) {
            return true;
        }
        var colon = required.indexOf(':');
        return colon > 0 && caller.scopes().contains("*:" + required.substring(colon + 1));
    }
}
