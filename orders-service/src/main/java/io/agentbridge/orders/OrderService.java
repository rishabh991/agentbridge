package io.agentbridge.orders;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final IdempotencyRepository idempotency;
    private final Catalog catalog;

    OrderService(OrderRepository orders, PaymentRepository payments,
                 IdempotencyRepository idempotency, Catalog catalog) {
        this.orders = orders;
        this.payments = payments;
        this.idempotency = idempotency;
        this.catalog = catalog;
    }

    @Transactional
    public Order createOrder(Dtos.CreateOrderRequest request, String idempotencyKey) {
        var item = catalog.bySku(request.sku())
                .orElseThrow(() -> new NotFoundException("unknown_sku", "No catalogue item with SKU " + request.sku()));
        if (item.stock() < request.quantity()) {
            throw new ConflictException("insufficient_stock",
                    "Only " + item.stock() + " units of " + item.sku() + " are available");
        }

        var fingerprint = fingerprint(request.customerId(), request.sku(), String.valueOf(request.quantity()));
        var replay = replayOf(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return orders.findById(replay.get()).orElseThrow();
        }

        var order = new Order(UUID.randomUUID(), request.customerId(), item.sku(), request.quantity(),
                item.unitPriceCents() * request.quantity(), item.currency());
        orders.save(order);
        record(idempotencyKey, fingerprint, order.getId());
        return order;
    }

    @Transactional
    public Payment capturePayment(UUID orderId, Dtos.CapturePaymentRequest request, String idempotencyKey) {
        var order = orders.findById(orderId)
                .orElseThrow(() -> new NotFoundException("unknown_order", "No order " + orderId));

        var fingerprint = fingerprint(orderId.toString(), String.valueOf(request.amountCents()));
        var replay = replayOf(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return payments.findById(replay.get()).orElseThrow();
        }

        if (order.getStatus() == Order.Status.PAID) {
            throw new ConflictException("already_paid", "Order " + orderId + " is already paid");
        }
        if (order.getStatus() == Order.Status.CANCELLED) {
            throw new ConflictException("order_cancelled", "Order " + orderId + " is cancelled");
        }
        if (request.amountCents() != order.getAmountCents()) {
            throw new ConflictException("amount_mismatch",
                    "Order total is " + order.getAmountCents() + " cents, not " + request.amountCents());
        }

        var payment = new Payment(UUID.randomUUID(), orderId, request.amountCents(), Payment.Status.CAPTURED);
        payments.save(payment);
        order.markPaid();
        record(idempotencyKey, fingerprint, payment.getId());
        return payment;
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID id) {
        return orders.findById(id)
                .orElseThrow(() -> new NotFoundException("unknown_order", "No order " + id));
    }

    @Transactional(readOnly = true)
    public List<Order> listOrders(String customerId, Order.Status status) {
        if (customerId != null && !customerId.isBlank()) {
            return orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
        }
        if (status != null) {
            return orders.findByStatusOrderByCreatedAtDesc(status);
        }
        return orders.findAll();
    }

    @Transactional(readOnly = true)
    public List<Payment> paymentsOf(UUID orderId) {
        return payments.findByOrderId(orderId);
    }

    /**
     * Returns the id created by an earlier call with this key, or empty if the key
     * is new. A key replayed with a different body is a client bug, not a retry.
     */
    private Optional<UUID> replayOf(String idempotencyKey, String fingerprint) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return idempotency.findByKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("idempotency_key_reuse",
                        "Idempotency-Key " + idempotencyKey + " was already used with a different request body");
            }
            return existing.getResourceId();
        });
    }

    private void record(String idempotencyKey, String fingerprint, UUID resourceId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotency.save(new IdempotencyRecord(idempotencyKey, fingerprint, resourceId));
        }
    }

    private static String fingerprint(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
