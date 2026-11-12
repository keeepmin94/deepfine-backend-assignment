package com.deepfine.inventory.core;

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
}
