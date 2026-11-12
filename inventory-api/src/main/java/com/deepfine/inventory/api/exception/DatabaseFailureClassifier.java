package com.deepfine.inventory.api.exception;

import com.deepfine.inventory.core.enums.StockErrorCode;
import java.sql.SQLException;

final class DatabaseFailureClassifier {

    private DatabaseFailureClassifier() {
    }

    static StockErrorCode classify(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                for (SQLException current = sqlException; current != null; current = current.getNextException()) {
                    if ("55P03".equals(current.getSQLState())) {
                        return StockErrorCode.STOCK_BUSY;
                    }
                    if ("40P01".equals(current.getSQLState())) {
                        return StockErrorCode.TRANSACTION_RETRY_REQUIRED;
                    }
                }
            }
        }
        return StockErrorCode.INTERNAL_ERROR;
    }
}
