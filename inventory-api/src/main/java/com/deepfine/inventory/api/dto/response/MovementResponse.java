package com.deepfine.inventory.api.dto.response;

import com.deepfine.inventory.core.dto.result.StockMovementResult;
import com.deepfine.inventory.core.enums.MovementType;
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
