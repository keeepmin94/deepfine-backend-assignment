package com.deepfine.inventory.api;

import com.deepfine.inventory.core.InboundCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InboundRequest(@NotNull String productCode, @NotNull String name, @NotNull @Positive Long quantity) {

    public InboundCommand toCommand() {
        return new InboundCommand(productCode, name, quantity);
    }
}
