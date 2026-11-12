package com.deepfine.inventory.api.dto.request;

import com.deepfine.inventory.core.dto.command.InboundCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InboundRequest(@NotNull String productCode, @NotNull String name, @NotNull @Positive Long quantity) {

    public InboundCommand toCommand() {
        return new InboundCommand(productCode, name, quantity);
    }
}
