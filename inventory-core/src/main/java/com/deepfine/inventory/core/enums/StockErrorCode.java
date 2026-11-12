package com.deepfine.inventory.core.enums;

public enum StockErrorCode {
    INVALID_REQUEST("잘못된 요청입니다."),
    PRODUCT_NOT_FOUND("상품을 찾을 수 없습니다."),
    PRODUCT_NAME_MISMATCH("등록된 상품명과 일치하지 않습니다."),
    INSUFFICIENT_STOCK("출고 가능한 재고가 부족합니다."),
    STOCK_LIMIT_EXCEEDED("허용 가능한 재고 수량을 초과합니다."),
    STOCK_BUSY("재고가 사용 중입니다. 잠시 후 다시 요청해 주세요."),
    TRANSACTION_RETRY_REQUIRED("트랜잭션이 중단되었습니다. 처리 결과를 확인한 뒤 다시 요청해 주세요."),
    INTERNAL_ERROR("요청을 처리하는 중 오류가 발생했습니다.");

    private final String message;

    StockErrorCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
