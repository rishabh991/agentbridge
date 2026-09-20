package io.agentbridge.gateway.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    /**
     * The filter is built here rather than annotated {@code @Component} on purpose.
     * A component would also be auto-registered as a plain servlet filter, and because
     * {@code OncePerRequestFilter} runs a given filter class once per request, only one
     * of the two registrations would execute — which is what made a scope-less key
     * answer 401 instead of 403 against the running container while MockMvc, which does
     * not use the servlet registrations, reported 403.
     */
    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        return new ApiKeyAuthenticationFilter(apiKeyService);
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterStaysOutOfTheServletChain(
            ApiKeyAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Three tiers:
     * <ul>
     *   <li>open — health and {@code /api/v1/info}, so a demo page and a load balancer
     *       can read liveness and capabilities without a credential;</li>
     *   <li>any valid key — the MCP endpoint and the read-only views. Which tools a key
     *       may actually <em>call</em> is a scope decision made per tool, not per URL;</li>
     *   <li>admin scope — issuing and revoking keys, refreshing the tool registry.</li>
     * </ul>
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        // A denied request is answered with sendError, which makes the
                        // container re-dispatch to /error. By then the request's security
                        // context has been cleared, so securing that dispatch too would
                        // re-evaluate it as anonymous and overwrite a considered 403 with
                        // a misleading 401. MockMvc performs no error dispatch, so it
                        // reports the 403 and hides this entirely.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/api/v1/info").permitAll()
                        .requestMatchers("/api/v1/keys/**", "/api/v1/tools/refresh").hasAuthority("SCOPE_admin")
                        .requestMatchers("/mcp/**", "/mcp", "/sse/**", "/api/v1/**", "/actuator/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        // No key, or one that does not resolve: 401, you are not anybody.
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        // A real key without the scope: 403, you are somebody, just not
                        // somebody who may do this. Retrying with the same key is pointless
                        // and the distinction matters to whoever reads the audit trail.
                        .accessDeniedHandler(new AccessDeniedHandlerImpl()))
                .build();
    }
}
