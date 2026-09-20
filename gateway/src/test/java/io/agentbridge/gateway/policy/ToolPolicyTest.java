package io.agentbridge.gateway.policy;

import io.agentbridge.gateway.security.ApiKeyPrincipal;
import io.agentbridge.gateway.security.CallerContext;
import io.agentbridge.gateway.tools.UpstreamTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPolicyTest {

    private final ToolPolicy policy = new ToolPolicy(new KeyRateLimiter());

    @AfterEach
    void clearCaller() {
        CallerContext.clear();
    }

    @Test
    void derivesReadScopeForSafeToolsAndWriteScopeForMutatingOnes() {
        assertThat(ToolPolicy.requiredScope(readTool())).isEqualTo("orders:read");
        assertThat(ToolPolicy.requiredScope(writeTool())).isEqualTo("orders:write");
    }

    @Test
    void aReadOnlyKeyMayReadButNotWrite() {
        callerWith(Set.of("orders:read"), 100);

        assertThat(policy.check(readTool()).allowed()).isTrue();

        var denied = policy.check(writeTool());
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.outcome()).isEqualTo(PolicyDecision.OUTCOME_DENIED);
        assertThat(denied.reason()).contains("orders:write");
    }

    @Test
    void theGuestWildcardGrantsReadEverywhereAndWriteNowhere() {
        callerWith(Set.of("*:read"), 100);

        assertThat(policy.check(readTool()).allowed()).isTrue();
        assertThat(policy.check(writeTool()).allowed()).isFalse();
    }

    @Test
    void adminHoldsEveryScope() {
        callerWith(Set.of("admin"), 100);

        assertThat(policy.check(readTool()).allowed()).isTrue();
        assertThat(policy.check(writeTool()).allowed()).isTrue();
    }

    @Test
    void aCallWithNoKeyInContextIsDenied() {
        CallerContext.clear();

        var decision = policy.check(readTool());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("No API key");
    }

    @Test
    void aKeyIsCutOffWhenItExceedsItsOwnPerMinuteLimit() {
        callerWith(Set.of("orders:read"), 3);

        var allowed = 0;
        PolicyDecision last = null;
        for (int i = 0; i < 5; i++) {
            last = policy.check(readTool());
            if (last.allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(3);
        assertThat(last.allowed()).isFalse();
        assertThat(last.outcome()).isEqualTo(PolicyDecision.OUTCOME_RATE_LIMITED);
        assertThat(last.reason()).contains("3 tool calls per minute");
    }

    @Test
    void oneKeyExhaustingItsLimitDoesNotAffectAnother() {
        callerWith(Set.of("orders:read"), 1, "key_noisy");
        assertThat(policy.check(readTool()).allowed()).isTrue();
        assertThat(policy.check(readTool()).allowed()).isFalse();

        callerWith(Set.of("orders:read"), 1, "key_quiet");
        assertThat(policy.check(readTool()).allowed()).isTrue();
    }

    private void callerWith(Set<String> scopes, int rpm) {
        callerWith(scopes, rpm, "key_test_" + Math.abs(scopes.hashCode()) + "_" + rpm);
    }

    private void callerWith(Set<String> scopes, int rpm, String keyId) {
        CallerContext.set(new ApiKeyPrincipal(keyId, "test", scopes, rpm));
    }

    private UpstreamTool readTool() {
        return new UpstreamTool("orders_getOrder", "Get an order", "{}", "orders", "GET", "/api/orders/{id}",
                List.of("id"), List.of(), List.of(), false);
    }

    private UpstreamTool writeTool() {
        return new UpstreamTool("orders_createOrder", "Create an order", "{}", "orders", "POST", "/api/orders",
                List.of(), List.of(), List.of("customerId"), true);
    }
}
