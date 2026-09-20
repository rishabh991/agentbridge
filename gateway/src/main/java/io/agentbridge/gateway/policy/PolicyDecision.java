package io.agentbridge.gateway.policy;

/**
 * The answer to "may this caller run this tool, right now".
 *
 * @param allowed whether the call proceeds
 * @param outcome audit outcome when it does not: {@code denied} or {@code rate_limited}
 * @param reason  what to tell the model, which is also what lands in the audit row
 */
public record PolicyDecision(boolean allowed, String outcome, String reason) {

    public static final String OUTCOME_DENIED = "denied";
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";

    public static PolicyDecision allow() {
        return new PolicyDecision(true, null, null);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, OUTCOME_DENIED, reason);
    }

    public static PolicyDecision rateLimited(String reason) {
        return new PolicyDecision(false, OUTCOME_RATE_LIMITED, reason);
    }
}
