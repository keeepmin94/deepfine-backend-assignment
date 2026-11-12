package com.deepfine.inventory.core;

public class StockException extends RuntimeException {

    private final StockErrorCode code;

    public StockException(StockErrorCode code) {
        super(code.message());
        this.code = code;
    }

    public StockErrorCode code() {
        return code;
    }
}
