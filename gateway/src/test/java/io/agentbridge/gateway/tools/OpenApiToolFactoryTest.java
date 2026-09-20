package io.agentbridge.gateway.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The orders-service spec is captured from the running service, not hand-written. */
class OpenApiToolFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenApiToolFactory factory;
    private List<UpstreamTool> tools;

    @BeforeEach
    void importTheRealSpec() throws Exception {
        factory = new OpenApiToolFactory(objectMapper);
        var spec = new String(new ClassPathResource("orders-openapi.json").getContentAsByteArray(),
                StandardCharsets.UTF_8);
        tools = factory.toolsFrom("orders", spec);
    }

    @Test
    void generatesOneToolPerOperationNamedAfterTheUpstreamAndOperationId() {
        assertThat(tools).extracting(UpstreamTool::name).containsExactlyInAnyOrder(
                "orders_createOrder",
                "orders_getOrder",
                "orders_listOrders",
                "orders_capturePayment",
                "orders_listPayments",
                "orders_listCatalogItems");
    }

    @Test
    void marksWriteOperationsAsMutatingAndReadsAsNot() {
        assertThat(tool("orders_createOrder").mutating()).isTrue();
        assertThat(tool("orders_capturePayment").mutating()).isTrue();
        assertThat(tool("orders_listCatalogItems").mutating()).isFalse();
        assertThat(tool("orders_getOrder").mutating()).isFalse();
    }

    @Test
    void flattensBodyFieldsAndPathParametersIntoOneArgumentSchema() throws Exception {
        var createOrder = tool("orders_createOrder");
        var schema = objectMapper.readTree(createOrder.inputSchema());

        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").fieldNames()).toIterable()
                .contains("customerId", "sku", "quantity");
        assertThat(createOrder.bodyParams()).contains("customerId", "sku", "quantity");
        assertThat(createOrder.pathParams()).isEmpty();

        var capturePayment = tool("orders_capturePayment");
        assertThat(capturePayment.pathParams()).containsExactly("id");
        assertThat(capturePayment.bodyParams()).contains("amountCents");
        assertThat(objectMapper.readTree(capturePayment.inputSchema()).get("properties").fieldNames())
                .toIterable().contains("id", "amountCents");
    }

    @Test
    void keepsQueryParametersOutOfTheBody() {
        var listOrders = tool("orders_listOrders");

        assertThat(listOrders.queryParams()).contains("customerId", "status");
        assertThat(listOrders.bodyParams()).isEmpty();
    }

    @Test
    void tellsTheModelWhenAToolChangesData() {
        assertThat(tool("orders_capturePayment").description())
                .contains("idempotency key");
        assertThat(tool("orders_listCatalogItems").description())
                .doesNotContain("idempotency key");
    }

    @Test
    void carriesTheHttpRoutingDetailNeededToCallTheOperation() {
        var capturePayment = tool("orders_capturePayment");

        assertThat(capturePayment.method()).isEqualTo("POST");
        assertThat(capturePayment.pathTemplate()).isEqualTo("/api/orders/{id}/payments");
        assertThat(capturePayment.upstream()).isEqualTo("orders");
    }

    @Test
    void rejectsADocumentItCannotParse() {
        assertThatThrownBy(() -> factory.toolsFrom("broken", "this is not an OpenAPI document"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken");
    }

    private UpstreamTool tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }
}
