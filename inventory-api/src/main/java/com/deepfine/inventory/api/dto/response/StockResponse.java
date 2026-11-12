package com.deepfine.inventory.api.dto.response;

import com.deepfine.inventory.core.dto.result.StockSnapshot;
import java.time.Instant;

public record StockResponse(long productId, String productCode, String name, long quantity, Instant updatedAt) {

    public static StockResponse from(StockSnapshot snapshot) {
        return new StockResponse(snapshot.productId(), snapshot.productCode(), snapshot.name(),
                snapshot.quantity(), snapshot.updatedAt());
    }
}
