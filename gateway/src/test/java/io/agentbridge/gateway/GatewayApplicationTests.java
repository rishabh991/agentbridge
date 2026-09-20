package io.agentbridge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class GatewayApplicationTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    GatewayProperties properties;

    @Test
    void infoReportsTheMilestoneAndUnfinishedCapabilities() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        mockMvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("AgentBridge"))
                .andExpect(jsonPath("$.milestone").value("M1"))
                .andExpect(jsonPath("$.capabilities.openapiToMcpTools").value(true))
                .andExpect(jsonPath("$.capabilities.auditStream").value(true))
                .andExpect(jsonPath("$.capabilities.rateLimits").value(false));
    }

    @Test
    void ordersUpstreamIsDeclaredWithAnOpenApiUrl() {
        assertThat(properties.upstreams()).hasSize(1);

        var orders = properties.upstreams().getFirst();
        assertThat(orders.name()).isEqualTo("orders");
        assertThat(orders.openapiUrl()).endsWith("/v3/api-docs");
        assertThat(orders.healthUrl()).endsWith("/actuator/health");
    }

    @Test
    void anUnreachableUpstreamIsReportedAsDownRatherThanThrowing() {
        var probe = context.getBean(UpstreamProbe.class);

        var status = probe.probe(new GatewayProperties.Upstream(
                "nowhere", "http://127.0.0.1:9", "/v3/api-docs", "/actuator/health"));

        assertThat(status.reachable()).isFalse();
        assertThat(status.detail()).isNotBlank();
    }
}
