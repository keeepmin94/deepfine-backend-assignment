package com.deepfine.inventory.core.dto.command;

import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundCommandTest {

    @Test
    void acceptsValidCodeAndQuantityBoundaries() {
        assertThat(new OutboundCommand("A_1-2", 1).quantity()).isEqualTo(1);
        assertThat(new OutboundCommand("A".repeat(64), Long.MAX_VALUE).quantity()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"product-a", " A", "A ", "한글", "A.B", "A/B", "A\n"})
    void rejectsInvalidCodes(String code) {
        assertInvalid(() -> new OutboundCommand(code, 1));
    }

    @Test
    void rejectsCodeLongerThan64Characters() {
        assertInvalid(() -> new OutboundCommand("A".repeat(65), 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonpositiveQuantities(long quantity) {
        assertInvalid(() -> new OutboundCommand("A", quantity));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StockException.class,
                exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INVALID_REQUEST));
    }
}
