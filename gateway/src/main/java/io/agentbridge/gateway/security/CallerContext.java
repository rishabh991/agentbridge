package io.agentbridge.gateway.security;

import java.util.Optional;

/**
 * Carries the authenticated key from the servlet filter down to the tool callback.
 *
 * <p>Spring AI's sync MCP server executes a tool on the request thread, so a
 * thread-local reaches it. It is set by {@link ApiKeyAuthenticationFilter} and
 * cleared in the same filter's finally block; anything that runs a tool off-thread
 * must pass the principal explicitly rather than hope this is populated.
 */
public final class CallerContext {

    private static final ThreadLocal<ApiKeyPrincipal> CURRENT = new ThreadLocal<>();

    private CallerContext() {
    }

    public static void set(ApiKeyPrincipal principal) {
        CURRENT.set(principal);
    }

    public static Optional<ApiKeyPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
