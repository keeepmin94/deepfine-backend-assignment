package com.deepfine.inventory.api;

import com.deepfine.inventory.core.OutboundCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OutboundRequest(@NotNull String productCode, @NotNull @Positive Long quantity) {

    public OutboundCommand toCommand() {
        return new OutboundCommand(productCode, quantity);
    }
}
