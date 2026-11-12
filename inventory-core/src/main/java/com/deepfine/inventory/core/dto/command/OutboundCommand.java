package com.deepfine.inventory.core.dto.command;

import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import com.deepfine.inventory.core.validator.ProductCodeValidator;

public record OutboundCommand(String productCode, long quantity) {

    public OutboundCommand {
        ProductCodeValidator.requireValid(productCode);
        if (quantity <= 0) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
    }
}
