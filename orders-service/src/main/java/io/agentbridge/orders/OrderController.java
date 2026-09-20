package io.agentbridge.orders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "orders", description = "Create, inspect and pay for orders")
class OrderController {

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create an order",
            description = "Mutating. Send an Idempotency-Key so a retried call does not create a second order.")
    ResponseEntity<Dtos.OrderResponse> create(
            @Valid @RequestBody Dtos.CreateOrderRequest request,
            @Parameter(description = "Replay-safe key, echoed by the caller on retry")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var order = service.createOrder(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.OrderResponse.of(order));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order by id")
    Dtos.OrderResponse get(@PathVariable UUID id) {
        return Dtos.OrderResponse.of(service.getOrder(id));
    }

    @GetMapping
    @Operation(summary = "List orders, optionally filtered by customer or status")
    List<Dtos.OrderResponse> list(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) Order.Status status) {
        return service.listOrders(customerId, status).stream().map(Dtos.OrderResponse::of).toList();
    }

    @PostMapping("/{id}/payments")
    @Operation(summary = "Capture payment for an order",
            description = "Mutating and money-moving. Requires an Idempotency-Key in production profiles.")
    ResponseEntity<Dtos.PaymentResponse> pay(
            @PathVariable UUID id,
            @Valid @RequestBody Dtos.CapturePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var payment = service.capturePayment(id, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.PaymentResponse.of(payment));
    }

    @GetMapping("/{id}/payments")
    @Operation(summary = "List payments captured against an order")
    List<Dtos.PaymentResponse> payments(@PathVariable UUID id) {
        return service.paymentsOf(id).stream().map(Dtos.PaymentResponse::of).toList();
    }
}
