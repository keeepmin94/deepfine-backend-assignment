package com.deepfine.inventory.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 10, updatable = false)
    private MovementType movementType;

    @Column(nullable = false, updatable = false)
    private long quantity;

    @Column(name = "quantity_after", nullable = false, updatable = false)
    private long quantityAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    static StockMovement inbound(long productId, long quantity, long quantityAfter) {
        var movement = new StockMovement();
        movement.productId = productId;
        movement.movementType = MovementType.INBOUND;
        movement.quantity = quantity;
        movement.quantityAfter = quantityAfter;
        movement.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return movement;
    }

    public Long getId() {
        return id;
    }

    public MovementType getMovementType() {
        return movementType;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getQuantityAfter() {
        return quantityAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
