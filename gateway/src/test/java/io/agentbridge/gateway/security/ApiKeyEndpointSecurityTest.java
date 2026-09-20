package io.agentbridge.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ApiKeyEndpointSecurityTest {

    private static final String ADMIN = "ab_test-admin-key";
    private static final String GUEST = "ab_test-guest-key";

    @Autowired
    WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void infoAndHealthAreOpenSoADemoPageAndALoadBalancerCanReadThem() throws Exception {
        mockMvc().perform(get("/api/v1/info")).andExpect(status().isOk());
        // health reports 503 here because the test upstream is not running (the probe
        // endpoints are only configured in the main profile). The point of this test is
        // that health answers at all rather than demanding a key.
        mockMvc().perform(get("/actuator/health"))
                .andExpect(result -> {
                    var code = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(code).isNotIn(401, 403);
                });
    }

    @Test
    void everythingElseNeedsAKey() throws Exception {
        mockMvc().perform(get("/api/v1/tools")).andExpect(status().isUnauthorized());
        mockMvc().perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
        mockMvc().perform(get("/api/v1/keys")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBadKeyIsAsUnauthenticatedAsNoKey() throws Exception {
        mockMvc().perform(get("/api/v1/tools").header("Authorization", "Bearer ab_wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc().perform(get("/api/v1/tools").header("X-API-Key", "ab_wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGuestKeyReadsButCannotTouchKeyAdministration() throws Exception {
        mockMvc().perform(get("/api/v1/tools").header("Authorization", "Bearer " + GUEST))
                .andExpect(status().isOk());

        mockMvc().perform(get("/api/v1/keys").header("Authorization", "Bearer " + GUEST))
                .andExpect(status().isForbidden());

        mockMvc().perform(post("/api/v1/tools/refresh").header("Authorization", "Bearer " + GUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminKeyIssuesAKeyAndTheSecretIsShownExactlyOnce() throws Exception {
        var response = mockMvc().perform(post("/api/v1/keys")
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"ci-created\",\"scopes\":[\"orders:read\"],\"requestsPerMinute\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andExpect(jsonPath("$.keyId").exists())
                .andReturn().getResponse().getContentAsString();

        // The list view never carries secrets back.
        mockMvc().perform(get("/api/v1/keys").header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].keyId").exists());

        org.assertj.core.api.Assertions.assertThat(response).contains("shown once");
    }

    @Test
    void bothHeaderStylesAuthenticate() throws Exception {
        mockMvc().perform(get("/api/v1/tools").header("Authorization", "Bearer " + ADMIN))
                .andExpect(status().isOk());
        mockMvc().perform(get("/api/v1/tools").header("X-API-Key", ADMIN))
                .andExpect(status().isOk());
    }
}
