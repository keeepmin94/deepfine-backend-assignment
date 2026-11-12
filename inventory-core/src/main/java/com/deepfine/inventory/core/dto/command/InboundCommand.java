package com.deepfine.inventory.core.dto.command;

import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import com.deepfine.inventory.core.validator.ProductCodeValidator;

public record InboundCommand(String productCode, String name, long quantity) {

    public InboundCommand {
        ProductCodeValidator.requireValid(productCode);
        if (name == null || quantity <= 0) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
        name = name.strip();
        int length = name.codePointCount(0, name.length());
        boolean invalidCharacter = name.codePoints()
                .anyMatch(codePoint -> codePoint == 0 || codePoint >= 0xD800 && codePoint <= 0xDFFF);
        if (length < 1 || length > 200 || invalidCharacter) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
    }
}
