package io.agentbridge.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token gate only matters where this service has a public address, so it is
 * tested over a real port rather than through MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "orders.upstream-token=test-upstream-token")
class UpstreamTokenFilterTest {

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder builder;

    private RestClient client() {
        return builder.baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aRequestWithoutTheTokenIsRefused() {
        var status = client().get().uri("/api/catalog/items")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }

    @Test
    void aRequestWithTheWrongTokenIsRefused() {
        var status = client().get().uri("/api/catalog/items")
                .header("X-Upstream-Token", "not-the-token")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }

    @Test
    void theGatewaysTokenGetsThrough() {
        var status = client().get().uri("/api/catalog/items")
                .header("X-Upstream-Token", "test-upstream-token")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(200);
    }

    @Test
    void thePlatformHealthCheckIsExemptBecauseItCannotCarryAToken() {
        var status = client().get().uri("/actuator/health")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(200);
    }
}
