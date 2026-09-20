package io.agentbridge.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A shared secret between the gateway and this service.
 *
 * <p>This exists because of a hosting constraint, not a design preference: on
 * Render's free plan a service can send private-network requests but cannot receive
 * them, so the gateway has to reach this service over its public URL. An order and
 * payment API open to the internet would make a nonsense of a project about governed
 * access, so when {@code UPSTREAM_TOKEN} is set every request must carry it.
 *
 * <p>Unset — as in local compose, where the network is private — the filter is not
 * registered at all and nothing changes.
 *
 * <p>This is not a replacement for the gateway's own authentication. It only ensures
 * that the gateway is the sole thing that can reach the upstream.
 */
@Component
// An empty value must NOT register the filter, so this tests the value rather
// than the property's presence: @ConditionalOnProperty treats "" as a match.
@ConditionalOnExpression("'${orders.upstream-token:}' != ''")
public class UpstreamTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UpstreamTokenFilter.class);
    static final String HEADER = "X-Upstream-Token";

    private final byte[] expected;

    UpstreamTokenFilter(@Value("${orders.upstream-token}") String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        log.info("upstream token required on every request except health");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The platform's own health check carries no token.
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var presented = request.getHeader(HEADER);
        if (presented == null || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"code\":\"upstream_token_required\",\"message\":\"This service is reachable only through "
                            + "the AgentBridge gateway.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
