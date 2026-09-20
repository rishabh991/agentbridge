package io.agentbridge.orders;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class Dtos {

    private Dtos() {
    }

    public record CreateOrderRequest(
            @NotBlank String customerId,
            @NotBlank String sku,
            @Min(1) @Max(1000) int quantity) {
    }

    public record OrderResponse(
            UUID id,
            String customerId,
            String sku,
            int quantity,
            long amountCents,
            String currency,
            String status,
            Instant createdAt) {

        static OrderResponse of(Order o) {
            return new OrderResponse(o.getId(), o.getCustomerId(), o.getSku(), o.getQuantity(),
                    o.getAmountCents(), o.getCurrency(), o.getStatus().name(), o.getCreatedAt());
        }
    }

    public record CapturePaymentRequest(@Min(1) long amountCents) {
    }

    public record PaymentResponse(
            UUID id,
            UUID orderId,
            long amountCents,
            String status,
            Instant capturedAt) {

        static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getAmountCents(),
                    p.getStatus().name(), p.getCapturedAt());
        }
    }

    public record CatalogItem(String sku, String name, long unitPriceCents, String currency, int stock) {
    }

    public record ApiError(String code, String message) {
    }
}
