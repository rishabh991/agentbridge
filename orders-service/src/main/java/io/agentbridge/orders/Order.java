package io.agentbridge.orders;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    public enum Status { PENDING, PAID, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "sku", nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Order() {
    }

    public Order(UUID id, String customerId, String sku, int quantity, long amountCents, String currency) {
        this.id = id;
        this.customerId = customerId;
        this.sku = sku;
        this.quantity = quantity;
        this.amountCents = amountCents;
        this.currency = currency;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void markPaid() { this.status = Status.PAID; }
    public void cancel() { this.status = Status.CANCELLED; }
}
