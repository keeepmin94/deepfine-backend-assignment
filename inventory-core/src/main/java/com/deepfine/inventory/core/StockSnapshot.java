package com.deepfine.inventory.core;

import java.time.Instant;

public record StockSnapshot(long productId, String productCode, String name, Long quantity, Instant updatedAt) {
}
