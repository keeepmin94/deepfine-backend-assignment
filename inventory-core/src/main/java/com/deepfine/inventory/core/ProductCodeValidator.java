package com.deepfine.inventory.core;

import java.util.regex.Pattern;

public final class ProductCodeValidator {

    private static final Pattern PRODUCT_CODE = Pattern.compile("[A-Z0-9_-]{1,64}");

    private ProductCodeValidator() {
    }

    public static void requireValid(String productCode) {
        if (productCode == null || !PRODUCT_CODE.matcher(productCode).matches()) {
            throw new StockException(StockErrorCode.INVALID_REQUEST);
        }
    }
}
