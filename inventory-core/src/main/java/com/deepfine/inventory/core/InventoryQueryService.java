package com.deepfine.inventory.core;

import org.springframework.stereotype.Service;

@Service
public class InventoryQueryService {

    private final ProductRepository productRepository;

    public InventoryQueryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public StockSnapshot getStock(String productCode) {
        ProductCodeValidator.requireValid(productCode);
        StockSnapshot snapshot = productRepository.findStockSnapshot(productCode)
                .orElseThrow(() -> new StockException(StockErrorCode.PRODUCT_NOT_FOUND));
        if (snapshot.quantity() == null || snapshot.updatedAt() == null) {
            throw new StockException(StockErrorCode.INTERNAL_ERROR);
        }
        return snapshot;
    }
}
