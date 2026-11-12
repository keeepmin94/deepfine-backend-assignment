package com.deepfine.inventory.api;

import com.deepfine.inventory.core.MovementType;
import com.deepfine.inventory.core.StockMovementResult;
import java.time.Instant;

public record MovementResponse(
        long movementId,
        String productCode,
        MovementType movementType,
        long quantity,
        long quantityAfter,
        Instant createdAt
) {

    public static MovementResponse from(StockMovementResult result) {
        return new MovementResponse(result.movementId(), result.productCode(), result.movementType(),
                result.quantity(), result.quantityAfter(), result.createdAt());
    }
}
