package com.deepfine.inventory.core.dto.result;

import com.deepfine.inventory.core.enums.MovementType;
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
