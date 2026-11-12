package com.deepfine.inventory.api.controller;

import com.deepfine.inventory.api.dto.response.StockResponse;
import com.deepfine.inventory.core.service.InventoryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final InventoryQueryService queryService;

    public ProductController(InventoryQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{productCode}/stock")
    public StockResponse getStock(@PathVariable String productCode) {
        return StockResponse.from(queryService.getStock(productCode));
    }
}
