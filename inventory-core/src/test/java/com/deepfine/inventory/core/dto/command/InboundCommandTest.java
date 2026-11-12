package com.deepfine.inventory.core.dto.command;

import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundCommandTest {

    @Test
    void trimsNamesAndAcceptsCodeAndQuantityBoundaries() {
        var command = new InboundCommand("A".repeat(64), "\u2003 상품 A \u2003", Long.MAX_VALUE);
        assertThat(command.name()).isEqualTo("상품 A");
        assertThat(command.productCode()).isEqualTo("A".repeat(64));
        assertThat(new InboundCommand("A_1-2", "상품", 1).quantity()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"product-a", " A", "A ", "한글", "A.B", "A/B", "A\n"})
    void rejectsInvalidCodes(String code) {
        assertInvalid(() -> new InboundCommand(code, "상품", 1));
    }

    @Test
    void rejectsCodeLongerThan64Characters() {
        assertInvalid(() -> new InboundCommand("A".repeat(65), "상품", 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003", "상품\u0000", "\uD800", "\uDC00"})
    void rejectsInvalidNames(String name) {
        assertInvalid(() -> new InboundCommand("A", name, 1));
    }

    @Test
    void nameLengthCountsCodePointsRatherThanUtf16Units() {
        assertThat(new InboundCommand("A", "😀".repeat(200), 1).name()).hasSize(400);
        assertThat(new InboundCommand("A", "가".repeat(200), 1).name()).hasSize(200);
        assertInvalid(() -> new InboundCommand("A", "😀".repeat(201), 1));
        assertInvalid(() -> new InboundCommand("A", "가".repeat(201), 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonpositiveQuantities(long quantity) {
        assertInvalid(() -> new InboundCommand("A", "상품", quantity));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(StockException.class,
                exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INVALID_REQUEST));
    }
}
