package io.agentbridge.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentbridge.gateway.audit.AuditEvent;
import io.agentbridge.gateway.audit.AuditService;
import io.agentbridge.gateway.audit.AuditSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpstreamToolCallbackTest {

    private static final String BASE_URL = "http://orders.test";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<AuditEvent> recorded = new ArrayList<>();

    private RestClient restClient;
    private MockRestServiceServer server;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        AuditSink sink = recorded::add;
        audit = new AuditService(sink, objectMapper, false);
    }

    @Test
    void sendsBodyFieldsAndAnIdempotencyKeyOnAMutatingCall() {
        server.expect(requestTo(BASE_URL + "/api/orders"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Idempotency-Key", org.hamcrest.Matchers.startsWith("ab-")))
                .andExpect(jsonPath("$.customerId").value("cust-1"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andRespond(withSuccess("{\"id\":\"order-1\",\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));

        var result = callback(createOrderTool()).call("{\"customerId\":\"cust-1\",\"sku\":\"SKU-1\",\"quantity\":2}");

        server.verify();
        assertThat(result).contains("order-1");
        assertThat(recorded).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(AuditEvent.OUTCOME_OK);
            assertThat(event.httpStatus()).isEqualTo(200);
            assertThat(event.tool()).isEqualTo("orders_createOrder");
            assertThat(event.idempotencyKey()).startsWith("ab-");
        });
    }

    @Test
    void doesNotSendAnIdempotencyKeyOnAReadOnlyCall() {
        server.expect(requestTo(BASE_URL + "/api/catalog/items"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        callback(listCatalogTool()).call("{}");

        server.verify();
        assertThat(recorded).singleElement()
                .satisfies(event -> assertThat(event.idempotencyKey()).isNull());
    }

    @Test
    void substitutesPathParametersAndAppendsQueryParameters() {
        server.expect(requestTo(BASE_URL + "/api/orders?customerId=cust-1&status=PAID"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        callback(listOrdersTool()).call("{\"customerId\":\"cust-1\",\"status\":\"PAID\"}");

        server.verify();
    }

    @Test
    void derivesTheSameKeyForTheSameArgumentsRegardlessOfFieldOrder() {
        var callback = callback(createOrderTool());

        var first = callback.idempotencyKey(json("{\"customerId\":\"c\",\"sku\":\"s\",\"quantity\":1}"));
        var reordered = callback.idempotencyKey(json("{\"quantity\":1,\"sku\":\"s\",\"customerId\":\"c\"}"));
        var different = callback.idempotencyKey(json("{\"customerId\":\"c\",\"sku\":\"s\",\"quantity\":9}"));

        assertThat(first).isEqualTo(reordered);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    void returnsUpstreamErrorsToTheModelAsDataAndAuditsThem() {
        server.expect(requestTo(BASE_URL + "/api/orders"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"already_paid\",\"message\":\"Order is already paid\"}"));

        var result = callback(createOrderTool()).call("{\"customerId\":\"c\",\"sku\":\"s\",\"quantity\":1}");

        assertThat(result).contains("upstream_error").contains("already_paid");
        assertThat(recorded).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(AuditEvent.OUTCOME_UPSTREAM_ERROR);
            assertThat(event.httpStatus()).isEqualTo(409);
        });
    }

    @Test
    void auditsArgumentFieldNamesButNotTheirValues() {
        server.expect(requestTo(BASE_URL + "/api/orders"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        callback(createOrderTool()).call("{\"customerId\":\"alice@example.com\",\"sku\":\"s\",\"quantity\":1}");

        var arguments = recorded.getFirst().arguments().toString();
        assertThat(arguments).contains("redacted").contains("customerId");
        assertThat(arguments).doesNotContain("alice@example.com");
    }

    @Test
    void refusesACallThatIsMissingARequiredPathArgument() {
        var result = callback(getOrderTool()).call("{}");

        assertThat(result).contains("gateway_error").contains("id");
        assertThat(recorded).singleElement()
                .satisfies(event -> assertThat(event.outcome()).isEqualTo(AuditEvent.OUTCOME_GATEWAY_ERROR));
    }

    @Test
    void reportsInvalidJsonArgumentsWithoutCallingUpstream() {
        var result = callback(createOrderTool()).call("not json");

        assertThat(result).contains("invalid_arguments");
        server.verify();
    }

    private com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UpstreamToolCallback callback(UpstreamTool tool) {
        return new UpstreamToolCallback(tool, BASE_URL, restClient, objectMapper, audit);
    }

    private UpstreamTool createOrderTool() {
        return new UpstreamTool("orders_createOrder", "Create an order", "{}", "orders", "POST", "/api/orders",
                List.of(), List.of(), List.of("customerId", "sku", "quantity"), true);
    }

    private UpstreamTool listOrdersTool() {
        return new UpstreamTool("orders_listOrders", "List orders", "{}", "orders", "GET", "/api/orders",
                List.of(), List.of("customerId", "status"), List.of(), false);
    }

    private UpstreamTool getOrderTool() {
        return new UpstreamTool("orders_getOrder", "Get an order", "{}", "orders", "GET", "/api/orders/{id}",
                List.of("id"), List.of(), List.of(), false);
    }

    private UpstreamTool listCatalogTool() {
        return new UpstreamTool("orders_listCatalogItems", "List catalogue", "{}", "orders", "GET",
                "/api/catalog/items", List.of(), List.of(), List.of(), false);
    }
}
