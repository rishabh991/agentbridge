package io.agentbridge.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceTest {

    @Autowired
    OrderService service;

    @Test
    void pricesAnOrderFromTheCatalogue() {
        var order = service.createOrder(new Dtos.CreateOrderRequest("cust-1", "SKU-ROAM-10", 3), null);

        assertThat(order.getAmountCents()).isEqualTo(3 * 1999);
        assertThat(order.getStatus()).isEqualTo(Order.Status.PENDING);
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheSameOrder() {
        var request = new Dtos.CreateOrderRequest("cust-2", "SKU-DATA-50", 1);

        var first = service.createOrder(request, "key-orders-1");
        var second = service.createOrder(request, "key-orders-1");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(service.listOrders("cust-2", null)).hasSize(1);
    }

    @Test
    void reusingAnIdempotencyKeyWithADifferentBodyIsRejected() {
        service.createOrder(new Dtos.CreateOrderRequest("cust-3", "SKU-DATA-50", 1), "key-orders-2");

        assertThatThrownBy(() ->
                service.createOrder(new Dtos.CreateOrderRequest("cust-3", "SKU-DATA-50", 9), "key-orders-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different request body");
    }

    @Test
    void capturingPaymentMarksTheOrderPaidAndIsReplaySafe() {
        var order = service.createOrder(new Dtos.CreateOrderRequest("cust-4", "SKU-VOICE-UNL", 2), null);
        var request = new Dtos.CapturePaymentRequest(order.getAmountCents());

        var first = service.capturePayment(order.getId(), request, "key-pay-1");
        var second = service.capturePayment(order.getId(), request, "key-pay-1");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(service.paymentsOf(order.getId())).hasSize(1);
        assertThat(service.getOrder(order.getId()).getStatus()).isEqualTo(Order.Status.PAID);
    }

    @Test
    void refusesToCaptureAnAmountThatIsNotTheOrderTotal() {
        var order = service.createOrder(new Dtos.CreateOrderRequest("cust-5", "SKU-ROAM-10", 1), null);

        assertThatThrownBy(() ->
                service.capturePayment(order.getId(), new Dtos.CapturePaymentRequest(1), "key-pay-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Order total is");
    }

    @Test
    void rejectsAnUnknownSku() {
        assertThatThrownBy(() ->
                service.createOrder(new Dtos.CreateOrderRequest("cust-6", "SKU-NOPE", 1), null))
                .isInstanceOf(NotFoundException.class);
    }
}
