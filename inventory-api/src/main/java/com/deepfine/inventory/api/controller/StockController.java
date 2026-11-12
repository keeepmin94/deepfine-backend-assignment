package com.deepfine.inventory.api.controller;

import com.deepfine.inventory.api.dto.request.InboundRequest;
import com.deepfine.inventory.api.dto.request.OutboundRequest;
import com.deepfine.inventory.api.dto.response.MovementResponse;
import com.deepfine.inventory.core.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;

    @PostMapping("/inbounds")
    public ResponseEntity<MovementResponse> inbound(@Valid @RequestBody InboundRequest request) {
        var result = stockService.inbound(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(MovementResponse.from(result));
    }

    @PostMapping("/outbounds")
    public ResponseEntity<MovementResponse> outbound(@Valid @RequestBody OutboundRequest request) {
        var result = stockService.outbound(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(MovementResponse.from(result));
    }
}
