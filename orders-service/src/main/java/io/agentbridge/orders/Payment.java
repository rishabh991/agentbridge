package io.agentbridge.orders;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { CAPTURED, DECLINED }

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected Payment() {
    }

    public Payment(UUID id, UUID orderId, long amountCents, Status status) {
        this.id = id;
        this.orderId = orderId;
        this.amountCents = amountCents;
        this.status = status;
        this.capturedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public long getAmountCents() { return amountCents; }
    public Status getStatus() { return status; }
    public Instant getCapturedAt() { return capturedAt; }
}
