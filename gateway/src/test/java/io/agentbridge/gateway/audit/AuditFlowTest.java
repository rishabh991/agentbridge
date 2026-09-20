package io.agentbridge.gateway.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Runs against the direct sink, which is the same code path the Kafka projector
 * ends in: both call {@link AuditStore#save}.
 */
@SpringBootTest
class AuditFlowTest {

    @Autowired
    AuditService auditService;

    @Autowired
    AuditStore auditStore;

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aRecordedCallBecomesAQueryableRowAndShowsUpOnTheAuditEndpoint() throws Exception {
        var before = auditStore.count();

        auditService.record("orders_createOrder", "orders", objectMapper.readTree("{\"customerId\":\"c-1\"}"),
                AuditEvent.OUTCOME_OK, 201, 42L, "ab-testkey");

        assertThat(auditStore.count()).isEqualTo(before + 1);

        var stored = auditStore.recent(1).getFirst();
        assertThat(stored.getTool()).isEqualTo("orders_createOrder");
        assertThat(stored.getOutcome()).isEqualTo(AuditEvent.OUTCOME_OK);
        assertThat(stored.getHttpStatus()).isEqualTo(201);
        assertThat(stored.getIdempotencyKey()).isEqualTo("ab-testkey");
        assertThat(stored.getArguments()).contains("redacted").doesNotContain("c-1");

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        mockMvc.perform(get("/api/v1/audit?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tool").value("orders_createOrder"))
                .andExpect(jsonPath("$[0].idempotencyKey").value("ab-testkey"));
    }

    @Test
    void theAuditTrailSurvivesAFailedCallToo() {
        auditService.record("orders_capturePayment", "orders", null,
                AuditEvent.OUTCOME_UPSTREAM_ERROR, 409, 7L, "ab-dup");

        assertThat(auditStore.recent(25))
                .anySatisfy(row -> {
                    assertThat(row.getOutcome()).isEqualTo(AuditEvent.OUTCOME_UPSTREAM_ERROR);
                    assertThat(row.getHttpStatus()).isEqualTo(409);
                });
    }
}
