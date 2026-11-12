package com.deepfine.inventory.core;

import java.time.Instant;

public record StockMovementResult(
        long movementId,
        String productCode,
        MovementType movementType,
        long quantity,
        long quantityAfter,
        Instant createdAt
) {
}
