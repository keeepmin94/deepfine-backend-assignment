package com.deepfine.inventory.core.service;

import com.deepfine.inventory.core.dto.command.InboundCommand;
import com.deepfine.inventory.core.dto.command.OutboundCommand;
import com.deepfine.inventory.core.dto.result.StockMovementResult;
import com.deepfine.inventory.core.entity.Inventory;
import com.deepfine.inventory.core.entity.Product;
import com.deepfine.inventory.core.entity.StockMovement;
import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import com.deepfine.inventory.core.repository.InventoryRepository;
import com.deepfine.inventory.core.repository.PostgresTransactionRepository;
import com.deepfine.inventory.core.repository.ProductRepository;
import com.deepfine.inventory.core.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final StockMovementRepository movementRepository;
    private final PostgresTransactionRepository transactionRepository;

    @Transactional
    public StockMovementResult inbound(InboundCommand command) {
        transactionRepository.applyLockTimeout();
        Product product = productRepository.findByProductCode(command.productCode()).orElse(null);
        if (product == null) {
            productRepository.insertIfAbsent(command.productCode(), command.name());
            product = productRepository.findByProductCode(command.productCode())
                    .orElseThrow(() -> new StockException(StockErrorCode.INTERNAL_ERROR));
            requireMatchingName(product, command.name());
            inventoryRepository.insertZeroIfAbsent(product.getId());
        } else {
            requireMatchingName(product, command.name());
        }

        Inventory inventory = inventoryRepository.findForUpdate(product.getId())
                .orElseThrow(() -> new StockException(StockErrorCode.INTERNAL_ERROR));
        inventory.inbound(command.quantity());
        StockMovement movement = movementRepository.save(
                StockMovement.inbound(product.getId(), command.quantity(), inventory.getQuantity()));
        return new StockMovementResult(movement.getId(), product.getProductCode(), movement.getMovementType(),
                movement.getQuantity(), movement.getQuantityAfter(), movement.getCreatedAt());
    }

    @Transactional
    public StockMovementResult outbound(OutboundCommand command) {
        transactionRepository.applyLockTimeout();
        Product product = productRepository.findByProductCode(command.productCode())
                .orElseThrow(() -> new StockException(StockErrorCode.PRODUCT_NOT_FOUND));
        Inventory inventory = inventoryRepository.findForUpdate(product.getId())
                .orElseThrow(() -> new StockException(StockErrorCode.INTERNAL_ERROR));
        inventory.outbound(command.quantity());
        StockMovement movement = movementRepository.save(
                StockMovement.outbound(product.getId(), command.quantity(), inventory.getQuantity()));
        return new StockMovementResult(movement.getId(), product.getProductCode(), movement.getMovementType(),
                movement.getQuantity(), movement.getQuantityAfter(), movement.getCreatedAt());
    }

    private void requireMatchingName(Product product, String name) {
        if (!product.getName().equals(name)) {
            throw new StockException(StockErrorCode.PRODUCT_NAME_MISMATCH);
        }
    }
}
