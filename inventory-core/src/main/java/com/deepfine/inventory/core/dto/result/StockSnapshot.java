package com.deepfine.inventory.core.dto.result;

import java.time.Instant;

public record StockSnapshot(long productId, String productCode, String name, Long quantity, Instant updatedAt) {
}
