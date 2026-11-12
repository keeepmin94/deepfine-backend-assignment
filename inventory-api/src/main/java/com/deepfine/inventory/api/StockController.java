package com.deepfine.inventory.api;

import com.deepfine.inventory.core.StockService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/inbounds")
    public ResponseEntity<MovementResponse> inbound(@Valid @RequestBody InboundRequest request) {
        var result = stockService.inbound(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(MovementResponse.from(result));
    }
}
