package com.deepfine.inventory.core;

public record OutboundCommand(String productCode, long quantity) {

    public OutboundCommand {
        ProductCodeValidator.requireValid(productCode);
        if (quantity <= 0) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
    }
}
