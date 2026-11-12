package com.deepfine.inventory.core.service;

import com.deepfine.inventory.core.dto.result.StockSnapshot;
import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import com.deepfine.inventory.core.repository.ProductRepository;
import com.deepfine.inventory.core.validator.ProductCodeValidator;
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
