package io.agentbridge.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockMvc does not exercise the real servlet filter registrations, and the two
 * disagreed: a scope-less key got 403 under MockMvc and 401 against the running
 * container. This test runs against a real port so the answer is the deployed one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessDeniedStatusTest {

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder builder;

    @Test
    void aValidKeyWithoutTheAdminScopeGets403NotAnAuthenticationChallenge() {
        var client = builder.baseUrl("http://localhost:" + port).build();

        var status = client.get().uri("/api/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ab_test-guest-key")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(403);
    }

    @Test
    void noKeyAtAllStillGets401() {
        var client = builder.baseUrl("http://localhost:" + port).build();

        var status = client.get().uri("/api/v1/keys")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }
}
