package com.deepfine.inventory.api.exception;

import com.deepfine.inventory.core.enums.StockErrorCode;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseFailureClassifierTest {

    @ParameterizedTest
    @CsvSource({"55P03, STOCK_BUSY", "40P01, TRANSACTION_RETRY_REQUIRED"})
    void classifiesOnlyRecognizedSqlStatesThroughWrapping(String sqlState, StockErrorCode expected) {
        var exception = new RuntimeException("Spring wrapper", new RuntimeException("JPA wrapper",
                new SQLException("localized database message", sqlState)));
        assertThat(DatabaseFailureClassifier.classify(exception)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"23505", "23514", "08006", "40001", "25P02", "P0001"})
    void otherDatabaseFailuresRemainInternalErrors(String sqlState) {
        assertThat(DatabaseFailureClassifier.classify(new SQLException("not retryable by this contract", sqlState)))
                .isEqualTo(StockErrorCode.INTERNAL_ERROR);
    }

    @Test
    void followsJdbcNextExceptionLinks() {
        var exception = new SQLException("batch wrapper");
        exception.setNextException(new SQLException("deadlock", "40P01"));
        assertThat(DatabaseFailureClassifier.classify(exception)).isEqualTo(StockErrorCode.TRANSACTION_RETRY_REQUIRED);
    }

    @Test
    void ordinaryExceptionsRemainInternalErrors() {
        assertThat(DatabaseFailureClassifier.classify(new IllegalStateException("internal failure")))
                .isEqualTo(StockErrorCode.INTERNAL_ERROR);
    }
}
