package io.agentbridge.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the key from {@code Authorization: Bearer ab_...} or {@code X-API-Key} and,
 * if it resolves, puts the caller into both the Spring Security context (for URL
 * rules) and {@link CallerContext} (for the tool layer).
 *
 * <p>A bad key is not rejected here — the filter simply does not authenticate, and
 * the security configuration decides what is allowed unauthenticated. That keeps one
 * place in charge of access rules.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var presented = presentedKey(request);
            if (presented != null) {
                apiKeyService.resolve(presented).ifPresent(principal -> {
                    var authorities = principal.scopes().stream()
                            .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                            .toList();
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    CallerContext.set(principal);
                });
            }
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String presentedKey(HttpServletRequest request) {
        var authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        var header = request.getHeader(HEADER);
        return header == null || header.isBlank() ? null : header.trim();
    }
}
