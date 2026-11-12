package com.deepfine.inventory.core;

public enum StockErrorCode {
    INVALID_REQUEST("잘못된 요청입니다."),
    PRODUCT_NAME_MISMATCH("등록된 상품명과 일치하지 않습니다."),
    STOCK_LIMIT_EXCEEDED("허용 가능한 재고 수량을 초과합니다."),
    INTERNAL_ERROR("요청을 처리하는 중 오류가 발생했습니다.");

    private final String message;

    StockErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
