package com.deepfine.inventory.core.entity;

import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inventory() {
    }

    public Inventory(long productId) {
        this.productId = productId;
        this.updatedAt = Instant.now();
    }

    public void inbound(long quantity) {
        if (quantity <= 0) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
        if (quantity > Long.MAX_VALUE - this.quantity) {
            throw new StockException(StockErrorCode.STOCK_LIMIT_EXCEEDED);
        }
        this.quantity += quantity;
        this.updatedAt = Instant.now();
    }

    public long getQuantity() {
        return quantity;
    }

    public void outbound(long quantity) {
        if (quantity <= 0) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
        if (quantity > this.quantity) {
            throw new StockException(StockErrorCode.INSUFFICIENT_STOCK);
        }
        this.quantity -= quantity;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
