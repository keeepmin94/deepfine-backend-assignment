package com.deepfine.inventory.core.entity;

import com.deepfine.inventory.core.enums.StockErrorCode;
import com.deepfine.inventory.core.exception.StockException;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    @Test
    void inboundAccumulatesQuantity() {
        var inventory = new Inventory(1);
        inventory.inbound(10);
        inventory.inbound(3);
        assertThat(inventory.getQuantity()).isEqualTo(13);
        assertThat(inventory.getUpdatedAt()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidQuantityLeavesStateUnchanged(long quantity) {
        var inventory = new Inventory(1);
        inventory.inbound(10);
        var updatedAt = inventory.getUpdatedAt();
        assertThatThrownBy(() -> inventory.inbound(quantity))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INVALID_REQUEST));
        assertThat(inventory.getQuantity()).isEqualTo(10);
        assertThat(inventory.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void longMaxIsAllowedButOverflowLeavesStateUnchanged() {
        var inventory = new Inventory(1);
        inventory.inbound(Long.MAX_VALUE - 1);
        inventory.inbound(1);
        var updatedAt = inventory.getUpdatedAt();
        assertThatThrownBy(() -> inventory.inbound(1))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.STOCK_LIMIT_EXCEEDED));
        assertThat(inventory.getQuantity()).isEqualTo(Long.MAX_VALUE);
        assertThat(inventory.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void outboundCanPartiallyOrFullyConsumeStock() {
        var inventory = new Inventory(1);
        inventory.inbound(10);
        inventory.outbound(3);
        assertThat(inventory.getQuantity()).isEqualTo(7);
        inventory.outbound(7);
        assertThat(inventory.getQuantity()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidOutboundDoesNotChangeQuantityOrTimestamp(long quantity) {
        var inventory = new Inventory(1);
        inventory.inbound(10);
        var updatedAt = inventory.getUpdatedAt();
        assertThatThrownBy(() -> inventory.outbound(quantity))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INVALID_REQUEST));
        assertThat(inventory.getQuantity()).isEqualTo(10);
        assertThat(inventory.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void insufficientStockDoesNotChangeQuantityOrTimestamp() {
        var inventory = new Inventory(1);
        inventory.inbound(10);
        var updatedAt = inventory.getUpdatedAt();
        assertThatThrownBy(() -> inventory.outbound(11))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INSUFFICIENT_STOCK));
        assertThat(inventory.getQuantity()).isEqualTo(10);
        assertThat(inventory.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void emptyInventoryRejectsOutbound() {
        var inventory = new Inventory(1);
        assertThatThrownBy(() -> inventory.outbound(1))
                .isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(StockErrorCode.INSUFFICIENT_STOCK));
        assertThat(inventory.getQuantity()).isZero();
    }

    @Test
    void longMaxCanBeFullyConsumed() {
        var inventory = new Inventory(1);
        inventory.inbound(Long.MAX_VALUE);
        inventory.outbound(Long.MAX_VALUE);
        assertThat(inventory.getQuantity()).isZero();
    }

    @Test
    void mixedBoundaryOperationsMatchAnIndependentBigIntegerModel() {
        var inventory = new Inventory(1);
        var random = new Random(20261112L);
        var limit = BigInteger.valueOf(Long.MAX_VALUE);
        var expected = BigInteger.ZERO;
        var rejections = EnumSet.noneOf(StockErrorCode.class);
        long[] quantities = {0, -1, Long.MIN_VALUE, 1, 2, 3, 100, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        int successes = 0;
        for (int step = 0; step < 256; step++) {
            long quantity = quantities[random.nextInt(quantities.length)];
            boolean inbound = random.nextBoolean();
            var delta = BigInteger.valueOf(quantity);
            var candidate = inbound ? expected.add(delta) : expected.subtract(delta);
            StockErrorCode rejection = quantity <= 0 ? StockErrorCode.INVALID_REQUEST
                    : candidate.signum() < 0 ? StockErrorCode.INSUFFICIENT_STOCK
                    : candidate.compareTo(limit) > 0 ? StockErrorCode.STOCK_LIMIT_EXCEEDED : null;
            var updatedAt = inventory.getUpdatedAt();
            Runnable operation = inbound ? () -> inventory.inbound(quantity) : () -> inventory.outbound(quantity);
            if (rejection == null) {
                operation.run();
                expected = candidate;
                successes++;
            } else {
                assertThatThrownBy(operation::run).isInstanceOfSatisfying(StockException.class,
                        exception -> assertThat(exception.code()).isEqualTo(rejection));
                assertThat(inventory.getUpdatedAt()).isEqualTo(updatedAt);
                rejections.add(rejection);
            }
            assertThat(inventory.getQuantity()).isEqualTo(expected.longValueExact());
        }
        assertThat(successes).isPositive();
        assertThat(rejections).containsExactlyInAnyOrder(StockErrorCode.INVALID_REQUEST,
                StockErrorCode.INSUFFICIENT_STOCK, StockErrorCode.STOCK_LIMIT_EXCEEDED);
    }
}
