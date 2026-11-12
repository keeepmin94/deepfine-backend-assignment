package com.deepfine.inventory.api.dto.response;

public record ApiError(String code, String message, String path, String requestId) {
}
