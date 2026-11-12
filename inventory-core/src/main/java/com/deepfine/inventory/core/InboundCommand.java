package com.deepfine.inventory.core;

import java.util.regex.Pattern;

public record InboundCommand(String productCode, String name, long quantity) {

    private static final Pattern PRODUCT_CODE = Pattern.compile("[A-Z0-9_-]{1,64}");

    public InboundCommand {
        if (productCode == null || !PRODUCT_CODE.matcher(productCode).matches() || name == null || quantity <= 0) {
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
